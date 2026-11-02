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

    public DocumentController(DocumentService docs, AuthService auth) {
        this.docs = docs;
        this.auth = auth;
    }

    @PostMapping("/kb/{kbId}/documents")
    public Result<Document> upload(@PathVariable Long kbId, @RequestParam("file") MultipartFile file) {
        return Result.ok(docs.upload(auth.currentUser(), kbId, file));
    }

    @GetMapping("/documents")
    public Result<List<Document>> list(@RequestParam Long kbId) {
        return Result.ok(docs.listByKb(auth.currentUser(), kbId));
    }

    @GetMapping("/documents/{id}/status")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        return Result.ok(docs.status(auth.currentUser(), id));
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
