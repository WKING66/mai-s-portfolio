ALTER TABLE agent_turn ADD COLUMN is_deleted boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN agent_turn.is_deleted IS '会话清空的逻辑删除标记；为真时不展示、不进入模型上下文、不支持原执行恢复，原问答和计次记录仍保留';
