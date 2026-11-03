<template>
  <div class="page" v-if="isAdmin">
    <div class="hd">
      <div>
        <h2>系统管理</h2>
        <span class="sub">全站概览 · 用户治理 · 任务与成本 · 质量回收</span>
      </div>
      <div class="hd-ops">
        <el-button size="small" @click="loadCards">↻ 刷新概览</el-button>
        <el-button size="small" type="primary" plain @click="$router.push('/audit')">审计日志</el-button>
      </div>
    </div>

    <!-- 概览卡 -->
    <el-row :gutter="12" class="cards">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card shadow="hover" class="stat">
          <div class="num">{{ c.value }}</div>
          <div class="lab">{{ c.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header><span class="bt">文档处理状态分布</span></template>
      <el-space wrap size="small">
        <el-tag v-for="s in stats.docsByStatus" :key="s.status" :type="tagType(s.status)" effect="light" round>
          {{ s.status }} · {{ s.n }}
        </el-tag>
        <span v-if="!stats.docsByStatus?.length" class="hint">暂无文档</span>
      </el-space>
    </el-card>

    <!-- 主区：标签页收纳，替代此前五张长卡堆叠（信息密度过高要滚动找） -->
    <el-tabs v-model="tab" class="block" @tab-change="onTab">
      <el-tab-pane name="users" lazy>
        <template #label><span class="tl">用户治理 <em class="n">{{ usersTotal }}</em></span></template>
        <div class="toolbar">
          <el-input v-model="uKw" placeholder="搜用户名 / 邮箱" clearable size="small" style="width: 220px"
                    @keyup.enter="uPage = 1; loadUsers()" @clear="uPage = 1; loadUsers()" />
          <el-button size="small" @click="uPage = 1; loadUsers()">搜索</el-button>
        </div>
        <el-table :data="users" size="small" v-loading="uLoading" empty-text="没有匹配用户">
          <el-table-column prop="id" label="ID" width="64" />
          <el-table-column prop="username" label="用户名" min-width="130" />
          <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
          <el-table-column label="角色" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.role === 'ADMIN' ? 'warning' : 'info'" effect="plain">{{ row.role }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="86">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'" effect="light">
                {{ row.status === 1 ? "正常" : "禁用" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button size="small" :type="row.status === 1 ? 'danger' : 'success'" plain
                         @click="setStatus(row, row.status === 1 ? 0 : 1)">
                {{ row.status === 1 ? "禁用" : "启用" }}
              </el-button>
              <el-button size="small" v-if="row.role === 'USER'" @click="setRole(row, 'ADMIN')">升为管理员</el-button>
              <el-button size="small" v-else-if="row.username !== store.me?.username" @click="setRole(row, 'USER')">降为普通</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pg" layout="total, sizes, prev, pager, next" :total="usersTotal"
                       v-model:current-page="uPage" v-model:page-size="uSize" :page-sizes="[10, 20, 50]"
                       @change="loadUsers" />
      </el-tab-pane>

      <el-tab-pane name="tasks" lazy>
        <template #label><span class="tl">任务历史 <em class="n">{{ tasksTotal }}</em></span></template>
        <div class="toolbar">
          <el-select v-model="tType" size="small" style="width: 140px" clearable placeholder="全部类型" @change="tPage = 1; loadTasks()">
            <el-option label="重建索引" value="reindex" /><el-option label="向量迁移" value="migrate" />
          </el-select>
          <span class="hint">重启不丢（M7.1）：中断任务标 FAILED 可回文档页重发</span>
        </div>
        <el-table :data="tasks" size="small" v-loading="tLoading" empty-text="暂无任务">
          <el-table-column prop="id" label="#" width="56" />
          <el-table-column label="类型" width="96">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.type === 'migrate' ? 'primary' : ''">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="kbName" label="知识库" min-width="150" show-overflow-tooltip />
          <el-table-column prop="target" label="目标" width="160" show-overflow-tooltip />
          <el-table-column label="进度" width="110">
            <template #default="{ row }">
              <el-progress :percentage="pct(row)" :stroke-width="8" :show-text="false" style="width: 60px; display: inline-block" />
              <span class="pr"> {{ row.unitDone }}/{{ row.unitTotal }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="stateType(row.state)" effect="light">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新" width="165">
            <template #default="{ row }">{{ fmt(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination class="pg" layout="total, prev, pager, next" :total="tasksTotal"
                       v-model:current-page="tPage" :page-size="tSize" @change="loadTasks" />
      </el-tab-pane>

      <el-tab-pane name="disliked" lazy>
        <template #label><span class="tl">点踩 badcase <em class="n">{{ disTotal }}</em></span></template>
        <p class="hint">检索质量回收：这些回答被用户点踩，优先据此补库/调参（点击会话 ID 无跳转，人工到对话页核对）。</p>
        <el-table :data="disliked" size="small" v-loading="dLoading" empty-text="暂无点踩，好事">
          <el-table-column prop="conversationId" label="会话" width="80" />
          <el-table-column prop="content" label="答案摘要" min-width="320">
            <template #default="{ row }">{{ clip(row.content, 120) }}</template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination class="pg" layout="total, prev, pager, next" :total="disTotal"
                       v-model:current-page="dPage" :page-size="dSize" @change="loadDisliked" />
      </el-tab-pane>

      <el-tab-pane name="usage" lazy>
        <template #label><span class="tl">AI 用量成本</span></template>
        <div class="toolbar">
          <el-select v-model="usageMonth" size="small" style="width: 130px" @change="loadMonth">
            <el-option v-for="(m, i) in monthOptions" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
          <span v-if="monthStat" class="sumline">
            {{ monthStat.period }}　总 token <b>{{ monthStat.totalTokens }}</b>　估算成本 <b class="cost">¥{{ monthStat.totalCostYuan }}</b>
            <span class="hint">（牌价近似，非账单口径）</span>
          </span>
        </div>
        <el-table :data="monthRows" size="small" v-loading="mLoading" empty-text="该月暂无用量">
          <el-table-column prop="user" label="用户" min-width="140" />
          <el-table-column prop="chatCalls" label="问答次数" width="100" sortable />
          <el-table-column prop="embCalls" label="嵌入批次" width="100" />
          <el-table-column prop="rerankCalls" label="重排次数" width="100" />
          <el-table-column prop="visionCalls" label="OCR页数" width="100" />
          <el-table-column prop="tokens" label="总 token" width="120" sortable />
          <el-table-column label="估算成本(¥)" width="130">
            <template #default="{ row }"><span class="cost">{{ Number(row.costYuan).toFixed(4) }}</span></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
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

const tab = ref("users");
const stats = ref<any>({ docsByStatus: [] });
const cards = computed(() => [
  { label: "用户", value: stats.value.users ?? "-" },
  { label: "知识库", value: stats.value.knowledgeBases ?? "-" },
  { label: "文档", value: stats.value.documents ?? "-" },
  { label: "切片", value: stats.value.chunks ?? "-" },
]);

// ---------- 各 tab 状态（分页参数独立，互不干扰） ----------
const users = ref<any[]>([]); const usersTotal = ref(0);
const uKw = ref(""); const uPage = ref(1); const uSize = ref(20); const uLoading = ref(false);
const tasks = ref<any[]>([]); const tasksTotal = ref(0);
const tType = ref(""); const tPage = ref(1); const tSize = 20; const tLoading = ref(false);
const disliked = ref<any[]>([]); const disTotal = ref(0);
const dPage = ref(1); const dSize = 20; const dLoading = ref(false);
const monthRows = ref<any[]>([]); const monthStat = ref<any>(null); const mLoading = ref(false);
const usageMonth = ref(1);

const monthOptions = computed(() => {
  const out: { label: string; value: number }[] = [];
  const now = new Date();
  for (let i = 0; i < 6; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    out.push({ label: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`, value: i + 1 });
  }
  return out;
});

async function loadCards() {
  stats.value = (await http.get("/admin/stats")) as any;
  // 各 tab 计数徽章首屏就真实（size=1 只取 total；否则懒加载前显示 0 有误导）
  http.get("/admin/tasks", { params: { page: 1, size: 1 } }).then((r: any) => tasksTotal.value = Number(r.total || 0)).catch(() => {});
  http.get("/admin/messages/disliked", { params: { page: 1, size: 1 } }).then((r: any) => disTotal.value = Number(r.total || 0)).catch(() => {});
}
async function loadUsers() {
  uLoading.value = true;
  try {
    const r = (await http.get("/admin/users", { params: { kw: uKw.value || undefined, page: uPage.value, size: uSize.value } })) as any;
    users.value = r.rows; usersTotal.value = Number(r.total);
  } finally { uLoading.value = false; }
}
async function loadTasks() {
  tLoading.value = true;
  try {
    const r = (await http.get("/admin/tasks", { params: { type: tType.value || undefined, page: tPage.value, size: tSize } })) as any;
    tasks.value = r.rows; tasksTotal.value = Number(r.total);
  } finally { tLoading.value = false; }
}
async function loadDisliked() {
  dLoading.value = true;
  try {
    const r = (await http.get("/admin/messages/disliked", { params: { page: dPage.value, size: dSize } })) as any;
    disliked.value = r.rows; disTotal.value = Number(r.total);
  } finally { dLoading.value = false; }
}
async function loadMonth() {
  mLoading.value = true;
  try {
    const mm = (await http.get("/admin/usage-month", { params: { month: usageMonth.value } })) as any;
    monthRows.value = mm.rows || []; monthStat.value = mm;
  } finally { mLoading.value = false; }
}
const loaded = new Set<string>();
function onTab(name: string) {
  if (loaded.has(name)) return;
  loaded.add(name);
  ({ users: loadUsers, tasks: loadTasks, disliked: loadDisliked, usage: loadMonth } as any)[name]?.();
}

function pct(r: any) {
  if (!r.unitTotal) return 0;
  return Math.min(100, Math.round((r.unitDone / r.unitTotal) * 100));
}
const stateType = (s: string) =>
  s?.startsWith("DONE") ? "success" : s?.startsWith("FAILED") ? "danger" : s === "RUNNING" ? "primary" : "info";
const tagType = (s: string) =>
  (s === "READY" ? "success" : s === "FAILED" ? "danger" : "warning") as never;
const clip = (s: string, n: number) => (s || "").slice(0, n) + ((s || "").length > n ? "…" : "");
// 后端 LocalDateTime 序列化为 ISO(2026-11-03T07:04:33)——展示层统一去 T 保秒
const fmt = (s?: string) => (s || "").replace("T", " ").slice(0, 19);

async function setStatus(row: any, s: number) {
  await http.post(`/admin/users/${row.id}/status?status=${s}`);
  ElMessage.success(s === 0 ? "已禁用" : "已启用"); loadUsers();
}
async function setRole(row: any, r: string) {
  await http.post(`/admin/users/${row.id}/role?role=${r}`);
  ElMessage.success("角色已更新"); loadUsers();
}

onMounted(async () => {
  await store.loadMe().catch(() => {});
  if (!isAdmin.value) return;
  await loadCards();
  loaded.add("users"); await loadUsers();
});
</script>

<style scoped>
.page { max-width: 1080px; margin: 0 auto; }
.hd { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 14px; }
.hd h2 { margin: 0; }
.sub { color: var(--muted-foreground, #909399); font-size: 12.5px; }
.hd-ops { display: flex; gap: 8px; }
.cards .stat { text-align: center; }
.num { font-size: 26px; font-weight: 700; color: #4c7dff; }
.lab { color: #909399; font-size: 13px; }
.block { margin-top: 14px; }
.bt { font-weight: 600; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.tl { display: inline-flex; align-items: center; gap: 6px; }
.n { font-style: normal; background: var(--muted, #f0f2f5); color: #606266; border-radius: 8px; padding: 0 7px; font-size: 12px; }
.pg { margin-top: 10px; justify-content: flex-end; }
.hint { color: #909399; font-size: 12px; }
.pr { font-size: 12px; color: #606266; }
.sumline { font-size: 13px; color: #303133; }
.cost { color: #e6a23c; font-weight: 600; }
</style>
