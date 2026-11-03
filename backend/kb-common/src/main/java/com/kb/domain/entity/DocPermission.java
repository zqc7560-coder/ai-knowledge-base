package com.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 受限文档的用户白名单行（M10.6）。perm=READ|ASK（文档级不设 EDIT——管理动作恒走库级 EDIT，避免权限体系分叉）。 */
@Data
@TableName("doc_permissions")
public class DocPermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long userId;
    private String perm;
    private Long grantedBy;
    private LocalDateTime createdAt;
}
