import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createServer } from 'node:net'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { mkdir } from 'node:fs/promises'
import { markdownSignature } from '../utils/markdown.ts'

// 使用已经完成的生产构建；不启动后端、不使用真实账号、不调用博客写入接口。
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const frontend = fileURLToPath(new URL('../../', import.meta.url))
const output = process.env.SHOWCASE_OUTPUT_DIR || resolve(frontend, '../.local-backups/design-showcase')
const children = []
const externalUrl = process.env.EXTERNAL_ACCEPTANCE_URL
let browser, baseUrl, closedUrl
const browserErrors = [], hydrationErrors = [], apiRequests = []

async function serve(enabled) {
  const probe = createServer()
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve))
  const port = probe.address().port
  await new Promise(resolve => probe.close(resolve))
  const env = { ...process.env, HOST: '127.0.0.1', PORT: String(port), NITRO_HOST: '127.0.0.1', NITRO_PORT: String(port), NUXT_PUBLIC_DESIGN_SHOWCASE_ENVIRONMENT: enabled ? 'test' : '' }
  delete env.NODE_TEST_CONTEXT
  const child = spawn(process.execPath, ['.output/server/index.mjs'], { cwd: frontend, windowsHide: true, env, stdio: ['ignore', 'pipe', 'pipe'] })
  children.push(child)
  return new Promise((resolve, reject) => {
    let log = ''
    const timer = setTimeout(() => reject(new Error('预览启动超时: ' + log)), 15000)
    function read(chunk) {
      log += chunk
      const url = log.match(/Listening on (http:\/\/127\.0\.0\.1:\d+)/)?.[1]
      if (url) { clearTimeout(timer); resolve(url) }
    }
    child.stdout.on('data', read); child.stderr.on('data', read)
    child.once('exit', code => { clearTimeout(timer); reject(new Error('预览退出: ' + code + log)) })
  })
}
before(async () => {
  await mkdir(output, { recursive: true })
  baseUrl = externalUrl || await serve(true)
  closedUrl = externalUrl ? null : await serve(false)
  browser = await chromium.launch({ headless: process.env.BROWSER_HEADED !== '1', ...(process.env.BROWSER_HEADED === '1' ? { args: ['--window-position=-32000,-32000'] } : {}), ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
}, { timeout: 40000 })
after(async () => {
  if (browser) await browser.close()
  for (const child of children) child.kill()
  assert.deepEqual(browserErrors, [], '无未处理浏览器异常')
  assert.deepEqual(hydrationErrors, [], '无 SSR 水合警告')
  assert.deepEqual(apiRequests, [], '样板页不访问业务 API')
})
async function contextFor(viewport = { width: 1440, height: 1000 }) {
  const context = await browser.newContext({ viewport, permissions: ['clipboard-read', 'clipboard-write'], reducedMotion: 'reduce' })
  await context.addCookies([{ name: 'portfolio-theme', value: 'dark', url: baseUrl }])
  await context.addInitScript(() => localStorage.setItem('portfolio-theme', 'dark'))
  const page = await context.newPage()
  page.on('pageerror', error => browserErrors.push(error.message))
  page.on('console', message => { if (/hydration/i.test(message.text())) hydrationErrors.push(message.text()) })
  page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/v1/')) apiRequests.push(request.url()) })
  await page.goto(baseUrl + '/design-showcase')
  await page.getByRole('textbox', { name: '富文本样板正文', exact: true }).waitFor()
  return { context, page }
}
const globalTheme = page => page.evaluate(() => ({
  cookie: document.cookie, saved: localStorage.getItem('portfolio-theme'),
  theme: document.documentElement.dataset.theme, class: document.documentElement.className,
  background: document.documentElement.style.backgroundColor,
  body: getComputedStyle(document.body).backgroundColor,
}))
async function checkWidth(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, '页面无横向溢出')
}

test('production defaults to 404', { skip: externalUrl ? '外部验收服务已开放 Showcase，不改变现有服务配置' : false }, async () => {
  const denied = await fetch(closedUrl + '/design-showcase')
  assert.equal(denied.status, 404)
  assert.equal((await fetch(closedUrl + '/__visual-regression')).status, 404, '交互测试页不进入生产路由')
})
test('test preview provides SSR content and noindex', async () => {
  const allowed = await fetch(baseUrl + '/design-showcase')
  assert.equal(allowed.status, 200)
  assert.equal(allowed.headers.get('x-robots-tag'), 'noindex,nofollow')
  const html = await allowed.text()
  assert.match(html, /让内容成为界面的主角/)
  assert.match(html, /data-showcase-theme="light"/)
  assert.match(html, /name="robots" content="noindex,nofollow"/)
  assert.doesNotMatch(html, /contenteditable="true"/, '编辑器仅在客户端初始化')
})

test('local themes, portals, code controls, form, modal, dropdown, tabs and focus', async () => {
  const { context, page } = await contextFor()
  try {
    const root = page.locator('.design-showcase')
    const original = await globalTheme(page)
    assert.equal(original.theme, 'dark')
    assert.equal(await root.getAttribute('data-showcase-theme'), 'light')
    const background = () => root.evaluate(el => getComputedStyle(el).backgroundColor)
    assert.equal(await background(), 'rgb(245, 247, 246)')
    assert.equal(await root.locator('.text-muted').first().evaluate(el => getComputedStyle(el).color), 'rgb(89, 108, 105)')
    assert.equal(await page.locator('.showcase-article').evaluate(el => getComputedStyle(el).fontSize), '17px')
    await page.getByRole('button', { name: '预览深色主题' }).click()
    assert.equal(await background(), 'rgb(16, 25, 28)')
    assert.deepEqual(await globalTheme(page), original)
    await page.getByRole('button', { name: '预览浅色主题' }).click()
    assert.deepEqual(await globalTheme(page), original)

    await page.getByRole('button', { name: '提交样例', exact: true }).click()
    await page.getByText('请填写样例标题。', { exact: true }).waitFor()
    await page.getByLabel('样例标题', { exact: true }).fill('交互检查')
    await page.getByRole('combobox', { name: '文章分类', exact: true }).click()
    await page.getByRole('option', { name: '设计笔记', exact: true }).click()
    await page.getByLabel('样例摘要', { exact: true }).fill('只在本页编辑。')
    await page.getByRole('button', { name: '提交样例', exact: true }).click()
    await page.getByRole('status').filter({ hasText: '已提交：交互检查' }).waitFor()
    await page.locator('#design-showcase-portals').getByText('样例操作已完成', { exact: true }).waitFor()

    const trigger = page.getByRole('button', { name: '打开对话框', exact: true })
    await trigger.click()
    const dialog = page.locator('#design-showcase-portals [role="dialog"]')
    await dialog.waitFor()
    assert.equal(await dialog.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)')
    for (let i = 0; i < 5; i++) {
      await page.keyboard.press('Tab')
      assert.equal(await dialog.evaluate(el => el.contains(document.activeElement)), true)
    }
    await page.keyboard.press('Escape')
    await dialog.waitFor({ state: 'hidden' })
    await page.getByRole('button', { name: '更多操作', exact: true }).click()
    await page.locator('#design-showcase-portals [role="menu"]').waitFor()
    await page.keyboard.press('ArrowDown')
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('role')), 'menuitem')
    await page.keyboard.press('Escape')
    await page.getByRole('tab', { name: '草稿', exact: true }).click()
    assert.equal(await page.getByRole('tab', { name: '草稿', exact: true }).getAttribute('aria-selected'), 'true')
    assert.equal(await page.getByText('草稿样例', { exact: true }).count(), 3)

    const firstCode = page.locator('.showcase-article .blog-code').first()
    await firstCode.getByRole('combobox').click()
    await page.getByRole('option', { name: 'GitHub Dark', exact: true }).click()
    assert.equal(await firstCode.getAttribute('data-code-theme'), 'dark')
    assert.equal(await page.locator('.showcase-article .blog-code[data-code-theme="follow"]').count(), 8)
    assert.equal(await root.getAttribute('data-showcase-theme'), 'light')
    const copy = page.locator('.showcase-code').first().getByRole('button')
    await page.mouse.move(0, 0)
    assert.equal(await copy.evaluate(el => getComputedStyle(el).opacity), '1', '桌面端复制按钮常驻可见')
    await copy.click()
    assert.match(await page.evaluate(() => navigator.clipboard.readText()), /public record Article/)
    assert.deepEqual(await globalTheme(page), original)
    await checkWidth(page)
    await page.reload()
    assert.equal(await root.getAttribute('data-showcase-theme'), 'light')
    assert.deepEqual(await globalTheme(page), original)
  } finally { await context.close() }
}, { timeout: 60000 })

