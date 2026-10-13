import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = 'http://127.0.0.1:3000', api = 'http://127.0.0.1:9333/api/v1/blogs'
const output = resolve(process.env.BLOG_READER_OUTPUT_DIR || '../docs/testing/screenshots/markdown-reader-stage2-20261011/business')
await mkdir(output, { recursive: true })
const envelope = data => ({ code: 'OK', data, message: '', details: [] })
const list = (await (await fetch(api)).json()).data
const card = list.items.find(item => item.title.includes('数据结构与算法'))
assert.ok(card, '真实长文章存在')
const article = (await (await fetch(api + '/by-slug/' + encodeURIComponent(card.slug))).json()).data
assert.equal(typeof article.content, 'string'); assert.ok(article.content.length > 10000)
assert.equal(Object.hasOwn(article, 'html'), false)
const metadata = { slug: card.slug, markdownCharacters: article.content.length, resources: article.resources.length }
const server = await chromium.launchServer({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
const watchdog = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 240000)
const browser = await chromium.connect(server.wsEndpoint()), errors = [], rows = []
async function navigate(page, path) { await page.evaluate(path => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push(path), path) }
try {
  for (const width of [1440, 390]) for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce', permissions: ['clipboard-read', 'clipboard-write'] })
    await context.addCookies([{ name: 'portfolio-theme', value: theme, url: base }])
    const page = await context.newPage(); page.setDefaultTimeout(30000)
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
    const response = await page.goto(base + '/blog/' + encodeURIComponent(card.slug))
    assert.equal(response.status(), 200)
    try { await page.locator('.blog-renderer h1, .blog-renderer h2').first().waitFor() }
    catch (error) { console.log(JSON.stringify({ width, theme, url: page.url(), errors, body: (await page.locator('main').innerText()).slice(0, 1500), diagnostics: await page.evaluate(() => {
      const app = document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt
      return { keys: Object.keys(app.payload.data), async: Object.fromEntries(Object.entries(app._asyncData).map(([key, value]) => [key, { error: String(value.error?.value || ''), nodes: value.data?.value?.nodes?.length, content: value.data?.value?.data?.content?.length }])), renderer: document.querySelector('.blog-renderer')?.outerHTML.slice(0, 500) }
    }) })); throw error }
    await page.locator('[data-account-entry]:not([disabled])').waitFor()
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: resolve(output, `article-${theme}-${width}.png`) })
    if (width === 1440 && theme === 'light') {
      const image = page.locator('.blog-renderer img').first()
      await image.scrollIntoViewIfNeeded()
      await page.waitForFunction(() => { const image = document.querySelector('.blog-renderer img'); return image?.complete && image.naturalWidth > 0 })
      await image.screenshot({ path: resolve(output, 'real-resource-image.png') })
    }
    const toc = page.getByRole('navigation', { name: '文章目录', exact: true })
    if (width < 1024) await page.getByRole('button', { name: '打开文章目录', exact: true }).click()
    const link = toc.getByRole('link').nth(1), text = await link.innerText()
    await link.click()
    await page.waitForFunction(() => Boolean(location.hash))
    const target = decodeURIComponent(new URL(page.url()).hash.slice(1))
    await page.waitForFunction(id => { const el = document.getElementById(id); const top = el?.getBoundingClientRect().top; return top >= 65 && top < 130 }, target)
    if (width < 1024) await page.getByRole('button', { name: '打开文章目录', exact: true }).click()
    assert.equal(await link.getAttribute('aria-current'), 'location')
    if (width < 1024) {
      await page.screenshot({ path: resolve(output, `article-directory-panel-${theme}-${width}.png`) })
      await page.mouse.click(width - 12, 200)
      await page.getByRole('dialog').waitFor({ state: 'hidden' })
    }
    rows.push({ width, theme, toc: text, nodes: await page.locator('.blog-renderer *').count(), codeBlocks: await page.locator('.blog-code').count() })
    await page.goto(base + '/blog'); await page.screenshot({ path: resolve(output, `list-${theme}-${width}.png`) })
    await context.close()
  }
  // 浏览器内的投影 fixture；不启动模拟服务、不写入维护者数据库。
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce', permissions: ['clipboard-read', 'clipboard-write'] })
  let reads = 0, expires = null
  const source = '## 安全阅读\n\n|左|右|\n|:---|---:|\n|~~旧~~|新|\n\n- [x] 完成\n- [ ] 待办\n\n![图](resource://image)\n\n[附件](resource://file)\n\n![禁止](asset://999)\n\n<script>window.__xss=1</script>\n\n[危险](javascript:alert(1))\n\n```java [Example.java]\n// resource://image stays in code\nclass Example {}\n```\n\n### 下一节\n\n正文'
  function projected() {
    reads++; expires ||= new Date(Date.now() + 38000).toISOString()
    return { id: 99, slug: '__reader-acceptance', title: '统一阅读器验收', summary: '', content: source, status: 'PUBLISHED', version: 2, documentId: 999, documentVersionId: 999,
      resources: [{ key: 'image', type: 'IMAGE', status: 'READY', assetId: 9, resourceUrl: '/images/design-showcase.svg?v=' + reads, expiresAt: reads === 1 ? expires : '2030-01-01T00:00:00Z', originalUri: null },
        { key: 'file', type: 'ATTACHMENT', status: 'READY', assetId: 10, resourceUrl: '/images/design-showcase.svg?attachment=1', expiresAt: null, originalUri: null }], allowDownload: false, publishedAt: '2026-10-11T00:00:00Z', updatedAt: '2026-10-11T00:00:00Z' }
  }
  await context.route('**/api/v1/blogs/by-slug/__reader-acceptance', route => route.fulfill({ json: envelope(projected()) }))
  const page = await context.newPage(); page.setDefaultTimeout(30000)
  page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (/hydration.*mismatch/i.test(m.text())) errors.push(m.text()) })
  await page.goto(base + '/blog'); await page.locator('[data-account-entry]:not([disabled])').waitFor()
  await navigate(page, '/blog/__reader-acceptance')
  const body = page.locator('.blog-renderer'); await body.getByRole('heading', { name: '安全阅读', exact: true }).waitFor()
  assert.equal(await body.locator('input[type=checkbox]').count(), 2)
  assert.equal(await body.locator('input[type=checkbox]').first().isChecked(), true)
  assert.equal(await body.locator('img').count(), 1); assert.equal(await body.locator('a[href*=attachment]').count(), 1)
  assert.equal(await body.locator('script,[onclick],[onerror],a[href^="javascript:"]').count(), 0)
  assert.equal(await page.evaluate(() => window.__xss), undefined)
  const block = body.locator('.blog-code').first()
  await block.getByRole('combobox').click(); await block.getByRole('option', { name: 'Dracula', exact: true }).click()
  await block.getByRole('button', { name: '复制 Example.java', exact: true }).click()
  await page.waitForFunction(async () => (await navigator.clipboard.readText()).replace(/\r\n/g, '\n') === '// resource://image stays in code\nclass Example {}')
  assert.equal((await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n'), '// resource://image stays in code\nclass Example {}')
  await block.getByRole('combobox').click(); await block.getByRole('option', { name: 'GitHub Light', exact: true }).waitFor()
  await page.screenshot({ path: resolve(output, 'reader-dracula-portal.png') }); await page.keyboard.press('Escape')
  await page.waitForFunction(() => document.querySelector('.blog-renderer img')?.getAttribute('src')?.includes('v=2'), undefined, { timeout: 20000 })
  assert.equal(reads, 2); assert.equal(await block.getAttribute('data-code-theme'), 'dracula')
  await block.getByRole('combobox').click(); await block.getByRole('option', { name: 'GitHub Light', exact: true }).click()
  await block.getByRole('combobox').click(); await block.getByRole('option', { name: 'Dracula', exact: true }).waitFor()
  await page.screenshot({ path: resolve(output, 'reader-github-light-portal.png') }); await page.keyboard.press('Escape')
  await context.close()
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ metadata, rows, resourceRefreshReads: reads, errors }))
  await writeFile(resolve(output, 'acceptance-results.json'), JSON.stringify({ metadata, rows, resourceRefreshReads: reads, errors }, null, 2))
} finally { clearTimeout(watchdog); await browser.close(); await server.close() }
