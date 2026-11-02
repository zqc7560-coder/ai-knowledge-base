package com.kb.common.api;

import lombok.Getter;

/** 业务异常：由 message 直接透出给用户，不带堆栈敏感信息。 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(int code, String message) {
        return new BizException(code, message);
    }
}
