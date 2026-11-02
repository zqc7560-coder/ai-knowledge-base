<template>
  <!-- 公开只读分享页：无导航、无输入框、不能提问（对应后端 /api/public/share/{token}） -->
  <div class="wrap">
    <el-card v-if="loading" class="hd"><el-skeleton :rows="4" animated /></el-card>
    <el-result v-else-if="err" icon="warning" title="链接无效" :sub-title="err" />
    <template v-else>
      <el-card class="hd">
        <h2>{{ title }}</h2>
        <p class="sub">来自 AI 知识库的分享对话 · 只读</p>
      </el-card>
      <div class="msg" v-for="(m, i) in messages" :key="i" :class="m.role">
        <div class="bubble">
          <div class="who">{{ m.role === "user" ? "提问" : "回答" }}</div>
          <div class="md" v-if="m.role === 'assistant'" v-html="renderMd(m.content)"></div>
          <div v-else class="plain">{{ m.content }}</div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { renderMd } from "../utils/md";

const route = useRoute();
const loading = ref(true);
const err = ref("");
const title = ref("");
const messages = ref<{ role: string; content: string }[]>([]);

onMounted(async () => {
  const token = route.params.token as string;
  try {
    // 公开端点：不带 Authorization（axios 实例会带 token，这里用原生 fetch 保持"匿名只读"语义）
    const resp = await fetch(`/api/public/share/${encodeURIComponent(token)}`, {
      headers: { Accept: "application/json" },
    });
    const body = await resp.json();
    if (body.code !== 0) { err.value = body.message || "分享不存在或已撤销"; return; }
    title.value = body.data.title;
    messages.value = body.data.messages;
  } catch {
    err.value = "网络错误，请稍后重试";
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.wrap { max-width: 760px; margin: 32px auto; padding: 0 16px; }
.hd h2 { margin: 0 0 6px; }
.sub { color: #909399; font-size: 13px; margin: 0; }
.msg { display: flex; margin: 14px 0; }
.msg.user { justify-content: flex-end; }
.bubble { max-width: 80%; background: #f0f2f5; border-radius: 10px; padding: 10px 14px; }
.msg.user .bubble { background: #e8efff; }
.who { font-size: 12px; color: #909399; margin-bottom: 4px; }
.plain { white-space: pre-wrap; font-size: 14px; }
.md { font-size: 14px; }
</style>
