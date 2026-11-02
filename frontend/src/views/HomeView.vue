<template>
  <div class="wrap">
    <el-card>
      <template #header>AI 智能知识库 · 系统状态</template>
      <el-descriptions :column="1" border v-loading="loading">
        <el-descriptions-item label="应用">{{ info.app }}</el-descriptions-item>
        <el-descriptions-item label="MySQL">
          <el-tag :type="info.mysql === 'ok' ? 'success' : 'danger'">{{ info.mysql }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="Redis">
          <el-tag :type="info.redis === 'ok' ? 'success' : 'danger'">{{ info.redis }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-button type="primary" style="margin-top: 16px" :loading="checking" @click="checkAi">
        检测 AI 能力（Chat / Embedding / Rerank）
      </el-button>
      <pre v-if="aiInfo" class="mono">{{ JSON.stringify(aiInfo, null, 2) }}</pre>
      <p class="tip">密钥由服务端保管，本页面仅显示掩码。</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { http } from "../api/http";

const loading = ref(false);
const checking = ref(false);
const info = ref<Record<string, string>>({});
const aiInfo = ref<Record<string, unknown> | null>(null);

async function load() {
  loading.value = true;
  try {
    info.value = (await http.get("/ping")) as Record<string, string>;
  } finally {
    loading.value = false;
  }
}

async function checkAi() {
  checking.value = true;
  try {
    aiInfo.value = (await http.get("/admin/ai-check")) as Record<string, unknown>;
  } finally {
    checking.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.wrap { max-width: 720px; margin: 48px auto; padding: 0 16px; }
.mono { font-family: Consolas, monospace; background: #f0f2f5; padding: 12px; border-radius: 6px; }
.tip { color: #909399; font-size: 13px; }
</style>
