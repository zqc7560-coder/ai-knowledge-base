-- V2: M6 增量——答案反馈 + 只读分享链接
ALTER TABLE messages ADD COLUMN feedback TINYINT NOT NULL DEFAULT 0 COMMENT '1=赞 -1=踩 0=未评';

CREATE TABLE IF NOT EXISTS share_links (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  token CHAR(16) NOT NULL COMMENT '随机串=访问凭证（不可枚举）',
  conversation_id BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  revoked TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_share_token (token),
  KEY idx_share_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
