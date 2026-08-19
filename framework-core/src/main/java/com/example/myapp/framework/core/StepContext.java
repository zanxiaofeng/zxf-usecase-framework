package com.example.myapp.framework.core;

import com.example.myapp.framework.core.exception.StepValidationException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管道执行上下文，在 step 之间流转（线程封闭于管道执行线程，非线程安全）。
 *
 * <ul>
 *   <li>{@code payload} —— 步骤间传递的「主数据」，逐段被加载、转换、保存</li>
 *   <li>{@code vars} —— 命名旁路结果（如 httpRequester 配置 {@code as: credit}），SpEL 中经 {@code #vars.credit} 引用</li>
 *   <li>{@code biz} —— 关键数据区：starter step 在用例开始时提取的 businessId / traceId / 租户等业务标识，
 *       SpEL 中经 {@code #biz.xxx} 引用，并同步到日志 MDC（{@code biz.*}）便于全链路日志关联</li>
 *   <li>{@code request} —— 入站 {@link ServerRequest}（core 模块直接依赖 spring-webmvc，无桥接层），
 *       SpEL 中经 {@code #path / #query / #headers / #body} 引用；管道外调用（{@link #standalone()}）为 null，
 *       此时各请求视图为空 Map / null</li>
 * </ul>
 */
public final class StepContext {

    private final @Nullable ServerRequest request;
    private final @Nullable ObjectMapper objectMapper;
    private Object payload;
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final Map<String, Object> biz = new LinkedHashMap<>();
    private boolean bodyRead;
    private @Nullable Object body;

    private StepContext(@Nullable ServerRequest request, @Nullable ObjectMapper objectMapper) {
        this.request = request;
        this.objectMapper = objectMapper;
    }

    /** Web 入口上下文：关联当前入站请求（由 framework.web.UseCaseRouterFactory 创建）。 */
    public static StepContext of(ServerRequest request, ObjectMapper objectMapper) {
        return new StepContext(request, objectMapper);
    }

    /** 管道外独立调用上下文（调度任务、消息消费、普通 Service）：无入站请求。 */
    public static StepContext standalone() {
        return new StepContext(null, null);
    }

    /**
     * 隔离子上下文（UseCaseInvoker.invokeIsolated 使用）：共享入站请求与父已解析的 body 缓存
     * （Servlet 请求体流只能消费一次），vars / payload 全新，biz 由调用方拷贝继承。
     */
    public StepContext newChildContext() {
        StepContext child = new StepContext(request, objectMapper);
        child.bodyRead = this.bodyRead;
        child.body = this.body;
        return child;
    }

    /** 入站请求；{@link #standalone()} 场景为 null。 */
    public @Nullable ServerRequest getRequest() {
        return request;
    }

    // ------------------------------------------------------------------
    // 便捷访问器：SpEL 模板表达式（#{path.id} / #{vars.x} / #{body.name}）以 StepContext 为根对象；
    // 同时供 StepExpressionEvaluator 注册 #path / #query / #headers / #body 变量
    // ------------------------------------------------------------------

    public Map<String, String> getPath() {
        return request == null ? Map.of() : request.pathVariables();
    }

    public Map<String, String> getQuery() {
        return request == null ? Map.of() : request.params().toSingleValueMap();
    }

    public Map<String, String> getHeaders() {
        return request == null ? Map.of() : request.headers().asHttpHeaders().toSingleValueMap();
    }

    /**
     * 请求体：惰性读取并缓存（只在表达式真正引用 {@code #body} 时解析），仅 POST/PUT/PATCH 尝试读体：
     * 空体 → {@code null}；JSON 内容类型（缺省视为 JSON，含 {@code +json} 后缀类型）→ Jackson 严格解析为
     * Map/List，<b>语法错误抛 {@link StepValidationException}</b>（映射 400，而非静默置 null 导致后续
     * schema 校验报出误导性明细）；其他内容类型 → 纯文本 String。standalone 场景恒为 null。
     */
    public @Nullable Object getBody() {
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

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    /** 类型化读取：类型不符时立即抛 ClassCastException（而非延迟到调用点） */
    public <T> T getPayload(Class<T> type) {
        return type.cast(payload);
    }

    public Map<String, Object> getVars() {
        return vars;
    }

    public void putVar(String name, Object value) {
        vars.put(name, value);
    }

    public Object getVar(String name) {
        return vars.get(name);
    }

    public <T> T getVar(String name, Class<T> type) {
        return type.cast(vars.get(name));
    }

    // ------------------------------------------------------------------
    // 关键数据区（biz）：starter step 写入，SpEL 经 #biz.xxx / 模板经 #{biz.xxx} 引用
    // ------------------------------------------------------------------

    public Map<String, Object> getBiz() {
        return biz;
    }

    public void putBiz(String key, Object value) {
        biz.put(key, value);
    }

    public Object getBiz(String key) {
        return biz.get(key);
    }

    public <T> T getBiz(String key, Class<T> type) {
        return type.cast(biz.get(key));
    }
}
