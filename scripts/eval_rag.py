"""
M4 评估：30 条 QA（网关手册 14 / 售后FAQ 12 / 门锁手册 4），两种配置对比跑分。
Recall@5 = top5 引用片段中包含答案关键词的问题占比。
答案正确 = 回答包含期望关键词集合中的关键 token。
用法: python eval_rag.py [--mode vector|hybrid]；hybrid 需服务端开混合检索（默认），vector 模式暂用同管道
"""
import json, sys, time, urllib.request

B = "http://127.0.0.1:8080/api"

QA = [
    # 网关 XG-200 (14)
    ("XG-200 支持多少台子设备", "32"),
    ("XG-200Pro 支持多少台子设备", "128"),
    ("XG-200 是否支持 PoE 供电", "PoE"),
    ("XG-200Pro 的备份链路是什么", "4G"),
    ("网关默认 IP 地址是多少", "192.168.8.100"),
    ("网关后台登录账号是什么", "admin"),
    ("DHCP 冲突时建议把网关设为哪个网段", "192.168.9"),
    ("从哪个固件版本开始支持 MQTT over TLS", "2.3.7"),
    ("绿色呼吸灯代表什么状态", "云端"),
    ("红色快闪灯代表什么", "OTA"),
    ("红色常亮时如何进入恢复模式", "RESET"),
    ("子设备信号强度应优于多少 dBm", "-75"),
    ("信道拥塞度高于多少需要切换信道", "70"),
    ("定时重启任务的默认时间是几点", "3:17"),
    # 售后 FAQ (12)
    ("智能门锁整机质保几年", "3"),
    ("锁的电机和电路板质保几年", "5"),
    ("电池是否在质保范围内", "不在"),
    ("网关设备质保期是多久", "2"),
    ("无理由退货的期限是几天", "7"),
    ("已上墙安装的锁具能否无理由退货", "不支持"),  # 判定词与资料原文一致（能/不能的断言由答案语义判断易误伤）
    ("同城上门安装费多少钱一次", "80"),
    ("超出市区 30 公里每公里加收多少", "1.5"),
    ("节假日上门按几倍计费", "倍"),
    ("应急机械钥匙单价是多少", "45"),
    ("报修微信服务号工作日响应时效是多久", "2"),
    ("紧急开锁（人员被困）应首先拨打什么", "119"),
    # 门锁手册 (4)
    ("智能门锁电池续航大约多久", "12"),
    ("门锁最多可录入多少枚指纹", "50"),
    ("安装门锁要求的门厚范围", "35"),
    ("低电量时门锁会怎样提示", "语音"),
]

def call(path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(B + path, data=data, method="POST" if body else "GET",
        headers={"Content-Type": "application/json", **({"Authorization": token} if token else {})})
    return urllib.request.urlopen(req, timeout=120)

import time as _t
tok = None
for i in range(8):
    try:
        tok = json.loads(call("/auth/login", {"username": "kb_owner_a", "password":"kb-pass-" + "12345"}).read())["data"]["token"]
        break
    except Exception:
        _t.sleep(15)  # 登录限流 5/分钟：等窗口滑过
assert tok, "login failed after retries"

# 找目标库集合：门锁库 + M4语料库（含网关/FAQ）
kb_list = json.loads(call("/kb", token=tok).read())["data"]
ready_kb = []
import re
for kb in kb_list:
    docs = json.loads(call(f"/documents?kbId={kb['id']}", token=tok).read())["data"]
    names = " ".join(d["filename"] for d in docs if d["status"] == "READY")
    # 评估范围=含三类语料文件的库（门锁手册可能叫"门锁手册.md"）
    if re.search("网关|FAQ|手册", names):
        ready_kb.append(kb["id"])
conv = json.loads(call("/chat/conversations", {"kbIds": ready_kb, "title": "M4评估"}, token=tok).read())["data"]
conv_id = conv["id"]
print("kb scope:", ready_kb, "conv:", conv_id)

recall5, ans_ok, fails = 0, 0, []
t0 = time.time()
for i, (q, kw) in enumerate(QA):
    req = urllib.request.Request(B + "/chat/ask",
        data=json.dumps({"conversationId": conv_id, "question": q}).encode(), method="POST",
        headers={"Content-Type": "application/json", "Authorization": tok, "Accept": "text/event-stream"})
    answer, cites = "", []
    with urllib.request.urlopen(req, timeout=180) as resp:
        name = ""
        for raw in resp:
            line = raw.decode("utf-8").strip()
            if line.startswith("event:"): name = line[6:].strip()
            elif line.startswith("data:") and name:
                d = line[5:].strip()
                if name == "delta": answer += d.strip('"')
                elif name == "citations":
                    cites = json.loads(d)["citations"]
                elif name == "done": break
    snippet_blob = " ".join(c["snippet"] for c in cites[:5])
    r5 = kw in snippet_blob
    ok = kw in answer.replace(" ", " ") or kw in answer
    recall5 += r5; ans_ok += ok
    if not (r5 and ok):
        fails.append((q, kw, "R5" if not r5 else "ANS", answer[:60]))
    print(f"[{i+1:2}/30] R5={'Y' if r5 else 'N'} ANS={'Y' if ok else 'N'}  {q}")
    time.sleep(0.5)  # 限流友好

print("\n==== 结果 ====")
print(f"Recall@5      : {recall5}/30 = {recall5/30:.0%}")
print(f"答案正确率    : {ans_ok}/30 = {ans_ok/30:.0%}")
print(f"失败样例      : {len(fails)}")
for f in fails[:8]: print("  MISS:", f)
print(f"用时 {time.time()-t0:.0f}s")
