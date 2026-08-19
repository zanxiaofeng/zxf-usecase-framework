
package com.example.myapp.framework.core;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.core.exception.StepValidationException;

/**
 * 请求体视图：HTTP 请求体解码语义从 {@link StepContext} 拆出的协作者（SRP——
 * 「请求体解析规则变化」与「管道数据流转」是两种变化原因）。
 *
 * <p>惰性读取并缓存（只在表达式真正引用 {@code #body} 时解析），仅 POST/PUT/PATCH 尝试读体：
 * 空体 → {@code null}；JSON 内容类型（缺省视为 JSON，含 {@code +json} 后缀类型）→ Jackson 严格解析为
 * Map/List，<b>语法错误抛 {@link StepValidationException}</b>（映射 400，而非静默置 null 导致后续
 * schema 校验报出误导性明细）；其他内容类型 → 纯文本 String。standalone 场景恒为 null。</p>
 *
 * <p>包私有、随上下文线程封闭，非线程安全；隔离子上下文与父共享同一视图
 * （Servlet 请求体流只能消费一次，缓存随之共享）。</p>
 */
final class RequestBodyView {

    private final @Nullable ServerRequest request;
    private final @Nullable ObjectMapper objectMapper;
    private boolean bodyRead;
    private @Nullable Object body;

    RequestBodyView(@Nullable ServerRequest request, @Nullable ObjectMapper objectMapper) {
        this.request = request;
        this.objectMapper = objectMapper;
    }

    /** 请求体（语义见类 Javadoc）；standalone 场景恒为 null */
    @Nullable Object getBody() {
        if (!bodyRead) {
            body = readBodySafely();
            bodyRead = true;
        }
        return body;
    }

    private @Nullable Object readBodySafely() {
        ServerRequest currentRequest = request;
        ObjectMapper mapper = objectMapper;
        if (currentRequest == null || mapper == null) {
            return null;
        }
        String method = currentRequest.method().name();
        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            return null;
        }
        String raw = readRawBody(currentRequest);
        if (raw == null || raw.isBlank()) {
            return null;    // 空体
        }
        if (!isJsonContentType(currentRequest)) {
            return raw;     // 非 JSON 内容类型：按纯文本处理
        }
        try {
            return mapper.readValue(raw, Object.class);
        } catch (JacksonException e) {
            throw new StepValidationException("BAD_REQUEST",
                    "Malformed JSON request body: " + e.getMessage());
        }
    }

    private @Nullable String readRawBody(ServerRequest currentRequest) {
        try {
            return currentRequest.body(String.class);
        } catch (Exception e) {
            return null;    // 读取失败（如体已被消费）：按无体处理
        }
    }

    /** REST 惯例：未声明内容类型时按 JSON 处理 */
    private boolean isJsonContentType(ServerRequest currentRequest) {
        MediaType contentType = currentRequest.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        return MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || contentType.getSubtype().endsWith("+json");
    }
}
