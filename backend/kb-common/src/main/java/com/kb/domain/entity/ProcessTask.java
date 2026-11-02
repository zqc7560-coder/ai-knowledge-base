package com.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 后台任务持久化（M7.1）：重建索引/向量库迁移。内存 TaskInfo 仍是热缓存，本表是事实源。 */
@Data
@TableName("process_tasks")
public class ProcessTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;
    private Long kbId;
    private String kbName;
    private String target;
    private Integer unitTotal;
    private Integer unitDone;
    private String state;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
