import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000'
const items = (await (await fetch(base + '/api/v1/blogs')).json()).data.items
const article = items.find(item => item.title.includes('数据结构与算法'))
const output = resolve(process.env.PERFORMANCE_DIRECT_OUTPUT || '../docs/testing/performance/blog-reader-fix-20261011')
await mkdir(output, { recursive: true })
const server = await chromium.launchServer({ headless: false, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--window-position=-32000,-32000', '--renderer-process-limit=1', '--js-flags=--max-old-space-size=512'] })
const timer = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true }); process.exit(124) }, 120000)
const browser = await chromium.connect(server.wsEndpoint())
const rows = [], errors = []
try {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' })
    await context.addInitScript(() => {
      window.tasks = []
      new PerformanceObserver(list => window.tasks.push(...list.getEntries().map(item => ({ start: item.startTime, duration: item.duration })))).observe({ type: 'longtask', buffered: true })
    })
    const page = await context.newPage(); page.setDefaultTimeout(20000)
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    const cdp = await context.newCDPSession(page); await cdp.send('Performance.enable')
    await cdp.send('Tracing.start', { categories: 'toplevel,devtools.timeline,v8,v8.execute,blink.user_timing', transferMode: 'ReturnAsStream' })
    const started = performance.now()
    await page.goto(base + '/blog/' + encodeURIComponent(article.slug))
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
    await page.locator('[data-account-entry]:not([disabled])').waitFor()
    assert.equal(await page.locator('.blog-renderer [data-reading-pending="true"]').count(), 0, '准备完成后所有 SSR 正文都可见')
    await page.waitForTimeout(1000)
    const metrics = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(item => [item.name, item.value]))
    const tasks = await page.evaluate(() => window.tasks)
    const completed = new Promise(resolve => cdp.once('Tracing.tracingComplete', resolve))
    await cdp.send('Tracing.end'); const { stream } = await completed
    let raw = ''; while (true) { const chunk = await cdp.send('IO.read', { handle: stream }); raw += chunk.data; if (chunk.eof) break }
    await cdp.send('IO.close', { handle: stream })
    const trace = JSON.parse(raw)
    await writeFile(resolve(output, 'direct-' + width + '.trace.json'), JSON.stringify(trace, (_key, value) => typeof value === 'string' && /^https?:/.test(value) ? value.split('?')[0] : value))
    const navigation = await page.evaluate(() => { const entry = performance.getEntriesByType('navigation')[0]; return { responseStart: entry.responseStart, domInteractive: entry.domInteractive, loadEventEnd: entry.loadEventEnd } })
    rows.push({ width, loadedMs: Math.round(performance.now() - started), navigation, tasks, nodes: metrics.Nodes, heapMB: metrics.JSHeapUsedSize / 1048576, layoutMs: metrics.LayoutDuration * 1000 })
    console.log(JSON.stringify(rows.at(-1)))
    if (!process.env.PERFORMANCE_BASELINE) assert.ok(tasks.every(item => item.duration < 250), '直接 SSR 阅读无严重阻塞')
    // 连续重入与提前离开只读测试，检查完整挂载后的堆回收及待挂载帧清理。
    for (let index = 0; index < 3; index++) {
      await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog'))
      await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
      await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click()
      if (index === 0) {
        await page.locator('.blog-renderer').waitFor()
        await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog'))
        await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
        await page.waitForTimeout(500)
        assert.equal(await page.locator('.blog-renderer').count(), 0)
      } else await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
      await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog'))
      await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
      await page.waitForTimeout(500)
      await cdp.send('HeapProfiler.collectGarbage')
      const next = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(item => [item.name, item.value]))
      rows.push({ width, cycle: index, heapAfterGC: next.JSHeapUsedSize / 1048576, nodes: next.Nodes })
      assert.ok(next.JSHeapUsedSize < 128 * 1048576, '反复重入堆有界')
      if (index === 2) {
        const cycles = rows.filter(row => row.width === width && row.cycle !== undefined)
        assert.ok(cycles.at(-1).heapAfterGC - cycles.at(-2).heapAfterGC < 5, '连续离开后堆不随每篇整树累加')
      }
    }
    if (process.env.PERFORMANCE_HEAP_SNAPSHOT) {
      const chunks = []; cdp.on('HeapProfiler.addHeapSnapshotChunk', event => chunks.push(event.chunk))
      await cdp.send('HeapProfiler.takeHeapSnapshot', { reportProgress: false })
      await writeFile(resolve(process.env.PERFORMANCE_HEAP_SNAPSHOT, 'reading-' + width + '.heapsnapshot'), chunks.join(''))
    }
    await context.close()
  }
  assert.deepEqual(errors, [])
} finally {
  await writeFile(resolve(output, 'direct-reading.json'), JSON.stringify({ rows, errors }, null, 2))
  clearTimeout(timer); await browser.close(); await server.close()
}
