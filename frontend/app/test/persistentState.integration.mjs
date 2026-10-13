import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { loadNuxt, build } from 'nuxt'

// 独立 Nuxt 构建、临时模拟后端和新浏览器上下文，不连接开发者的后端或浏览器。
// Playwright 可以来自本地工具运行时，不要求项目新增运行时依赖。
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const frontend = fileURLToPath(new URL('../../', import.meta.url))
const output = resolve(frontend, '../.local-backups/persistent-state-browser')
const snapshotKey = 'portfolio:account:v1'
const requests = []
const owner = { loggedIn: true, username: 'fixture', roles: ['OWNER'], csrfToken: 'fixture-csrf' }
const anonymous = { loggedIn: false, username: null, roles: [], csrfToken: null }
const profile = { username: 'fixture', nickname: '缓存测试', avatarUrl: null, avatarExpiresAt: null }
let session, accountProfile, savedProject, failure, browser, app, baseUrl
const hydrationErrors = []
const pageErrors = []
const envelope = data => ({ code: 'OK', data, message: '', details: [] })
const api = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  requests.push({ method: req.method, path: url.pathname, view: url.searchParams.get('view') })
  res.setHeader('Content-Type', 'application/json')
  if (failure && url.pathname === failure.path && (!failure.view || url.searchParams.get('view') === failure.view)) {
    res.statusCode = failure.status
    res.end(JSON.stringify({ code: failure.code, message: failure.message || '模拟拒绝', details: [], data: null }))
    return
  }
  let data
  if (url.pathname === '/api/v1/auth/session') {
    if (req.method === 'DELETE') session = anonymous
    data = req.method === 'DELETE' ? null : session
  } else if (url.pathname === '/api/v1/account/profile') {
    if (req.method === 'PUT') {
      let body = ''
      for await (const chunk of req) body += chunk
      accountProfile = { ...accountProfile, nickname: JSON.parse(body).nickname }
      assert.equal(req.headers['x-csrf-token'], session.csrfToken)
    }
    data = accountProfile
  } else if (url.pathname === '/api/v1/profile') {
    data = { displayName: '缓存测试', headline: 'Java 工程', intro: '隔离验证', githubUrl: null, email: null,
      avatarUrl: null, resumeUrl: null, avatarExpiresAt: null, resumeExpiresAt: null, techStack: [], updatedAt: '2026-10-08T00:00:00' }
  } else if (url.pathname === '/api/v1/projects') {
    if (req.method === 'POST') {
      let body = ''
      for await (const chunk of req) body += chunk
      savedProject = { ...JSON.parse(body), id: 7, version: 1, tags: [], cover: null, status: 'DRAFT',
        publishedAt: null, updatedAt: '2026-10-08T00:00:00' }
      data = savedProject
    } else data = { view: url.searchParams.get('view') || 'PUBLIC', page: 1, size: 4, total: 0, items: [] }
  } else if (url.pathname === '/api/v1/projects/7') {
    data = savedProject
  } else if (url.pathname === '/api/v1/blogs') {
    data = { view: 'PUBLIC', page: 1, size: 4, total: 0, items: [] }
  } else if (url.pathname === '/api/v1/tags') data = []
  else { res.statusCode = 404; data = null }
  res.end(JSON.stringify(envelope(data)))
})

before(async () => {
  await new Promise(resolve => api.listen(0, '127.0.0.1', resolve))
  const apiUrl = `http://127.0.0.1:${api.address().port}`
  const nuxt = await loadNuxt({ cwd: frontend, dev: false, overrides: {
    buildDir: output + '/.nuxt',
    routeRules: { '/api/v1/**': { proxy: apiUrl + '/api/v1/**' } },
    nitro: { output: { dir: output + '/.output', serverDir: output + '/.output/server', publicDir: output + '/.output/public' } },
  } })
  try { await build(nuxt) } finally { await nuxt.close() }
  const portProbe = createServer()
  await new Promise(resolve => portProbe.listen(0, '127.0.0.1', resolve))
  const port = portProbe.address().port
  await new Promise(resolve => portProbe.close(resolve))
  const childEnv = { ...process.env, HOST: '127.0.0.1', PORT: String(port), NITRO_HOST: '127.0.0.1', NITRO_PORT: String(port) }
  delete childEnv.NODE_TEST_CONTEXT // 应用子进程不继承 node:test 的二进制报告协议。
  let log = ''
  app = spawn(process.execPath, [output + '/.output/server/index.mjs'], {
    cwd: frontend, windowsHide: true, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'],
  })
  // 从实际监听地址读取随机端口，测试不占用或重启任何现有服务。
  baseUrl = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Nuxt 启动超时: ' + log)), 10000)
    function read(chunk) {
      log += chunk
      const match = log.match(/Listening on (http:\/\/127\.0\.0\.1:\d+)/)
      if (match) { clearTimeout(timer); resolve(match[1]) }
    }
    app.stdout.on('data', read); app.stderr.on('data', read)
    app.once('exit', code => { clearTimeout(timer); reject(new Error('Nuxt 提前退出: ' + code + log)) })
  })
  browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
}, { timeout: 180000 })

