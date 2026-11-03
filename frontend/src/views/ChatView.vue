<template>
  <div class="chat">
    <!-- 左：会话列表 -->
    <aside class="side">
      <el-button type="primary" style="width: 100%" @click="openNew">＋ 新对话</el-button>
      <div class="convlist">
        <div v-for="c in convs" :key="c.id" class="conv" :class="{ on: c.id === current }"
             @click="select(c.id)">
          <span class="ttl">{{ c.pinned ? "📌 " : "" }}{{ c.title }}</span>
          <el-dropdown size="small" trigger="click">
            <span class="more">⋯</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="pin(c, !c.pinned)">{{ c.pinned ? "取消置顶" : "置顶" }}</el-dropdown-item>
                <el-dropdown-item @click="rename(c)">重命名</el-dropdown-item>
                <el-dropdown-item divided @click="del(c)">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </aside>

    <!-- 右：消息流 -->
    <section class="main">
      <div v-if="current" class="searchbar">
        <el-input v-model="searchKw" size="small" placeholder="在本会话中搜索…" clearable style="width: 220px"
                  @keyup.enter="doSearch" @clear="searchHits = null">
          <template #append><el-button size="small" @click="doSearch">搜索</el-button></template>
        </el-input>
        <div v-if="searchHits" class="hits">
          <span v-if="!searchHits.length" class="hint">无命中</span>
          <div v-for="h in searchHits" :key="h.id" class="hit" @click="jumpTo(h.id)">
            <b>{{ h.role === 'user' ? '问' : '答' }}</b> {{ h.snippet }}
          </div>
        </div>
      </div>
      <div class="msgs" ref="box">
        <el-empty v-if="!current" description="选择或新建一个对话" />
        <template v-for="(m, i) in messages" :key="i">
          <div class="msg" :class="m.role" :id="m.id ? 'msg-' + m.id : undefined">
            <div class="bubble">
              <div class="md" v-if="m.role === 'assistant'" v-html="renderMd(m.content)"></div>
              <div class="plain" v-else>{{ m.content }}</div>
              <div class="qfoot" v-if="m.role === 'assistant'">
                <span v-if="m.elapsedMs" class="ftime">{{ (m.elapsedMs / 1000).toFixed(1) }}s</span>
                <span v-if="m.model" class="ftime">{{ m.model }}</span>
                <span class="fb" :class="{ on: m.feedback === 1 }" @click="feedback(m, m.feedback === 1 ? 0 : 1)">👍</span>
                <span class="fb" :class="{ bad: m.feedback === -1 }" @click="feedback(m, m.feedback === -1 ? 0 : -1)">👎</span>
                <el-tag v-if="m.lowConf" size="small" type="warning" effect="plain">相关度偏低，答案仅供参考</el-tag>
                <el-button v-if="m === lastAnswer" size="small" text type="primary" :disabled="asking" @click="regenerate(m)">重新生成</el-button>
              </div>
              <div v-if="m.role === 'assistant' && m.citationList?.length" class="cites">
                <el-divider content-position="left">引用来源</el-divider>
                <el-card v-for="ct in m.citationList" :key="ct.no" class="cite" shadow="never">
                  <div class="citehd">
                    <span class="cite-link" @click="openPreview(ct)">[{{ ct.no }}] {{ ct.docName }}</span>
                    <span v-if="ct.pageNo" class="ftime"> · 第{{ ct.pageNo }}页</span>
                    · 相关度 {{ ct.score }}
                  </div>
                  <div class="citebody">{{ ct.snippet }}</div>
                </el-card>
              </div>
            </div>
          </div>
        </template>
        <div v-if="asking" class="msg assistant">
          <div class="bubble"><span class="typing">正在检索知识库并生成…</span></div>
        </div>
      </div>

      <footer class="input">
        <div class="toolbar" v-if="current">
          <el-button size="small" text @click="doExport">导出 Markdown</el-button>
          <el-button size="small" text @click="doShare">分享链接</el-button>
        </div>
        <div class="row">
          <el-input v-model="q" type="textarea" :rows="2" resize="none"
                    placeholder="基于知识库提问，Enter 发送，Shift+Enter 换行"
                    @keydown.enter.exact.prevent="send" />
          <el-button type="primary" :disabled="!q.trim() || asking || !current" @click="send">发送</el-button>
        </div>
      </footer>
    </section>

    <!-- 新建会话 -->
    <el-dialog v-model="newShow" title="新建对话" width="420px">
      <el-form label-width="80px">
        <el-form-item label="知识库">
          <el-select v-model="pickKbs" multiple style="width: 100%" placeholder="可多选联合检索">
            <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="newTitle" placeholder="留空自动生成" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newShow = false">取消</el-button>
        <el-button type="primary" :disabled="!pickKbs.length" @click="createConv">开始对话</el-button>
      </template>
    </el-dialog>

    <!-- 引用原文预览（M6：同文档切片按序展示，命中块高亮） -->
    <el-drawer v-model="pvShow" :title="'原文预览 · ' + (pvDoc?.filename || '')" size="46%">
      <div class="pvlist">
        <div v-for="ck in pvChunks" :key="ck.id" class="pvchunk" :class="{ hit: ck.id === pvHit }" :ref="el => setRef(ck.id, el)">
          <div class="pvmeta">切片 #{{ ck.seqNo }}<span v-if="ck.pageNo"> · 第{{ ck.pageNo }}页</span><span v-if="ck.titlePath"> · {{ ck.titlePath }}</span></div>
          <div class="pvtext">{{ ck.content }}</div>
        </div>
      </div>
    </el-drawer>

    <!-- 分享 -->
    <el-dialog v-model="shShow" title="分享此会话" width="460px">
      <p class="shtip">生成只读链接：对方可查看本对话历史，<b>不能提问</b>、看不到你的其它会话与知识库。</p>
      <el-input v-if="shUrl" :model-value="shUrl" readonly>
        <template #append><el-button @click="copyShare">复制</el-button></template>
      </el-input>
      <div v-if="shareList.length" style="margin-top: 10px">
        <div v-for="s in shareList" :key="s.token" class="shrow">
          <span class="mono">{{ origin + '/share/' + s.token }}</span>
          <el-button size="small" type="danger" text @click="revokeShare(s.token)">撤销</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, computed } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { http } from "../api/http";
