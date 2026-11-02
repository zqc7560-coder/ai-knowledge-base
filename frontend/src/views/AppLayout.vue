<template>
  <el-container class="layout">
    <el-header class="hd">
      <div class="brand" @click="$router.push('/')">📚 AI 知识库</div>
      <div class="right" v-if="store.me">
        <span class="uname">{{ store.me.username }}</span>
        <el-button size="small" text @click="doLogout">退出</el-button>
      </div>
    </el-header>
    <el-main><router-view /></el-main>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted } from "vue";
import { useRouter } from "vue-router";
import { useUserStore } from "../stores/user";

const store = useUserStore();
const router = useRouter();

onMounted(async () => {
  if (store.loggedIn && !store.me) {
    try { await store.loadMe(); } catch { store.clear(); router.push("/login"); }
  }
});

async function doLogout() {
  await store.logout();
  router.push("/login");
}
</script>

<style scoped>
.layout { min-height: 100vh; }
.hd { display: flex; align-items: center; justify-content: space-between; background: #fff;
      border-bottom: 1px solid #e4e7ed; }
.brand { font-weight: 700; font-size: 17px; cursor: pointer; }
.right { display: flex; align-items: center; gap: 8px; }
.uname { color: #606266; }
</style>
