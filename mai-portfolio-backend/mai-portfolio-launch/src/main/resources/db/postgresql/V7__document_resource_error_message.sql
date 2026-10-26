-- 失败原因属于文档版本资源事实，保存安全摘要，刷新或读取历史版本时不丢失。
ALTER TABLE document_version_asset ADD COLUMN error_message VARCHAR(512);
COMMENT ON COLUMN document_version_asset.error_message IS '资源失败的安全诊断摘要；就绪资源及未记录原因的历史资源为空，仅管理端投影公开';