import { useUserStore } from "../stores/user";
import { renderMd } from "../utils/md";

interface Cite { no: number; chunkId: number; docId: number; docName: string; snippet: string; score: number | string; pageNo?: number }
interface Msg { lowConf?: boolean;
   id?: number; role: string; content: string; citeChunkIds?: string; citationList?: Cite[];
  elapsedMs?: number; model?: string; feedback?: number; createdAt?: string }

const store = useUserStore();
const convs = ref<any[]>([]);
const kbs = ref<any[]>([]);
const current = ref<number | null>(null);
const messages = ref<Msg[]>([]);
const q = ref("");
const searchKw = ref("");
const searchHits = ref<any[] | null>(null);
async function doSearch() {
  if (!current.value || !searchKw.value.trim()) return;
  searchHits.value = (await http.get(`/chat/conversations/${current.value}/search`,
    { params: { q: searchKw.value.trim() } })) as any[];
}
function jumpTo(msgId: number) {
  const el = document.getElementById("msg-" + msgId);
  if (el) { el.scrollIntoView({ behavior: "smooth", block: "center" }); el.classList.add("flash"); setTimeout(() => el.classList.remove("flash"), 1500); }
}
const asking = ref(false);
const box = ref<HTMLElement>();

const newShow = ref(false);
const pickKbs = ref<number[]>([]);
const newTitle = ref("");

const pvShow = ref(false);
const pvDoc = ref<any>(null);
const pvChunks = ref<any[]>([]);
const pvHit = ref<number | null>(null);
const pvRefs: Record<number, HTMLElement> = {};

const shShow = ref(false);
const shUrl = ref("");
const shareList = ref<any[]>([]);
const origin = window.location.origin;
const lastAnswer = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--)
    if (messages.value[i].role === "assistant") return messages.value[i];
  return null;
});
async function regenerate(m: any) {
  if (!current.value || asking.value) return;
  try {
    // 服务端删除尾部 assistant+user 两行并回传问题原文；本地同删后重发
    const r = (await http.post(`/chat/conversations/${current.value}/regenerate`)) as any;
    const idx = messages.value.indexOf(m);
    if (idx > 0 && messages.value[idx - 1].role === "user") messages.value.splice(idx - 1, 2);
    else messages.value.splice(idx, 1);
    await send(r.question);
  } catch { /* toast */ }
}

async function reloadConvs() {
  convs.value = (await http.get("/chat/conversations")) as any[];
}

function openNew() {
  pickKbs.value = kbs.value.length === 1 ? [kbs.value[0].id] : [];
  newTitle.value = "";
  newShow.value = true;
}

async function createConv() {
  const c = (await http.post("/chat/conversations", { kbIds: pickKbs.value, title: newTitle.value })) as any;
  newShow.value = false;
  await reloadConvs();
  current.value = c.id;
  messages.value = [];
}

