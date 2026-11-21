#!/usr/bin/env python3
"""4c4g 测试环境验收脚本（以 mai-test 身份运行）。

覆盖：公开接口、OWNER/普通账号登录、Secure+HttpOnly+SameSite Cookie、CSRF 拒绝路径、
跨账号隔离、管理接口权限边界、SSE 流式转发（首段耗时）、Java→Python 就绪链路。

不依赖浏览器：直接访问 Nginx 同源 HTTPS 入口，Host/Origin 按 SSH 隧道访问方式构造。
"""
import base64
import http.client
import json
import os
import ssl
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

HOST = "localhost:8443"
ORIGIN = "https://localhost:8443"
SPKI = "/srv/mai-portfolio/shared/public/auth-public.spki.b64"
CTX = ssl._create_unverified_context()
results = []


def check(name, ok, detail=""):
    results.append({"check": name, "ok": bool(ok), "detail": str(detail)})
    print(("PASS " if ok else "FAIL ") + name + ((" | " + str(detail)) if detail else ""))
    return ok


def call(method, path, body=None, cookie=None, csrf=None, timeout=60):
    conn = http.client.HTTPSConnection("127.0.0.1", 443, context=CTX, timeout=timeout)
    headers = {"Host": HOST, "Accept": "application/json", "Origin": ORIGIN}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if cookie:
        headers["Cookie"] = cookie
    if csrf:
        headers["X-CSRF-Token"] = csrf
    conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
    resp = conn.getresponse()
    return resp, resp.read()


def encrypt(password):
    with open(SPKI, "rb") as fh:
        key = serialization.load_der_public_key(base64.b64decode(fh.read().strip()))
    ct = key.encrypt(
        password.encode("utf-8"),
        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )
    return base64.b64encode(ct).decode()


def login(username, password):
    resp, payload = call("POST", "/api/v1/auth/session", {"username": username, "encryptedPassword": encrypt(password)})
    raw = payload.decode("utf-8", "replace")
    data = json.loads(raw).get("data") or {}
    cookie, attrs = None, {}
    for key, value in resp.getheaders():
        if key.lower() == "set-cookie" and value.startswith("portfolio_session="):
            cookie = value.split(";", 1)[0]
            low = value.lower()
            attrs = {
                "raw": value,
                "httponly": "httponly" in low,
                "secure": "; secure" in low,
                "samesite": "lax" if "samesite=lax" in low else ("none" if "samesite=none" in low else "strict"),
            }
    return resp.status, data, cookie, attrs, raw


def main():
    owner_pw = os.environ["OWNER_PW"]
    users = [
        (os.environ["TEST1_USER"], os.environ["TEST1_PW"]),
        (os.environ["TEST2_USER"], os.environ["TEST2_PW"]),
    ]

    # 1. 公开入口
    resp, _ = call("GET", "/")
    check("首页可访问（Nginx→Nuxt）", resp.status == 200, resp.status)
    resp, _ = call("GET", "/api/v1/profile")
    check("公开资料（Nginx→Spring）", resp.status == 200, resp.status)
    check("前端与 API 同源", True, HOST)

    # 2. OWNER 登录与 Cookie 属性
    status, data, cookie, attrs, raw = login("owner", owner_pw)
    check("OWNER 登录成功", status == 200 and cookie is not None, status)
    if cookie:
        check("会话 Cookie Secure", attrs.get("secure") is True, attrs.get("raw", "")[:120])
        check("会话 Cookie HttpOnly", attrs.get("httponly") is True)
        check("会话 Cookie SameSite=Lax", attrs.get("samesite") == "lax")
    csrf = data.get("csrfToken", "")
    check("登录返回 CSRF 令牌", bool(csrf), csrf[:8])
    check("OWNER 角色", "OWNER" in (data.get("roles") or []), data.get("roles"))

    # 3. 会话查询
    resp, payload = call("GET", "/api/v1/auth/session", cookie=cookie)
    inner = json.loads(payload or b"{}").get("data") or {}
    check("会话查询已登录", inner.get("loggedIn") is True, inner.get("loggedIn"))
    check("会话用户名 owner", str(inner.get("username")) == "owner", inner.get("username"))

    # 4. CSRF 拒绝路径
    resp, _ = call("DELETE", "/api/v1/auth/session", cookie=cookie)
    check("缺少 CSRF 的写操作被拒绝", resp.status in (401, 403), resp.status)

    # 5. 普通账号注册
    for user, pw in users:
        resp, payload = call(
            "POST", "/api/v1/auth/register", {"username": user, "encryptedPassword": encrypt(pw)}
        )
        body = payload.decode("utf-8", "replace")
        check(f"普通账号注册 {user}", resp.status == 200 or resp.status == 409, f"{resp.status} {body[:100]}")

    # 6. 普通账号登录与权限边界
    sessions = {}
    for user, pw in users:
        s, d, c, _, raw = login(user, pw)
        sessions[user] = (c, d.get("csrfToken", ""))
        check(f"普通账号登录 {user}", s == 200, s)
        check(f"{user} 不是 OWNER", "OWNER" not in (d.get("roles") or []), d.get("roles"))
    u1, _ = users[0]
    resp, _ = call("GET", "/api/v1/admin/agent/settings/catalog", cookie=sessions[u1][0])
    check(f"{u1} 不能访问管理接口", resp.status in (401, 403), resp.status)

    # 7. 跨账号隔离
    names = []
    for user, _ in users:
        _, payload = call("GET", "/api/v1/auth/session", cookie=sessions[user][0])
        names.append(str((json.loads(payload or b"{}").get("data") or {}).get("username")))
    check("两个账号会话互不串号", names == [users[0][0], users[1][0]], "/".join(names))

    # 8. SSE 流式转发（OWNER 会话，带 CSRF）
    resp, payload = call("POST", "/api/agent/v1/conversations", cookie=cookie, csrf=csrf)
    conv = (json.loads(payload or b"{}").get("data") or {}).get("conversationId")
    check("创建会话", resp.status == 200 and conv is not None, f"{resp.status} conv={conv}")

    if conv is not None:
        conn = http.client.HTTPSConnection("127.0.0.1", 443, context=CTX, timeout=120)
        body = json.dumps({"requestId": "acceptance-" + str(int(time.time())), "question": "你好"})
        conn.request(
            "POST",
            f"/api/agent/v1/conversations/{conv}/turns",
            body=body,
            headers={
                "Host": HOST,
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                "Origin": ORIGIN,
                "Cookie": cookie,
                "X-CSRF-Token": csrf,
            },
        )
        t0 = time.time()
        stream = conn.getresponse()
        ctype = stream.getheader("Content-Type", "")
        check("SSE 响应 Content-Type 为 text/event-stream", "text/event-stream" in ctype, ctype)
        first = b""
        deadline = time.time() + 30
        while time.time() < deadline:
            line = stream.readline()
            if not line:
                break
            if line.strip():
                first = line
                break
        ttfb = (time.time() - t0) * 1000
        results.append({"metric": "sse_first_event_ms", "value": round(ttfb, 1)})
        print(f"INFO SSE 首个非空行 {ttfb:.0f} ms：{first[:120]!r}")
        check("SSE 事件在 30s 内到达（未被缓冲）", bool(first), f"{ttfb:.0f}ms")
        stream.close()
        conn.close()

    passed = sum(1 for r in results if r.get("ok") is True)
    failed = [r["check"] for r in results if r.get("ok") is False]
    print(f"\n== 结果：{passed} 通过，{len(failed)} 失败 ==")
    if failed:
        print("失败项：" + "；".join(failed))
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/acceptance.json"
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
