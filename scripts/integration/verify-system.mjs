import { assertIsolatedBackend } from './assert-isolated-backend.mjs';
import assert from 'node:assert/strict';
import { publicEncrypt, constants, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
// 写入和账号状态变更只允许本脚本新建的隔离账号；不能用于开发库/生产服务。
const base = 'http://127.0.0.1:3000', root = process.env.BASELINE_OUTPUT_DIR;
assert.equal(process.env.BASELINE_ISOLATED, '1');
assert.ok(root && process.env.BASELINE_PUBLIC_KEY_FILE && process.env.BASELINE_OWNER_PASSWORD);
assert.equal(process.env.ACCEPTANCE_DB, 'system_acceptance_20261013');
const key = await readFile(process.env.BASELINE_PUBLIC_KEY_FILE, 'utf8'), checks = [], assets = [];
const encrypt = text => publicEncrypt({ key, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(text)).toString('base64');
function sql(statement) { return execFileSync(process.env.ACCEPTANCE_PSQL, ['-h', '127.0.0.1', '-p', '55442', '-U', 'postgres', '-d', process.env.ACCEPTANCE_DB, '-At', '-c', statement], { encoding: 'utf8', windowsHide: true }).trim(); }
async function call(method, path, { session, body, form, status = 200 } = {}) {
    const headers = { Origin: base };
    if (session) {
        headers.Cookie = session.cookie;
        if (method !== 'GET')
            headers['X-CSRF-Token'] = session.csrf;
    }
    if (body !== undefined)
        headers['Content-Type'] = 'application/json';
    const response = await fetch(base + '/api/v1' + path, { method, headers, body: form || (body === undefined ? undefined : JSON.stringify(body)), signal: AbortSignal.timeout(60000) });
    assert.equal(response.status, status, `${method} ${path}: expected ${status}, received ${response.status}`);
    const value = await response.json();
    if (status === 200)
        assert.equal(value.code, 'OK');
    checks.push({ method, path: path.split('?')[0], status, code: value.code });
    return { response, data: value.data };
}
async function login(username, password) { const { response, data } = await call('POST', '/auth/session', { body: { username, encryptedPassword: encrypt(password) } }); const cookie = response.headers.getSetCookie().find(s => s.startsWith('portfolio_session='))?.split(';')[0]; assert.ok(cookie && data.csrfToken); return { cookie, csrf: data.csrfToken }; }
const account = 'accept_' + randomUUID().replaceAll('-', ''), password = 'Acceptance-' + randomUUID(), newPassword = 'Acceptance-' + randomUUID();
const owner = await login('owner', process.env.BASELINE_OWNER_PASSWORD);
await assertIsolatedBackend(base, owner.cookie);
let browser, completed = false;
try {
    await call('POST', '/auth/register', { body: { username: account, encryptedPassword: encrypt(password) } });
    const visitor = await login(account, password);
    assert.equal((await call('GET', '/auth/session', { session: visitor })).data.loggedIn, true);
    await call('PUT', '/account/profile', { session: visitor, body: { nickname: '隔离验收昵称' } });
    assert.equal((await call('GET', '/account/profile', { session: visitor })).data.nickname, '隔离验收昵称');
    await call('PUT', '/account/profile', { session: { ...visitor, csrf: 'invalid' }, body: { nickname: '不得保存' }, status: 403 });
    await call('GET', '/blogs?view=MANAGE', { session: visitor, status: 403 });
    await call('PUT', '/account/password', { session: visitor, body: { oldEncryptedPassword: encrypt('wrong-password'), newEncryptedPassword: encrypt(newPassword) }, status: 422 });
    await call('PUT', '/account/password', { session: visitor, body: { oldEncryptedPassword: encrypt(password), newEncryptedPassword: encrypt(newPassword) } });
    await call('GET', '/account/profile', { session: visitor, status: 401 });
    let active = await login(account, newPassword);
    sql(`UPDATE user_account SET status=0 WHERE username='${account}'`);
    await call('GET', '/account/profile', { session: active, status: 403 });
    await call('DELETE', '/auth/session', { session: active });
    sql(`UPDATE user_account SET status=1 WHERE username='${account}'`);
    active = await login(account, newPassword);
    sql("UPDATE user_account SET type=1 WHERE username='owner'");
    await call('GET', '/blogs?view=MANAGE', { session: owner, status: 403 });
    assert.equal((await call('GET', '/auth/session', { session: owner })).data.loggedIn, true);
    sql("UPDATE user_account SET type=0 WHERE username='owner'");
    // 无效图片在任何云端写入之前被拒绝，不能增加资产记录。
    const before = sql('SELECT count(*) FROM media_asset');
    const bad = new FormData();
    bad.set('usage', 'IMAGE');
    bad.set('file', new Blob(['invalid'], { type: 'image/png' }), 'bad.png');
    await call('POST', '/assets', { session: owner, form: bad, status: 422 });
    assert.equal(sql('SELECT count(*) FROM media_asset'), before);
    const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF1sAAAAASUVORK5CYII=', 'base64');
    async function upload(usage, bytes, name, type, session = owner) { const form = new FormData(); form.set('usage', usage); form.set('file', new Blob([bytes], { type }), name); const { data } = await call('POST', '/assets', { session, form }); assets.push({ id: data.id, key: sql(`SELECT storage_key FROM media_asset WHERE id=${Number(data.id)}`), url: data.resourceUrl }); assert.ok(new URL(data.resourceUrl).searchParams.has('Signature')); assert.ok(Date.parse(data.expiresAt) > Date.now()); return data; }
    const avatar = await upload('AVATAR', image, 'avatar.png', 'image/png', active);
    await call('PUT', '/account/avatar', { session: active, body: { assetId: avatar.id } });
    const photo = await upload('IMAGE', image, 'test.png', 'image/png'), attachment = await upload('ATTACHMENT', Buffer.from('isolated test attachment'), 'test.txt', 'text/plain');
    await call('GET', `/assets/${photo.id}`, { session: active, status: 403 });
    const imageResponse = await fetch(photo.resourceUrl);
    assert.equal(imageResponse.status, 200);
    assert.match(imageResponse.headers.get('content-type'), /image/);
    const unsigned = new URL(photo.resourceUrl);
    unsigned.search = '';
    assert.equal((await fetch(unsigned)).status, 403);
    const slug = 'acceptance-' + randomUUID(), title = '全链路隔离验收文章';
    const input = content => ({ slug, title, summary: '真实 OSS 和版本隔离验收', content, allowDownload: true, remoteRightsConfirmed: false });
    const markdown = `# ${title}\n\n![图片](asset://${photo.id})\n\n[附件](asset://${attachment.id})\n\n## 代码\n\n\`\`\`javascript\nconst x = 1\n\`\`\`\n\n## 公式\n\n$$x^2+y^2=z^2$$\n\n## 图表\n\n\`\`\`mermaid\nflowchart LR\n A --> B\n\`\`\`\n`;
    const { data: draft } = await call('POST', '/blogs', { session: owner, body: input(markdown) });
    assert.equal(draft.resources.length, 2);
    assert.ok(draft.content.includes('resource://'));
    assert.ok(!draft.content.includes('Signature='));
    const { data: published } = await call('POST', `/blogs/${draft.id}/publish`, { session: owner, body: { version: draft.version } });
    const { data: edited } = await call('PATCH', `/blogs/${draft.id}`, { session: owner, body: { ...input(draft.content + '\n仅草稿版本'), version: published.version } });
    assert.notEqual(edited.documentVersionId, published.documentVersionId);
    const { data: publicArticle } = await call('GET', `/blogs/by-slug/${slug}`);
    assert.equal(publicArticle.content, published.content);
    assert.equal(publicArticle.resources.length, 2);
    async function download(resources) { const r = await fetch(`${base}/api/v1/blogs/${draft.id}/export?view=PUBLIC&includeResources=${resources}`, { headers: { Cookie: active.cookie }, signal: AbortSignal.timeout(60000) }); assert.equal(r.status, 200); assert.match(r.headers.get('content-disposition'), resources ? /\.zip/ : /\.md/); const bytes = Buffer.from(await r.arrayBuffer()); const path = resolve(root, resources ? 'resources.zip' : 'body.md'); await writeFile(path, bytes); if (!resources) {
        assert.ok(!bytes.toString().includes('resource://'));
        assert.ok(bytes.toString().includes('Signature='));
    }
    else {
        execFileSync(process.env.ACCEPTANCE_PYTHON, ['-c', 'import zipfile,sys;z=zipfile.ZipFile(sys.argv[1]);assert z.testzip() is None;names=z.namelist();assert len(names)==3;md=z.read(next(n for n in names if n.endswith(".md"))).decode();assert "resource://" not in md;assert "Signature=" not in md;assert any(z.read(n)==b"isolated test attachment" for n in names)', path], { windowsHide: true });
    } checks.push({ path: '/blogs/{id}/export', status: 200, format: resources ? 'ZIP' : 'MD' }); }
    await download(false);
    await download(true);
    // Markdown 导入使用同一个 Asset 能力，并携带本地图片和附件，不用 fixture。
    const form = new FormData();
    form.set('file', new Blob(['# 导入验收\n\n![图](test.png)\n\n[附件](test.txt)'], { type: 'text/markdown' }), 'import.md');
    form.append('resources', new Blob([image], { type: 'image/png' }), 'test.png');
    form.append('resources', new Blob(['import attachment'], { type: 'text/plain' }), 'test.txt');
    const { data: imported } = await call('POST', '/blogs/imports', { session: owner, form });
    assert.equal(imported.status, 'DRAFT');
    assert.equal(imported.resources.length, 2);
    for (const r of imported.resources) {
        assert.equal(r.status, 'READY');
        assets.push({ id: r.assetId, key: sql(`SELECT storage_key FROM media_asset WHERE id=${Number(r.assetId)}`), url: r.resourceUrl });
    }
    const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH);
    browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE });
    const context = await browser.newContext({ acceptDownloads: true }), page = await context.newPage();
    page.setDefaultTimeout(30000);
    const imageNetwork = [];
    page.on('response', r => { if (r.request().resourceType() === 'image')
        imageNetwork.push({ host: new URL(r.url()).host, status: r.status() }); });
    page.on('requestfailed', r => { if (r.resourceType() === 'image')
        imageNetwork.push({ host: new URL(r.url()).host, failure: r.failure()?.errorText }); });
    const pageErrors = [];
    page.on('pageerror', e => pageErrors.push(e.message));
    await page.goto(base + '/register');
    await page.locator('#register-username').fill('browser_' + randomUUID().replaceAll('-', ''));
    await page.locator('#register-password').fill(password);
    const registered = page.waitForResponse(r => r.url().endsWith('/auth/register') && r.request().method() === 'POST');
    await page.locator('button[type=submit]').click();
    assert.equal((await registered).status(), 200);
    checks.push({ path: 'browser registration', status: 'PASS', fixtures: false });
    await page.goto(base + '/login');
    await page.locator('#username').fill('owner');
    await page.locator('#password').fill(process.env.BASELINE_OWNER_PASSWORD);
    await page.locator('button[type=submit]').click();
    await page.waitForURL(base + '/');
    await page.goto(`${base}/admin/blogs/${draft.id}`);
    await page.getByRole('button', { name: '保存草稿', exact: true }).waitFor();
    await page.getByPlaceholder('使用 Markdown 开始写作…').fill(edited.content + '\n浏览器实际编辑');
    const saved = page.waitForResponse(r => r.url().endsWith(`/blogs/${draft.id}`) && r.request().method() === 'PATCH');
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    assert.equal((await saved).status(), 200);
    assert.equal((await call('GET', `/blogs/by-slug/${slug}`)).data.content, publicArticle.content);
    const publishedUi = page.waitForResponse(r => r.url().endsWith(`/blogs/${draft.id}/publish`));
    await page.getByRole('button', { name: '发布当前草稿', exact: true }).click();
    assert.equal((await publishedUi).status(), 200);
    const mdEvent = page.waitForEvent('download');
    await page.getByRole('button', { name: '导出已保存草稿', exact: true }).click();
    const mdDownload = await mdEvent;
    assert.match(mdDownload.suggestedFilename(), /\.md$/);
    assert.equal(await mdDownload.failure(), null);
    await page.getByRole('checkbox', { name: '包含图片和附件（ZIP）' }).check();
    const zipEvent = page.waitForEvent('download');
    await page.getByRole('button', { name: '导出已保存草稿', exact: true }).click();
    const zipDownload = await zipEvent;
    assert.match(zipDownload.suggestedFilename(), /\.zip$/);
    assert.equal(await zipDownload.failure(), null);
    checks.push({ path: 'browser edit/save/publish/MD+ZIP download', status: 'PASS', fixtures: false });
    await page.goto(`${base}/blog/${slug}`);
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor();
    await page.locator('.blog-renderer img').first().scrollIntoViewIfNeeded();
    await page.waitForFunction(() => { const image = document.querySelector('.blog-renderer img'); return image?.complete && image.naturalWidth > 0; }).catch(async (error) => { const state = await page.locator('.blog-renderer img').first().evaluate(img => ({ complete: img.complete, width: img.naturalWidth, protocol: new URL(img.src).protocol })); await writeFile(resolve(root, 'image-diagnostic.json'), JSON.stringify({ state, imageNetwork }, null, 2)); throw error; });
    assert.ok(await page.locator('.katex').count());
    await page.locator('.blog-mermaid').scrollIntoViewIfNeeded();
    await page.locator('.blog-mermaid svg').first().waitFor();
    await page.reload();
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor();
    await page.goto(base + '/account');
    await page.locator('#account-nickname').fill('浏览器实际保存');
    const updated = page.waitForResponse(r => r.url().endsWith('/account/profile') && r.request().method() === 'PUT');
    await page.getByRole('button', { name: '保存资料', exact: true }).click();
    assert.equal((await updated).status(), 200);
    await page.reload();
    await page.locator('#account-nickname').waitFor();
    assert.equal(await page.locator('#account-nickname').inputValue(), '浏览器实际保存');
    assert.deepEqual(pageErrors, []);
    checks.push({ path: 'browser owner login/editor/save/profile/reload/article-image-math', status: 'PASS', fixtures: false });
    await browser.close();
    browser = undefined;
    // 当前会话的 CSRF 由服务端维护；同账号在浏览器重新登录后先读取新快照。
    owner.csrf = (await call('GET', '/auth/session', { session: owner })).data.csrfToken;
    const { data: current } = await call('GET', `/blogs/${draft.id}`, { session: owner });
    await call('POST', `/blogs/${draft.id}/unpublish`, { session: owner, body: { version: current.version } });
    await call('GET', `/blogs/by-slug/${slug}`, { status: 404 });
    await call('GET', `/blogs/${draft.id}/export?view=PUBLIC`, { session: active, status: 403 });
    assert.equal((await fetch(photo.resourceUrl)).status, 200);
    // 删除的是脚本自己的临时访客；随后必须自动变为匿名并拒绝受保护接口。
    sql(`DELETE FROM user_profile WHERE account_id=(SELECT id FROM user_account WHERE username='${account}')`);
    sql(`UPDATE media_asset SET uploader_account_id=NULL WHERE uploader_account_id=(SELECT id FROM user_account WHERE username='${account}')`);
    sql(`DELETE FROM user_account WHERE username='${account}'`);
    assert.equal((await call('GET', '/auth/session', { session: active })).data.loggedIn, false);
    await call('GET', '/account/profile', { session: active, status: 401 });
    await call('DELETE', '/auth/session', { session: owner });
    completed = true;
}
finally {
    if (browser)
        await browser.close();
    sql("UPDATE user_account SET type=0 WHERE username='owner'");
    // 精确删除本轮对象；既不扫 Bucket 前缀，也不变更访问权限。残留 ID 留在隔离库中。
    await writeFile(resolve(root, 'system-summary.json'), JSON.stringify({ completed, checks, realHttp: true, realOss: assets.length > 0 }, null, 2));
    if (assets.length) {
        const manifest = resolve(root, 'created-oss-keys.txt');
        await writeFile(manifest, assets.map(a => a.key).join('\n'));
        execFileSync(process.env.ACCEPTANCE_JAVA, ['--class-path', process.env.ACCEPTANCE_OSS_CLASSPATH, resolve('scripts/integration/OssAcceptanceCleanup.java'), manifest], { windowsHide: true });
        checks.push({ path: 'OSS exact-created-object cleanup', status: 'PASS', objects: assets.length });
    }
    await writeFile(resolve(root, 'system-summary.json'), JSON.stringify({ completed, checks, realHttp: true, realOss: assets.length > 0 }, null, 2));
}
console.log(JSON.stringify({ completed, checks: checks.length, realHttp: true, realOss: assets.length > 0 }));
