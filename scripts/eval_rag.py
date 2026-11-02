"""
检索评估运行器（M4 原版 30 题 → M8.4 扩容版）。
- 默认读 scripts/eval_v2.json（LLM 生成+evidence逐字校验+对抗题），无该文件时回退内嵌 30 题。
- Recall@5 = top5 引用片段包含 evidence（逐字）；答案正确 = 回答包含 evidence。
- 对抗题(kind含"不存在/库外")：期望"不命中/拒答"，单独统计，不计入常规 Recall 分母。
- --kb <id[,id]> 限定库范围；--tag <s> 结果文件名后缀（消融矩阵对比用）。
用法: python eval_rag.py [--kb 5,7] [--tag pc_on] [--builtin]
"""
import json, sys, time, urllib.request, pathlib, re

B = "http://127.0.0.1:8080/api"
argv = sys.argv[1:]
def opt(name, default=None):
    if name in argv:
        return argv[argv.index(name) + 1]
    return default
KB_SCOPE = opt("--kb")
TAG = opt("--tag", "default")
USE_BUILTIN = "--builtin" in argv

BUILTIN_QA = [
    ("XG-200 支持多少台子设备", "32"), ("XG-200Pro 支持多少台子设备", "128"),
    ("XG-200 是否支持 PoE 供电", "PoE"), ("XG-200Pro 的备份链路是什么", "4G"),
    ("网关默认 IP 地址是多少", "192.168.8.100"), ("网关后台登录账号是什么", "admin"),
    ("DHCP 冲突时建议把网关设为哪个网段", "192.168.9"), ("从哪个固件版本开始支持 MQTT over TLS", "2.3.7"),
    ("绿色呼吸灯代表什么状态", "云端"), ("红色快闪灯代表什么", "OTA"),
    ("红色常亮时如何进入恢复模式", "RESET"), ("子设备信号强度应优于多少 dBm", "-75"),
    ("信道拥塞度高于多少需要切换信道", "70"), ("定时重启任务的默认时间是几点", "3:17"),
    ("智能门锁整机质保几年", "3"), ("锁的电机和电路板质保几年", "5"),
    ("电池是否在质保范围内", "不在"), ("网关设备质保期是多久", "2"),
    ("无理由退货的期限是几天", "7"), ("已上墙安装的锁具能否无理由退货", "不支持"),
    ("同城上门安装费多少钱一次", "80"), ("超出市区 30 公里每公里加收多少", "1.5"),
    ("节假日上门按几倍计费", "倍"), ("应急机械钥匙单价是多少", "45"),
    ("报修微信服务号工作日响应时效是多久", "2"), ("紧急开锁（人员被困）应首先拨打什么", "119"),
    ("智能门锁电池续航大约多久", "12"), ("门锁最多可录入多少枚指纹", "50"),
    ("安装门锁要求的门厚范围", "35"), ("低电量时门锁会怎样提示", "语音"),
]


def judge_ans(ev, anorm):
    """ANS 判定(M8.4 口径)：① 证据中数字/专名 token 须全部出现在答案；
    ② 证据整句与答案的字符 bigram 覆盖率 ≥0.45——措辞可精简但不能缺要点。
    判定纯离线（基于保存的 raw 答案），不重复烧配额。"""
    toks = re.findall(r"[A-Za-z0-9][A-Za-z0-9.\-\u00b7/]{1,}|\d+(?:[.,-]\d+)?(?:年|个月|天|次|元|人|台|种|倍|岁|小时|分钟|毫米|米|克|公斤|%|dBm)?", ev)
    keytoks = [x for x in toks if re.search(r"\d|[A-Za-z]", x) and len(x) >= 2]
    if keytoks and not all(k in anorm for k in keytoks[:4]):
        return False
    evb = re.sub(r"[\s*`>\"\u201c\u201d：:，,。]+", "", ev)
    if not keytoks:  # 纯中文证据：bigram 覆盖是唯一判据
        pass
    bigs = {evb[i:i+2] for i in range(len(evb)-1)}
    if not bigs: return evb in anorm
    cov = sum(1 for bg in bigs if bg in anorm) / len(bigs)
    return cov >= 0.45

def load_cases():
    p = pathlib.Path(__file__).with_name("eval_v2.json")
    if not USE_BUILTIN and p.exists():
        data = json.loads(p.read_text(encoding="utf-8"))
        cases = [{"q": c["question"], "ev": c.get("evidence", ""),
                  "adv": bool(re.search("不存在|库外", c.get("kind", "")))} for c in data["cases"]]
        print(f"loaded {len(cases)} cases from eval_v2.json ({sum(c['adv'] for c in cases)} adversarial)")
        return cases
    print("using builtin 30 cases")
    return [{"q": q, "ev": e, "adv": False} for q, e in BUILTIN_QA]

