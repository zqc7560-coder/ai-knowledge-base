    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `ai_usage_daily` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("ai_usage_daily")
    public class AiUsageDaily {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private java.time.LocalDate statDate;

    private Integer embCalls;

    private Integer chatCalls;

    private Integer rerankCalls;

    private Long totalTokens;
    private Long promptTokens;
    private Long completionTokens;
    private Long embTokens;
    private Long rerankTokens;
    private Integer visionCalls;
    private Long visionTokens;
    }
