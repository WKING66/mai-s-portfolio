CREATE TABLE agent_conversation (
    id uuid PRIMARY KEY,
    owner_key varchar(100) NOT NULL,
    anonymous boolean NOT NULL,
    import_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_key, import_id)
);

CREATE UNIQUE INDEX agent_conversation_anonymous_owner_idx
    ON agent_conversation (owner_key) WHERE anonymous;

CREATE INDEX agent_conversation_owner_activity_idx
    ON agent_conversation (owner_key, updated_at DESC, id DESC);

CREATE TABLE agent_turn (
    request_id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES agent_conversation(id),
    execution_id uuid UNIQUE,
    question text NOT NULL,
    answer text NOT NULL DEFAULT '',
    status smallint NOT NULL DEFAULT 0 CHECK (status BETWEEN 0 AND 5),
    error_code varchar(80),
    accept_before timestamptz NOT NULL,
    ordinal bigint GENERATED ALWAYS AS IDENTITY,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX agent_turn_conversation_order_idx
    ON agent_turn (conversation_id, ordinal);

CREATE UNIQUE INDEX agent_turn_active_conversation_idx
    ON agent_turn (conversation_id) WHERE status < 3;