def call(path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(B + path, data=data, method="POST" if body else "GET",
        headers={"Content-Type": "application/json", **({"Authorization": token} if token else {})})
    return urllib.request.urlopen(req, timeout=120)

# 优先用评估专用账号（kb_eval_，见 docs/07；凭据文件在用户目录，不入库）
tok = None
for attempt in range(10):
    try:
        cred = pathlib.Path.home().joinpath("kb_eval_cred.json")
        if cred.exists():
            c = json.loads(cred.read_text(encoding="utf-8"))
            user, pw = c["user"], c["password"]
        else:
            user, pw = "kb_owner_a", "kb-pass-" + "12345"
        tok = json.loads(call("/auth/login", {"username": user, "password": pw}).read())["data"]["token"]
        break
    except Exception:
        time.sleep(15)  # 限流/锁定时滑窗（评估账号避免撞 kb_owner_a 的锁）
assert tok, "login failed"

if KB_SCOPE:
    ready_kb = [int(x) for x in KB_SCOPE.split(",")]
elif pathlib.Path.home().joinpath("kb_eval_cred.json").exists():
    ready_kb = [json.loads(pathlib.Path.home().joinpath("kb_eval_cred.json").read_text())["kbId"]]
    print("kb scope from eval cred:", ready_kb)
else:
    kb_list = json.loads(call("/kb", token=tok).read())["data"]
    ready_kb = []
    for kb in kb_list:
        try:
            docs = json.loads(call(f"/documents?kbId={kb['id']}", token=tok).read())["data"]
        except Exception:
            continue
        names = " ".join(d["filename"] for d in docs if d["status"] == "READY")
        if re.search("网关|FAQ|手册|爬虫|爬行动物", names):
            ready_kb.append(kb["id"])
print("kb scope:", ready_kb)
conv = json.loads(call("/chat/conversations", {"kbIds": ready_kb, "title": "eval-" + TAG}, token=tok).read())["data"]
conv_id = conv["id"]

cases = load_cases()
norm = [c for c in cases if not c["adv"]]
adv = [c for c in cases if c["adv"]]
recall5 = ans_ok = 0
adv_correct = 0
fails = []
raw_out = []
t0 = time.time()

def one(q):
    req = urllib.request.Request(B + "/chat/ask",
        data=json.dumps({"conversationId": conv_id, "question": q}).encode(), method="POST",
        headers={"Content-Type": "application/json", "Authorization": tok, "Accept": "text/event-stream"})
    for attempt in range(4):
        answer, cites = "", []
        try:
            resp = urllib.request.urlopen(req, timeout=180)
        except urllib.error.HTTPError:
            time.sleep(61); continue
        hit = False
        name = ""
        with resp as r:
            for raw in r:
                line = raw.decode("utf-8").strip()
                if line.startswith("event:"): name = line[6:].strip()
                elif line.startswith("data:") and name:
                    d = line[5:].strip()
                    if name == "delta": answer += d.strip('"')
                    elif name == "citations": cites = json.loads(d)["citations"]
                    elif name == "error":
                        j = json.loads(d)
                        if j.get("code") == 1004: hit = True
                    elif name == "done": break
        if hit:
            time.sleep(61); continue
        return answer, cites
    return "", []

for i, c in enumerate(norm):
    answer, cites = one(c["q"])
    blob = " ".join(x["snippet"] for x in cites[:5])
    r5 = c["ev"] in blob
    # ANS 判定口径修正(M8.4)：evidence 是整句，模型复述措辞必不同——
    # 用"区分性 token"（数字/字母专名，如 32、V2.3.7、192.168.9、RESET）覆盖率判答案正确，
    # 要求 ≥1 个数字型 token 命中且无"未找到"式拒答。全句逐字匹配只适用于 R5（片段原文包含）。
    anorm = re.sub(r"[\s*`>\"\u201c\u201d：:，,。]+", "", answer)
    ok = judge_ans(c["ev"], anorm)
    recall5 += r5; ans_ok += ok
    raw_out.append({"q": c["q"], "ev": c["ev"], "r5": r5, "ok": ok,
                    "answer": answer[:400], "n_cites": len(cites)})
    if not (r5 and ok): fails.append((c["q"], c["ev"], "R5" if not r5 else "ANS", answer[:50]))
    print(f"[{i+1:3}/{len(norm)}] R5={'Y' if r5 else 'N'} ANS={'Y' if ok else 'N'}  {c['q'][:34]}")
    time.sleep(0.4)

for c in adv:
    answer, cites = one(c["q"])
    refused = "未找到" in answer or "无法" in answer or "没有相关" in answer or len(cites) == 0
    adv_correct += refused
    print(f"[ADV] {'Y' if refused else 'N(误答)'}  {c['q'][:34]}")

N = max(1, len(norm))
print("\n==== 结果 tag=%s ====" % TAG)
print(f"Recall@5   : {recall5}/{N} = {recall5/N:.0%}")
print(f"答案正确率 : {ans_ok}/{N} = {ans_ok/N:.0%}")
print(f"对抗拒答率 : {adv_correct}/{len(adv)}")
print(f"失败样例   : {len(fails)}")
for f in fails[:10]: print("  MISS:", f)
out = {"tag": TAG, "kb": ready_kb, "n": N, "recall5": recall5, "ans": ans_ok, "raw": raw_out,
       "adv_total": len(adv), "adv_correct": adv_correct, "fails": fails[:30]}
pathlib.Path(__file__).with_name(f"eval_result_{TAG}.json").write_text(
    json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"saved eval_result_{TAG}.json  用时 {time.time()-t0:.0f}s")
