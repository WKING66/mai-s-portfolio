import assert from 'node:assert/strict'
import { publicEncrypt, constants, randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

// 仅用于明确确认的隔离候选服务；不能拿维护者开发库或生产账号运行写入验收。
const base = process.env.BASELINE_URL || 'http://127.0.0.1:3040'
const target = new URL(base)
assert.equal(process.env.BASELINE_ISOLATED, '1', '必须明确确认隔离测试环境')
assert.equal(target.hostname, '127.0.0.1')
assert.equal(target.port, '3040')
const password = process.env.BASELINE_OWNER_PASSWORD
assert.ok(password && process.env.BASELINE_PUBLIC_KEY_FILE && process.env.BASELINE_OUTPUT_DIR)
const publicKey = await readFile(process.env.BASELINE_PUBLIC_KEY_FILE, 'utf8')
const output = resolve(process.env.BASELINE_OUTPUT_DIR)
const checks = []
const encrypt = value => publicEncrypt({ key: publicKey, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(value)).toString('base64')
async function call(method, path, { body, session, expected = 200 } = {}) {
  const headers = { Origin: base }
  if (session) {
    headers.Cookie = session.cookie
    if (method !== 'GET') headers['X-CSRF-Token'] = session.csrf
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(base + '/api/v1' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) })
  assert.equal(response.status, expected, `${method} ${path}`)
  const value = await response.json()
  if (expected === 200) assert.equal(value.code, 'OK', `${method} ${path}`)
  // 只记录接口与结果，Cookie、密文、正文、签名 URL 均不进入验收日志。
  checks.push({ method, path: path.split('?')[0], status: response.status, code: value.code })
  return { response, data: value.data }
}
async function login(username, plainPassword) {
  const result = await call('POST', '/auth/session', { body: { username, encryptedPassword: encrypt(plainPassword) } })
  const cookie = result.response.headers.getSetCookie().find(value => value.startsWith('portfolio_session='))?.split(';')[0]
  assert.ok(cookie && result.data.csrfToken)
  return { cookie, csrf: result.data.csrfToken }
}
const owner = await login('owner', password)
if (process.argv.includes('--cleanup')) {
  const state = JSON.parse(await readFile(resolve(output, 'article-state.json'), 'utf8'))
  const { data: current } = await call('GET', `/blogs/${state.id}`, { session: owner })
  await call('POST', `/blogs/${state.id}/unpublish`, { session: owner, body: { version: current.version } })
  await call('GET', `/blogs/by-slug/${state.slug}`, { expected: 404 })
  await call('DELETE', '/auth/session', { session: owner })
  await writeFile(resolve(output, 'cleanup-summary.json'), JSON.stringify({ checks }, null, 2))
  console.log(JSON.stringify({ cleanupChecks: checks.length }))
} else {
  await call('GET', '/profile')
  await call('GET', '/projects?view=PUBLIC&page=1&size=12')
  await call('GET', '/blogs?view=MANAGE', { expected: 401 })
  await call('GET', '/account/profile', { session: owner })
  await call('GET', '/tags?kind=TECH', { session: owner })
  const slug = 'baseline-' + randomUUID()
  const markdown = '# 数据结构与算法：隔离基线验收\n\n## 示例代码\n\n```javascript\nconst total = [1, 2, 3].reduce((a, b) => a + b, 0)\n```\n\n## 示例公式\n\n$$x^2 + y^2 = z^2$$\n\n## 示例流程\n\n```mermaid\nflowchart LR\n A[输入] --> B[文档]\n B --> C[展示]\n```\n\n' + Array.from({ length: 30 }, (_, index) => `## 阅读章节 ${index + 1}\n\n这是独立临时数据库中的合成验收正文，用于验证目录、折叠、SSR 和 Markdown 契约。\n\n`).join('')
  const input = content => ({ slug, title: '数据结构与算法：隔离基线验收', summary: '独立测试环境中的合成内容', content, allowDownload: true, remoteRightsConfirmed: false })
  const { data: draft } = await call('POST', '/blogs', { session: owner, body: input(markdown) })
  await call('GET', `/blogs/by-slug/${slug}`, { expected: 404 })
  const { data: firstPublished } = await call('POST', `/blogs/${draft.id}/publish`, { session: owner, body: { version: draft.version } })
  const { data: edited } = await call('PATCH', `/blogs/${draft.id}`, { session: owner, body: { ...input(markdown + '\n仅在新草稿中的内容'), version: firstPublished.version } })
  const { data: unchangedPublic } = await call('GET', `/blogs/by-slug/${slug}`)
  assert.equal(unchangedPublic.content, markdown)
  assert.ok(!('html' in unchangedPublic), '公开正文契约应为 Markdown')
  await call('PATCH', `/blogs/${draft.id}`, { session: owner, body: { ...input(markdown), version: firstPublished.version }, expected: 409 })
  const { data: published } = await call('POST', `/blogs/${draft.id}/publish`, { session: owner, body: { version: edited.version } })
  await call('GET', `/blogs/${draft.id}/export?view=PUBLIC`, { expected: 401 })
  const username = 'baseline_' + randomUUID().replaceAll('-', '')
  const normalPassword = 'baseline-visitor-test-20261013'
  await call('POST', '/auth/register', { body: { username, encryptedPassword: encrypt(normalPassword) } })
  const visitor = await login(username, normalPassword)
  await call('GET', '/blogs?view=MANAGE', { session: visitor, expected: 403 })
  await call('POST', `/blogs/${draft.id}/unpublish`, { session: visitor, body: { version: published.version }, expected: 403 })
  const download = await fetch(base + `/api/v1/blogs/${draft.id}/export?view=PUBLIC&includeResources=true`, { headers: { Cookie: visitor.cookie }, signal: AbortSignal.timeout(20000) })
  assert.equal(download.status, 200)
  assert.match(download.headers.get('content-disposition'), /\.md/)
  assert.match(await download.text(), /仅在新草稿中的内容/)
  checks.push({ method: 'GET', path: '/blogs/{id}/export', status: 200, bodyOnlyEvenWhenResourcesSelected: true })
  await call('DELETE', '/auth/session', { session: visitor })
  await call('DELETE', '/auth/session', { session: owner })
  await writeFile(resolve(output, 'article-state.json'), JSON.stringify({ id: draft.id, slug }))
  await writeFile(resolve(output, 'api-summary.json'), JSON.stringify({ checks, scope: '真实候选前端代理和后端、临时 PostgreSQL；本轮未上传云资源' }, null, 2))
  console.log(JSON.stringify({ apiChecks: checks.length, realDatabase: true, cloudResourceUpload: false }))
}
