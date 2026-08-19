package com.example.myapp.framework.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * HTTP Basic 认证。
 * <pre>{@code
 * auth:
 *   scheme: basic
 *   options:
 *     username: "${svc.username}"
 *     password: "${svc.password}"
 * }</pre>
 */
public final class BasicAuthHandler implements AuthHandler {

    @Override
    public String scheme() {
        return "basic";
    }

    @Override
    public void apply(RestClient.RequestHeadersSpec<?> request, Map<String, Object> options) {
        String username = String.valueOf(options.get("username"));
        String password = String.valueOf(options.get("password"));
        String credentials = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        request.header(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
    }

    @Override
    public void validate(Map<String, Object> options) {
        if (options.get("username") == null || options.get("password") == null) {
            throw new IllegalArgumentException("auth scheme 'basic' requires options.username and options.password");
        }
    }
}
