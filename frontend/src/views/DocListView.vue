<template>
  <div class="page">
    <div class="bar">
      <el-button text @click="$router.push('/')">← 返回</el-button>
      <h2>{{ kbName }}</h2>
      <el-tag v-if="kb" size="small">{{ visLabel }}</el-tag>
      <span style="flex:1"></span>
      <el-button size="small" :disabled="reindexing" :loading="reindexing" @click="doReindex">
        {{ reindexing ? '重建中 ' + reTask : '重建索引' }}
      </el-button>
    </div>

    <el-upload drag :auto-upload="false" :show-file-list="false" accept=".pdf,.docx,.txt,.md,.markdown,.pptx,.xlsx"
               :on-change="onFile" class="up">
      <el-icon size="40"><UploadFilled /></el-icon>
      <div>拖拽或点击上传 <b>PDF / Word(docx) / PPT / Excel / TXT / Markdown</b>（≤20MB）</div>
    </el-upload>

    <el-table :data="docs" style="margin-top: 18px" v-loading="loading">
      <el-table-column prop="filename" label="文件名" min-width="200" />
      <el-table-column prop="fileType" label="类型" width="80" />
      <el-table-column label="大小" width="90">
        <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="200">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          <span v-if="row.status === 'EMBEDDING' && row.progress" class="prog">
            {{ row.progress.done }}/{{ row.progress.total }}
          </span>
          <el-tooltip v-if="row.status === 'FAILED'" :content="row.errorMsg || '未知原因'">
            <span class="err">原因</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切片" width="70" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button v-if="row.status === 'FAILED'" size="small" @click="retry(row.id)">重试</el-button>
          <el-button size="small" plain @click="openChunks(row)">切片</el-button>
          <el-popconfirm title="删除文档及其向量？" @confirm="del(row.id)">
            <template #reference><el-button size="small" type="danger" text>删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    <ChunkDrawer v-model="chunkShow" :doc-id="chunkDoc?.id" :doc-name="chunkDoc?.filename" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { UploadFilled } from "@element-plus/icons-vue";
import { http } from "../api/http";
import ChunkDrawer from "../components/ChunkDrawer.vue";

const route = useRoute();
const kbId = route.params.id as string;

interface DocRow { id: number; filename: string; fileType: string; fileSize: number;
  status: string; errorMsg?: string; chunkCount: number; progress?: { done: number; total: number } }

const docs = ref<DocRow[]>([]);
const kb = ref<any>(null);
const loading = ref(true);
const kbName = computed(() => kb.value?.name || "知识库");
const visLabel = computed(() => {
  const map: Record<string, string> = { PRIVATE: "私有", SHARED: "共享", PUBLIC: "公开" };
  return map[kb.value?.visibility ?? ""] ?? "";
});

let timer: number | undefined;

const fmtSize = (n: number) => n > 1048576 ? (n / 1048576).toFixed(1) + " MB" : (n / 1024).toFixed(0) + " KB";
const statusType = (s: string) =>
  (s === "READY" ? "success" : s === "FAILED" ? "danger" : s === "UPLOADED" ? "info" : "warning") as never;

const chunkShow = ref(false);
const chunkDoc = ref<any>(null);
function openChunks(row: any) { chunkDoc.value = row; chunkShow.value = true; }

const reindexing = ref(false);
const reTask = ref("");
async function doReindex() {
  try {
    await ElMessageBox.confirm(
      "重建索引会清空本库向量并按知识库当前切片参数重新解析、切片、向量化全部文档。修改了切片设置后点这里生效。",
      "重建索引", { confirmButtonText: "开始重建", type: "warning" });
  } catch { return; }
  await http.post(`/kb/${kbId}/reindex`);
  reindexing.value = true;
  pollReindex();
}
function pollReindex() {
  const t = window.setInterval(async () => {
    const s = (await http.get(`/kb/${kbId}/reindex-status`)) as any;
    const task = s.task;
    reTask.value = task ? `${task.unitDone}/${task.unitTotal}` : "";
    if (!task || task.state === "DONE" || String(task.state).startsWith("FAILED")) {
      clearInterval(t);
      reindexing.value = false;
      if (task && String(task.state).startsWith("FAILED")) ElMessage.error("重建失败: " + task.state);
      else if (task) ElMessage.success("重建完成");
      reload();
    }
  }, 3000);
}

async function reload() {
  loading.value = true;
  try {
    docs.value = (await http.get(`/documents?kbId=${kbId}`)) as DocRow[];
    pollBusy();
  } finally { loading.value = false; }
}

/** 有进行中的文档就轮询进度（2s），全部终态自动停。 */
function pollBusy() {
  const busy = docs.value.some(d => !["READY", "FAILED"].includes(d.status));
  if (!busy || timer) return;
  timer = window.setInterval(async () => {
    for (const d of docs.value.filter(x => !["READY", "FAILED"].includes(x.status))) {
      const st = (await http.get(`/documents/${d.id}/status`)) as DocRow & { progress?: never };
      Object.assign(d, st);
    }
    if (!docs.value.some(x => !["READY", "FAILED"].includes(x.status))) {
      clearInterval(timer); timer = undefined;
    }
  }, 2000);
}

async function onFile(uf: { raw?: File }) {
  if (!uf.raw) return;
  const fd = new FormData();
  fd.append("file", uf.raw);
  // M9.3：同名文件已存在→询问"替换旧版"（新版本入库+旧版软删下线）还是照旧并存
  const sameName = docs.value.find(d => d.filename === uf.raw!.name);
  if (sameName) {
    try {
      await ElMessageBox.confirm(
        `已存在同名文件《${sameName.filename}》（v${sameName.id}）。替换将以本次上传为新版本并下线旧版；选"并存"则保留两份。`,
        "同名文件", { confirmButtonText: "替换旧版", cancelButtonText: "并存", distinguishCancelAndClose: true, type: "warning" });
      fd.append("replace", "true");
    } catch (e: any) {
      if (e === "close") return; // 关闭弹窗=放弃上传
    }
  }
  try {
    const created = await http.post(`/kb/${kbId}/documents`, fd, { headers: { "Content-Type": "multipart/form-data" } });
    ElMessage.success(sameName ? "已按新版本替换并处理中" : "已提交处理");
    docs.value.unshift(created as unknown as DocRow);
    if (sameName) docs.value = docs.value.filter(d => d.id !== sameName.id);
    pollBusy();
  } catch { /* toast */ }
}

async function retry(id: number) {
  await http.post(`/documents/${id}/retry`);
  const d = docs.value.find(x => x.id === id);
  if (d) d.status = "PARSING";
  pollBusy();
}

async function del(id: number) {
  await http.delete(`/documents/${id}`);
  docs.value = docs.value.filter(x => x.id !== id);
}

onMounted(async () => {
  const list = (await http.get("/kb")) as any[];
  kb.value = list.find(k => String(k.id) === kbId);
  await reload();
});
onUnmounted(() => { if (timer) clearInterval(timer); });
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; }
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.bar h2 { margin: 0; }
.up :deep(.el-upload-dragger) { padding: 26px; }
.prog { margin-left: 8px; color: #909399; font-size: 12px; }
.err { color: #f56c6c; font-size: 12px; margin-left: 6px; text-decoration: underline; cursor: help; }
</style>
