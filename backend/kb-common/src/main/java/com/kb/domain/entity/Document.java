    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `documents` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("documents")
    public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Long uploaderId;

    private String filename;

    private String filePath;

    private String fileType;

    private Long fileSize;

    private String sha256;

    private String status;

    private Integer restricted;   // M10.6 文档级受限位

    private String errorMsg;

    private Integer chunkCount;

    private Integer tokens;

    @TableLogic
    private Integer deleted;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
    }
