-- V4: M7.2 AI 用量成本落库——ai_usage_daily 从"预留未用"升级为主数据表
ALTER TABLE ai_usage_daily
  ADD COLUMN prompt_tokens     BIGINT NOT NULL DEFAULT 0 AFTER total_tokens,
  ADD COLUMN completion_tokens BIGINT NOT NULL DEFAULT 0 AFTER prompt_tokens,
  ADD COLUMN emb_tokens        BIGINT NOT NULL DEFAULT 0 AFTER completion_tokens,
  ADD COLUMN rerank_tokens     BIGINT NOT NULL DEFAULT 0 AFTER emb_tokens,
  ADD COLUMN vision_calls      INT    NOT NULL DEFAULT 0 AFTER rerank_tokens,
  ADD COLUMN vision_tokens     BIGINT NOT NULL DEFAULT 0 AFTER vision_calls;
