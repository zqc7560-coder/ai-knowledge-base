package com.kb.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.DocPermission;
import com.kb.domain.entity.Document;
import com.kb.domain.entity.User;
import com.kb.domain.kb.KbAccessService;
import com.kb.domain.mapper.DocPermissionMapper;
import com.kb.domain.mapper.DocumentMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档级细粒度权限（M10.6）：库内个别敏感文档对部分成员隐藏。
 *
 * <h3>模型</h3>
 * documents.restricted=1 → 该文档只对这些角色可见：库 owner、对库持 EDIT 的成员
 * （管理者必须看得见一切，否则无法治理）、doc_permissions 白名单用户（READ/ASK）。
 * READ/ASK 档成员若无白名单行：列表不显示、问答检索不命中、直接访问 3002。
 *
 * <h3>为什么"检索后过滤"而不是向量库内隔离</h3>
 * 向量库（pgvector/Milvus）不认识用户——隔离要么按用户建 collection（爆炸），
 * 要么向量 metadata 带 ACL（两实现都要改且难维护）。过滤放在应用层：召回多取一些
 * （wideK 已有 4 倍余量），rerank 前剔除不可见 doc 的 Hit。代价=topK 偶发凑不满
 * （可见文档本来就少时），语义正确优先。默认全库 restricted=0，兼容既有行为。
 */
@Service
public class DocPermissionService {

    private final DocumentMapper docs;
    private final DocPermissionMapper perms;
    private final KbAccessService access;

    public DocPermissionService(DocumentMapper docs, DocPermissionMapper perms, KbAccessService access) {
        this.docs = docs;
        this.perms = perms;
        this.access = access;
    }

    /** 当前用户在这些库中【不可见的受限文档 id 集】（给列表/检索做排除）。 */
    public Set<Long> hiddenDocIds(User me, List<Long> kbIds) {
        Set<Long> hidden = new HashSet<>();
        for (Long kbId : kbIds) {
            var kb = access.loadExisting(kbId);
            boolean privileged = kb.getOwnerId().equals(me.getId())
                    || access.canAccess(kb, me, KbAccessService.Perm.EDIT);
            List<Document> restricted = docs.selectList(new LambdaQueryWrapper<Document>()
                    .eq(Document::getKbId, kbId).eq(Document::getRestricted, 1).eq(Document::getDeleted, 0));
            if (restricted.isEmpty()) continue;
            if (privileged) continue;                       // owner/EDIT 全可见
            Set<Long> allowed = new HashSet<>();
            List<Long> rids = restricted.stream().map(Document::getId).toList();
            perms.selectList(new LambdaQueryWrapper<DocPermission>()
                    .in(DocPermission::getDocId, rids).eq(DocPermission::getUserId, me.getId()))
                    .forEach(p -> allowed.add(p.getDocId()));
            for (Long rid : rids) if (!allowed.contains(rid)) hidden.add(rid);
        }
        return hidden;
    }

    /** 单文档可见性判定（状态查询/详情/引用回放用）。 */
    public boolean canSee(User me, Document d) {
        if (d.getRestricted() == null || d.getRestricted() == 0) return true;
        var kb = access.loadExisting(d.getKbId());
        if (kb.getOwnerId().equals(me.getId()) || access.canAccess(kb, me, KbAccessService.Perm.EDIT)) return true;
        return perms.selectCount(new LambdaQueryWrapper<DocPermission>()
                .eq(DocPermission::getDocId, d.getId()).eq(DocPermission::getUserId, me.getId())) > 0;
    }

    /** 设置受限开关（库 EDIT 权限）。开启时白名单清空重建由前端逐个加。 */
    public void setRestricted(User me, Long docId, boolean restricted) {
        Document d = load(docId);
        access.require(d.getKbId(), me, KbAccessService.Perm.EDIT);
        d.setRestricted(restricted ? 1 : 0);
        docs.updateById(d);
    }

    public List<DocPermission> listPerms(User me, Long docId) {
        Document d = load(docId);
        access.require(d.getKbId(), me, KbAccessService.Perm.EDIT);
        return perms.selectList(new LambdaQueryWrapper<DocPermission>().eq(DocPermission::getDocId, docId));
    }

    /** 加/改白名单（同 doc+user upsert 语义）。 */
    public void grant(User me, Long docId, Long userId, String perm) {
        Document d = load(docId);
        access.require(d.getKbId(), me, KbAccessService.Perm.EDIT);
        if (!List.of("READ", "ASK").contains(perm)) throw BizException.of(BizCode.PARAM_INVALID, "文档级仅 READ/ASK");
        DocPermission exist = perms.selectOne(new LambdaQueryWrapper<DocPermission>()
                .eq(DocPermission::getDocId, docId).eq(DocPermission::getUserId, userId));
        if (exist != null) { exist.setPerm(perm); perms.updateById(exist); }
        else {
            DocPermission p = new DocPermission();
            p.setDocId(docId); p.setUserId(userId); p.setPerm(perm); p.setGrantedBy(me.getId());
            perms.insert(p);
        }
    }

    public void revoke(User me, Long docId, Long userId) {
        Document d = load(docId);
        access.require(d.getKbId(), me, KbAccessService.Perm.EDIT);
        perms.delete(new LambdaQueryWrapper<DocPermission>()
                .eq(DocPermission::getDocId, docId).eq(DocPermission::getUserId, userId));
    }

    private Document load(Long docId) {
        Document d = docs.selectById(docId);
        if (d == null) throw BizException.of(BizCode.NOT_FOUND, "文档不存在");
        return d;
    }
}
