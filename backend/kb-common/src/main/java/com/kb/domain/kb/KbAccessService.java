package com.kb.domain.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.KbMember;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.mapper.KbMemberMapper;
import com.kb.domain.mapper.KnowledgeBaseMapper;
import com.kb.domain.entity.User;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 知识库权限判定核心——全系统唯一的"能不能操作这个库"裁决点。
 * 所有文档上传、问答、配置修改都必须先过这里（调用 {@link #require}），
 * 保证权限模型只实现一份、不会散落在各 Service 里出现口径不一。
 *
 * <h3>三级权限模型（为什么是 READ &lt; ASK &lt; EDIT 线性阶梯）</h3>
 * <ul>
 *   <li><b>READ</b>：能浏览库详情/文档列表——给"旁观者"，比如同事只需要知道有哪些资料。</li>
 *   <li><b>ASK</b>：READ + 能对该库提问（问答会返回原文片段，所以必须比 READ 高一级授权）。</li>
 *   <li><b>EDIT</b>：ASK + 上传/删除文档、改配置、授权他人——知识库管理员级。</li>
 * </ul>
 * 线性阶梯的好处：判定只需 rank 数字比较（见 {@link #canAccess}），新增级别也无需改判定逻辑。
 *
 * <h3>三种可见性（visibility）</h3>
 * <ul>
 *   <li>PRIVATE：仅 owner 可见（即便有残留成员记录也不对外展示，见 KbService.update 的
 *       可见性切换注释）。</li>
 *   <li>SHARED：owner + kb_members 显式授权者可见，权限按成员的 READ/ASK/EDIT 阶梯判定——
 *       团队协作的主模式。</li>
 *   <li>PUBLIC：所有登录用户可 READ/ASK（用于演示库/全员资料库），
 *       但 EDIT 永远不随 PUBLIC 放开——只能由 owner 或管理员经 update() 设置该状态。</li>
 * </ul>
 *
 * <h3>为什么返回 3002 而不是 403 状态码</h3>
 * 系统统一响应信封（docs/01 §5）：HTTP 层 200 + 业务 code 表达"无权限"，
 * 前端 axios 拦截器按 code 弹提示；401（登录失效）走 HTTP 401/2001 特判跳登录页。
 */
@Service
public class KbAccessService {
    public enum Perm { READ, ASK, EDIT }

    private final KnowledgeBaseMapper kbs;
    private final KbMemberMapper members;

    public KbAccessService(KnowledgeBaseMapper kbs, KbMemberMapper members) {
        this.kbs = kbs;
        this.members = members;
    }

    public KnowledgeBase loadExisting(Long kbId) {
        KnowledgeBase kb = kbs.selectById(kbId);
        if (kb == null) throw BizException.of(BizCode.KB_NOT_FOUND, "知识库不存在");
        return kb;
    }

    public boolean canAccess(KnowledgeBase kb, User user, Perm need) {
        if (kb.getOwnerId().equals(user.getId())) return true;
        if ("PUBLIC".equals(kb.getVisibility()) && need != Perm.EDIT) return true;
        KbMember m = members.selectOne(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kb.getId()).eq(KbMember::getUserId, user.getId()));
        if (m == null) return false;
        return rank(m.getPermission()) >= need.ordinal() + 1;
    }

    public KnowledgeBase require(Long kbId, User user, Perm need) {
        KnowledgeBase kb = loadExisting(kbId);
        if (!canAccess(kb, user, need)) throw BizException.of(BizCode.KB_NO_PERMISSION, "没有该操作权限");
        return kb;
    }

    /** 用户可见的全部库（owner ∪ PUBLIC ∪ 被授权）。 */
    public List<KnowledgeBase> visibleTo(User user) {
        List<Long> memberKbIds = members.selectList(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getUserId, user.getId())).stream().map(KbMember::getKbId).toList();
        LambdaQueryWrapper<KnowledgeBase> q = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getOwnerId, user.getId());
        q.or(x -> x.eq(KnowledgeBase::getVisibility, "PUBLIC"));
        if (!memberKbIds.isEmpty()) q.or(y -> y.in(KnowledgeBase::getId, memberKbIds));
        return kbs.selectList(q.orderByDesc(KnowledgeBase::getUpdatedAt));
    }

    public static int rank(String p) {
        return switch (p) { case "READ" -> 1; case "ASK" -> 2; case "EDIT" -> 3; default -> 0; };
    }
}
