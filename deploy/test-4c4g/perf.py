#!/usr/bin/env python3
"""4c4g 测试环境性能测量（以 mai-test 身份运行）。

用法：python3 perf.py <输出json>
产出：空闲基线、页面/API 延迟分位、SSE 首段耗时、采样到的 CPU/内存/swap。
聊天并发需要真实模型凭据；未配置时该场景只测到"受理+流式建立"为止，报告中标注待验收。
"""
import base64
import http.client
import json
import os
import ssl
import statistics
import sys
import threading
import time

HOST = "localhost:8443"
ORIGIN = "https://localhost:8443"
SPKI = "/srv/mai-portfolio/shared/public/auth-public.spki.b64"
CTX = ssl._create_unverified_context()


def sample_system(seconds, out, stop):
    """按 1s 采样 /proc/stat、/proc/meminfo。"""
    def read_cpu():
        with open("/proc/stat") as fh:
            parts = fh.readline().split()[1:]
        vals = [int(x) for x in parts]
        idle = vals[3] + vals[4]
        return sum(vals), idle

    def read_mem():
        info = {}
        with open("/proc/meminfo") as fh:
            for line in fh:
                k, v = line.split(":", 1)
                info[k] = int(v.strip().split()[0])
        return info

    t0, i0 = read_cpu()
    start = time.time()
    while not stop.is_set() and time.time() - start < seconds:
        time.sleep(1)
        t1, i1 = read_cpu()
        total = t1 - t0
        idle = i1 - i0
        cpu = 100.0 * (total - idle) / total if total else 0.0
        mem = read_mem()
        swap_total = mem.get("SwapTotal", 0) // 1024
        swap_free = mem.get("SwapFree", 0) // 1024
        out.append(
            {
                "t": round(time.time() - start, 1),
                "cpu": round(cpu, 1),
                "memUsedMB": (mem["MemTotal"] - mem["MemAvailable"]) // 1024,
                "memTotalMB": mem["MemTotal"] // 1024,
                "swapUsedMB": swap_total - swap_free,
            }
        )
        t0, i0 = t1, i1


def summarize(samples):
    if not samples:
        return {}
    cpu = [s["cpu"] for s in samples]
    mem = [s["memUsedMB"] for s in samples]
    swap = [s["swapUsedMB"] for s in samples]
    return {
        "cpuAvg": round(statistics.mean(cpu), 1),
        "cpuMax": round(max(cpu), 1),
        "memAvgMB": round(statistics.mean(mem)),
        "memMaxMB": max(mem),
        "swapMaxMB": max(swap),
        "samples": len(samples),
    }


def call(method, path, body=None, cookie=None, csrf=None, timeout=30):
    conn = http.client.HTTPSConnection("127.0.0.1", 443, context=CTX, timeout=timeout)
    headers = {"Host": HOST, "Accept": "application/json", "Origin": ORIGIN}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if cookie:
        headers["Cookie"] = cookie
    if csrf:
        headers["X-CSRF-Token"] = csrf
    t0 = time.perf_counter()
    conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
    resp = conn.getresponse()
    payload = resp.read()
    dt = (time.perf_counter() - t0) * 1000
    conn.close()
    return resp.status, payload, dt


def pct(values, p):
    if not values:
        return None
    values = sorted(values)
    k = max(0, min(len(values) - 1, int(round((p / 100.0) * (len(values) - 1)))))
    return round(values[k], 1)


