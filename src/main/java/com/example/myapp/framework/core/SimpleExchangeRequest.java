package com.example.myapp.framework.core;

import java.util.Map;

/**
 * {@link ExchangeRequest} 的内存实现，用于单元测试与非 Web 驱动场景（CLI / 消息消费者等）。
 */
public final class SimpleExchangeRequest implements ExchangeRequest {

    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final Object body;

    public SimpleExchangeRequest(String method, String path,
                                 Map<String, String> pathVariables,
                                 Map<String, String> queryParams,
                                 Map<String, String> headers,
                                 Object body) {
        this.method = method;
        this.path = path;
        this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
        this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.body = body;
    }

    public static SimpleExchangeRequest of(String method, String path) {
        return new SimpleExchangeRequest(method, path, Map.of(), Map.of(), Map.of(), null);
    }

    public static SimpleExchangeRequest withPathVariables(String method, String path, Map<String, String> pathVariables) {
        return new SimpleExchangeRequest(method, path, pathVariables, Map.of(), Map.of(), null);
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Map<String, String> pathVariables() {
        return pathVariables;
    }

    @Override
    public Map<String, String> queryParams() {
        return queryParams;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public Object body() {
        return body;
    }
}
