package com.kb.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.Document;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.DocumentChunk;
import com.kb.domain.mapper.DocumentChunkMapper;
import com.kb.domain.mapper.DocumentMapper;
import com.kb.rag.vector.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重建索引 / 跨向量库迁移（M6：docs/01"一键重向量化"+ docs/02 M6"Milvus 切换演练"）。
 *
 * <h3>能力一：reindex（同存储重建）</h3>
 * 场景=改了切片参数后想让新参数生效：清空该库向量 → 走 DocumentService.process
 * 逐文档"解析→切片→向量化"重跑（process 的 upsert 幂等 + ChunkSplitter 读库级参数，
 * 所以重跑=自动用新 chunk_size 重切）。文档置回 PARSING 让前端有状态可看。
 *
 * <h3>能力二：migrate（跨存储搬迁，切换演练）</h3>
 * 场景=从 pgvector 切到 Milvus（或反向）。做法：逐批读"chunk 原文"（MySQL 是事实源）
 * → upsert 进目标 VectorStore（注意：embed 在 upsert 内部发生，即目标库用的是
 * 目标链路重嵌入，与旧库向量无关——保证口径自洽）。成功后清旧库该批数据。
 * 为什么不在应用内做"读旧向量→写新向量"的搬运：两边相似度度量已对齐(IP)，
 * 但 Milvus 集合 schema/索引由实现自管（惰性建集合），搬运原始 float[] 反而绕过了
 * 各实现的建集合逻辑，重嵌入更简单可靠；成本=一次全量 embedding（可分批限流）。
 *
 * <h3>并发与幂等</h3>
 * 单库同时只允许一个任务（RUNNING 集合按 kbId 判重，重复提交 1004）；
 * 任务跑在后台线程，进度写内存 map（admin 轮询 /reindex/status）。
 * 中途失败=旧数据未清（migrate 先写新后删旧），目标库可能残缺，重跑即可——
 * chunkId upsert 幂等保证重试不脏数据。
 */
@Slf4j
@Service
public class ReindexService {

    /** 单库任务状态。state 用 AtomicReference 而非 record 组件（record 字段不可变，任务要就地更新）。 */
    public static class TaskInfo {
        public final String type;
        public final String target;
        public final int unitTotal;
        public final AtomicInteger unitDone = new AtomicInteger();
        public final AtomicInteger chunks = new AtomicInteger();
        public final java.util.concurrent.atomic.AtomicReference<String> state =
                new java.util.concurrent.atomic.AtomicReference<>("RUNNING");
        Long rowId;                     // process_tasks.id（M7.1 持久化）
        volatile long lastFlush;        // 进度节流写库（≥2s 或终态才 update，避免每单位一次 UPDATE）
        TaskInfo(String type, String target, int unitTotal) {
            this.type = type; this.target = target; this.unitTotal = unitTotal;
        }
    }

    private final DocumentMapper docs;
    private final DocumentChunkMapper chunks;
    private final DocumentService docPipeline;
    private final com.kb.rag.vector.VectorStoreRouter router;
    private final com.kb.domain.mapper.ProcessTaskMapper taskLog;

    private final Map<Long, TaskInfo> tasks = new ConcurrentHashMap<>();

    public ReindexService(DocumentMapper docs, DocumentChunkMapper chunks,
                          DocumentService docPipeline, com.kb.rag.vector.VectorStoreRouter router,
                          com.kb.domain.mapper.ProcessTaskMapper taskLog) {
        this.docs = docs;
        this.chunks = chunks;
        this.docPipeline = docPipeline;
        this.router = router;
        this.taskLog = taskLog;
    }

    /** 当前生效的向量存储（切换配置后自动变）。 */
    private VectorStore active() { return router.active(); }

