package com.kb.domain.kb;

/**
 * 知识库删除事件（M12.1 修复删库残留孤儿数据）。
 *
 * <h3>为什么是事件而不是直接调用</h3>
 * 模块方向：kb-document → kb-common；KbService（kb-common）不能反向依赖 DocumentService。
 * 删库要级联清文档行/chunk行/向量/原文件——这些全归 kb-document 管。
 * 事件让 KbService 只发布"kb#id 已删"，清理责任由 kb-document 监听者承担，依赖方向不破。
 * 同步发布（不用 @Async/@TransactionalEventListener）：删除接口本来就该在返回前清完，
 * 异步失败会变成新的孤儿来源。监听器内部吞错记日志——向量库不可达时 MySQL 软删不该回滚
 * （用户视角"库没了"已达成；孤儿向量可由重建索引兜底清）。
 */
public record KbDeletedEvent(Long kbId, String operator) {}
