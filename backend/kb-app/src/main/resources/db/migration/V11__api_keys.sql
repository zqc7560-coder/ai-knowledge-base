-- V11: M10.6 开放 API 密钥
CREATE TABLE IF NOT EXISTS api_keys (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(60) NOT NULL,
  key_hash CHAR(64) NOT NULL COMMENT 'sha256(明文)，明文仅创建时返回一次',
  kb_scope VARCHAR(200) NOT NULL COMMENT 'CSV 库id，请求库必须⊆此集合',
  revoked TINYINT NOT NULL DEFAULT 0,
  last_used_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_hash (key_hash), KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对外开放API密钥(M10.6)';
