-- 统一业务枚举的存储编码；未知旧值会使迁移失败，避免静默归为另一个类别。
-- flyway_schema_history.type 是 Flyway 管理的迁移格式，mime_type 是开放 MIME 文本，均不属于业务枚举。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_content WHERE stage NOT IN ('DRAFT', 'PUBLISHED'))
        OR EXISTS (SELECT 1 FROM document_content_tag WHERE kind NOT IN ('TOPIC', 'TECH'))
        OR EXISTS (SELECT 1 FROM document_import_task WHERE status NOT IN
            ('PROCESSING', 'SUCCEEDED', 'SUCCEEDED_WITH_ISSUES', 'FAILED', 'INTERRUPTED'))
        OR EXISTS (SELECT 1 FROM media_asset WHERE usage NOT IN ('AVATAR', 'IMAGE', 'ATTACHMENT'))
    THEN
        RAISE EXCEPTION 'Unknown categorical value; stop before changing any business table';
    END IF;
END $$;

ALTER TABLE document_content DROP CONSTRAINT document_content_stage_check;
ALTER TABLE document_content ALTER COLUMN stage TYPE SMALLINT
    USING (CASE stage WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE NULL END)::SMALLINT;
COMMENT ON COLUMN document_content.stage IS '内容阶段：0=工作草稿，1=正式正文；与博客上架状态独立';

ALTER TABLE document_content_tag DROP CONSTRAINT document_content_tag_kind_check;
ALTER TABLE document_content_tag ALTER COLUMN kind TYPE SMALLINT
    USING (CASE kind WHEN 'TOPIC' THEN 0 WHEN 'TECH' THEN 1 ELSE NULL END)::SMALLINT;
COMMENT ON COLUMN document_content_tag.kind IS '标签类别，与 tag.kind 一致：0=主题 TOPIC，1=技术 TECH';

ALTER TABLE document_import_task DROP CONSTRAINT ck_import_task_status;
ALTER TABLE document_import_task ALTER COLUMN status DROP DEFAULT;
ALTER TABLE document_import_task ALTER COLUMN status TYPE SMALLINT
    USING (CASE status WHEN 'PROCESSING' THEN 0 WHEN 'SUCCEEDED' THEN 1
        WHEN 'SUCCEEDED_WITH_ISSUES' THEN 2 WHEN 'FAILED' THEN 3
        WHEN 'INTERRUPTED' THEN 4 ELSE NULL END)::SMALLINT;
ALTER TABLE document_import_task ALTER COLUMN status SET DEFAULT 4;
COMMENT ON COLUMN document_import_task.status IS '导入状态：0=处理中，1=成功，2=已保存但有资源问题，3=失败，4=中断或未确认结束';

ALTER TABLE media_asset ALTER COLUMN usage TYPE SMALLINT
    USING (CASE usage WHEN 'AVATAR' THEN 0 WHEN 'IMAGE' THEN 1
        WHEN 'ATTACHMENT' THEN 2 ELSE NULL END)::SMALLINT;
COMMENT ON COLUMN media_asset.usage IS '上传用途：0=头像 AVATAR，1=公开图片 IMAGE，2=附件 ATTACHMENT';
