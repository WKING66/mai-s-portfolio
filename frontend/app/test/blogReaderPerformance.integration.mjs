import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

// 单个隔离 Edge，先受控短文再真实文章；不写文章、不另起服务。保留失败前的采样。
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000'
const label = process.env.PERFORMANCE_LABEL || 'after'
const isolation = process.env.PERFORMANCE_CSS_ISOLATION
const baseline = label === 'before' || Boolean(isolation)
const output = resolve(process.env.PERFORMANCE_OUTPUT || '../docs/testing/performance/blog-reader-fix-20261011')
await mkdir(output, { recursive: true })
const list = (await (await fetch(base + '/api/v1/blogs')).json()).data
const card = list.items.find(item => item.title.includes('数据结构与算法'))
assert.ok(card, '真实故障文章存在')
const article = (await (await fetch(base + '/api/v1/blogs/by-slug/' + encodeURIComponent(card.slug))).json()).data
const server = await chromium.launchServer({ headless: false, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--window-position=-32000,-32000', '--renderer-process-limit=1', '--js-flags=--max-old-space-size=512'] })
const kill = () => execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
const watchdog = setTimeout(() => { kill(); process.exit(124) }, 240000)
const browser = await chromium.connect(server.wsEndpoint())
const rows = [], errors = []
const save = () => writeFile(resolve(output, label + '-reading.json'), JSON.stringify({ label, metadata: { slug: card.slug, characters: article.content.length, resources: article.resources.length }, rows, errors }, null, 2))
function traceSummary(events, name) {
  const start = events.find(event => event.name === 'reading:start:' + name), end = events.find(event => event.name === 'reading:end:' + name)
  if (!start || !end) return null
  const slice = events.filter(e => e.pid === start.pid && e.tid === start.tid && e.ph === 'X' && e.ts >= start.ts && e.ts < end.ts)
  const sum = names => +(slice.filter(e => names.includes(e.name)).reduce((n, e) => n + (e.dur || 0), 0) / 1000).toFixed(2)
  const union = intervals => { let total = 0, end = -Infinity; for (const [a, b] of intervals.sort((a, b) => a[0] - b[0])) { total += Math.max(0, b - Math.max(a, end)); end = Math.max(end, b) } return total }
  const script = slice.filter(e => ['FunctionCall', 'EvaluateScript', 'RunMicrotasks', 'EventDispatch', 'TimerFire', 'FireAnimationFrame'].includes(e.name)).map(e => [e.ts, e.ts + e.dur])
  const rendering = slice.filter(e => ['Layout', 'UpdateLayoutTree', 'Paint'].includes(e.name)).map(e => [e.ts, e.ts + e.dur])
  const overlap = script.flatMap(([a, b]) => rendering.map(([c, d]) => [Math.max(a, c), Math.min(b, d)]).filter(([a, b]) => b > a))
  return { scriptingMs: +((union(script) - union(overlap)) / 1000).toFixed(2), styleMs: sum(['UpdateLayoutTree']), layoutMs: sum(['Layout']), paintMs: sum(['Paint']), layoutCalls: slice.filter(e => e.name === 'Layout').length, paintCalls: slice.filter(e => e.name === 'Paint').length }
}
try {
  for (const width of isolation ? [1440] : [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce', permissions: ['clipboard-read', 'clipboard-write'] })
    await context.addInitScript(() => {
      window.__reading = { operations: {}, vue: {}, vueTime: {}, observers: 0, callbacks: 0, disconnects: 0, rects: 0, tasks: [], workers: 0 }
      window.__blogReadingProbe = operation => { window.__reading.operations[operation] = (window.__reading.operations[operation] || 0) + 1 }
      const NativeWorker = Worker
      window.Worker = class extends NativeWorker {
        constructor(...args) { super(...args); window.__reading.workers++; this.addEventListener('message', ({ data }) => { for (const [name, count] of Object.entries(data.counts || {})) window.__reading.operations[name] = (window.__reading.operations[name] || 0) + count }) }
        postMessage(data) { super.postMessage({ ...data, probe: true }) }
      }
      const NativeObserver = IntersectionObserver
      window.IntersectionObserver = class extends NativeObserver {
        constructor(callback, options) { super((...args) => { window.__reading.callbacks++; callback(...args) }, options); window.__reading.observers++ }
        disconnect() { window.__reading.disconnects++; return super.disconnect() }
      }
      const rect = Element.prototype.getBoundingClientRect
      Element.prototype.getBoundingClientRect = function () { if (this.matches('.blog-renderer :is(h1,h2,h3,h4,h5,h6)')) window.__reading.rects++; return rect.call(this) }
      new PerformanceObserver(list => { window.__reading.tasks.push(...list.getEntries().map(e => ({ start: e.startTime, duration: e.duration }))) }).observe({ type: 'longtask', buffered: true })
    })
    const page = await context.newPage(); page.setDefaultTimeout(15000)
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    const cdp = await context.newCDPSession(page)
    await cdp.send('Performance.enable')
    const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(item => [item.name, item.value]))
    const collect = () => page.evaluate(() => JSON.parse(JSON.stringify(window.__reading)))
    await page.goto(base + '/blog'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
    await page.evaluate(() => {
      const app = document.querySelector('#__nuxt').__vue_app__
      const starts = new WeakMap()
      function count(component, event) { const name = component.$.type.name || component.$.type.__name || 'anonymous'; const key = name + ':' + event; window.__reading.vue[key] = (window.__reading.vue[key] || 0) + 1; return name }
      app.mixin({ beforeMount() { count(this, 'mount') }, beforeUpdate() { count(this, 'update'); starts.set(this.$, performance.now()) }, updated() { const name = this.$.type.name || this.$.type.__name || 'anonymous'; const start = starts.get(this.$); if (start !== undefined) window.__reading.vueTime[name] = (window.__reading.vueTime[name] || 0) + performance.now() - start }, unmounted() { count(this, 'unmount') } })
    })
    // 受控样例作为安全闸；每次只加载一个文档。
    await context.route('**/api/v1/blogs/by-slug/__reading-small', route => route.fulfill({ json: { code: 'OK', data: { ...article, id: 999, documentVersionId: 999, slug: '__reading-small', title: '受控性能样例', content: '# 小型样例\n\n## A\n\n' + '受控段落。\n\n'.repeat(20) + '### B\n\n$x^2$\n\n```java\nclass A {}\n```', resources: [] }, details: [] } }))
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog/__reading-small'))
    await page.locator('.blog-renderer h2').waitFor()
    await page.getByRole('button', { name: '收起章节：小型样例', exact: true }).click()
    await page.getByRole('button', { name: '展开章节：小型样例', exact: true }).click()
    await page.goBack(); await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor()
    if (isolation) await page.addStyleTag({ content: ({ 'mathml-hidden': '.blog-renderer .katex-mathml { display: none !important; }', 'mathml-contained': '.blog-renderer .katex-mathml { content-visibility: auto; contain-intrinsic-size: 1px 1px; }', 'text-wrap': '.blog-renderer, .blog-renderer * { text-wrap: wrap !important; }', 'inline-size': '.blog-renderer { contain: inline-size; }', 'local-layout': '.blog-chapter-body { contain: layout style; }' })[isolation] || '' })
    await cdp.send('HeapProfiler.collectGarbage')
    await cdp.send('Tracing.start', { categories: 'toplevel,devtools.timeline,v8,v8.execute,blink.user_timing', transferMode: 'ReturnAsStream' })
    const phases = []
    async function phase(name, action) {
      const before = await metrics(), counts = await collect()
      await page.evaluate(name => { window.__reading.tasks = []; performance.mark('reading:start:' + name) }, name)
      const started = performance.now()
      await action()
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
      const responseMs = Math.round(performance.now() - started)
      await page.waitForTimeout(350)
      await page.evaluate(name => performance.mark('reading:end:' + name), name)
      const after = await metrics(), next = await collect()
      assert.ok(after.JSHeapUsedSize < 384 * 1048576, '页面堆达到安全上限，停止后续测试')
      const diff = (a, b) => Object.fromEntries(Object.entries(b).map(([key, value]) => [key, +(value - (a[key] || 0)).toFixed(2)]).filter(([, value]) => value))
      const row = { width, name, responseMs, layoutMs: Math.round((after.LayoutDuration - before.LayoutDuration) * 1000), styleMs: Math.round((after.RecalcStyleDuration - before.RecalcStyleDuration) * 1000), scriptMs: Math.round((after.ScriptDuration - before.ScriptDuration) * 1000), heapMB: +(after.JSHeapUsedSize / 1048576).toFixed(2), nodes: after.Nodes, operations: diff(counts.operations, next.operations), vue: diff(counts.vue, next.vue), vueInclusiveMs: diff(counts.vueTime, next.vueTime), observers: next.observers - counts.observers, callbacks: next.callbacks - counts.callbacks, headingRects: next.rects - counts.rects, longTasks: next.tasks }
      phases.push(row); rows.push(row); await save(); console.log(JSON.stringify({ width, name, responseMs, layoutMs: row.layoutMs, operations: row.operations, chapterUpdates: row.vue['BlogChapter:update'], longTasks: row.longTasks }))
      if (!baseline) assert.ok(row.longTasks.every(task => task.duration < 250), name + '不存在严重主线程阻塞')
      if (!baseline && !['initial', 'return'].includes(name)) {
        assert.equal(row.operations.markdownParse || 0, 0, name + '不重新解析 Markdown')
        assert.equal(row.operations.chapterTree || 0, 0, name + '不重建章节树')
        assert.equal(row.operations.shikiTokenize || 0, 0, name + '不重新分词')
        assert.equal(row.operations.katexRender || 0, 0, name + '不重新解析公式')
        assert.equal(row.vue['BlogCodeBlock:mount'] || 0, 0, name + '无代码块重新挂载')
        assert.equal(row.vue['BlogMath:mount'] || 0, 0, name + '无公式重新挂载')
        assert.ok(row.longTasks.every(task => task.duration < 250), name + '不存在严重主线程阻塞')
        assert.ok(responseMs < (name === 'repeat-folds' ? 1200 : 600), name + '及时响应')
      }
    }
    await phase('initial', async () => { await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click(); await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor() })
    const facts = await page.evaluate(() => {
      const body = document.querySelector('.blog-renderer'), headings = [...body.querySelectorAll('h1,h2,h3,h4,h5,h6')], chapters = [...body.querySelectorAll('.blog-chapter')]
      window.__readingCodeNodes = [...body.querySelectorAll('.blog-code')]
      const largest = chapters.map(el => ({ title: el.querySelector('.blog-chapter-heading button').getAttribute('aria-label').replace(/^收起章节：/, ''), id: el.dataset.chapter, nodes: el.querySelector('.blog-chapter-body').querySelectorAll('*').length })).sort((a, b) => b.nodes - a.nodes)[0]
      const wrapping = [...body.querySelectorAll('p,li,td,th,h1,h2,h3,h4,h5,h6,blockquote')].filter(el => ['balance', 'pretty'].includes(getComputedStyle(el).textWrapStyle)).map(el => ({ tag: el.tagName, characters: el.textContent.length, style: getComputedStyle(el).textWrapStyle })).sort((a, b) => b.characters - a.characters).slice(0, 10)
      return { headings: headings.length, maximumDepth: Math.max(...headings.map(h => Number(h.tagName.slice(1)))), chapters: chapters.length, codes: body.querySelectorAll('.blog-code').length, largestCode: Math.max(...[...body.querySelectorAll('pre')].map(el => el.textContent.length)), images: body.querySelectorAll('img').length, tables: body.querySelectorAll('table').length, formulas: body.querySelectorAll('.blog-math').length, bodyNodes: body.querySelectorAll('*').length, wrapping, largest }
    })
    rows.at(-1).facts = facts
    const toggle = page.getByRole('button', { name: '收起章节：' + facts.largest.title, exact: true })
    await toggle.scrollIntoViewIfNeeded(); await page.waitForTimeout(200)
    await phase('collapse', () => toggle.click())
    await phase('expand', () => page.getByRole('button', { name: '展开章节：' + facts.largest.title, exact: true }).click())
    assert.ok(await page.evaluate(() => window.__readingCodeNodes.every(node => node.isConnected)), '折叠后代码 DOM 保持原实例')
    const headingIds = await page.locator('.blog-renderer :is(h1,h2,h3,h4,h5,h6)').evaluateAll(items => items.map(el => el.id))
    const go = id => page.evaluate(id => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push({ hash: '#' + id }), id)
    await phase('jump-last', () => go(headingIds.at(-1)))
    const position = await page.locator('[id="' + headingIds.at(-1) + '"]').boundingBox()
    const atDocumentEnd = await page.evaluate(() => Math.abs(document.documentElement.scrollHeight - innerHeight - scrollY) < 3)
    rows.at(-1).targetTop = position.y
    assert.ok(position.y >= 70 && (position.y < 130 || atDocumentEnd && position.y < 850), '标题定位正确；文末受文档最大滚动距离限制时保持可见')
    await phase('rapid-jumps', async () => { for (const id of [headingIds[1], headingIds.at(-2), headingIds[4], headingIds.at(-1)]) await go(id) })
    await page.getByRole('button', { name: '收起章节：' + facts.largest.title, exact: true }).scrollIntoViewIfNeeded()
    await phase('repeat-folds', async () => { for (let i = 0; i < 3; i++) { await page.getByRole('button', { name: '收起章节：' + facts.largest.title, exact: true }).click(); await page.getByRole('button', { name: '展开章节：' + facts.largest.title, exact: true }).click() } })
    const code = page.locator('.blog-code').first(); await code.scrollIntoViewIfNeeded()
    await phase('code-theme', async () => { await code.getByRole('combobox').click(); await code.getByRole('option', { name: 'Dracula', exact: true }).click() })
    await page.screenshot({ path: resolve(output, label + '-article-' + width + '.png') })
    await phase('leave', async () => { await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog')); await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor() })
    await phase('return', async () => { await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click(); await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor() })
    const completedTrace = new Promise(resolve => cdp.once('Tracing.tracingComplete', resolve))
    await cdp.send('Tracing.end')
    const { stream } = await completedTrace
    let raw = ''; while (true) { const data = await cdp.send('IO.read', { handle: stream }); raw += data.data; if (data.eof) break }
    await cdp.send('IO.close', { handle: stream })
    const trace = JSON.parse(raw)
    for (const row of phases) row.trace = traceSummary(trace.traceEvents, row.name)
    // 去掉任何 URL 查询参数，避免性能输出携带 OSS 签名。
    await writeFile(resolve(output, label + '-' + width + '.trace.json'), JSON.stringify(trace, (_key, value) => typeof value === 'string' && /^https?:/.test(value) ? value.split('?')[0] : value))
    await cdp.send('HeapProfiler.collectGarbage'); const gc = await metrics(); rows.at(-1).heapAfterGC = +(gc.JSHeapUsedSize / 1048576).toFixed(2)
    await context.close(); await save()
  }
  assert.deepEqual(errors, [])
} finally { await save(); clearTimeout(watchdog); await browser.close(); await server.close() }
