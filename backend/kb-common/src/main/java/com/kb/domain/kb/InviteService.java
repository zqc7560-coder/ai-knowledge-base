package com.kb.domain.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.KbInvite;
import com.kb.domain.entity.KbRequest;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.KbInviteMapper;
import com.kb.domain.mapper.KbRequestMapper;
import com.kb.domain.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 邀请链接 + 入伙申请（M10.5，仿语雀协作入口，替代"owner 必须先知道对方用户名"）。
 *
 * <h3>邀请链接的安全模型</h3>
 * token=SecureRandom 12 字节 hex（24 位，不可枚举）；三重约束：过期时间(默认7天,上限30天)、
 * 使用次数(默认1)、可撤销。接受时原子占坑（UPDATE used=used+1 WHERE used<max_uses）——
 * 并发双击不会双发授权。EDIT 者才能生成/撤销（access.require EDIT 统一裁决，与成员管理同构）。
 * 权限档位由生成者指定（READ/ASK/EDIT），grantDirect 复用成员表，不引入第二套权限体系。
 *
 * <h3>申请-审批</h3>
 * 非成员对任意库可发申请（能搜到库名才谈得上申请；PUBLIC 库无需申请——直接可看可问，
 * 因此 apply 对 PUBLIC 拒绝，避免噪音申请）。EDIT 成员审批；通过后按库默认档 READ 授权
 * （审批人在通过时可改档位）。同人对同库仅一条申请记录（唯一键 upsert 语义：被拒后可重发=复用行）。
 */
@Slf4j
@Service
public class InviteService {

    private final KbInviteMapper invites;
    private final KbRequestMapper requests;
    private final UserMapper users;
    private final KbAccessService access;
    private final KbService kbService;
    private static final SecureRandom RNG = new SecureRandom();

    public InviteService(KbInviteMapper invites, KbRequestMapper requests, UserMapper users,
                         KbAccessService access, KbService kbService) {
        this.invites = invites;
        this.requests = requests;
        this.users = users;
        this.access = access;
        this.kbService = kbService;
    }

    // ---------- 邀请链接 ----------

    public KbInvite createInvite(User me, Long kbId, String permission, Integer maxUses, Integer days) {
        access.require(kbId, me, KbAccessService.Perm.EDIT);
        if ("PRIVATE".equals(access.loadExisting(kbId).getVisibility()))
            throw BizException.of(BizCode.PARAM_INVALID, "私有知识库无法邀请成员（接受后仍不可见），请先设为 SHARED");
        if (permission != null && !List.of("READ", "ASK", "EDIT").contains(permission))
            throw BizException.of(BizCode.PARAM_INVALID, "邀请档位仅 READ/ASK/EDIT");
        byte[] rnd = new byte[12];
        RNG.nextBytes(rnd);
        StringBuilder tk = new StringBuilder();
        for (byte b : rnd) tk.append(String.format("%02x", b));
        KbInvite inv = new KbInvite();
        inv.setKbId(kbId);
        inv.setToken(tk.toString());
        inv.setPermission(permission == null ? "READ" : permission);
        inv.setMaxUses(maxUses == null || maxUses < 1 || maxUses > 1000 ? 1 : maxUses);
        inv.setUsed(0);
        int d = days == null || days < 1 ? 7 : Math.min(days, 30);
        inv.setExpiresAt(LocalDateTime.now().plusDays(d));
        inv.setCreatedBy(me.getId());
        inv.setRevoked(0);
        invites.insert(inv);
        return inv;
    }

    public List<KbInvite> listInvites(User me, Long kbId) {
        access.require(kbId, me, KbAccessService.Perm.EDIT);
        return invites.selectList(new LambdaQueryWrapper<KbInvite>()
                .eq(KbInvite::getKbId, kbId).orderByDesc(KbInvite::getId).last("limit 50"));
    }

    public void revokeInvite(User me, Long inviteId) {
        KbInvite inv = invites.selectById(inviteId);
        if (inv == null) throw BizException.of(BizCode.NOT_FOUND, "邀请不存在");
        access.require(inv.getKbId(), me, KbAccessService.Perm.EDIT);
        inv.setRevoked(1);
        invites.updateById(inv);
    }

    /** 预览：持有效 token 的人（未登录也可）能看到"将被加入哪个库、拿什么档"。不回显库内内容。 */
    public Map<String, Object> previewInvite(String token) {
        KbInvite inv = loadValid(token);
        var kb = access.loadExisting(inv.getKbId());
        return Map.of("kbName", kb.getName(), "permission", inv.getPermission(),
                "expiresAt", String.valueOf(inv.getExpiresAt()));
    }

