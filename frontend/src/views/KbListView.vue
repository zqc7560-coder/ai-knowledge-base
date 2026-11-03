<template>
  <div class="page">
    <div class="bar">
      <h2>我的知识库</h2>
      <el-radio-group v-if="groups.length > 1" v-model="grpFilter" size="small" style="margin-right: 12px">
        <el-radio-button value="__all__">全部</el-radio-button>
        <el-radio-button v-for="g in groups" :key="g" :value="g || '__none__'">{{ g || '未分组' }}</el-radio-button>
      </el-radio-group>
      <el-button @click="$router.push('/debug')">检索调试</el-button>
      <el-button type="primary" @click="openCreate">＋ 新建知识库</el-button>
    </div>

    <el-empty v-if="!loading && kbs.length === 0" description="还没有知识库，点击右上角创建" />
    <div class="grid" v-loading="loading">
      <el-card v-for="kb in shown" :key="kb.id" class="kbc" shadow="hover">
        <div class="kbt">
          <span class="nm">{{ kb.name }}</span>
          <el-tag size="small" :type="visType(kb.visibility)">{{ visLabel(kb.visibility) }}</el-tag>
        </div>
        <p class="desc">{{ kb.description || "（暂无描述）" }}</p>
        <div class="meta">切片 {{ kb.chunkSize }}±{{ kb.chunkOverlap }} · TopK {{ kb.topK }} · 阈值 {{ kb.scoreThreshold }}</div>
        <div class="meta" v-if="kb.tags">
          <el-tag v-for="tg in String(kb.tags).split(',').filter(Boolean)" :key="tg" size="small" style="margin-right:4px">{{ tg }}</el-tag>
        </div>
        <div class="ops">
          <el-button size="small" type="primary" plain @click="$router.push(`/kb/${kb.id}/docs`)">文档</el-button>
          <el-button size="small" @click="openSettings(kb)">设置</el-button>
          <el-button size="small" @click="openMembers(kb)">成员</el-button>
          <el-popconfirm title="删除知识库及其全部文档？" @confirm="del(kb)">
            <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
          </el-popconfirm>
        </div>
      </el-card>
    </div>

    <!-- 创建/设置 -->
    <el-dialog v-model="dlgShow" :title="editing ? '知识库设置' : '新建知识库'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="f.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="f.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="f.visibility" style="width: 100%">
            <el-option label="私有 PRIVATE" value="PRIVATE" />
            <el-option label="共享 SHARED（授权成员可见）" value="SHARED" />
            <el-option label="公开 PUBLIC（仅管理员）" value="PUBLIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="切片大小">
          <el-input-number v-model="f.chunkSize" :min="100" :max="4000" :step="100" />
        </el-form-item>
        <el-form-item label="重叠">
          <el-input-number v-model="f.chunkOverlap" :min="0" :max="500" :step="25" />
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="f.topK" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="相似阈值">
          <el-input-number v-model="f.scoreThreshold" :min="0" :max="1" :step="0.05" :precision="3" />
        </el-form-item>
        <el-form-item label="父子分块">
          <el-switch v-model="f.parentChild" />
          <span class="hint" style="margin-left:10px">命中精确小片，回答前自动扩展前后文（M8）</span>
        </el-form-item>
        <el-form-item v-if="f.parentChild" label="父窗范围">
          <el-input-number v-model="f.pcSpan" :min="1" :max="5" /> 片
        </el-form-item>
        <el-form-item label="HyDE">
          <el-switch v-model="f.hyde" />
          <span class="hint" style="margin-left:10px">向量通道用"假设答案"检索（+1 次小模型调用）</span>
        </el-form-item>
        <el-form-item label="多查询">
          <el-switch v-model="f.multiQuery" />
          <span class="hint" style="margin-left:10px">原问题+假设答案双路召回取并集</span>
        </el-form-item>
        <el-form-item label="分组">
          <el-input v-model="f.grp" placeholder="可选，如：工作/学习（每用户自由文本）" maxlength="40" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="f.tags" placeholder="可选，逗号分隔，如：设备,售后" maxlength="200" />
        </el-form-item>
        <el-form-item>
          <el-button size="small" @click="goDebug">检索调试 →</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlgShow = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 成员管理 -->
    <el-drawer v-model="memShow" title="成员授权" size="380px">
      <div class="grant">
        <el-input v-model="gUser" placeholder="用户名" style="width: 130px" />
        <el-select v-model="gPerm" style="width: 110px">
          <el-option label="READ" value="READ" />
          <el-option label="ASK" value="ASK" />
          <el-option label="EDIT" value="EDIT" />
        </el-select>
        <el-button type="primary" size="small" @click="grant">授权</el-button>
      </div>
      <el-table :data="memberList" size="small" style="margin-top: 12px">
        <el-table-column prop="userId" label="用户ID" width="80" />
        <el-table-column prop="permission" label="权限" width="90" />
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button size="small" type="danger" text @click="revoke(row.userId)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { http } from "../api/http";

