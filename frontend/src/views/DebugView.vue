<template>
  <div class="page">
    <div class="bar">
      <el-button text @click="$router.push('/')">← 返回</el-button>
      <h2>检索调试</h2>
      <span class="hint">跑真实检索管道，逐阶段看中间结果（不生成答案、不落库）</span>
    </div>

    <el-card shadow="never" class="form">
      <el-form inline>
        <el-form-item label="知识库">
          <el-select v-model="kbIds" multiple collapse-tags style="width: 260px">
            <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="问题" style="flex:1">
          <el-input v-model="q" placeholder="输入要调试检索的问题" style="width: 320px" @keyup.enter="run" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="run">调试</el-button>
      </el-form>
    </el-card>

    <div v-if="res" class="stages">
      <el-card shadow="never">
        <template #header>① 查询改写</template>
        <div class="kv"><b>原始：</b>{{ res.original }}</div>
        <div class="kv"><b>改写：</b>{{ res.rewritten }}<span v-if="res.original===res.rewritten" class="hint">（未触发/无变化）</span></div>
      </el-card>

      <el-card shadow="never">
        <template #header>② 向量召回（宽 TopK）</template>
        <div v-if="!res.vectorHits?.length" class="hint">（空）</div>
        <div v-for="r in res.vectorHits" :key="'v'+r.chunkId" class="rrow">
          <span class="rid">#{{ r.chunkId }}</span><span class="rsc">{{ num(r.score) }}</span><span class="rsn">{{ r.snippet }}</span>
        </div>
      </el-card>

      <el-card v-if="res.keywordHits" shadow="never">
        <template #header>③ 全文关键词召回</template>
        <div v-if="!res.keywordHits.length" class="hint">（空）</div>
        <div v-for="r in res.keywordHits" :key="'k'+r.chunkId" class="rrow">
          <span class="rid">#{{ r.chunkId }}</span><span class="rsn">{{ r.snippet }}</span>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>④ RRF 名次融合</template>
        <div v-for="r in res.fused" :key="'f'+r.chunkId" class="rrow">
          <span class="rid">#{{ r.chunkId }}</span><span class="rsc">{{ num(r.score) }}</span><span class="rsn">{{ r.snippet }}</span>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>⑤ Rerank 精排（gte-rerank-v2）</template>
        <div v-if="typeof res.rerank==='string'" class="hint">{{ res.rerank }}</div>
        <el-table v-else :data="res.rerank" size="small">
          <el-table-column prop="chunkId" label="chunk" width="80" />
          <el-table-column label="分数" width="90"><template #default="{ row }">{{ row.score.toFixed(3) }}</template></el-table-column>
          <el-table-column label="入选" width="70">
            <template #default="{ row }"><el-tag :type="row.passed?'success':'info'" size="small">{{ row.passed?'是':'否' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="snippet" label="片段" min-width="260" />
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>⑥ 最终喂料{{ res.parentExpanded ? '（父子分块：父窗扩展后）' : '' }}</template>
        <div v-for="r in (res.parentExpanded || res.finalRanked)" :key="'e'+r.chunkId" class="rrow">
          <span class="rid">#{{ r.chunkId }}</span><span class="rsn">{{ clip(r.snippet) }}</span>
        </div>
      </el-card>

      <div class="switches">
        <el-tag v-for="(v,k) in res.switches" :key="k" :type="v?'success':'info'" size="small">{{ k }}={{ v }}</el-tag>
      </div>
    </div>
    <el-empty v-else-if="searched" description="无结果或出错" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { http } from "../api/http";

const route = useRoute();
import { ElMessage } from "element-plus";

const kbs = ref<any[]>([]);
const kbIds = ref<number[]>([]);
const q = ref("");
const res = ref<any>(null);
const loading = ref(false);
const searched = ref(false);

const num = (x: any) => Number(x).toFixed(4);
const clip = (s: string) => (s && s.length > 90 ? s.slice(0, 90) + "…" : s);

onMounted(async () => {
  kbs.value = (await http.get("/kb")) as any[];
  const pre = Number(route.query.kb || 0);
  if (pre && kbs.value.some(k => k.id === pre)) kbIds.value = [pre];
  else if (kbs.value.length) kbIds.value = [kbs.value[0].id];
});

async function run() {
  if (!kbIds.value.length || !q.value.trim()) { ElMessage.warning("选库并输入问题"); return; }
  loading.value = true; searched.value = true;
  try { res.value = (await http.post("/debug/retrieve", { kbIds: kbIds.value, question: q.value })) as any; }
  catch { res.value = null; }
  finally { loading.value = false; }
}
</script>

<style scoped>
.page { padding: 16px 22px; }
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.form :deep(.el-form-item) { margin-bottom: 0; }
.stages { margin-top: 14px; display: flex; flex-direction: column; gap: 12px; }
.kv { font-size: 13px; margin: 4px 0; }
.rrow { display: flex; gap: 10px; padding: 5px 0; border-bottom: 1px dashed var(--border, #eee); font-size: 12.5px; align-items: baseline; }
.rid { color: var(--muted-foreground, #888); min-width: 62px; font-family: monospace; }
.rsc { color: #16a34a; min-width: 64px; font-family: monospace; }
.rsn { flex: 1; }
.switches { display: flex; gap: 8px; flex-wrap: wrap; }
.hint { color: var(--muted-foreground, #999); font-size: 12px; }
</style>
