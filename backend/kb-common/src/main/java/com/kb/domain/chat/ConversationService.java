package com.kb.domain.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.Conversation;
import com.kb.domain.entity.Message;
import com.kb.domain.entity.User;
import com.kb.domain.kb.KbAccessService;
import com.kb.domain.mapper.ConversationMapper;
import com.kb.domain.mapper.MessageMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/** 会话与消息管理（docs/01 F7）：会话绑定多库(kbIds CSV)，消息带引用/用量。 */
@Service
public class ConversationService {

    private final ConversationMapper convs;
    private final MessageMapper msgs;
    private final KbAccessService access;

    public ConversationService(ConversationMapper convs, MessageMapper msgs, KbAccessService access) {
        this.convs = convs;
        this.msgs = msgs;
        this.access = access;
    }

    public Conversation create(User me, List<Long> kbIds, String title) {
        if (kbIds == null || kbIds.isEmpty())
            throw BizException.of(BizCode.PARAM_INVALID, "请至少选择一个知识库");
        for (Long id : kbIds) access.require(id, me, KbAccessService.Perm.ASK); // 问答权限才能建会话
        Conversation c = new Conversation();
        c.setUserId(me.getId());
        c.setKbIds(join(kbIds));
        c.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
        c.setPinned(0);
        c.setDeleted(0);
        convs.insert(c);
        return c;
    }

    public List<Conversation> my(User me) {
        return convs.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, me.getId())
                .orderByDesc(Conversation::getPinned).orderByDesc(Conversation::getUpdatedAt));
    }

    /** 仅按 id 取会话（分享公开视图用：凭证=token 已证明授权，不再叠加用户校验）。不存在抛 1002。 */
    public Conversation loadExisting(Long convId) {
        Conversation c = convs.selectById(convId);
        if (c == null) throw BizException.of(BizCode.NOT_FOUND, "会话不存在");
        return c;
    }

    public Conversation loadOwned(User me, Long convId) {
        Conversation c = convs.selectById(convId);
        if (c == null || !c.getUserId().equals(me.getId()))
            throw BizException.of(BizCode.NOT_FOUND, "会话不存在");
        // 会话存续期间库可能被删/撤权：每次问答重新校验 ASK
        for (Long id : split(c.getKbIds())) access.require(id, me, KbAccessService.Perm.ASK);
        return c;
    }

    public List<Message> history(User me, Long convId, int limit) {
        loadOwned(me, convId);
        List<Message> list = msgs.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, convId).orderByDesc(Message::getId).last("limit " + Math.min(limit, 200)));
        list = list.stream().sorted((a, b) -> Long.compare(a.getId(), b.getId())).toList();
        return list;
    }

    /**
     * 会话内消息搜索（M10.2a）：关键词 LIKE 命中，返回 ≤50 条带 ±上下文片段的结果。
     * 只做本会话内检索（loadOwned 校验归属），不跨库不全文——定位是"翻旧对话"，
     * 不是知识库检索（那是 RagService 的职责，边界清晰）。
     */
    public List<java.util.Map<String, Object>> search(User me, Long convId, String kw) {
        loadOwned(me, convId);
        if (kw == null || kw.isBlank() || kw.length() > 100)
            throw BizException.of(BizCode.PARAM_INVALID, "关键词需 1-100 字符");
        // LIKE 通配符转义：%/_ 按字面量处理（先转义再交给 mybatis-plus 的 like 参数绑定，无注入面）
        String esc = kw.replace("\\", "").replace("%", "\\%").replace("_", "\\_");
        List<Message> list = msgs.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, convId)
                .like(Message::getContent, esc)
                .orderByAsc(Message::getId).last("limit 50"));
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        String plain = kw.replace("\\", "").replace("%", "").replace("_", "");
        for (Message m : list) {
            String c = m.getContent();
            int i = c.indexOf(plain);
            int lo = Math.max(0, i - 30), hi = Math.min(c.length(), i + plain.length() + 70);
            out.add(java.util.Map.of("id", m.getId(), "role", m.getRole(),
                    "snippet", (lo > 0 ? "…" : "") + c.substring(lo, hi) + (hi < c.length() ? "…" : ""),
                    "createdAt", String.valueOf(m.getCreatedAt())));
        }
        return out;
    }

    /**
     * 编辑重问（M10.2b）：删除该条 user 问题【及其后全部消息】，并把改写后的问题文本回给前端重问。
     *
     * <h3>为什么"连自己一起删"而不是"改内容+删后面"</h3>
     * 首版实现是改内容保留该行，前端随后再发一次提问 → 服务端历史里出现两条同文 user
     * （专项实测 n=3 暴露）。正确语义="回退到这一轮并重新提问"：本轮整体作废，由 /ask
     * 产生干净的 user+assistant 一对。因此这里删 id>=msgId 全部，只回传新文本。
     * 级联删的必要性同旧版注释：不能留着引用旧问题的答案污染多轮上下文。
     */
    public Message editQuestionAndTruncate(User me, Long msgId, String newContent) {
        if (newContent == null || newContent.isBlank() || newContent.length() > 2000)
            throw BizException.of(BizCode.PARAM_INVALID, "问题需 1-2000 字符");
        Message m = msgs.selectById(msgId);
        if (m == null) throw BizException.of(BizCode.NOT_FOUND, "消息不存在");
        loadOwned(me, m.getConversationId());
        if (!"user".equals(m.getRole()))
            throw BizException.of(BizCode.PARAM_INVALID, "仅可编辑本人提问");
        msgs.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, m.getConversationId())
                .ge(Message::getId, msgId));       // 含自身
        m.setContent(newContent.trim());           // 仅回传用（库中该行已删）
        return m;
    }

    public void rename(User me, Long convId, String title) {
        Conversation c = convs.selectById(convId);
        if (c == null || !c.getUserId().equals(me.getId()))
            throw BizException.of(BizCode.NOT_FOUND, "会话不存在");
        c.setTitle(title);
        convs.updateById(c);
    }

    public void pin(User me, Long convId, boolean pinned) {
        Conversation c = convs.selectById(convId);
        if (c == null || !c.getUserId().equals(me.getId()))
            throw BizException.of(BizCode.NOT_FOUND, "会话不存在");
        c.setPinned(pinned ? 1 : 0);
        convs.updateById(c);
    }

    public void delete(User me, Long convId) {
        Conversation c = convs.selectById(convId);
        if (c == null || !c.getUserId().equals(me.getId()))
            throw BizException.of(BizCode.NOT_FOUND, "会话不存在");
        msgs.delete(new LambdaQueryWrapper<Message>().eq(Message::getConversationId, convId));
        convs.deleteById(convId);
    }

    public Message append(Long convId, String role, String content, String citeChunkIds,
                          Integer promptTokens, Integer completionTokens, Integer elapsedMs, String model) {
        Message m = new Message();
        m.setConversationId(convId);
        m.setRole(role);
        m.setContent(content);
        m.setCiteChunkIds(citeChunkIds);
        m.setPromptTokens(promptTokens == null ? 0 : promptTokens);
        m.setCompletionTokens(completionTokens == null ? 0 : completionTokens);
        m.setElapsedMs(elapsedMs == null ? 0 : elapsedMs);
        m.setModel(model);
        msgs.insert(m);
        // touch updated_at
        Conversation c = convs.selectById(convId);
        if (c != null) convs.updateById(c);
        return m;
    }

    public static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
    }

    public static List<Long> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::parseLong).toList();
    }
}