test('UEditor initializes Markdown and exposes actual formatting, slash menu and source editing', async () => {
  const { context, page } = await contextFor()
  try {
    const editor = page.getByRole('textbox', { name: '富文本样板正文', exact: true })
    assert.equal(await editor.locator('h1').textContent(), '写下值得分享的想法')
    assert.ok(await editor.locator('strong').count())
    async function format(button, selector) {
      // 通过实际源码入口创建一个普通段落，不用 DOM fill 替换含列表和代码的整个富文本树。
      await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).click()
      await page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true }).fill('样板编辑验证')
      await page.getByRole('tab', { name: '富文本', exact: true }).click()
      await editor.locator('p').filter({ hasText: '样板编辑验证' }).waitFor()
      await editor.click()
      await editor.press('ControlOrMeta+A')
      await page.getByRole('button', { name: button, exact: true }).click()
      await editor.locator(selector).filter({ hasText: '样板编辑验证' }).waitFor()
    }
    await format('加粗', 'strong')
    await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).click()
    const source = page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true })
    assert.match(await source.inputValue(), /\*\*样板编辑验证\*\*/)
    await source.fill('## 从源码继续写作\n\n新的段落。')
    await page.getByRole('tab', { name: '富文本', exact: true }).click()
    await editor.locator('h2').filter({ hasText: '从源码继续写作' }).waitFor()
    await format('斜体', 'em')
    await format('无序列表', 'ul li')
    await format('有序列表', 'ol li')
    await format('引用', 'blockquote')
    await format('代码块', 'pre code')
    await format('链接到 Nuxt UI', 'a[href="https://ui.nuxt.com"]')
    await editor.fill('标题验证')
    await editor.press('ControlOrMeta+A')
    await page.getByRole('button', { name: '标题层次', exact: true }).click()
    await page.getByRole('menuitem', { name: '二级标题', exact: true }).click()
    await editor.locator('h2').filter({ hasText: '标题验证' }).waitFor()
    await page.getByRole('button', { name: '重置样例', exact: true }).click()
    await editor.fill('')
    await editor.pressSequentially('/')
    const menuLayout = await layout(page)
    await page.locator('#design-showcase-portals').getByText('标题', { exact: true }).waitFor()
    stable(menuLayout, await layout(page))
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '重置样例', exact: true }).click()
    await checkWidth(page)
  } finally { await context.close() }
}, { timeout: 60000 })

