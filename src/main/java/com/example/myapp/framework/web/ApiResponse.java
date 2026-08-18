package com.example.myapp.framework.web;

import java.time.OffsetDateTime;

/**
 * 统一响应信封（沿用原 solution 的契约：code / data / message / timestamp / traceId）。
 * traceId 由 Web 层在管道执行前种子化（X-Request-Id 请求头或 UUID），随响应回填。
 * 若项目保留注解式 Controller，可直接复用本类，替代 adapter.in.web.common.ApiResponse。
 */
public record ApiResponse<T>(
        String code,
        T data,
        String message,
        OffsetDateTime timestamp,
        String traceId
) {
    public static <T> ApiResponse<T> success(T data) {
        return success(data, null);
    }

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>("000000", data, null, OffsetDateTime.now(), traceId);
    }

    public static ApiResponse<Void> error(String code, String message) {
        return error(code, message, null);
    }

    public static ApiResponse<Void> error(String code, String message, String traceId) {
        return new ApiResponse<>(code, null, message, OffsetDateTime.now(), traceId);
    }
}
