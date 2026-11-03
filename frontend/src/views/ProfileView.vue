<template>
  <div class="page">
    <div class="hd">
      <el-button text @click="$router.back()">← 返回</el-button>
      <h2>个人设置</h2>
    </div>

    <el-card shadow="never" class="block">
      <template #header><span class="bt">账号信息</span></template>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="用户名">{{ store.me?.username }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ store.me?.email }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag size="small" :type="store.me?.role === 'ADMIN' ? 'warning' : 'info'" effect="plain">{{ store.me?.role }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <p class="hint">用户名注册后不可修改；邮箱与密码可在下方自助更改（均需验证当前密码）。</p>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span class="bt">修改密码</span></template>
      <el-form label-position="top" style="max-width: 360px">
        <el-form-item label="当前密码">
          <el-input v-model="p.old" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码（8–64 位）">
          <el-input v-model="p.nw" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="p.re" type="password" show-password autocomplete="new-password"
                    @keyup.enter="savePwd" />
          <div v-if="p.nw && p.re && p.nw !== p.re" class="err">两次输入的新密码不一致</div>
        </el-form-item>
        <el-button type="primary" :loading="p.busy"
                   :disabled="!p.old || !p.nw || !p.re || p.nw !== p.re" @click="savePwd">修改密码</el-button>
      </el-form>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span class="bt">修改邮箱</span><span class="hint">（找回密码凭据，改后以新邮箱收验证码）</span></template>
      <el-form label-position="top" style="max-width: 360px">
        <el-form-item label="新邮箱">
          <el-input v-model="e.mail" placeholder="you@example.com" />
        </el-form-item>
        <el-form-item label="当前密码">
          <el-input v-model="e.pw" type="password" show-password @keyup.enter="saveMail" />
        </el-form-item>
        <el-button type="primary" :loading="e.busy" :disabled="!e.mail || !e.pw" @click="saveMail">修改邮箱</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive } from "vue";
import { ElMessage } from "element-plus";
import { http } from "../api/http";
import { useUserStore } from "../stores/user";

const store = useUserStore();
const p = reactive({ old: "", nw: "", re: "", busy: false });
const e = reactive({ mail: "", pw: "", busy: false });

async function savePwd() {
  if (p.nw.length < 8 || p.nw.length > 64) { ElMessage.warning("新密码需 8–64 位"); return; }
  if (p.nw !== p.re) { ElMessage.warning("两次输入的新密码不一致"); return; }
  p.busy = true;
  try {
    await http.post("/auth/password/change", { oldPassword: p.old, newPassword: p.nw, confirmPassword: p.re });
    ElMessage.success("密码已修改");
    p.old = p.nw = p.re = "";
  } finally { p.busy = false; }
}
async function saveMail() {
  e.busy = true;
  try {
    await http.post("/auth/email/change", { email: e.mail, password: e.pw });
    ElMessage.success("邮箱已修改");
    e.mail = e.pw = "";
    await store.loadMe();   // 顶栏/本页描述即时反映新邮箱
  } finally { e.busy = false; }
}
</script>

<style scoped>
.page { max-width: 640px; margin: 20px auto; padding: 0 16px; }
.hd { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.hd h2 { margin: 0; }
.block { margin-bottom: 14px; }
.bt { font-weight: 600; }
.hint { color: #909399; font-size: 12px; margin: 6px 0 0; }
.err { color: #f56c6c; font-size: 12px; margin-top: 4px; }
</style>
