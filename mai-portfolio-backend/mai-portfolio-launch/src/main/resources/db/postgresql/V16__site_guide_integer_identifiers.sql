-- 切换前必须停收并排空对话。旧记录仍在活动状态时拒绝迁移。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM agent_turn WHERE status IN (0, 1)) THEN
        RAISE EXCEPTION '仍有网站问答未收尾，请关停新对话并完成恢复';
    END IF;
END $$;

ALTER TABLE agent_conversation
    ADD COLUMN numeric_id bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE agent_turn ADD COLUMN numeric_conversation_id bigint;

UPDATE agent_turn turn
SET numeric_conversation_id = conversation.numeric_id
FROM agent_conversation conversation
WHERE turn.conversation_id = conversation.id;

ALTER TABLE agent_turn ALTER COLUMN numeric_conversation_id SET NOT NULL;
ALTER TABLE agent_turn DROP CONSTRAINT agent_turn_conversation_id_fkey;

-- 与 Python 0008 使用同一历史编号区间。新版已绑定的整数编号直接沿用。
ALTER TABLE agent_turn
    ALTER COLUMN execution_id TYPE bigint USING (
        CASE
            WHEN execution_id IS NULL THEN NULL
            WHEN execution_id ~ '^[1-9][0-9]{0,18}$' THEN execution_id::bigint
            ELSE (
                ('x' || substr(replace(execution_id, '-', ''), 1, 16))::bit(64)::bigint
                    & 4611686018427387903
            ) | 4611686018427387904
        END
    );

ALTER TABLE agent_turn
    DROP CONSTRAINT agent_turn_pkey,
    ADD CONSTRAINT agent_turn_request_id_key UNIQUE (request_id),
    DROP COLUMN conversation_id;
ALTER TABLE agent_turn RENAME COLUMN numeric_conversation_id TO conversation_id;
ALTER TABLE agent_turn RENAME COLUMN ordinal TO id;
ALTER TABLE agent_turn ADD PRIMARY KEY (id);

ALTER TABLE agent_conversation
    DROP CONSTRAINT agent_conversation_pkey,
    DROP COLUMN id,
    DROP COLUMN import_id;
ALTER TABLE agent_conversation RENAME COLUMN numeric_id TO id;
ALTER TABLE agent_conversation ADD PRIMARY KEY (id);

-- V15 已回填既有会话。若准备迁移后旧进程又创建了记录，按相同规则补齐。
WITH history AS (
    SELECT c.id, COALESCE(c.created_at, t.created_at, c.updated_at) AS created_at,
           NULLIF(left(btrim(t.question), 25), '') AS title
    FROM agent_conversation c
    LEFT JOIN LATERAL (
        SELECT turn.created_at, turn.question
        FROM agent_turn turn WHERE turn.conversation_id = c.id
        ORDER BY turn.id LIMIT 1
    ) t ON true
    WHERE c.created_at IS NULL OR c.title IS NULL
)
UPDATE agent_conversation c
SET created_at = history.created_at,
    title = COALESCE(c.title, history.title, '新会话 ' ||
                     to_char(history.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS'))
FROM history WHERE c.id = history.id;

ALTER TABLE agent_conversation
    ALTER COLUMN title SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN created_at SET NOT NULL;

UPDATE agent_turn
SET error_mes = COALESCE(error_mes, CASE error_code
        WHEN 'ACCEPTANCE_EXPIRED' THEN '这次发送未受理，请重新发送。'
        WHEN 'EXECUTION_TEXT_EXPIRED' THEN '原回答已无法恢复。'
        ELSE '模型服务暂不可用。' END)
WHERE error_code IS NOT NULL;
UPDATE agent_turn SET usage_status = 3 WHERE usage_status = 0;
ALTER TABLE agent_turn
    DROP COLUMN error_code,
    DROP CONSTRAINT agent_turn_status_check,
    ADD CONSTRAINT agent_turn_status_check CHECK (status IN (0, 1, 3, 4, 5));

CREATE INDEX agent_conversation_owner_activity_idx
    ON agent_conversation (owner_key, updated_at DESC, id DESC);
CREATE INDEX agent_turn_conversation_order_idx ON agent_turn (conversation_id, id);
CREATE UNIQUE INDEX agent_turn_active_conversation_idx
    ON agent_turn (conversation_id) WHERE status IN (0, 1);
CREATE INDEX agent_turn_quota_conversation_time_idx
    ON agent_turn (conversation_id, quota_at) WHERE quota_at IS NOT NULL;

COMMENT ON TABLE agent_conversation IS '网站会话，保存归属、标题和最近活动时间';
COMMENT ON COLUMN agent_conversation.id IS '自增会话编号，网站 API 以十进制字符串传输';
COMMENT ON COLUMN agent_conversation.created_at IS '首次建立会话的时间，用于生成默认标题';
COMMENT ON TABLE agent_turn IS '网站正式保存的问答、执行状态以及每轮 Token 汇总';
COMMENT ON COLUMN agent_turn.id IS '原 ordinal，自增轮次主键，保持会话内问答顺序';
COMMENT ON COLUMN agent_turn.conversation_id IS '所属 agent_conversation.id，关联由 Spring 维护';
COMMENT ON COLUMN agent_turn.execution_id IS 'Python 的 model_execution.id，未绑定或历史导入时为空';
COMMENT ON COLUMN agent_turn.status IS '网站状态：0 待受理，1 已受理，3 完成，4 失败，5 中断';
