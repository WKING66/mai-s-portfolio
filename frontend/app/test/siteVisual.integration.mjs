import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { mkdir } from 'node:fs/promises'
import { loadNuxt, build } from 'nuxt'

// 真实 Nuxt 页面 + 隔离 API fixture。永远不连接维护者后端或使用真实账号。
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const frontend = process.env.SITE_VISUAL_FRONTEND || fileURLToPath(new URL('../../', import.meta.url))
const output = process.env.SITE_VISUAL_OUTPUT_DIR || resolve(frontend, '../.local-backups/site-visual')
const owner = { loggedIn: true, username: 'fixture', roles: ['OWNER'], csrfToken: 'fixture-csrf' }
const anonymous = { loggedIn: false, username: null, roles: [], csrfToken: null }
let session = anonymous, browser, app, baseUrl
const pageErrors = [], hydrationErrors = [], requests = []
const envelope = data => ({ code: 'OK', data, message: '', details: [] })
const profile = { displayName: '阿霾', headline: 'Java 后端与工程实践', intro: '用清晰的边界构建可靠的软件。记录项目实践、技术思考与持续学习。', githubUrl: 'https://github.com/WKING66', email: 'fixture@example.test', avatarUrl: null, resumeUrl: null, avatarExpiresAt: null, resumeExpiresAt: null, techStack: [{ name: 'Java', slug: 'tech-java', group: 'LANGUAGE' }, { name: 'Spring Boot', slug: 'tech-spring-boot', group: 'FRAMEWORK' }], updatedAt: '2026-10-10T00:00:00Z' }
profile.techStack.push({ name: 'Git', slug: 'tech-git', group: 'TOOL' }, { name: 'PostgreSQL', slug: 'tech-postgresql', group: 'DATA' })
const projects = [1, 2].map(id => ({ id, slug: 'project-' + id, title: id === 1 ? '可靠的资源管理' : 'Markdown 内容工作流', summary: '围绕明确的业务边界、资源生命周期与测试构建可靠系统。', contribution: '设计接口与权限边界，完善回归验证。', outcome: '让维护与扩展更容易。', timeLabel: '2026 · Engineering', tags: [{ id: 1, name: 'Java', slug: 'tech-java' }], links: [], cover: null, status: 'PUBLISHED', featured: true, sortOrder: id, version: 3, publishedAt: '2026-10-10T00:00:00Z', updatedAt: '2026-10-10T00:00:00Z' }))
const content = '## 从内容开始\n\n清晰的标题与适当的留白帮助读者建立阅读节奏。Frontend rendering uses Markdown.\n\n> 让内容成为界面的主角。\n\n- 保持业务边界\n- 验证真实交互\n\n### 代码与表格\n\n```typescript\nconst content = "' + '保持长代码行和横向滚动。'.repeat(15) + '";\n```\n\n|模块|职责|\n|---|---|\n|Blog|内容与发布|\n|Asset|资源与访问|\n\n![设计插图](/images/design-showcase.svg)\n\n[Nuxt UI 文档](https://ui.nuxt.com)'
const article = { id: 7, slug: 'style-guide', title: '清晰的表达，稳定的系统', summary: '技术文章的阅读体验，来自清晰的层次、舒适的排版与可靠的交互。', status: 'PUBLISHED', version: 3, publishedAt: '2026-10-10T00:00:00Z', documentId: 70, documentVersionId: 71, content, previewIssue: null, resources: [], allowDownload: false, updatedAt: '2026-10-10T00:00:00Z' }
const api = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  requests.push({ method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams) })
  res.setHeader('content-type', 'application/json')
  if (req.method !== 'GET') { res.statusCode = 405; res.end(JSON.stringify({ code: 'METHOD_NOT_ALLOWED', message: '视觉测试不接受写入', details: [] })); return }
  let data
  if (url.pathname === '/api/v1/auth/session') data = session
  else if (url.pathname === '/api/v1/account/profile') data = { username: 'fixture', nickname: '视觉验收', avatarUrl: null, avatarExpiresAt: null }
  else if (url.pathname === '/api/v1/profile') data = profile
  else if (url.pathname === '/api/v1/projects') data = { view: url.searchParams.get('view') || 'PUBLIC', page: 1, size: 12, total: projects.length, items: projects }
  else if (/\/api\/v1\/projects\/\d+$/.test(url.pathname)) data = projects[0]
  else if (url.pathname === '/api/v1/tags') data = []
  else if (url.pathname === '/api/v1/blogs') data = { view: url.searchParams.get('view') || 'PUBLIC', page: Number(url.searchParams.get('page') || 1), size: Number(url.searchParams.get('size') || 12), total: 1, items: [article] }
  else if (url.pathname === '/api/v1/blogs/by-slug/style-guide') data = article
  else if (url.pathname === '/api/v1/blogs/7') data = { ...article, content: '# 已保存的 Markdown\n\n保留现有编辑流程。' }
  else { res.statusCode = 404; data = null }
  res.end(JSON.stringify(envelope(data)))
})
before(async () => {
  await mkdir(output, { recursive: true })
  await new Promise(resolve => api.listen(0, '127.0.0.1', resolve))
  const apiUrl = 'http://127.0.0.1:' + api.address().port
  const nuxt = await loadNuxt({ cwd: frontend, dev: false, overrides: { buildDir: output + '/.nuxt', hooks: { 'pages:extend': pages => { pages.push({ name: 'visual-regression', path: '/__visual-regression', file: fileURLToPath(new URL('./fixtures/VisualInteractions.vue', import.meta.url)) }) } }, routeRules: { '/api/v1/**': { proxy: apiUrl + '/api/v1/**' } }, nitro: { output: { dir: output + '/.output', serverDir: output + '/.output/server', publicDir: output + '/.output/public' } } } })
  try { await build(nuxt) } finally { await nuxt.close() }
  const probe = createServer()
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve)); const port = probe.address().port
  await new Promise(resolve => probe.close(resolve))
  const env = { ...process.env, HOST: '127.0.0.1', PORT: String(port), NITRO_HOST: '127.0.0.1', NITRO_PORT: String(port), NUXT_PUBLIC_SITE_URL: 'https://portfolio.example.test' }
  delete env.NODE_TEST_CONTEXT
  app = spawn(process.execPath, [output + '/.output/server/index.mjs'], { cwd: frontend, windowsHide: true, env, stdio: ['ignore', 'pipe', 'pipe'] })
  baseUrl = await new Promise((resolve, reject) => {
    let log = ''; const timer = setTimeout(() => reject(new Error(log)), 15000)
    const read = chunk => { log += chunk; const url = log.match(/Listening on (http:\/\/127\.0\.0\.1:\d+)/)?.[1]; if (url) { clearTimeout(timer); resolve(url) } }
    app.stdout.on('data', read); app.stderr.on('data', read)
  })
  browser = await chromium.launch({ headless: false, args: ['--window-position=-32000,-32000'], ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
}, { timeout: 300000 })
after(async () => {
  if (browser) await browser.close()
  if (app) app.kill()
  await new Promise(resolve => api.close(resolve))
  assert.deepEqual(pageErrors, [], '无未处理浏览器异常')
  assert.deepEqual(hydrationErrors, [], '无水合警告')
  assert.equal(requests.filter(req => req.method !== 'GET').length, 0, '视觉验收不写真实或模拟内容')
})
async function contextFor(theme = 'light', width = 1440, saved = theme, cookie = theme) {
  const context = await browser.newContext({ viewport: { width, height: width === 1440 ? 1000 : 844 }, reducedMotion: 'reduce' })
  if (cookie) await context.addCookies([{ name: 'portfolio-theme', value: cookie, url: baseUrl }])
  if (saved) await context.addInitScript(value => localStorage.setItem('portfolio-theme', value), saved)
  const page = await context.newPage()
  page.on('pageerror', error => pageErrors.push(error.message))
  page.on('console', message => { if (/hydration/i.test(message.text())) hydrationErrors.push({ url: page.url(), message: message.text() }) })
  return { context, page }
}
async function ready(page) { await page.locator('[data-account-entry]:not([disabled])').waitFor() }
async function widthOK(page) { assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '没有页面横向溢出') }
const geometry = page => page.evaluate(() => { const box = document.querySelector('main').getBoundingClientRect(); return { x: box.x, width: box.width, scroll: scrollY } })
function stable(a, b) { assert.ok(Math.abs(a.x - b.x) < .75 && Math.abs(a.width - b.width) < .75, '主体位置和宽度稳定'); assert.ok(Math.abs(a.scroll - b.scroll) < 1, '滚动位置保留') }

