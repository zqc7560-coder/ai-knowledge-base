<template>
  <el-container class="layout">
    <el-header class="hd">
      <div class="brand" @click="$router.push('/')">📚 AI 知识库</div>
      <div class="navs">
        <el-button text :type="isTab('/') ? 'primary' : ''" @click="$router.push('/')">知识库</el-button>
        <el-button text :type="isTab('/chat') ? 'primary' : ''" @click="$router.push('/chat')">对话</el-button>
        <el-button v-if="store.me?.role === 'ADMIN'" text
                   :type="isTab('/admin') ? 'primary' : ''" @click="$router.push('/admin')">管理</el-button>
      </div>
      <div class="right" v-if="store.me">
        <el-button text @click="$router.push('/keys')">API 密钥</el-button>
        <el-dropdown trigger="click">
          <span class="uname userdrop">{{ store.me.username }} ▾</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="$router.push('/profile')">个人设置</el-dropdown-item>
              <el-dropdown-item divided @click="doLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>
    <el-main><router-view /></el-main>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useUserStore } from "../stores/user";

const store = useUserStore();
const router = useRouter();
const route = useRoute();

/** 当前一级导航是否高亮：以路由路径前缀判断（/kb/x/docs 归"知识库"）。 */
function isTab(base: string) {
  if (base === "/") return route.path === "/" || route.path.startsWith("/kb");
  return route.path.startsWith(base);
}

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
.userdrop { cursor: pointer; color: #4c7dff; font-weight: 500; }
</style>
