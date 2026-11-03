package com.kb.domain.audit;

import com.kb.domain.entity.AuditLog;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 审计日志（M11）：敏感管理动作落库——删库/成员变更/角色升降/禁用账号/分享创建/密钥创建等。
 *
 * 设计取舍（docs/06 定的口径）：
 * - **记录绝不影响业务**：全程 try-catch 吞错只 warn（与 UsageRecorder 同一纪律——
 *   旁路观测写失败不能让主操作回滚或报错）。
 * - **IP 取 X-Forwarded-For 优先**：nginx 反代后 remoteAddr 恒为容器网 IP，
 *   生产形态真实来源在 XFF（nginx.conf 已透传）；开发直连时回退 remoteAddr。
 *   注意 XFF 可被直连后端的调用方伪造——本系统生产入口只有 nginx，可信度够用；
 *   若要更强，nginx 应覆盖而非追加 XFF（docs/08 部署后话）。
 * - username 冗余存储：用户注销/删除后审计仍可读（合规诉求就是"查得到历史"）。
 */
@Slf4j
@Service
public class AuditService {

    private final AuditLogMapper logs;

    public AuditService(AuditLogMapper logs) {
        this.logs = logs;
    }

    public void record(User me, String action, String target, String detail) {
        try {
            AuditLog a = new AuditLog();
            if (me != null) { a.setUserId(me.getId()); a.setUsername(me.getUsername()); }
            a.setIp(currentIp());
            a.setAction(action);
            a.setTarget(truncate(target, 160));
            a.setDetail(truncate(detail, 500));
            a.setCreatedAt(LocalDateTime.now());
            logs.insert(a);
        } catch (Exception e) {
            log.warn("audit record failed action={} err={}", action, e.getClass().getSimpleName());
        }
    }

    private static String currentIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;           // 非请求线程（如启动任务）
            var req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (Exception ignore) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
