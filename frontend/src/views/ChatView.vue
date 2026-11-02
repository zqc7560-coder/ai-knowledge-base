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
      <div class="msgs" ref="box">
        <el-empty v-if="!current" description="选择或新建一个对话" />
        <template v-for="(m, i) in messages" :key="i">
          <div class="msg" :class="m.role">
            <div class="bubble">
              <div class="md" v-if="m.role === 'assistant'" v-html="renderMd(m.content)"></div>
              <div class="plain" v-else>{{ m.content }}</div>
              <div v-if="m.role === 'assistant' && m.citationList?.length" class="cites">
                <el-divider content-position="left">引用来源</el-divider>
                <el-card v-for="ct in m.citationList" :key="ct.no" class="cite" shadow="never">
                  <div class="citehd">[{{ ct.no }}] {{ ct.docName }} · 相关度 {{ ct.score }}</div>
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
        <el-input v-model="q" type="textarea" :rows="2" resize="none"
                  placeholder="基于知识库提问，Enter 发送，Shift+Enter 换行"
                  @keydown.enter.exact.prevent="send" />
        <el-button type="primary" :disabled="!q.trim() || asking || !current" @click="send">发送</el-button>
      </footer>
    </section>

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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { http } from "../api/http";
import { useUserStore } from "../stores/user";
import { renderMd } from "../utils/md";

interface Cite { no: number; chunkId: number; docId: number; docName: string; snippet: string; score: number }
interface Msg { role: string; content: string; citeChunkIds?: string; citationList?: Cite[] }

const store = useUserStore();
const convs = ref<any[]>([]);
const kbs = ref<any[]>([]);
const current = ref<number | null>(null);
const messages = ref<Msg[]>([]);
const q = ref("");
const asking = ref(false);
const box = ref<HTMLElement>();

const newShow = ref(false);
const pickKbs = ref<number[]>([]);
const newTitle = ref("");

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
  // 引用详情：assistant 消息的 citeChunkIds 已在 ask 时通过事件带回，但历史回放需再拉一次
  messages.value = hs.map(h => ({ ...h }));
  for (const m of messages.value) {
    if (m.role === "assistant" && m.citeChunkIds) {
      // 历史里的引用内容不在消息表里，简化处理：按 chunkIds 从后端取原文
      m.citationList = await fetchCites(m.citeChunkIds);
    }
  }
  scroll();
}

async function fetchCites(idsCsv: string): Promise<Cite[]> {
  try {
    const ids = idsCsv.split(",").map(Number);
    return (await http.post("/chat/citations", { chunkIds: ids })) as unknown as Cite[];
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

/** 原生 fetch 读 SSE（axios 不便处理流）。 */
async function send() {
  const text = q.value.trim();
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
          else if (evName === "citations") {
            const j = JSON.parse(payload);
            holder.citationList = j.citations || [];
            // 首字到达前展示引用预览
          } else if (evName === "error") {
            const j = JSON.parse(payload);
            holder.content = holder.content || "⚠ " + j.message;
            ElMessage.error(j.message);
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

function scroll() {
  nextTick(() => { if (box.value) box.value.scrollTop = box.value.scrollHeight; });
}

onMounted(async () => {
  kbs.value = (await http.get("/kb")) as any[];
  await reloadConvs();
  if (convs.value.length) select(convs.value[0].id);
});
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
.cites { margin-top: 10px; }
.cite { margin-bottom: 8px; background: #fafbfd; }
.citehd { font-size: 12px; color: #4c7dff; margin-bottom: 4px; }
.citebody { font-size: 12.5px; color: #606266; }
.typing { color: #909399; }
.input { display: flex; gap: 10px; padding: 12px; border-top: 1px solid #eee; }
</style>
