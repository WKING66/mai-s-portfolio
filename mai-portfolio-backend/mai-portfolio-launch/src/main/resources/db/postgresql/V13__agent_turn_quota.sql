ALTER TABLE agent_turn ADD COLUMN quota_at timestamptz;

-- V10 的已完成状态为 3。已完成且从未绑定执行的旧轮次来自历史导入。
UPDATE agent_turn
SET quota_at = created_at
WHERE execution_id IS NOT NULL OR status <> 3;

COMMENT ON COLUMN agent_turn.quota_at IS '网站受理新问答并记入轮次额度的时间，历史导入为空';

CREATE INDEX agent_turn_quota_time_idx ON agent_turn (quota_at)
    WHERE quota_at IS NOT NULL;

CREATE INDEX agent_turn_quota_conversation_time_idx ON agent_turn (conversation_id, quota_at)
    WHERE quota_at IS NOT NULL;