    /** 接受邀请：登录用户 → 原子占坑 → 授权。 */
    public void acceptInvite(User me, String token) {
        KbInvite inv = loadValid(token);
        int hit = invites.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbInvite>()
                .eq(KbInvite::getId, inv.getId())
                .lt(KbInvite::getUsed, inv.getMaxUses())
                .setSql("used = used + 1"));
        if (hit == 0) throw BizException.of(BizCode.RATE_LIMITED, "邀请链接使用次数已用完");
        if (me.getId().equals(inv.getCreatedBy()))
            throw BizException.of(BizCode.PARAM_INVALID, "生成者本人无需接受");
        kbService.grantDirect(inv.getKbId(), me.getId(), inv.getPermission());
        log.info("invite {} accepted by user {}", inv.getId(), me.getId());
    }

    private KbInvite loadValid(String token) {
        if (token == null || !token.matches("^[0-9a-f]{24}$"))
            throw BizException.of(BizCode.PARAM_INVALID, "邀请链接无效");
        KbInvite inv = invites.selectOne(new LambdaQueryWrapper<KbInvite>().eq(KbInvite::getToken, token));
        if (inv == null || inv.getRevoked() == 1 || inv.getExpiresAt().isBefore(LocalDateTime.now()))
            throw BizException.of(BizCode.NOT_FOUND, "邀请链接无效或已过期");
        return inv;
    }

    // ---------- 入伙申请 ----------

    public KbRequest apply(User me, Long kbId) {
        var kb = access.loadExisting(kbId);
        if ("PUBLIC".equals(kb.getVisibility()))
            throw BizException.of(BizCode.PARAM_INVALID, "公开库无需申请，直接可用");
        if (kb.getOwnerId().equals(me.getId()))
            throw BizException.of(BizCode.PARAM_INVALID, "你是所有者");
        if (access.canAccess(kb, me, KbAccessService.Perm.READ))
            throw BizException.of(BizCode.PARAM_INVALID, "你已可访问该库");
        KbRequest exist = requests.selectOne(new LambdaQueryWrapper<KbRequest>()
                .eq(KbRequest::getKbId, kbId).eq(KbRequest::getUserId, me.getId()));
        if (exist != null && "PENDING".equals(exist.getState()))
            throw BizException.of(BizCode.PARAM_INVALID, "已有待审批申请");
        if (exist != null) {   // 被拒后重发：复用行
            exist.setState("PENDING"); exist.setDecidedBy(null); exist.setDecidedAt(null);
            requests.updateById(exist);
            return exist;
        }
        KbRequest r = new KbRequest();
        r.setKbId(kbId); r.setUserId(me.getId()); r.setState("PENDING");
        requests.insert(r);
        return r;
    }

    public List<Map<String, Object>> pendingRequests(User me, Long kbId) {
        access.require(kbId, me, KbAccessService.Perm.EDIT);
        List<KbRequest> rs = requests.selectList(new LambdaQueryWrapper<KbRequest>()
                .eq(KbRequest::getKbId, kbId).eq(KbRequest::getState, "PENDING").orderByDesc(KbRequest::getId));
        if (rs.isEmpty()) return List.of();
        var uids = rs.stream().map(KbRequest::getUserId).distinct().toList();
        var nameMap = new java.util.HashMap<Long, String>();
        users.selectBatchIds(uids).forEach(u -> nameMap.put(u.getId(), u.getUsername()));
        return rs.stream().map(r -> Map.<String, Object>of(
                "id", r.getId(), "userId", r.getUserId(),
                "username", nameMap.getOrDefault(r.getUserId(), "?"),
                "createdAt", String.valueOf(r.getCreatedAt()))).toList();
    }

    public void decide(User me, Long requestId, boolean approve, String permission) {
        KbRequest r = requests.selectById(requestId);
        if (r == null) throw BizException.of(BizCode.NOT_FOUND, "申请不存在");
        access.require(r.getKbId(), me, KbAccessService.Perm.EDIT);
        r.setState(approve ? "APPROVED" : "REJECTED");
        r.setDecidedBy(me.getId());
        r.setDecidedAt(LocalDateTime.now());
        requests.updateById(r);
        if (approve) kbService.grantDirect(r.getKbId(), r.getUserId(), permission == null ? "READ" : permission);
    }
}
