import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000'
const card = (await (await fetch(base + '/api/v1/blogs')).json()).data.items.find(item => item.title.includes('数据结构与算法'))
const output = resolve('../docs/testing/screenshots/blog-reader-fix-20261011/layout')
await mkdir(output, { recursive: true })
const server = await chromium.launchServer({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--renderer-process-limit=1'] })
const timer = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true }); process.exit(124) }, 180000)
const browser = await chromium.connect(server.wsEndpoint()), rows = [], errors = []
try {
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: base }])
    const page = await context.newPage(); page.setDefaultTimeout(20000)
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    await page.goto(base + '/blog'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
    await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click()
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
    for (const width of [1199, 1200, 1280, 1440]) {
      await page.setViewportSize({ width, height: 900 }); await page.waitForTimeout(120)
      const boxes = { body: await page.locator('.blog-renderer').boundingBox(), title: await page.locator('.blog-reading-title').boundingBox(), toc: await page.locator('.blog-reading-toc').boundingBox() }
      assert.ok(boxes.body.width <= 760.1 && boxes.body.width >= 759, '正文保持舒适宽度')
      if (width >= 1200) {
        assert.ok(boxes.toc.width >= 260 && boxes.toc.width <= 300)
        assert.ok(Math.abs(boxes.toc.y - boxes.title.y) < 3, '目录与文章标题共同起始，消除头部空白')
        assert.ok(boxes.toc.x < width / 2 - 350 && boxes.toc.x + boxes.toc.width < boxes.body.x)
        assert.ok(Math.abs(boxes.title.x - boxes.body.x) < 1)
        assert.ok(boxes.toc.y + boxes.toc.height <= 900, '目录首屏底部留在视口内')
      } else assert.ok(await page.getByRole('button', { name: '打开文章目录' }).isVisible(), '中等桌面视口降级为目录面板')
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1))
      rows.push({ width, theme, boxes })
      if (width === 1440) await page.screenshot({ path: resolve(output, 'pc-' + theme + '.png') })
    }
    const scroll = page.locator('.blog-toc-desktop .blog-toc-scroll')
    const y = await page.evaluate(() => scrollY)
    await scroll.evaluate(el => { el.scrollTop = el.scrollHeight })
    assert.equal(await page.evaluate(() => scrollY), y, '目录内部滚动独立')
    const ids = await page.locator('.blog-renderer :is(h1,h2,h3,h4,h5,h6)').evaluateAll(items => items.map(el => el.id))
    const id = ids[Math.floor(ids.length * .7)]
    await page.evaluate(id => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push({ hash: '#' + id }), id)
    await page.waitForTimeout(120)
    assert.ok(Math.abs((await page.locator('[id="' + id + '"]').boundingBox()).y - 97) < 3, '锚点保持 Header 安全距离')
    await page.getByRole('button', { name: '收起章节：数据结构与算法', exact: true }).click()
    await page.evaluate(() => scrollTo({ top: 0, behavior: 'instant' }))
    await page.screenshot({ path: resolve(output, 'collapsed-' + theme + '.png') })
    await context.close()
  }
  assert.deepEqual(errors, [])
} finally {
  await writeFile(resolve(output, 'layout-results.json'), JSON.stringify({ rows, errors }, null, 2))
  clearTimeout(timer); await browser.close(); await server.close()
}
