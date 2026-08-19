package com.example.myapp.framework.auth;

import java.util.Map;

import org.springframework.web.client.RestClient;

/** 无认证（显式声明 scheme: none 时使用；缺省不配置 auth 等效）。 */
public final class NoAuthHandler implements AuthHandler {

    @Override
    public String scheme() {
        return "none";
    }

    @Override
    public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
        // no-op
    }
}
