package com.kb.common.api;

import java.util.UUID;

/** 请求链路 ID：ThreadLocal，由 Filter 注入，Result 与日志共用。 */
public final class RequestIds {
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RequestIds() {}

    public static void set(String id) { HOLDER.set(id); }

    public static void clear() { HOLDER.remove(); }

    public static String current() {
        String id = HOLDER.get();
        return id != null ? id : UUID.randomUUID().toString().substring(0, 8);
    }
}
