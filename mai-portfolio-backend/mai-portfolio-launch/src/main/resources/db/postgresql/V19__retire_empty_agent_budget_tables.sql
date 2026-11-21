-- Spring 已退出金额预占。旧账本若还存有记录，先归档核对，避免丢弃历史数据。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM agent_model_attempt)
        OR EXISTS (SELECT 1 FROM agent_model_attempt_cancelled) THEN
        RAISE EXCEPTION '旧模型账本仍有记录，请先归档并核对再执行迁移';
    END IF;
END;
$$;

DROP TABLE agent_model_attempt_cancelled;
DROP TABLE agent_model_attempt;
