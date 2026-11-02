<template>
  <!-- M9.4 切片人工干预抽屉：查看/编辑/禁用单个 chunk（烂切片污染的自救通道） -->
  <el-drawer :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
             :title="'切片管理 · ' + (docName || '')" size="58%">
    <div v-loading="loading">
      <div class="hint" style="margin-bottom: 10px">
        编辑会重新向量化该切片；禁用=从检索中下线（向量删除，可随时启用）。改动立即生效，无需重建索引。
      </div>
      <div v-for="c in list" :key="c.id" class="chk" :class="{ off: c.disabled === 1 }">
        <div class="hd">
          <b>#{{ c.seqNo }}</b>
          <span class="meta">{{ c.tokenCount }} tok<span v-if="c.pageNo"> · P{{ c.pageNo }}</span><span v-if="c.titlePath"> · {{ c.titlePath }}</span></span>
          <span style="flex:1"></span>
          <el-button size="small" @click="toggle(c)">{{ c.disabled === 1 ? '启用' : '禁用' }}</el-button>
          <el-button size="small" type="primary" plain @click="startEdit(c)" v-if="c.disabled !== 1">编辑</el-button>
        </div>
        <div v-if="editingId !== c.id" class="body">{{ c.content }}</div>
        <div v-else class="body">
          <el-input v-model="editText" type="textarea" :rows="8" />
          <div style="margin-top: 6px">
            <el-button size="small" @click="editingId = 0">取消</el-button>
            <el-button size="small" type="primary" :loading="saving" @click="save(c)">保存并重新向量化</el-button>
          </div>
        </div>
      </div>
      <el-empty v-if="!list.length && !loading" description="该文档暂无切片（可能未处理完成）" />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { http } from "../api/http";

const props = defineProps<{ modelValue: boolean; docId?: number; docName?: string }>();
const emit = defineEmits(["update:modelValue"]);
const list = ref<any[]>([]);
const loading = ref(false);
const editingId = ref(0);
const editText = ref("");
const saving = ref(false);

watch(() => props.modelValue, async (v) => {
  if (v && props.docId) {
    loading.value = true;
    try { list.value = (await http.get(`/documents/${props.docId}/chunks?limit=200`)) as any[]; }
    finally { loading.value = false; }
  }
});

function startEdit(c: any) { editingId.value = c.id; editText.value = c.content; }

async function save(c: any) {
  saving.value = true;
  try {
    const r = (await http.put(`/chunks/${c.id}`, { content: editText.value })) as any;
    c.content = r.content;
    editingId.value = 0;
    ElMessage.success("已更新并重新向量化");
  } finally { saving.value = false; }
}

async function toggle(c: any) {
  const r = (await http.post(`/chunks/${c.id}/toggle`)) as any;
  c.disabled = r.disabled;
  ElMessage.success(r.disabled === 1 ? "已禁用（检索不再命中该切片）" : "已启用（重新回灌向量）");
}
</script>

<style scoped>
.chk { border: 1px solid var(--border, #eee); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; }
.chk.off { opacity: .5; background: var(--muted, #f7f7f7); }
.hd { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.meta { color: var(--muted-foreground, #888); font-size: 12px; }
.body { font-size: 13px; white-space: pre-wrap; line-height: 1.55; max-height: 260px; overflow: auto; }
</style>
