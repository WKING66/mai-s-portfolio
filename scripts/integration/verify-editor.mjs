import { assertIsolatedBackend } from './assert-isolated-backend.mjs';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
assert.equal(process.env.BASELINE_ISOLATED, '1');
assert.equal(process.env.ACCEPTANCE_DB, 'system_acceptance_20261013');
const base = 'http://127.0.0.1:3000', out = process.env.BASELINE_OUTPUT_DIR, keys = [];
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH);
const browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE });
try {
    const context = await browser.newContext(), page = await context.newPage();
    page.setDefaultTimeout(30000);
    await page.goto(base + '/login');
    await page.locator('#username').fill('owner');
    await page.locator('#password').fill(process.env.BASELINE_OWNER_PASSWORD);
    await page.locator('button[type=submit]').click();
    await page.waitForURL(base + '/');
    const cookies = await context.cookies(base);
    await assertIsolatedBackend(base, 'portfolio_session=' + cookies.find(cookie => cookie.name === 'portfolio_session').value);
    await page.goto(base + '/admin/blogs');
    await page.getByRole('link', { name: '新建文章', exact: true }).click();
    await page.getByPlaceholder('给文章起个标题').fill('浏览器新建及文件上传验收');
    await page.locator('#article-slug').fill('editor-' + randomUUID());
    await page.getByPlaceholder('使用 Markdown 开始写作…').fill('# 浏览器新建文章\n\n真实文件选择与统一上传。');
    const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF1sAAAAASUVORK5CYII=', 'base64');
    for (const file of [{ label: '选择正文图片', name: 'editor.png', mimeType: 'image/png', buffer: image }, { label: '选择正文附件', name: 'editor.txt', mimeType: 'text/plain', buffer: Buffer.from('browser attachment') }]) {
        const uploaded = page.waitForResponse(r => r.url().endsWith('/assets') && r.request().method() === 'POST');
        await page.getByLabel(file.label, { exact: true }).setInputFiles({ name: file.name, mimeType: file.mimeType, buffer: file.buffer });
        const response = await uploaded;
        assert.equal(response.status(), 200);
        const asset = (await response.json()).data;
        const key = execFileSync(process.env.ACCEPTANCE_PSQL, ['-h', '127.0.0.1', '-p', '55442', '-U', 'postgres', '-d', process.env.ACCEPTANCE_DB, '-At', '-c', `SELECT storage_key FROM media_asset WHERE id=${Number(asset.id)}`], { encoding: 'utf8', windowsHide: true }).trim();
        keys.push(key);
        await page.waitForFunction(id => document.querySelector('textarea[placeholder="使用 Markdown 开始写作…"]').value.includes(`asset://${id}`), asset.id);
    }
    const saved = page.waitForResponse(r => r.url().endsWith('/blogs') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    const response = await saved;
    assert.equal(response.status(), 200);
    const draft = (await response.json()).data;
    assert.equal(draft.resources.length, 2);
    const publish = page.waitForResponse(r => r.url().endsWith('/publish'));
    await page.getByRole('button', { name: '发布当前草稿', exact: true }).click();
    assert.equal((await publish).status(), 200);
    await page.getByRole('link', { name: '查看公开文章', exact: true }).click();
    await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor();
    await page.locator('.blog-renderer img').scrollIntoViewIfNeeded();
    await page.waitForFunction(() => { const image = document.querySelector('.blog-renderer img'); return image?.complete && image.naturalWidth > 0; });
    await page.goto(`${base}/admin/blogs/${draft.id}`);
    const unpublished = page.waitForResponse(r => r.url().endsWith('/unpublish'));
    await page.getByRole('button', { name: '下架文章', exact: true }).click();
    assert.equal((await unpublished).status(), 200);
    await page.goto(base + '/login');
    const logout = page.waitForResponse(r => r.url().endsWith('/auth/session') && r.request().method() === 'DELETE');
    await page.getByRole('button', { name: '注销账号', exact: true }).click();
    assert.equal((await logout).status(), 200);
    await writeFile(resolve(out, 'editor-summary.json'), JSON.stringify({ realUi: true, fixtures: false, create: true, uploadImage: true, uploadAttachment: true, save: true, publish: true, imageLoaded: true, unpublish: true, logout: true }, null, 2));
    console.log('real editor create/upload/save/publish/image/unpublish/logout: PASS');
}
finally {
    await browser.close();
    if (keys.length) {
        const manifest = resolve(out, 'editor-oss-keys.txt');
        await writeFile(manifest, keys.join('\n'));
        execFileSync(process.env.ACCEPTANCE_JAVA, ['--class-path', process.env.ACCEPTANCE_OSS_CLASSPATH, resolve('scripts/integration/OssAcceptanceCleanup.java'), manifest], { windowsHide: true });
    }
}
