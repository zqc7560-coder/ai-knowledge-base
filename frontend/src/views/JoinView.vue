<template>
  <!-- M10.5 邀请落地页：/join?token=xxx 。未登录→引导登录并回跳；已登录→预览+接受 -->
  <div class="wrap">
    <el-card class="box" shadow="never">
      <template #header>知识库邀请</template>
      <div v-if="loading"><el-skeleton :rows="3" animated /></div>
      <el-result v-else-if="err" icon="warning" title="链接无效" :sub-title="err" />
      <div v-else-if="!store.loggedIn">
        <p>你被邀请加入知识库 <b>{{ info.kbName }}</b>，权限档位：<el-tag size="small">{{ info.permission }}</el-tag></p>
        <p class="hint">接受邀请需先登录，登录后自动回到本页。</p>
        <el-button type="primary" @click="goLogin">去登录</el-button>
      </div>
      <div v-else-if="accepted" style="text-align:center">
        <el-result icon="success" title="已加入" sub-title="现在可以在知识库列表中看到它并提问了" />
        <el-button type="primary" @click="$router.push('/')">去知识库</el-button>
      </div>
      <div v-else>
        <p>你被邀请加入知识库 <b>{{ info.kbName }}</b>，权限档位：<el-tag size="small">{{ info.permission }}</el-tag></p>
        <p class="hint">有效期至 {{ info.expiresAt?.replace('T', ' ') }}</p>
        <el-button type="primary" :loading="busy" @click="accept">接受邀请</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { http } from "../api/http";
import { useUserStore } from "../stores/user";
import { ElMessage } from "element-plus";

const route = useRoute(), router = useRouter(), store = useUserStore();
const loading = ref(true), busy = ref(false), err = ref(""), info = ref<any>(null), accepted = ref(false);

async function load() {
  loading.value = true; err.value = "";
  try { info.value = (await http.get(`/public/invite/${route.query.token}`)) as any; }
  catch (e: any) { err.value = e?.message || "链接无效或已过期"; }
  finally { loading.value = false; }
}
function goLogin() {
  router.push({ name: "login", query: { redirect: route.fullPath } });
}
async function accept() {
  busy.value = true;
  try {
    await http.post("/invites/accept", { token: route.query.token });
    accepted.value = true;
    ElMessage.success("邀请已接受");
  } catch { /* toast */ } finally { busy.value = false; }
}
onMounted(load);
</script>

<style scoped>
.wrap { min-height: 70vh; display: flex; align-items: flex-start; justify-content: center; padding-top: 8vh; }
.box { width: 460px; }
.hint { color: var(--muted-foreground, #999); font-size: 12.5px; }
</style>