test('desktop and mobile light/dark screenshots, reading and editor, keyboard entry', async () => {
  for (const [name, viewport] of [['desktop', { width: 1440, height: 1000 }], ['mobile', { width: 390, height: 844 }]]) {
    const { context, page } = await contextFor(viewport)
    try {
      for (const theme of ['light', 'dark']) {
        if (theme === 'dark') await page.getByRole('button', { name: '预览深色主题' }).click()
        await checkWidth(page)
        await page.evaluate(() => window.scrollTo(0, 0))
        await page.screenshot({ path: resolve(output, `${theme}-${name}.png`), animations: 'disabled' })
      }
      if (name === 'desktop') {
        // 截取长区域时移除粘性页头的遮挡，仅影响截图，不改变页面交互。
        const regionOptions = { animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' }
        await page.locator('.showcase-article').screenshot({ path: resolve(output, 'markdown-reading.png'), ...regionOptions })
        await page.locator('[data-showcase-editor]').screenshot({ path: resolve(output, 'richtext-editor.png'), ...regionOptions })
      } else {
        await page.getByRole('button', { name: '打开样板页导航' }).click()
        await page.locator('#design-showcase-portals [role="menu"]').waitFor()
        await page.keyboard.press('Escape')
        await page.getByRole('textbox', { name: '富文本样板正文', exact: true }).scrollIntoViewIfNeeded()
        await checkWidth(page)
      }
      await page.reload()
      await page.getByRole('textbox', { name: '富文本样板正文', exact: true }).waitFor()
      await page.keyboard.press('Tab')
      assert.equal(await page.getByRole('link', { name: '跳到主要内容', exact: true }).evaluate(el => el === document.activeElement), true)
      await page.keyboard.press('Enter')
      assert.equal(await page.locator('#showcase-main').evaluate(el => el === document.activeElement), true)
    } finally { await context.close() }
  }
}, { timeout: 60000 })

const layout = page => page.evaluate(() => {
  const rect = document.querySelector('main').getBoundingClientRect()
  return { x: rect.x, width: rect.width, scroll: scrollY, documentWidth: document.documentElement.scrollWidth }
})
function stable(before, after, scroll = true) {
  assert.ok(Math.abs(before.x - after.x) < 0.75, '主容器没有横向位移')
  assert.ok(Math.abs(before.width - after.width) < 0.75, '主容器宽度稳定')
  assert.equal(before.documentWidth, after.documentWidth)
  if (scroll) assert.ok(Math.abs(before.scroll - after.scroll) < 1, '页面滚动位置保持')
}
async function sourceDraft(page, markdown) {
  await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).click()
  await page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true }).fill(markdown)
}
async function visualDraft(page, markdown) {
  await sourceDraft(page, markdown)
  await page.getByRole('tab', { name: '富文本', exact: true }).click()
  await page.getByRole('textbox', { name: '富文本样板正文', exact: true }).waitFor()
}

