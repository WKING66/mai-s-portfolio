import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const output = resolve(process.env.BLOG_MATH_OUTPUT_DIR || '../docs/testing/screenshots/blog-reader-refinement-20261011/math')
await mkdir(output, { recursive: true })
const base = 'http://127.0.0.1:3000'
const html = await (await fetch(base + '/design-showcase')).text()
assert.ok(html.includes('katex-mathml') && /<math[ >]/.test(html), 'SSR 已包含公式和可访问 MathML')
const browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE })
const errors = [], rows = []
try {
  for (const width of [1440, 768, 390, 320]) for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width, height: 1000 }, ...(width < 1024 ? { isMobile: true, hasTouch: true } : {}) })
    const page = await context.newPage()
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    await page.goto(base + '/design-showcase#math-reading')
    const section = page.locator('#math-reading')
    await section.locator('.katex').first().waitFor()
    if (theme === 'dark') await page.getByRole('button', { name: '预览深色主题' }).click()
    await section.scrollIntoViewIfNeeded()
    await page.evaluate(() => document.fonts.ready)
    assert.ok(await section.locator('math').count() >= 10)
    assert.equal(await section.locator('a[href^="javascript:"],script,img,[onerror],[onclick]').count(), 0)
    assert.ok((await section.locator('.blog-math-fallback').innerText()).includes('notAValidCommand'))
    const long = section.locator('.blog-math-block').filter({ has: page.locator('.katex .stretchy') }).last()
    if (width <= 390) assert.ok(await long.evaluate(el => el.scrollWidth > el.clientWidth), '长公式在自身容器滚动')
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await section.screenshot({ path: resolve(output, `math-${theme}-${width}.png`), style: '.showcase-header { visibility: hidden !important; }' })
    rows.push({ width, theme, formulas: await section.locator('.blog-math').count(), mathml: await section.locator('math').count(), nodes: await section.locator('*').count() })
    await context.close()
  }
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'math-results.json'), JSON.stringify({ ssr: true, rows, errors }, null, 2))
  console.log(JSON.stringify({ ssr: true, rows, errors }))
} finally { await browser.close() }
