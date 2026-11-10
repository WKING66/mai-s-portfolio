import argparse
import os
from pathlib import Path
from secrets import token_urlsafe

from cryptography.fernet import Fernet


def default_secret_path() -> Path:
    return Path(__file__).resolve().parents[1] / ".env"


def generate_secret_file(path: Path) -> Path:
    """生成数字人启动凭证；已存在的密钥文件由原部署继续使用。"""
    path = path.expanduser().absolute()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    server_token = token_urlsafe(48)
    encryption_key = Fernet.generate_key().decode()
    content = (
        "# 填写独立 AI 数据库连接串后再启动服务。\n"
        "# DATABASE_URL=postgresql://<账号>:<密码>@127.0.0.1:5433/mai_portfolio_ai\n"
        "DATABASE_URL=\n"
        f"AI_SERVER_TOKEN={server_token}\n"
        f"CONFIG_ENCRYPTION_KEY={encryption_key}\n"
    )
    # 独占创建防止覆盖旧主密钥，否则数据库已有的供应商密钥无法解密。
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as file:
        file.write(content)
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description="生成站长数字人启动密钥文件")
    parser.add_argument("--output", type=Path, default=default_secret_path())
    arguments = parser.parse_args()
    try:
        path = generate_secret_file(arguments.output)
    except FileExistsError:
        parser.exit(1, "密钥文件已存在，请继续使用原文件。\n")
    print(path)


if __name__ == "__main__":
    main()
