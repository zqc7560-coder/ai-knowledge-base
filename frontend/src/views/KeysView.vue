<template>
  <div class="page">
    <div class="bar">
      <el-button text @click="$router.push('/')">← 返回</el-button>
      <h2>开放 API 密钥</h2>
      <span class="hint">外部程序用密钥直调 <code>POST /api/open/ask</code>，仅能问绑定的库</span>
    </div>

    <el-card shadow="never">
      <div class="grant">
        <el-input v-model="name" placeholder="密钥名称，如：我的机器人" style="width: 220px" maxlength="60" />
        <el-select v-model="kbIds" multiple collapse-tags placeholder="绑定知识库" style="width: 260px">
          <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
        </el-select>
        <el-button type="primary" :disabled="!name || !kbIds.length" :loading="busy" @click="create">创建密钥</el-button>
      </div>
      <div v-if="newKey" class="newkey">
        <div>新密钥（<b>仅此一次显示，请立即复制保存</b>）：</div>
        <code>{{ newKey }}</code>
        <el-button size="small" @click="copy(newKey)">复制</el-button>
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header>我的密钥</template>
      <el-table :data="list" size="small" empty-text="还没有密钥">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="绑定库" min-width="160">
          <template #default="{ row }">{{ scopeNames(row.kbScope) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="row.revoked ? 'info' : 'success'" size="small">{{ row.revoked ? '已撤销' : '有效' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="最近使用" width="170">
          <template #default="{ row }">{{ row.lastUsedAt || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="!row.revoked" size="small" type="danger" text @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="usage">
        <div class="hint">调用示例（密钥明文创建后不再可取，忘了就新建一个）：</div>
        <pre>curl -X POST {{ origin }}/api/open/ask \
  -H "Authorization: Bearer ***" \
  -H "Content-Type: application/json" \
  -d '{"kbIds":[库ID],"question":"你的问题"}'</pre>
        <div class="hint">返回 JSON：answer（带 [n] 引用标记）、citations（来源片段）、conversationId（多轮请回传）。</div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { http } from "../api/http";
import { ElMessage } from "element-plus";

const origin = window.location.origin;
const kbs = ref<any[]>([]);
const list = ref<any[]>([]);
const name = ref("");
const kbIds = ref<number[]>([]);
const busy = ref(false);
const newKey = ref("");

const kbName = (id: number) => (kbs.value.find(k => k.id === id) || { name: "#" + id }).name;
const scopeNames = (csv: string) => String(csv).split(",").filter(Boolean).map(s => kbName(Number(s))).join("、");

async function reload() {
  list.value = (await http.get("/keys")) as any[];
}
onMounted(async () => {
  kbs.value = (await http.get("/kb")) as any[];
  await reload();
});
async function create() {
  busy.value = true;
  try {
    const r = (await http.post("/keys", { name: name.value, kbIds: kbIds.value })) as any;
    newKey.value = r.key; name.value = ""; kbIds.value = [];
    await reload();
    ElMessage.success("已创建");
  } finally { busy.value = false; }
}
async function revoke(row: any) {
  await http.delete(`/keys/${row.id}`);
  row.revoked = 1;
}
function copy(t: string) { navigator.clipboard.writeText(t); ElMessage.success("已复制"); }
</script>

<style scoped>
.page { padding: 16px 22px; }
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.grant { display: flex; gap: 8px; flex-wrap: wrap; }
.newkey { margin-top: 12px; padding: 10px; background: var(--muted, #f7f7f7); border-radius: 8px; font-size: 13px; }
.newkey code { display: block; word-break: break-all; margin: 6px 0; color: #b45309; }
.usage { margin-top: 14px; }
.usage pre { background: #0f172a; color: #e2e8f0; padding: 10px 12px; border-radius: 8px; font-size: 12px; overflow: auto; }
.hint { color: var(--muted-foreground, #999); font-size: 12px; }
</style>
