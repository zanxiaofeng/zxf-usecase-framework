package com.example.myapp.framework.assemble;

import java.util.Map;

import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 单个 step 的配置定义。
 *
 * @param name      步骤名（日志与异常定位用），缺省取 type
 * @param type      内置步骤类型：dataLoader / dataTransformer / httpRequester / dataSaver，
 *                  或任何通过 StepFactory Bean 扩展的自定义类型
 * @param ref       自定义 Step Bean 名（与 type 二选一）
 * @param config    该步骤的类型化配置（由各 StepFactory 解释）
 * @param useCaseId 所属用例 id（非配置项，装配期由 UseCaseAssembler 经 {@link #withUseCaseId} 注入）
 */
public record StepDefinition(String name, String type, String ref, Map<String, Object> config,
        String useCaseId) {

    /** 绑定入口声明：存在多个构造器时向 Boot 绑定器指定 canonical 构造器 */
    @ConstructorBinding
    public StepDefinition {
    }

    /** 配置绑定与手工构造入口：useCaseId 缺省 null */
    public StepDefinition(String name, String type, String ref, Map<String, Object> config) {
        this(name, type, ref, config, null);
    }

    /** 返回附带 useCaseId 的副本（装配器构建 step 前调用） */
    public StepDefinition withUseCaseId(String useCaseId) {
        return new StepDefinition(name, type, ref, config, useCaseId);
    }

    public Map<String, Object> configOrEmpty() {
        return config == null ? Map.of() : config;
    }

    public String nameOr(String fallback) {
        return name == null || name.isBlank() ? String.valueOf(fallback) : name;
    }
}
