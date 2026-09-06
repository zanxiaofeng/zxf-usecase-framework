package com.example.myapp.framework.autoconfigure;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置的条件化装配（starter 契约）：
 * 非 Web 环境管道可用（Registry / Invoker）但路由跳过；Web 环境路由装配；
 * 用户自定义同类型 Bean 替换内置实现。
 */
class AutoConfigurationConditionTest {

    /** 非 Web 环境需手动提供 RestClient.Builder（正常应用由 RestClientAutoConfiguration 提供） */
    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withConfiguration(AutoConfigurations.of(UseCaseFrameworkAutoConfiguration.class));

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withConfiguration(AutoConfigurations.of(UseCaseFrameworkAutoConfiguration.class));

    @Test
    void nonWebContext_assemblesPipelineButSkipsRouterAndLogger() {
        nonWebRunner.run(context -> {
            assertThat(context).hasSingleBean(UseCaseRegistry.class);      // 管道装配仍可用（非 HTTP 驱动场景）
            assertThat(context).hasSingleBean(UseCaseInvoker.class);
            assertThat(context).doesNotHaveBean("useCaseRouterFunction");  // 路由绑定仅 Servlet Web 环境
            assertThat(context).doesNotHaveBean("useCaseRouteLogger");
        });
    }

    @Test
    void webContext_registersRouterFunction() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(UseCaseRegistry.class);
            assertThat(context).hasBean("useCaseRouterFunction");
            assertThat(context).hasBean("useCaseRouteLogger");
        });
    }

    @Test
    void customEvaluatorBean_replacesBuiltIn() {
        StepExpressionEvaluator custom = new StepExpressionEvaluator(null);
        nonWebRunner
                .withBean("customEvaluator", StepExpressionEvaluator.class, () -> custom)
                .run(context -> assertThat(context.getBean(StepExpressionEvaluator.class)).isSameAs(custom));
    }

    @Test
    void customAuthHandlerBean_overridesBuiltInByScheme() {
        // 内置 AuthHandler 非独立 Bean：自定义 Bean 经 authHandlerMap 同名 scheme 覆盖内置
        nonWebRunner
                .withBean("myBasicAuthHandler", com.example.myapp.framework.auth.AuthHandler.class,
                        com.example.myapp.framework.auth.BasicAuthHandler::new)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    Map<String, com.example.myapp.framework.auth.AuthHandler> map =
                            (Map<String, com.example.myapp.framework.auth.AuthHandler>) context.getBean("authHandlerMap");
                    assertThat(map.get("basic")).isSameAs(context.getBean("myBasicAuthHandler"));
                });
    }
}
