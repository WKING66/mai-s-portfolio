import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { gzipSync } from 'node:zlib'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000', label = process.env.EXPERIENCE_LABEL || 'before'
const [width, height] = (process.env.EXPERIENCE_VIEWPORT || '1280x800').split('x').map(Number)
assert.ok(width >= 1200 && height >= 500, '桌面追踪使用实际可见的宽屏目录布局')
const viewport = { width, height }
const output = resolve(process.env.EXPERIENCE_OUTPUT || '../docs/testing/performance/blog-experience-20261012')
await mkdir(output, { recursive: true })
const articles = (await (await fetch(base + '/api/v1/blogs')).json()).data.items
const card = articles.find(item => item.title.includes('数据结构与算法'))
assert.ok(card, '实际故障文章存在')
const article = (await (await fetch(base + '/api/v1/blogs/by-slug/' + encodeURIComponent(card.slug))).json()).data
const server = await chromium.launchServer({ headless: false, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--window-position=60,60', '--window-size=1440,1000', '--renderer-process-limit=1', '--js-flags=--max-old-space-size=512'] })
const timeout = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 180000)
const browser = await chromium.connect(server.wsEndpoint())
const rows = [], errors = []
const context = await browser.newContext({ viewport, reducedMotion: 'no-preference', ...(process.env.EXPERIENCE_VIDEO === '1' ? { recordVideo: { dir: resolve(output, label + '-video'), size: viewport } } : {}) })
const page = await context.newPage().catch(async error => { clearTimeout(timeout); await context.close(); await browser.close(); await server.close(); throw error }); page.setDefaultTimeout(15000)
page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
await context.addInitScript(isolateToc => {
  window.__experience = { tasks: [], frames: [], loaf: [], updates: {}, mounts: {}, unmounts: {}, reads: 0, parses: {}, resizes: {}, bodySizeWrites: 0, visibility: [], start: 0, stop: 0 }
  document.addEventListener('visibilitychange', () => window.__experience.visibility.push({time:performance.now(),state:document.visibilityState}))
  const NativeObserver = ResizeObserver
  window.ResizeObserver = class extends NativeObserver {
    targets = new Set()
    constructor(callback) { super((entries, observer) => {
      const chapterObserver = [...this.targets].some(el => el.classList.contains('blog-chapter-body'))
      const tocObserver = !chapterObserver && [...this.targets].some(el => el.classList.contains('blog-renderer'))
      const name = chapterObserver ? 'chapter' : tocObserver ? 'toc' : 'other'
      const counts = window.__experience.resizes; counts[name] = (counts[name] || 0) + 1
      if (!(isolateToc && tocObserver)) callback(entries, observer)
    }) }
    observe(element, options) { this.targets.add(element); super.observe(element, options) }
    unobserve(element) { this.targets.delete(element); super.unobserve(element) }
    disconnect() { this.targets.clear(); super.disconnect() }
  }
  const setProperty = CSSStyleDeclaration.prototype.setProperty
  CSSStyleDeclaration.prototype.setProperty = function (name, ...args) { if (name === '--reading-body-height') window.__experience.bodySizeWrites++; return setProperty.call(this, name, ...args) }
  window.__blogReadingProbe = name => { const counts = window.__experience.parses; counts[name] = (counts[name] || 0) + 1 }
  const NativeWorker = Worker
  window.Worker = class extends NativeWorker {
    constructor(...args) { super(...args); this.addEventListener('message', ({ data }) => { for (const [name, n] of Object.entries(data.counts || {})) window.__experience.parses[name] = (window.__experience.parses[name] || 0) + n }) }
    postMessage(data) { super.postMessage({ ...data, probe: true }) }
  }
  const rect = Element.prototype.getBoundingClientRect
  Element.prototype.getBoundingClientRect = function () { if (this.matches('.blog-renderer :is(h1,h2,h3,h4,h5,h6)')) window.__experience.reads++; return rect.call(this) }
  new PerformanceObserver(list => window.__experience.tasks.push(...list.getEntries().map(item => ({ start: item.startTime, duration: item.duration })))).observe({ type: 'longtask', buffered: true })
  if (PerformanceObserver.supportedEntryTypes.includes('long-animation-frame')) new PerformanceObserver(list => window.__experience.loaf.push(...list.getEntries().map(item => ({ start: item.startTime, duration: item.duration, blocking: item.blockingDuration, scripts: item.scripts.map(script => ({ function: script.sourceFunctionName, forcedLayout: script.forcedStyleAndLayoutDuration, duration: script.duration })) })))).observe({ type: 'long-animation-frame', buffered: true })
  function frame(time) { if (window.__experience.start && !window.__experience.stop) window.__experience.frames.push(time); requestAnimationFrame(frame) }
  requestAnimationFrame(frame)
}, process.env.EXPERIENCE_OBSERVER_ISOLATION === 'toc')
try {
  await page.goto(base + '/'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
  await page.evaluate(() => {
    const app = document.querySelector('#__nuxt').__vue_app__
    function count(instance, event) { const name = instance.$.type.__name || instance.$.type.name || 'anonymous'; const counts = window.__experience[event]; counts[name] = (counts[name] || 0) + 1 }
    app.mixin({ beforeUpdate() { count(this, 'updates') }, mounted() { count(this, 'mounts') }, unmounted() { count(this, 'unmounts') } })
  })
  const cdp = await context.newCDPSession(page); await cdp.send('Performance.enable')
  const root = await browser.newBrowserCDPSession()
  const gpu = await root.send('SystemInfo.getInfo').catch(() => ({ unavailable: true }))
  await cdp.send('Tracing.start', { categories: 'toplevel,devtools.timeline,disabled-by-default-devtools.timeline,disabled-by-default-devtools.timeline.frame,disabled-by-default-devtools.timeline.invalidationTracking,cc,gpu,viz,blink.user_timing,v8', transferMode: 'ReturnAsStream' })
  const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(item => [item.name, item.value]))
  async function phase(name, action, settle = 250) {
    const before = await metrics()
    if (name === 'leave' && process.env.EXPERIENCE_PROFILE === '1') { await cdp.send('Profiler.enable'); await cdp.send('Profiler.start') }
    await page.evaluate(name => { Object.assign(window.__experience, { tasks: [], frames: [], loaf: [], updates: {}, mounts: {}, unmounts: {}, reads: 0, parses: {}, resizes: {}, bodySizeWrites: 0, visibility: [{time:performance.now(),state:document.visibilityState}], start: performance.now(), stop: 0 }); performance.mark('experience:start:' + name) }, name)
    const start = performance.now(); await action(); const responseMs = Math.round(performance.now() - start)
    if (name === 'leave' && process.env.EXPERIENCE_PROFILE === '1') { const { profile } = await cdp.send('Profiler.stop'); await writeFile(resolve(output, label + '-leave.cpuprofile'), JSON.stringify(profile)); await cdp.send('Profiler.disable') }
    await page.waitForTimeout(settle)
    const sample = await page.evaluate(name => { window.__experience.stop = performance.now(); performance.mark('experience:end:' + name); return JSON.parse(JSON.stringify(window.__experience)) }, name)
    const after = await metrics(), gaps = sample.frames.slice(1).map((time, index) => time - sample.frames[index]).sort((a, b) => a - b)
    const row = { name, responseMs, duration: sample.stop - sample.start, frames: gaps.length, rafP95: gaps[Math.floor(gaps.length * .95)] || 0, rafMax: gaps.at(-1) || 0, rafGapsOver33: gaps.filter(gap => gap > 33.4).length, tasks: sample.tasks, loaf: sample.loaf, updates: sample.updates, mounts: sample.mounts, unmounts: sample.unmounts, headingReads: sample.reads, parses: sample.parses, resizes: sample.resizes, bodySizeWrites: sample.bodySizeWrites, visibility:sample.visibility, heapMB: after.JSHeapUsedSize / 1048576, nodes: after.Nodes, layoutMs: (after.LayoutDuration - before.LayoutDuration) * 1000, styleMs: (after.RecalcStyleDuration - before.RecalcStyleDuration) * 1000 }
    rows.push(row); await writeFile(resolve(output, label + '-experience.json'), JSON.stringify({ label, browser: browser.version(), article: { slug: card.slug, characters: article.content.length, resources: article.resources.length }, rows, errors }, null, 2))
    console.log(JSON.stringify({ name, responseMs, rafP95: row.rafP95, rafMax: row.rafMax, gapsOver33: row.rafGapsOver33, headingReads: row.headingReads, layoutMs: row.layoutMs, styleMs: row.styleMs, updates: row.updates, loaf: row.loaf }))
    assert.ok(row.heapMB < 384, '内存达到安全闸，停止')
    if (['collapse', 'expand', 'toc-last', 'toc-rapid', 'whole-article-down-up'].includes(name)) {
      assert.equal(sample.parses.markdownParse || 0, 0, '阅读交互不重新解析全文')
      assert.equal(sample.parses.shikiTokenize || 0, 0, '阅读交互不重新高亮代码')
      assert.equal(sample.parses.chapterTree || 0, 0, '阅读交互不重新构建章节树')
      assert.equal(sample.parses.katexRender || 0, 0, '阅读交互不重新解析公式')
      assert.equal(sample.mounts.BlogChapter || 0, 0, '阅读交互不重新挂载章节')
      assert.equal(sample.unmounts.BlogChapter || 0, 0, '阅读交互不卸载章节')
      if (name === 'whole-article-down-up') assert.ok(sample.reads < 3000, '全篇往返滚动不能重复全量测量目录')
    }
  }
  await phase('home-wheel', async () => { await page.mouse.move(1000, 500); for (let i = 0; i < 12; i++) { await page.mouse.wheel(0, 300); await page.waitForTimeout(90) } })
  await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog'))
  await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
  await phase('first-reading', async () => { await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click(); await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor() }, 500)
  const facts = await page.evaluate(() => {
    const body = document.querySelector('.blog-renderer'), chapters = [...body.querySelectorAll('.blog-chapter')]
    return { headings: body.querySelectorAll('h1,h2,h3,h4,h5,h6').length, bodyNodes: body.querySelectorAll('*').length, codes: body.querySelectorAll('.blog-code').length, math: body.querySelectorAll('.blog-math').length, images: body.querySelectorAll('img').length, tables: body.querySelectorAll('table').length, largest: chapters.map(el => ({ title: el.querySelector('.blog-chapter-heading button').getAttribute('aria-label').replace(/^收起章节：/, ''), nodes: el.querySelectorAll('*').length })).sort((a,b) => b.nodes-a.nodes)[0] }
  })
  rows.at(-1).facts = facts
  if (process.env.EXPERIENCE_CSS_ISOLATION) {
    if (process.env.EXPERIENCE_CSS_ISOLATION.includes('transition')) await page.addStyleTag({ content: '.blog-code, .blog-code * { transition: none !important; }' })
    if (process.env.EXPERIENCE_CSS_ISOLATION.includes('leaf')) {
      await page.addStyleTag({ content: '.blog-chapter-body:focus-within { content-visibility: visible !important; }' })
      await page.evaluate(() => {
      for (const chapter of document.querySelectorAll('.blog-chapter')) {
        const body = chapter.querySelector(':scope > .blog-chapter-body')
        if (!body?.querySelector('.blog-chapter') && body?.children.length) {
          body.style.containIntrinsicBlockSize = 'auto ' + body.getBoundingClientRect().height + 'px'
          body.style.contentVisibility = 'auto'
        }
      }
      })
    }
  }
  await page.mouse.move(1000, 500)
  await phase('steady-wheel', async () => { for (let i = 0; i < 35; i++) { await page.mouse.wheel(0, 250); await page.waitForTimeout(70) } })
  if (!process.env.EXPERIENCE_OBSERVER_ISOLATION) assert.ok(await page.locator('.blog-toc-desktop .blog-toc-scroll').evaluate(list => {
    const item = list.querySelector('[aria-current="location"]'), bounds = list.getBoundingClientRect(), selected = item?.getBoundingClientRect()
    return selected && selected.top >= bounds.top - 1 && selected.bottom <= bounds.bottom + 1
  }), '正文滚动后目录高亮始终在目录可视区域内')
  await phase('fast-down-up', async () => { for (const delta of [1000,1000,1000,1000,1000,1000,-1200,-1200,-1200,-1200,-1200]) { await page.mouse.wheel(0, delta); await page.waitForTimeout(45) } })
  if (process.env.EXPERIENCE_SUSTAINED === '1') await phase('whole-article-down-up', async () => {
    await page.evaluate(() => scrollTo(0, 0))
    for (const direction of [1, -1, 1, -1]) for (let index = 0; index < 65; index++) { await page.mouse.wheel(0, direction * 1500); await page.waitForTimeout(55) }
  })
  await page.getByRole('button', { name: '收起章节：' + facts.largest.title, exact: true }).scrollIntoViewIfNeeded(); await page.waitForTimeout(300)
  await phase('collapse', () => page.getByRole('button', { name: '收起章节：' + facts.largest.title, exact: true }).click())
  await phase('expand', () => page.getByRole('button', { name: '展开章节：' + facts.largest.title, exact: true }).click())
  const toc = page.getByRole('navigation', { name: '文章目录', exact: true })
  await phase('toc-last', () => toc.getByRole('link').last().click())
  await phase('toc-rapid', async () => { for (const index of [1,15,50,5,80]) await toc.getByRole('link').nth(index).click() })
  const code = page.locator('.blog-code').first(); await code.scrollIntoViewIfNeeded()
  await phase('code-theme', async () => { await code.getByRole('combobox').click(); await code.getByRole('option', { name: 'Dracula', exact: true }).click() })
  await phase('leave', async () => { await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog')); await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor() })
  await phase('return', async () => { await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click(); await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor() })
  await phase('idle', () => page.waitForTimeout(1500), 0)
  const completed = new Promise(resolve => cdp.once('Tracing.tracingComplete', resolve)); await cdp.send('Tracing.end'); const { stream } = await completed
  let raw = ''; while (true) { const chunk = await cdp.send('IO.read', { handle: stream }); raw += chunk.data; if (chunk.eof) break }; await cdp.send('IO.close', { handle: stream })
  const trace = JSON.parse(raw)
  for (const row of rows) {
    const events = trace.traceEvents, start = events.find(e => e.name === 'experience:start:' + row.name), end = events.find(e => e.name === 'experience:end:' + row.name)
    const span = events.filter(e => e.ts >= start.ts && e.ts < end.ts)
    const main = span.filter(e => e.pid === start.pid && e.tid === start.tid && e.ph === 'X' && e.dur)
    const union = intervals => { let total = 0, end = -Infinity; for (const [a,b] of intervals.sort((a,b) => a[0]-b[0])) { total += Math.max(0,b-Math.max(a,end)); end = Math.max(end,b) }; return total }
    const scripts = main.filter(e => ['FunctionCall','EvaluateScript','RunMicrotasks','EventDispatch','TimerFire','FireAnimationFrame'].includes(e.name)).map(e => [e.ts,e.ts+e.dur])
    const rendering = main.filter(e => ['Layout','UpdateLayoutTree','Paint'].includes(e.name)).map(e => [e.ts,e.ts+e.dur])
    const overlap = scripts.flatMap(([a,b]) => rendering.map(([c,d]) => [Math.max(a,c),Math.min(b,d)]).filter(([a,b]) => b>a))
    row.scriptingMs = (union(scripts)-union(overlap))/1000
    row.trace = Object.fromEntries(['Layout','UpdateLayoutTree','Paint','RasterTask','Decode Image','ImageDecodeTask','CompositeLayers','DrawFrame','DroppedFrame','FramePresented','BeginFrame'].map(name => { const relevant = span.filter(e => e.name === name); return [name, { count: relevant.length, durationMs: relevant.reduce((n,e) => n + (e.dur || 0),0)/1000 }] }))
    row.expensive = span.filter(e => e.ph === 'X' && e.dur > 10000).sort((a,b) => b.dur-a.dur).slice(0,12).map(e => ({ name:e.name, ms:e.dur/1000, pid:e.pid, tid:e.tid }))
  }
  await writeFile(resolve(output, label + '.trace.json.gz'), gzipSync(Buffer.from(JSON.stringify(trace, (_key,value) => typeof value === 'string' && /^https?:/.test(value) ? value.split('?')[0] : value))))
  await writeFile(resolve(output, label + '-gpu.json'), JSON.stringify({ gpu: gpu.gpu, browser: browser.version() }, null, 2))
  await page.screenshot({ path: resolve(output, label + '-reading.png') })
  assert.deepEqual(errors, [])
} finally {
  await writeFile(resolve(output, label + '-experience.json'), JSON.stringify({ label, browser: browser.version(), article: { slug: card.slug, characters: article.content.length, resources: article.resources.length }, viewport, video: process.env.EXPERIENCE_VIDEO === '1', profiler: process.env.EXPERIENCE_PROFILE === '1', input: '隔离可见 Edge，Playwright 驱动真实 wheel/click；非维护者人工验收', frameNote: 'rAF gap 是主线程帧回调间隔，不等价于 GPU FPS；Trace 单独记录合成/Raster', rows, errors }, null, 2))
  const recording = page.video()
  await context.close()
  if (recording) await recording.saveAs(resolve(output, label + '-visible-edge.webm'))
  clearTimeout(timeout); await browser.close(); await server.close()
}
