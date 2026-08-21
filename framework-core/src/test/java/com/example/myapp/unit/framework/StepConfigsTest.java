package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.steps.config.CodecStepConfig;
import com.example.myapp.framework.steps.config.SubUseCaseConfig;
import com.example.myapp.framework.steps.config.ValidatorConfig;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StepConfig 空白串硬化：带默认值的字段被 YAML 空白串覆盖时装配期 fail-fast，
 * 而非运行期 SpEL 解析失败（500）或空错误码流向客户端。
 */
class StepConfigsTest {

    @Test
    void blankCodecSource_isRejectedAtAssembly() {
        assertThatThrownBy(() -> StepConfigs.bind(
                new StepDefinition("codec", "encoder", null, Map.of("algorithm", "base64", "source", " ")),
                CodecStepConfig.class))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("source");
    }

    @Test
    void blankSubUseCaseInput_isRejectedAtAssembly() {
        assertThatThrownBy(() -> StepConfigs.bind(
                new StepDefinition("sub", "usecase", "someUseCase", Map.of("input", "")),
                SubUseCaseConfig.class))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("input");
    }

    @Test
    void blankValidatorTarget_isRejectedAtAssembly() {
        assertThatThrownBy(() -> StepConfigs.bind(
                new StepDefinition("validator", "validator", null,
                        Map.of("target", " ", "expression", "#payload != null")),
                ValidatorConfig.class))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("target");
    }

    @Test
    void blankValidatorErrorCode_isRejectedAtAssembly() {
        assertThatThrownBy(() -> StepConfigs.bind(
                new StepDefinition("validator", "validator", null,
                        Map.of("expression", "#payload != null", "errorCode", "")),
                ValidatorConfig.class))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("errorCode");
    }
}