after(async () => {
  if (browser) await browser.close()
  if (app) app.kill()
  await new Promise(resolve => api.close(resolve))
  assert.deepEqual(hydrationErrors, [], '持久化恢复不能导致 SSR 水合不匹配')
  assert.deepEqual(pageErrors, [], '浏览器不能出现未处理异常')
})

function reset() { session = { ...owner }; accountProfile = { ...profile }; savedProject = null; failure = null; requests.length = 0 }
function count(path, method = 'GET') { return requests.filter(req => req.path === '/api/v1/' + path && req.method === method).length }
async function pageIn(context) {
  const page = await context.newPage()
  page.on('console', message => { if (/hydration/i.test(message.text())) hydrationErrors.push(message.text()) })
  page.on('pageerror', error => { pageErrors.push(error.message) })
  return page
}
async function accountVisible(page, name = '缓存测试') {
  await page.getByRole('button', { name: name + '，账户', exact: true }).waitFor()
}
async function snapshot(page) { return page.evaluate(key => JSON.parse(localStorage.getItem(key)), snapshotKey) }
function notifications(page) { return page.locator('[data-slot="viewport"] li[data-state="open"]') }
async function dismissFeedback(page, message) {
  const toasts = notifications(page)
  await toasts.first().waitFor()
  if (message) assert.ok((await toasts.allInnerTexts()).some(text => text.includes(message)))
  while (await toasts.count()) {
    const toast = toasts.first()
    assert.equal(await toast.evaluate(element => element.closest('main')), null, '通知由库挂载，不占业务页面位置')
    assert.equal(await toast.locator('button').count(), 1, '只提供库内置关闭按钮，无确认操作')
    await toast.locator('button').click()
  }
}
async function warmContext() {
  reset()
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } })
  const page = await pageIn(context)
  await page.goto(baseUrl + '/')
  await accountVisible(page)
  assert.equal(count('auth/session'), 1)
  assert.equal(count('account/profile'), 1)
  return { context, page }
}

test('首次刷新新锚点、重复刷新及阅读偏移首帧恢复，不重复查询账号', async () => {
  const { context, page } = await warmContext()
  try {
    await page.addInitScript(() => {
      window.scrollSamples = []
      const start = performance.now()
      function sample() {
        window.scrollSamples.push(scrollY)
        if (performance.now() - start < 1200) requestAnimationFrame(sample)
      }
      requestAnimationFrame(sample)
    })
    for (const section of ['projects', 'projects', 'blog', 'contact']) {
      await page.locator(`[data-nav-section="${section}"]`).click()
      await page.waitForTimeout(700)
      if (section === 'blog') await page.evaluate(() => scrollBy(0, 90))
      const before = await page.evaluate(() => scrollY)
      requests.length = 0
      await page.reload()
      await accountVisible(page)
      await page.waitForTimeout(200)
      const actual = await page.evaluate(() => ({ y: scrollY, first: window.scrollSamples[0] }))
      assert.ok(before > 0)
      assert.ok(Math.abs(actual.y - before) <= 1, JSON.stringify({ section, before, actual }))
      assert.ok(Math.abs(actual.first - before) <= 1, '首帧不能先绘制顶部: ' + JSON.stringify({ section, before, actual }))
      assert.equal(count('auth/session'), 0)
      assert.equal(count('account/profile'), 0)
      assert.equal(count('profile'), 1, '公开资料仍由每次 SSR 读取')
    }
  } finally { await context.close() }
})

test('昵称写入、跨标签页同步和退出后的刷新使用最新快照', async () => {
  const { context, page } = await warmContext()
  try {
    const other = await pageIn(context)
    await other.goto(baseUrl + '/')
    await accountVisible(other)
    requests.length = 0
    await page.goto(baseUrl + '/account')
    await page.locator('#account-nickname').fill('更新昵称')
    await page.getByRole('button', { name: '保存资料', exact: true }).click()
    await dismissFeedback(page, '昵称已保存')
    await accountVisible(page, '更新昵称')
    await accountVisible(other, '更新昵称')
    assert.equal((await snapshot(page)).profile.nickname, '更新昵称')
    await page.reload()
    await accountVisible(page, '更新昵称')
    assert.equal(count('account/profile', 'PUT'), 1)
    assert.equal(count('auth/session'), 0)
    assert.equal(count('account/profile'), 0)
    await page.getByRole('button', { name: '更新昵称，账户', exact: true }).click()
    await page.getByRole('menuitem', { name: '退出登录' }).click()
    await other.locator('[data-account-entry][aria-label="登录"]').waitFor()
    assert.equal((await snapshot(other)).profile, null)
    assert.equal((await snapshot(other)).session.loggedIn, false)
    requests.length = 0
    await other.reload()
    await other.locator('[data-account-entry][aria-label="登录"]').waitFor()
    assert.equal(count('auth/session'), 0)
  } finally { await context.close() }
})

