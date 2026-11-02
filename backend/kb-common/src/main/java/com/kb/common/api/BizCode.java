package com.kb.common.api;

/** 业务错误码：1xxx 通用，2xxx 认证/权限，3xxx 知识库/文档，4xxx AI 能力。 */
public final class BizCode {
    public static final int PARAM_INVALID = 1001;
    public static final int NOT_FOUND = 1002;
    public static final int INTERNAL_ERROR = 1003;
    public static final int RATE_LIMITED = 1004;

    public static final int UNAUTHORIZED = 2001;
    public static final int FORBIDDEN = 2002;
    public static final int LOGIN_FAILED = 2003;
    public static final int ACCOUNT_DISABLED = 2004;

    public static final int KB_NOT_FOUND = 3001;
    public static final int KB_NO_PERMISSION = 3002;
    public static final int FILE_TYPE_NOT_ALLOWED = 3003;
    public static final int FILE_TOO_LARGE = 3004;
    public static final int DOC_NOT_READY = 3005;

    public static final int AI_UPSTREAM_BUSY = 4001;
    public static final int AI_UPSTREAM_ERROR = 4002;
    public static final int AI_QUOTA_EXCEEDED = 4003;

    private BizCode() {}
}