test('Modal repeated open/close, focus restoration, centered Toast and other popup stability', async () => {
  const { context, page } = await contextFor()
  try {
    const trigger = page.getByRole('button', { name: '打开对话框', exact: true })
    await trigger.scrollIntoViewIfNeeded()
    const before = await layout(page)
    for (let i = 0; i < 5; i++) {
      await trigger.click()
      const dialog = page.getByRole('dialog')
      await dialog.waitFor()
      stable(before, await layout(page))
      assert.equal(await page.evaluate(() => document.body.style.overflow), 'hidden', '仍然锁定背景滚动')
      await page.keyboard.press('Tab')
      assert.equal(await dialog.evaluate(el => el.contains(document.activeElement)), true)
      if (i === 0) await page.screenshot({ path: resolve(output, 'modal-open.png'), animations: 'disabled' })
      await page.keyboard.press('Escape')
      await dialog.waitFor({ state: 'hidden' })
      await page.waitForFunction(() => document.body.style.overflow !== 'hidden')
      stable(before, await layout(page))
      assert.equal(await trigger.evaluate(el => el === document.activeElement), true, '焦点回到触发按钮')
    }
    await page.getByRole('button', { name: '更多操作', exact: true }).click()
    await page.getByRole('menu').waitFor()
    stable(before, await layout(page))
    await page.keyboard.press('Escape')
    await page.getByRole('combobox', { name: '文章分类', exact: true }).scrollIntoViewIfNeeded()
    const selectBefore = await layout(page)
    await page.getByRole('combobox', { name: '文章分类', exact: true }).click()
    await page.getByRole('option', { name: '设计笔记', exact: true }).waitFor()
    stable(selectBefore, await layout(page))
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '显示 Toast', exact: true }).scrollIntoViewIfNeeded()
    const toastBefore = await layout(page)
    for (let i = 0; i < 3; i++) await page.getByRole('button', { name: '显示 Toast', exact: true }).click()
    const toasts = page.locator('#design-showcase-portals [data-state="open"]').filter({ hasText: '样例操作已完成' })
    await page.waitForFunction(() => document.querySelectorAll('[data-slot="viewport"] li').length >= 3)
    const viewport = page.locator('[data-slot="viewport"]').filter({ hasText: '样例操作已完成' })
    assert.equal(await viewport.count(), 1, '只存在一个 Toast 容器')
    const box = await viewport.boundingBox()
    assert.ok(Math.abs(box.x + box.width / 2 - (toastBefore.x + toastBefore.width / 2)) < 1)
    assert.ok(box.y >= 72 && box.y < 120, '顶部居中并避开 Header')
    assert.ok(await toasts.count() >= 3)
    stable(toastBefore, await layout(page))
    await page.screenshot({ path: resolve(output, 'toast-top-center.png'), animations: 'disabled' })
    await checkWidth(page)
  } finally { await context.close() }
}, { timeout: 60000 })

