    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `conversations` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("conversations")
    public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String kbIds;

    private String title;

    private Integer pinned;

    @TableLogic
    private Integer deleted;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
    }
