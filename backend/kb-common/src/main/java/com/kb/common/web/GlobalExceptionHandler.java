package com.kb.common.web;

import com.kb.common.api.BizException;
import com.kb.common.api.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static com.kb.common.api.BizCode.*;

/** 全局异常 -> 统一信封。任何异常信息不透传上游原始报文（密钥安全规范 L5/L6）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> biz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> valid(MethodArgumentNotValidException e) {
        FieldError f = e.getBindingResult().getFieldError();
        String msg = f == null ? "参数不合法" : f.getField() + " " + f.getDefaultMessage();
        return Result.fail(PARAM_INVALID, msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> tooLarge(MaxUploadSizeExceededException e) {
        return Result.fail(FILE_TOO_LARGE, "文件超过大小限制");
    }

    /**
     * 路由不存在 = 客户端问题，不是服务端异常。此前它落到 catch-all：ERROR 级堆栈污染日志
     * （探测扫描一次刷一条"unexpected error"）+ 误报 500/1003，运维误导。
     * Spring Boot 3 静态资源兜底对未知路径抛 NoResourceFoundException，显式接住回 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> notFound(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.debug("no route: {}", e.getResourcePath());
        return Result.fail(NOT_FOUND, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> other(Exception e) {
        log.error("unexpected error reqId={}", com.kb.common.api.RequestIds.current(), e);
        return Result.fail(INTERNAL_ERROR, "服务内部错误");
    }
}