test('resizable editor retains its DOM, draft and selection; keyboard/reset and independent scroll', async () => {
  const { context, page } = await contextFor()
  try {
    const editor = page.getByRole('textbox', { name: '富文本样板正文', exact: true })
    const divider = page.getByRole('separator', { name: '调整编辑与预览宽度', exact: true })
    await page.locator('[data-showcase-editor]').scrollIntoViewIfNeeded()
    await page.locator('[data-editor-preview] h1').waitFor()
    await editor.evaluate(el => { el.dataset.testIdentity = 'same-instance' })
    const panel = page.locator('[data-editor-panel]')
    const preview = page.locator('[data-preview-panel]')
    const original = await panel.boundingBox()
    const pageBefore = await layout(page)
    await page.locator('[data-showcase-editor]').screenshot({ path: resolve(output, 'editor-preview-desktop.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    const handle = await divider.boundingBox()
    await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2)
    await page.mouse.down(); await page.mouse.move(handle.x - 130, handle.y + handle.height / 2, { steps: 15 }); await page.mouse.up()
    assert.ok((await panel.boundingBox()).width < original.width - 100)
    assert.ok((await preview.boundingBox()).width > 300)
    stable(pageBefore, await layout(page))
    assert.equal(await editor.getAttribute('data-test-identity'), 'same-instance')
    assert.equal(await editor.locator('h1').textContent(), '写下值得分享的想法')
    await page.locator('[data-showcase-editor]').screenshot({ path: resolve(output, 'editor-preview-resized.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    await divider.focus()
    const keyboardBefore = (await panel.boundingBox()).width
    await divider.press('ArrowRight')
    assert.ok((await panel.boundingBox()).width > keyboardBefore + 10)
    await divider.dblclick()
    assert.ok(Math.abs((await panel.boundingBox()).width - original.width) < 1)
    await visualDraft(page, '光标与草稿保留')
    await editor.click(); await editor.press('ControlOrMeta+A')
    const textBefore = await editor.textContent()
    const nextHandle = await divider.boundingBox()
    await page.mouse.move(nextHandle.x + 5, nextHandle.y + 100); await page.mouse.down(); await page.mouse.move(nextHandle.x - 50, nextHandle.y + 100, { steps: 8 }); await page.mouse.up()
    await page.getByRole('button', { name: '加粗', exact: true }).click()
    await editor.locator('strong').filter({ hasText: textBefore.trim() }).waitFor()
    await page.locator('[data-editor-preview] strong').filter({ hasText: '光标与草稿保留' }).waitFor()
    const modesBefore = await page.locator('.showcase-workspace').boundingBox()
    await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).click()
    const modesAfter = await page.locator('.showcase-workspace').boundingBox()
    assert.ok(Math.abs(modesBefore.height - modesAfter.height) < 1)
    await page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true }).fill('# 实时更新\n\n' + Array.from({ length: 40 }, (_, i) => '段落 ' + i + '。').join('\n\n'))
    await page.locator('[data-editor-preview] h1').filter({ hasText: '实时更新' }).waitFor()
    const scrollingBefore = await layout(page)
    await page.locator('.showcase-preview-scroll').evaluate(el => { el.scrollTop = 300 })
    assert.equal(await page.locator('.showcase-editor-scroll').evaluate(el => el.scrollTop), 0)
    stable(scrollingBefore, await layout(page))
    await checkWidth(page)
  } finally { await context.close() }
}, { timeout: 60000 })

test('Markdown format roundtrip, editable strike/table and source fallback on loss', async () => {
  const { context, page } = await contextFor()
  try {
    const fixture = '## 往返验证\n\n~~删除线~~ 与 **_嵌套强调_**。\n\n- 一级\n  - 二级\n    - 三级\n\n| 名称 | 格式 |\n| --- | --- |\n| 内容 | Markdown |\n\n```python\nprint("保留语言")\n```\n\n结尾'
    await visualDraft(page, fixture)
    const editor = page.getByRole('textbox', { name: '富文本样板正文', exact: true })
    await editor.locator('s').waitFor()
    assert.equal(await editor.locator('table').count(), 1)
    assert.equal(await editor.locator('ul ul ul').count(), 1)
    await editor.locator('p').filter({ hasText: '结尾' }).last().click()
    await page.keyboard.press('End'); await page.keyboard.type('x'); await page.keyboard.press('Backspace')
    await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).click()
    const source = page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true })
    const roundtrip = await source.inputValue()
    assert.equal(await markdownSignature(roundtrip), await markdownSignature(fixture))
    assert.match(roundtrip, /```python/)
    await visualDraft(page, '删除线操作')
    await editor.click(); await editor.press('ControlOrMeta+A')
    await page.getByRole('button', { name: '删除线', exact: true }).click()
    await editor.locator('s').filter({ hasText: '删除线操作' }).waitFor()
    await page.locator('[data-editor-preview] :is(s, del)').filter({ hasText: '删除线操作' }).waitFor()
    await visualDraft(page, '选区工具栏')
    await editor.click(); await editor.press('ControlOrMeta+A')
    await page.getByRole('button', { name: '选区斜体', exact: true }).click()
    await editor.locator('em').filter({ hasText: '选区工具栏' }).waitFor()
    await page.locator('[data-editor-preview] em').filter({ hasText: '选区工具栏' }).waitFor()
    await visualDraft(page, '表格交互')
    await editor.click(); await editor.press('End')
    await page.getByRole('button', { name: '表格操作', exact: true }).click()
    await page.getByRole('menuitem', { name: '插入表格', exact: true }).click()
    assert.equal(await editor.locator('table tr').count(), 3)
    await editor.locator('td').first().click(); await page.keyboard.type('可编辑表格')
    await page.locator('[data-editor-preview] td').filter({ hasText: '可编辑表格' }).waitFor()
    await page.getByRole('button', { name: '表格操作', exact: true }).click()
    await page.getByRole('menuitem', { name: '增加行', exact: true }).click()
    assert.equal(await editor.locator('table tr').count(), 4)
    for (const supported of ['[链接标题](https://ui.nuxt.com "保留标题")', '```typescript [Roundtrip.ts]\nconst x = 1\n```']) {
      await visualDraft(page, supported)
      const target = editor.locator(supported.startsWith('```') ? 'pre code' : 'p').first()
      await target.click(); await page.keyboard.press('End'); await page.keyboard.type('x'); await page.keyboard.press('Backspace')
      await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).click()
      assert.equal(await markdownSignature(await source.inputValue()), await markdownSignature(supported), '编辑后仍保留链接标题及代码文件名')
    }
    for (const unsupported of ['![不支持的图片](/images/design-showcase.svg)\n\n保持原文。']) {
      await sourceDraft(page, unsupported)
      const preserved = await source.inputValue()
      await page.getByRole('tab', { name: '富文本', exact: true }).click()
      await page.getByRole('alert').filter({ hasText: '已保留完整 Markdown' }).waitFor()
      assert.equal(await source.inputValue(), preserved)
      assert.equal(await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).getAttribute('aria-selected'), 'true')
    }
    await source.fill('<script>window.__previewUnsafe = true</script>\n\n[x](javascript:alert(1))')
    await page.waitForFunction(() => document.querySelector('[data-editor-preview]')?.textContent.includes('previewUnsafe'))
    assert.equal(await page.evaluate(() => window.__previewUnsafe), undefined)
    assert.equal(await page.locator('[data-editor-preview] script, [data-editor-preview] a[href^="javascript:"]').count(), 0)
  } finally { await context.close() }
}, { timeout: 60000 })

