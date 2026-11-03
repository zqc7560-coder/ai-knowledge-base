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

    /** 瞬态（不落库）：当前查询者对本库的有效档位（1 READ/2 ASK/3 EDIT，owner 与 PUBLIC 折算后）。
     *  由 KbService.myVisible 统一计算——前端据此显隐"检索调试"等 ASK 级入口，
     *  后端 /api/debug 仍独立 require(ASK)：显隐是体验，校验是安全，两层都要。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer myRank;
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

    private String grp;

    private String tags;

    private Integer parentChild;

    private Integer pcSpan;

    private Integer hyde;

    private Integer multiQuery;

    @TableLogic
    private Integer deleted;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
    }
