package com.example.myapp.framework.steps;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.core.HttpRequester;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.HttpStepException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 配置驱动的外部 HTTP 调用步骤。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * - name: fetchCredit
 *   type: httpRequester
 *   config:
 *     method: GET                                  # 缺省 GET
 *     url: "https://credit.internal/scores/{userId}"
 *     uriVariables:                                # URI 模板变量，值支持 SpEL
 *       userId: "#path.id"
 *     headers:                                     # 值支持字面量 / #{...} 模板 / SpEL
 *       X-Request-From: "usecase-framework"
 *     body: "{'id': #payload.id}"                  # 可选，SpEL 表达式，结果序列化为 JSON
 *     auth:                                        # 可选，挂接 AuthHandler
 *       scheme: bearer                             # none/basic/bearer/apiKey/clientCredentials/自定义
 *       options:
 *         token: "${credit.token}"
 *     as: credit                                   # 可选：写入 #vars.credit 而不占用 payload
 * }</pre>
 *
 * <p>非 2xx 响应抛 {@link HttpStepException}；连接失败/超时由 RestClient 抛出
 * ResourceAccessException，传输层统一映射为 502。</p>
 */
@Slf4j
@RequiredArgsConstructor
public final class HttpRequesterStep implements HttpRequester {

    private final String name;
    private final HttpMethod method;
    private final String url;
    private final Map<String, Object> uriVariables;
    private final Map<String, Object> headers;
    private final String bodyExpression;
    private final AuthSpec authSpec;
    private final String as;
    private final RestClient restClient;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        Map<String, Object> resolvedUriVariables = resolveMap(uriVariables, context);
        RestClient.RequestBodySpec request = restClient.method(method).uri(url, resolvedUriVariables);
        resolveMap(headers, context).forEach((key, value) -> {
            if (value != null) {
                request.header(key, String.valueOf(value));
            }
        });
        authSpec.apply(request);
        if (bodyExpression != null) {
            request.body(evaluator.evaluate(bodyExpression, context));
        }
        log.debug("http step [{}] {} {}", name, method, url);
        Object result = request.exchange((req, res) -> {
            int status = res.getStatusCode().value();
            Object responseBody;
            try {
                responseBody = res.bodyTo(Object.class);
            } catch (Exception e) {
                responseBody = null;
            }
            if (status >= 400) {
                throw new HttpStepException(name, status, snippetOf(responseBody));
            }
            return responseBody;
        });
        context.storeResult(result, as, true);
    }

    private Map<String, Object> resolveMap(Map<String, Object> source, StepContext context) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        source.forEach((key, value) ->
                resolved.put(key, value instanceof String text ? evaluator.resolve(text, context) : value));
        return resolved;
    }

    private static String snippetOf(Object responseBody) {
        if (responseBody == null) {
            return "";
        }
        String text = String.valueOf(responseBody);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
