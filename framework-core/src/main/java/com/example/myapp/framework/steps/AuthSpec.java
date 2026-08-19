package com.example.myapp.framework.steps;

import com.example.myapp.framework.auth.AuthHandler;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * httpRequester 的认证配置（scheme + options + handler 查找表三元组）。
 * scheme 为空或 {@code none} 时不携带认证头；其余 scheme 的 handler 存在性
 * 由 HttpRequesterStepFactory 在装配期校验，这里仅兜底防御。
 */
record AuthSpec(String scheme, Map<String, Object> options, Map<String, AuthHandler> handlers) {

    /** 按 scheme 找到 handler 并应用认证头；scheme 空 / none 时什么都不做 */
    void apply(RestClient.RequestHeadersSpec<?> request) {
        if (scheme == null || scheme.isBlank() || "none".equalsIgnoreCase(scheme)) {
            return;
        }
        AuthHandler handler = handlers.get(scheme);
        if (handler == null) {
            throw new IllegalStateException("no AuthHandler for scheme: " + scheme);
        }
        handler.apply(request, options);
    }
}
