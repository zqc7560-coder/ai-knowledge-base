package com.kb.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.document.parser.DocumentParser;
import com.kb.document.parser.ParsedDoc;
import com.kb.document.parser.ParserException;
import com.kb.document.pipeline.DocStatus;
import com.kb.document.upload.FileValidator;
import com.kb.domain.entity.Document;
import com.kb.domain.entity.DocumentChunk;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.User;
import com.kb.domain.kb.KbAccessService;
import com.kb.domain.mapper.DocumentChunkMapper;
import com.kb.domain.mapper.DocumentMapper;
import com.kb.document.chunk.ChunkSplitter;
import com.kb.rag.vector.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 文档管道门面：上传落盘 -> 异步 parse/chunk/embed -> 状态机推进（docs/02 M2）。
 * 异步用固定线程池(2) 防打爆 DashScope QPS；进度同步写 Redis（供前端轮询）。
 */
@Slf4j
@Service
public class DocumentService {

    private final DocumentMapper docs;
    private final DocumentChunkMapper chunks;
    private final KbAccessService access;
    private final VectorStore vectorStore;
    private final com.kb.document.parser.OcrService ocrService;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final Map<String, DocumentParser> parsers = new HashMap<>();
    private final Path uploadRoot;

    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(2,
                    r -> { Thread t = new Thread(r, "doc-pipe"); t.setDaemon(true); return t; });

