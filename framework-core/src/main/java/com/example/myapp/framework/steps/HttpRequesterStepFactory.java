package com.example.myapp.framework.steps;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.auth.AuthHandler;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.HttpRequesterConfig;

/**
 * httpRequester 类型步骤的工厂：config schema 见 {@link HttpRequesterConfig}（注解校验），
 * auth scheme 的存在性与 options 校验依赖注册表，在此装配期完成（fail-fast）。
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
    public Step create(StepDefinition definition) {
        HttpRequesterConfig config = StepConfigs.bind(definition, HttpRequesterConfig.class);
        String name = definition.nameOr("httpRequester");

        AuthHandler authHandler = null;
        Map<String, Object> authOptions = Map.of();
        if (config.getAuth() != null) {
            String scheme = config.getAuth().getScheme();
            authHandler = authHandlers.get(scheme);
            if (authHandler == null) {
                throw new UseCaseAssemblyException(
                        "step [%s]: unknown auth scheme '%s', available: %s"
                                .formatted(name, scheme, authHandlers.keySet()));
            }
            authOptions = config.getAuth().getOptions();
            authHandler.validate(authOptions);
        }

        return new HttpRequesterStep(name, config.getMethod(), config.getUrl(), config.getUriVariables(),
                config.getHeaders(), config.getBody(), new AuthSpec(authHandler, authOptions), config.getAs(),
                restClient, evaluator);
    }
}