async function select(id: number) {
  current.value = id;
  const hs = (await http.get(`/chat/conversations/${id}/messages`)) as Msg[];
  messages.value = hs.map(h => ({ ...h }));
  for (const m of messages.value) {
    if (m.role === "assistant" && m.citeChunkIds) m.citationList = await fetchCites(m.citeChunkIds);
  }
  scroll();
}

async function fetchCites(idsCsv: string): Promise<Cite[]> {
  try {
    const ids = idsCsv.split(",").map(Number);
    const arr = (await http.post("/chat/citations", { chunkIds: ids })) as unknown as any[];
    return arr.map((c, i) => ({ no: i + 1, chunkId: c.chunkId, docId: c.docId,
      docName: c.docName, snippet: c.snippet, score: "-", pageNo: c.pageNo }));
  } catch { return []; }
}

async function pin(c: any, v: boolean) {
  await http.patch(`/chat/conversations/${c.id}/pin?pinned=${v}`);
  await reloadConvs();
}
async function rename(c: any) {
  const { value } = await ElMessageBox.prompt("新标题", "重命名", { inputValue: c.title });
  if (value) { await http.put(`/chat/conversations/${c.id}`, { title: value }); await reloadConvs(); }
}
async function del(c: any) {
  await http.delete(`/chat/conversations/${c.id}`);
  if (current.value === c.id) { current.value = null; messages.value = []; }
  await reloadConvs();
}

/** 赞/踩：POST 后本地更新；历史回放依赖后端落库（messages.feedback 列）。 */
async function feedback(m: Msg, v: number) {
  if (!m.id) { ElMessage.info("本条为刚生成的流式回答，刷新页面后即可评价"); return; }
  await http.post(`/messages/${m.id}/feedback`, { value: v });
  m.feedback = v;
}

/** 原生 fetch 读 SSE（axios 不便处理流）。 */
async function send(preset?: string) {
  const text = (preset ?? q.value).trim();
  if (!text || !current.value || asking.value) return;
  asking.value = true;
  q.value = "";
  messages.value.push({ role: "user", content: text });
  const holder: Msg = { role: "assistant", content: "", citationList: [] };
  messages.value.push(holder);
  scroll();
  try {
    const resp = await fetch("/api/chat/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: store.token },
      body: JSON.stringify({ conversationId: current.value, question: text }),
    });
    if (!resp.ok || !resp.body) throw new Error("HTTP " + resp.status);
    const reader = resp.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    let evName = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, idx).trim(); buf = buf.slice(idx + 1);
        if (line.startsWith("event:")) evName = line.slice(6).trim();
        else if (line.startsWith("data:")) {
          const payload = line.slice(5).trim();
          if (evName === "delta") holder.content += payload.startsWith('"') ? JSON.parse(payload) : payload;
          else if (evName === "citations") holder.citationList = JSON.parse(payload).citations || [];
          else if (evName === "error") {
            const j = JSON.parse(payload);
            holder.content = holder.content || "⚠ " + j.message;
            ElMessage.error(j.message);
          } else if (evName === "done") {
            // done 事件回 elapsedMs + 置信度（M10）；消息 id 由刷新历史获得（评价功能对当轮新消息提示刷新）
            const j = JSON.parse(payload);
            holder.elapsedMs = j.elapsedMs;
            holder.lowConf = !!j.lowConfidence;
          }
        }
      }
      scroll();
    }
  } catch (e) {
    holder.content = holder.content || "⚠ 请求失败，请稍后重试";
  } finally {
    asking.value = false;
    scroll();
  }
}

// ---------- M6：引用预览 ----------
function setRef(id: number, el: any) { if (el) pvRefs[id] = el as HTMLElement; }
async function openPreview(ct: Cite) {
  pvHit.value = ct.chunkId;
  pvDoc.value = { filename: ct.docName };
  pvChunks.value = [];
  pvShow.value = true;
  try {
    // 以命中 chunk 为中心取同文档相邻切片窗口（前后各 2）
    const win = (await http.post("/chat/citations/window", { chunkId: ct.chunkId, around: 2 })) as unknown as any[];
    pvChunks.value = win;
    await nextTick();
    pvRefs[ct.chunkId]?.scrollIntoView({ behavior: "smooth", block: "center" });
  } catch { /* 拦截器已提示 */ }
}

// ---------- M6：导出 / 分享 ----------
function doExport() {
  const lines: string[] = ["# " + (convs.value.find(c => c.id === current.value)?.title || "对话"), ""];
  for (const m of messages.value) {
    lines.push(m.role === "user" ? `**问：** ${m.content}` : `**答：** ${m.content}`);
    if (m.citationList?.length) {
      lines.push("", "> 引用：" + m.citationList.map(c => `[${c.no}] ${c.docName}`).join("、"));
    }
    lines.push("");
  }
  const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `对话导出-${Date.now()}.md`;
  a.click();
  URL.revokeObjectURL(a.href);
}

