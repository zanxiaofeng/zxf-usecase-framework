package com.example.myapp.framework.auth;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Bearer Token 认证，两种令牌来源（二选一）：
 * <pre>{@code
 * # 静态令牌
 * auth: { scheme: bearer, options: { token: "${credit.token}" } }
 * # 动态令牌（引用 TokenProvider Bean，每次请求取最新）
 * auth: { scheme: bearer, options: { tokenProvider: creditTokenProvider } }
 * }</pre>
 */
public final class BearerTokenAuthHandler implements AuthHandler {

    @Nullable
    private final BeanFactory beanFactory;

    public BearerTokenAuthHandler(@Nullable BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public String scheme() {
        return "bearer";
    }

    @Override
    public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
        Object token = options.get("token");
        if (token == null) {
            Object providerName = options.get("tokenProvider");
            if (providerName != null && beanFactory != null) {
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
    }
}
