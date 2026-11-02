    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `document_chunks` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("document_chunks")
    public class DocumentChunk {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Long kbId;

    private Integer seqNo;

    private String content;

    private String titlePath;

    private Integer pageNo;

    private Integer tokenCount;

    private String vectorId;

    private java.time.LocalDateTime createdAt;
    }
