import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const baseUrl = process.env.EXTERNAL_ACCEPTANCE_URL || 'http://127.0.0.1:3000'
const output = resolve('../docs/testing/screenshots/site-visual-stabilization-20261011')
await mkdir(output, { recursive: true })
const server = await chromium.launchServer({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
const watchdog = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 90000)
const browser = await chromium.connect(server.wsEndpoint()), errors = [], hydration = [], results = []
function observe(page) {
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (/hydration/i.test(message.text())) hydration.push(message.text()) })
}
async function closeFeedback(page) {
  const toasts = page.locator('[data-slot="viewport"] li[data-state="open"]')
  while (await toasts.count()) await toasts.first().locator('button').click()
}
try {
  for (const width of [1440, 390]) for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: baseUrl }])
    const page = await context.newPage(); observe(page)
    const response = await page.goto(baseUrl + '/__missing-stabilization-page')
    assert.equal(response.status(), 404)
    await page.getByRole('heading', { name: '这条路径，暂时没有内容' }).waitFor()
    await page.locator('[data-account-entry]:not([disabled])').waitFor()
    assert.equal(await page.locator('html').getAttribute('data-theme'), theme)
    assert.equal(await page.locator('main').count(), 1)
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    assert.equal(await page.getByRole('button', { name: '返回上一页', exact: true }).count(), 0)
    await page.screenshot({ path: resolve(output, `404-${theme}-${width}.png`), fullPage: true })
    await page.getByRole('link', { name: '技术文章 →', exact: true }).click()
    await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
    await page.goto(baseUrl + '/forbidden')
    await page.getByRole('heading', { name: '此处需要相应的访问权限' }).waitFor()
    await page.getByRole('link', { name: '前往登录', exact: true }).waitFor()
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    assert.match(await page.locator('main').innerText(), /实际访问仍由账号权限决定/)
    await closeFeedback(page)
    await page.screenshot({ path: resolve(output, `403-${theme}-${width}.png`), fullPage: true })
    await page.getByRole('link', { name: '前往登录', exact: true }).click()
    await page.getByRole('heading', { name: '登录你的账号', exact: true }).waitFor()
    await context.close(); results.push({ width, theme, status: 'passed' })
  }
  const context = await browser.newContext(), page = await context.newPage(); observe(page)
  let manageRequests = 0
  page.on('request', req => { if (new URL(req.url()).searchParams.get('view') === 'MANAGE') manageRequests++ })
  for (const path of ['/admin/projects', '/admin/blogs', '/account', '/login?returnTo=/admin/projects']) {
    await page.goto(baseUrl + path); await page.waitForURL('**/login?returnTo=**')
    await page.getByRole('heading', { name: '登录你的账号', exact: true }).waitFor()
  }
  assert.equal(manageRequests, 0)
  const missing = await page.goto(baseUrl + '/blog/__missing-stabilization-article'); assert.equal(missing.status(), 404)
  await page.getByRole('heading', { name: '这条路径，暂时没有内容' }).waitFor()
  await page.goto(baseUrl + '/blog'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
  await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/__missing-with-history'))
  await page.getByRole('button', { name: '返回上一页', exact: true }).waitFor()
  const back = page.getByRole('button', { name: '返回上一页', exact: true })
  await back.focus()
  assert.ok(await back.evaluate(el => el === document.activeElement && getComputedStyle(el).outlineStyle !== 'none'), '键盘焦点可见')
  await page.keyboard.press('Enter'); await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
  await context.close()
  const member = await browser.newContext()
  await member.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path === '/api/v1/auth/session' ? { loggedIn: true, username: 'fixture', roles: ['MEMBER'], csrfToken: 'fixture' } : { username: 'fixture', nickname: '测试账号', avatarUrl: null }
    return route.fulfill({ json: { code: 'OK', data, message: '', details: [] } })
  })
  const denied = await member.newPage(); observe(denied)
  await denied.goto(baseUrl + '/admin/projects'); await denied.waitForURL('**/forbidden')
  await denied.getByRole('link', { name: '查看当前账号', exact: true }).waitFor()
  assert.match(await denied.locator('main').innerText(), /你已登录，但当前账号没有/)
  assert.match(await denied.locator('main').innerText(), /重新登录不会自动获得权限/)
  assert.equal(await denied.getByRole('link', { name: '前往登录', exact: true }).count(), 0)
  await closeFeedback(denied)
  await denied.screenshot({ path: resolve(output, '403-authenticated.png'), fullPage: true })
  await member.close()
  assert.deepEqual(errors, []); assert.deepEqual(hydration, [])
  await writeFile(resolve(output, 'status-pages.json'), JSON.stringify({ results, hydration, errors, manageRequests }, null, 2))
  console.log('PASS: 8 状态页面、真实 SSR 404、匿名/非 OWNER 跳转、历史返回及键盘焦点，0 Hydration 警告')
} finally { clearTimeout(watchdog); await browser.close(); await server.close() }
