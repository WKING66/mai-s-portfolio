from alembic import op

revision = "0003"
down_revision = "0002"


def upgrade() -> None:
    # 旧开关未明确开启的已有配置归零预算，避免移除开关后意外产生费用。
    op.execute("""
        UPDATE chat_preferences
        SET runtime = CASE
                WHEN runtime->'ai_paid_calls_enabled' = 'true'::jsonb
                    THEN runtime - 'ai_paid_calls_enabled'
                ELSE (runtime - 'ai_paid_calls_enabled') || '{"ai_daily_budget": "0"}'::jsonb
            END,
            revision = revision + 1
        WHERE revision > 0 OR runtime ? 'ai_paid_calls_enabled'
    """)


def downgrade() -> None:
    op.execute("""
        UPDATE chat_preferences
        SET runtime = runtime || '{"ai_paid_calls_enabled": false}'::jsonb,
            revision = revision + 1
    """)
