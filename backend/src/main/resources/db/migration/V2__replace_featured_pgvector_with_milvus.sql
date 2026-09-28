-- 保留可能被项目或文章关联的历史标签，仅从公开技术栈移除。
-- 新的 Milvus 标签由 BootstrapServiceImpl 按默认技术栈补齐。
UPDATE tag
SET is_featured = 0
WHERE kind = 1 AND slug = 'tech-pgvector';
