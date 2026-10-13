import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

/** 默认端口也可能是维护者开发服务：写入前用随机只读探针证明 HTTP 与隔离库一致。 */
export async function assertIsolatedBackend(base, cookie) {
    assert.equal(base, 'http://127.0.0.1:3000');
    assert.equal(process.env.BASELINE_ISOLATED, '1');
    assert.equal(process.env.ACCEPTANCE_DB, 'system_acceptance_20261013');
    assert.ok(process.env.ACCEPTANCE_PSQL);
    const marker = 'acceptance-env-' + randomUUID();
    const sql = statement => execFileSync(process.env.ACCEPTANCE_PSQL, ['-h', '127.0.0.1', '-p', '55442', '-U', 'postgres', '-d', process.env.ACCEPTANCE_DB, '-At', '-c', statement], { encoding: 'utf8', windowsHide: true });
    // TECH=1、LANGUAGE=0 是现有 Schema/枚举编码；仅插入本轮临时验证标签。
    try {
        sql(`INSERT INTO tag(kind,name,normalized_name,slug,group_code,logo_key,is_featured,sort_order) VALUES(1,'${marker}','${marker}','${marker}',0,NULL,0,0)`);
        const response = await fetch(base + '/api/v1/tags?kind=TECH', { headers: { Cookie: cookie }, signal: AbortSignal.timeout(15000) });
        assert.equal(response.status, 200, 'cannot confirm isolated backend');
        const envelope = await response.json();
        assert.equal(envelope.code, 'OK');
        assert.ok(envelope.data.some(tag => tag.slug === marker), 'HTTP service is not using the isolated test database; writes refused');
    } finally {
        sql(`DELETE FROM tag WHERE slug='${marker}'`);
    }
}
