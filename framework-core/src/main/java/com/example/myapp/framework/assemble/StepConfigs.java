package com.example.myapp.framework.assemble;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import lombok.experimental.UtilityClass;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import com.example.myapp.framework.core.exception.UseCaseAssemblyException;

/**
 * step config 的声明式绑定器：{@code Map<String, Object>} → 类型化 config record
 * （Jackson convertValue）→ Bean Validation 校验，约束违反与绑定失败均包装为
 * {@link UseCaseAssemblyException}（携带 step 名，装配期 fail-fast）。
 *
 * <p>每种 step 的 config schema 见 {@code framework.steps.config} 包下对应 record——
 * 约束以注解声明（{@code @NotBlank} / {@code @Pattern} / 跨字段 {@code @AssertTrue}），
 * 枚举组件（如 SLF4J Level）由类型系统直接保证合法性，StepFactory 不再承担 null check。</p>
 */
@UtilityClass
public class StepConfigs {

    /** 大小写不敏感枚举绑定：对齐 Spring Boot 宽松绑定体验（level: debug ≡ DEBUG） */
    private final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();
    private final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * 绑定并校验 step config。
     *
     * @param definition step 定义（提供原始 config Map 与 step 名）
     * @param configType 目标 config record 类型
     * @return 校验通过的强类型 config
     * @throws UseCaseAssemblyException 绑定失败（如非法枚举值）或约束违反（信息含 step 名与明细）
     */
    public <T> T bind(StepDefinition definition, Class<T> configType) {
        String label = definition.nameOr(String.valueOf(definition.type()));
        T config = convert(definition, configType, label);
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(config);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> "config '%s' %s".formatted(violation.getPropertyPath(), violation.getMessage()))
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new UseCaseAssemblyException("step [%s]: %s".formatted(label, details));
        }
        return config;
    }

    private <T> T convert(StepDefinition definition, Class<T> configType, String label) {
        try {
            return MAPPER.convertValue(definition.configOrEmpty(), configType);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new UseCaseAssemblyException(
                    "step [%s]: invalid config: %s".formatted(label, e.getMessage()), e);
        }
    }
}
