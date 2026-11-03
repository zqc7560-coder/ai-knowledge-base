-- V12: M11 审计日志
CREATE TABLE IF NOT EXISTS audit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NULL,
  username VARCHAR(60) NULL COMMENT '冗余存名，用户注销后仍可读',
  ip VARCHAR(60) NULL,
  action VARCHAR(40) NOT NULL COMMENT '如 kb.delete / member.grant / role.change / user.disable / share.create / key.create / invite.accept',
  target VARCHAR(160) NULL COMMENT '对象描述(库名/用户/文档)',
  detail VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_time (created_at), KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理动作审计(M11)';
