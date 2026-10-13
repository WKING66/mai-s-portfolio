import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

// 只连接 3000/9333，不启动应用或模拟后端。样例 API 在隔离浏览器内拦截，禁止真实写入。
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = process.env.ACCEPTANCE_URL || 'http://127.0.0.1:3000'
const baseline = process.argv.includes('--baseline')
const label = process.env.PERFORMANCE_LABEL || (baseline ? 'before' : 'after')
const output = resolve(process.env.PERFORMANCE_OUTPUT || '../docs/testing/performance/site-visual-stabilization-20261011')
await mkdir(output, { recursive: true })
const markdown = {
  short: '## 短文章\n\n清晰的内容，稳定的交互。',
  long: '## 长文章\n\n' + '可靠的软件需要明确边界与可验证的行为。Readable content and bounded work.\n\n'.repeat(1000),
  code: '## 多个代码块\n\n' + ('```typescript [Long.ts]\nconst value = "' + 'long code line '.repeat(300) + '";\n```\n\n').repeat(60),
  images: '## 大量图片\n\n' + Array.from({ length: 120 }, (_, i) => `插图 ${i + 1}\n\n![测试插图 ${i + 1}](/__performance-image/${i}.svg)\n\n`).join(''),
  table: '## 复杂表格\n\n|' + '字段|'.repeat(12) + '\n|' + '---|'.repeat(12) + '\n' + ('|' + '有边界的数据与明确的职责|'.repeat(12) + '\n').repeat(250),
}
const server = await chromium.launchServer({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}), args: ['--renderer-process-limit=1'] })
const watchdog = setTimeout(() => {
  if (process.platform === 'win32') execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
  else server.process().kill('SIGKILL')
  process.exit(124)
}, 300000)
const browser = await chromium.connect(server.wsEndpoint())
const rows = [], errors = []
const envelope = data => ({ code: 'OK', data, message: '', details: [] })
const metrics = async cdp => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(x => [x.name, x.value]))
async function navigate(page, path) {
  await page.evaluate(path => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push(path), path)
}
try {
  for (const width of [1440, 390]) for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: base }])
    let imageRequests = 0, articleRequests = 0
    await context.route('**/api/v1/**', async route => {
      assert.equal(route.request().method(), 'GET', '性能检查不写入真实数据')
      const path = new URL(route.request().url()).pathname
      if (path.includes('/blogs/by-slug/__performance-')) {
        articleRequests++
        const kind = path.split('__performance-')[1]
        await route.fulfill({ json: envelope({ id: 9000, slug: '__performance-' + kind, title: '性能样例 ' + kind, summary: '', content: markdown[kind], documentVersionId: 9000, resources: [], allowDownload: false, publishedAt: '2026-10-11T00:00:00Z' }) })
      } else await route.continue()
    })
    await context.route('**/__performance-image/**', async route => {
      imageRequests++
      await route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"><rect width="800" height="600" fill="#36776d"/></svg>' })
    })
    await context.addInitScript(() => {
      window.__performanceTasks = []
      new PerformanceObserver(entries => { window.__performanceTasks.push(...entries.getEntries().map(entry => ({ start: entry.startTime, duration: entry.duration }))) }).observe({ type: 'longtask', buffered: true })
    })
    const page = await context.newPage()
    page.setDefaultTimeout(30000)
    page.on('pageerror', e => errors.push(e.message))
    page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    const cdp = await context.newCDPSession(page)
    await cdp.send('Performance.enable')
    await page.goto(base + '/blog', { waitUntil: 'networkidle', timeout: 20000 })
    await page.locator('[data-account-entry]:not([disabled])').waitFor()
    for (const kind of Object.keys(markdown)) {
      await cdp.send('HeapProfiler.collectGarbage')
      const start = performance.now(), before = await metrics(cdp), imageStart = imageRequests
      await page.evaluate(() => { window.__performanceTasks = [] })
      await navigate(page, '/blog/__performance-' + kind)
      await page.locator('.blog-renderer').waitFor()
      const enterMs = Math.round(performance.now() - start)
      await page.waitForTimeout(300)
      const entered = await metrics(cdp)
      const facts = await page.evaluate(() => ({ htmlNodes: document.querySelectorAll('.blog-renderer *').length, tasks: window.__performanceTasks, lazyImages: [...document.querySelectorAll('.blog-renderer img')].filter(img => img.loading === 'lazy' && img.decoding === 'async').length, overflow: document.documentElement.scrollWidth > innerWidth }))
      const initialImages = imageRequests - imageStart
      assert.equal(facts.overflow, false)
      if (!baseline && kind === 'images') { assert.equal(facts.lazyImages, 120); assert.ok(initialImages < 20, '图片按视口加载，不同时加载 120 张') }
      const idleStart = await metrics(cdp)
      await page.waitForTimeout(600)
      const idle = await metrics(cdp)
      assert.ok((idle.TaskDuration - idleStart.TaskDuration) * 1000 < 400, '页面能恢复空闲')
      await page.mouse.wheel(0, 1500)
      await page.waitForTimeout(200)
      if (!baseline && kind === 'images') {
        await page.locator('.blog-renderer img').last().scrollIntoViewIfNeeded()
        await page.waitForFunction(() => {
          const image = [...document.querySelectorAll('.blog-renderer img')].at(-1)
          return image?.complete && image.naturalWidth > 0
        })
      }
      if (!baseline && kind === 'long') {
        await page.locator('.blog-renderer p').last().scrollIntoViewIfNeeded()
        assert.ok(await page.locator('.blog-renderer p').last().evaluate(el => { const box = el.getBoundingClientRect(); return box.top < innerHeight && box.bottom > 0 }), '屏外末段仍可正常阅读')
      }
      await page.goBack(); await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
      await page.goForward(); await page.locator('.blog-renderer').waitFor()
      await navigate(page, '/blog')
      await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
      await cdp.send('HeapProfiler.collectGarbage')
      const released = await metrics(cdp)
      rows.push({ label, width, theme, kind, enterMs, taskMs: Math.round((entered.TaskDuration - before.TaskDuration) * 1000), layoutMs: Math.round((entered.LayoutDuration - before.LayoutDuration) * 1000), scriptMs: Math.round((entered.ScriptDuration - before.ScriptDuration) * 1000), idleTaskMs: Math.round((idle.TaskDuration - idleStart.TaskDuration) * 1000), initialImages, imageRequests: imageRequests - imageStart, heapBeforeMB: +(before.JSHeapUsedSize / 1048576).toFixed(2), heapAfterLeaveMB: +(released.JSHeapUsedSize / 1048576).toFixed(2), nodesAfterLeave: released.Nodes, ...facts })
    }
    const requestCount = articleRequests
    for (let repeat = 0; repeat < 4; repeat++) { await navigate(page, '/blog/__performance-long'); await page.locator('.blog-renderer').waitFor(); await navigate(page, '/blog') }
    assert.equal(articleRequests, requestCount, '再次进入相同公开投影复用缓存')
    await context.close()
    console.log(JSON.stringify({ label, width, theme, samples: Object.keys(markdown).length, result: 'passed' }))
  }
  assert.deepEqual(errors, [], '无页面异常或水合警告')
} finally {
  clearTimeout(watchdog)
  await browser.close(); await server.close()
  await writeFile(resolve(output, label + '.json'), JSON.stringify({ rows, errors }, null, 2))
}
