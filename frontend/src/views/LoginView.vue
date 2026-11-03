<template>
  <div class="wrap">
    <div class="brand" @click="$router.push('/login')">
      <div class="logo">📚</div>
      <div>
        <h1>AI 知识库</h1>
        <p class="slogan">上传文档，构建你自己的知识库，让 AI 基于资料精准回答</p>
      </div>
    </div>
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
      <div v-if="tab === 'login'" class="forgot">
        <el-link v-if="resetAvailable" type="info" :underline="false" @click="openForgot">忘记密码？</el-link>
        <span v-else class="norfgtip">忘记密码请联系管理员重置</span>
      </div>

    <!-- 找回密码（M11.3）：两步——邮箱发码 → 验证码+新密码 -->
    <el-dialog v-model="forgot" title="找回密码" width="380px">
      <el-steps :active="forgotStep" simple style="margin-bottom: 16px">
        <el-step title="邮箱" /><el-step title="验证并重置" />
      </el-steps>
      <div v-if="forgotStep === 0">
        <el-input v-model="fpEmail" placeholder="注册时使用的邮箱" />
        <div class="tip">若该邮箱已注册，验证码将发送到邮箱（10 分钟有效）。</div>
      </div>
      <div v-else>
        <el-input v-model="fpCode" placeholder="6 位验证码" maxlength="6" style="margin-bottom: 10px" />
        <el-input v-model="fpNew" type="password" placeholder="新密码（8-64 位）" show-password />
      </div>
      <template #footer>
        <el-button @click="forgot = false">取消</el-button>
        <el-button type="primary" :loading="fpBusy" @click="forgotNext">
          {{ forgotStep === 0 ? "发送验证码" : "重置密码" }}
        </el-button>
      </template>
    </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { http } from "../api/http";
import { useUserStore } from "../stores/user";

const tab = ref<"login" | "register">("login");
const resetAvailable = ref(false);
const forgot = ref(false);
const forgotStep = ref(0);
const fpEmail = ref(""); const fpCode = ref(""); const fpNew = ref("");
const fpBusy = ref(false);
function openForgot() { forgot.value = true; forgotStep.value = 0; }
async function forgotNext() {
  if (forgotStep.value === 0) {
    if (!/^[\w.+-]+@[\w-]+(\.[\w-]+)+$/.test(fpEmail.value)) { ElMessage.warning("邮箱格式不正确"); return; }
    fpBusy.value = true;
    try {
      await http.post("/auth/password/reset-request", { email: fpEmail.value });
      ElMessage.success("验证码已发送，请查收件箱");
      forgotStep.value = 1;
    } finally { fpBusy.value = false; }
  } else {
    if (!/^\d{6}$/.test(fpCode.value)) { ElMessage.warning("验证码为 6 位数字"); return; }
    if (fpNew.value.length < 8 || fpNew.value.length > 64) { ElMessage.warning("新密码需 8-64 位"); return; }
    fpBusy.value = true;
    try {
      await http.post("/auth/password/reset-confirm", { email: fpEmail.value, code: fpCode.value, password: fpNew.value });
      ElMessage.success("密码已重置，请用新密码登录");
      forgot.value = false; form.password = "";
    } finally { fpBusy.value = false; }
  }
}
const form = reactive({ username: "", email: "", password: "" });
// 进入登录页即拉"找回密码可用性"（SMTP 配了才显示链接；曾漏拉导致永远隐藏）
onMounted(async () => {
  try { resetAvailable.value = !!(await http.get("/auth/password/reset-available")); } catch { resetAvailable.value = false; }
});
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
.wrap { max-width: 380px; margin: 48px auto; padding: 0 16px; }
.brand { display: flex; align-items: center; gap: 14px; margin-bottom: 20px; cursor: pointer; }
.logo { width: 54px; height: 54px; border-radius: 14px; background: linear-gradient(135deg, #4c7dff, #7aa5ff);
  color: #fff; font-size: 30px; display: flex; align-items: center; justify-content: center; box-shadow: 0 6px 16px rgba(76, 125, 255, .35); }
.brand h1 { margin: 0; font-size: 24px; letter-spacing: 1px; }
.slogan { margin: 3px 0 0; font-size: 12.5px; color: #909399; max-width: 300px; line-height: 1.5; }
.norfgtip { color: #b0b3ba; font-size: 12px; }
.forgot { text-align: right; margin-top: 8px; }
.tip { color: #999; font-size: 12px; margin-top: 6px; }
</style>
