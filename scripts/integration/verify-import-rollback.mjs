import { assertIsolatedBackend } from './assert-isolated-backend.mjs';
import assert from 'node:assert/strict';
import { publicEncrypt, constants, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { resolve } from 'node:path';
// 在独立库注入一次持久化失败，验证真正的事务回滚与已上传 OSS 文件补偿，不替换存储。
assert.equal(process.env.BASELINE_ISOLATED, '1');
assert.equal(process.env.ACCEPTANCE_DB, 'system_acceptance_20261013');
const base = 'http://127.0.0.1:3000', out = process.env.BASELINE_OUTPUT_DIR;
const sql = s => execFileSync(process.env.ACCEPTANCE_PSQL, ['-h', '127.0.0.1', '-p', '55442', '-U', 'postgres', '-d', process.env.ACCEPTANCE_DB, '-At', '-c', s], { encoding: 'utf8', windowsHide: true }).trim();
const key = await readFile(process.env.BASELINE_PUBLIC_KEY_FILE, 'utf8'), encryptedPassword = publicEncrypt({ key, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(process.env.BASELINE_OWNER_PASSWORD)).toString('base64');
const login = await fetch(base + '/api/v1/auth/session', { method: 'POST', headers: { Origin: base, 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'owner', encryptedPassword }) });
assert.equal(login.status, 200);
const session = (await login.json()).data, cookie = login.headers.getSetCookie().find(v => v.startsWith('portfolio_session='))?.split(';')[0];
await assertIsolatedBackend(base, cookie);
const before = sql('SELECT count(*) FROM media_asset'), documents = sql('SELECT count(*) FROM document_version'), last = Number(sql('SELECT coalesce(max(id),0) FROM media_asset'));
const title = 'rollback-' + randomUUID(), createdKeys = new Set();
let result;
try {
    sql(`ALTER TABLE document_version ADD CONSTRAINT acceptance_reject_document CHECK (title <> '${title}') NOT VALID`);
    const form = new FormData();
    form.set('file', new Blob([`# ${title}\n\n[附件](rollback.txt)`], { type: 'text/markdown' }), 'rollback.md');
    form.append('resources', new Blob(['transaction rollback test'], { type: 'text/plain' }), 'rollback.txt');
    const request = fetch(base + '/api/v1/blogs/imports', { method: 'POST', headers: { Origin: base, Cookie: cookie, 'X-CSRF-Token': session.csrfToken }, body: form, signal: AbortSignal.timeout(60000) });
    let finished = false;
    request.finally(() => { finished = true; }).catch(() => { });
    while (!finished) {
        for (const k of sql(`SELECT storage_key FROM media_asset WHERE id>${last}`).split('\n').filter(Boolean))
            createdKeys.add(k);
        await delay(100);
    }
    result = await request;
    assert.equal(result.status, 409);
    assert.equal(sql('SELECT count(*) FROM media_asset'), before);
    assert.equal(sql('SELECT count(*) FROM document_version'), documents);
    assert.equal(createdKeys.size, 1, 'must observe real uploaded test asset');
    const manifest = resolve(out, 'rollback-oss-keys.txt');
    await writeFile(manifest, [...createdKeys].join('\n'));
    execFileSync(process.env.ACCEPTANCE_JAVA, ['--class-path', process.env.ACCEPTANCE_OSS_CLASSPATH, resolve('scripts/integration/OssAcceptanceCleanup.java'), manifest, '--assert-absent'], { windowsHide: true });
    await writeFile(resolve(out, 'rollback-summary.json'), JSON.stringify({ status: result.status, databaseRolledBack: true, realOssObjectRemoved: true, objects: createdKeys.size }, null, 2));
    console.log('real import failure: database rolled back; uploaded OSS object already absent');
}
finally {
    sql('ALTER TABLE document_version DROP CONSTRAINT IF EXISTS acceptance_reject_document');
    if (createdKeys.size) {
        const manifest = resolve(out, 'rollback-oss-keys.txt');
        await writeFile(manifest, [...createdKeys].join('\n'));
        execFileSync(process.env.ACCEPTANCE_JAVA, ['--class-path', process.env.ACCEPTANCE_OSS_CLASSPATH, resolve('scripts/integration/OssAcceptanceCleanup.java'), manifest], { windowsHide: true });
    }
}
