-- 1) document 收口上下架状态、可见性分级与单篇 AI 开关
ALTER TABLE document
  ADD COLUMN publish_status smallint NOT NULL DEFAULT 0 CHECK (publish_status IN (0, 1, 2)),
  ADD COLUMN visibility_level smallint NOT NULL DEFAULT 1 CHECK (visibility_level IN (1, 2, 3)),
  ADD COLUMN ai_index_enabled boolean NOT NULL DEFAULT true;

-- 无损回填：原 blog_post.status=1 -> 1(已上架)；原 status=0 且存在正式槽 stage=1 -> 2(已下架)；其余 -> 0(未上架)
UPDATE document d
SET publish_status = CASE
  WHEN b.status = 1 THEN 1
  WHEN EXISTS (SELECT 1 FROM document_content dc WHERE dc.document_id = d.id AND dc.stage = 1) THEN 2
  ELSE 0
END
FROM blog_post b
WHERE b.document_id = d.id;

CREATE INDEX idx_document_publish_visibility ON document (publish_status, visibility_level);

-- 2) blog_post 退掉重复的 status 列，仅保留纯博客渠道属性（slug / 下载开关 / published_at）
DROP INDEX IF EXISTS idx_blog_post_published;
ALTER TABLE blog_post DROP COLUMN status;
CREATE INDEX idx_blog_post_published_at ON blog_post (published_at DESC, id DESC);

-- 3) 知识同步队列表
CREATE TABLE knowledge_source_sync_queue (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_type smallint NOT NULL CHECK (source_type IN (1,2)),
  source_id bigint NOT NULL CHECK (source_id > 0),
  change_seq bigint NOT NULL CHECK (change_seq > 0),
  projection_hash bytea CHECK (octet_length(projection_hash)=32),
  action smallint NOT NULL CHECK (action IN (1,2,3)),
  build_mode smallint NOT NULL CHECK (build_mode IN (1,2)),
  status smallint NOT NULL CHECK (status IN (0,1,2,3,4)),
  due_at timestamptz NOT NULL,
  lease_token uuid,
  lease_until timestamptz,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
  last_error smallint CHECK (last_error IN (1,2,3,4)),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_type, source_id),
  CHECK ((action=1) = (projection_hash IS NOT NULL)),
  CHECK (action=1 OR build_mode=1),
  CHECK ((status=1) = (lease_token IS NOT NULL)),
  CHECK ((status=1) = (lease_until IS NOT NULL)),
  CHECK (status<>1 OR attempt_count>0)
);
CREATE INDEX knowledge_sync_queue_due_idx ON knowledge_source_sync_queue(due_at,source_type,source_id)
  WHERE status IN (0,2);
CREATE INDEX knowledge_sync_queue_lease_idx ON knowledge_source_sync_queue(lease_until)
  WHERE status=1;
