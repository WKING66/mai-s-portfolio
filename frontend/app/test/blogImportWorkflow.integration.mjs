import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const baseUrl = process.env.EXTERNAL_ACCEPTANCE_URL || 'http://127.0.0.1:3000'
const output = resolve(process.env.BLOG_IMPORT_OUTPUT_DIR || '../docs/testing/screenshots/blog-import-hotfix-20261011')
const fixtures = resolve('../.local-backups/blog-import-hotfix')
await mkdir(output, { recursive: true })
const directory = resolve(fixtures, '文档集'), nested = '长目录名称-'.repeat(6), filename = '王俊荣-面向对象分析与设计方法应用工程实践-'.repeat(2) + '.md'
await mkdir(resolve(directory, nested, 'images'), { recursive: true })
await writeFile(resolve(directory, nested, filename), '# 中文标题\n\n正文 ![图片](images/0.png)')
await writeFile(resolve(directory, '第二份.md'), '# 第二份')
for (let i = 0; i < 30; i++) await writeFile(resolve(directory, nested, 'images', i + '.png'), Buffer.from('fixture'))
const emptyDirectory = resolve(fixtures, '没有正文')
await mkdir(emptyDirectory, { recursive: true }); await writeFile(resolve(emptyDirectory, '附件.txt'), '附件')
const body = { name: 'sample.md', mimeType: 'text/markdown', buffer: Buffer.from('# English Import\n\n**正文保真**\n![a](a.png)') }
const longBody = { ...body, name: filename }
const owner = { loggedIn: true, username: 'fixture', roles: ['OWNER'], csrfToken: 'fixture-csrf' }
const envelope = data => ({ code: 'OK', data, message: '', details: [] })
const server = await chromium.launchServer({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
const watchdog = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 180000)
const browser = await chromium.connect(server.wsEndpoint()), errors = [], hydration = [], evidence = []
let imports = 0, folderImports = 0, draft, published = null, delayImport = null, failSave = false
const requests = []
async function fixture(route) {
  const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
  requests.push({ path, method })
  if (method !== 'GET') assert.equal(request.headers()['x-csrf-token'], owner.csrfToken)
  let data
  if (path === '/api/v1/auth/session') data = owner
  else if (path === '/api/v1/account/profile') data = { username: 'fixture', nickname: '测试账号', avatarUrl: null }
  else if (path === '/api/v1/blogs/imports') {
    imports++
    const payload = request.postDataBuffer().toString('utf8')
    assert.match(payload, /name="file"; filename=/)
    if (payload.includes('name="resources"; filename="images/0.png"')) {
      folderImports++
      const paths = [...payload.matchAll(/name="resources"; filename="([^"]+)"/g)].map(match => match[1])
      assert.equal(paths.length, 30)
      assert.ok(paths.every(path => /^images\/\d+\.png$/.test(path)), '目录附件按所选正文目录发送相对路径')
      assert.ok(!paths.includes('第二份.md'), '不包含正文目录外的文件')
    }
    if (delayImport) {
      const wait = delayImport; delayImport = null
      await wait.promise
      if (wait.fail) return route.fulfill({ status: 422, json: { code: 'INVALID_ARGUMENT', message: '旧导入任务失败', details: [] } })
    }
    draft = { id: 7, slug: null, title: 'English Import', summary: '', content: '# English Import\n\n**正文保真**\n![a](resource://image-key)', version: 1,
      status: 'DRAFT', documentId: 70, documentVersionId: 71, allowDownload: true, publishedAt: null, updatedAt: '2026-10-11T00:00:00', previewIssue: null,
      resources: [{ key: 'image-key', type: 'IMAGE', status: 'READY', assetId: 19, resourceUrl: '/images/design-showcase.svg', expiresAt: null, originalUri: 'a.png' }] }
    data = draft
  } else if (path === '/api/v1/blogs/7' && method === 'PATCH') {
    const input = request.postDataJSON()
    if (input.slug === 'taken-slug') return route.fulfill({ status: 409, json: { code: 'DATA_CONFLICT', message: '数据状态已变化，请刷新后重试', details: [] } })
    if (failSave) { failSave = false; return route.fulfill({ status: 500, json: { code: 'INTERNAL_ERROR', message: '保存暂时失败，请重试', details: [] } }) }
    assert.equal(input.version, draft.version)
    assert.equal(input.content, draft.content, '元数据保存保留 Markdown 原文及资源引用')
    assert.equal(Boolean(draft.publishedAt) && input.slug !== draft.slug, false, '首次发布后不更改 slug')
    draft = { ...draft, ...input, version: draft.version + 1 }; data = draft
  } else if (path === '/api/v1/blogs/7/publish' || path === '/api/v1/blogs/7/unpublish') {
    assert.equal(request.postDataJSON().version, draft.version)
    assert.ok(draft.slug && draft.title && draft.content)
    const publishing = path.endsWith('/publish')
    draft = { ...draft, version: draft.version + 1, status: publishing ? 'PUBLISHED' : 'DRAFT', publishedAt: draft.publishedAt || '2026-10-11T00:00:00' }
    published = publishing ? { ...draft } : null; data = draft
  } else if (path === '/api/v1/blogs/7') data = draft
  else if (path === '/api/v1/blogs') data = { view: 'MANAGE', page: 1, size: 20, total: draft ? 1 : 0, items: draft ? [draft] : [] }
  else if (path.startsWith('/api/v1/blogs/by-slug/')) {
    if (!published || decodeURIComponent(path.split('/').at(-1)) !== published.slug) return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: '未公开', details: [] } })
    data = published
  } else return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: '测试接口不存在', details: [] } })
  return route.fulfill({ json: envelope(data) })
}
async function pageIn(context) {
  await context.route('**/api/v1/**', fixture)
  const page = await context.newPage(); page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (/hydration/i.test(message.text())) hydration.push(message.text()) })
  return page
}
async function openList(page) {
  await page.goto(baseUrl + '/admin/blogs')
  await page.locator('.blog-import-panel').waitFor()
}
const panelOf = page => page.locator('.blog-import-panel')
const panelCapture = path => ({ path: resolve(output, path), style: '.site-header { visibility: hidden !important; }' })
const chooseFile = (panel, file = body) => panel.locator('input[accept=".md"]').setInputFiles(file)
const chooseFolder = panel => panel.locator('input[webkitdirectory]').setInputFiles(directory)
async function widthCheck(page, panel, width) {
  const stats = await panel.evaluate(el => ({ width: el.getBoundingClientRect().width, overflow: el.scrollWidth > el.clientWidth,
    outside: [...el.querySelectorAll('button,[role="radio"],input:not([hidden]),[role="status"]')].filter(x => { const r = x.getBoundingClientRect(), p = el.getBoundingClientRect(); return r.width && (r.left < p.left - 1 || r.right > p.right + 1) }).map(x => x.textContent) }))
  assert.ok(Math.abs(stats.width - width) < 1); assert.equal(stats.overflow, false, JSON.stringify(stats)); assert.deepEqual(stats.outside, [])
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
}
async function clearFeedback(page) {
  const toasts = page.locator('[data-slot="viewport"] li[data-state="open"]')
  while (await toasts.count()) await toasts.first().locator('button').click()
}
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' }), page = await pageIn(context)
  await openList(page)
  const panel = panelOf(page)
  for (const width of [240, 280, 320, 390]) {
    // 在真实管理侧栏内约束实际容器宽度，不用模拟页面替代组件。
    await panel.evaluate((el, width) => { el.style.width = width + 'px'; el.parentElement.style.gridTemplateColumns = 'minmax(0,1fr) ' + width + 'px' }, width)
    await panel.getByRole('radio', { name: 'Markdown 文件', exact: true }).click()
    await widthCheck(page, panel, width)
    await chooseFile(panel, longBody)
    assert.equal(await panel.locator('[data-slot="fileName"] span').getAttribute('title'), filename)
    await widthCheck(page, panel, width)
    await panel.screenshot(panelCapture(`markdown-${width}.png`))
    await panel.getByRole('radio', { name: '文档文件夹', exact: true }).click()
    assert.equal(await panel.locator('[data-slot="fileName"]').count(), 0)
    await chooseFolder(panel)
    const select = panel.getByRole('combobox')
    await select.click()
    await page.getByRole('option', { name: '文档集/' + nested + '/' + filename, exact: true }).click()
    await panel.getByText('已识别正文 · 30 个候选资源，保留目录结构', { exact: true }).waitFor()
    await widthCheck(page, panel, width)
    await panel.screenshot(panelCapture(`folder-${width}.png`))
    await select.click(); await page.screenshot({ path: resolve(output, `folder-menu-${width}.png`) }); await page.keyboard.press('Escape')
    await panel.getByRole('radio', { name: 'Markdown 文件', exact: true }).click()
    assert.equal(await panel.locator('input[accept=".md"]').evaluate(el => el.files.length), 0)
    assert.ok(await panel.getByRole('button', { name: '导入为草稿', exact: true }).isDisabled())
    evidence.push({ width, modes: 'passed' })
  }
  for (let i = 0; i < 4; i++) {
    await chooseFile(panel); await panel.getByRole('radio', { name: '文档文件夹', exact: true }).click()
    await panel.getByRole('radio', { name: 'Markdown 文件', exact: true }).click()
    assert.equal(await panel.locator('input[accept=".md"]').evaluate(el => el.files.length), 0)
  }
  await chooseFile(panel); await panel.getByRole('button', { name: '清空选择', exact: true }).click(); await chooseFile(panel)
  assert.ok(await panel.getByRole('button', { name: '导入为草稿', exact: true }).isEnabled())
  await panel.getByRole('button', { name: '清空选择', exact: true }).click()
  await chooseFile(panel, { ...body, buffer: Buffer.alloc(0) })
  await panel.getByRole('status').filter({ hasText: '正文文件为空' }).waitFor()
  assert.ok(await panel.getByRole('button', { name: '导入为草稿', exact: true }).isDisabled())
  await widthCheck(page, panel, 390); await panel.screenshot(panelCapture('empty-file-error.png'))
  await panel.getByRole('radio', { name: '文档文件夹', exact: true }).click()
  await panel.locator('input[webkitdirectory]').setInputFiles(emptyDirectory)
  await panel.getByRole('status').filter({ hasText: '没有 .md' }).waitFor()
  await widthCheck(page, panel, 390); await panel.screenshot(panelCapture('folder-no-markdown.png'))
  await panel.getByRole('radio', { name: 'Markdown 文件', exact: true }).click(); await chooseFile(panel)
  await panel.getByRole('button', { name: '添加本地图片或附件（可选）', exact: true }).click()
  await panel.locator('input[multiple]').setInputFiles([{ name: 'a.png', mimeType: 'image/png', buffer: Buffer.from('fixture') }])
  await panel.getByRole('checkbox', { name: '允许保存正文中的网络图片', exact: true }).check()
  await panel.getByRole('radio', { name: '文档文件夹', exact: true }).click()
  await panel.getByRole('radio', { name: 'Markdown 文件', exact: true }).click()
  assert.equal(await panel.getByRole('checkbox', { name: '允许保存正文中的网络图片', exact: true }).isChecked(), false)
  assert.equal(await panel.locator('[data-slot="fileName"]').count(), 0)
  await chooseFile(panel)
  let release; delayImport = { promise: new Promise(resolve => { release = resolve }), fail: true }
  const count = imports
  await panel.getByRole('button', { name: '导入为草稿', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.blog-import-panel').getAttribute('aria-busy') === 'true')
  assert.ok(await panel.getByRole('radio', { name: '文档文件夹', exact: true }).isDisabled())
  await panel.getByRole('button', { name: '正在导入文档…', exact: true }).evaluate(el => { el.click(); el.click() })
  assert.equal(imports, count + 1)
  // 离开组件后，旧请求失败不能把 Toast 和状态写回下一次导入。
  await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/admin/blogs/new'))
  await page.getByLabel('标题', { exact: true }).waitFor(); release()
  await page.waitForTimeout(300)
  assert.equal(await page.getByText('旧导入任务失败', { exact: true }).count(), 0)
  await openList(page); const fresh = panelOf(page)
  await fresh.getByRole('radio', { name: '文档文件夹', exact: true }).click(); await chooseFolder(fresh)
  await fresh.getByRole('combobox').click()
  await page.getByRole('option', { name: '文档集/' + nested + '/' + filename, exact: true }).click()
  await fresh.getByRole('button', { name: '导入为草稿', exact: true }).click()
  await page.waitForURL('**/admin/blogs/7'); await page.getByLabel('正文', { exact: true }).waitFor()
  assert.equal(folderImports, 1)
  await openList(page)
  await chooseFile(fresh)
  await page.screenshot({ path: resolve(output, 'import-desktop.png'), fullPage: true })
  await fresh.getByRole('button', { name: '导入为草稿', exact: true }).click()
  await page.waitForURL('**/admin/blogs/7'); await page.getByLabel('标题', { exact: true }).waitFor()
  await clearFeedback(page)
  if (!process.argv.includes('--selection-only')) {
    const slug = page.getByLabel('文章标识', { exact: true }), title = page.getByLabel('标题', { exact: true })
    const save = page.getByRole('button', { name: '保存草稿', exact: true }), publish = page.getByRole('button', { name: '发布当前草稿', exact: true })
    assert.ok(await slug.isVisible() && await slug.isEnabled(), '导入后立即可见且可操作')
    const titleBox = await title.boundingBox(), slugBox = await slug.boundingBox()
    assert.ok(slugBox.y > titleBox.y && slugBox.y - titleBox.y < 200, '文章标识紧邻标题')
    assert.equal(await slug.inputValue(), '')
    assert.ok(await publish.isDisabled())
    await page.screenshot({ path: resolve(output, 'imported-slug-empty.png') })
    await slug.fill('manual-first-slug'); await title.fill('独立的文章标题')
    assert.equal(await slug.inputValue(), 'manual-first-slug', '改标题不覆盖手动 Slug')
    await slug.fill('bad/slug')
    await page.getByText(/文章标识不能包含空格/).waitFor()
    assert.ok(await save.isDisabled()); assert.ok(await publish.isDisabled())
    await page.screenshot({ path: resolve(output, 'slug-invalid.png') })
    await slug.fill('taken-slug'); await save.click()
    await page.getByText(/保存冲突：文章标识可能已被使用/).first().waitFor()
    assert.equal(await slug.inputValue(), 'taken-slug'); assert.equal(draft.version, 1)
    assert.ok(await publish.isDisabled())
    await page.screenshot({ path: resolve(output, 'slug-conflict.png') })
    await clearFeedback(page)
    await slug.fill('manual-first-slug'); failSave = true; await save.click()
    await page.getByRole('alert').filter({ hasText: '保存暂时失败' }).waitFor()
    assert.equal(await title.inputValue(), '独立的文章标题'); assert.equal(await slug.inputValue(), 'manual-first-slug')
    await save.click(); await page.getByText('草稿已保存。', { exact: true }).waitFor()
    assert.equal(draft.slug, 'manual-first-slug')
    await page.reload(); await page.getByLabel('正文', { exact: true }).waitFor()
    assert.equal(await slug.inputValue(), 'manual-first-slug'); assert.equal(await title.inputValue(), '独立的文章标题')
    await clearFeedback(page)
    await page.screenshot({ path: resolve(output, 'slug-saved-reloaded.png') })
    // Slug 不按语言分类；未发布草稿可明确改名，沿用现有后端可保存的独立路径段。
    await slug.fill('算法学习笔记'); await save.click(); await page.getByText('草稿已保存。', { exact: true }).waitFor()
    await clearFeedback(page)
    await page.getByLabel('摘要', { exact: true }).fill('仅修改摘要'); await save.click()
    await page.getByText('草稿已保存。', { exact: true }).waitFor()
    assert.equal(draft.slug, '算法学习笔记')
    await clearFeedback(page); await publish.click()
    await page.getByRole('link', { name: '查看公开文章', exact: true }).waitFor()
    assert.ok(await slug.isDisabled(), '首次发布后 Slug 固定')
    await clearFeedback(page)
    await page.getByRole('link', { name: '查看公开文章', exact: true }).click()
    await page.getByRole('heading', { name: '独立的文章标题', exact: true }).waitFor()
    assert.equal(decodeURIComponent(new URL(page.url()).pathname), '/blog/算法学习笔记')
    await page.screenshot({ path: resolve(output, 'published-slug-reader.png') })
    await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog/manual-first-slug'))
    await page.getByRole('heading', { name: '这条路径，暂时没有内容', exact: true }).waitFor()
    await page.goto(baseUrl + '/admin/blogs/7'); await slug.waitFor()
    assert.equal(await slug.inputValue(), '算法学习笔记'); assert.ok(await slug.isDisabled())
    await page.getByRole('button', { name: '下架文章', exact: true }).click()
    await page.getByText('文章已下架。', { exact: true }).waitFor()
    assert.ok(await slug.isDisabled(), '下架不解锁已发布标识')
    evidence.push({ slug: 'passed', publishedAddress: '/blog/算法学习笔记', conflictAndFailurePreserveInput: true, version: draft.version })
  }
  await context.close()
  const mobileContext = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' }), mobile = await pageIn(mobileContext)
  await openList(mobile)
  await panelOf(mobile).getByRole('radio', { name: '文档文件夹', exact: true }).click(); await chooseFolder(panelOf(mobile))
  assert.ok(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  await panelOf(mobile).screenshot(panelCapture('folder-mobile.png'))
  if (!process.argv.includes('--selection-only')) {
    await mobile.goto(baseUrl + '/admin/blogs/7')
    await mobile.getByLabel('文章标识', { exact: true }).waitFor()
    assert.equal(await mobile.getByLabel('文章标识', { exact: true }).inputValue(), '算法学习笔记')
    assert.ok(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await mobile.screenshot({ path: resolve(output, 'slug-mobile.png'), fullPage: true })
  }
  await mobileContext.close()
  assert.deepEqual(errors, []); assert.deepEqual(hydration, [])
  await writeFile(resolve(output, 'browser-results.json'), JSON.stringify({ evidence, imports, folderImports, errors, hydration }, null, 2))
  console.log('PASS: 240/280/320/390 实际容器、模式清理、重选、错误、附件、异步旧结果、导入互斥和移动布局' + (process.argv.includes('--selection-only') ? '' : '；Slug 编辑、失败保留、重载、改名、发布访问及首次发布后锁定'))
} finally { clearTimeout(watchdog); await browser.close(); await server.close() }
