<template>
  <div class="page" v-if="isAdmin">
    <h2>系统管理</h2>

    <el-row :gutter="12">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card shadow="never"><div class="num">{{ c.value }}</div><div class="lab">{{ c.label }}</div></el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header>文档处理状态分布</template>
      <el-space wrap>
        <el-tag v-for="s in stats.docsByStatus" :key="s.status" :type="tagType(s.status)">
          {{ s.status }}: {{ s.n }}
        </el-tag>
      </el-space>
    </el-card>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header>用户治理
        <el-button size="small" style="float: right" @click="loadAll">刷新</el-button>
      </template>
      <el-table :data="users" size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="email" label="邮箱" min-width="160" />
        <el-table-column prop="role" label="角色" width="90" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? "正常" : "禁用" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" @click="setStatus(row, row.status === 1 ? 0 : 1)">
              {{ row.status === 1 ? "禁用" : "启用" }}
            </el-button>
            <el-button size="small" v-if="row.role === 'USER'" @click="setRole(row, 'ADMIN')">升为管理员</el-button>
            <el-button size="small" v-else @click="setRole(row, 'USER')">降为普通</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header>任务历史（重建索引/向量迁移，最近50条 · 重启不丢 M7.1）</template>
      <el-table :data="tasks" size="small" empty-text="暂无任务">
        <el-table-column prop="id" label="#" width="50" />
        <el-table-column prop="type" label="类型" width="80" />
        <el-table-column prop="kbName" label="知识库" min-width="140" />
        <el-table-column prop="target" label="目标" width="150" />
        <el-table-column label="进度" width="90">
          <template #default="{ row }">{{ row.unitDone }}/{{ row.unitTotal }}</template>
        </el-table-column>
        <el-table-column prop="state" label="状态" min-width="140" />
        <el-table-column prop="updatedAt" label="更新" width="160" />
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header>点踩答案（检索质量 badcase，可据此补库）</template>
      <el-table :data="disliked" size="small" empty-text="暂无点踩">
        <el-table-column prop="conversationId" label="会话" width="70" />
        <el-table-column prop="content" label="答案摘要" min-width="260">
          <template #default="{ row }">{{ (row.content || '').slice(0, 80) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="160" />
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header>
        本月 AI 用量与估算成本（token 落库口径 M7.2，单价为牌价近似）
        <el-button size="small" style="float: right" @click="loadAll">刷新</el-button>
      </template>
      <div v-if="monthStat" class="hint" style="margin-bottom: 8px">
        {{ monthStat.period }}　总 token {{ monthStat.totalTokens }}　估算成本 ¥{{ monthStat.totalCostYuan }}
      </div>
      <el-table :data="monthRows" size="small" empty-text="本月暂无用量">
        <el-table-column prop="user" label="用户" min-width="120" />
        <el-table-column prop="chatCalls" label="问答次数" width="90" />
        <el-table-column prop="embCalls" label="嵌入批次" width="90" />
        <el-table-column prop="rerankCalls" label="重排次数" width="90" />
        <el-table-column prop="visionCalls" label="OCR页数" width="90" />
        <el-table-column prop="tokens" label="总 token" width="110" />
        <el-table-column label="估算成本(¥)" width="110">
          <template #default="{ row }">{{ Number(row.costYuan).toFixed(4) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
  <el-empty v-else description="仅管理员可见" />
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { http } from "../api/http";
import { useUserStore } from "../stores/user";

const store = useUserStore();
const isAdmin = computed(() => store.me?.role === "ADMIN");
const stats = ref<any>({ docsByStatus: [] });
const users = ref<any[]>([]);
const usageRows = ref<{ user: string; asks: number }[]>([]);
const monthRows = ref<any[]>([]);
const monthStat = ref<any>(null);
const disliked = ref<any[]>([]);
const tasks = ref<any[]>([]);

const cards = computed(() => [
  { label: "用户", value: stats.value.users ?? "-" },
  { label: "知识库", value: stats.value.knowledgeBases ?? "-" },
  { label: "文档", value: stats.value.documents ?? "-" },
  { label: "切片", value: stats.value.chunks ?? "-" },
]);

const tagType = (s: string) =>
  (s === "READY" ? "success" : s === "FAILED" ? "danger" : "warning") as never;

async function loadAll() {
  if (!isAdmin.value) return;
  stats.value = (await http.get("/admin/stats")) as any;
  users.value = (await http.get("/admin/users")) as any[];
  const usage = (await http.get("/admin/usage")) as Record<string, string>;
  usageRows.value = Object.entries(usage || {}).map(([k, v]) => ({
    user: userName((k.match(/u(\d+):/) || [])[1]), asks: Number(v),
  }));
  disliked.value = (await http.get("/admin/messages/disliked")) as any[];
  tasks.value = (await http.get("/admin/tasks")) as any[];
  const mm = (await http.get("/admin/usage-month")) as any;
  monthRows.value = mm.rows || []; monthStat.value = mm;
}
function userName(id: string) {
  const u = users.value.find(x => String(x.id) === id);
  return u ? u.username : "用户" + id;
}

async function setStatus(row: any, s: number) {
  await http.post(`/admin/users/${row.id}/status?status=${s}`);
  ElMessage.success("已更新"); loadAll();
}
async function setRole(row: any, r: string) {
  await http.post(`/admin/users/${row.id}/role?role=${r}`);
  ElMessage.success("已更新"); loadAll();
}

onMounted(async () => { await store.loadMe().catch(() => {}); loadAll(); });
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; }
.num { font-size: 26px; font-weight: 700; color: #4c7dff; }
.lab { color: #909399; font-size: 13px; }
</style>
