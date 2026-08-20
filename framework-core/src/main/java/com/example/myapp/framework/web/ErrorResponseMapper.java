package com.example.myapp.framework.web;

import java.lang.reflect.Method;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.example.myapp.framework.core.DataSnapshot;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.ErrorCoded;
import com.example.myapp.framework.core.exception.HttpStepException;
import com.example.myapp.framework.core.exception.StepExecutionException;

/**
 * 用例管道异常 → HTTP 响应映射（等价于 @RestControllerAdvice 的职责，自包含于 router 以适配函数式端点）：
 *
 * <ul>
 *   <li>领域异常（ErrorCoded）：状态码按 usecase.error-mappings → @ResponseStatus →
 *       {@code ErrorCoded.defaultHttpStatus()} → 500 的顺序解析；
 *       状态码 &lt; 500 时透传业务消息，&ge; 500 时使用固定文案（不回显内部异常消息）；</li>
 *   <li>下游 HTTP 失败（HttpStepException / RestClientResponseException / ResourceAccessException）→ 502；</li>
 *   <li>其余兜底 → 500 固定文案。</li>
 * </ul>
 *
 * <p>错误映射是可替换的策略协作者（DIP），经构造器注入；定制错误翻译时替换本类 Bean 即可。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ErrorResponseMapper {

    private final Map<String, Integer> errorMappings;

    ServerResponse toErrorResponse(Throwable thrown, ServerRequest request) {
        Throwable cause = unwrap(thrown);
        String traceId = null;
        if (request.attributes().get(UseCaseRouterFactory.CONTEXT_ATTRIBUTE) instanceof StepContext context) {
            traceId = UseCaseRouterFactory.traceIdOf(context);
        }

        String errorCode = cause instanceof ErrorCoded coded ? coded.getErrorCode() : reflectiveErrorCode(cause);
        if (errorCode != null) {
            int status = resolveStatus(cause);
            logFailure(status, thrown, cause);
            String message = status >= 500 ? "Internal server error" : cause.getMessage();
            return json(status, ApiResponse.error(errorCode, message, traceId), traceId);
        }
        if (cause instanceof HttpStepException
                || cause instanceof RestClientResponseException
                || cause instanceof ResourceAccessException) {
            log.warn("downstream call failed: {}", cause.getMessage());
            return json(502, ApiResponse.error("DOWNSTREAM_ERROR", "Downstream service call failed", traceId), traceId);
        }
        log.error("unexpected error in usecase pipeline", thrown);
        return json(500, ApiResponse.error("INTERNAL_ERROR", "Internal server error", traceId), traceId);
    }

    /** 5xx 记 ERROR 附完整堆栈（系统故障）；其余记 WARN 不附堆栈（业务/客户端问题，一次一处日志）。失败现场（若有）一并进日志 */
    private void logFailure(int status, Throwable thrown, Throwable cause) {
        DataSnapshot snapshot = thrown instanceof StepExecutionException see ? see.getDiagnostics() : null;
        if (status >= 500) {
            if (snapshot != null) {
                log.error("usecase failed, snapshot: {}", snapshot, thrown);
                return;
            }
            log.error("usecase failed", thrown);
            return;
        }
        if (snapshot != null) {
            log.warn("usecase failed: {} | snapshot: {}", cause.getMessage(), snapshot);
            return;
        }
        log.warn("usecase failed: {}", cause.getMessage());
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

    private ServerResponse json(int status, ApiResponse<?> body, @Nullable String traceId) {
        ServerResponse.BodyBuilder response = ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON);
        if (traceId != null) {
            response.header(UseCaseRouterFactory.TRACE_ID_HEADER, traceId);
        }
        return response.body(body);
    }
}
