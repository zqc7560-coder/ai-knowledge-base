-- V6: M8.2 高级检索开关（HyDE 假设答案 / 多查询扩展），默认关
ALTER TABLE knowledge_bases
  ADD COLUMN hyde TINYINT NOT NULL DEFAULT 0 COMMENT '1=向量通道用LLM假设答案做查询嵌入',
  ADD COLUMN multi_query TINYINT NOT NULL DEFAULT 0 COMMENT '1=原问题+HyDE假设答案双路向量召回取并集';
