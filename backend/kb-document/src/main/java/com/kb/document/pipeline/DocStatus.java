package com.kb.document.pipeline;

/** 文档状态机（docs/01 F3）：UPLOADED→PARSING→CHUNKED→EMBEDDING→READY，任一步失败→FAILED。 */
public enum DocStatus {
    UPLOADED, PARSING, CHUNKED, EMBEDDING, READY, FAILED;

    public boolean canTransitionTo(DocStatus next) {
        if (next == FAILED) return this != READY && this != FAILED; // 任意进行中状态都可失败
        return switch (this) {
            case UPLOADED -> next == PARSING;
            case PARSING -> next == CHUNKED;
            case CHUNKED -> next == EMBEDDING;
            case EMBEDDING -> next == READY;
            case FAILED -> next == PARSING;   // 重试
            case READY -> false;
        };
    }
}