def load(path, total, concurrency, cookie=None):
    lat, codes = [], []
    lock = threading.Lock()

    def worker(n):
        local_lat, local_codes = [], []
        for _ in range(n):
            try:
                status, _, dt = call("GET", path, cookie=cookie)
            except Exception:
                status, dt = 0, 0.0
            local_lat.append(dt)
            local_codes.append(status)
        with lock:
            lat.extend(local_lat)
            codes.extend(local_codes)

    per = total // concurrency
    threads = [threading.Thread(target=worker, args=(per,)) for _ in range(concurrency)]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - t0
    return {
        "path": path,
        "requests": len(lat),
        "concurrency": concurrency,
        "rps": round(len(lat) / elapsed, 2) if elapsed else None,
        "p50ms": pct(lat, 50),
        "p95ms": pct(lat, 95),
        "p99ms": pct(lat, 99),
        "maxMs": round(max(lat), 1) if lat else None,
        "codes": {str(c): codes.count(c) for c in sorted(set(codes))},
        "5xx": sum(1 for c in codes if 500 <= c < 600),
    }


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/perf.json"
    report = {"measuredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}

    # 1) 空闲基线 30s
    idle, stop = [], threading.Event()
    th = threading.Thread(target=sample_system, args=(30, idle, stop))
    th.start()
    th.join()
    report["idle"] = summarize(idle)

    # 2) 登录一次（避免触发 5 次/300 秒限流）
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    with open(SPKI, "rb") as fh:
        key = serialization.load_der_public_key(base64.b64decode(fh.read().strip()))
    ct = key.encrypt(
        os.environ["OWNER_PW"].encode(),
        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )
    conn = http.client.HTTPSConnection("127.0.0.1", 443, context=CTX, timeout=30)
    conn.request(
        "POST",
        "/api/v1/auth/session",
        body=json.dumps({"username": "owner", "encryptedPassword": base64.b64encode(ct).decode()}),
        headers={"Host": HOST, "Content-Type": "application/json", "Origin": ORIGIN},
    )
    resp = conn.getresponse()
    body = resp.read()
    cookie = None
    for k, v in resp.getheaders():
        if k.lower() == "set-cookie" and v.startswith("portfolio_session="):
            cookie = v.split(";", 1)[0]
    data = (json.loads(body).get("data") or {}) if body else {}
    report["loginStatus"] = resp.status

    # 3) 页面与 API 压测（会话已建立后不再登录）
    report["page"] = load("/", 120, 4)
    report["api"] = load("/api/v1/profile", 200, 4, cookie=cookie)

    # 4) 负载中的资源采样 + SSE 首段
    busy, stop2 = [], threading.Event()
    th2 = threading.Thread(target=sample_system, args=(40, busy, stop2))
    th2.start()

    csrf = data.get("csrfToken", "")
    sse = {}
    if cookie and csrf:
        try:
            status, payload, _ = call("POST", "/api/agent/v1/conversations", cookie=cookie, csrf=csrf)
            conv = (json.loads(payload or b"{}").get("data") or {}).get("conversationId")
            if conv:
                c = http.client.HTTPSConnection("127.0.0.1", 443, context=CTX, timeout=120)
                t0 = time.perf_counter()
                c.request(
                    "POST",
                    f"/api/agent/v1/conversations/{conv}/turns",
                    body=json.dumps({"requestId": "perf-" + str(int(time.time())), "question": "你好"}),
                    headers={
                        "Host": HOST,
                        "Content-Type": "application/json",
                        "Accept": "text/event-stream",
                        "Origin": ORIGIN,
                        "Cookie": cookie,
                        "X-CSRF-Token": csrf,
                    },
                )
                r = c.getresponse()
                sse["status"] = r.status
                sse["contentType"] = r.getheader("Content-Type")
                first = b""
                while True:
                    line = r.readline()
                    if not line:
                        break
                    if line.strip():
                        first = line
                        break
                sse["firstEventMs"] = round((time.perf_counter() - t0) * 1000, 1)
                sse["firstEvent"] = first[:120].decode("utf-8", "replace")
                r.close()
                c.close()
        except Exception as exc:  # 记录失败原因，不中断其它指标
            sse["error"] = repr(exc)
    report["sse"] = sse

    # 并发负载中的资源采样
    report["page_again"] = load("/", 80, 8, cookie=cookie)
    stop2.set()
    th2.join()
    report["load"] = summarize(busy)

    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