test('SSR uses public HTML projection, current SEO and cookie theme before hydration', async () => {
  session = anonymous
  for (const path of ['/', '/blog', '/blog/style-guide', '/projects']) {
    const response = await fetch(baseUrl + path, { headers: { Cookie: 'portfolio-theme=dark' } })
    assert.equal(response.status, 200)
    const html = await response.text()
    assert.match(html, /data-theme="dark"/)
    assert.match(html, /rel="canonical"/)
    assert.equal((html.match(/<footer\b/g) || []).length, 1, '公共页脚仅出现一次')
    if (path === '/blog/style-guide') assert.match(html, /id="从内容开始"/)
  }
})

test('theme restoration honors Cookie, storage-only and system fallback without changing geometry', async () => {
  session = anonymous
  for (const [saved, cookie, expected] of [['dark', 'light', 'light'], ['dark', '', 'dark'], ['', '', 'light']]) {
    const { context, page } = await contextFor(expected, 1440, saved, cookie)
    try {
      await page.goto(baseUrl + '/'); await ready(page)
      assert.equal(await page.locator('html').getAttribute('data-theme'), expected)
      assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), expected === 'dark' ? 'rgb(16, 25, 28)' : 'rgb(245, 247, 246)')
      const before = await geometry(page)
      await page.getByRole('button', { name: expected === 'dark' ? '切换为浅色主题' : '切换为深色主题' }).click()
      stable(before, await geometry(page)); await page.reload(); await ready(page)
      assert.equal(await page.locator('html').getAttribute('data-theme'), expected === 'dark' ? 'light' : 'dark')
      assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor), await page.evaluate(() => getComputedStyle(document.body).backgroundColor))
    } finally { await context.close() }
  }
})

