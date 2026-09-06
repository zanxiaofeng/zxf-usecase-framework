package com.example.myapp.framework.steps;

import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
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

    /** 标准 HTTP 方法白名单：Jackson 绑定 HttpMethod 走 valueOf 语义（SF7 对未知方法名静默构造自定义实例），
     *  拼写错误（如 GTE）与小写变体（如 get，不等同 GET 常量）在此装配期显式拦截（与 UseCaseAssembler 的 endpoint 白名单同构） */
    private static final Set<HttpMethod> STANDARD_HTTP_METHODS = Set.of(HttpMethod.values());

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

        if (!STANDARD_HTTP_METHODS.contains(config.getMethod())) {
            throw new UseCaseAssemblyException(
                    "step [%s]: method [%s] is not a standard HTTP method (expected one of %s)"
                            .formatted(name, config.getMethod(), STANDARD_HTTP_METHODS));
        }

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
                config.getHeaders(), config.getBody(), new HttpRequesterStep.AuthSpec(authHandler, authOptions), config.getAs(),
                restClient, evaluator);
    }
}
