package com.example.myapp.framework.web;

import com.example.myapp.framework.core.UseCase.EndpointSpec;
import com.example.myapp.framework.core.exception.ErrorCoded;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.StepExecutionException;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.exception.HttpStepException;
import com.example.myapp.framework.steps.StarterStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 把 UseCaseRegistry 中所有用例绑定为一个 {@link RouterFunction}：
 * 每条路由 = endpoint(method + path) → usecase 管道执行 → ApiResponse 信封。
 *
 * <p>入口职责：</p>
 * <ul>
 *   <li>管道执行前往 biz 关键数据区写入 {@code traceId}（取 X-Request-Id 请求头，缺省生成 UUID），
 *       并随 ApiResponse.traceId 回填；</li>
 *   <li>管道结束后清理 starter 写入的 MDC（{@code biz.*} 前缀），防止线程复用串号。</li>
 * </ul>
 *
 * <p>异常映射（等价于原 solution 的 GlobalExceptionHandler，自包含于 router，无需 @RestControllerAdvice）：</p>
 * <ul>
 *   <li>领域异常（ErrorCoded）：状态码按 usecase.error-mappings → @ResponseStatus → 500 的顺序解析；
 *       状态码 &lt; 500 时透传业务消息，&ge; 500 时使用固定文案；</li>
 *   <li>下游 HTTP 失败（HttpStepException / RestClientResponseException / ResourceAccessException）→ 502；</li>
 *   <li>其余兜底 → 500 固定文案，绝不回显内部异常消息。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public final class UseCaseRouterFactory {

    /** biz 区中 traceId 的约定键名 */
    public static final String TRACE_ID_KEY = "traceId";
    /** traceId 白名单：调用方传入的 X-Request-Id 不合法（含控制字符/分隔符等注入载荷）时丢弃重新生成 */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    private static final String CONTEXT_ATTRIBUTE = StepContext.class.getName();

    private final Map<String, Integer> errorMappings;
    private final ObjectMapper objectMapper;

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
        builder.onError(thrown -> true, (thrown, request) -> toErrorResponse(thrown, request));
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
            return ServerResponse.status(useCase.getEndpoint().status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.success(payload, traceIdOf(context)));
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
        context.putBiz(TRACE_ID_KEY, traceId);
        MDC.put(TRACE_ID_KEY, traceId);
    }

    private String traceIdOf(StepContext context) {
        Object value = context.getBiz(TRACE_ID_KEY);
        return value == null ? null : String.valueOf(value);
    }

    /** 清理 starter 写入的 MDC（biz.* 前缀）与入口写入的 traceId，避免容器线程复用导致日志串号。 */
    private void clearBizMdc() {
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();
        if (mdcMap == null) {
            return;
        }
        mdcMap.keySet().stream()
                .filter(key -> key.startsWith(StarterStep.MDC_PREFIX) || TRACE_ID_KEY.equals(key))
                .forEach(MDC::remove);
    }

    // ------------------------------------------------------------------
    // 异常 → HTTP 响应
    // ------------------------------------------------------------------

    private ServerResponse toErrorResponse(Throwable thrown, ServerRequest request) {
        Throwable cause = unwrap(thrown);
        String traceId = null;
        if (request.attributes().get(CONTEXT_ATTRIBUTE) instanceof StepContext context) {
            traceId = traceIdOf(context);
        }

        String errorCode = cause instanceof ErrorCoded coded ? coded.getErrorCode() : reflectiveErrorCode(cause);
        if (errorCode != null) {
            int status = resolveStatus(cause);
            String message = status >= 500 ? "Internal server error" : cause.getMessage();
            if (status >= 500) {
                log.error("usecase failed", thrown);
            } else {
                log.warn("usecase failed: {}", cause.getMessage());
            }
            return json(status, ApiResponse.error(errorCode, message, traceId));
        }
        if (cause instanceof HttpStepException
                || cause instanceof RestClientResponseException
                || cause instanceof ResourceAccessException) {
            log.warn("downstream call failed: {}", cause.getMessage());
            return json(502, ApiResponse.error("DOWNSTREAM_ERROR", "Downstream service call failed", traceId));
        }
        log.error("unexpected error in usecase pipeline", thrown);
        return json(500, ApiResponse.error("INTERNAL_ERROR", "Internal server error", traceId));
    }

    /** 沿 StepExecutionException 包装链还原原始异常。 */
    private Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current instanceof StepExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 状态码解析优先级：usecase.error-mappings（全限定名 → 简单名）→ @ResponseStatus
     * → ErrorCoded.defaultHttpStatus()（如 StepValidationException 默认 400）→ 500。
     */
    private int resolveStatus(Throwable cause) {
        Class<?> exceptionType = cause.getClass();
        Integer status = errorMappings.get(exceptionType.getName());
        if (status == null) {
            status = errorMappings.get(exceptionType.getSimpleName());
        }
        if (status == null) {
            ResponseStatus annotation = exceptionType.getAnnotation(ResponseStatus.class);
            if (annotation != null) {
                status = annotation.value().value();
            }
        }
        if (status == null && cause instanceof ErrorCoded coded) {
            status = coded.defaultHttpStatus();
        }
        return status == null ? 500 : status;
    }

    /** 领域异常未实现 ErrorCoded 时的反射回退：读取 getErrorCode()。 */
    private String reflectiveErrorCode(Throwable cause) {
        try {
            Method method = cause.getClass().getMethod("getErrorCode");
            Object value = method.invoke(cause);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private ServerResponse json(int status, ApiResponse<?> body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
