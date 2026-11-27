package com.example.usecase.framework.unit.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.usecase.framework.assemble.StepDefinition;
import com.example.usecase.framework.core.spi.DataTransformer;
import com.example.usecase.framework.core.spi.Step;
import com.example.usecase.framework.core.exception.UseCaseAssemblyException;
import com.example.usecase.framework.expression.StepExpressionEvaluator;
import com.example.usecase.framework.steps.DataTransferStep;
import com.example.usecase.framework.steps.DataTransferStepFactory;

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
    void duplicateKeySpecFailsFast() {
        // 外部评审二轮 P1-3：重复 YAML 键此前被静默后值覆盖（前一份规则丢失而校验通过）——生产入口装配期即拒绝
        assertThatThrownBy(() -> factory.create(new StepDefinition("dup", "dataTransfer", null,
                Map.of("spec", "transfers/duplicate-keys.yaml"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("dup")
                .hasMessageContaining("rules");
    }

    @Test
    void conflictingTargetsAndBadExpressions_failFast() {
        // 外部评审二轮 评审二-10 / 新发现 2：结构冲突与表达式语法错误在装配期暴露，而非首个请求才炸
        assertThatThrownBy(() -> factory.create(new StepDefinition("conflict", "dataTransfer", null,
                Map.of("spec", "transfers/conflicting-targets.yaml"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("conflict")
                .hasMessageContaining("out.id.part");
        assertThatThrownBy(() -> factory.create(new StepDefinition("badexpr", "dataTransfer", null,
                Map.of("spec", "transfers/bad-expression.yaml"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("badexpr")
                .hasMessageContaining("computed #0");
    }

    @Test
    void blankConfigRejectedByBeanValidation() {
        assertThatThrownBy(() -> factory.create(new StepDefinition("nospec", "dataTransfer", null,
                Map.of())))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("spec");
    }
}
