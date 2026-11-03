package com.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 入伙申请（M10.5）：非成员对 SHARED 库可申请，EDIT 成员审批。 */
@Data
@TableName("kb_requests")
public class KbRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private Long userId;
    private String state;   // PENDING|APPROVED|REJECTED
    private Long decidedBy;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
}
