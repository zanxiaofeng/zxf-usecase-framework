package com.example.myapp.framework.steps;

import java.util.Locale;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.auth.AuthHandler;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * httpRequester 类型步骤的工厂：解析 config，装配期校验 auth scheme 是否存在（fail-fast）。
 */
@RequiredArgsConstructor
public final class HttpRequesterStepFactory implements StepFactory {

    private final RestClient restClient;
    private final Map<String, AuthHandler> authHandlers;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "httpRequester";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Step create(StepDefinition definition) {
        StepConfig config = StepConfig.of(definition);
        String name = definition.nameOr("httpRequester");

        HttpMethod method;
        try {
            method = HttpMethod.valueOf(config.stringOr("method", "GET").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UseCaseAssemblyException(
                    "step [%s]: unsupported http method '%s'".formatted(name, config.optionalString("method")));
        }
        String url = config.requiredString("url");
        Map<String, Object> uriVariables = config.mapOrEmpty("uriVariables");
        Map<String, Object> headers = config.mapOrEmpty("headers");
        String bodyExpression = config.optionalString("body");
        String as = config.optionalString("as");

        AuthHandler authHandler = null;
        Map<String, Object> authOptions = Map.of();
        Map<String, Object> auth = config.mapOrEmpty("auth");
        if (!auth.isEmpty()) {
            Object scheme = auth.get("scheme");
            String authScheme = scheme == null ? null : String.valueOf(scheme);
            if (authScheme == null || !authHandlers.containsKey(authScheme)) {
                throw new UseCaseAssemblyException(
                        "step [%s]: unknown auth scheme '%s', available: %s"
                                .formatted(name, authScheme, authHandlers.keySet()));
            }
            Object options = auth.get("options");
            if (options instanceof Map<?, ?> optionsMap) {
                authOptions = (Map<String, Object>) optionsMap;
            }
            authHandler = authHandlers.get(authScheme);
            authHandler.validate(authOptions);
        }

        return new HttpRequesterStep(name, method, url, uriVariables, headers, bodyExpression,
                new AuthSpec(authHandler, authOptions), as, restClient, evaluator);
    }
}
