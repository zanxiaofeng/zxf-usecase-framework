package com.example.myapp.unit.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.DataTransferStep;
import com.example.myapp.framework.steps.DataTransferStepFactory;

import tools.jackson.databind.ObjectMapper;

/** dataTransfer step 装配期行为：成功装配 / spec 缺失与校验失败 fail-fast / 未支持特性拒绝。 */
class DataTransferStepFactoryTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);
    private final DataTransferStepFactory factory =
            new DataTransferStepFactory(evaluator, new ObjectMapper());

    @Test
    void create_loadsAndValidatesClasspathSpec() {
        Step step = factory.create(new StepDefinition("toCrmView", "dataTransfer", null,
                Map.of("spec", "transfers/user-to-crm.yaml")));

        assertThat(step).isInstanceOf(DataTransferStep.class);
        assertThat(step.name()).isEqualTo("toCrmView");
        assertThat(step).isInstanceOf(DataTransformer.class);
    }

    @Test
    void create_acceptsClasspathPrefix() {
        Step step = factory.create(new StepDefinition("toCrmView", "dataTransfer", null,
                Map.of("spec", "classpath:transfers/user-to-crm.yaml")));

        assertThat(step).isInstanceOf(DataTransferStep.class);
    }

    @Test
    void missingSpecFailsFastWithStepName() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("toCrmView", "dataTransfer", null,
                Map.of("spec", "transfers/nope.yaml"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("toCrmView")
                .hasMessageContaining("nope.yaml");
    }

    @Test
    void schemaInvalidSpecFailsFast() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("bad", "dataTransfer", null,
                Map.of("spec", "transfers/invalid-spec.yaml"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("bad");
    }

    @Test
    void unsupportedFeatureSpecFailsFast() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("unsup", "dataTransfer", null,
                Map.of("spec", "transfers/unsupported-sources.yaml"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("sources");
    }

    @Test
    void blankConfigRejectedByBeanValidation() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("nospec", "dataTransfer", null,
                Map.of())))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("spec");
    }
}
