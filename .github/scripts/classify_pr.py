"""判断 PR 是否需要进入各应用的验证任务。"""
from __future__ import annotations

import io
import re
import subprocess
import sys
import tokenize
from pathlib import PurePosixPath


ROOTS = {
    "mai-portfolio-backend": "java",
    "mai-portfolio-ai-server": "python",
    "mai-portfolio-frontend": "frontend",
}
C_LIKE = re.compile(
    r'(?P<comment>//[^\r\n]*|/\*.*?\*/)'
    r'|(?P<literal>"""(?:\\.|(?!""").)*"""|"(?:\\.|[^"\\\r\n])*"|'
    r"'(?:\\.|[^'\\\r\n])*')"
    r"""|(?P<ambiguous>["']|/\*|/)""",
    re.DOTALL,
)
DIRECTIVE = re.compile(r"(?:type:\s*ignore|noqa\b|ruff:|pyright:|ts-(?:ignore|nocheck|expect-error))")


def python_code(text: str) -> tuple[tuple[int, str], ...] | None:
    try:
        tokens = tokenize.generate_tokens(io.StringIO(text).readline)
        return tuple(
            (token.type, token.string)
            for token in tokens
            if token.type != tokenize.NL
            and (
                token.type != tokenize.COMMENT
                or token.string.startswith("#!")
                or DIRECTIVE.search(token.string)
                or (token.start[0] <= 2 and re.search(r"coding[:=]", token.string))
            )
        )
    except (tokenize.TokenError, IndentationError):
        return None


def c_like_code(text: str, *, java: bool) -> tuple[str, tuple[str, ...]] | None:
    # Java 的 Unicode 转义在词法分析之前处理。JS 模板与正则语义不明时交给应用检查。
    if "\0" in text or (java and r"\u" in text) or (not java and chr(96) in text):
        return None
    parts: list[str] = []
    literals: list[str] = []
    position = 0
    for match in C_LIKE.finditer(text):
        parts.append(text[position : match.start()])
        position = match.end()
        if match.lastgroup == "comment":
            comment = match.group()
            parts.append(comment if DIRECTIVE.search(comment) else " " + "\n" * comment.count("\n"))
        elif match.lastgroup == "literal":
            literals.append(match.group())
            parts.append("\0")
        elif java and match.group() == "/":
            parts.append("/")
        else:
            return None
    parts.append(text[position:])
    code = "\n".join(line.rstrip() for line in "".join(parts).split("\n") if line.strip())
    return code, tuple(literals)


def v0(path: str, before: str | None, after: str | None) -> bool:
    file = PurePosixPath(path)
    if file.suffix.lower() in {".md", ".rst", ".adoc"} or file.name.upper() == "README":
        return True
    if file.name == "package-info.java" and (before is None or after is None):
        remaining = c_like_code(before or after or "", java=True)
        return remaining is not None and bool(
            re.fullmatch(r"\s*package\s+[\w.]+\s*;\s*", remaining[0])
        ) and not remaining[1]
    if before is None or after is None or before == after:
        return False
    if file.suffix == ".py":
        left, right = python_code(before), python_code(after)
    elif file.suffix in {".java", ".js", ".ts", ".mjs", ".cjs"}:
        java = file.suffix == ".java"
        left, right = c_like_code(before, java=java), c_like_code(after, java=java)
    else:
        return False
    return left is not None and left == right


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args])


def content(ref: str, path: str) -> str | None:
    result = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True)
    if result.returncode:
        return None
    try:
        return result.stdout.decode("utf-8")
    except UnicodeDecodeError:
        return None


def classify(base: str, head: str) -> set[str]:
    changes = git("diff", "--name-status", "--no-renames", "-z", base, head).split(b"\0")
    active: set[str] = set()
    for status, raw_path in zip(changes[0::2], changes[1::2]):
        path = raw_path.decode("utf-8")
        application = ROOTS.get(path.split("/", 1)[0])
        if application is None or application in active:
            continue
        before = None if status == b"A" else content(base, path)
        after = None if status == b"D" else content(head, path)
        # 类型变化与无法读取的源码交给应用检查。
        if status not in {b"A", b"M", b"D"} or not v0(path, before, after):
            active.add(application)
    return active


if __name__ == "__main__":
    active_apps = classify(sys.argv[1], sys.argv[2])
    for name in ROOTS.values():
        print(f"{name}={str(name in active_apps).lower()}")
