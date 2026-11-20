import sqlalchemy as sa
from alembic import op

revision = "0009"
down_revision = "0008"

OLD_DEFAULT = "回答简洁、友好。"
NEW_DEFAULT = "表达自然、热情、接地气，可以适度幽默。技术问题先给判断，再说依据和取舍，结尾随内容自然结束。"
OLD_DESCRIPTION = "回答前加载的站长数字人基础身份和表达风格"
NEW_DESCRIPTION = "调整站长数字人的口吻、幽默程度和技术表达方式"


def replace_seed(previous: str, current: str, description_from: str, description_to: str) -> None:
    connection = op.get_bind()
    # 只更新尚未发布过后台版本的初始默认值，包括已切回旧版的配置在内的自定义内容都保留。
    connection.execute(
        sa.text("""
            UPDATE prompt_version AS version
            SET content = :current
            FROM prompt_config AS config
            WHERE config.prompt_key = 'persona'
              AND config.active_version_id = version.id
              AND config.id = version.prompt_config_id
              AND version.version_no = 1
              AND version.content = :previous
              AND NOT EXISTS (
                  SELECT 1 FROM prompt_version AS newer
                  WHERE newer.prompt_config_id = config.id AND newer.version_no > 1
              )
        """),
        {"previous": previous, "current": current},
    )
    connection.execute(
        sa.text("""
            UPDATE prompt_config
            SET description = :description_to
            WHERE prompt_key = 'persona' AND description = :description_from
        """),
        {"description_from": description_from, "description_to": description_to},
    )


def upgrade() -> None:
    replace_seed(OLD_DEFAULT, NEW_DEFAULT, OLD_DESCRIPTION, NEW_DESCRIPTION)


def downgrade() -> None:
    replace_seed(NEW_DEFAULT, OLD_DEFAULT, NEW_DESCRIPTION, OLD_DESCRIPTION)