    public DocumentService(DocumentMapper docs, DocumentChunkMapper chunks, KbAccessService access,
                           VectorStore vectorStore,
                           com.kb.document.parser.OcrService ocrService,
                           org.springframework.data.redis.core.StringRedisTemplate redis,
                           List<DocumentParser> parserList,
                           @Value("${kb.doc.upload-dir}") String uploadDir) {
        this.docs = docs;
        this.chunks = chunks;
        this.access = access;
        this.vectorStore = vectorStore;
        this.ocrService = ocrService;
        this.redis = redis;
        for (DocumentParser p : parserList) {
            for (String ext : p.supportedType().split("\\|")) parsers.put(ext, p);
        }
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try { Files.createDirectories(uploadRoot); } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + uploadDir);
        }
    }

    public Document upload(User me, Long kbId, MultipartFile file) {
        KnowledgeBase kb = access.require(kbId, me, KbAccessService.Perm.EDIT);
        if (file.isEmpty()) throw BizException.of(BizCode.FILE_TYPE_NOT_ALLOWED, "空文件");
        String filename = Path.of(file.getOriginalFilename() == null ? "noname" : file.getOriginalFilename())
                .getFileName().toString();
        filename = filename.replace("..", "").replace("/", "_").replace("\\", "_");

        // 落盘
        Path dir = uploadRoot.resolve(String.valueOf(kb.getId()));
        Path target = dir.resolve(System.currentTimeMillis() + "_" + filename);
        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException e) {
            throw BizException.of(BizCode.INTERNAL_ERROR, "保存文件失败");
        }

        String ext = FileValidator.validate(filename, file.getSize(), target);
        String sha = sha256(target);
        // 秒传去重：同库同 hash 的未删除文档
        Document dup = docs.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kb.getId()).eq(Document::getSha256, sha)
                .ne(Document::getStatus, DocStatus.FAILED.name()).last("limit 1"));
        if (dup != null) {
            try { Files.deleteIfExists(target); } catch (IOException ignore) {}
            log.info("dedupe hit doc={} kb={}", dup.getId(), kb.getId());
            return dup;
        }

        Document d = new Document();
        d.setKbId(kb.getId());
        d.setUploaderId(me.getId());
        d.setFilename(filename);
        d.setFilePath(target.toString());
        d.setFileType(ext);
        d.setFileSize(file.getSize());
        d.setSha256(sha);
        d.setStatus(DocStatus.UPLOADED.name());
        d.setChunkCount(0);
        d.setTokens(0);
        d.setDeleted(0);
        docs.insert(d);

        final Long docId = d.getId();
        pool.submit(() -> process(docId));
        return d;
    }

    /** 完整管道：解析->切片入库->向量化->READY；任何异常记 FAILED+原因。 */
    void process(Long docId) {
        Document d = docs.selectById(docId);
        if (d == null) return;
        try {
            moveTo(d, DocStatus.PARSING, null);
            DocumentParser parser = parsers.get(d.getFileType());
            if (parser == null) throw new ParserException("不支持的类型: " + d.getFileType());
            ParsedDoc parsed = parser.parse(Path.of(d.getFilePath()));
            // M6 OCR：扫描版 PDF（解析器标记 needsOcr）→ 视觉模型逐页识别补齐文本。
            // 策略放管道而非解析器：解析器保持"格式→文本"单一职责，是否花 OCR 成本由此处决策。
            if (parsed.isNeedsOcr()) {
                log.info("doc {} needs OCR ({} pages)", d.getId(), parsed.getPageCount());
                parsed = ocrService.ocr(Path.of(d.getFilePath()), parsed);
            }

            moveTo(d, DocStatus.CHUNKED, null);
            KnowledgeBase kb = access.loadExisting(d.getKbId());
            ChunkSplitter splitter = new ChunkSplitter(kb.getChunkSize(), kb.getChunkOverlap());
            List<ChunkSplitter.Chunk> cs = splitter.split(parsed);
            List<DocumentChunk> rows = new ArrayList<>();
            int totalTokens = 0;
            for (ChunkSplitter.Chunk c : cs) {
                DocumentChunk row = new DocumentChunk();
                row.setDocId(d.getId());
                row.setKbId(d.getKbId());
                row.setSeqNo(c.seqNo());
                row.setContent(c.text());
                row.setTitlePath(c.titlePath());
                row.setPageNo(c.pageNo());
                row.setTokenCount(c.tokenEstimate());
                row.setCreatedAt(java.time.LocalDateTime.now());
                chunks.insert(row);
                rows.add(row);
                totalTokens += c.tokenEstimate();
            }

            moveTo(d, DocStatus.EMBEDDING, null);
            List<VectorStore.ChunkRow> vrows = rows.stream()
                    .map(r -> new VectorStore.ChunkRow(r.getId(), r.getKbId(), r.getDocId(), r.getContent()))
                    .toList();
            for (int i = 0; i < vrows.size(); i += 10) {
                vectorStore.upsert(vrows.subList(i, Math.min(i + 10, vrows.size())));
                redisProgress(d.getId(), i, vrows.size());
            }

            d.setChunkCount(rows.size());
            d.setTokens(totalTokens);
            moveTo(d, DocStatus.READY, null);
            log.info("doc {} READY chunks={} tokens={}", docId, rows.size(), totalTokens);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (reason.length() > 480) reason = reason.substring(0, 480);
            moveTo(d, DocStatus.FAILED, reason);
            log.warn("doc {} FAILED: {}", docId, reason);
        }
    }

    /**
     * 同步重跑单文档完整管道（ReindexService 重索引用——它自己已是后台线程，这里要串行可控）。
     * <h3>为什么先删旧 chunk 再跑</h3>
     * process() 会重新插入切片行；旧行不删会翻倍，且旧 vector_id 悬空。
     * 向量侧由调用方(ReindexService.deleteByKbIds)整库清过，这里只清 MySQL 行。
     * 状态复位 UPLOADED 保证状态机 UPLOADED→PARSING→… 合法推进（类内 moveTo 拒绝非法迁移）。
     */
    public void reprocess(Long docId) {
        Document d = docs.selectById(docId);
        if (d == null) return;
        chunks.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocId, docId));
        d.setStatus(DocStatus.UPLOADED.name());
        d.setChunkCount(0);
        d.setTokens(0);
        docs.updateById(d);
        process(docId);
    }

    public void retry(User me, Long docId) {
        Document d = docs.selectById(docId);
        if (d == null) throw BizException.of(BizCode.NOT_FOUND, "文档不存在");
        access.require(d.getKbId(), me, KbAccessService.Perm.EDIT);
        if (!DocStatus.FAILED.name().equals(d.getStatus()))
            throw BizException.of(BizCode.DOC_NOT_READY, "仅失败文档可重试");
        moveTo(d, DocStatus.PARSING, null);
        pool.submit(() -> process(docId));
    }

    public void delete(User me, Long docId) {
        Document d = docs.selectById(docId);
        if (d == null) return;
        access.require(d.getKbId(), me, KbAccessService.Perm.EDIT);
        vectorStore.deleteByDocIds(List.of(docId));
        chunks.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocId, docId));
        docs.deleteById(d.getId()); // @TableLogic 软删
        try { Files.deleteIfExists(Path.of(d.getFilePath())); } catch (IOException ignore) {}
    }

    public List<Document> listByKb(User me, Long kbId) {
        access.require(kbId, me, KbAccessService.Perm.READ);
        return docs.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId).orderByDesc(Document::getId));
    }

    /** 状态+进度（Redis 里的 embedding 进度）。 */
    public Map<String, Object> status(User me, Long docId) {
        Document d = docs.selectById(docId);
        if (d == null) throw BizException.of(BizCode.NOT_FOUND, "文档不存在");
        access.require(d.getKbId(), me, KbAccessService.Perm.READ);
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.getId());
        m.put("status", d.getStatus());
        m.put("errorMsg", d.getErrorMsg());
        m.put("chunkCount", d.getChunkCount());
        m.put("progress", progress(d.getId()));
        return m;
    }

    private void moveTo(Document d, DocStatus next, String errorMsg) {
        DocStatus cur = DocStatus.valueOf(d.getStatus());
        if (cur == next) {   // 重试入口先 PARSING 再 process，允许幂等重入
            d.setErrorMsg(errorMsg);
            docs.updateById(d);
            return;
        }
        if (!cur.canTransitionTo(next)) {
            log.warn("illegal transition {} -> {} doc={}", cur, next, d.getId());
            return;
        }
        d.setStatus(next.name());
        d.setErrorMsg(errorMsg);
        docs.updateById(d);
    }

    // Redis 进度
    private void redisProgress(Long docId, int done, int total) {
        redis.opsForValue().set("doc:prog:" + docId, done + "/" + total,
                java.time.Duration.ofMinutes(10));
    }
    private Object progress(Long docId) {
        String v = redis.opsForValue().get("doc:prog:" + docId);
        if (v == null) return null;
        String[] p = v.split("/");
        Map<String, Object> m = new HashMap<>();
        m.put("done", Integer.parseInt(p[0]));
        m.put("total", Integer.parseInt(p[1]));
        return m;
    }

    private static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
        } catch (Exception e) {
            throw BizException.of(BizCode.INTERNAL_ERROR, "校验和计算失败");
        }
    }
}
