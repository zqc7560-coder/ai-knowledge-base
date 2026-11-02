package com.kb.app.web;

import com.kb.common.api.Result;
import com.kb.rag.ai.AiClientFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M0 自检端点：/api/ping 活着；/api/admin/ai-check 验证三能力通路。
 * 只回掩码与耗时，绝不回显 key（规范 L5）。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final AiClientFactory ai;
    private final JdbcTemplate mysql;
    private final StringRedisTemplate redis;

    public HealthController(AiClientFactory ai, JdbcTemplate mysql, StringRedisTemplate redis) {
        this.ai = ai;
        this.mysql = mysql;
        this.redis = redis;
    }

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "kb");
        m.put("mysql", exec(() -> mysql.queryForObject("select 1", Integer.class) == 1));
        m.put("redis", exec(() -> "PONG".equalsIgnoreCase(
                redis.execute((org.springframework.data.redis.core.RedisCallback<String>) conn -> conn.ping()))));
        return Result.ok(m);
    }

    /** 真实打一次三类上游调用，返回各自耗时与形状校验结果。 */
    @GetMapping("/admin/ai-check")
    public Result<Map<String, Object>> aiCheck() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyMask", ai.maskedKey());
        long t0 = System.currentTimeMillis();
        m.put("chat", exec(() -> ai.chat(java.util.List.of(Map.of("role", "user", "content", "回复:OK")), 0.0, 8) != null));
        m.put("chatMs", System.currentTimeMillis() - t0);
        long t1 = System.currentTimeMillis();
        m.put("embedding", exec(() -> {
            var v = ai.embed(java.util.List.of("连通性测试"));
            return v.size() == 1 && v.get(0).length == ai.embeddingDim();
        }));
        m.put("embeddingMs", System.currentTimeMillis() - t1);
        long t2 = System.currentTimeMillis();
        m.put("rerank", exec(() -> {
            var r = ai.rerank("什么是RAG", java.util.List.of("RAG是检索增强生成技术", "今天天气不错"), 2);
            return r.get(0).index() == 0 && r.get(0).score() > r.get(1).score();
        }));
        m.put("rerankMs", System.currentTimeMillis() - t2);
        return Result.ok(m);
    }

    private interface Probe { boolean run() throws Exception; }

    private static String exec(Probe p) {
        try { return p.run() ? "ok" : "shape_mismatch"; }
        catch (Exception e) { return "fail:" + e.getClass().getSimpleName(); }
    }
}
