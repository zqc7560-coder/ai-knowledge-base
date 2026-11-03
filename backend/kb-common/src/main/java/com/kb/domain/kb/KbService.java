package com.kb.domain.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.KbMember;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.KbMemberMapper;
import com.kb.domain.mapper.KnowledgeBaseMapper;
import com.kb.domain.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
public class KbService {
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "SHARED", "PUBLIC");
    private static final Set<String> PERMISSIONS = Set.of("READ", "ASK", "EDIT");

    private final KnowledgeBaseMapper kbs;
    private final KbMemberMapper members;
    private final UserMapper users;
    private final KbAccessService access;

    public KbService(KnowledgeBaseMapper kbs, KbMemberMapper members, UserMapper users, KbAccessService access) {
        this.kbs = kbs;
        this.members = members;
        this.users = users;
        this.access = access;
    }

    public record CreateCmd(String name, String description, String visibility,
                            Integer chunkSize, Integer chunkOverlap, Integer topK, BigDecimal scoreThreshold,
                            Boolean parentChild, Integer pcSpan,
                            Boolean hyde, Boolean multiQuery, String grp, String tags) {}

    public KnowledgeBase create(User me, CreateCmd cmd) {
        if (cmd.name() == null || cmd.name().isBlank() || cmd.name().length() > 100)
            throw BizException.of(BizCode.PARAM_INVALID, "知识库名称需1-100字符");
        if (cmd.visibility() != null && !VISIBILITIES.contains(cmd.visibility()))
            throw BizException.of(BizCode.PARAM_INVALID, "可见性仅支持 PRIVATE/SHARED/PUBLIC");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setOwnerId(me.getId());
        kb.setName(cmd.name().trim());
        kb.setDescription(cmd.description());
        kb.setVisibility(cmd.visibility() == null ? "PRIVATE" : cmd.visibility());
        kb.setChunkSize(clamp(cmd.chunkSize(), 100, 4000, 500));
        kb.setChunkOverlap(clamp(cmd.chunkOverlap(), 0, 500, 100));
        kb.setTopK(clamp(cmd.topK(), 1, 20, 5));
        kb.setScoreThreshold(cmd.scoreThreshold() == null ? new BigDecimal("0.250") : cmd.scoreThreshold());
        kb.setEmbeddingModel("text-embedding-v3");
        kb.setParentChild(Boolean.TRUE.equals(cmd.parentChild()) ? 1 : 0);
        kb.setPcSpan(clamp(cmd.pcSpan(), 1, 5, 2));
        kb.setHyde(Boolean.TRUE.equals(cmd.hyde()) ? 1 : 0);
        kb.setMultiQuery(Boolean.TRUE.equals(cmd.multiQuery()) ? 1 : 0);
        kb.setGrp(sanitize(cmd.grp(), 40));
        kb.setTags(sanitize(cmd.tags(), 200));
        kb.setDeleted(0);
        kbs.insert(kb);
        return kb;
    }

    /** 带权限的加载（READ 即可见——update 前取旧值用）。 */
    public KnowledgeBase loadForUser(User me, Long kbId) {
        var kb = access.loadExisting(kbId);
        access.require(kbId, me, KbAccessService.Perm.READ);
        return kb;
    }

    public KnowledgeBase update(User me, Long kbId, CreateCmd cmd) {
        KnowledgeBase kb = access.require(kbId, me, KbAccessService.Perm.EDIT);
        if (cmd.name() != null && !cmd.name().isBlank()) kb.setName(cmd.name().trim());
        if (cmd.description() != null) kb.setDescription(cmd.description());
        if (cmd.visibility() != null) {
            if (!VISIBILITIES.contains(cmd.visibility()))
                throw BizException.of(BizCode.PARAM_INVALID, "可见性仅支持 PRIVATE/SHARED/PUBLIC");
            if ("PUBLIC".equals(cmd.visibility()) && !"ADMIN".equals(me.getRole()))
                throw BizException.of(BizCode.KB_NO_PERMISSION, "PUBLIC 可见性仅管理员可设置");
            kb.setVisibility(cmd.visibility());
        }
        if (cmd.chunkSize() != null) kb.setChunkSize(clamp(cmd.chunkSize(), 100, 4000, kb.getChunkSize()));
        if (cmd.chunkOverlap() != null) kb.setChunkOverlap(clamp(cmd.chunkOverlap(), 0, 500, kb.getChunkOverlap()));
        if (cmd.topK() != null) kb.setTopK(clamp(cmd.topK(), 1, 20, kb.getTopK()));
        if (cmd.scoreThreshold() != null) kb.setScoreThreshold(cmd.scoreThreshold());
        if (cmd.parentChild() != null) kb.setParentChild(cmd.parentChild() ? 1 : 0);
        if (cmd.pcSpan() != null) kb.setPcSpan(clamp(cmd.pcSpan(), 1, 5, kb.getPcSpan() == null ? 2 : kb.getPcSpan()));
        if (cmd.hyde() != null) kb.setHyde(cmd.hyde() ? 1 : 0);
        if (cmd.multiQuery() != null) kb.setMultiQuery(cmd.multiQuery() ? 1 : 0);
        if (cmd.grp() != null) kb.setGrp(sanitize(cmd.grp(), 40));
        if (cmd.tags() != null) kb.setTags(sanitize(cmd.tags(), 200));
        kbs.updateById(kb);
        return kb;
    }

    public void delete(User me, Long kbId) {
        access.require(kbId, me, KbAccessService.Perm.EDIT);
        kbs.deleteById(kbId); // @TableLogic 软删；向量物理清理由文档管道负责（M2）
    }

    public List<KnowledgeBase> myVisible(User me) {
        return access.visibleTo(me);
    }

    /** 底层授权（M10.5 复用）：跳过"操作者权限"校验——调用方已各自完成鉴权（接受邀请=持token，审批=EDIT者）。 */
    public void grantDirect(Long kbId, Long userId, String permission) {
        if (!PERMISSIONS.contains(permission))
            throw BizException.of(BizCode.PARAM_INVALID, "授权仅支持 READ/ASK/EDIT");
        KbMember exist = members.selectOne(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId).eq(KbMember::getUserId, userId));
        if (exist != null) {
            // 已是成员：只升不降（邀请/审批不该把既有高权限顶掉）
            if (KbAccessService.rank(permission) > KbAccessService.rank(exist.getPermission())) {
                exist.setPermission(permission);
                members.updateById(exist);
            }
        } else {
            KbMember m = new KbMember();
            m.setKbId(kbId);
            m.setUserId(userId);
            m.setPermission(permission);
            members.insert(m);
        }
    }

    public void grantMember(User me, Long kbId, String username, String permission) {
        access.require(kbId, me, KbAccessService.Perm.EDIT);
        if (!PERMISSIONS.contains(permission))
            throw BizException.of(BizCode.PARAM_INVALID, "授权仅支持 READ/ASK/EDIT");
        User target = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (target == null) throw BizException.of(BizCode.NOT_FOUND, "用户不存在");
        if (target.getId().equals(me.getId()))
            throw BizException.of(BizCode.PARAM_INVALID, "无需给自己授权");
        KbMember exist = members.selectOne(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId).eq(KbMember::getUserId, target.getId()));
        if (exist != null) {
            exist.setPermission(permission);
            members.updateById(exist);
        } else {
            KbMember m = new KbMember();
            m.setKbId(kbId);
            m.setUserId(target.getId());
            m.setPermission(permission);
            members.insert(m);
        }
    }

    public void revokeMember(User me, Long kbId, Long userId) {
        access.require(kbId, me, KbAccessService.Perm.EDIT);
        members.delete(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId).eq(KbMember::getUserId, userId));
    }

    public List<KbMember> listMembers(User me, Long kbId) {
        access.require(kbId, me, KbAccessService.Perm.READ);
        return members.selectList(new LambdaQueryWrapper<KbMember>().eq(KbMember::getKbId, kbId));
    }

    /** 自由文本字段消毒（M10.4）：去控制字符、限长、空串存 null（展示层不再区分空/未设置）。 */
    static String sanitize(String v, int max) {
        if (v == null) return null;
        String s = v.replaceAll("\\p{Cntrl}", "").trim();
        return s.isEmpty() ? null : (s.length() > max ? s.substring(0, max) : s);
    }

    private static int clamp(Integer v, int min, int max, int def) {
        if (v == null) return def;
        if (v < min || v > max) throw BizException.of(BizCode.PARAM_INVALID, "参数范围 " + min + "-" + max);
        return v;
    }
}
