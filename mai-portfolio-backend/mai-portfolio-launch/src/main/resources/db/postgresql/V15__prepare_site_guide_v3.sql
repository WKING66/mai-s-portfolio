-- 扩展 v3 需要的字段。旧服务在正式切换前仍可读写原列。
ALTER TABLE site_config
    ADD COLUMN agent_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN global_window_hours integer NOT NULL DEFAULT 1 CHECK (global_window_hours > 0),
    ADD COLUMN global_request_limit integer NOT NULL DEFAULT 60 CHECK (global_request_limit > 0),
    ADD COLUMN visitor_daily_turn_limit integer NOT NULL DEFAULT 10 CHECK (visitor_daily_turn_limit > 0),
    ADD COLUMN account_daily_turn_limit integer NOT NULL DEFAULT 30 CHECK (account_daily_turn_limit > 0);

CREATE UNIQUE INDEX site_config_single_row_idx ON site_config ((true));

COMMENT ON TABLE site_config IS '本站唯一的站点配置，保存数字人开关和轮次限额';
COMMENT ON COLUMN site_config.id IS '本站配置自增编号';
COMMENT ON COLUMN site_config.agent_enabled IS '是否开放站长数字人对话';
COMMENT ON COLUMN site_config.global_window_hours IS '全站轮次限额使用的滚动窗口小时数';
COMMENT ON COLUMN site_config.global_request_limit IS '全站滚动窗口内最多受理的问答轮次';
COMMENT ON COLUMN site_config.visitor_daily_turn_limit IS '单个匿名访客滚动 24 小时最多受理的问答轮次';
COMMENT ON COLUMN site_config.account_daily_turn_limit IS '单个登录账号滚动 24 小时最多受理的问答轮次';
COMMENT ON COLUMN site_config.updated_at IS 'Spring 最近保存本站配置的时间';

ALTER TABLE agent_conversation
    ADD COLUMN title varchar(25),
    ADD COLUMN created_at timestamptz;

-- 旧记录以首轮时间和首问回填。没有轮次的会话沿用原活动时间。
WITH conversation_history AS (
    SELECT conversation.id,
           COALESCE(first_turn.created_at, conversation.updated_at) AS created_at,
           NULLIF(left(btrim(first_turn.question), 25), '') AS first_title
    FROM agent_conversation conversation
    LEFT JOIN LATERAL (
        SELECT turn.created_at, turn.question
        FROM agent_turn turn
        WHERE turn.conversation_id = conversation.id
        ORDER BY turn.ordinal
        LIMIT 1
    ) first_turn ON true
)
UPDATE agent_conversation conversation
SET created_at = history.created_at,
    title = COALESCE(history.first_title,
           '新会话 ' || to_char(history.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS'))
FROM conversation_history history
WHERE conversation.id = history.id;

COMMENT ON TABLE agent_conversation IS '网站会话，保存会话归属、标题和活动时间';
COMMENT ON COLUMN agent_conversation.owner_key IS '由 Spring 核验的登录账号或匿名访客归属键';
COMMENT ON COLUMN agent_conversation.anonymous IS '是否仍归属未登录的匿名访客';
COMMENT ON COLUMN agent_conversation.title IS '会话标题，新会话使用创建时间，首问可生成标题';
COMMENT ON COLUMN agent_conversation.created_at IS '会话创建时间，旧版存量在切换时按首轮时间回填';
COMMENT ON COLUMN agent_conversation.updated_at IS '最近活动时间，用于会话列表排序和游标分页';

ALTER TABLE agent_turn
    ADD COLUMN error_mes text,
    ADD COLUMN input_tokens bigint CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens bigint CHECK (output_tokens >= 0),
    ADD COLUMN usage_status smallint NOT NULL DEFAULT 0 CHECK (usage_status IN (0, 1, 2, 3)),
    ADD CONSTRAINT agent_turn_usage_values_check CHECK (
        (usage_status IN (0, 3) AND input_tokens IS NULL AND output_tokens IS NULL)
        OR (usage_status = 1 AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL)
        OR (usage_status = 2 AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL))
    );

COMMENT ON TABLE agent_turn IS '网站持久化的正式问答轮次、执行状态及 Token 汇总';
COMMENT ON COLUMN agent_turn.request_id IS '浏览器生成的提问幂等键，与 Python 的原始执行请求关联';
COMMENT ON COLUMN agent_turn.question IS '用户提交的问题正文';
COMMENT ON COLUMN agent_turn.answer IS '网站正式保存的回答正文，尚无输出时为空字符串';
COMMENT ON COLUMN agent_turn.error_mes IS '面向用户的脱敏错误信息，无错误时为空';
COMMENT ON COLUMN agent_turn.input_tokens IS '本轮已知的模型输入 Token 总数，未知为空';
COMMENT ON COLUMN agent_turn.output_tokens IS '本轮已知的模型输出 Token 总数，未知为空';
COMMENT ON COLUMN agent_turn.usage_status IS 'Token 记录状态：0 待汇总，1 完整，2 部分，3 不可得';
COMMENT ON COLUMN agent_turn.accept_before IS 'Python 首次受理同一请求的截止时间';
COMMENT ON COLUMN agent_turn.quota_at IS '轮次计入站点额度的受理时间，历史导入记录为空';
COMMENT ON COLUMN agent_turn.created_at IS '网站首次登记该轮请求的时间';
