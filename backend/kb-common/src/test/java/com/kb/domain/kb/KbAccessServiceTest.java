package com.kb.domain.kb;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.KbMember;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.KbMemberMapper;
import com.kb.domain.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.kb.domain.kb.KbAccessService.Perm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 权限裁决矩阵单测（M12.4）——全系统"能不能"的唯一裁决点，逐格钉死。
 *
 * 覆盖重点：
 * 1) owner 恒全权；
 * 2) **PRIVATE 无视成员行**（回归防线：可见性 SHARED→PRIVATE 切换不清成员行，
 *    若判定顺序退化为"先查成员"，残留行=越权读——真实数据里存在 39 条这类残留行）；
 * 3) PUBLIC 放开 READ/ASK 但 **永不放开 EDIT**；
 * 4) READ<ASK<EDIT 阶梯：够格放行、不够格拒绝；
 * 5) 未知权限串 rank=0（前向兼容：新增级别不会误放旧数据）。
 *
 * mock 粒度：只 mock 两个 mapper（canAccess 不碰别处），测试跑在无数据库环境（CI 友好）。
 */
class KbAccessServiceTest {

    // 脱离 Spring 用 LambdaQueryWrapper 检视需手动灌 TableInfo 缓存（getSqlSegment 惰性解析列名）
    static {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                KbMember.class);
    }

    KnowledgeBaseMapper kbs;
    KbMemberMapper members;
    KbAccessService svc;

    @BeforeEach
    void setUp() {
        kbs = mock(KnowledgeBaseMapper.class);
        members = mock(KbMemberMapper.class);
        svc = new KbAccessService(kbs, members);
    }

    private KnowledgeBase kb(Long id, Long ownerId, String vis) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id); k.setOwnerId(ownerId); k.setVisibility(vis);
        return k;
    }
    private User u(Long id) { User x = new User(); x.setId(id); return x; }
    private KbMember mem(Long userId, String perm) {
        KbMember m = new KbMember(); m.setUserId(userId); m.setPermission(perm); return m;
    }

    @Test
    @DisplayName("owner 恒全权：三种可见性 × 三档需求")
    void ownerAlwaysPasses() {
        for (String vis : new String[]{"PRIVATE", "SHARED", "PUBLIC"}) {
            KnowledgeBase k = kb(1L, 100L, vis);
            for (Perm p : Perm.values())
                assertThat(svc.canAccess(k, u(100L), p)).as("owner / " + vis + " / " + p).isTrue();
        }
        // owner 路径根本不查成员表（性能与旁路证明）
        verifyNoInteractions(members);
    }

    @Test
    @DisplayName("PRIVATE 无视成员行：残留 ASK/EDIT 行也必须拒绝，且不落到成员查询")
    void privateIgnoresMemberRows() {
        KnowledgeBase k = kb(2L, 100L, "PRIVATE");
        // 即便 mock 会返回 EDIT 成员行——正确实现下它不该被查询
        when(members.selectOne(any(Wrapper.class))).thenReturn(mem(200L, "EDIT"));
        for (Perm p : Perm.values())
            assertThat(svc.canAccess(k, u(200L), p)).as("PRIVATE 非owner / " + p).isFalse();
        verify(members, never()).selectOne(any(Wrapper.class));   // 钉死判定顺序：PRIVATE 绝断先于成员查询
    }

    @Test
    @DisplayName("PUBLIC：全员 READ/ASK，EDIT 永不随 PUBLIC 放开")
    void publicNeverGrantsEdit() {
        KnowledgeBase k = kb(3L, 100L, "PUBLIC");
        assertThat(svc.canAccess(k, u(200L), Perm.READ)).isTrue();
        assertThat(svc.canAccess(k, u(200L), Perm.ASK)).isTrue();
        assertThat(svc.canAccess(k, u(200L), Perm.EDIT)).isFalse();  // 无成员行 → 拒
        // PUBLIC+EDIT成员行 → 可 EDIT（库主显式授权者，放开的是行不是 PUBLIC 本身）
        when(members.selectOne(any(Wrapper.class))).thenReturn(mem(200L, "EDIT"));
        assertThat(svc.canAccess(k, u(200L), Perm.EDIT)).isTrue();
    }

    @Test
    @DisplayName("SHARED 阶梯：READ<ASK<EDIT，够格放行不够格拒绝")
    void sharedLadder() {
        KnowledgeBase k = kb(4L, 100L, "SHARED");
        assertThat(svc.canAccess(k, u(200L), Perm.READ)).as("无授权").isFalse();

        when(members.selectOne(any(Wrapper.class))).thenReturn(mem(200L, "READ"));
        assertThat(svc.canAccess(k, u(200L), Perm.READ)).isTrue();
        assertThat(svc.canAccess(k, u(200L), Perm.ASK)).isFalse();
        assertThat(svc.canAccess(k, u(200L), Perm.EDIT)).isFalse();

        when(members.selectOne(any(Wrapper.class))).thenReturn(mem(200L, "ASK"));
        assertThat(svc.canAccess(k, u(200L), Perm.READ)).isTrue();   // 高权可低用
        assertThat(svc.canAccess(k, u(200L), Perm.ASK)).isTrue();
        assertThat(svc.canAccess(k, u(200L), Perm.EDIT)).isFalse();

        when(members.selectOne(any(Wrapper.class))).thenReturn(mem(200L, "EDIT"));
        for (Perm p : Perm.values()) assertThat(svc.canAccess(k, u(200L), p)).isTrue();
    }

    @Test
    @DisplayName("未知权限串 rank=0：旧数据/脏值不误放，正常值1/2/3")
    void rankForwardCompatible() {
        assertThat(KbAccessService.rank("READ")).isEqualTo(1);
        assertThat(KbAccessService.rank("ASK")).isEqualTo(2);
        assertThat(KbAccessService.rank("EDIT")).isEqualTo(3);
        assertThat(KbAccessService.rank("OWNER")).isZero();
        assertThat(KbAccessService.rank(null)).isZero();
    }

    @Test
    @DisplayName("require：拒绝时抛 3002（业务码），不存在时抛 KB_NOT_FOUND")
    void requireThrowsBizCodes() {
        when(kbs.selectById(9L)).thenReturn(kb(9L, 100L, "SHARED"));
        when(members.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> svc.require(9L, u(200L), Perm.ASK))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(BizCode.KB_NO_PERMISSION);   // 3002

        when(kbs.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> svc.require(99L, u(200L), Perm.READ))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(BizCode.KB_NOT_FOUND);
    }

    @Test
    @DisplayName("成员查询按 (kbId,userId) 双条件定位（防跨库串权）")
    void memberLookupScopedToKb() {
        when(members.selectOne(any(Wrapper.class))).thenReturn(null);
        svc.canAccess(kb(7L, 100L, "SHARED"), u(200L), Perm.READ);
        ArgumentCaptor<Wrapper> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(members).selectOne(cap.capture());
        String sql = cap.getValue().getSqlSegment() + " " +
                ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) cap.getValue()).getParamNameValuePairs();
        assertThat(sql).contains("kb_id").contains("user_id");
    }

    @Test
    @DisplayName("visibleTo：被授权分支必须带 visibility≠PRIVATE（列表口径与 canAccess 一致，防元数据泄漏）")
    void visibleToExcludesPrivateFromMemberBranch() {
        when(members.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(mem(200L, "READ")));
        when(kbs.selectList(any(Wrapper.class))).thenReturn(java.util.List.of());
        svc.visibleTo(u(200L));
        ArgumentCaptor<Wrapper> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(kbs).selectList(cap.capture());
        var w = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) cap.getValue();
        String sql = w.getSqlSegment();
        String params = String.valueOf(w.getParamNameValuePairs());
        assertThat(sql).contains("visibility");           // 成员分支带了 visibility 条件
        assertThat(params).contains("PRIVATE");           // 值=排除 PRIVATE
        assertThat(sql).containsAnyOf("<>", "!=");        // ne() 渲染为 <>
    }

    static {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                KnowledgeBase.class);
    }
}
