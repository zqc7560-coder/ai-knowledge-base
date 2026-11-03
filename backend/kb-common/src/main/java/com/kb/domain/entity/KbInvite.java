package com.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识库邀请链接（M10.5）。token 是不可枚举的 SecureRandom hex；过期/限次/可撤销三重约束。 */
@Data
@TableName("kb_invites")
public class KbInvite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String token;
    private String permission;
    private Integer maxUses;
    private Integer used;
    private LocalDateTime expiresAt;
    private Long createdBy;
    private Integer revoked;
    private LocalDateTime createdAt;
}
