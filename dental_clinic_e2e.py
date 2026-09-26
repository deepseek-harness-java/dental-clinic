#!/usr/bin/env python3
"""dental-clinic E2E：通过业务应用 SSE 代理调用 DSH Agent，验证 6 个工具全链路。"""
import json, subprocess, sys

AGENT = "dental-copilot"
URL = "http://127.0.0.1:18102/api/assistant/stream"

CASES = [
    ("T1 医生排班", "牙科诊所明天哪些医生出诊？周明远和林晓芸还有余号吗？简洁回答", ["周明远", "林晓芸"]),
    ("T2 就诊记录", "查一下牙科诊所患者陈国栋的就诊记录，最近一次看了什么？简洁回答", ["种植牙"]),
    ("T3 挂号预约", "我是测试患者王强，想在牙科诊所挂赵启铭医生补牙，下午的号。请确认后挂号，告诉我取号码", ["赵启铭", "取号"]),
    ("T4 费用估算", "牙科诊所种一颗牙多少钱？医保能报吗？简洁回答", ["8800", "自费"]),
    ("T5 取消挂号", "帮我把牙科诊所测试患者王强刚才挂的号取消掉", ["取消"]),
    ("T6 运营统计", "牙科诊所今天运营情况怎么样？出诊几个医生？简洁回答", ["出诊", "满"]),
]

def ask(message, timeout=170):
    payload = json.dumps({"message": message}, ensure_ascii=False)
    try:
        out = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "-N", "-X", "POST", URL,
             "-H", "Content-Type: application/json", "-d", payload,
             "--max-time", str(timeout)],
            capture_output=True, text=True, timeout=timeout + 10).stdout
    except Exception as e:
        return "", f"curl 异常: {e}"
    text = []
    ev = ""
    for line in out.splitlines():
        line = line.rstrip("\r")
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            s = line[5:].strip()
            if not s or s == "[DONE]" or ev != "chunk":
                continue
            try:
                j = json.loads(s)
                c = j.get("content", "")
                if c:
                    text.append(c)
            except Exception:
                pass
            ev = ""
    return "".join(text), out

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = CASES if not only else [c for c in CASES if c[0].startswith(only)]
    passed, failed = 0, []
    for name, q, keys in cases:
        reply, raw = ask(q)
        ok = all(k in reply for k in keys)
        print(f"[{'PASS' if ok else 'FAIL'}] {name}\n  Q: {q}\n  A: {reply[:200]}")
        if ok:
            passed += 1
        else:
            failed.append(name)
            if not reply:
                print(f"  raw 首行: {raw.splitlines()[:3] if raw else '(空)'}")
    print(f"\n===== dental-clinic E2E: {passed}/{len(cases)} PASS =====")
    if failed:
        print("失败用例:", ", ".join(failed))
        sys.exit(1)

if __name__ == "__main__":
    main()
