package com.example.myapp.framework.assemble;

import java.util.Map;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import com.example.myapp.framework.core.exception.UseCaseAssemblyException;

/**
 * step config Map 的类型化读取器：统一的必填校验与错误信息（携带 step 名）。
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class StepConfig {

    private final Map<String, Object> config;
    private final String label;

    public static StepConfig of(StepDefinition definition) {
        return new StepConfig(definition.configOrEmpty(), definition.nameOr(String.valueOf(definition.type())));
    }

    public String requiredString(String key) {
        String value = optionalString(key);
        if (value == null || value.isBlank()) {
            throw new UseCaseAssemblyException("step [%s]: config '%s' is required".formatted(label, key));
        }
        return value;
    }

    public String optionalString(String key) {
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public String stringOr(String key, String defaultValue) {
        String value = optionalString(key);
        return value == null ? defaultValue : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> mapOrEmpty(String key) {
        Object value = config.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new UseCaseAssemblyException("step [%s]: config '%s' must be a map".formatted(label, key));
    }
}
