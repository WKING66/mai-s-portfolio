from alembic import op

revision = "0001"
down_revision = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE chat_preferences (
            singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
            revision bigint NOT NULL DEFAULT 0,
            persona text NOT NULL
        )
    """)
    op.execute(
        "INSERT INTO chat_preferences (persona) VALUES ('Be concise and friendly.')"
    )
    op.execute("""
        CREATE TABLE model_attempt (
            attempt_id uuid PRIMARY KEY,
            kind text NOT NULL CHECK (kind IN ('chat', 'embedding')),
            billing_day date NOT NULL,
            currency text NOT NULL,
            price_version text NOT NULL,
            ceiling numeric NOT NULL CHECK (ceiling > 0),
            actual numeric CHECK (actual BETWEEN 0 AND ceiling),
            bounds_exceeded boolean NOT NULL DEFAULT false,
            input_tokens integer,
            output_tokens integer
        )
    """)
    op.execute("""
        CREATE TABLE execution (
            execution_id uuid PRIMARY KEY,
            request_id uuid NOT NULL UNIQUE,
            input_hash text NOT NULL,
            status text NOT NULL CHECK (
                status IN ('accepted', 'running', 'completed', 'failed', 'interrupted')
            ),
            text text NOT NULL DEFAULT '',
            error_code text,
            created_at timestamptz NOT NULL DEFAULT now(),
            finished_at timestamptz
        )
    """)


def downgrade() -> None:
    op.drop_table("chat_preferences")
    op.drop_table("execution")
    op.drop_table("model_attempt")
