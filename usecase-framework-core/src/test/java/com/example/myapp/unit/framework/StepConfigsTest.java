package com.example.myapp.unit.framework;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.steps.config.CodecStepConfig;
import com.example.myapp.framework.steps.config.SubUseCaseConfig;
import com.example.myapp.framework.steps.config.ValidatorConfig;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void unknownConfigKey_failsAtAssembly() {
        // 可选键拼错（isloate）不得被静默忽略后按缺省值运行——配置即契约，装配期显式报错并指认未知键
        assertThatThrownBy(() -> StepConfigs.bind(
                new StepDefinition("sub", "usecase", "childUseCase", Map.of("isloate", true)),
                SubUseCaseConfig.class))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("isloate");
    }

    @Test
    void openMapConfigValues_areNotTreatedAsUnknownProperties() {
        // FAIL_ON_UNKNOWN_PROPERTIES 只作用于封闭 schema 的属性映射：validator 的 schema、
        // auth 的 options 等开放 Map 字段的**内容**键任意，不得被未知键拦截误伤
        var config = StepConfigs.bind(
                new StepDefinition("validator", "validator", null,
                        Map.of("schema", Map.of("type", "object", "required", List.of("userId")))),
                ValidatorConfig.class);

        assertThat(config.getSchema()).containsEntry("type", "object");
    }
}
