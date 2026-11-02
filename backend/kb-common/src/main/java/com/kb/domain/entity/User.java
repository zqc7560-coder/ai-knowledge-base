    package com.kb.domain.entity;

    import com.baomidou.mybatisplus.annotation.IdType;
    import com.baomidou.mybatisplus.annotation.TableId;
    import com.baomidou.mybatisplus.annotation.TableLogic;
    import com.baomidou.mybatisplus.annotation.TableName;
    import lombok.Data;

    /** 表 `users` 实体（docs/01 §4 数据模型）。 */
    @Data
    @TableName("users")
    public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String email;

    private String passwordHash;

    private String role;

    private Integer status;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
    }
