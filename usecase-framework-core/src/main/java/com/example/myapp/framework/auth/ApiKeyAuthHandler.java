package com.example.myapp.framework.auth;

import java.util.Map;

import org.springframework.web.client.RestClient;

/**
 * API Key 认证（请求头形式）。
 * <pre>{@code
 * auth:
 *   scheme: apiKey
 *   options:
 *     header: X-Api-Key
 *     value: "${svc.api-key}"
 * }</pre>
 * 查询参数形式的 key 可直接写入 url / uriVariables，无需本处理器。
 */
public final class ApiKeyAuthHandler implements AuthHandler {

    @Override
    public String scheme() {
        return "apiKey";
    }

    @Override
    public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
        String headerName = String.valueOf(options.get("header"));
        String value = String.valueOf(options.get("value"));
        request.header(headerName, value);
    }

    @Override
    public void validate(Map<String, Object> options) {
        if (options.get("header") == null || options.get("value") == null) {
            throw new IllegalArgumentException("auth scheme 'apiKey' requires options.header and options.value");
        }
    }
}