    public void reindex(Long kbId, Long userId, String kbName) {
        guard(kbId);
        List<Document> list = docs.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId));
        TaskInfo t = new TaskInfo("reindex", active().name(), list.size());
        t.rowId = openRow(t, kbId, kbName, userId);   // M7.1：任务先落库再起跑
        tasks.put(kbId, t);
        VectorStore vs = active();
        new Thread(() -> {
            try {
                vs.deleteByKbIds(List.of(kbId));
                for (Document d : list) {
                    docPipeline.reprocess(d.getId());   // 同步重跑：解析→按库新参数切片→向量
                    t.unitDone.incrementAndGet(); flush(t);
                }
                t.state.set("DONE");
            } catch (Exception e) {
                t.state.set("FAILED: " + e.getMessage());
                log.warn("reindex kb {} failed", kbId, e);
            } finally { tasksRunning.remove(kbId); flushFinal(t); }
        }, "reindex-" + kbId).start();
    }

    /** 迁移演练：把库内全部 chunk 重嵌入进目标存储，成功后清当前存储该库数据。 */
    public void migrate(Long kbId, String toStore, Long userId, String kbName) {
        VectorStore from = active();
        VectorStore to = router.byName(toStore);
        if (to == null) throw BizException.of(BizCode.PARAM_INVALID, "未知目标存储: " + toStore);
        if (toStore.equals(from.name())) throw BizException.of(BizCode.PARAM_INVALID,
                "目标与当前存储相同（当前=" + from.name() + "）");
        guard(kbId);
        List<DocumentChunk> all = chunks.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getKbId, kbId).orderByAsc(DocumentChunk::getId));
        TaskInfo t = new TaskInfo("migrate", from.name() + "->" + to.name(), all.size());
        t.rowId = openRow(t, kbId, kbName, userId);
        tasks.put(kbId, t);
        VectorStore src = from, dst = to;
        new Thread(() -> {
            try {
                // 只拷原文重嵌入（不做向量搬运）：dst.upsert 内部 embed——口径自洽（类注释有推导）
                for (int i = 0; i < all.size(); i += 10) {
                    var batch = all.subList(i, Math.min(i + 10, all.size()));
                    dst.upsert(batch.stream().map(c -> new VectorStore.ChunkRow(
                            c.getId(), c.getKbId(), c.getDocId(), c.getContent())).toList());
                    t.unitDone.addAndGet(batch.size()); flush(t);
                }
                src.deleteByKbIds(List.of(kbId));
                t.state.set("DONE");
            } catch (Exception e) {
                t.state.set("FAILED: " + e.getMessage());
                log.warn("migrate kb {} failed", kbId, e);
            } finally { tasksRunning.remove(kbId); flushFinal(t); }
        }, "migrate-" + kbId).start();
    }

    public Map<String, Object> status(Long kbId) {
        TaskInfo t = tasks.get(kbId);
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("store", active().name());
        if (t == null) {
            // 重启后内存没了，但前端仍在轮询本库状态——回源 DB 最近一条（M7.1）
            var last = taskLog.selectList(new LambdaQueryWrapper<com.kb.domain.entity.ProcessTask>()
                    .eq(com.kb.domain.entity.ProcessTask::getKbId, kbId)
                    .orderByDesc(com.kb.domain.entity.ProcessTask::getId).last("LIMIT 1"));
            if (!last.isEmpty()) {
                var r = last.get(0);
                m.put("task", Map.of("type", r.getType(), "target", r.getTarget(),
                        "unitTotal", r.getUnitTotal(), "unitDone", r.getUnitDone(),
                        "chunks", 0, "state", r.getState()));
                return m;
            }
            m.put("task", null); return m;
        }
        m.put("task", Map.of("type", t.type, "target", t.target,
                "unitTotal", t.unitTotal, "unitDone", t.unitDone.get(),
                "chunks", t.chunks.get(), "state", t.state.get()));
        return m;
    }

    /** 任务历史分页（管理页）：type 可选过滤，返回 {rows,total}。 */
    public java.util.Map<String, Object> history(String type, int page, int size) {
        int sz = Math.min(Math.max(size, 1), 100);
        var q = new LambdaQueryWrapper<com.kb.domain.entity.ProcessTask>();
        if (type != null && !type.isBlank()) q.eq(com.kb.domain.entity.ProcessTask::getType, type);
        long total = taskLog.selectCount(q);
        q.orderByDesc(com.kb.domain.entity.ProcessTask::getId)
         .last("LIMIT " + sz + " OFFSET " + (Math.max(page, 1) - 1) * sz);
        return java.util.Map.of("rows", taskLog.selectList(q), "total", total);
    }

    /** 任务落库：立即 insert 得 rowId。 */
    private Long openRow(TaskInfo t, Long kbId, String kbName, Long userId) {
        var r = new com.kb.domain.entity.ProcessTask();
        r.setType(t.type); r.setKbId(kbId); r.setKbName(kbName); r.setTarget(t.target);
        r.setUnitTotal(t.unitTotal); r.setUnitDone(0); r.setState("RUNNING"); r.setCreatedBy(userId);
        taskLog.insert(r);
        return r.getId();
    }
    /** 进度节流写库：距上次 ≥2 秒才 update（任务循环里高频调用，不能每步都打 DB）。 */
    private void flush(TaskInfo t) {
        if (t.rowId == null) return;
        long now = System.currentTimeMillis();
        if (now - t.lastFlush < 2000) return;
        t.lastFlush = now;
        var r = new com.kb.domain.entity.ProcessTask();
        r.setId(t.rowId); r.setUnitDone(t.unitDone.get());
        taskLog.updateById(r);
    }
    /** 终态强制写库（不受节流限制）。 */
    private void flushFinal(TaskInfo t) {
        if (t.rowId == null) return;
        var r = new com.kb.domain.entity.ProcessTask();
        r.setId(t.rowId); r.setUnitDone(t.unitDone.get()); r.setState(t.state.get());
        taskLog.updateById(r);
    }

    private final java.util.Set<Long> tasksRunning = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private void guard(Long kbId) {
        if (!tasksRunning.add(kbId)) throw BizException.of(BizCode.RATE_LIMITED, "该库已有进行中的任务");
    }
}
