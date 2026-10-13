import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const base = process.env.EXTERNAL_ACCEPTANCE_URL || 'http://127.0.0.1:3000'
const output = resolve(process.env.BLOG_RENDERER_OUTPUT_DIR || '../docs/testing/screenshots/markdown-reader-stage2-20261011')
await mkdir(output, { recursive: true })
const server = await chromium.launchServer({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
const watchdog = setTimeout(() => { execFileSync('taskkill', ['/PID', String(server.process().pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); process.exit(124) }, 180000)
const browser = await chromium.connect(server.wsEndpoint())
const errors = [], hydration = []
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce', permissions: ['clipboard-read', 'clipboard-write'] })
  const page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (/hydration/i.test(message.text())) hydration.push(message.text()) })
  await page.addInitScript(() => {
    window.__blogXss = 0; window.__workerRequests = []; window.__workerResults = []
    const NativeWorker = window.Worker
    window.Worker = class extends NativeWorker {
      constructor(...args) { super(...args); this.addEventListener('message', ({ data }) => window.__workerResults.push({ id: data.id, error: data.error, nodes: data.document?.nodes.slice(0, 2) })); this.addEventListener('error', event => window.__workerResults.push({ error: event.message })) }
      postMessage(data) { window.__workerRequests.push({ id: data.id, markdown: data.markdown }); super.postMessage(data) }
    }
  })
  const workers = []; page.on('worker', worker => workers.push(worker.url()))
  await page.goto(base + '/design-showcase')
  await page.getByRole('textbox', { name: '富文本样板正文', exact: true }).waitFor()
  await page.getByRole('tab', { name: 'Markdown 源码', exact: true }).click()
  const markdown = '## Worker 验证\n\n| 左 | 右 |\n|:---|---:|\n| ~~旧~~ | 新 |\n\n- [x] 完成\n- [ ] 待办\n\n```typescript [Worker.ts]\nconst value = "<safe>"\n```\n\n<img src=x onerror="window.__blogXss=1">\n\n[危险](javascript:alert(1))'
  await page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true }).fill(markdown)
  const preview = page.locator('.showcase-preview-scroll .blog-renderer')
  try { await preview.getByRole('heading', { name: 'Worker 验证', exact: true }).waitFor({ timeout: 10000 }) }
  catch (error) {
    console.log(JSON.stringify({ worker: workers, observed: await page.evaluate(() => ({ requests: window.__workerRequests, results: window.__workerResults })), preview: (await preview.innerHTML()).slice(0, 500), source: await page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true }).inputValue(), errors, hydration }))
    throw error
  }
  assert.equal(workers.length, 1, '浏览器真正创建并复用 Markdown Worker')
  assert.ok(await preview.locator('th').last().evaluate(el => ['right', 'end'].includes(getComputedStyle(el).textAlign)))
  const checkboxes = preview.locator('input[type="checkbox"]')
  assert.equal(await checkboxes.count(), 2)
  assert.ok(await checkboxes.first().isChecked() && await checkboxes.first().isDisabled())
  assert.equal(await checkboxes.last().isChecked(), false)
  assert.equal(await preview.locator('script,img,[onclick],[onerror],a[href^="javascript:"]').count(), 0)
  assert.equal(await page.evaluate(() => window.__blogXss), 0)
  const block = preview.locator('.blog-code')
  await block.getByRole('combobox').click(); await block.getByRole('option', { name: 'Dracula', exact: true }).click()
  assert.equal(await block.getAttribute('data-code-theme'), 'dracula')
  await block.getByRole('button', { name: '复制 Worker.ts', exact: true }).click()
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), 'const value = "<safe>"')
  await page.screenshot({ path: resolve(output, 'worker-preview.png') })
  await page.getByRole('textbox', { name: 'Markdown 样板源码', exact: true }).fill('## 最新预览\n\n正文仍是 Markdown。')
  await preview.getByRole('heading', { name: '最新预览', exact: true }).waitFor()
  assert.equal(workers.length, 1)
  assert.deepEqual(errors, []); assert.deepEqual(hydration, [])
  await writeFile(resolve(output, 'renderer-results.json'), JSON.stringify({ worker: workers, errors, hydration, taskList: 'passed', tableAlignment: 'passed', xss: 'passed', copyAndTheme: 'passed' }, null, 2))
  await context.close()
  console.log('PASS: SSR 样板、Worker 更新与复用、任务状态、表格对齐、XSS、代码主题和复制')
} finally { clearTimeout(watchdog); await browser.close(); await server.close() }
