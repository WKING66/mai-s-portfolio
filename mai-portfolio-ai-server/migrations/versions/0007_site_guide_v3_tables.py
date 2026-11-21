from alembic import op

revision = "0007"
down_revision = "0006"


def upgrade() -> None:
    # 创建 v3 表并迁入模型及 Persona 配置。旧执行与账本在停流量后再迁移。
    op.execute("""
        CREATE TABLE model_provider (
            id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            title          varchar(100) NOT NULL UNIQUE,
            provider_code  varchar(64) NOT NULL CHECK (provider_code ~ '^[a-z][a-z0-9_-]*$'),
            api_protocol   smallint NOT NULL CHECK (api_protocol IN (1)),
            base_url       text NOT NULL,
            api_key        text,
            wallet_key     varchar(80),
            revision       bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
            updated_at     timestamptz NOT NULL DEFAULT now(),
            CHECK (wallet_key IS NULL OR btrim(wallet_key) <> '')
        )
    """)
    op.execute("""
        COMMENT ON TABLE model_provider IS '模型供应商接入配置，一条记录对应后台一张接入卡片'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.id IS '供应商接入配置自增编号'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.title IS '配置中心显示名称，例如百炼按量计费或百炼套餐'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.provider_code IS '实际提供并对该接入计费的平台标识，例如 bailian、deepseek、zhipu。自定义接入填写可区分的平台标识'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.api_protocol IS '接口协议枚举，1 为 OpenAI 兼容协议，使用 LangChain 的 OpenAI 集成'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.base_url IS '接入的 API 基础地址，供目录下的模型共同使用'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.api_key IS '接入凭证密文。管理接口只返回凭证是否已配置'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.wallet_key IS '实际计费钱包的配置别名。同一供应商的接入可共用一个别名，空值时按独立接入处理'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.revision IS '接入配置保存修订号，用于识别过期编辑'
    """)
    op.execute("""
        COMMENT ON COLUMN model_provider.updated_at IS '接入配置最近保存时间，由应用更新'
    """)
    op.execute("""
        CREATE TABLE model_config (
            id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            model_provider_id bigint NOT NULL,
            model_type         smallint NOT NULL CHECK (model_type IN (1, 2)),
            model_key          varchar(200) NOT NULL,
            model_nickname     varchar(100) NOT NULL,
            is_active          boolean NOT NULL DEFAULT false,
            config             jsonb NOT NULL CHECK (jsonb_typeof(config) = 'object'),
            revision           bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
            updated_at         timestamptz NOT NULL DEFAULT now(),
            UNIQUE (model_provider_id, model_type, model_key)
        )
    """)
    op.execute("""
        COMMENT ON TABLE model_config IS '供应商模型目录，一行对应一个模型及其调用配置'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.id IS '模型配置自增编号，供执行链选用'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.model_provider_id IS '模型所属接入编号，指向 model_provider.id'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.model_type IS '模型能力枚举，1 为 Chat，2 为 Embedding'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.model_key IS '发给供应商的模型 ID，区别于后台显示名称'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.model_nickname IS '模型在配置中心里的显示名称'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.is_active IS '模型配置是否已启用。启用后可被选入执行链'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.config IS '按模型能力校验的调用参数。Chat 支持 max_output_tokens，Embedding 支持 dimensions'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.revision IS '模型配置保存修订号，用于识别过期编辑'
    """)
    op.execute("""
        COMMENT ON COLUMN model_config.updated_at IS '模型配置最近保存时间，由应用更新'
    """)
    op.execute("""
        CREATE TABLE model_route (
            id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            type            smallint NOT NULL CHECK (type IN (1, 2)),
            model_config_id bigint NOT NULL,
            position        integer NOT NULL CHECK (position > 0),
            is_removed      boolean NOT NULL DEFAULT false,
            UNIQUE (type, model_config_id)
        )
    """)
    op.execute("""
        CREATE UNIQUE INDEX model_route_position_idx
            ON model_route (type, position)
            WHERE NOT is_removed
    """)
    op.execute("""
        COMMENT ON TABLE model_route IS '业务执行链的候选模型，分别管理成员和顺序'
    """)
    op.execute("""
        COMMENT ON COLUMN model_route.id IS '候选成员自增编号'
    """)
    op.execute("""
        COMMENT ON COLUMN model_route.type IS '执行链用途枚举，1 为 Chat 回复，2 为 Embedding 向量化'
    """)
    op.execute("""
        COMMENT ON COLUMN model_route.model_config_id IS '具体的模型配置编号，指向 model_config.id'
    """)
    op.execute("""
        COMMENT ON COLUMN model_route.position IS '模型在这条链中的顺序，1 为主模型，后续位置依次备用'
    """)
    op.execute("""
        COMMENT ON COLUMN model_route.is_removed IS '是否已从这条链移出，重新加入时复用记录'
    """)
    op.execute("""
        CREATE TABLE prompt_config (
            id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- Prompt 配置编号
            prompt_key        varchar(64) NOT NULL UNIQUE, -- 工作流使用的稳定用途键，例如 persona
            description       text NOT NULL DEFAULT '', -- 说明这段 Prompt 在哪一步使用以及用途
            active_version_id bigint, -- 当前生效的版本编号，首次发布前为空
            updated_at        timestamptz NOT NULL DEFAULT now() -- 配置说明或生效版本最近修改时间
        )
    """)
    op.execute("""
        COMMENT ON TABLE prompt_config IS 'Prompt 用途说明以及当前生效版本'
    """)
    op.execute("""
        CREATE TABLE prompt_version (
            id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- Prompt 版本编号
            prompt_config_id bigint NOT NULL, -- 所属 Prompt 配置编号
            version_no       integer NOT NULL CHECK (version_no > 0), -- 同一 Prompt 从 1 递增的版本号
            content          text NOT NULL CHECK (btrim(content) <> ''), -- 此版本完整正文
            created_at       timestamptz NOT NULL DEFAULT now(), -- 版本创建时间
            UNIQUE (prompt_config_id, version_no)
        )
    """)
    op.execute("""
        COMMENT ON TABLE prompt_version IS '不可覆盖的 Prompt 正文版本，可被重新选为生效版本'
    """)
    op.execute("""
        CREATE TABLE model_execution (
            id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- Python 执行编号
            request_id  uuid NOT NULL UNIQUE, -- 对应 Java 请求的幂等标识
            input_hash  text NOT NULL, -- 检查同一请求是否换了业务输入
            status      smallint NOT NULL DEFAULT 0 CHECK (status IN (0, 1, 2, 3, 4)), -- 0 已受理，1 运行中，2 完成，3 失败，4 中断
            text        text NOT NULL DEFAULT '', -- 用于断线恢复的已生成正文
            error         text, -- 执行错误码
            input_tokens  bigint CHECK (input_tokens >= 0), -- 本轮已知输入 Token 总数
            output_tokens bigint CHECK (output_tokens >= 0), -- 本轮已知输出 Token 总数
            usage_status  smallint NOT NULL DEFAULT 0 CHECK (usage_status IN (0, 1, 2, 3)), -- 用量状态
            created_at  timestamptz NOT NULL DEFAULT now(), -- 执行登记时间
            finished_at timestamptz, -- 执行进入终态时间
            CHECK ((status IN (2, 3, 4)) = (finished_at IS NOT NULL)),
            CHECK (
                (usage_status IN (0, 3) AND input_tokens IS NULL AND output_tokens IS NULL)
                OR (usage_status = 1 AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL)
                OR (usage_status = 2 AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL))
            )
        )
    """)
    op.execute("""
        CREATE INDEX model_execution_pending_cleanup_idx ON model_execution (finished_at, id)
            WHERE finished_at IS NOT NULL AND text <> ''
    """)
    op.execute("""
        COMMENT ON TABLE model_execution IS 'Python 模型执行状态和用于流式恢复的临时正文'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_config.id IS 'Prompt 配置编号'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_config.prompt_key IS '工作流使用的稳定用途键，例如 persona'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_config.description IS '说明这段 Prompt 在哪一步使用以及用途'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_config.active_version_id IS '当前生效的版本编号，首次发布前为空'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_config.updated_at IS '配置说明或生效版本最近修改时间'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_version.id IS 'Prompt 版本编号'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_version.prompt_config_id IS '所属 Prompt 配置编号'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_version.version_no IS '同一 Prompt 从 1 递增的版本号'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_version.content IS '此版本完整正文'
    """)
    op.execute("""
        COMMENT ON COLUMN prompt_version.created_at IS '版本创建时间'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.id IS 'Python 执行编号'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.request_id IS '对应 Java 请求的幂等标识'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.input_hash IS '检查同一请求是否换了业务输入'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.status IS '0 已受理，1 运行中，2 完成，3 失败，4 中断'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.text IS '用于断线恢复的已生成正文'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.error IS '执行错误码'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.input_tokens IS '本轮已知输入 Token 总数，未知为空'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.output_tokens IS '本轮已知输出 Token 总数，未知为空'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.usage_status IS 'Token 记录状态：0 待汇总，1 完整，2 部分，3 不可得'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.created_at IS '执行登记时间'
    """)
    op.execute("""
        COMMENT ON COLUMN model_execution.finished_at IS '执行进入终态时间'
    """)

    # 保留当前 Persona 作为首个已发布版本，旧配置仍可用于回退版本部署。
    op.execute("""
        INSERT INTO prompt_config (prompt_key, description)
        VALUES ('persona', '回答前加载的站长数字人基础身份和表达风格')
    """)
    op.execute("""
        INSERT INTO prompt_version (prompt_config_id, version_no, content)
        SELECT config.id, 1, preferences.persona
        FROM prompt_config config
        CROSS JOIN chat_preferences preferences
        WHERE config.prompt_key = 'persona' AND preferences.singleton
    """)
    op.execute("""
        UPDATE prompt_config config
        SET active_version_id = version.id
        FROM prompt_version version
        WHERE version.prompt_config_id = config.id AND version.version_no = 1
    """)

    # 兼容旧配置中两个端点使用不同密钥的情形，迁移时分别建卡，保持原密文。
    op.execute("""
        WITH legacy_models AS (
            SELECT settings.model_type, settings.title, settings.model_key, settings.base_url,
                   NULLIF(settings.api_key, '') AS api_key, settings.config
            FROM chat_preferences preferences
            CROSS JOIN LATERAL (
                VALUES
                    (1, '原对话接入', preferences.runtime ->> 'chat_model',
                     preferences.runtime ->> 'chat_base_url', preferences.runtime ->> 'chat_api_key',
                     CASE WHEN jsonb_typeof(preferences.runtime -> 'chat_max_output_tokens') = 'number'
                          THEN jsonb_build_object(
                              'max_output_tokens', preferences.runtime -> 'chat_max_output_tokens')
                          ELSE '{}'::jsonb END),
                    (2, '原向量接入', preferences.runtime ->> 'embedding_model',
                     preferences.runtime ->> 'embedding_base_url',
                     preferences.runtime ->> 'embedding_api_key',
                     CASE WHEN jsonb_typeof(preferences.runtime -> 'embedding_dimensions') = 'number'
                                AND (preferences.runtime ->> 'embedding_dimensions')::integer > 0
                          THEN jsonb_build_object(
                              'dimensions', preferences.runtime -> 'embedding_dimensions')
                          ELSE '{}'::jsonb END)
            ) settings (model_type, title, model_key, base_url, api_key, config)
            WHERE preferences.singleton
              AND NULLIF(settings.model_key, '') IS NOT NULL
              AND NULLIF(settings.base_url, '') IS NOT NULL
        ),
        new_providers AS (
            INSERT INTO model_provider (title, provider_code, api_protocol, base_url, api_key)
            SELECT title, left('legacy-' || COALESCE(regexp_replace(
                       lower(substring(base_url FROM '^https://([^/:]+)')),
                       '[^a-z0-9_-]', '-', 'g'), 'endpoint'), 64),
                   1, base_url, api_key
            FROM legacy_models
            RETURNING id, title, api_key
        ),
        new_models AS (
            INSERT INTO model_config
                (model_provider_id, model_type, model_key, model_nickname, is_active, config)
            SELECT provider.id, legacy.model_type, legacy.model_key, left(legacy.model_key, 100),
                   legacy.model_type = 1 AND provider.api_key IS NOT NULL, legacy.config
            FROM legacy_models legacy
            JOIN new_providers provider USING (title)
            RETURNING id, model_type, is_active
        )
        INSERT INTO model_route (type, model_config_id, position)
        SELECT model_type, id, 1
        FROM new_models
        WHERE is_active
    """)


def downgrade() -> None:
    # 新表一旦有数据，必须按单独的切换回退流程恢复两库，避免漏掉新问答。
    op.execute("""
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM model_execution)
                OR EXISTS (SELECT 1 FROM model_provider)
                OR EXISTS (SELECT 1 FROM model_config)
                OR EXISTS (SELECT 1 FROM model_route)
                OR EXISTS (SELECT 1 FROM prompt_config WHERE prompt_key <> 'persona')
                OR EXISTS (SELECT 1 FROM prompt_version WHERE version_no > 1) THEN
                RAISE EXCEPTION 'v3 表已有数据，请按切换回退流程恢复';
            END IF;
        END $$
    """)
    op.drop_table("model_execution")
    op.drop_table("prompt_version")
    op.drop_table("prompt_config")
    op.drop_table("model_route")
    op.drop_table("model_config")
    op.drop_table("model_provider")
