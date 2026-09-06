package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.StepValidationException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.SpelDataLoaderStep;
import com.example.myapp.framework.steps.SpelDataSaverStep;
import com.example.myapp.framework.steps.SpelDataTransformerStep;
import com.example.myapp.framework.steps.config.SpelStepConfig.OnNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SpEL 内置步骤语义：@bean 引用、#path/#payload/#vars 变量、as 旁路输出、saver 的 null 保护。
 */
class SpelStepTest {

    /** 模拟出端口 Bean */
    public static class StubUserRepository {
        public String getById(String id) {
            return "user:" + id;
        }
    }

    private StepExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        StaticApplicationContext applicationContext = new StaticApplicationContext();
        applicationContext.getBeanFactory().registerSingleton("userRepository", new StubUserRepository());
        applicationContext.refresh();
        evaluator = new StepExpressionEvaluator(applicationContext);
    }

    private StepContext newContext() {
        return StepContext.of(
                TestServerRequests.getRequest(Map.of("id", "u1"), Map.of()), new ObjectMapper());
    }

    @Test
    void dataLoaderEvaluatesBeanCallIntoPayload() {
        SpelDataLoaderStep step =
                new SpelDataLoaderStep("loadUser", "@userRepository.getById(#path.id)", null, evaluator);
        StepContext context = newContext();

        step.execute(context);

        assertThat(context.getPayload()).isEqualTo("user:u1");
    }

    @Test
    void asClauseWritesToVarsAndKeepsPayload() {
        SpelDataLoaderStep step =
                new SpelDataLoaderStep("loadUser", "@userRepository.getById(#path.id)", "user", evaluator);
        StepContext context = newContext();
        context.setPayload("original");

        step.execute(context);

        assertThat(context.getVar("user")).isEqualTo("user:u1");
        assertThat(context.getPayload()).isEqualTo("original");
    }

    @Test
    void transformerReadsPayloadAndVars() {
        SpelDataTransformerStep step = new SpelDataTransformerStep(
                "merge", "#payload + '|' + #vars.side", null, OnNull.OVERWRITE, evaluator);
        StepContext context = newContext();
        context.setPayload("main");
        context.putVar("side", "extra");

        step.execute(context);

        assertThat(context.getPayload()).isEqualTo("main|extra");
    }

    @Test
    void transformerNullClearsPayloadWithWarnByDefault() {
        SpelDataTransformerStep step = new SpelDataTransformerStep("pick", "null", null, OnNull.OVERWRITE, evaluator);
        StepContext context = newContext();
        context.setPayload("doomed");

        step.execute(context);

        assertThat(context.getPayload()).isNull();
    }

    @Test
    void transformerNullKeepsPayloadWhenOnNullKeep() {
        SpelDataTransformerStep step = new SpelDataTransformerStep("pick", "null", null, OnNull.KEEP, evaluator);
        StepContext context = newContext();
        context.setPayload("keep-me");

        step.execute(context);

        assertThat(context.getPayload()).isEqualTo("keep-me");
    }

    @Test
    void evaluationFailureMapsTo400WithStepName() {
        // 统一求值收口：SpEL 求值失败（#vars 缺失键上取属性）映射 400 语义并附 step 名
        SpelDataLoaderStep step = new SpelDataLoaderStep("loadCredit", "#vars.credit.score", null, evaluator);
        StepContext context = newContext();

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(StepValidationException.class)
                .hasMessageContaining("loadCredit")
                .hasMessageContaining("evaluation failed");
    }

    @Test
    void dataSaverKeepsPayloadWhenExpressionReturnsNull() {
        SpelDataSaverStep step = new SpelDataSaverStep("save", "null", null, evaluator);
        StepContext context = newContext();
        context.setPayload("keep-me");

        step.execute(context);

        assertThat(context.getPayload()).isEqualTo("keep-me");
    }

    @Test
    void resolveSupportsLiteralTemplateAndExpression() {
        StepContext context = newContext();
        context.putVar("token", "abc");

        assertThat(evaluator.resolve("plain-literal", context)).isEqualTo("plain-literal");
        assertThat(evaluator.resolve("Bearer #{vars.token}", context)).isEqualTo("Bearer abc");
        assertThat(evaluator.resolve("#path.id", context)).isEqualTo("u1");
    }
}
