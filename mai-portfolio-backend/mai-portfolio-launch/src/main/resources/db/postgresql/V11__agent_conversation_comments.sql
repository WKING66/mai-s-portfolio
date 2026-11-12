COMMENT ON TABLE agent_conversation IS '网站会话主表，统一保存登录账号和匿名访客的会话。';
COMMENT ON COLUMN agent_conversation.id IS '网站会话唯一标识。';
COMMENT ON COLUMN agent_conversation.owner_key IS '会话归属键，登录账号使用 account:账号标识，匿名访客使用 visitor:访客标识。';
COMMENT ON COLUMN agent_conversation.anonymous IS '是否为匿名会话，同一匿名访客只允许有一个会话。';
COMMENT ON COLUMN agent_conversation.import_id IS '历史会话导入标识，同一所有者重复提交时复用原会话，普通会话为空。';
COMMENT ON COLUMN agent_conversation.updated_at IS '会话最近活动时间，用于会话列表倒序排序和游标分页。';

COMMENT ON TABLE agent_turn IS '网站问答轮次，保存问题、正式回答及与 Python 执行的关联。';
COMMENT ON COLUMN agent_turn.request_id IS '问答请求唯一标识，用于重试去重，并关联 Python 执行请求。';
COMMENT ON COLUMN agent_turn.conversation_id IS '所属网站会话标识。';
COMMENT ON COLUMN agent_turn.execution_id IS 'Python 执行标识，尚未绑定执行或由历史导入的轮次为空。';
COMMENT ON COLUMN agent_turn.question IS '用户提交或从历史导入的问题正文。';
COMMENT ON COLUMN agent_turn.answer IS '网站保存的正式回答正文，未写入正文时为空字符串。';
COMMENT ON COLUMN agent_turn.status IS '网站问答状态，0 待受理，1 已受理，2 当前未使用，3 已完成，4 失败，5 已中断。';
COMMENT ON COLUMN agent_turn.error_code IS '轮次失败或中断时保存的错误码，无错误时为空。';
COMMENT ON COLUMN agent_turn.accept_before IS 'Python 允许受理该请求的截止时间，用于拒绝过期的未绑定请求。';
COMMENT ON COLUMN agent_turn.ordinal IS '数据库生成的轮次顺序号，用于按写入顺序读取同一会话的问答。';
COMMENT ON COLUMN agent_turn.created_at IS '网站问答轮次的创建时间。';
