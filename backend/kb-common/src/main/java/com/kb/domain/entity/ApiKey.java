package com.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开放 API 密钥（M10.6）。库级作用域：key 只能问绑定的库；
 * 明文只在创建响应出现一次，库中存 sha256——数据库泄露不等于密钥泄露。
 */
@Data
@TableName("api_keys")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String keyHash;
    private String kbScope;
    private Integer revoked;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}