async function doShare() {
  const r = (await http.post(`/chat/conversations/${current.value}/share`)) as any;
  shUrl.value = origin + r.url;
  shareList.value = (await http.get(`/chat/conversations/${current.value}/shares`)) as any[];
  shShow.value = true;
}
async function revokeShare(token: string) {
  await http.delete(`/chat/conversations/${current.value}/share/${token}`);
  shareList.value = (await http.get(`/chat/conversations/${current.value}/shares`)) as any[];
  ElMessage.success("已撤销");
}
function copyShare() {
  navigator.clipboard.writeText(shUrl.value).then(() => ElMessage.success("已复制"));
}

function scroll() {
  nextTick(() => { if (box.value) box.value.scrollTop = box.value.scrollHeight; });
}

onMounted(async () => {
  kbs.value = (await http.get("/kb")) as any[];
  await reloadConvs();
  if (convs.value.length) select(convs.value[0].id);
});
onUnmounted(() => {});
</script>

<style scoped>
.chat { display: flex; height: calc(100vh - 92px); max-width: 1200px; margin: 0 auto; gap: 12px; }
.side { width: 230px; display: flex; flex-direction: column; }
.convlist { margin-top: 10px; overflow-y: auto; flex: 1; }
.conv { padding: 9px 10px; border-radius: 8px; cursor: pointer; display: flex; justify-content: space-between; align-items: center; font-size: 14px; }
.conv:hover { background: #ecf0f8; }
.conv.on { background: #dbe7ff; }
.ttl { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.more { color: #909399; padding: 0 4px; }
.main { flex: 1; display: flex; flex-direction: column; background: #fff; border-radius: 10px; overflow: hidden; }
.msgs { flex: 1; overflow-y: auto; padding: 18px; }
.msg { display: flex; margin-bottom: 14px; }
.msg.user { justify-content: flex-end; }
.msg .bubble { max-width: 76%; padding: 10px 14px; border-radius: 10px; background: #f0f2f5; font-size: 14px; line-height: 1.65; }
.msg.user .bubble { background: #4c7dff; color: #fff; }
.plain { white-space: pre-wrap; }
.searchbar { padding: 6px 14px 0; position: relative; }
.hits { position: absolute; top: 34px; left: 14px; right: 14px; max-height: 240px; overflow: auto; background: var(--card, #fff); border: 1px solid var(--border, #eee); border-radius: 8px; z-index: 5; box-shadow: 0 4px 16px rgba(0,0,0,.08); }
.hit { padding: 7px 12px; font-size: 12.5px; cursor: pointer; border-bottom: 1px dashed var(--border, #f0f0f0); }
.hit:hover { background: var(--muted, #f7f7f7); }
.flash { outline: 2px solid var(--accent, #409eff); outline-offset: 2px; border-radius: 8px; }
.qfoot { margin-top: 6px; display: flex; gap: 8px; align-items: center; }
.ftime { color: #b0b3b8; font-size: 12px; }
.fb { cursor: pointer; opacity: .55; font-size: 14px; user-select: none; }
.fb:hover { opacity: 1; }
.fb.on, .fb.bad { opacity: 1; }
.cites { margin-top: 10px; }
.cite { margin-bottom: 8px; background: #fafbfd; }
.citehd { font-size: 12px; color: #4c7dff; margin-bottom: 4px; }
.cite-link { cursor: pointer; }
.cite-link:hover { text-decoration: underline; }
.citebody { font-size: 12.5px; color: #606266; }
.typing { color: #909399; }
.input { padding: 6px 12px 12px; border-top: 1px solid #eee; }
.toolbar { display: flex; gap: 4px; margin-bottom: 4px; }
.row { display: flex; gap: 10px; }
.pvlist { display: flex; flex-direction: column; gap: 10px; }
.pvchunk { padding: 10px 12px; border-radius: 8px; background: #f7f8fa; font-size: 13px; }
.pvchunk.hit { background: #fff7d6; outline: 2px solid #e6a23c; }
.pvmeta { color: #909399; font-size: 12px; margin-bottom: 4px; }
.pvtext { white-space: pre-wrap; }
.shtip { font-size: 13px; color: #606266; margin: 0 0 12px; }
.shrow { display: flex; justify-content: space-between; align-items: center; font-size: 12.5px; padding: 4px 0; }
.mono { font-family: Consolas, monospace; word-break: break-all; }
</style>
