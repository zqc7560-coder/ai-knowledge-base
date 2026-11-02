package com.kb.rag.usage;

/**
 * 用量归因上下文（M7.2）：AiClientFactory 解析到 usage 时，"该记在谁头上"由调用线程预先绑定。
 *
 * <h3>为什么不把 userId/kbId 塞进每个方法签名</h3>
 * embed 藏在 PgVectorStore.upsert 里、chat 藏在改写与生成两处、rerank 在融合后——
 * 全链路改签名波及面大且污染接口；归因本质是"横切关注点"，ThreadLocal 由入口设置、
 * finally 清理，工厂侧只读，符合 AOP 心智。池线程（文档管道）与请求线程（问答）都安全，
 * 因为每个逻辑任务开始时都会 set。
 *
 * <h3>两类归因</h3>
 * chat/rerank 记 userId（问答触发者）；embedding/vision 记 kbId（UsageRecorder 再映射到库主）。
 */
public final class UsageContext {
    private static final ThreadLocal<UsageContext> CUR = new ThreadLocal<>();

    public final Long userId;   // 可为 null（管道线程没有登录态）
    public final Long kbId;     // 可为 null（纯问答的 chat 不需要库归因到 emb）

    private UsageContext(Long userId, Long kbId) { this.userId = userId; this.kbId = kbId; }

    public static void set(Long userId, Long kbId) { CUR.set(new UsageContext(userId, kbId)); }
    public static UsageContext get() { return CUR.get(); }
    public static void clear() { CUR.remove(); }
}
