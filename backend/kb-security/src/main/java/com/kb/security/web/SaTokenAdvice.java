package com.kb.security.web;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import com.kb.common.api.BizCode;
import com.kb.common.api.Result;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.kb.common.api.BizCode.*;

/** Sa-Token 异常 -> 业务码（放在 kb-security 层，避免 kb-common 依赖 Sa-Token）。 */
@Order(-10)
@RestControllerAdvice
public class SaTokenAdvice {

    @ExceptionHandler(NotLoginException.class)
    public Result<Void> notLogin(NotLoginException e) {
        return Result.fail(UNAUTHORIZED, "登录已失效，请重新登录");
    }

    @ExceptionHandler(NotRoleException.class)
    public Result<Void> notRole(NotRoleException e) {
        return Result.fail(FORBIDDEN, "权限不足");
    }

    @ExceptionHandler(DisableServiceException.class)
    public Result<Void> disabled(DisableServiceException e) {
        return Result.fail(ACCOUNT_DISABLED, "账号已被封禁，请稍后再试");
    }
}
