import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000', label = process.env.RESPONSIVE_LABEL || 'after'
const output = resolve(process.env.RESPONSIVE_OUTPUT || '../docs/testing/screenshots/blog-reader-fix-20261011/responsive')
await mkdir(output, { recursive: true })
const widths = process.env.RESPONSIVE_WIDTHS ? process.env.RESPONSIVE_WIDTHS.split(',').map(Number) : process.env.RESPONSIVE_BASELINE ? [320, 768, 1024] : [320, 360, 375, 390, 412, 430, 639, 640, 767, 768, 769, 1023, 1024, 1199, 1200, 1280, 1440]
const envelope = data => ({ code: 'OK', data, message: '', details: [] })
const profile = (await (await fetch(base + '/api/v1/profile')).json()).data
const long = 'unbroken-filename-and-long-url-'.repeat(8)
const content = '# 响应式阅读\n\n长地址 https://example.test/' + long + '\n\n行内公式 $\\frac{' + 'x+'.repeat(65) + 'x}{n}$\n\n## 代码与表格\n\n```typescript [VeryLongFilename.ts]\nconst line = "' + long + '"\n```\n\n|列一|列二|\n|---|---|\n|' + long + '|' + long + '|\n\n![图片](/images/design-showcase.svg)\n\n## 末节\n\n' + '正文阅读。\n\n'.repeat(30)
const article = { id: 999, slug: '__responsive', title: '响应式布局与长内容样例'.repeat(8), summary: '用于布局验证，数据仅存在隔离浏览器。', content, status: 'DRAFT', version: 1, documentId: 999, documentVersionId: 999, resources: [], allowDownload: false, previewIssue: null, publishedAt: '2026-10-11T00:00:00Z', updatedAt: '2026-10-11T00:00:00Z' }
const publicPages = [['home', '/'], ['projects', '/projects'], ['list', '/blog'], ['article', '/blog/__responsive'], ['login', '/login'], ['register', '/register'], ['403', '/forbidden'], ['showcase', '/design-showcase'], ['404', '/__responsive_missing_page']]
const privatePages = [['account', '/account'], ['admin', '/admin/blogs'], ['edit', '/admin/blogs/999'], ['new', '/admin/blogs/new'], ['admin-projects', '/admin/projects'], ['admin-profile', '/admin/profile']]
const server = await chromium.launchServer({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--renderer-process-limit=1', '--js-flags=--max-old-space-size=512'] })
const timer = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true }); process.exit(124) }, 360000)
const browser = await chromium.connect(server.wsEndpoint()), rows = [], errors = [], failures = []
const save = () => writeFile(resolve(output, label + '-results.json'), JSON.stringify({ widths, rows, failures, errors, privateData: '隔离浏览器只读 fixture，未改真实权限或写入数据库' }, null, 2))
try {
  for (const theme of ['light', 'dark']) for (const width of widths) for (const managed of [false, true]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: base }])
    await context.route('**/api/v1/**', route => {
      const request = route.request(), url = new URL(request.url()), path = url.pathname
      if (request.method() !== 'GET') throw new Error('响应式检查禁止真实写入')
      let data
      if (path === '/api/v1/auth/session') data = { loggedIn: managed, username: managed ? 'fixture' : null, roles: managed ? ['OWNER'] : [], csrfToken: managed ? 'fixture-csrf' : null }
      else if (path === '/api/v1/account/profile') data = { username: 'fixture', nickname: '响应式验收', avatarUrl: null }
      else if (path === '/api/v1/blogs/by-slug/__responsive') data = { ...article, status: 'PUBLISHED' }
      else if (managed && path === '/api/v1/blogs') data = { view: 'MANAGE', page: 1, size: 20, total: 1, items: [article] }
      else if (managed && path === '/api/v1/blogs/999') data = article
      else if (managed && path === '/api/v1/profile') data = { ...profile, version: 1 }
      else if (managed && path === '/api/v1/projects') data = { view: 'MANAGE', page: 1, size: 20, total: 0, items: [] }
      else if (managed && path === '/api/v1/assets') data = { page: 1, size: 20, total: 0, items: [] }
      else return route.continue()
      return route.fulfill({ json: envelope(data) })
    })
    const page = await context.newPage(); page.setDefaultTimeout(20000)
    page.on('pageerror', e => errors.push({ width, theme, message: e.message })); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push({ width, theme, message: m.text() }) })
    await page.goto(base + '/'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
    if (process.env.RESPONSIVE_FONT_SIZE) { await page.addStyleTag({ content: `html { font-size: ${Number(process.env.RESPONSIVE_FONT_SIZE)}px !important; }` }); await page.waitForTimeout(100) }
    if (width < 768) {
      const navigation = page.getByRole('button', { name: '打开页面导航', exact: true })
      const before = await page.locator('main').boundingBox()
      await navigation.click(); const menu = page.getByRole('menu'); await menu.waitFor()
      const box = await menu.boundingBox()
      assert.ok(box.x >= 0 && box.x + box.width <= width + 1, '移动导航菜单在视口内')
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1))
      await page.keyboard.press('ArrowDown')
      assert.equal(await page.evaluate(() => document.activeElement.getAttribute('role')), 'menuitem')
      await page.keyboard.press('Escape'); await menu.waitFor({ state: 'hidden' })
      assert.ok(await navigation.evaluate(el => el === document.activeElement), '菜单关闭后恢复焦点')
      assert.ok(Math.abs((await page.locator('main').boundingBox()).x - before.x) < 1, '菜单不移动主体')
    }
    for (const [name, path] of managed ? privatePages : publicPages) {
      if (name === '404') await page.goto(base + path)
      else if (path !== '/') await page.evaluate(path => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push(path), path)
      await page.locator('main').waitFor()
      if (name === 'article') await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
      if (managed) await page.getByRole('heading', { level: 1 }).waitFor()
      if (name === 'account') {
        const before = await page.locator('main').boundingBox()
        await page.getByRole('tab', { name: '密码修改', exact: true }).click()
        await page.getByLabel('当前密码', { exact: true }).waitFor()
        const after = await page.locator('main').boundingBox()
        assert.ok(Math.abs(before.x - after.x) < 1 && Math.abs(before.width - after.width) < 1, '账户 Tabs 保持横向布局稳定')
        await page.getByRole('tab', { name: '个人信息', exact: true }).click()
        await page.getByLabel('昵称', { exact: true }).waitFor()
      }
      await page.waitForTimeout(100)
      assert.equal(new URL(page.url()).pathname, path, '实际进入目标页面，不能把重定向页当作页面通过')
      const geometry = await page.evaluate(() => {
        const root = document.documentElement, width = root.clientWidth
        const overflow = root.scrollWidth > width + 1
        const offenders = overflow ? [...document.querySelectorAll('body *')].filter(el => {
          const rect = el.getBoundingClientRect()
          if (!rect.width || rect.right <= width + 1 || getComputedStyle(el).visibility === 'hidden') return false
          for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
            if (['auto', 'scroll', 'hidden', 'clip'].includes(getComputedStyle(parent).overflowX) && parent.getBoundingClientRect().right <= width + 1) return false
          }
          return true
        }).slice(0, 12).map(el => ({ tag: el.tagName, class: typeof el.className === 'string' ? el.className.slice(0, 140) : '', width: Math.round(el.getBoundingClientRect().width), right: Math.round(el.getBoundingClientRect().right) })) : []
        return { viewport: innerWidth, clientWidth: width, scrollWidth: root.scrollWidth, overflow, offenders }
      })
      const row = { name, path, width, height: 900, dpr: 1, simulated: true, browser: 'Microsoft Edge ' + browser.version(), orientation: width > 900 ? 'Landscape' : 'Portrait', theme, ...geometry }; rows.push(row)
      if (geometry.overflow) failures.push(row)
      if (await page.evaluate(() => document.documentElement.dataset.theme) !== theme) failures.push({ name, width, theme, reason: '主题未恢复' })
      if (!process.env.RESPONSIVE_BASELINE && [390, 1440].includes(width) && ['home', 'projects', 'account', 'admin', 'edit', 'login', 'article', '403', '404'].includes(name)) await page.screenshot({ path: resolve(output, name + '-' + theme + '-' + width + '.png') })
    }
    await context.close(); await save(); console.log(JSON.stringify({ width, theme, managed, failures: failures.length }))
  }
  if (!process.env.RESPONSIVE_BASELINE) { assert.deepEqual(failures, [], '页面水平溢出及主题恢复检查'); assert.deepEqual(errors, []) }
} finally { await save(); clearTimeout(timer); await browser.close(); await server.close() }
