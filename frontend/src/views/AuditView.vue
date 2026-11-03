<template>
  <div class="page">
    <div class="bar">
      <el-button text @click="$router.push('/admin')">← 返回后台</el-button>
      <h2>审计日志</h2>
      <div class="filters">
        <el-select v-model="action" placeholder="动作过滤" clearable size="small" style="width: 170px" @change="page = 1; reload">
          <el-option v-for="a in ACTION_TYPES" :key="a" :label="a" :value="a" />
        </el-select>
        <el-input v-model="kw" placeholder="搜操作者 / 对象 / 详情" clearable size="small" style="width: 200px"
                  @keyup.enter="page = 1; reload" @clear="page = 1; reload" />
        <el-button size="small" @click="page = 1; reload">搜索</el-button>
      </div>
    </div>

    <el-table :data="rows" size="small" v-loading="loading" empty-text="暂无审计记录">
      <el-table-column label="时间" width="168">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作者" width="140">
        <template #default="{ row }">
          <span>{{ row.username || "匿名" }}</span>
          <el-tag v-if="row.userId" size="small" effect="plain" type="info" class="uid">u{{ row.userId }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="动作" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="actType(row.action)" effect="light">{{ row.action }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="target" label="对象" min-width="120" show-overflow-tooltip />
      <el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip />
      <el-table-column prop="ip" label="IP" width="130" show-overflow-tooltip />
    </el-table>
    <el-pagination class="pg" layout="total, sizes, prev, pager, next, jumper" :total="total"
                   v-model:current-page="page" v-model:page-size="size" :page-sizes="[20, 50, 100]"
                   @change="reload" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { http } from "../api/http";

// 与后端埋点对齐的十类动作（docs/07 M11 节）；新加动作时这里同步补一项
const ACTION_TYPES = ["kb.delete", "kb.visibility", "member.grant", "member.revoke",
  "user.disable", "user.enable", "user.role", "share.create", "key.create", "key.revoke", "user.pwdreset"];
const rows = ref<any[]>([]);
const total = ref(0);
const action = ref("");
const kw = ref("");
const page = ref(1);
const size = ref(20);
const loading = ref(false);

// 后端 LocalDateTime 序列化为 ISO(2026-11-03T15:04:53)——展示统一去 T
const fmt = (s?: string) => (s || "").replace("T", " ").slice(0, 19);
const actType = (a: string) =>
  (a.startsWith("kb.delete") || a.startsWith("user.disable") || a === "key.revoke" || a === "member.revoke"
    ? "danger" : a.startsWith("user.role") || a.startsWith("kb.visibility") || a === "share.create" ? "warning" : "info");

async function reload() {
  loading.value = true;
  try {
    const r = (await http.get("/admin/audit", {
      params: { action: action.value || undefined, kw: kw.value || undefined, page: page.value, size: size.value },
    })) as any;
    rows.value = r.rows || []; total.value = Number(r.total || 0);
  } finally { loading.value = false; }
}
onMounted(reload);
</script>

<style scoped>
.page { padding: 16px 22px; max-width: 1180px; margin: 0 auto; }
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.bar h2 { margin: 0; }
.filters { margin-left: auto; display: flex; gap: 8px; }
.pg { margin-top: 10px; justify-content: flex-end; }
.uid { margin-left: 6px; }
</style>
