    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `kb_members` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("kb_members")
    public class KbMember {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Long userId;

    private String permission;

    private java.time.LocalDateTime createdAt;
    }