test('Shiki per-block palettes keep tokens, code size, horizontal scroll and copy stable', async () => {
  const { context, page } = await contextFor()
  try {
    const blocks = page.locator('.showcase-article .blog-code')
    const java = blocks.first()
    const code = java.locator('pre')
    await code.scrollIntoViewIfNeeded()
    const token = code.locator('span[style]').first()
    await token.evaluate(el => { el.dataset.testIdentity = 'same-token' })
    const originalText = await code.textContent()
    const originalBox = await code.boundingBox()
    const colors = new Set()
    for (const name of ['GitHub Light', 'GitHub Dark', 'Nord', 'Tokyo Night', 'Dracula']) {
      await java.getByRole('combobox').click(); await page.getByRole('option', { name, exact: true }).click()
      colors.add(await token.evaluate(el => getComputedStyle(el).color))
      assert.equal(await token.getAttribute('data-test-identity'), 'same-token')
      assert.equal(await code.textContent(), originalText)
      const box = await code.boundingBox()
      assert.ok(Math.abs(box.height - originalBox.height) < 1 && Math.abs(box.width - originalBox.width) < 1)
      await java.screenshot({ path: resolve(output, 'shiki-' + name.toLowerCase().replaceAll(' ', '-') + '.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    }
    assert.ok(colors.size >= 4, '真实 Token 主题颜色变化')
    assert.equal(await blocks.nth(1).getAttribute('data-code-theme'), 'follow')
    await java.getByRole('button', { name: '复制 ArticleService.java', exact: true }).click()
    // Windows 剪贴板将 LF 规范为 CRLF，正文与空白仍需逐字一致。
    assert.equal((await page.evaluate(() => navigator.clipboard.readText())).replaceAll('\r\n', '\n'), originalText)
    const longCode = blocks.nth(2).locator('pre')
    await longCode.evaluate(el => { el.scrollLeft = 200 })
    await blocks.nth(2).getByRole('combobox').click(); await page.getByRole('option', { name: 'Nord', exact: true }).click()
    assert.ok(await longCode.evaluate(el => el.scrollLeft > 100))
    assert.equal(await blocks.nth(3).locator('.blog-code-toolbar > span[title]').textContent(), 'javascript')
    assert.equal(await blocks.nth(7).locator('.blog-code-toolbar > span[title]').textContent(), '代码')
    await blocks.nth(3).screenshot({ path: resolve(output, 'code-language-only.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    await blocks.nth(7).screenshot({ path: resolve(output, 'code-without-filename.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    await checkWidth(page)
  } finally { await context.close() }
}, { timeout: 60000 })

test('TOC scroll, header clearance, current chapter, rapid navigation, history and reduced motion', async () => {
  const { context, page } = await contextFor()
  try {
    const toc = page.getByRole('navigation', { name: '文章目录', exact: true })
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await page.evaluate(() => {
      window.__scrollBehaviors = []
      const original = window.scrollTo.bind(window)
      window.scrollTo = (...args) => { window.__scrollBehaviors.push(args[0]?.behavior); return original(...args) }
      const intoView = Element.prototype.scrollIntoView
      Element.prototype.scrollIntoView = function (options) { window.__scrollBehaviors.push(options?.behavior); return intoView.call(this, options) }
    })
    const rhythm = toc.getByRole('link', { name: '阅读节奏', exact: true })
    await rhythm.click()
    await page.waitForFunction(() => Math.abs(document.getElementById('阅读节奏').getBoundingClientRect().top - 96) < 2)
    assert.ok(await page.evaluate(() => window.__scrollBehaviors.includes('instant')))
    assert.equal(await page.evaluate(() => window.__scrollBehaviors.includes('smooth')), false)
    assert.equal(await rhythm.getAttribute('aria-current'), 'location')
    await toc.getByRole('link', { name: '结构与层次', exact: true }).click()
    await toc.getByRole('link', { name: '代码展示', exact: true }).click()
    await toc.getByRole('link', { name: '表格与图片', exact: true }).click()
    await page.waitForFunction(() => Math.abs(document.getElementById('表格与图片').getBoundingClientRect().top - 96) < 2)
    assert.equal(await toc.getByRole('link', { name: '表格与图片', exact: true }).getAttribute('aria-current'), 'location')
    await page.goBack()
    assert.ok(decodeURIComponent(page.url()).endsWith('#代码展示'))
    await page.goForward()
    assert.ok(decodeURIComponent(page.url()).endsWith('#表格与图片'))
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await rhythm.click()
    await page.waitForFunction(() => Math.abs(document.getElementById('阅读节奏').getBoundingClientRect().top - 96) < 2)
    assert.equal(await page.evaluate(() => window.__scrollBehaviors.at(-1)), 'instant')
    await checkWidth(page)
  } finally { await context.close() }
}, { timeout: 60000 })

test('mobile editing and live preview, source preservation, Toast and direct hash entry', async () => {
  const { context, page } = await contextFor({ width: 390, height: 844 })
  try {
    await page.locator('[data-showcase-editor]').scrollIntoViewIfNeeded()
    await sourceDraft(page, '## 手机写作\n\n~~移动端删除线~~\n\n| A | B |\n|---|---|\n| 1 | 2 |')
    await page.locator('[data-showcase-editor]').screenshot({ path: resolve(output, 'editor-mobile.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    await page.getByRole('tab', { name: '实时预览', exact: true }).click()
    await page.locator('[data-editor-preview] h2').filter({ hasText: '手机写作' }).waitFor()
    assert.equal(await page.locator('[data-editor-preview] table').count(), 1)
    await page.locator('[data-showcase-editor]').screenshot({ path: resolve(output, 'preview-mobile.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    await checkWidth(page)
    await page.getByRole('tab', { name: '编辑', exact: true }).click()
    assert.match(await page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true }).inputValue(), /手机写作/)
    const filename = 'very-long-markdown-preview-controller-with-a-readable-source-code-filename.ts'
    await sourceDraft(page, '```typescript [' + filename + ']\nconst mobile = true\n```')
    await page.getByRole('tab', { name: '实时预览', exact: true }).click()
    const label = page.locator('[data-editor-preview] .blog-code-toolbar > span[title]').filter({ hasText: filename })
    await label.waitFor()
    assert.equal(await label.getAttribute('title'), filename)
    assert.ok(await label.evaluate(el => el.scrollWidth > el.clientWidth), '窄屏长文件名截断但完整名称可查看')
    await page.locator('[data-editor-preview] .blog-code').screenshot({ path: resolve(output, 'code-long-filename-mobile.png'), animations: 'disabled', style: '.showcase-header { visibility: hidden !important; }' })
    await checkWidth(page)
    await page.getByRole('button', { name: '显示 Toast', exact: true }).click()
    await page.getByText('样例操作已完成', { exact: true }).waitFor()
    await checkWidth(page)
    await page.goto(baseUrl + '/design-showcase#' + encodeURIComponent('代码展示'))
    await page.locator('#代码展示').waitFor()
    const top = await page.locator('#代码展示').evaluate(el => el.getBoundingClientRect().top)
    assert.ok(top >= 72 && top < 130, '移动端直接 Hash 进入不遮挡标题')
  } finally { await context.close() }
}, { timeout: 60000 })

test('code controls and their Portal keep readable block colors across site themes and viewports', async () => {
  async function contrast(locator) {
    return locator.evaluate(el => {
      const style = getComputedStyle(el)
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1
      const ctx = canvas.getContext('2d')
      function luminance(color) {
        ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = color; ctx.fillRect(0, 0, 1, 1)
        const channels = [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3).map(x => x / 255).map(x => x <= .04045 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4)
        return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
      }
      const colors = [luminance(style.color), luminance(style.backgroundColor)].sort((a, b) => b - a)
      return (colors[0] + .05) / (colors[1] + .05)
    })
  }
  for (const width of [1440, 390]) {
    const { context, page } = await contextFor({ width, height: 1000 })
    try {
      const block = page.locator('.showcase-article .blog-code').first()
      const trigger = block.getByRole('combobox', { includeHidden: true })
      const copy = block.getByRole('button', { name: '复制 ArticleService.java', exact: true })
      for (const site of ['light', 'dark']) {
        if (site === 'dark') await page.getByRole('button', { name: '预览深色主题' }).click()
        for (const theme of ['GitHub Light', 'GitHub Dark', 'Nord', 'Tokyo Night', 'Dracula']) {
          await trigger.click(); await page.getByRole('option', { name: theme, exact: true }).click()
          assert.ok(await contrast(trigger) >= 4.5, `${site}/${theme} Select 文字对比度`)
          assert.ok(await contrast(copy) >= 4.5, `${site}/${theme} 复制图标对比度`)
          await trigger.click()
          const menu = block.locator('[data-code-portal] [role="listbox"]')
          await menu.waitFor()
          assert.ok(await contrast(menu) >= 4.5, `${site}/${theme} Portal 菜单文字对比度`)
          assert.equal(await menu.evaluate(el => getComputedStyle(el).getPropertyValue('--code-control-bg').trim()), await trigger.evaluate(el => getComputedStyle(el).getPropertyValue('--code-control-bg').trim()))
          await page.keyboard.press('ArrowDown')
          assert.ok(await menu.locator('[data-highlighted]').count() > 0, '键盘高亮可用')
          if (site === 'dark' && ['GitHub Light', 'Dracula'].includes(theme)) {
            await page.screenshot({ path: resolve(output, `controls-${theme.toLowerCase().replaceAll(' ', '-')}-${width}.png`), animations: 'disabled' })
          }
          await page.keyboard.press('Escape')
          await checkWidth(page)
        }
      }
    } finally { await context.close() }
  }
}, { timeout: 60000 })
