package com.example.myapp.framework.config;

import java.util.Map;

/**
 * 单个 step 的配置定义。
 *
 * @param name   步骤名（日志与异常定位用），缺省取 type
 * @param type   内置步骤类型：dataLoader / dataTransformer / httpRequester / dataSaver，
 *               或任何通过 StepFactory Bean 扩展的自定义类型
 * @param ref    自定义 Step Bean 名（与 type 二选一）
 * @param config 该步骤的类型化配置（由各 StepFactory 解释）
 */
public record StepDefinition(String name, String type, String ref, Map<String, Object> config) {

    public Map<String, Object> configOrEmpty() {
        return config == null ? Map.of() : config;
    }

    public String nameOr(String fallback) {
        return name == null || name.isBlank() ? String.valueOf(fallback) : name;
    }
}
