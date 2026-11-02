"""LLM-as-judge 离线判分（M8.4 答案层口径终解）。
读 scripts/eval_result_<tag>.json 的 raw[{q,ev,answer}]，每 15 题一批交 qwen-plus 判
"答案是否基于证据给出了正确回答（允许措辞不同/省略无关部分）"，写回 judged 字段。
只判已存答案，不重新问答——不重复烧对话配额；程序化 R5（证据逐字在引用片段）保留为检索层指标。
用法: python judge_eval.py baseline3
"""
import json, urllib.request, pathlib, re, sys, time

envtxt = pathlib.Path(r"D:\项目\ai-knowledge-base\backend\.env").read_text(encoding="utf-8")
KEY = re.search(r"AI_API_KEY=(\S+)", envtxt).group(1)
URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

def judge_batch(items):
    lines = []
    for i, it in enumerate(items):
        lines.append(f"[{i}] 问题：{it['q']}\n证据：{it['ev']}\n答案：{it['answer'][:200]}")
    prompt = ("判断每题答案是否基于证据给出了正确回答（允许措辞不同、可省略与问题无关的部分；"
              "但关键数值/专名/否定判断缺失或错误即为 false）。只输出 JSON 数组，"
              "形如 [true,false,...]，长度等于题数。\n\n" + "\n\n".join(lines))
    body = json.dumps({"model": "qwen-plus", "messages": [{"role": "user", "content": prompt}],
                       "temperature": 0}).encode()
    req = urllib.request.Request(URL, data=body, method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    # 强制直连：本机 FlClash 系统代理对 Python urllib 不稳定(TLS reset)，
    # 而 DashScope 直连实测可达（curl --noproxy 已验）。ProxyHandler({}) 绕过继承的系统代理。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for i in range(6):
        try:
            with opener.open(req, timeout=120) as r:
                out = json.loads(r.read())["choices"][0]["message"]["content"]
            m = re.search(r"\[[^\[\]]*\]", out, re.S)
            arr = json.loads(m.group(0))
            if len(arr) == len(items): return arr
        except Exception as e:
            print("  retry", e.__class__.__name__); time.sleep(15 + i * 10)
    return [None] * len(items)

tag = sys.argv[1] if len(sys.argv) > 1 else "baseline3"
f = pathlib.Path(__file__).with_name(f"eval_result_{tag}.json")
d = json.loads(f.read_text(encoding="utf-8"))
rows = d["raw"]
verdicts = []
for i in range(0, len(rows), 15):
    batch = rows[i:i+15]
    v = judge_batch(batch)
    verdicts += v
    okc = sum(1 for x in v if x is True)
    print(f"batch {i//15+1}: {okc}/{len(v)} correct")
    time.sleep(2)
for r, v in zip(rows, verdicts):
    r["judged"] = v
d["ans_llm"] = sum(1 for v in verdicts if v is True)
d["ans_llm_note"] = "LLM-as-judge(qwen-plus,t=0) 离线判分；None=判分失败按程序判定回退"
for r, v in zip(rows, verdicts):
    if v is None: r["judged"] = r.get("ok2", r["ok"])
d["ans_llm"] = sum(1 for r in rows if r["judged"])
f.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
print(f"JUDGED {tag}: LLM答案正确 {d['ans_llm']}/{len(rows)}  Recall@5(程序) {d['recall5']}/{d['n']}")
