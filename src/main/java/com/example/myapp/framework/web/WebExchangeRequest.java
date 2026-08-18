package com.example.myapp.framework.web;

import com.example.myapp.framework.core.ExchangeRequest;
import com.example.myapp.framework.core.StepValidationException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * {@link ExchangeRequest} 的 Web MVC 实现：包装 ServerRequest。
 *
 * <p>请求体惰性读取（只在 step 真正引用 {@code #body} 时解析），仅 POST/PUT/PATCH 尝试读体，
 * 读取规则：</p>
 * <ul>
 *   <li>空体 → {@code null}；</li>
 *   <li>JSON 内容类型（缺省视为 JSON，含 {@code +json} 后缀类型）→ Jackson 严格解析为 Map/List，
 *       <b>语法错误抛 {@link StepValidationException}</b>（映射 400，给出明确错误，
 *       而非静默置 null 导致后续 schema 校验报出误导性明细）；</li>
 *   <li>其他内容类型（text 等）→ 按纯文本返回 String。</li>
 * </ul>
 */
public final class WebExchangeRequest implements ExchangeRequest {

    private final ServerRequest delegate;
    private final ObjectMapper objectMapper;
    private volatile boolean bodyRead;
    private Object body;

    public WebExchangeRequest(ServerRequest delegate, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
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
        String raw = readRawBody();
        if (raw == null || raw.isBlank()) {
            return null;    // 空体
        }
        if (!isJsonContentType()) {
            return raw;     // 非 JSON 内容类型：契约保持，按纯文本处理
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (JacksonException e) {
            throw new StepValidationException("BAD_REQUEST",
                    "Malformed JSON request body: " + e.getMessage());
        }
    }

    @Nullable
    private String readRawBody() {
        try {
            return delegate.body(String.class);
        } catch (Exception e) {
            return null;    // 读取失败（如体已被消费）：按无体处理
        }
    }

    /** REST 惯例：未声明内容类型时按 JSON 处理 */
    private boolean isJsonContentType() {
        MediaType contentType = delegate.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        return MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || contentType.getSubtype().endsWith("+json");
    }
}
