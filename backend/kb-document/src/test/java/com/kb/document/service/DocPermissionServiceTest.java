package com.kb.document.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.DocPermission;
import com.kb.domain.entity.Document;
import com.kb.domain.entity.KbMember;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.User;
import com.kb.domain.kb.KbAccessService;
import com.kb.domain.mapper.DocPermissionMapper;
import com.kb.domain.mapper.DocumentMapper;
import com.kb.domain.mapper.KbMemberMapper;
import com.kb.domain.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 文档级细粒度权限判定单测（M12.4 续）。
 * 真实 KbAccessService（用 mock mapper 构造）参与判定——测的是"服务组合"而非孤立桩，
 * 权限回归只能在这条链上发生。
 */
class DocPermissionServiceTest {

    DocumentMapper docs;
    DocPermissionMapper perms;
    KnowledgeBaseMapper kbs;
    KbMemberMapper members;
    KbAccessService access;
    DocPermissionService svc;

    static {
        var ba = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(ba, Document.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(ba, DocPermission.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(ba, KnowledgeBase.class);
    }

    @BeforeEach
    void setUp() {
        docs = mock(DocumentMapper.class);
        perms = mock(DocPermissionMapper.class);
        kbs = mock(KnowledgeBaseMapper.class);
        members = mock(KbMemberMapper.class);
        access = new KbAccessService(kbs, members);
        svc = new DocPermissionService(docs, perms, access);
    }

    private User u(Long id) { User x = new User(); x.setId(id); return x; }
    private Document d(Long id, Long kbId, Integer restricted) {
        Document x = new Document(); x.setId(id); x.setKbId(kbId); x.setRestricted(restricted); x.setDeleted(0);
        return x;
    }
    private KnowledgeBase kb(Long id, Long ownerId, String vis) {
        KnowledgeBase k = new KnowledgeBase(); k.setId(id); k.setOwnerId(ownerId); k.setVisibility(vis);
        return k;
    }
    private DocPermission dp(Long docId, Long userId) {
        DocPermission p = new DocPermission(); p.setDocId(docId); p.setUserId(userId); p.setPerm("READ"); return p;
    }

    @Test
    @DisplayName("未受限文档全员可见（restricted=0 或 null 都不设门槛；null 为旧行防御）")
    void unrestrictedAlwaysVisible() {
        assertThat(svc.canSee(u(9L), d(1L, 2L, 0))).isTrue();
        assertThat(svc.canSee(u(9L), d(1L, 2L, null))).isTrue();   // 迁移前旧行缺列值
    }

    @Test
    @DisplayName("受限 + owner 恒可见")
    void ownerSeesAll() {
        when(kbs.selectById(2L)).thenReturn(kb(2L, 100L, "SHARED"));
        assertThat(svc.canSee(u(100L), d(1L, 2L, 1))).isTrue();
    }

    @Test
    @DisplayName("受限 + 库 EDIT 成员可见（EDIT 者可管权限，当然能看）")
    void editMemberSees() {
        when(kbs.selectById(2L)).thenReturn(kb(2L, 100L, "SHARED"));
        KbMember m = new KbMember(); m.setUserId(200L); m.setPermission("EDIT");
        when(members.selectOne(any(Wrapper.class))).thenReturn(m);
        assertThat(svc.canSee(u(200L), d(1L, 2L, 1))).isTrue();
    }

    @Test
    @DisplayName("受限 + 非owner非EDIT：白名单是唯一放行通道")
    void whitelistIsOnlyGate() {
        when(kbs.selectById(2L)).thenReturn(kb(2L, 100L, "SHARED"));
        KbMember m = new KbMember(); m.setUserId(200L); m.setPermission("ASK");   // ASK 不是 EDIT
        when(members.selectOne(any(Wrapper.class))).thenReturn(m);
        // 无白名单行 → 不可见
        when(perms.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThat(svc.canSee(u(200L), d(1L, 2L, 1))).isFalse();
        // 有白名单行 → 可见
        when(perms.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThat(svc.canSee(u(200L), d(1L, 2L, 1))).isTrue();
    }

    @Test
    @DisplayName("hiddenDocIds：READ 成员看不到受限文档，白名单精确到单文档")
    void hiddenSetPrecise() {
        when(kbs.selectById(2L)).thenReturn(kb(2L, 100L, "SHARED"));
        KbMember m = new KbMember(); m.setUserId(200L); m.setPermission("READ");
        when(members.selectOne(any(Wrapper.class))).thenReturn(m);
        when(docs.selectList(any(Wrapper.class))).thenReturn(List.of(d(10L, 2L, 1), d(11L, 2L, 1), d(12L, 2L, 1)));
        when(perms.selectList(any(Wrapper.class))).thenReturn(List.of(dp(11L, 200L)));  // 只放行 11
        var hidden = svc.hiddenDocIds(u(200L), List.of(2L));
        assertThat(hidden).containsExactlyInAnyOrder(10L, 12L);
    }

    @Test
    @DisplayName("hiddenDocIds：owner/EDIT 特权 → 空集（全可见，且根本不发白名单查询）")
    void privilegedSkipsWhitelistQuery() {
        when(kbs.selectById(2L)).thenReturn(kb(2L, 100L, "SHARED"));
        when(docs.selectList(any(Wrapper.class))).thenReturn(List.of(d(10L, 2L, 1)));
        assertThat(svc.hiddenDocIds(u(100L), List.of(2L))).isEmpty();     // owner
        verify(perms, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("setRestricted/grant/revoke 都要求库 EDIT——READ 成员想开权限=3002")
    void adminOpsNeedEditPerm() {
        when(kbs.selectById(2L)).thenReturn(kb(2L, 100L, "SHARED"));
        when(docs.selectById(1L)).thenReturn(d(1L, 2L, 0));
        KbMember m = new KbMember(); m.setUserId(200L); m.setPermission("READ");
        when(members.selectOne(any(Wrapper.class))).thenReturn(m);
        assertThatThrownBy(() -> svc.setRestricted(u(200L), 1L, true))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(BizCode.KB_NO_PERMISSION);
        assertThatThrownBy(() -> svc.grant(u(200L), 1L, 300L, "READ"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(BizCode.KB_NO_PERMISSION);
        assertThatThrownBy(() -> svc.revoke(u(200L), 1L, 300L))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(BizCode.KB_NO_PERMISSION);
    }

    @Test
    @DisplayName("文档级白名单只有 READ/ASK——EDIT 档位拒绝（越界授权=参数错）")
    void docWhitelistOnlyReadAsk() {
        when(kbs.selectById(2L)).thenReturn(kb(2L, 100L, "SHARED"));
        when(docs.selectById(1L)).thenReturn(d(1L, 2L, 0));
        assertThatThrownBy(() -> svc.grant(u(100L), 1L, 300L, "EDIT"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(BizCode.PARAM_INVALID);
    }
}
