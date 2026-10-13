import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000', output = resolve(process.env.BLOG_MERMAID_OUTPUT_DIR || '../docs/testing/screenshots/blog-experience-20261012/mermaid')
await mkdir(output, { recursive: true })
const html = await (await fetch(base + '/design-showcase')).text()
assert.ok(html.includes('blog-mermaid-placeholder'), 'SSR 输出一致的懒渲染占位')
const server = await chromium.launchServer({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE, args: ['--renderer-process-limit=1', '--js-flags=--max-old-space-size=512'] })
const timer = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 180000)
const browser = await chromium.connect(server.wsEndpoint()), rows = [], errors = []
try {
  for (const width of [1280, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, ...(width < 800 ? { hasTouch: true, isMobile: true } : {}), permissions: ['clipboard-read', 'clipboard-write'] })
    await context.addInitScript(() => { window.__diagrams = {}; window.__blogReadingProbe = op => window.__diagrams[op] = (window.__diagrams[op] || 0) + 1; window.__mermaidAttack = 0 })
    const page = await context.newPage(); page.setDefaultTimeout(20000)
    page.on('pageerror', error => errors.push(error.message)); page.on('console', message => { if (/hydration.*mismatch/i.test(message.text())) errors.push(message.text()) })
    await page.goto(base + '/design-showcase'); await page.locator('[data-showcase-theme]').waitFor()
    const section = page.locator('#mermaid-reading'), diagrams = section.locator('.blog-mermaid')
    for (const theme of ['light', 'dark', 'light']) {
      if (await page.locator('.design-showcase').getAttribute('data-showcase-theme') !== theme) await page.getByRole('button', { name: theme === 'dark' ? '预览深色主题' : '预览浅色主题', exact: true }).click()
      for (let index = 0; index < 5; index++) {
        const diagram = diagrams.nth(index); await diagram.scrollIntoViewIfNeeded(); await diagram.locator('svg').waitFor()
        await page.waitForFunction(index => { const diagram = document.querySelectorAll('#mermaid-reading .blog-mermaid')[index]; return diagram?.getAttribute('aria-busy') === 'false' }, index)
        assert.equal(await diagram.locator('foreignObject,a,image,script,[onclick],[onerror],[href],[xlink\\:href]').count(), 0)
        await diagram.screenshot({ path: resolve(output, `${theme}-${width}-${index}.png`), style: '.site-header { visibility: hidden !important; }' })
      }
      rows.push({ width, height: 900, dpr: 1, browser: browser.version(), orientation: width > 900 ? 'Landscape' : 'Portrait', theme, path: '/design-showcase#mermaid-reading', counts: await page.evaluate(() => ({ ...window.__diagrams })) })
      assert.ok(await section.evaluate(section => { const ids = [...section.querySelectorAll('.blog-mermaid [id]')].map(el => el.id); return new Set(ids).size === ids.length }), '多个图表内部 ID 不冲突')
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    }
    const valid = diagrams.first(); await valid.getByRole('button', { name: '查看源码', exact: true }).click()
    await valid.getByRole('button', { name: '复制 Mermaid 源码', exact: true }).click()
    assert.ok((await page.evaluate(() => navigator.clipboard.readText())).includes('A[上传文档]'))
    const invalid = diagrams.nth(5); await invalid.scrollIntoViewIfNeeded(); await invalid.locator('pre').waitFor()
    assert.ok((await invalid.locator('pre').innerText()).includes('A --> ((('))
    assert.equal(await invalid.locator('svg').count(), 0)
    await invalid.screenshot({ path: resolve(output, `error-${width}.png`) })
    assert.ok(await section.locator('.blog-code pre').innerText().then(text => text.includes('const source')))
    const before = await page.evaluate(() => window.__diagrams.mermaidRender)
    await valid.scrollIntoViewIfNeeded(); await page.mouse.wheel(0, 200); await page.mouse.wheel(0, -200); await page.waitForTimeout(300)
    assert.equal(await page.evaluate(() => window.__diagrams.mermaidRender), before, '滚动不重新解析图表')
    const finalLight = rows.filter(row => row.width === width && row.theme === 'light').at(-1), dark = rows.find(row => row.width === width && row.theme === 'dark')
    assert.equal(finalLight.counts.mermaidRender, dark.counts.mermaidRender, '重复主题使用安全 SVG 缓存')
    const content = '# 安全与超宽图表\n\n```mermaid\nflowchart LR\n' + Array.from({ length: 10 }, (_, i) => `A${i}[很长的技术流程步骤 ${i}]-->A${i+1}`).join('\n') + '\n```\n\n```mermaid\n%%{init: {"securityLevel":"loose"}}%%\nflowchart TD; A-->B\n```\n\n```mermaid\nflowchart TD\nA["<img src=x onerror=window.__mermaidAttack=1>"]-->B[安全]\nclick B "javascript:alert(1)"\n```'
    await context.route('**/api/v1/blogs/by-slug/__mermaid-security', route => route.fulfill({ json: { code: 'OK', data: { id: 998, slug: '__mermaid-security', title: 'Mermaid 安全样例', content, resources: [], status: 'PUBLISHED', version: 1, documentVersionId: 998, allowDownload: false, publishedAt: '2026-10-12', updatedAt: '2026-10-12' } } }))
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog/__mermaid-security'))
    const articleDiagrams = page.locator('.blog-reading-article .blog-mermaid')
    await articleDiagrams.first().locator('svg').waitFor()
    assert.ok(await articleDiagrams.first().locator('.blog-mermaid-viewport').evaluate(el => {
      const viewport = el.getBoundingClientRect(), svg = el.querySelector('svg').getBoundingClientRect()
      return svg.width <= viewport.width + 1 && svg.height <= viewport.height + 1
    }), '超宽图表默认适配容器，细节通过缩放查看')
    await articleDiagrams.nth(1).scrollIntoViewIfNeeded(); await articleDiagrams.nth(1).locator('pre').waitFor()
    assert.ok((await articleDiagrams.nth(1).innerText()).includes('配置指令'))
    await articleDiagrams.nth(2).scrollIntoViewIfNeeded(); await articleDiagrams.nth(2).locator('svg').waitFor()
    assert.equal(await articleDiagrams.nth(2).locator('img,image,script,a,[onerror],[onclick],[href],[xlink\\:href]').count(), 0)
    assert.equal(await page.evaluate(() => window.__mermaidAttack), 0)
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    await page.screenshot({ path: resolve(output, `safe-wide-${width}.png`) })
    await context.close()
  }
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ ssr: '一致的客户端占位', deviceType: 'Edge 设备模拟；非真实手机', rows, errors }, null, 2))
  console.log(JSON.stringify({ passed: true, rows, errors }))
} catch (error) {
  const page = browser.contexts()[0]?.pages()[0]
  const diagrams = page ? await page.locator('.blog-mermaid').allTextContents() : []
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ message: String(error), diagrams, rows, errors }, null, 2))
  if (page) await page.screenshot({ path: resolve(output, 'failure.png') })
  throw error
} finally { clearTimeout(timer); await browser.close(); await server.close() }
