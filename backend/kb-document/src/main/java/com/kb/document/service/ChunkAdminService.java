package com.kb.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.DocumentChunk;
import com.kb.domain.entity.User;
import com.kb.domain.kb.KbAccessService;
import com.kb.domain.mapper.DocumentChunkMapper;
import com.kb.rag.vector.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * chunk 人工干预（M9.4，对齐 FastGPT"切片级编辑"）：
 * 解决"个别烂切片污染检索"——owner/EDIT 用户可查看、改文、禁用单个切片。
 *
 * <h3>禁用的实现口径：删向量而非检索过滤</h3>
 * 向量库里该 chunk 的向量被物理删除，启用时重新 embed 回灌。
 * 为什么不在 search() 里 join MySQL 过滤 disabled：pgvector/Milvus 两种实现都要改、
 * 且召回 topK 会被禁用行"占坑后剔除"导致数量抖动。删向量=检索天然看不见，代价
 * 仅是启用时一次 embedding 调用（用户显式操作，频率极低）。
 * 禁用行的 MySQL 文本保留（禁用≠删除，随时可启用）。
 */
@Slf4j
@Service
public class ChunkAdminService {

    private final DocumentChunkMapper chunks;
    private final KbAccessService access;
    private final VectorStore vectors;

    public ChunkAdminService(DocumentChunkMapper chunks, KbAccessService access, VectorStore vectors) {
        this.chunks = chunks;
        this.access = access;
        this.vectors = vectors;
    }

    public List<DocumentChunk> list(User me, Long docId, int limit) {
        DocumentChunk any = chunks.selectOne(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, docId).last("limit 1"));
        if (any == null) return List.of();
        access.require(any.getKbId(), me, KbAccessService.Perm.READ);   // 看切片=读库权限
        return chunks.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, docId).orderByAsc(DocumentChunk::getSeqNo)
                .last("limit " + Math.min(limit, 500)));
    }

    /** 改切片原文：文本更新 + 向量重嵌入（同 chunkId upsert）。EDIT 权限。 */
    public DocumentChunk edit(User me, Long chunkId, String content) {
        if (content == null || content.isBlank() || content.length() > 8000)
            throw BizException.of(BizCode.PARAM_INVALID, "切片内容需 1-8000 字符");
        DocumentChunk c = loadForEdit(me, chunkId);
        c.setContent(content);
        chunks.updateById(c);
        if (c.getDisabled() == null || c.getDisabled() == 0) {
            vectors.upsert(List.of(new VectorStore.ChunkRow(c.getId(), c.getKbId(), c.getDocId(), content)));
        }
        return c;
    }

    /** 禁用/启用。禁用=删向量留文本；启用=重嵌入回灌。 */
    public DocumentChunk toggle(User me, Long chunkId) {
        DocumentChunk c = loadForEdit(me, chunkId);
        boolean nowDisabled = c.getDisabled() == null || c.getDisabled() == 0;
        c.setDisabled(nowDisabled ? 1 : 0);
        chunks.updateById(c);
        if (nowDisabled) {
            vectors.deleteByChunkIds(List.of(chunkId));
        } else {
            vectors.upsert(List.of(new VectorStore.ChunkRow(c.getId(), c.getKbId(), c.getDocId(), c.getContent())));
        }
        log.info("chunk {} {} by user {}", chunkId, nowDisabled ? "disabled" : "enabled", me.getId());
        return c;
    }

    private DocumentChunk loadForEdit(User me, Long chunkId) {
        DocumentChunk c = chunks.selectById(chunkId);
        if (c == null) throw BizException.of(BizCode.NOT_FOUND, "切片不存在");
        access.require(c.getKbId(), me, KbAccessService.Perm.EDIT);
        return c;
    }
}
