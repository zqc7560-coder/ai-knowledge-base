package com.kb.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 统一响应信封：前端拦截器按 code 判成败。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    public static final int OK = 0;

    private int code;
    private String message;
    private T data;
    private String requestId;

    public static <T> Result<T> ok(T data) {
        return new Result<>(OK, "ok", data, RequestIds.current());
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, RequestIds.current());
    }
}
