package com.example.myapp.framework.steps;

import com.example.myapp.framework.auth.AuthHandler;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * httpRequester 的认证配置：装配期已解析的单个 {@link AuthHandler} + options。
 * handler 为 null（未配置 auth）时不携带认证头；scheme 存在性与 options 校验
 * 由 HttpRequesterStepFactory 在装配期完成（fail-fast），运行期零查找零防御分支。
 */
record AuthSpec(@Nullable AuthHandler handler, Map<String, Object> options) {

    /** 应用认证头；未配置认证时什么都不做 */
    void apply(RestClient.RequestHeadersSpec<?> request) {
        if (handler == null) {
            return;
        }
        handler.apply(request, options);
    }
}
