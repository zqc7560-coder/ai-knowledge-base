    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `messages` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("messages")
    public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private String role;

    private String content;

    private String citeChunkIds;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer elapsedMs;

    private String model;
    private Integer feedback;

    private java.time.LocalDateTime createdAt;
    }
