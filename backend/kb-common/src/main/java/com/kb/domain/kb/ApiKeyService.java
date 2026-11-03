package com.kb.domain.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.ApiKey;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.ApiKeyMapper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 开放 API 密钥管理（M10.6）：把本系统当"别人的产品"用的临门一脚——
 * 外部程序 `Authorization: Bearer <key>` 直调 /api/open/ask，只能问该 key 绑定的库。
 *
 * 安全模型（对齐 docs/03 红线，这是"用户自己的 key"，不是 AI 上游 key）：
 * - 明文只在创建瞬间返回一次；库存 sha256，校验时对入参做 hash 查询——
 *   即使 dba/备份泄露库表也拿不到可用凭据。
 * - 每用户上限 10 个（防 key 泛滥难审计）；撤销即时生效（状态位）。
 * - kbScope 是白名单 CSV：请求的库必须全部 ⊆ scope，且 key 主人当前仍有 ASK 权
 *   （双校验：owner 被撤权/删库后，key 自动失效——开放接口不能成为权限绕过通道）。
 * - last_used_at 便于发现僵尸 key；不记录请求内容（问答内容属于用户数据，日志最小化）。
 */
@Service
public class ApiKeyService {

    private final ApiKeyMapper keys;
    private final KbAccessService access;
    private final com.kb.domain.mapper.UserMapper users;
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_KEYS = 10;

    public ApiKeyService(ApiKeyMapper keys, KbAccessService access, com.kb.domain.mapper.UserMapper users) {
        this.keys = keys;
        this.access = access;
        this.users = users;
    }

    /** 创建：返回 {key: 明文一次}。 */
    public Map<String, Object> create(User me, String name, List<Long> kbIds) {
        if (name == null || name.isBlank() || name.length() > 60)
            throw BizException.of(BizCode.PARAM_INVALID, "名称需 1-60 字符");
        if (kbIds == null || kbIds.isEmpty())
            throw BizException.of(BizCode.PARAM_INVALID, "至少绑定一个知识库");
        long cnt = keys.selectCount(new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getUserId, me.getId()));
        if (cnt >= MAX_KEYS) throw BizException.of(BizCode.RATE_LIMITED, "密钥数量已达上限 " + MAX_KEYS);
        for (Long id : kbIds) access.require(id, me, KbAccessService.Perm.ASK);   // 绑定即校验，之后每次调用再校验

        byte[] rnd = new byte[24];
        RNG.nextBytes(rnd);
        String plain = "kb_" + HexFormat.of().formatHex(rnd);   // kb_ + 48 hex = 51 字符；前缀便于识别/扫描器友好
        ApiKey k = new ApiKey();
        k.setUserId(me.getId());
        k.setName(name.trim());
        k.setKeyHash(sha256(plain));
        k.setKbScope(com.kb.domain.chat.ConversationService.join(kbIds));
        k.setRevoked(0);
        keys.insert(k);
        return Map.of("id", k.getId(), "name", k.getName(), "kbScope", k.getKbScope(), "key", plain);
    }

    public List<ApiKey> list(User me) {
        List<ApiKey> ls = keys.selectList(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserId, me.getId()).orderByDesc(ApiKey::getId));
        ls.forEach(k -> k.setKeyHash(null));   // 摘要也不外露，纯元数据
        return ls;
    }

    public void revoke(User me, Long id) {
        ApiKey k = keys.selectById(id);
        if (k == null || !k.getUserId().equals(me.getId()))
            throw BizException.of(BizCode.NOT_FOUND, "密钥不存在");
        k.setRevoked(1);
        keys.updateById(k);
    }

    /** 认证：Bearer 明文 → hash 查活 key → 双权限校验（scope ⊆ 且主人仍有 ASK）。 */
    public ApiKey authenticate(String bearer, List<Long> askedKbIds) {
        if (bearer == null || !bearer.startsWith("kb_")) throw BizException.of(BizCode.KB_NO_PERMISSION, "API 密钥无效");
        ApiKey k = keys.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, sha256(bearer)).eq(ApiKey::getRevoked, 0));
        if (k == null) throw BizException.of(BizCode.KB_NO_PERMISSION, "API 密钥无效或已撤销");
        List<Long> scope = com.kb.domain.chat.ConversationService.split(k.getKbScope());
        if (askedKbIds == null || askedKbIds.isEmpty() || !scope.containsAll(askedKbIds))
            throw BizException.of(BizCode.KB_NO_PERMISSION, "请求的库超出该密钥绑定范围");
        User owner = users.selectById(k.getUserId());   // 主人被禁用/注销则 key 即刻不可用
        if (owner == null || owner.getStatus() == null || owner.getStatus() != 1)
            throw BizException.of(BizCode.KB_NO_PERMISSION, "密钥主人已失效");
        for (Long id : askedKbIds) access.require(id, owner, KbAccessService.Perm.ASK);
        LocalDateTime now = LocalDateTime.now();
        k.setLastUsedAt(now);
        keys.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ApiKey>()
                .eq(ApiKey::getId, k.getId()).set(ApiKey::getLastUsedAt, now));   // 只碰 last_used_at，勿整实体回写
        return k;
    }

    public User ownerOf(ApiKey k) { return users.selectById(k.getUserId()); }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
