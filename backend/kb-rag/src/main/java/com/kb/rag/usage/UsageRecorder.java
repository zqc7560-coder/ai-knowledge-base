package com.kb.rag.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.domain.entity.AiUsageDaily;
import com.kb.domain.mapper.AiUsageDailyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 用量落库（M7.2 兑现 docs/00 待办"ai_usage_daily 建而未用"）。
 *
 * <h3>口径：归因给"触发者"，而非执行线程</h3>
 * embedding/rerank/vision 发生在文档处理线程（无登录上下文），归因给上传者——
 * 由调用方在提交任务前用 {@link #bindOwner} 记明（kbId→userId，内存映射足够，
 * 进程重启后在途文档丢失重新排队会重新绑定）。chat 发生在问答线程，直接取当前用户。
 * 没绑定到主人的库，用量记 userId=0（系统行），查询侧展示"未知"。
 *
 * <h3>并发</h3>
 * 写入是"一天一行累加"：INSERT ... 唯一键冲突则 UPDATE。这里用应用层双保险——
 * 同 (user,date) 的行锁在 JVM 内用 stripe 锁串行化，跨进程场景单机部署不存在；
 * 若将来多实例，需换成 SQL `ON DUPLICATE KEY UPDATE col=col+?`。已在注释划线。
 *
 * <h3>失败语义</h3>
 * 用量统计绝不能影响业务：record() 全程 try-catch 吞错只记 warn。
 */
@Slf4j
@Service
public class UsageRecorder {

    private final AiUsageDailyMapper usage;
    private final com.kb.domain.mapper.KnowledgeBaseMapper kbs;
    private final com.kb.rag.ai.AiClientFactory aiFactory;
    /** kbId→库主 短 TTL 缓存：省掉批量 embedding 时的重复单行查询；重启后回源 DB，不会归因错。 */
    private final ConcurrentHashMap<Long, long[]> kbOwnerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Object> stripes = new ConcurrentHashMap<>();

    public UsageRecorder(AiUsageDailyMapper usage, com.kb.domain.mapper.KnowledgeBaseMapper kbs,
                         com.kb.rag.ai.AiClientFactory aiFactory) {
        this.usage = usage;
        this.kbs = kbs;
        this.aiFactory = aiFactory;
    }

    /** 工厂侧零依赖 recorder（setter 晚绑定），这里装配。 */
    @jakarta.annotation.PostConstruct
    public void wire() { aiFactory.setUsageRecorder(this); }

    public Long ownerOf(Long kbId) {
        if (kbId == null) return 0L;
        long[] hit = kbOwnerCache.get(kbId);
        if (hit != null && System.currentTimeMillis() - hit[1] < 300_000) return hit[0];
        Long owner = 0L;
        try {
            var kb = kbs.selectById(kbId);
            if (kb != null && kb.getOwnerId() != null) owner = kb.getOwnerId();
        } catch (Exception ignore) { /* 库已删 → 记系统行 */ }
        kbOwnerCache.put(kbId, new long[]{owner, System.currentTimeMillis()});
        return owner;
    }

    /** chat：prompt+completion 都记（问答/改写共享一次调用口径）。 */
    public void chat(long userId, long promptTokens, long completionTokens) {
        record(userId, "chat", promptTokens + completionTokens, promptTokens, completionTokens, 0, 0);
    }

    /** embedding：批量后 token 归触发者。 */
    public void embedding(long kbId, long totalTokens) {
        record(ownerOf(kbId), "emb", totalTokens, totalTokens, 0, totalTokens, 0);
    }

    public void rerank(long userId, long totalTokens) {
        record(userId, "rerank", totalTokens, totalTokens, 0, 0, totalTokens);
    }

    public void vision(long kbId, long totalTokens) {
        record(ownerOf(kbId), "vision", totalTokens, totalTokens, 0, 0, 0);
    }

    private void record(long userId, String kind, long total, long pt, long ct, long emb, long rr) {
        try {
            LocalDate today = LocalDate.now();
            Object lock = stripes.computeIfAbsent((int) (Math.abs(userId) % 16), i -> new Object());
            synchronized (lock) {
                AiUsageDaily row = usage.selectOne(new LambdaQueryWrapper<AiUsageDaily>()
                        .eq(AiUsageDaily::getUserId, userId).eq(AiUsageDaily::getStatDate, today));
                boolean isNew = row == null;
                if (isNew) {
                    row = new AiUsageDaily();
                    row.setUserId(userId); row.setStatDate(today);
                    row.setEmbCalls(0); row.setChatCalls(0); row.setRerankCalls(0);
                    row.setTotalTokens(0L); row.setPromptTokens(0L); row.setCompletionTokens(0L);
                    row.setEmbTokens(0L); row.setRerankTokens(0L); row.setVisionCalls(0); row.setVisionTokens(0L);
                }
                switch (kind) {
                    case "chat"   -> row.setChatCalls(row.getChatCalls() + 1);
                    case "emb"    -> row.setEmbCalls(row.getEmbCalls() + 1);
                    case "rerank" -> row.setRerankCalls(row.getRerankCalls() + 1);
                    case "vision" -> row.setVisionCalls(row.getVisionCalls() + 1);
                }
                row.setTotalTokens(row.getTotalTokens() + total);
                row.setPromptTokens(row.getPromptTokens() + pt);
                row.setCompletionTokens(row.getCompletionTokens() + ct);
                row.setEmbTokens(row.getEmbTokens() + emb);
                row.setRerankTokens(row.getRerankTokens() + rr);
                if (kind.equals("vision")) row.setVisionTokens(row.getVisionTokens() + total);
                if (isNew) usage.insert(row); else usage.updateById(row);
            }
        } catch (Exception e) {
            log.warn("usage record failed user={} kind={} err={}", userId, kind, e.getClass().getSimpleName());
        }
    }

    /** 月报（管理页成本视图用）：本月所有行 + 用户名单在控制器拼。 */
    public List<AiUsageDaily> monthRows(int year, int month) {
        LocalDate s = LocalDate.of(year, month, 1);
        LocalDate e = s.plusMonths(1);
        return usage.selectList(new LambdaQueryWrapper<AiUsageDaily>()
                .ge(AiUsageDaily::getStatDate, s).lt(AiUsageDaily::getStatDate, e));
    }
}
