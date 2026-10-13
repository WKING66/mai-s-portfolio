-- 版本中的资源引用是业务事实；文件存储元数据继续归 media_asset。
-- 与项目现有状态约定一致，使用 SMALLINT；就绪/类型规则由领域服务统一校验。
ALTER TABLE document_version_asset ALTER COLUMN media_id DROP NOT NULL;
ALTER TABLE document_version_asset ADD COLUMN resource_key VARCHAR(36);
ALTER TABLE document_version_asset ADD COLUMN resource_type SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE document_version_asset ADD COLUMN source_kind SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE document_version_asset ADD COLUMN status SMALLINT NOT NULL DEFAULT 0;
UPDATE document_version_asset SET resource_key = id::text;
ALTER TABLE document_version_asset ALTER COLUMN resource_key SET NOT NULL;
ALTER TABLE document_version_asset ADD CONSTRAINT uq_document_resource_key UNIQUE (document_version_id, resource_key);
COMMENT ON TABLE document_version_asset IS '文档版本资源引用；未就绪资源保留来源并允许文件关联为空';
COMMENT ON COLUMN document_version_asset.media_id IS '已归档的 Asset ID；抓取失败或无效资源为空';
COMMENT ON COLUMN document_version_asset.resource_key IS '稳定正文引用 resource://key，与 OSS URL 无关';
COMMENT ON COLUMN document_version_asset.resource_type IS '资源类型：0 IMAGE，1 ATTACHMENT';
COMMENT ON COLUMN document_version_asset.source_kind IS '资源来源：0 EMBEDDED，1 LOCAL，2 REMOTE';
COMMENT ON COLUMN document_version_asset.status IS '资源处理状态：0 READY，1 FETCH_FAILED，2 INVALID';
