import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000'
const output = resolve(process.env.BLOG_REFINEMENT_OUTPUT_DIR || '../docs/testing/screenshots/blog-reader-refinement-20261011')
await mkdir(output, { recursive: true })
const longCode = Array.from({ length: 45 }, (_, i) => i === 0 ? 'const longLine = "' + 'long readable code '.repeat(45) + '"' : `const line${i} = ${i}`).join('\n')
const source = '# 阅读体验测试\n\n开始阅读。\n\n' + Array.from({ length: 30 }, (_, i) => `## 章节 ${i}\n\n${('正文段落保持中文阅读体验。 '.repeat(30) + '\n\n').repeat(3)}### 子节 ${i}\n\n子节正文。\n\n#### 深层 ${i}\n\n深层正文。\n\n`).join('') + '## 代码样例\n\n```typescript [Long.ts]\n' + longCode + '\n```\n\n```javascript [Short.js]\n' + Array.from({ length: 20 }, (_, i) => `console.log(${i})`).join('\n') + '\n```\n\n## 结尾\n\n' + '结束正文。\n\n'.repeat(40)
const fixture = { id: 99, slug: '__reader-refinement', title: '博客阅读体验测试', summary: '', content: source, resources: [], status: 'PUBLISHED', version: 1, documentId: 990, documentVersionId: 990, allowDownload: false, publishedAt: '2026-10-11T00:00:00Z', updatedAt: '2026-10-11T00:00:00Z' }
const browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE })
const rows = [], errors = []
try {
  for (const width of process.env.REFINEMENT_WIDTHS ? process.env.REFINEMENT_WIDTHS.split(',').map(Number) : [1440, 768, 390, 320]) for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'no-preference', ...(width < 1024 ? { isMobile: true, hasTouch: true } : {}), permissions: ['clipboard-read', 'clipboard-write'] })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: base }])
    await context.route('**/api/v1/blogs/by-slug/__reader-refinement', route => route.fulfill({ json: { code: 'OK', message: '', data: fixture, details: [] } }))
    const page = await context.newPage()
    let workers = 0
    page.on('worker', () => workers++)
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    await page.goto(base + '/blog'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog/__reader-refinement'))
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
    const toc = page.getByRole('navigation', { name: '文章目录', exact: true })
    async function openDirectory() {
      if (width < 1024 && !await page.getByRole('dialog').isVisible()) {
        await page.getByRole('button', { name: '打开文章目录', exact: true }).click()
        await page.waitForFunction(() => { const dialog = document.querySelector('[role="dialog"]'); return dialog && Math.abs(dialog.getBoundingClientRect().left) < 1 && Number(getComputedStyle(dialog).opacity) === 1 })
      }
    }
    async function closeDirectory() { if (width < 1024 && await page.getByRole('dialog').isVisible()) { await page.keyboard.press('Escape'); await page.getByRole('dialog').waitFor({ state: 'hidden' }) } }
    async function choose(name) { await openDirectory(); await toc.getByRole('link', { name, exact: true }).click(); if (width < 1024) await page.getByRole('dialog').waitFor({ state: 'hidden' }) }
    if (width >= 1024) {
      const a = await toc.boundingBox(), b = await page.locator('.blog-renderer').boundingBox()
      assert.ok(a.x + a.width < b.x, '桌面目录位于正文左侧')
    }
    await page.screenshot({ path: resolve(output, `layout-${theme}-${width}.png`) })
    if (width >= 1024) {
      const arrow = page.getByRole('button', { name: '收起章节：阅读体验测试', exact: true })
      await page.mouse.move(0, 0)
      assert.equal(await arrow.evaluate(el => getComputedStyle(el).opacity), '0')
      await arrow.locator('..').hover(); await page.waitForTimeout(170)
      assert.equal(await arrow.evaluate(el => getComputedStyle(el).opacity), '1')
      await arrow.focus(); await page.mouse.move(0, 0)
      assert.equal(await arrow.evaluate(el => getComputedStyle(el).opacity), '1', '键盘焦点可发现折叠操作')
    } else {
      const beforeModal = await page.locator('.blog-renderer').boundingBox()
      await openDirectory()
      const panel = await page.getByRole('dialog').boundingBox()
      console.log(JSON.stringify({ width, theme, panel, document: await page.evaluate(() => ({ viewport: innerWidth, client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth, rem: getComputedStyle(document.documentElement).fontSize })) }))
      assert.ok(Math.abs(panel.x) < 1 && panel.width < width, '目录是左侧面板，正文仍有可见区域')
      assert.ok(panel.y >= 70 && panel.y <= 74, '面板避开全站 Header')
      assert.equal(await page.locator('[data-slot="overlay"]').count(), 1, '目录保留低干扰遮罩')
      assert.ok(await page.getByRole('dialog').evaluate(el => el.contains(document.activeElement)), '目录打开后焦点位于面板内')
      assert.ok(panel.width <= 320.5 && width - panel.width >= 47.5, '面板宽度与可见正文安全区（允许变换的小数像素）')
      assert.equal(await toc.locator('.blog-toc-branch').count(), 0)
      assert.equal(await toc.getByRole('link', { name: '子节 18', exact: true }).isVisible(), true)
      await page.screenshot({ path: resolve(output, `directory-panel-${theme}-${width}.png`) })
      await closeDirectory()
      assert.ok(Math.abs((await page.locator('.blog-renderer').boundingBox()).x - beforeModal.x) < 1)
      assert.equal(await page.getByRole('button', { name: '打开文章目录', exact: true }).evaluate(el => el === document.activeElement), true)
    }
    await page.evaluate(() => {
      document.documentElement.style.scrollBehavior = 'smooth'
      window.__scrolls = []
      const original = window.scrollTo.bind(window)
      window.scrollTo = (...args) => { window.__scrolls.push({ behavior: args[0]?.behavior, top: args[0]?.top }); return original(...args) }
      const intoView = Element.prototype.scrollIntoView
      Element.prototype.scrollIntoView = function (options) { window.__scrolls.push({ behavior: options?.behavior, heading: this.id }); return intoView.call(this, options) }
    })
    const link = toc.getByRole('link', { name: '章节 24', exact: true })
    await choose('章节 24')
    await page.waitForFunction(() => document.getElementById('章节-24')?.hasAttribute('data-heading-flash'))
    const before = await page.evaluate(() => { const heading = document.getElementById('章节-24'); return { top: heading.getBoundingClientRect().top, height: heading.getBoundingClientRect().height, opacity: Number(getComputedStyle(heading, '::before').opacity), y: scrollY, scrolls: window.__scrolls, flashes: document.querySelectorAll('[data-heading-flash]').length } })
    console.log(JSON.stringify({ navigation: before, width, theme }))
    assert.ok(Math.abs(before.top - 96) < 3); assert.equal(before.flashes, 1)
    assert.ok(before.y > 3000); assert.ok(before.scrolls.every(s => s.behavior === 'instant'))
    assert.ok(before.opacity >= .8, '目标标题实际显示闪现背景')
    await page.screenshot({ path: resolve(output, `jump-flash-${theme}-${width}.png`) })
    const opacities = []
    for (const frame of [1, 2, 3]) {
      await page.waitForTimeout(180)
      opacities.push(await page.evaluate(() => { const heading = document.getElementById('章节-24'); return heading.hasAttribute('data-heading-flash') ? Number(getComputedStyle(heading, '::before').opacity) : 0 }))
      await page.screenshot({ path: resolve(output, `jump-sequence-${theme}-${width}-${frame}.png`) })
    }
    await page.waitForTimeout(350)
    assert.ok(Math.abs(await page.evaluate(() => scrollY) - before.y) < 3, '跳转后没有长距离动画或回弹')
    await page.waitForFunction(() => document.querySelectorAll('[data-heading-flash]').length === 0)
    assert.ok(opacities.some(opacity => opacity > 0 && opacity < 1), '闪现通过透明度渐退')
    assert.equal(await page.locator('#章节-24').evaluate(el => el.getBoundingClientRect().height), before.height, '闪现不改变标题尺寸')
    await openDirectory()
    assert.equal(await link.getAttribute('aria-current'), 'location', '闪现结束后仍保留目录当前位置')
    await closeDirectory()
    await choose('章节 3')
    await choose('章节 18')
    await page.waitForFunction(() => document.getElementById('章节-18')?.hasAttribute('data-heading-flash'))
    assert.equal(await page.locator('[data-heading-flash]').count(), 1)
    await page.goBack(); await page.waitForFunction(() => Math.abs(document.getElementById('章节-3').getBoundingClientRect().top - 96) < 3)
    await page.goForward(); await page.waitForFunction(() => Math.abs(document.getElementById('章节-18').getBoundingClientRect().top - 96) < 3)
    const scroll = toc.locator('.blog-toc-scroll')
    await openDirectory()
    assert.ok(await scroll.evaluate(el => el.scrollHeight > el.clientHeight))
    await scroll.evaluate(el => { el.scrollTop = el.scrollHeight })
    await page.screenshot({ path: resolve(output, `toc-scrolled-${theme}-${width}.png`) })
    await closeDirectory()
    // 目录只控制目录；正文折叠隐藏后代标题，并保留子章节偏好。
    if (width >= 1024) {
      await toc.getByRole('button', { name: '收起目录：章节 18', exact: true }).click()
      assert.equal(await toc.getByRole('link', { name: '子节 18', exact: true }).count(), 0)
      assert.equal(await page.locator('#子节-18').isVisible(), true)
      await page.screenshot({ path: resolve(output, `toc-fold-${theme}-${width}.png`) })
      await page.setViewportSize({ width: 768, height: 900 })
      await page.getByRole('button', { name: '打开文章目录', exact: true }).click()
      await page.getByRole('dialog').waitFor()
      assert.equal(await toc.getByRole('link', { name: '子节 18', exact: true }).isVisible(), true, '宽屏收起的目录在小屏弹窗中仍完整可达')
      assert.equal(await toc.locator('.blog-toc-branch').count(), 0)
      await page.keyboard.press('Escape')
      await page.getByRole('dialog').waitFor({ state: 'hidden' })
      await page.setViewportSize({ width, height: 900 })
      assert.equal(await toc.getByRole('link', { name: '子节 18', exact: true }).count(), 0, '恢复宽屏时保留独立目录偏好')
      await toc.getByRole('button', { name: '展开目录：章节 18', exact: true }).click()
    }
    await page.getByRole('button', { name: '收起章节：子节 18', exact: true }).click()
    assert.equal(await page.locator('#深层-18').isVisible(), false)
    await page.getByRole('button', { name: '收起章节：章节 18', exact: true }).click()
    assert.equal(await page.locator('#子节-18').isVisible(), false)
    assert.equal(await page.locator('#章节-19').isVisible(), true)
    await openDirectory()
    assert.equal(await toc.getByRole('link', { name: '子节 18', exact: true }).count(), 0)
    if (width >= 1024) assert.equal(await toc.getByRole('button', { name: '收起目录：章节 18', exact: true }).isDisabled(), true)
    await closeDirectory()
    await page.screenshot({ path: resolve(output, `body-fold-${theme}-${width}.png`) })
    await page.getByRole('button', { name: '展开章节：章节 18', exact: true }).click()
    assert.equal(await page.locator('#子节-18').isVisible(), true)
    assert.equal(await page.locator('#深层-18').isVisible(), false)
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push({ hash: '#深层-18' }))
    await page.waitForFunction(() => Math.abs(document.getElementById('深层-18').getBoundingClientRect().top - 96) < 3)
    assert.equal(await page.locator('#深层-18').isVisible(), true)
    await page.getByRole('button', { name: '收起章节：子节 17', exact: true }).click()
    assert.equal(await page.locator('#深层-17').isVisible(), false)
    await choose('代码样例')
    const block = page.locator('.blog-code').first(), viewport = block.locator('.blog-code-viewport')
    assert.equal(await viewport.getAttribute('data-code-collapsed'), 'true')
    const short = page.locator('.blog-code').nth(1)
    assert.equal(await short.getByRole('button', { name: '展开全部代码', exact: true }).count(), 0, '20 行代码无需折叠')
    const pre = block.locator('pre')
    const visibleLines = await pre.evaluate(el => { const bounds = el.getBoundingClientRect(); return [...el.querySelectorAll('.line')].filter(line => { const box = line.getBoundingClientRect(); return box.top >= bounds.top && box.bottom <= bounds.bottom }).length })
    assert.ok(visibleLines >= 12 && visibleLines <= 16, `折叠代码实际可见 ${visibleLines} 行`)
    assert.ok(await pre.evaluate(el => el.scrollWidth > el.clientWidth))
    await pre.evaluate(el => { el.scrollLeft = 100 })
    const collapsedHeight = (await viewport.boundingBox()).height
    await block.getByRole('button', { name: '复制 Long.ts', exact: true }).click()
    await page.waitForFunction(async expected => (await navigator.clipboard.readText()).replace(/\r\n/g, '\n') === expected, longCode)
    assert.equal((await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n'), longCode)
    await block.getByRole('combobox').click(); await block.getByRole('option', { name: 'Dracula', exact: true }).click()
    assert.equal(await viewport.getAttribute('data-code-collapsed'), 'true')
    assert.ok(Math.abs((await viewport.boundingBox()).height - collapsedHeight) < 1)
    assert.ok(await pre.evaluate(el => el.scrollLeft >= 100), '主题切换保留代码横向滚动位置')
    await page.screenshot({ path: resolve(output, `code-collapsed-${theme}-${width}.png`) })
    await block.getByRole('button', { name: '展开全部代码', exact: true }).click()
    assert.ok((await viewport.boundingBox()).height > collapsedHeight * 2)
    await block.screenshot({ path: resolve(output, `code-expanded-${theme}-${width}.png`), style: '.site-header { visibility: hidden !important; }' })
    await page.getByRole('button', { name: '收起章节：代码样例', exact: true }).click()
    assert.equal(await block.isVisible(), false)
    await page.getByRole('button', { name: '展开章节：代码样例', exact: true }).click()
    assert.equal(await viewport.getAttribute('data-code-collapsed'), 'false')
    assert.equal(await block.getAttribute('data-code-theme'), 'dracula')
    assert.equal(await page.locator('#深层-17').isVisible(), false, '定位只展开必要祖先，保留其他章节状态')
    await block.getByRole('button', { name: '收起代码', exact: true }).click()
    assert.equal(await viewport.getAttribute('data-code-collapsed'), 'true')
    assert.equal(workers, 1, '折叠和主题切换不重新创建 Markdown / Shiki Worker')
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    rows.push({ width, theme, jump: before, opacities, visibleCodeLines: visibleLines })
    await context.close()
  }
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'interaction-results.json'), JSON.stringify({ rows, errors }, null, 2))
  console.log(JSON.stringify({ passed: true, rows, errors }))
} finally { await browser.close() }
