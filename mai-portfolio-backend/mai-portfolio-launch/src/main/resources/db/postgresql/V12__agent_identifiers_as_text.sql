-- 将已保存的 UUID 原值转换为标准字符串。C 排序保证会话游标在相同时间戳下沿用 UUID 的顺序。
ALTER TABLE agent_turn DROP CONSTRAINT agent_turn_conversation_id_fkey;

ALTER TABLE agent_conversation
    ALTER COLUMN id TYPE varchar(36) COLLATE "C" USING id::text,
    ALTER COLUMN import_id TYPE varchar(36) COLLATE "C" USING import_id::text;

ALTER TABLE agent_turn
    ALTER COLUMN request_id TYPE varchar(36) COLLATE "C" USING request_id::text,
    ALTER COLUMN conversation_id TYPE varchar(36) COLLATE "C" USING conversation_id::text,
    ALTER COLUMN execution_id TYPE varchar(36) COLLATE "C" USING execution_id::text,
    ADD CONSTRAINT agent_turn_conversation_id_fkey
        FOREIGN KEY (conversation_id) REFERENCES agent_conversation (id);