test('public, account and management pages render in both themes and desktop/mobile layouts', async () => {
  for (const width of [1440, 390]) for (const theme of ['light', 'dark']) {
    for (const [path, heading, auth] of [
      ['/', '阿霾', anonymous], ['/blog', '博客笔记', anonymous], ['/blog/style-guide', article.title, anonymous], ['/projects', '项目作品', anonymous], ['/login', '登录你的账号', anonymous], ['/register', '创建访客账号', anonymous], ['/account', '个人中心', owner], ['/admin/projects', '项目管理', owner], ['/admin/projects/1', '编辑项目', owner], ['/admin/profile', '站长公开资料', owner], ['/admin/blogs', '博客管理', owner], ['/admin/blogs/7', '编辑博客', owner],
    ]) {
      session = auth
      const { context, page } = await contextFor(theme, width)
      try {
        await page.goto(baseUrl + path); await ready(page)
        await page.getByRole('heading', { name: heading, exact: true }).first().waitFor()
        if (path === '/admin/blogs/7') await page.getByLabel('正文', { exact: true }).waitFor()
        if (path === '/account') await page.getByLabel('昵称', { exact: true }).waitFor()
        if (path === '/admin/projects') await page.getByRole('combobox').waitFor()
        if (path === '/admin/projects/1') await page.getByLabel('项目标题', { exact: true }).waitFor()
        if (path === '/admin/profile') await page.getByLabel('昵称', { exact: true }).waitFor()
        await widthOK(page)
        assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), theme === 'dark' ? 'rgb(16, 25, 28)' : 'rgb(245, 247, 246)', '共享背景在真实路由一致')
        assert.ok(Math.abs((await page.locator('.site-header').boundingBox()).height - 73) < 1, '导航高度保持一致')
        if (path === '/blog/style-guide') {
          const readingWidth = await page.locator('main').evaluate(el => ({ width: el.getBoundingClientRect().width, maximum: getComputedStyle(el).maxWidth }))
          assert.equal(readingWidth.maximum, '760px', '共享阅读容器最大宽度')
          assert.ok(readingWidth.width <= 760.5, '长文阅读宽度 ' + JSON.stringify(readingWidth))
          assert.equal(await page.locator('.blog-renderer').evaluate(el => getComputedStyle(el).fontSize), '17px')
          assert.ok(await page.locator('.blog-renderer pre').evaluate(el => el.scrollWidth > el.clientWidth), '长代码行在块内滚动')
        }
        assert.equal(await page.locator('footer').count(), 1)
        await page.screenshot({ path: resolve(output, `${path === '/' ? 'home' : path.slice(1).replaceAll('/', '-')}-${theme}-${width}.png`), animations: 'disabled' })
        if (path === '/blog/style-guide') await page.locator('.blog-renderer').screenshot({ path: resolve(output, `article-body-${theme}-${width}.png`), animations: 'disabled', style: '.site-header { visibility: hidden !important; }' })
      } finally { await context.close() }
    }
  }
}, { timeout: 180000 })

