package com.example.myapp.framework.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import com.example.myapp.framework.core.UseCaseRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * usecase.* 配置绑定期的 Bean Validation（UseCaseProperties @Validated）：
 * 非法字段值在启动期 fail-fast，错误消息携带属性路径（definitions[0].id 等）；
 * 交叉规则仍由装配器承担（见 UseCaseAssemblerTest）。
 */
class UseCasePropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withConfiguration(AutoConfigurations.of(UseCaseFrameworkAutoConfiguration.class));

    @Test
    void blankUseCaseId_failsStartupAtBindingPhase() {
        runner.withPropertyValues(
                        "usecase.definitions[0].id= ",
                        "usecase.definitions[0].steps[0].type=logging")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messagesOf(context.getStartupFailure())).contains("definitions[0].id");
                });
    }

    @Test
    void errorMappingStatusOutOfRange_failsStartupAtBindingPhase() {
        runner.withPropertyValues("usecase.error-mappings.com.example.SomeException=99")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messagesOf(context.getStartupFailure())).contains("errorMappings");
                });
    }

    @Test
    void endpointStatusOutOfRange_failsStartupAtBindingPhase() {
        runner.withPropertyValues(
                        "usecase.definitions[0].id=demo",
                        "usecase.definitions[0].endpoint.method=GET",
                        "usecase.definitions[0].endpoint.path=/api/x",
                        "usecase.definitions[0].endpoint.status=99",
                        "usecase.definitions[0].steps[0].type=logging")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messagesOf(context.getStartupFailure())).contains("status");
                });
    }

    @Test
    void validDefinitions_startNormally() {
        runner.withPropertyValues(
                        "usecase.definitions[0].id=demo",
                        "usecase.definitions[0].endpoint.method=GET",
                        "usecase.definitions[0].endpoint.path=/api/x/{id}",
                        "usecase.definitions[0].steps[0].type=logging")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UseCaseRegistry.class);
                });
    }

    /** 收集异常链上全部消息（绑定违例明细在 BindValidationException 的消息体中） */
    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
