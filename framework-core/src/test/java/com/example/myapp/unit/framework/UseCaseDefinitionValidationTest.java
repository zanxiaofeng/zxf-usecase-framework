package com.example.myapp.unit.framework;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.UseCaseDefinition;
import com.example.myapp.framework.assemble.UseCaseProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * usecase.definitions 体系的声明式约束直验（不经 Spring 上下文）：
 * 容器元素级联（List<@Valid>）、字段级约束与「shared 用例可缺省 endpoint」的边界。
 */
class UseCaseDefinitionValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validDefinition_hasNoViolations() {
        UseCaseDefinition definition = new UseCaseDefinition("demo", null, false,
                new UseCaseDefinition.Endpoint(HttpMethod.GET, "/api/x", 200),
                List.of(step()));

        assertThat(validator.validate(propertiesOf(definition))).isEmpty();
    }

    @Test
    void blankId_isRejected() {
        UseCaseDefinition definition = new UseCaseDefinition(" ", null, false, null, List.of(step()));

        assertThat(validator.validate(propertiesOf(definition)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("definitions[0].id"));
    }

    @Test
    void emptySteps_isRejected() {
        UseCaseDefinition definition = new UseCaseDefinition("demo", null, false, null, List.of());

        assertThat(validator.validate(propertiesOf(definition)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("definitions[0].steps"));
    }

    @Test
    void blankEndpointPath_isRejected() {
        UseCaseDefinition definition = new UseCaseDefinition("demo", null, false,
                new UseCaseDefinition.Endpoint(HttpMethod.GET, " ", 200), List.of(step()));

        assertThat(validator.validate(propertiesOf(definition)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath())
                        .hasToString("definitions[0].endpoint.path"));
    }

    @Test
    void statusOutOfRange_isRejected() {
        UseCaseDefinition low = new UseCaseDefinition("demo", null, false,
                new UseCaseDefinition.Endpoint(HttpMethod.GET, "/api/x", 99), List.of(step()));
        UseCaseDefinition high = new UseCaseDefinition("demo", null, false,
                new UseCaseDefinition.Endpoint(HttpMethod.GET, "/api/x", 600), List.of(step()));

        assertThat(validator.validate(propertiesOf(low)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath())
                        .hasToString("definitions[0].endpoint.status"));
        assertThat(validator.validate(propertiesOf(high)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath())
                        .hasToString("definitions[0].endpoint.status"));
    }

    @Test
    void nullStepElement_isRejected() {
        // 容器元素约束：YAML 空元素（`- `）绑定为 null 时不得静默穿过 @NotEmpty/@Valid（List.of 不收 null，用 Arrays.asList）
        UseCaseDefinition definition = new UseCaseDefinition("demo", null, false, null,
                Arrays.asList((StepDefinition) null));

        assertThat(validator.validate(propertiesOf(definition)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .startsWith("definitions[0].steps[0]"));
    }

    @Test
    void nullDefinitionElement_isRejected() {
        UseCaseProperties properties = new UseCaseProperties(Arrays.asList((UseCaseDefinition) null),
                Map.of(), true, new UseCaseProperties.Trace(false, false));

        assertThat(validator.validate(properties))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .startsWith("definitions[0]"));
    }

    @Test
    void sharedWithoutEndpoint_hasNoViolations() {
        // shared 用例缺省 endpoint 合法（@Valid 级联在 null 时跳过）；非 shared 缺 endpoint 属交叉规则，由装配器拦截
        UseCaseDefinition shared = new UseCaseDefinition("shared", null, true, null, List.of(step()));

        assertThat(validator.validate(propertiesOf(shared))).isEmpty();
    }

    @Test
    void errorMappingStatusOutOfRange_isRejected() {
        UseCaseProperties properties = new UseCaseProperties(List.of(), Map.of("com.example.SomeException", 99),
                true, new UseCaseProperties.Trace(false, false));

        assertThat(validator.validate(properties))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).startsWith("errorMappings"));
    }

    private static StepDefinition step() {
        return new StepDefinition(null, "logging", null, Map.of());
    }

    private static UseCaseProperties propertiesOf(UseCaseDefinition definition) {
        return new UseCaseProperties(List.of(definition), Map.of(), true, new UseCaseProperties.Trace(false, false));
    }
}
