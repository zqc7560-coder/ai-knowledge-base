    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `knowledge_bases` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("knowledge_bases")
    public class KnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;

    private String name;

    private String description;

    private String visibility;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private Integer topK;

    private java.math.BigDecimal scoreThreshold;

    private String embeddingModel;

    @TableLogic
    private Integer deleted;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
    }
