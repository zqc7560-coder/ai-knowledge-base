<template>
  <div class="wrap">
    <el-card>
      <el-tabs v-model="tab">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-form :model="form" label-position="top" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" maxlength="20" />
        </el-form-item>
        <el-form-item v-if="tab === 'register'" label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password @keyup.enter="submit" />
        </el-form-item>
      </el-form>
      <el-button type="primary" style="width: 100%" :loading="loading" @click="submit">
        {{ tab === "login" ? "登 录" : "注 册" }}
      </el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { http } from "../api/http";
import { useUserStore } from "../stores/user";

const tab = ref<"login" | "register">("login");
const form = reactive({ username: "", email: "", password: "" });
const loading = ref(false);
const router = useRouter();
const route = useRoute();
const store = useUserStore();

async function submit() {
  if (!form.username || form.password.length < 8) {
    ElMessage.warning("用户名必填，密码至少8位");
    return;
  }
  loading.value = true;
  try {
    const path = tab.value === "login" ? "/auth/login" : "/auth/register";
    const body: Record<string, string> = { username: form.username, password: form.password };
    if (tab.value === "register") body.email = form.email;
    const data = (await http.post(path, body)) as { token: string };
    store.setToken(data.token);
    await store.loadMe();
    router.push((route.query.redirect as string) || "/");
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.wrap { max-width: 380px; margin: 72px auto; padding: 0 16px; }
</style>
