package com.example.myapp.framework.web;

import com.example.myapp.framework.core.EndpointSpec;
import com.example.myapp.framework.core.ErrorCoded;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.StepExecutionException;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.steps.HttpStepException;
import com.example.myapp.framework.steps.StarterStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
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

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
public final class UseCaseRouterFactory {

    private static final Logger log = LoggerFactory.getLogger(UseCaseRouterFactory.class);

    /** biz 区中 traceId 的约定键名 */
    public static final String TRACE_ID_KEY = "traceId";
    private static final String CONTEXT_ATTRIBUTE = StepContext.class.getName();

    private final Map<String, Integer> errorMappings;

    public UseCaseRouterFactory(Map<String, Integer> errorMappings) {
        this.errorMappings = errorMappings;
    }

    public RouterFunction<ServerResponse> build(UseCaseRegistry registry) {
        RouterFunctions.Builder builder = RouterFunctions.route();
        for (UseCase useCase : registry.all()) {
            if (useCase.isShared()) {
                continue;   // shared 用例不绑定 endpoint，仅作为子用例被内嵌调用
            }
            builder.route(predicateFor(useCase.getEndpoint()), request -> invoke(useCase, request));
        }
        builder.onError(thrown -> true, (thrown, request) -> toErrorResponse(thrown, request));
        return builder.build();
    }

    private RequestPredicate predicateFor(EndpointSpec endpoint) {
        return RequestPredicates.method(HttpMethod.valueOf(endpoint.method().toUpperCase(Locale.ROOT)))
                .and(RequestPredicates.path(endpoint.path()));
    }

    private ServerResponse invoke(UseCase useCase, ServerRequest request) {
        StepContext context = new StepContext(new WebExchangeRequest(request));
        seedTraceId(context);
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

    /** 种子化 traceId：优先复用调用方传入的 X-Request-Id，否则生成 UUID。 */
    private void seedTraceId(StepContext context) {
        String traceId = context.getRequest().header("X-Request-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        context.putBiz(TRACE_ID_KEY, traceId);
    }

    private String traceIdOf(StepContext context) {
        Object value = context.getBiz(TRACE_ID_KEY);
        return value == null ? null : String.valueOf(value);
    }

    /** 清理 starter 写入的 MDC（biz.* 前缀），避免容器线程复用导致日志串号。 */
    private void clearBizMdc() {
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();
        if (mdcMap == null) {
            return;
        }
        mdcMap.keySet().stream()
                .filter(key -> key.startsWith(StarterStep.MDC_PREFIX))
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

        if (cause instanceof ErrorCoded || reflectiveErrorCode(cause) != null) {
            int status = resolveStatus(cause);
            String code = cause instanceof ErrorCoded coded ? coded.getErrorCode() : reflectiveErrorCode(cause);
            String message = status >= 500 ? "Internal server error" : cause.getMessage();
            if (status >= 500) {
                log.error("usecase failed", thrown);
            } else {
                log.warn("usecase failed: {}", cause.getMessage());
            }
            return json(status, ApiResponse.error(code, message, traceId));
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