test('OWNER 撤销后只更新权限，401 后刷新真实会话并移除账号缓存，不重放写入', async () => {
  const { context, page } = await warmContext()
  try {
    session = { ...owner, roles: [] }
    failure = { path: '/api/v1/projects', view: 'MANAGE', status: 403, code: 'FORBIDDEN' }
    requests.length = 0
    await page.goto(baseUrl + '/admin/projects')
    await page.waitForURL(baseUrl + '/forbidden')
    await dismissFeedback(page)
    await accountVisible(page)
    assert.equal(count('auth/session'), 1)
    assert.equal(count('auth/session', 'DELETE'), 0)
    assert.deepEqual((await snapshot(page)).session.roles, [])
    assert.equal((await snapshot(page)).session.loggedIn, true)
    failure = null
    await page.goto(baseUrl + '/account')
    await page.locator('#account-nickname').fill('不能写入')
    session = anonymous
    failure = { path: '/api/v1/account/profile', status: 401, code: 'UNAUTHORIZED' }
    requests.length = 0
    await page.getByRole('button', { name: '保存资料', exact: true }).click()
    await page.waitForURL(/\/login/)
    await dismissFeedback(page, '模拟拒绝')
    assert.equal(count('account/profile', 'PUT'), 1)
    assert.equal(count('auth/session'), 1)
    assert.equal((await snapshot(page)).session.loggedIn, false)
    assert.equal((await snapshot(page)).profile, null)
  } finally { await context.close() }
})

test('签名头像过期只重取资料，损坏快照按无缓存恢复', async () => {
  const { context, page } = await warmContext()
  try {
    await page.addInitScript(key => {
      const value = JSON.parse(localStorage.getItem(key))
      value.profile.avatarUrl = 'https://oss.invalid/avatar'
      value.profile.avatarExpiresAt = '2000-01-01T00:00:00Z'
      localStorage.setItem(key, JSON.stringify(value))
    }, snapshotKey)
    requests.length = 0
    await page.reload()
    await accountVisible(page)
    assert.equal(count('auth/session'), 0)
    assert.equal(count('account/profile'), 1)
    await page.addInitScript(key => localStorage.setItem(key, '{broken'), snapshotKey)
    requests.length = 0
    await page.reload()
    await accountVisible(page)
    assert.equal(count('auth/session'), 1)
    assert.equal(count('account/profile'), 1)
  } finally { await context.close() }
})

test('写入错误通知保留输入，关闭后重复同一失败仍提示，成功后恢复操作', async () => {
  const { context, page } = await warmContext()
  try {
    await page.goto(baseUrl + '/account')
    await page.locator('#account-nickname').fill('保留输入')
    failure = { path: '/api/v1/account/profile', status: 422, code: 'VALIDATION_FAILED', message: '<img src=x onerror=alert(1)> 无效昵称' }
    requests.length = 0
    for (let attempt = 0; attempt < 2; attempt++) {
      await page.getByRole('button', { name: '保存资料', exact: true }).click()
      await notifications(page).first().waitFor()
      assert.equal(await notifications(page).locator('img').count(), 0, '服务端消息仅按文字展示')
      assert.equal(await page.locator('#account-nickname').inputValue(), '保留输入')
      await dismissFeedback(page, '无效昵称')
    }
    assert.equal(count('account/profile', 'PUT'), 2, '只有用户明确重试才再次写入')
    failure = null
    await page.getByRole('button', { name: '保存资料', exact: true }).click()
    await dismissFeedback(page, '昵称已保存')
    await accountVisible(page, '保留输入')
  } finally { await context.close() }
})

test('新项目保存后的 Toast 跨路由保留，关闭后只读取已创建项目', async () => {
  const { context, page } = await warmContext()
  try {
    await page.goto(baseUrl + '/admin/projects/new')
    await page.locator('#project-title').fill('新草稿')
    requests.length = 0
    await page.getByRole('button', { name: '保存项目', exact: true }).click()
    await page.waitForURL(baseUrl + '/admin/projects/7')
    await dismissFeedback(page, '项目已保存')
    assert.equal(count('projects', 'POST'), 1)
    assert.equal(await page.locator('#project-title').inputValue(), '新草稿')
  } finally { await context.close() }
})

