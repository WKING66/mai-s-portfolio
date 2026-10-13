import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium, devices } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000', output = resolve(process.env.DEVICE_MATRIX_OUTPUT || '../docs/testing/screenshots/blog-experience-20261012/devices')
await mkdir(output, { recursive: true })
const names = ['iPhone SE', 'iPhone XR', 'iPhone 12 Pro', 'iPhone 14', 'iPhone 14 Plus', 'iPhone 14 Pro Max', 'iPhone 15', 'iPhone 15 Pro Max', 'iPhone 16', 'iPhone 16 Pro', 'iPhone 16 Pro Max', 'Moto G4', 'Pixel 7', 'Pixel 8', 'Galaxy Tab S4', 'iPad (gen 7)']
const requested = names.flatMap(name => [name, name + ' landscape'])
const missing = requested.filter(name => !devices[name])
const selected = requested.filter(name => devices[name]).map(name => ({ name, ...devices[name] }))
selected.push({ name: 'Generic Android wide viewport (not Mate60Pro hardware)', viewport: { width: 420, height: 900 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true }, { name: 'Generic Android wide landscape (not Mate60Pro hardware)', viewport: { width: 900, height: 420 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true })
const groups = process.env.DEVICE_MATRIX_GROUP?.split(',')
const configurations = groups ? selected.filter(item => groups.some(group => item.name.startsWith(group))) : selected
const articles = (await (await fetch(base + '/api/v1/blogs')).json()).data.items
const article = articles.find(item => item.title.includes('数据结构与算法'))
assert.ok(article)
const server = await chromium.launchServer({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--renderer-process-limit=1', '--js-flags=--max-old-space-size=512'] })
const timer = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 720000)
const browser = await chromium.connect(server.wsEndpoint()), rows = [], errors = [], failures = []
let completed = false
const save = () => writeFile(resolve(output, 'results' + (process.env.DEVICE_MATRIX_GROUP ? '-' + process.env.DEVICE_MATRIX_GROUP.replace(/\W/g, '-') : '') + '.json'), JSON.stringify({ kind: 'Playwright device emulation in actual Edge Chromium, NOT physical hardware; preset UA may describe Safari', browser: browser.version(), completed, configurations: configurations.map(item => item.name), missing, unavailablePresets: ['Moto G Power', 'iPad 9', 'Huawei Mate60Pro'], ipadNote: 'gen7 810×1080@2 is a similar CSS viewport; NOT an iPad9 physical test', rows, failures, errors }, null, 2))
try {
  for (const configuration of configurations) for (const theme of ['light', 'dark']) {
    const { name, defaultBrowserType, ...options } = configuration
    const context = await browser.newContext({ ...options, reducedMotion: 'reduce', colorScheme: theme })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: base }])
    if (process.env.DEVICE_DIAGNOSTIC) await context.addInitScript(() => {
      window.__readingSizes = []
      document.addEventListener('load', event => { if (event.target instanceof HTMLImageElement) window.__readingSizes.push({ kind: 'image', time: performance.now(), width: event.target.naturalWidth, height: event.target.naturalHeight }) }, true)
      const NativeObserver = ResizeObserver
      window.ResizeObserver = class extends NativeObserver { constructor(callback) { super((entries, observer) => { for (const entry of entries) if (entry.target.classList.contains('blog-renderer')) window.__readingSizes.push({ kind: 'body', time: performance.now(), width: entry.contentRect.width, height: entry.contentRect.height }); callback(entries, observer) }) } }
    })
    const page = await context.newPage(); page.setDefaultTimeout(20000)
    page.on('pageerror', e => errors.push({ name, theme, error: e.message })); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push({ name, theme, error: m.text() }) })
    const stem = name.replace(/[^\w-]/g, '-').toLowerCase() + '-' + theme
    async function screenshot(kind, requestedViewport = options.viewport) {
      const path = new URL(page.url()).pathname, file = stem + '-' + kind + '.png'
      const actual = await page.evaluate(() => ({ viewport: { width: innerWidth, height: innerHeight }, cssClientWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth, dpr: devicePixelRatio, theme: document.documentElement.dataset.theme, localTheme: document.querySelector('.design-showcase')?.getAttribute('data-showcase-theme') ?? null, touch: navigator.maxTouchPoints, userAgent: navigator.userAgent }))
      const row = { device: name, simulated: true, browser: 'Microsoft Edge ' + browser.version(), orientation: requestedViewport.width > requestedViewport.height ? 'Landscape' : 'Portrait', requestedViewport, requestedDpr: options.deviceScaleFactor, path, state: kind, screenshot: file, ...actual }; rows.push(row)
      if (actual.scrollWidth > actual.cssClientWidth + 1 || actual.theme !== theme) failures.push(row)
      await page.screenshot({ path: resolve(output, file) })
    }
    await page.goto(base + '/'); await page.locator('[data-account-entry]:not([disabled])').waitFor(); await screenshot('home')
    if (options.viewport.width < 768) {
      await page.getByRole('button', { name: '打开页面导航', exact: true }).click(); await page.getByRole('menu').waitFor()
      const bounds = await page.getByRole('menu').boundingBox(); assert.ok(bounds.x >= -1 && bounds.x + bounds.width <= options.viewport.width + 1)
      await page.keyboard.press('Escape'); await page.getByRole('menu').waitFor({ state: 'hidden' })
    }
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog'))
    await page.getByRole('heading', { name: '博客笔记', exact: true }).waitFor(); await screenshot('list')
    await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click(); await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor(); await screenshot('article')
    if (options.viewport.width < 1200) {
      const before = await page.evaluate(() => scrollY)
      await page.getByRole('button', { name: '打开文章目录', exact: true }).click(); await page.getByRole('dialog').waitFor()
      await page.waitForFunction(() => { const dialog = document.querySelector('[role="dialog"]'); return dialog && Math.abs(dialog.getBoundingClientRect().left) < 1 })
      const panel = await page.getByRole('dialog').boundingBox(); assert.ok(panel.x >= -1 && panel.x + panel.width <= options.viewport.width + 1 && panel.y + panel.height <= options.viewport.height + 1)
      await screenshot('directory')
      await page.getByRole('dialog').getByRole('link').last().click(); await page.getByRole('dialog').waitFor({ state: 'hidden' })
      assert.ok(await page.evaluate(() => scrollY) > before)
    }
    if (['iPhone 16', 'Pixel 8', 'iPad (gen 7)'].includes(name)) {
      for (const viewport of [{ width: options.viewport.height, height: options.viewport.width }, options.viewport]) {
        await page.setViewportSize(viewport); await page.waitForTimeout(150)
        await page.getByRole('button', { name: '打开文章目录', exact: true }).click(); await page.getByRole('dialog').waitFor()
        await page.getByRole('dialog').getByRole('link').last().click(); await page.getByRole('dialog').waitFor({ state: 'hidden' })
        await page.waitForFunction(() => document.activeElement === [...document.querySelectorAll('.blog-chapter-title :is(h1,h2,h3,h4,h5,h6)')].at(-1))
        await page.waitForTimeout(150)
        const alignment = await page.locator('.blog-chapter-title :is(h1,h2,h3,h4,h5,h6)').last().evaluate(el => {
          const scrollRoot = document.scrollingElement, top = el.getBoundingClientRect().top, margin = parseFloat(getComputedStyle(el).scrollMarginTop)
          return { top, margin, scroll: scrollY, maxScroll: scrollRoot.scrollHeight - scrollRoot.clientHeight, expectedScroll: Math.max(0, Math.min(scrollY + top - margin, scrollRoot.scrollHeight - scrollRoot.clientHeight)), height: scrollRoot.scrollHeight, clientHeight: scrollRoot.clientHeight, viewport: innerHeight, id: el.id }
        })
        if (Math.abs(alignment.scroll - alignment.expectedScroll) >= 3) await writeFile(resolve(output, stem + '-rotation-diagnostic.json'), JSON.stringify({ name, viewport, theme, alignment, sizes: await page.evaluate(() => window.__readingSizes || []), intrinsic: await page.locator('[data-reading-contained="true"]').first().evaluate(el => ({ computed: getComputedStyle(el).containIntrinsicBlockSize, inline: el.getAttribute('style'), rules: [...document.styleSheets].flatMap(sheet => { const result = []; function visit(rules) { for (const rule of rules) { const value = rule.style?.getPropertyValue('contain-intrinsic-block-size'); if (value) result.push({ source: sheet.href, selector: rule.selectorText, value }); if (rule.cssRules) visit(rule.cssRules) } } try { visit(sheet.cssRules) } catch {} return result }) })) }, null, 2))
        assert.ok(Math.abs(alignment.scroll - alignment.expectedScroll) < 3, '旋转后准确定位；末节允许到达浏览器滚动下限：' + JSON.stringify(alignment))
        await screenshot(viewport.width === options.viewport.width ? 'rotate-back' : 'rotate', viewport)
      }
    }
    await page.locator('.blog-code').first().scrollIntoViewIfNeeded()
    const block = page.locator('.blog-code').first(); await block.getByRole('combobox').click(); await block.getByRole('option', { name: 'Dracula', exact: true }).click(); assert.equal(await block.getAttribute('data-code-theme'), 'dracula')
    await screenshot('code')
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/design-showcase#mermaid-reading'))
    await page.locator('.design-showcase').waitFor()
    if (theme === 'dark') await page.getByRole('button', { name: '预览深色主题', exact: true }).click()
    const diagram = page.locator('#mermaid-reading .blog-mermaid').first()
    await diagram.scrollIntoViewIfNeeded(); await diagram.locator('svg').waitFor()
    await page.waitForFunction(() => document.querySelector('#mermaid-reading .blog-mermaid')?.getAttribute('aria-busy') === 'false')
    assert.equal(await page.locator('.design-showcase').getAttribute('data-showcase-theme'), theme)
    await screenshot('mermaid')
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/login'))
    await page.getByRole('heading', { level: 1 }).waitFor(); await screenshot('login')
    await context.close(); await save(); console.log(JSON.stringify({ device: name, theme, cases: rows.length, failures: failures.length, errors: errors.length }))
  }
  assert.deepEqual(failures, [], '全部设备无整页横向溢出且正确恢复主题'); assert.deepEqual(errors, []); completed = true
} finally { await save(); clearTimeout(timer); await browser.close(); await server.close() }
