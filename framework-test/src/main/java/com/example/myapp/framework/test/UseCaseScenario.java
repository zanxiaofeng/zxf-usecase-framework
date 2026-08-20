package com.example.myapp.framework.test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.steps.StarterStep;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YAML 用例的测试场景：构造接近真实的 {@link ServerRequest}（MockHttpServletRequest 打底，
 * path/query/header/body 语义与 RouterFunction 入口一致），经 {@code StepContext.of} 走真实管道执行，
 * 然后断言最终 payload、vars、biz 与已发布事件——配置驱动的用例本身就是回归对象。
 *
 * <pre>{@code
 * UseCaseScenario.given(registry, objectMapper)
 *         .request("GET", "/api/v1/users/{id}/profile")
 *         .pathVar("id", "u1")
 *         .header("X-Channel", "web")
 *         .expectBiz("businessId", "u1")
 *         .expectVar("credit", credit -> ...)
 *         .expectPayload(payload -> ...)
 *         .run();
 * }</pre>
 *
 * <p>与 Web 入口（{@code UseCaseRouterFactory}）的对齐与差异：</p>
 * <ul>
 *   <li>对齐：执行前向 biz/MDC 种子化 traceId（默认固定值 {@value #DEFAULT_TRACE_ID}，可
 *       {@link #traceId(String)} 覆盖），执行后清理 MDC（{@code biz.*} 前缀 + traceId）；</li>
 *   <li>差异：不经过路由与异常→HTTP 映射，管道异常原样从 {@link #run()} 抛出
 *       （失败场景用 {@code assertThatThrownBy(scenario::run)} 断言）；
 *       expectXxx 断言只在管道成功完成后执行；</li>
 *   <li>用例定位：{@link #request(String, String)} 按 method + path 模板匹配 endpoint 用例，
 *       或 {@link #useCase(String)} 直接按 id（可定位 shared 用例）。</li>
 * </ul>
 *
 * <p>非线程安全，每个测试新建实例。</p>
 */
public final class UseCaseScenario {

    /** 缺省种子化的 traceId（固定值，保证测试确定性） */
    public static final String DEFAULT_TRACE_ID = "scenario-trace";

    /** RequestBodyView 经 body(String.class) 读体：StringHttpMessageConverter 支持全部内容类型 */
    private static final List<HttpMessageConverter<?>> MESSAGE_CONVERTERS =
            List.of(new StringHttpMessageConverter(StandardCharsets.UTF_8));

    private final UseCaseRegistry registry;
    private final ObjectMapper objectMapper;

    private @Nullable String method;
    private @Nullable String pathTemplate;
    private @Nullable String useCaseId;
    private final Map<String, String> pathVars = new LinkedHashMap<>();
    private final Map<String, String> queryParams = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private @Nullable String body;
    private String contentType = MediaType.APPLICATION_JSON_VALUE;
    private String traceId = DEFAULT_TRACE_ID;
    private @Nullable RecordingEventPublisher eventRecorder;
    private final List<Consumer<ScenarioResult>> expectations = new ArrayList<>();
    private final List<Consumer<RecordingEventPublisher>> eventExpectations = new ArrayList<>();

    private UseCaseScenario(UseCaseRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 场景入口。
     *
     * @param registry      已装配的用例注册表（{@code @SpringBootTest} 中注入，或手工装配）
     * @param objectMapper  请求体 JSON 解析用（与 Web 入口同一 Bean）
     */
    public static UseCaseScenario given(UseCaseRegistry registry, ObjectMapper objectMapper) {
        return new UseCaseScenario(registry, objectMapper);
    }

    /**
     * 按端点定位用例（与真实路由同一视角）。
     *
     * @param method       HTTP 方法（大小写不敏感）
     * @param pathTemplate endpoint path 模板原文（含 {@code {var}} 占位符，与 YAML 一致）
     */
    public UseCaseScenario request(String method, String pathTemplate) {
        this.method = method;
        this.pathTemplate = pathTemplate;
        this.useCaseId = null;
        return this;
    }

    /** 按用例 id 直接定位（可定位 shared 用例） */
    public UseCaseScenario useCase(String id) {
        this.useCaseId = id;
        this.method = null;
        this.pathTemplate = null;
        return this;
    }

    /** 路径变量（{@code #path.xxx} 可见；同时用于把 path 模板替换为实际请求路径） */
    public UseCaseScenario pathVar(String name, String value) {
        pathVars.put(name, value);
        return this;
    }

    /** 查询参数（{@code #query.xxx} 可见） */
    public UseCaseScenario queryParam(String name, String value) {
        queryParams.put(name, value);
        return this;
    }

    /** 请求头（{@code #headers.xxx} / {@code #{headers['xxx']}} 可见） */
    public UseCaseScenario header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** 请求体（JSON 内容类型；{@code #body.xxx} 可见） */
    public UseCaseScenario body(String json) {
        return body(json, MediaType.APPLICATION_JSON_VALUE);
    }

    /** 请求体（指定内容类型；非 JSON 类型时 {@code #body} 为纯文本 String） */
    public UseCaseScenario body(String body, String contentType) {
        this.body = body;
        this.contentType = contentType;
        return this;
    }

    /** 覆盖种子化的 traceId（缺省 {@value #DEFAULT_TRACE_ID}） */
    public UseCaseScenario traceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    /** 接入事件探针（配合 {@link #expectEventPublished(Class)} 断言 eventPublisher 步骤的产出） */
    public UseCaseScenario recordingEventsTo(RecordingEventPublisher recorder) {
        this.eventRecorder = recorder;
        return this;
    }

    // ------------------------------------------------------------------
    // 断言注册（管道成功完成后按注册顺序执行；run() 抛出异常时不执行）
    // ------------------------------------------------------------------

    /** 断言最终 payload 等于期望值 */
    public UseCaseScenario expectPayload(@Nullable Object expected) {
        expectations.add(result -> assertThat(result.payload()).as("payload").isEqualTo(expected));
        return this;
    }

    /** 断言最终 payload */
    public UseCaseScenario expectPayload(Consumer<@Nullable Object> assertion) {
        expectations.add(result -> assertion.accept(result.payload()));
        return this;
    }

    /** 断言最终 payload（先按类型转换，不符即 ClassCastException） */
    public <T> UseCaseScenario expectPayload(Class<T> type, Consumer<T> assertion) {
        expectations.add(result -> assertion.accept(result.payload(type)));
        return this;
    }

    /** 断言 vars 键的值等于期望值 */
    public UseCaseScenario expectVar(String name, @Nullable Object expected) {
        expectations.add(result -> assertThat(result.var(name)).as("vars[%s]", name).isEqualTo(expected));
        return this;
    }

    /** 断言 vars 键（自定义断言） */
    public UseCaseScenario expectVar(String name, Consumer<@Nullable Object> assertion) {
        expectations.add(result -> assertion.accept(result.var(name)));
        return this;
    }

    /** 断言 biz 键的值等于期望值 */
    public UseCaseScenario expectBiz(String key, @Nullable Object expected) {
        expectations.add(result -> assertThat(result.biz(key)).as("biz[%s]", key).isEqualTo(expected));
        return this;
    }

    /** 断言 biz 键（自定义断言） */
    public UseCaseScenario expectBiz(String key, Consumer<@Nullable Object> assertion) {
        expectations.add(result -> assertion.accept(result.biz(key)));
        return this;
    }

    /** 断言至少发布了一个指定类型的事件（需先 {@link #recordingEventsTo}） */
    public UseCaseScenario expectEventPublished(Class<?> eventType) {
        eventExpectations.add(recorder -> assertThat(recorder.published(eventType))
                .as("expected at least one published event of type %s", eventType.getName())
                .isNotEmpty());
        return this;
    }

    /** 断言第一个指定类型的事件内容（需先 {@link #recordingEventsTo}） */
    public <T> UseCaseScenario expectEventPublished(Class<T> eventType, Consumer<T> assertion) {
        eventExpectations.add(recorder -> {
            List<T> events = recorder.published(eventType);
            assertThat(events)
                    .as("expected at least one published event of type %s", eventType.getName())
                    .isNotEmpty();
            assertion.accept(events.getFirst());
        });
        return this;
    }

    /**
     * 执行场景：构造请求上下文 → 种子化 traceId → 走真实管道 → 执行已注册断言。
     *
     * @return 执行结果视图（payload + 上下文），供进一步自定义断言
     * @throws IllegalStateException 未定位用例（未调 request/useCase）或事件断言未接探针
     * @throws IllegalArgumentException request 定位的端点无匹配用例（消息附可用路由表）
     */
    public ScenarioResult run() {
        if (!eventExpectations.isEmpty() && eventRecorder == null) {
            throw new IllegalStateException(
                    "expectEventPublished 需要先 recordingEventsTo(recorder) 接入事件探针");
        }
        UseCase useCase = resolveUseCase();
        StepContext context = StepContext.of(buildServerRequest(), objectMapper);
        context.putBiz(StepContext.TRACE_ID_KEY, traceId);
        MDC.put(StepContext.TRACE_ID_KEY, traceId);
        try {
            Object payload = useCase.execute(context);
            ScenarioResult result = new ScenarioResult(payload, context);
            expectations.forEach(expectation -> expectation.accept(result));
            if (eventRecorder != null) {
                RecordingEventPublisher recorder = eventRecorder;
                eventExpectations.forEach(expectation -> expectation.accept(recorder));
            }
            return result;
        } finally {
            clearMdc();
        }
    }

    private UseCase resolveUseCase() {
        if (useCaseId != null) {
            return registry.require(useCaseId);
        }
        if (method == null || pathTemplate == null) {
            throw new IllegalStateException("先调用 request(method, path) 或 useCase(id) 定位要执行的用例");
        }
        return registry.all().stream()
                .filter(candidate -> !candidate.isShared())
                .filter(candidate -> candidate.getEndpoint().method().name().equalsIgnoreCase(method)
                        && candidate.getEndpoint().path().equals(pathTemplate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no endpoint usecase matches %s %s — available routes: %s"
                                .formatted(method, pathTemplate, availableRoutes())));
    }

    private String availableRoutes() {
        return registry.all().stream()
                .filter(candidate -> !candidate.isShared())
                .map(candidate -> candidate.getEndpoint().method() + " " + candidate.getEndpoint().path())
                .collect(Collectors.joining(", "));
    }

    private ServerRequest buildServerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method == null ? "GET" : method.toUpperCase(), concretePath());
        if (!pathVars.isEmpty()) {
            // 与 RouterFunction 匹配后的行为一致：路径变量经请求属性暴露给 ServerRequest.pathVariables()
            request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVars);
        }
        queryParams.forEach(request::setParameter);
        headers.forEach(request::addHeader);
        if (body != null) {
            request.setContentType(contentType);
            request.setCharacterEncoding(StandardCharsets.UTF_8.name());
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return ServerRequest.create(request, MESSAGE_CONVERTERS);
    }

    private String concretePath() {
        String path = pathTemplate == null ? "/" : pathTemplate;
        for (Map.Entry<String, String> entry : pathVars.entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return path;
    }

    /** 与 Web 入口一致：清理 biz.* 前缀键与 traceId，防止测试线程复用导致 MDC 串号 */
    private void clearMdc() {
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();
        if (mdcMap == null) {
            return;
        }
        mdcMap.keySet().stream()
                .filter(key -> key.startsWith(StarterStep.MDC_PREFIX) || StepContext.TRACE_ID_KEY.equals(key))
                .forEach(MDC::remove);
    }
}
