package com.example.myapp.framework.web;

import com.example.myapp.framework.core.ExchangeRequest;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;

/**
 * {@link ExchangeRequest} 的 Web MVC 实现：包装 ServerRequest。
 * 请求体惰性读取（只在 step 真正引用 {@code #body} 时解析），仅 POST/PUT/PATCH 尝试读体。
 */
public final class WebExchangeRequest implements ExchangeRequest {

    private final ServerRequest delegate;
    private volatile boolean bodyRead;
    private Object body;

    public WebExchangeRequest(ServerRequest delegate) {
        this.delegate = delegate;
    }

    @Override
    public String method() {
        return delegate.method().name();
    }

    @Override
    public String path() {
        return delegate.path();
    }

    @Override
    public Map<String, String> pathVariables() {
        return delegate.pathVariables();
    }

    @Override
    public Map<String, String> queryParams() {
        return delegate.params().toSingleValueMap();
    }

    @Override
    public Map<String, String> headers() {
        return delegate.headers().asHttpHeaders().toSingleValueMap();
    }

    @Override
    public Object body() {
        if (!bodyRead) {
            synchronized (this) {
                if (!bodyRead) {
                    body = readBodySafely();
                    bodyRead = true;
                }
            }
        }
        return body;
    }

    private Object readBodySafely() {
        String method = method();
        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            return null;
        }
        try {
            return delegate.body(Object.class);
        } catch (Exception e) {
            return null;    // 空体或非 JSON 体：按 null 处理
        }
    }
}
