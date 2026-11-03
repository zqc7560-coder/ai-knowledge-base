package com.kb.security.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.common.api.Result;
import com.kb.domain.chat.ConversationService;
import com.kb.domain.entity.Conversation;
import com.kb.domain.entity.Message;
import com.kb.domain.entity.ShareLink;
import com.kb.domain.mapper.MessageMapper;
import com.kb.domain.mapper.ShareLinkMapper;
import com.kb.security.auth.AuthService;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 答案反馈（赞/踩）与会话只读分享链接（M6）。
 *
 * <h3>反馈设计</h3>
 * 只存 1/0/-1 到 messages.feedback（同用户改评即覆盖；取消=0）。
 * 为什么这么轻：反馈的目的是给"检索质量差的答案"留可追溯信号（管理页聚合点踩列表），
 * 不是做 RLHF 数据管道——过度设计只会带来表结构负担。
 * 归属校验：只能评自己会话里的消息（loadOwned 复用会话归属判定）。
 *
 * <h3>分享链接安全模型</h3>
 * 分享的是【某会话的历史快照】（只读 GET），不是知识库本身：
 * - token=SecureRandom 8字节 hex（16字符，不可枚举；不用自增 id 防遍历）
 * - 公开端点 /api/public/share/{token} 在 Sa-Token 白名单——无登录仅回消息文本与引用摘要，
 *   不能提问（提问要花 AI 额度且需库权限），也永不泄露 kbIds 之外的内部 id 语义
 * - 撤销=revoke 置位（软删，链接即 404 文案）；创建者唯一（同会话多链接可并存/逐个撤）
 */
@RestController
@RequestMapping("/api")
public class ShareFeedbackController {

    private static final SecureRandom RNG = new SecureRandom();

    private final MessageMapper msgs;
    private final ShareLinkMapper shares;
    private final ConversationService convs;
    private final AuthService auth;
    private final com.kb.domain.audit.AuditService audit;

    public ShareFeedbackController(MessageMapper msgs, ShareLinkMapper shares,
                                   ConversationService convs, AuthService auth,
                                   com.kb.domain.audit.AuditService audit) {
        this.msgs = msgs;
        this.shares = shares;
        this.convs = convs;
        this.auth = auth;
        this.audit = audit;
    }

    /** 赞/踩/取消：body {value: 1|-1|0}。 */
    @PostMapping("/messages/{id}/feedback")
    public Result<Void> feedback(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        var me = auth.currentUser();
        Integer v = body.get("value");
        if (v == null || (v != 1 && v != -1 && v != 0)) throw BizException.of(BizCode.PARAM_INVALID, "value 仅 1/-1/0");
        Message m = msgs.selectById(id);
        if (m == null) throw BizException.of(BizCode.NOT_FOUND, "消息不存在");
        convs.loadOwned(me, m.getConversationId()); // 归属校验（非本人会话 404 语义）
        m.setFeedback(v);
        msgs.updateById(m);
        return Result.ok(null);
    }

    /** 管理页：点踩答案清单（badcase 收集）。ADMIN 专属（注解校验已在 SaTokenConfig 打开）。 */
    @cn.dev33.satoken.annotation.SaCheckRole("ADMIN")
    @GetMapping("/admin/messages/disliked")
    public Result<List<Message>> disliked() {
        return Result.ok(msgs.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getFeedback, -1).orderByDesc(Message::getId).last("limit 100")));
    }

    // ---------- 分享 ----------

    @PostMapping("/chat/conversations/{id}/share")
    public Result<Map<String, Object>> createShare(@PathVariable Long id) {
        var me = auth.currentUser();
        convs.loadOwned(me, id);
        ShareLink sl = new ShareLink();
        byte[] rnd = new byte[8];
        RNG.nextBytes(rnd);
        sl.setToken(HexFormat.of().formatHex(rnd));
        sl.setConversationId(id);
        sl.setCreatedBy(me.getId());
        sl.setRevoked(0);
        shares.insert(sl);
        audit.record(me, "share.create", "conv#" + id, "token=" + sl.getToken());
        return Result.ok(Map.of("token", sl.getToken(), "url", "/share/" + sl.getToken()));
    }

    @DeleteMapping("/chat/conversations/{id}/share/{token}")
    public Result<Void> revokeShare(@PathVariable Long id, @PathVariable String token) {
        var me = auth.currentUser();
        ShareLink sl = shares.selectOne(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getToken, token).eq(ShareLink::getConversationId, id));
        if (sl == null || !sl.getCreatedBy().equals(me.getId()))
            throw BizException.of(BizCode.NOT_FOUND, "分享链接不存在");
        sl.setRevoked(1);
        shares.updateById(sl);
        return Result.ok(null);
    }

    @GetMapping("/chat/conversations/{id}/shares")
    public Result<List<ShareLink>> listShares(@PathVariable Long id) {
        var me = auth.currentUser();
        convs.loadOwned(me, id);
        return Result.ok(shares.selectList(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getConversationId, id).eq(ShareLink::getRevoked, 0)));
    }

    /** 公开只读：凭 token 拉会话标题+消息（无知识库元信息、不可提问、不可翻页到其它会话）。 */
    @GetMapping("/public/share/{token}")
    public Result<Map<String, Object>> publicView(@PathVariable String token) {
        ShareLink sl = shares.selectOne(new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getToken, token));
        if (sl == null || sl.getRevoked() == 1) throw BizException.of(BizCode.NOT_FOUND, "分享链接不存在或已撤销");
        Conversation c = convs.loadExisting(sl.getConversationId());
        List<Message> list = msgs.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, sl.getConversationId()).orderByAsc(Message::getId).last("limit 200"));
        return Result.ok(Map.of(
                "title", c.getTitle(),
                "messages", list.stream().map(m -> Map.of(
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "createdAt", String.valueOf(m.getCreatedAt()))).toList()));
    }
}
