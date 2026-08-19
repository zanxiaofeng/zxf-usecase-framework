package com.example.myapp.framework.web;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCase.EndpointSpec;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.steps.StarterStep;

/**
 * 把 UseCaseRegistry 中所有用例绑定为一个 {@link RouterFunction}：
 * 每条路由 = endpoint(method + path) → usecase 管道执行 → ApiResponse 信封。
 *
 * <p>入口职责：</p>
 * <ul>
 *   <li>管道执行前往 biz 关键数据区写入 {@code traceId}（取 X-Request-Id 请求头，缺省生成 UUID），
 *       并随 ApiResponse.traceId 回填；</li>
 *   <li>管道结束后清理 starter 写入的 MDC（{@code biz.*} 前缀），防止线程复用串号；</li>
 *   <li>异常 → HTTP 响应的映射委托 {@link ErrorResponseMapper}（等价于 @RestControllerAdvice，
 *       自包含于 router 以适配函数式端点）。</li>
 * </ul>
 */
public final class UseCaseRouterFactory {

    /** traceId 白名单：调用方传入的 X-Request-Id 不合法（含控制字符/分隔符等注入载荷）时丢弃重新生成 */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    /** 请求属性中 StepContext 的键（ErrorResponseMapper 取 traceId 用） */
    static final String CONTEXT_ATTRIBUTE = StepContext.class.getName();
    /** 响应头回填 traceId（对齐 logging.md：客户端可经响应头关联全链路日志） */
    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final ObjectMapper objectMapper;
    private final ErrorResponseMapper errorMapper;

    /** errorMapper 为可替换的策略协作者（DIP），由组合根（AutoConfiguration）装配注入 */
    public UseCaseRouterFactory(ErrorResponseMapper errorMapper, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.errorMapper = errorMapper;
    }

    public RouterFunction<ServerResponse> build(UseCaseRegistry registry) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        boolean anyRoute = false;
        for (UseCase useCase : registry.all()) {
            if (useCase.isShared()) {
                continue;   // shared 用例不绑定 endpoint，仅作为子用例被内嵌调用
            }
            builder.route(predicateFor(useCase.getEndpoint()), request -> invoke(useCase, request));
            anyRoute = true;
        }
        if (!anyRoute) {
            // 无 endpoint 用例（如引入 starter 尚未声明 usecase.definitions）：返回不匹配任何请求的
            // 空路由保证应用可启动——RouterFunctions.Builder.build() 在零路由时会抛
            // "No routes registered"，导致整个上下文启动失败
            return request -> Optional.empty();
        }
        builder.onError(thrown -> true, errorMapper::toErrorResponse);
        return builder.build();
    }

    private RequestPredicate predicateFor(EndpointSpec endpoint) {
        return RequestPredicates.method(endpoint.method())
                .and(RequestPredicates.path(endpoint.path()));
    }

    private ServerResponse invoke(UseCase useCase, ServerRequest request) {
        StepContext context = StepContext.of(request, objectMapper);
        seedTraceId(context, request);
        request.attributes().put(CONTEXT_ATTRIBUTE, context);
        try {
            Object payload = useCase.execute(context);
            String traceId = traceIdOf(context);
            ServerResponse.BodyBuilder response = ServerResponse.status(useCase.getEndpoint().status())
                    .contentType(MediaType.APPLICATION_JSON);
            if (traceId != null) {
                response.header(TRACE_ID_HEADER, traceId);
            }
            return response.body(ApiResponse.success(payload, traceId));
        } finally {
            clearBizMdc();
        }
    }

    /**
     * 种子化 traceId：优先复用调用方传入的 X-Request-Id（须匹配 {@link #TRACE_ID_PATTERN} 白名单，
     * 防日志注入/响应头分裂，不合法即丢弃重新生成），否则生成 UUID。
     * 同步写入 MDC（键 {@code traceId}），供 logback pattern {@code %X{traceId}} 全链路关联。
     */
    private void seedTraceId(StepContext context, ServerRequest request) {
        String raw = request.headers().firstHeader("X-Request-Id");
        String traceId = raw != null && TRACE_ID_PATTERN.matcher(raw).matches()
                ? raw
                : UUID.randomUUID().toString();
        context.putBiz(StepContext.TRACE_ID_KEY, traceId);
        MDC.put(StepContext.TRACE_ID_KEY, traceId);
    }

    static String traceIdOf(StepContext context) {
        Object value = context.getBiz(StepContext.TRACE_ID_KEY);
        return value == null ? null : String.valueOf(value);
    }

    /** 清理 starter 写入的 MDC（biz.* 前缀）与入口写入的 traceId，避免容器线程复用导致日志串号。 */
    private void clearBizMdc() {
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();
        if (mdcMap == null) {
            return;
        }
        mdcMap.keySet().stream()
                .filter(key -> key.startsWith(StarterStep.MDC_PREFIX) || StepContext.TRACE_ID_KEY.equals(key))
                .forEach(MDC::remove);
    }
}
