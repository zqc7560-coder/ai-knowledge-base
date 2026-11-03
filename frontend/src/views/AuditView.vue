<template>
  <div class="page">
    <div class="bar">
      <el-button text @click="$router.push('/admin')">← 后台</el-button>
      <h2>审计日志</h2>
      <div class="filters">
        <el-select v-model="action" placeholder="动作过滤" clearable size="small" style="width: 160px" @change="reload">
          <el-option v-for="a in ACTION_TYPES" :key="a" :label="a" :value="a" />
        </el-select>
        <el-button size="small" @click="reload">刷新</el-button>
      </div>
    </div>
    <el-table :data="rows" size="small" v-loading="loading" empty-text="暂无审计记录">
      <el-table-column prop="createdAt" label="时间" width="165" />
      <el-table-column prop="username" label="操作者" width="120" />
      <el-table-column prop="action" label="动作" width="140" />
      <el-table-column prop="target" label="对象" min-width="120" />
      <el-table-column prop="detail" label="详情" min-width="180" />
      <el-table-column prop="ip" label="IP" width="130" />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { http } from "../api/http";

const ACTION_TYPES = ["kb.delete", "kb.visibility", "member.grant", "member.revoke",
  "user.disable", "user.enable", "user.role", "share.create", "key.create", "key.revoke"];
const rows = ref<any[]>([]);
const action = ref("");
const loading = ref(false);
async function reload() {
  loading.value = true;
  try { rows.value = (await http.get("/admin/audit", { params: action.value ? { action: action.value } : {} })) as any[]; }
  finally { loading.value = false; }
}
onMounted(reload);
</script>

<style scoped>
.page { padding: 16px 22px; }
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.filters { margin-left: auto; display: flex; gap: 8px; }
</style>