const router = useRouter();

interface Kb { id: number; name: string; description?: string; visibility: string;
  chunkSize: number; chunkOverlap: number; topK: number; scoreThreshold: number;
  parentChild?: number; pcSpan?: number; hyde?: number; multiQuery?: number; grp?: string; tags?: string }

const kbs = ref<Kb[]>([]);
const grpFilter = ref<string>("__all__");
const loading = ref(true);
const dlgShow = ref(false);
const saving = ref(false);
const editing = ref<Kb | null>(null);
const f = reactive({ name: "", description: "", visibility: "PRIVATE",
  chunkSize: 500, chunkOverlap: 100, topK: 5, scoreThreshold: 0.25, parentChild: false, pcSpan: 2,
  hyde: false, multiQuery: false, grp: "", tags: "" });

function goDebug() { dlgShow.value = false; router.push({ name: "debug", query: editing.value ? { kb: editing.value.id } : {} }); }
const memShow = ref(false);
const memberKb = ref<Kb | null>(null);
const memberList = ref<{ userId: number; permission: string }[]>([]);
const gUser = ref("");
const gPerm = ref("ASK");

const shown = computed(() =>
  grpFilter.value === "__all__" ? kbs.value :
  kbs.value.filter(k => (k.grp || "") === (grpFilter.value === "__none__" ? "" : grpFilter.value)));
const groups = computed(() => {
  const set = new Set<string>(kbs.value.map(k => k.grp || ""));
  return [...set].sort();
});
const visLabel = (v: string) => ({ PRIVATE: "私有", SHARED: "共享", PUBLIC: "公开" }[v] || v);
const visType = (v: string) => (v === "PUBLIC" ? "success" : v === "SHARED" ? "warning" : "info") as never;

async function reload() {
  loading.value = true;
  try { kbs.value = (await http.get("/kb")) as Kb[]; }
  finally { loading.value = false; }
}

function openCreate() {
  editing.value = null;
  Object.assign(f, { name: "", description: "", visibility: "PRIVATE",
    chunkSize: 500, chunkOverlap: 100, topK: 5, scoreThreshold: 0.25, parentChild: false, pcSpan: 2,
  hyde: false, multiQuery: false, grp: "", tags: "" });
  dlgShow.value = true;
}

function openSettings(kb: Kb) {
  editing.value = kb;
  Object.assign(f, { name: kb.name, description: kb.description || "", visibility: kb.visibility,
    chunkSize: kb.chunkSize, chunkOverlap: kb.chunkOverlap, topK: kb.topK,
    scoreThreshold: Number(kb.scoreThreshold), parentChild: kb.parentChild === 1, pcSpan: kb.pcSpan || 2,
    hyde: kb.hyde === 1, multiQuery: kb.multiQuery === 1, grp: kb.grp || "", tags: kb.tags || "" });
  dlgShow.value = true;
}

async function save() {
  if (!f.name.trim()) { ElMessage.warning("请输入名称"); return; }
  saving.value = true;
  try {
    if (editing.value) await http.put(`/kb/${editing.value.id}`, { ...f });
    else await http.post("/kb", { ...f });
    dlgShow.value = false;
    await reload();
  } catch { /* toast in interceptor */ }
  finally { saving.value = false; }
}

async function del(kb: Kb) {
  await http.delete(`/kb/${kb.id}`);
  ElMessage.success("已删除");
  await reload();
}

async function openMembers(kb: Kb) {
  memberKb.value = kb;
  memberList.value = (await http.get(`/kb/${kb.id}/members`)) as never;
  memShow.value = true;
}

async function grant() {
  if (!gUser.value || !memberKb.value) return;
  await http.post(`/kb/${memberKb.value.id}/members`, { username: gUser.value, permission: gPerm.value });
  ElMessage.success("已授权");
  memberList.value = (await http.get(`/kb/${memberKb.value.id}/members`)) as never;
}

async function revoke(userId: number) {
  if (!memberKb.value) return;
  await http.delete(`/kb/${memberKb.value.id}/members/${userId}`);
  memberList.value = (await http.get(`/kb/${memberKb.value.id}/members`)) as never;
}

onMounted(reload);
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; }
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.bar h2 { margin: 0; font-size: 19px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.kbt { display: flex; justify-content: space-between; align-items: center; }
.nm { font-weight: 600; font-size: 15px; }
.desc { color: #606266; font-size: 13px; min-height: 36px; }
.meta { color: #909399; font-size: 12px; margin-bottom: 10px; }
.ops { display: flex; gap: 6px; }
.grant { display: flex; gap: 8px; }
</style>
