package com.kb.document.web;

import com.kb.common.api.Result;
import com.kb.document.service.DocumentService;
import com.kb.domain.entity.Document;
import com.kb.security.auth.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService docs;
    private final AuthService auth;
    private final com.kb.document.service.ChunkAdminService chunkAdmin;
    private final com.kb.document.service.UrlIngestService urlFetcher;

    public DocumentController(DocumentService docs, AuthService auth,
                              com.kb.document.service.ChunkAdminService chunkAdmin,
                              com.kb.document.service.UrlIngestService urlFetcher) {
        this.docs = docs;
        this.auth = auth;
        this.chunkAdmin = chunkAdmin;
        this.urlFetcher = urlFetcher;
    }

    @PostMapping("/kb/{kbId}/documents")
    public Result<Document> upload(@PathVariable Long kbId, @RequestParam("file") MultipartFile file,
                                   @RequestParam(name = "replace", defaultValue = "false") boolean replaceByName) {
        return Result.ok(docs.upload(auth.currentUser(), kbId, file, replaceByName));
    }

    // M9.6 URL 抓正文入库（SSRF 三防线见 UrlIngestService）
    @PostMapping("/kb/{kbId}/ingest-url")
    public Result<com.kb.domain.entity.Document> ingestUrl(@PathVariable Long kbId,
            @RequestBody java.util.Map<String, String> body) {
        return Result.ok(docs.ingestUrl(auth.currentUser(), kbId, body.get("url"), urlFetcher));
    }

    @GetMapping("/documents")
    public Result<List<Document>> list(@RequestParam Long kbId) {
        return Result.ok(docs.listByKb(auth.currentUser(), kbId));
    }

    @GetMapping("/documents/{id}/status")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        return Result.ok(docs.status(auth.currentUser(), id));
    }

    // ---------- M9.4 切片人工干预 ----------
    @GetMapping("/documents/{docId}/chunks")
    public Result<java.util.List<com.kb.domain.entity.DocumentChunk>> chunksOf(@PathVariable Long docId,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(chunkAdmin.list(auth.currentUser(), docId, limit));
    }

    @PutMapping("/chunks/{chunkId}")
    public Result<com.kb.domain.entity.DocumentChunk> editChunk(@PathVariable Long chunkId,
            @RequestBody java.util.Map<String, String> body) {
        return Result.ok(chunkAdmin.edit(auth.currentUser(), chunkId, body.get("content")));
    }

    @PostMapping("/chunks/{chunkId}/toggle")
    public Result<com.kb.domain.entity.DocumentChunk> toggleChunk(@PathVariable Long chunkId) {
        return Result.ok(chunkAdmin.toggle(auth.currentUser(), chunkId));
    }

    @PostMapping("/documents/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        docs.retry(auth.currentUser(), id);
        return Result.ok(null);
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        docs.delete(auth.currentUser(), id);
        return Result.ok(null);
    }
}
