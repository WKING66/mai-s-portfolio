-- 只补充 V3 新增列的数据库说明，不修改既有迁移、凭据值或会话语义。
COMMENT ON COLUMN user_account.credential_version IS '账号凭据版本；修改密码时递增，会话保存登录时版本并在请求时校验';
