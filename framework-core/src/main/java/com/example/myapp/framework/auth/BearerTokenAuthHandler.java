package com.example.myapp.framework.auth;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Bearer Token 认证，两种令牌来源（二选一）：
 * <pre>{@code
 * # 静态令牌
 * auth: { scheme: bearer, options: { token: "${credit.token}" } }
 * # 动态令牌（引用 TokenProvider Bean，每次请求取最新）
 * auth: { scheme: bearer, options: { tokenProvider: creditTokenProvider } }
 * }</pre>
 *
 * <p>{@code beanFactory} 允许为 null（独立使用时无 Spring 容器），
 * 此时仅支持静态 {@code token} 方式。</p>
 */
@RequiredArgsConstructor
public final class BearerTokenAuthHandler implements AuthHandler {

    @Nullable
    private final BeanFactory beanFactory;

    @Override
    public String scheme() {
        return "bearer";
    }

    @Override
    public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
        Object token = options.get("token");
        if (token == null) {
            Object providerName = options.get("tokenProvider");
            if (providerName != null) {
                if (beanFactory == null) {
                    throw new IllegalStateException(
                            "auth scheme 'bearer': tokenProvider '%s' configured but no Spring container available"
                                    .formatted(providerName));
                }
                token = beanFactory.getBean(String.valueOf(providerName), TokenProvider.class).getToken();
            }
        }
        if (token == null) {
            throw new IllegalStateException("auth scheme 'bearer': neither token nor tokenProvider available");
        }
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    @Override
    public void validate(Map<String, Object> options) {
        if (options.get("token") == null && options.get("tokenProvider") == null) {
            throw new IllegalArgumentException("auth scheme 'bearer' requires options.token or options.tokenProvider");
        }
        // Bean 名拼错装配期即报错（containsBean 查 BeanDefinition 注册表，装配期已就绪），不留到首次请求
        if (options.get("tokenProvider") != null && beanFactory != null
                && !beanFactory.containsBean(String.valueOf(options.get("tokenProvider")))) {
            throw new IllegalArgumentException(
                    "auth scheme 'bearer': tokenProvider bean '%s' not found"
                            .formatted(options.get("tokenProvider")));
        }
    }
}
