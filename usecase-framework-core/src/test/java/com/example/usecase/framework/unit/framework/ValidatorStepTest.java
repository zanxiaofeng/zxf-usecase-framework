package com.example.usecase.framework.unit.framework;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.example.usecase.framework.assemble.StepDefinition;
import com.example.usecase.framework.core.spi.Step;
import com.example.usecase.framework.core.context.StepContext;
import com.example.usecase.framework.core.exception.StepValidationException;
import com.example.usecase.framework.core.exception.UseCaseAssemblyException;
import com.example.usecase.framework.expression.StepExpressionEvaluator;
import com.example.usecase.framework.steps.ValidatorStepFactory;

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
        StepContext context = StepContext.standalone();
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
        // #payload 为 null 时取 .score 抛 SpEL 求值异常：语义上是"校验无法通过"→ 400 而非 500；
        // 求值错误原文（含类型名/表达式结构）只进日志，客户端消息为脱敏固定文案
        Step step = factory.create(new StepDefinition("check", "validator", null,
                Map.of("expression", "#payload.score >= 600", "message", "信用分校验失败")));
        assertThatThrownBy(() -> step.execute(contextWithPayload(null)))
                .isInstanceOfSatisfying(StepValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.defaultHttpStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("信用分校验失败").contains("表达式求值失败");
                    assertThat(e.getMessage()).doesNotContain("EL1007E").doesNotContain("#payload.score");
                });
    }

    @Test
    void expressionNullResultFailsClosed() {
        // fail-closed：表达式求值为 null（断言依赖的数据缺失）视为校验失败，不再静默放行
        Step step = factory.create(new StepDefinition("checkNull", "validator", null,
                Map.of("expression", "#payload", "message", "数据缺失")));
        assertThatThrownBy(() -> step.execute(contextWithPayload(null)))
                .isInstanceOfSatisfying(StepValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.getMessage()).contains("数据缺失");
                });
    }

    @Test
    void expressionNonBooleanResultFailsFastAsConfigDefect() {
        // 非 boolean 返回值属表达式编写缺陷：抛 IllegalStateException（配置/契约错误）而非静默通过
        Step step = factory.create(new StepDefinition("checkNonBool", "validator", null,
                Map.of("expression", "#payload + 1")));
        assertThatThrownBy(() -> step.execute(contextWithPayload(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must evaluate to boolean");
    }

    @Test
    void blankExpressionFailsAtAssembly() {
        // expression: "" 空串在绑定期拒绝（此前空串绕过互斥校验、运行期首次求值才失败）
        assertThatThrownBy(() -> factory.create(new StepDefinition("checkBlank", "validator", null,
                Map.of("expression", "", "schema", Map.of("type", "object")))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("expression");
    }

    @Test
    void schemaRefLoadsIndependentResource_preservingNumericKeyObjects() {
        // schemaRef 独立资源直读 JSON：不经属性绑定，无「全数字键 Map 被还原为 List」的失真——
        // {"const": {"0": "a", "1": "b"}} 的对象语义保持原形（内联写法会被启发式还原破坏）
        Step step = factory.create(new StepDefinition("checkRef", "validator", null,
                Map.of("schemaRef", "schema/numeric-keys-schema.json")));

        // 恰好等于该数字键对象的 target 通过
        Map<String, Object> matching = new LinkedHashMap<>();
        matching.put("0", "a");
        matching.put("1", "b");
        StepContext matchContext = StepContext.standalone();
        matchContext.setPayload(matching);
        assertDoesNotThrow(() -> step.execute(matchContext));

        // 数组形态（被误转后的语义）不通过——证明资源路径保持了对象语义
        StepContext arrayContext = StepContext.standalone();
        arrayContext.setPayload(List.of("a", "b"));
        assertThatThrownBy(() -> step.execute(arrayContext))
                .isInstanceOf(StepValidationException.class);
    }

    @Test
    void schemaRefMissingResourceFailsAtAssembly() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("checkMissing", "validator", null,
                Map.of("schemaRef", "schema/not-exists.json"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("schemaRef");
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
