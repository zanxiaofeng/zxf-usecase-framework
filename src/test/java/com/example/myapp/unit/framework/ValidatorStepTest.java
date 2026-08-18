package com.example.myapp.unit.framework;

import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.SimpleExchangeRequest;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.StepValidationException;
import com.example.myapp.framework.core.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.ValidatorStepFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * validator 步骤：expression / schema 两种互斥模式、失败异常的错误码与默认状态码、装配期互斥校验。
 */
class ValidatorStepTest {

    private final ValidatorStepFactory factory =
            new ValidatorStepFactory(new StepExpressionEvaluator(null), new ObjectMapper());

    private StepContext contextWithPayload(Object payload) {
        StepContext context = new StepContext(SimpleExchangeRequest.of("POST", "/x"));
        context.setPayload(payload);
        return context;
    }

    // ------------------------------------------------------------------
    // expression 模式
    // ------------------------------------------------------------------

    @Test
    void expressionTruePasses() {
        Step step = factory.create(new StepDefinition("check", "validator", null,
                Map.of("expression", "#payload.score >= 600")));
        StepContext context = contextWithPayload(Map.of("score", 700));

        assertDoesNotThrow(() -> step.execute(context));
        assertThat(context.getPayload()).isEqualTo(Map.of("score", 700));   // 校验步不动 payload
    }

    @Test
    void expressionFalseThrowsWithErrorCodeAndMessage() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("expression", "#payload.score >= 600");
        config.put("message", "信用分不足");
        config.put("errorCode", "CREDIT_TOO_LOW");
        Step step = factory.create(new StepDefinition("check", "validator", null, config));

        StepContext context = contextWithPayload(Map.of("score", 500));
        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOfSatisfying(StepValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("CREDIT_TOO_LOW");
                    assertThat(e.getMessage()).contains("信用分不足");
                    assertThat(e.defaultHttpStatus()).isEqualTo(400);   // 传输层默认映射 400
                });
    }

    @Test
    void expressionDefaultErrorCodeIsValidationError() {
        Step step = factory.create(new StepDefinition("check", "validator", null,
                Map.of("expression", "false")));
        assertThatThrownBy(() -> step.execute(contextWithPayload(null)))
                .isInstanceOfSatisfying(StepValidationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void expressionEvaluationFailureMapsToValidation400() {
        // #payload 为 null 时取 .score 抛 SpEL 求值异常：语义上是"校验无法通过"→ 400 而非 500
        Step step = factory.create(new StepDefinition("check", "validator", null,
                Map.of("expression", "#payload.score >= 600", "message", "信用分校验失败")));
        assertThatThrownBy(() -> step.execute(contextWithPayload(null)))
                .isInstanceOfSatisfying(StepValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.defaultHttpStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("信用分校验失败").contains("expression evaluation failed");
                });
    }

    // ------------------------------------------------------------------
    // schema 模式
    // ------------------------------------------------------------------

    private Map<String, Object> schemaConfig() {
        return Map.of(
                "type", "object",
                "required", List.of("userId"),
                "properties", Map.of("userId", Map.of("type", "string", "minLength", 1)));
    }

    @Test
    void schemaValidPasses() {
        Step step = factory.create(new StepDefinition("v", "validator", null,
                Map.of("schema", schemaConfig())));
        assertDoesNotThrow(() -> step.execute(contextWithPayload(Map.of("userId", "u1"))));
    }

    @Test
    void schemaInvalidThrowsWithFieldDetail() {
        Step step = factory.create(new StepDefinition("v", "validator", null,
                Map.of("schema", schemaConfig())));
        assertThatThrownBy(() -> step.execute(contextWithPayload(Map.of("name", "x"))))
                .isInstanceOfSatisfying(StepValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.getMessage()).contains("userId");   // 失败明细包含字段信息
                });
    }

    @Test
    void schemaTypeMismatchFails() {
        Step step = factory.create(new StepDefinition("v", "validator", null,
                Map.of("schema", schemaConfig())));
        assertThatThrownBy(() -> step.execute(contextWithPayload(Map.of("userId", 42))))
                .isInstanceOf(StepValidationException.class);
    }

    // ------------------------------------------------------------------
    // 装配期互斥校验
    // ------------------------------------------------------------------

    @Test
    void bothExpressionAndSchemaFailsAtAssembly() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("v", "validator", null,
                Map.of("expression", "true", "schema", schemaConfig()))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void neitherExpressionNorSchemaFailsAtAssembly() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("v", "validator", null, Map.of())))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("exactly one");
    }
}
