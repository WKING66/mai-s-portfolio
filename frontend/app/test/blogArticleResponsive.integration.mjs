import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000'
const card = (await (await fetch(base + '/api/v1/blogs')).json()).data.items.find(item => item.title.includes('数据结构与算法'))
const output = resolve('../docs/testing/screenshots/blog-reader-fix-20261011/mobile')
await mkdir(output, { recursive: true })
const server = await chromium.launchServer({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--renderer-process-limit=1', '--js-flags=--max-old-space-size=512'] })
const timer = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true }); process.exit(124) }, 180000)
const browser = await chromium.connect(server.wsEndpoint()), rows = [], errors = []
try {
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width: 390, height: 900 }, hasTouch: true, isMobile: true, reducedMotion: 'reduce' })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: base }])
    const page = await context.newPage(); page.setDefaultTimeout(20000)
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    await page.goto(base + '/blog'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
    await page.getByRole('link').filter({ hasText: '数据结构与算法' }).click()
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
    const body = page.locator('.blog-renderer'), trigger = page.getByRole('button', { name: '打开文章目录', exact: true })
    for (const width of [320, 360, 375, 390, 412, 430, 768, 1024, 1280, 1440]) {
      await page.setViewportSize({ width, height: 900 }); await page.evaluate(() => scrollTo({ top: 0, behavior: 'instant' })); await page.waitForTimeout(80)
      const geometry = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
      assert.ok(geometry.scroll <= geometry.client + 1, '真实长文章无页面横向溢出')
      const box = await body.boundingBox()
      assert.ok(box.x >= 15 && box.x + box.width <= width - 15, '正文保留安全边距')
      if (width < 1200) {
        if (width === 390) await page.screenshot({ path: resolve(output, 'reading-' + theme + '.png') })
        await page.evaluate(() => scrollTo({ top: document.documentElement.scrollHeight * .5, behavior: 'instant' })); await page.waitForTimeout(80)
        const button = await trigger.boundingBox()
        assert.ok(button && button.y >= 73 && button.y < 180, '深处阅读仍能打开目录，无需回到文章顶部')
        // 本测试使用真实 OSS 图片。先等阅读位置附近的图片结束加载，避免把
        // 图片固有尺寸变化触发的浏览器滚动锚定误判为弹层关闭后的页面位移。
        await page.waitForFunction(() => [...document.querySelectorAll('.blog-renderer img')].filter(img => { const box = img.getBoundingClientRect(); return box.bottom > 0 && box.top < innerHeight + 1500 }).every(img => img.complete))
        const before = { y: await page.evaluate(() => scrollY), body: await body.boundingBox(), height: await page.evaluate(() => document.documentElement.scrollHeight) }
        await trigger.click(); const dialog = page.getByRole('dialog'); await dialog.waitFor()
        const panel = await dialog.boundingBox()
        assert.ok(panel.width <= 320.5 && width - panel.width >= 47.5 && panel.y >= 73 && panel.y + panel.height <= 884.5)
        assert.ok(await dialog.locator('.blog-toc-scroll').evaluate(el => el.scrollHeight > el.clientHeight), '长目录内部独立滚动')
        assert.ok(await dialog.evaluate(el => el.contains(document.activeElement)))
        await page.keyboard.press('Tab'); assert.ok(await dialog.evaluate(el => el.contains(document.activeElement)))
        if (width === 390) await page.screenshot({ path: resolve(output, 'drawer-' + theme + '.png') })
        await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' })
        console.log(JSON.stringify({ width, theme, before: { y: before.y, height: before.height }, after: await page.evaluate(() => ({ y: scrollY, height: document.documentElement.scrollHeight })) }))
        assert.ok(Math.abs(await page.evaluate(() => scrollY) - before.y) < 1)
        assert.ok(Math.abs((await body.boundingBox()).x - before.body.x) < 1)
        assert.ok(await trigger.evaluate(el => el === document.activeElement), '关闭后焦点恢复')
        rows.push({ theme, width, geometry, body: box, panel, scrollStable: true })
      } else rows.push({ theme, width, geometry, body: box })
    }
    await context.close()
  }
  assert.deepEqual(errors, [])
} finally {
  await writeFile(resolve(output, 'mobile-results.json'), JSON.stringify({ rows, errors }, null, 2))
  clearTimeout(timer); await browser.close(); await server.close()
}
