package com.example.myapp.framework.test;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCase.EndpointSpec;
import com.example.myapp.framework.core.UseCaseRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UseCaseScenario#expectDataflow} 数据链断言：中间态读写验证、失败消息可读、自动录制接入。
 */
class UseCaseScenarioDataflowTest {

    private final ObjectMapper objectMapper = JsonMapper.shared();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private Step named(String stepName, java.util.function.Consumer<com.example.myapp.framework.core.StepContext> action) {
        record NamedStep(String stepName,
                         java.util.function.Consumer<com.example.myapp.framework.core.StepContext> action) implements Step {
            @Override
            public void execute(com.example.myapp.framework.core.StepContext context) {
                action.accept(context);
            }

            @Override
            public String name() {
                return stepName;
            }
        }
        return new NamedStep(stepName, action);
    }

    private UseCaseRegistry registry() {
        UseCase useCase = new UseCase("uc1", null,
                new EndpointSpec(HttpMethod.GET, "/api/x", 200),
                List.of(
                        named("writer", context -> context.putVar("credit", 650)),
                        named("reader", context -> context.setPayload(context.getVar("credit")))),
                false);
        return new UseCaseRegistry(List.of(useCase));
    }

    @Test
    void expectDataflowVerifiesIntermediateReadWrites() {
        ScenarioResult result = UseCaseScenario.given(registry(), objectMapper)
                .request("GET", "/api/x")
                .expectDataflow(flow -> flow
                        .write("writer", "vars.credit")
                        .read("reader", "vars.credit")
                        .noWrite("reader", "vars.credit")   // reader 只消费不回写
                        .noRead("writer", "vars.credit"))
                .expectPayload(650)
                .run();

        // 结果视图同时暴露 trace 供自定义断言
        assertThat(result.trace().writersOf("vars.credit")).isNotEmpty();
    }

    @Test
    void failedDataflowExpectationMessageContainsTraceRender() {
        UseCaseScenario scenario = UseCaseScenario.given(registry(), objectMapper)
                .request("GET", "/api/x")
                .expectDataflow(flow -> flow.write("writer", "vars.nobody"));

        assertThatThrownBy(scenario::run)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("vars.nobody")
                .hasMessageContaining("usecase [uc1]");   // trace.render() 附入失败消息
    }

    @Test
    void noReadExpectationFailsWhenStepReadsKey() {
        UseCaseScenario scenario = UseCaseScenario.given(registry(), objectMapper)
                .request("GET", "/api/x")
                .expectDataflow(flow -> flow.noRead("reader", "vars.credit"));

        assertThatThrownBy(scenario::run)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("vars.credit");
    }

    @Test
    void dataflowAssertionsDoNotRunWhenPipelineFails() {
        UseCaseRegistry registry = new UseCaseRegistry(List.of(new UseCase("boom", null,
                new EndpointSpec(HttpMethod.GET, "/api/boom", 200),
                List.of(named("breaker", context -> {
                    throw new IllegalStateException("boom");
                })), false)));
        UseCaseScenario scenario = UseCaseScenario.given(registry, objectMapper)
                .request("GET", "/api/boom")
                .expectDataflow(flow -> flow.write("writer", "vars.credit"));

        assertThatThrownBy(scenario::run).hasMessageContaining("boom");
    }
}
