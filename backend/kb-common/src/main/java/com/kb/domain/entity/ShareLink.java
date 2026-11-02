package com.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 表 `share_links`：会话只读分享（M6）。token=随机凭证，撤销用 revoked 标志。 */
@Data
@TableName("share_links")
public class ShareLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String token;
    private Long conversationId;
    private Long createdBy;
    private Integer revoked;
    private java.time.LocalDateTime createdAt;
}
