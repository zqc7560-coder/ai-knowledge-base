<template>
  <div class="wrap">
    <el-card>
      <template #header>登录（M1 实现）</template>
      <el-input v-model="u" placeholder="用户名" />
      <el-input v-model="p" type="password" placeholder="密码" style="margin-top: 12px" />
      <el-button type="primary" style="margin-top: 16px; width: 100%" disabled>登录</el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
const u = ref("");
const p = ref("");
</script>

<style scoped>
.wrap { max-width: 380px; margin: 80px auto; padding: 0 16px; }
</style>
