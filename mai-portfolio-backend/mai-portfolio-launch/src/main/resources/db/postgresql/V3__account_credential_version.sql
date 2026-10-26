-- 与密码同事务更新；每个登录 token 保存登录时版本，Redis 清理失败不能恢复旧凭据。
ALTER TABLE user_account
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0
    CHECK (credential_version >= 0);
