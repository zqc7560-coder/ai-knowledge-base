package com.kb.rag.ai;

/** AI 上游异常：只携带业务文案，绝不携带上游原始报文（防 key 回声，密钥规范 §3.2）。 */
public class AiUpstreamException extends RuntimeException {
    private final boolean busy;

    public AiUpstreamException(String message, boolean busy, Throwable cause) {
        super(message, cause);
        this.busy = busy;
    }

    public AiUpstreamException(String message, Throwable cause) {
        this(message, false, cause);
    }

    public boolean busy() { return busy; }
}
