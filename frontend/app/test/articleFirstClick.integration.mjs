import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const label = process.env.PERFORMANCE_LABEL || process.argv[2] || 'after'
const output = resolve(process.env.PERFORMANCE_OUTPUT || '../docs/testing/performance/site-visual-stabilization-20261011')
await mkdir(output, { recursive: true })
const server = await chromium.launchServer({ headless: false, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}), args: ['--window-position=-32000,-32000', '--renderer-process-limit=1'] })
const timer = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 120000)
const browser = await chromium.connect(server.wsEndpoint())
const rows = []
try {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } })
    await context.addInitScript(() => { window.__tasks = []; new PerformanceObserver(e => window.__tasks.push(...e.getEntries().map(t => ({ start: t.startTime, duration: t.duration })))).observe({ type: 'longtask', buffered: true }) })
    const page = await context.newPage(), errors = []
    page.setDefaultTimeout(30000)
    page.on('pageerror', e => errors.push(e.message))
    let images = 0, api = 0
    page.on('request', r => { if (r.resourceType() === 'image') images++; if (r.url().includes('/blogs/by-slug/')) api++ })
    const cdp = await context.newCDPSession(page)
    await cdp.send('Performance.enable')
    await page.goto('http://127.0.0.1:3000/blog')
    await page.locator('[data-account-entry]:not([disabled])').waitFor()
    await cdp.send('HeapProfiler.collectGarbage')
    const measure = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(x => [x.name, x.value]))
    const before = await measure(), imageStart = images
    await page.evaluate(() => { window.__tasks = [] })
    await cdp.send('Profiler.enable'); await cdp.send('Profiler.start')
    const start = performance.now()
    await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click()
    await page.locator('.blog-renderer').waitFor()
    const enterMs = Math.round(performance.now() - start)
    await page.waitForTimeout(600)
    const initial = await measure(), initialImages = images - imageStart
    const data = await page.evaluate(() => ({ tasks: window.__tasks, articleNodes: document.querySelectorAll('.blog-renderer *').length, lazyImages: document.querySelectorAll('.blog-renderer img[loading="lazy"]').length, naturalImageMB: [...document.querySelectorAll('.blog-renderer img')].reduce((n, img) => n + img.naturalWidth * img.naturalHeight * 4, 0) / 1048576 }))
    await page.mouse.wheel(0, 1200)
    const idleStart = await measure(); await page.waitForTimeout(1500); const idle = await measure()
    const profile = (await cdp.send('Profiler.stop')).profile
    // CPU profile contains script call frames, not network requests or signed resource URLs.
    await writeFile(`${output}/${label}-${width}.cpuprofile`, JSON.stringify(profile))
    await page.screenshot({ path: `${output}/${label}-article-${width}.png` })
    rows.push({ label, width, enterMs, taskMs: Math.round((initial.TaskDuration - before.TaskDuration) * 1000), layoutMs: Math.round((initial.LayoutDuration - before.LayoutDuration) * 1000), scriptMs: Math.round((initial.ScriptDuration - before.ScriptDuration) * 1000), idleTaskMs: Math.round((idle.TaskDuration - idleStart.TaskDuration) * 1000), initialImages, articleRequests: api, heapMB: +(idle.JSHeapUsedSize / 1048576).toFixed(2), errors, ...data })
    console.log(JSON.stringify(rows.at(-1)))
    await context.close()
  }
} finally { clearTimeout(timer); await browser.close(); await server.close() }
await writeFile(`${output}/${label}-first-click.json`, JSON.stringify(rows, null, 2))
