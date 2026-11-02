package com.kb.document.web;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.kb.common.api.Result;
import com.kb.domain.entity.AiUsageDaily;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.UserMapper;
import com.kb.rag.usage.UsageRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务历史 + AI 用量成本月报（M7.1/M7.2）。ADMIN 专属（类级注解）。
 *
 * <h3>成本口径（如实）</h3>
 * 单价为 DashScope 2024–2025 公开牌价的近似常量（元/千 token），非账单实时价：
 * qwen-plus 输入0.0008/输出0.002、embedding 0.0005、rerank 0.0008、vl-ocr 0.02/0.02。
 * 目的是"量级可见"辅助配额决策，不是财务对账——所以字段命名 estimated，且改价只动这里。
 */
@SaCheckRole("ADMIN")
@RestController
@RequestMapping("/api/admin")
public class AdminTaskController {

    private static final BigDecimal CHAT_IN = new BigDecimal("0.0008");
    private static final BigDecimal CHAT_OUT = new BigDecimal("0.002");
    private static final BigDecimal EMB = new BigDecimal("0.0005");
    private static final BigDecimal RERANK = new BigDecimal("0.0008");
    private static final BigDecimal VISION = new BigDecimal("0.02");

    private final UsageRecorder usage;
    private final com.kb.document.service.ReindexService reindex;
    private final UserMapper users;

    public AdminTaskController(UsageRecorder usage, com.kb.document.service.ReindexService reindex, UserMapper users) {
        this.usage = usage;
        this.reindex = reindex;
        this.users = users;
    }

    @GetMapping("/tasks")
    public Result<List<com.kb.domain.entity.ProcessTask>> tasks() {
        return Result.ok(reindex.history());
    }

    /** 月报：按用户聚合 token 与估算成本（牌价近似），附全局汇总。 */
    @GetMapping("/usage-month")
    public Result<Map<String, Object>> usageMonth(@RequestParam(required = false) Integer month) {
        LocalDate base = month == null ? LocalDate.now() : LocalDate.now().withDayOfMonth(1).minusMonths(month - 1);
        List<AiUsageDaily> rows = usage.monthRows(base.getYear(), base.getMonthValue());
        Map<Long, String> names = new LinkedHashMap<>();
        for (User u : users.selectList(null)) names.put(u.getId(), u.getUsername());

        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        BigDecimal grand = BigDecimal.ZERO;
        long gTok = 0;
        for (AiUsageDaily r : rows) {
            BigDecimal cost = BigDecimal.valueOf(r.getPromptTokens()).multiply(CHAT_IN)
                    .add(BigDecimal.valueOf(r.getCompletionTokens()).multiply(CHAT_OUT))
                    .add(BigDecimal.valueOf(r.getEmbTokens()).multiply(EMB))
                    .add(BigDecimal.valueOf(r.getRerankTokens()).multiply(RERANK))
                    .add(BigDecimal.valueOf(r.getVisionTokens()).multiply(VISION))
                    .setScale(4, RoundingMode.HALF_UP);
            gTok += r.getTotalTokens() == null ? 0 : r.getTotalTokens();
            grand = grand.add(cost);
            byUser.merge(r.getUserId(), new LinkedHashMap<>(Map.of(
                    "user", names.getOrDefault(r.getUserId(), "已注销/系统"),
                    "chatCalls", r.getChatCalls(), "embCalls", r.getEmbCalls(),
                    "rerankCalls", r.getRerankCalls(), "visionCalls", r.getVisionCalls(),
                    "tokens", r.getTotalTokens(), "costYuan", cost
            )), (a, b) -> {
                a.merge("chatCalls", b.get("chatCalls"), (x, y) -> ((Number) x).intValue() + ((Number) y).intValue());
                a.merge("embCalls", b.get("embCalls"), (x, y) -> ((Number) x).intValue() + ((Number) y).intValue());
                a.merge("rerankCalls", b.get("rerankCalls"), (x, y) -> ((Number) x).intValue() + ((Number) y).intValue());
                a.merge("visionCalls", b.get("visionCalls"), (x, y) -> ((Number) x).intValue() + ((Number) y).intValue());
                a.merge("tokens", b.get("tokens"), (x, y) -> ((Number) x).longValue() + ((Number) y).longValue());
                a.merge("costYuan", b.get("costYuan"), (x, y) -> ((BigDecimal) x).add((BigDecimal) y));
                return a;
            });
        }
        List<Map<String, Object>> list = new ArrayList<>(byUser.values());
        list.sort((a, b) -> Long.compare(((Number) b.get("tokens")).longValue(), ((Number) a.get("tokens")).longValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", base.getYear() + "-" + String.format("%02d", base.getMonthValue()));
        out.put("rows", list);
        out.put("totalTokens", gTok);
        out.put("totalCostYuan", grand.setScale(4, RoundingMode.HALF_UP));
        return Result.ok(out);
    }
}