test('real management Select, account Dropdown, confirmation Modal and Toast preserve layout and focus', async () => {
  session = owner
  const { context, page } = await contextFor('dark')
  try {
    await page.goto(baseUrl + '/admin/projects'); await ready(page)
    const select = page.getByRole('combobox'); await select.waitFor()
    const before = await geometry(page)
    await select.click(); stable(before, await geometry(page)); await page.keyboard.press('Escape'); stable(before, await geometry(page))
    await page.locator('[data-account-entry]').click(); await page.getByRole('menu').waitFor(); stable(before, await geometry(page)); await page.keyboard.press('Escape')
    await page.goto(baseUrl + '/admin/blogs/7'); await page.getByLabel('正文', { exact: true }).waitFor()
    await page.getByLabel('标题', { exact: true }).fill('未保存的修改')
    assert.ok(await page.getByRole('button', { name: '发布当前草稿', exact: true }).isDisabled())
    // 正式博客重载仍保留既有流程；测试公共 UApp 的 Modal 不替换业务确认逻辑。
    await page.goto(baseUrl + '/__visual-regression'); await ready(page)
    const trigger = page.getByRole('button', { name: '打开测试对话框', exact: true })
    const modalBefore = await geometry(page)
    for (let i = 0; i < 5; i++) {
      await trigger.click(); const dialog = page.getByRole('dialog'); await dialog.waitFor(); stable(modalBefore, await geometry(page))
      assert.equal(await page.evaluate(() => document.body.style.overflow), 'hidden')
      await page.keyboard.press('Tab'); assert.ok(await dialog.evaluate(el => el.contains(document.activeElement)))
      await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' }); stable(modalBefore, await geometry(page))
      assert.ok(await trigger.evaluate(el => el === document.activeElement))
    }
    await page.goto(baseUrl + '/forbidden'); await page.getByText('当前账号没有访问权限。', { exact: true }).waitFor()
    const viewport = page.locator('[data-slot="viewport"]'); assert.equal(await viewport.count(), 1)
    const box = await viewport.boundingBox(); const main = await geometry(page)
    assert.ok(Math.abs(box.x + box.width / 2 - main.x - main.width / 2) < 1 && box.y >= 73 && box.y < 120)
    await widthOK(page)
  } finally { await context.close() }
}, { timeout: 60000 })

test('mobile navigation and current account Tabs retain geometry, labels and visible keyboard focus', async () => {
  session = owner
  const { context, page } = await contextFor('light', 390)
  try {
    await page.goto(baseUrl + '/account'); await page.getByLabel('昵称', { exact: true }).waitFor()
    assert.equal(await page.locator('[data-account-entry]').evaluate(el => {
      const boundary = el.getBoundingClientRect()
      return [...el.querySelectorAll('[data-slot]')].some(child => {
        const box = child.getBoundingClientRect()
        return box.width > 0 && (box.left < boundary.left - .75 || box.right > boundary.right + .75)
      })
    }), false, '移动端账户按钮内容不越界、不与主题按钮重叠')
    const before = await geometry(page)
    const nav = page.getByRole('button', { name: '打开页面导航', exact: true })
    await nav.click(); await page.getByRole('menu').waitFor(); stable(before, await geometry(page)); await widthOK(page)
    await page.keyboard.press('ArrowDown'); assert.equal(await page.evaluate(() => document.activeElement.getAttribute('role')), 'menuitem')
    await page.keyboard.press('Escape'); assert.ok(await nav.evaluate(el => el === document.activeElement))
    await page.getByRole('tab', { name: '密码修改', exact: true }).click(); stable(before, await geometry(page)); await widthOK(page)
    const input = page.getByLabel('当前密码', { exact: true }); await input.focus(); await input.press('Tab')
    assert.notEqual(await page.evaluate(() => getComputedStyle(document.activeElement).outlineStyle), 'none')
  } finally { await context.close() }
})

test('anonymous management navigation remains denied and never requests MANAGE data', async () => {
  session = anonymous; const start = requests.length
  const { context, page } = await contextFor()
  try {
    await page.goto(baseUrl + '/admin/blogs'); await page.waitForURL('**/login?**')
    assert.equal(await page.getByLabel('正文', { exact: true }).count(), 0)
    assert.equal(requests.slice(start).filter(req => req.query.view === 'MANAGE').length, 0)
  } finally { await context.close() }
})