test('公开 SSR 读取失败在挂载后提示，无须确认就能直接重试恢复内容', async () => {
  const { context, page } = await warmContext()
  try {
    failure = { path: '/api/v1/profile', status: 503, code: 'SERVICE_UNAVAILABLE' }
    await page.goto(baseUrl + '/?read-failure=1')
    const toast = notifications(page).first()
    await toast.waitFor()
    assert.ok((await toast.innerText()).includes('资料暂时无法加载'))
    assert.ok(!(await toast.innerText()).includes('/api/v1/profile'), '不能把原始 FetchError 地址作为提示')
    failure = null
    // 通知尚在显示时直接操作页面，不需要先关闭或确认。
    await page.getByRole('button', { name: '重新读取资料', exact: true }).click()
    await page.locator('#projects').waitFor()
    assert.equal(await page.getByRole('dialog').count(), 0)
  } finally { await context.close() }
})

test('Toast 在页面上方居中，不抢输入焦点，出现、自动消失和手动关闭均不改变布局', async () => {
  const { context, page } = await warmContext()
  try {
    await page.goto(baseUrl + '/account')
    const input = page.locator('#account-nickname')
    await input.fill('布局验证')
    failure = { path: '/api/v1/account/profile', status: 422, code: 'VALIDATION_FAILED' }
    const before = await page.evaluate(() => {
      function measure() {
        const rect = document.querySelector('main').getBoundingClientRect()
        return { x: rect.x, width: rect.width, y: scrollY, clientWidth: document.documentElement.clientWidth,
          layoutWidth: document.documentElement.getBoundingClientRect().width,
          overflow: getComputedStyle(document.body).overflow, padding: getComputedStyle(document.body).paddingRight }
      }
      window.toastLayoutSamples = []
      window.toastFocusEvents = []
      document.addEventListener('focusin', event => {
        window.toastFocusEvents.push(!!event.target.closest('[data-slot="viewport"]'))
      })
      const start = performance.now()
      function sample() {
        window.toastLayoutSamples.push(measure())
        if (performance.now() - start < 7000) requestAnimationFrame(sample)
      }
      requestAnimationFrame(sample)
      return measure()
    })
    await input.press('Enter')
    const toast = notifications(page).first()
    await toast.waitFor()
    assert.equal(await page.evaluate(() => !!document.activeElement.closest('[data-slot="viewport"]')), false,
      '显示通知不得将焦点移入通知')
    // 提交时业务表单暂时 disabled，浏览器会取消原焦点；通知显示后仍可立即继续输入。
    await input.focus()
    // 等库内置进入动画结束再测位置，布局采样则涵盖整个动画。
    await page.waitForTimeout(350)
    const box = await toast.boundingBox()
    const viewport = page.viewportSize()
    await page.screenshot({ path: output + '/toast-top-center.png' })
    // 页面预留滚动条槽；按实际布局宽度检查中心，与 Nuxt UI 的 fixed viewport 一致。
    assert.ok(Math.abs(box.x + box.width / 2 - before.layoutWidth / 2) <= 1,
      '水平居中: ' + JSON.stringify({ box, viewport, layoutWidth: before.layoutWidth }))
    assert.ok(box.y >= 0 && box.y < 50, '位于视口上方')
    assert.equal(await input.evaluate(element => document.activeElement === element), true, '保留输入焦点')
    assert.ok((await page.evaluate(() => window.toastFocusEvents)).every(insideToast => !insideToast),
      '通知不能主动抢焦点')
    assert.equal(await page.getByRole('dialog').count(), 0, '不挂载模态弹窗')
    assert.equal(await page.locator('main[inert], main[aria-hidden="true"]').count(), 0)
    await toast.waitFor({ state: 'hidden', timeout: 8000 }) // 库默认计时自动消失，无需确认。
    const samples = await page.evaluate(() => window.toastLayoutSamples)
    assert.ok(samples.length > 10)
    for (const sample of samples) assert.deepEqual(sample, before, '出现和自动消失时页面不能抖动')
    await input.press('Enter')
    await dismissFeedback(page, '模拟拒绝')
    await page.waitForTimeout(350) // 包含组件库关闭动画，而非只检查点击瞬间。
    const after = await page.evaluate(() => {
      const rect = document.querySelector('main').getBoundingClientRect()
      return { x: rect.x, width: rect.width, y: scrollY, clientWidth: document.documentElement.clientWidth,
        layoutWidth: document.documentElement.getBoundingClientRect().width,
        overflow: getComputedStyle(document.body).overflow, padding: getComputedStyle(document.body).paddingRight }
    })
    assert.deepEqual(after, before, '手动关闭也不能改变页面位置')
    for (const sample of await page.evaluate(() => window.toastLayoutSamples)) {
      assert.deepEqual(sample, before, '包括手动关闭动画在内，页面布局必须保持稳定')
    }
  } finally { await context.close() }
})
