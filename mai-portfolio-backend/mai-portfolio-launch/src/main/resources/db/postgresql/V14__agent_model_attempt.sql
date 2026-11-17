CREATE TABLE agent_model_attempt (
    id varchar(36) PRIMARY KEY,
    billing_day date NOT NULL,
    currency varchar(3) NOT NULL,
    price_version text NOT NULL,
    input_price numeric NOT NULL CHECK (input_price > 0),
    output_price numeric NOT NULL CHECK (output_price >= 0),
    input_limit integer NOT NULL CHECK (input_limit > 0),
    output_limit integer NOT NULL CHECK (output_limit >= 0),
    ceiling numeric NOT NULL CHECK (ceiling > 0),
    actual numeric CHECK (actual BETWEEN 0 AND ceiling),
    bounds_exceeded boolean NOT NULL DEFAULT false
);

CREATE TABLE agent_model_attempt_cancelled (
    attempt_id varchar(36) PRIMARY KEY
);

COMMENT ON TABLE agent_model_attempt_cancelled IS '确认未调用供应商的尝试，防止迟到的请求重新预占';
COMMENT ON COLUMN agent_model_attempt_cancelled.attempt_id IS '已撤销的供应商调用尝试标识';

CREATE INDEX agent_model_attempt_day_idx ON agent_model_attempt (billing_day);

CREATE INDEX agent_model_attempt_pending_idx ON agent_model_attempt (id) WHERE actual IS NULL;

COMMENT ON TABLE agent_model_attempt IS 'Java 模型费用账本，金额预占成功后才允许供应商调用';
COMMENT ON COLUMN agent_model_attempt.id IS '一次供应商调用的唯一尝试标识';
COMMENT ON COLUMN agent_model_attempt.billing_day IS '按亚洲上海时区计算的预占日期';
COMMENT ON COLUMN agent_model_attempt.currency IS '供应商报价和站点预算使用的共同币种';
COMMENT ON COLUMN agent_model_attempt.price_version IS '发起调用时的模型报价版本';
COMMENT ON COLUMN agent_model_attempt.input_price IS '每百万输入 Token 的单价';
COMMENT ON COLUMN agent_model_attempt.output_price IS '每百万输出 Token 的单价';
COMMENT ON COLUMN agent_model_attempt.input_limit IS '该次预占允许的输入 Token 上界';
COMMENT ON COLUMN agent_model_attempt.output_limit IS '该次预占允许的输出 Token 上界';
COMMENT ON COLUMN agent_model_attempt.ceiling IS '模型调用前预占的最大费用';
COMMENT ON COLUMN agent_model_attempt.actual IS '供应商可信用量结算后的费用，未知时为空';
COMMENT ON COLUMN agent_model_attempt.bounds_exceeded IS '供应商回报超过预占上界，暂停后续收费调用';
