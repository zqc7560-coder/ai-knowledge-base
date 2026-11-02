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
 * 知识库权限判定核心（docs/02 M1.4 用例矩阵）：
 * - owner 全权
 * - PUBLIC 库：所有登录用户 READ/ASK，非 owner 无 EDIT
 * - SHARED 库：kb_members 授权决定 READ < ASK < EDIT
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

    private static int rank(String p) {
        return switch (p) { case "READ" -> 1; case "ASK" -> 2; case "EDIT" -> 3; default -> 0; };
    }
}
