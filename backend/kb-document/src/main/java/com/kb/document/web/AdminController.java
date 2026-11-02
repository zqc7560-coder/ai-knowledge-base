package com.kb.document.web;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.Result;
import com.kb.domain.entity.Conversation;
import com.kb.domain.entity.Document;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.ConversationMapper;
import com.kb.domain.mapper.DocumentMapper;
import com.kb.domain.mapper.KnowledgeBaseMapper;
import com.kb.domain.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理端（docs/01 F8）：统计只读 + 用户治理写操作。
 *
 * <h3>权限：类级 @SaCheckRole("ADMIN")</h3>
 * Sa-Token 在拦截层校验角色，非管理员访问本类任何端点直接 403->业务码 2002；
 * 不在每个方法里手写 if(role!=ADMIN) 是防"漏检一个端点"的最稳做法（注解对类生效=默认拒绝）。
 *
 * <h3>为什么统计直查 SQL 而不用 ORM</h3>
 * 报表是"一次聚合多指标"场景，逐表 selectCount 会产生 6+ 次查询；
 * queryForList + GROUP BY 一次拿全，口径也最贴近 DBA 视角便于核对。
 *
 * <h3>出参安全</h3>
 * 用户列表强制置空 password_hash（即便将来 VO 忘了裁剪，数据层已消毒——纵深防御）。
 */
@SaCheckRole("ADMIN")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserMapper users;
    private final KnowledgeBaseMapper kbs;
    private final DocumentMapper docs;
    private final ConversationMapper convs;
    private final JdbcTemplate mysql;
    private final StringRedisTemplate redis;

    public AdminController(UserMapper users, KnowledgeBaseMapper kbs, DocumentMapper docs,
                           ConversationMapper convs, JdbcTemplate mysql, StringRedisTemplate redis) {
        this.users = users;
        this.kbs = kbs;
        this.docs = docs;
        this.convs = convs;
        this.mysql = mysql;
        this.redis = redis;
    }

    /** 概览卡：总量四指标 + 文档状态分布（供首页仪表盘一次拉取）。 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", users.selectCount(null));
        m.put("knowledgeBases", kbs.selectCount(new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getDeleted, 0)));
        m.put("documents", docs.selectCount(new LambdaQueryWrapper<Document>().eq(Document::getDeleted, 0)));
        m.put("conversations", convs.selectCount(new LambdaQueryWrapper<Conversation>().eq(Conversation::getDeleted, 0)));
        m.put("chunks", mysql.queryForObject("SELECT count(*) FROM document_chunks", Long.class));
        m.put("docsByStatus", mysql.queryForList(
                "SELECT status, count(*) n FROM documents WHERE deleted=0 GROUP BY status"));
        return Result.ok(m);
    }

    /**
     * AI 用量：按天读 Redis hash（键 usage:{yyyy-MM-dd}，字段 u{uid}:ask，见 RagService.usageBump）。
     * Redis 里是当日实时计数；后续如需历史持久化再落 ai_usage_daily 表（M5 预留）。
     */
    @GetMapping("/usage")
    public Result<Map<Object, Object>> usage(@RequestParam(required = false) String date) {
        String key = "usage:" + (date == null || date.isBlank() ? java.time.LocalDate.now() : date);
        return Result.ok(redis.opsForHash().entries(key));
    }

    /** 今日剩余配额视角（管理页展示用）：已用次数按用户。 */
    @GetMapping("/users")
    public Result<List<User>> userList() {
        List<User> list = users.selectList(new LambdaQueryWrapper<User>().orderByDesc(User::getId));
        list.forEach(u -> u.setPasswordHash(null));
        return Result.ok(list);
    }

    /** 禁用/启用账号：status=0 时登录被拒（2004），已登录会话由 Sa-Token 下次校验时拒绝。 */
    @PostMapping("/users/{id}/status")
    public Result<Void> setUserStatus(@PathVariable Long id, @RequestParam int status) {
        User u = new User();
        u.setId(id);
        u.setStatus(status == 0 ? 0 : 1);
        users.updateById(u);
        return Result.ok(null);
    }

    /** 角色升降：仅 USER/ADMIN 两值（三级权限是库级概念，全局角色保持简单）。 */
    @PostMapping("/users/{id}/role")
    public Result<Void> setRole(@PathVariable Long id, @RequestParam String role) {
        if (!List.of("USER", "ADMIN").contains(role)) return Result.fail(1001, "角色仅 USER/ADMIN");
        User u = new User();
        u.setId(id);
        u.setRole(role);
        users.updateById(u);
        return Result.ok(null);
    }
}
