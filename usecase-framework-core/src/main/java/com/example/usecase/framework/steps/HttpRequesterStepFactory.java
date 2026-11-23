package com.example.usecase.framework.steps;

import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.example.usecase.framework.assemble.StepConfigs;
import com.example.usecase.framework.assemble.StepDefinition;
import com.example.usecase.framework.assemble.StepFactory;
import com.example.usecase.framework.auth.AuthHandler;
import com.example.usecase.framework.core.spi.Step;
import com.example.usecase.framework.core.exception.UseCaseAssemblyException;
import com.example.usecase.framework.expression.StepExpressionEvaluator;
import com.example.usecase.framework.steps.config.HttpRequesterConfig;

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

        // 表达式值（uriVariables / headers 的字符串值 / body）装配期语法预解析；url 是 ${} 占位符
        // 与 {var} URI 模板的混合、非 SpEL，不在其列
        evaluator.precompileForStep(name, stringValuesOf(config.getUriVariables()));
        evaluator.precompileForStep(name, stringValuesOf(config.getHeaders()));
        evaluator.precompileForStep(name, config.getBody());

        return new HttpRequesterStep(name, config.getMethod(), config.getUrl(), config.getUriVariables(),
                config.getHeaders(), config.getBody(), new HttpRequesterStep.AuthSpec(authHandler, authOptions), config.getAs(),
                restClient, evaluator);
    }

    /** 提取 Map 值中的字符串元素（header/uriVariable 的值约定为字符串/表达式，非字符串原样跳过） */
    private static String[] stringValuesOf(Map<String, Object> values) {
        return values.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toArray(String[]::new);
    }
}
