package com.example.myapp.framework.assemble;

import java.util.Map;

import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.StringUtils;

/**
 * 单个 step 的配置定义。
 *
 * <p>可选组件（name/type/ref）标注 {@code @Pattern(regexp = "\\S+")}：null 表示未配置（合法放行），
 * 非 null 但空白属配置手滑，YAML 绑定期即拒绝（编程式装配入口的同等校验由 UseCaseAssembler 的
 * hasText 检查承担）。这里不能用 {@code @NotBlank}——它属「必填」三剑客，连 null 一并拒绝，
 * 会破坏「缺省取 type」「二选一」的 null 语义；@Pattern 属「null 放行」组，恰好表达
 * 「可选但配了就要合规」。与 {@code @Nullable} 同框不冲突：前者管「配了就要合规」，
 * 后者表达「校验前确实可空」。</p>
 *
 * @param name      步骤名（日志与异常定位用），缺省取 type；非 null 时不得空白
 * @param type      内置步骤类型：dataLoader / dataTransformer / httpRequester / dataSaver，
 *                  或任何通过 StepFactory Bean 扩展的自定义类型；非 null 时不得空白
 * @param ref       自定义 Step Bean 名（与 type 二选一）；非 null 时不得空白
 * @param config    该步骤的类型化配置（由各 StepFactory 解释）；缺省空配置——构造期归一化，永不为 null
 * @param useCaseId 所属用例 id（非配置项，装配期由 UseCaseAssembler 经 {@link #withUseCaseId} 注入）
 */
public record StepDefinition(@Pattern(regexp = "\\S+", message = "must not be blank when set") @Nullable String name,
        @Pattern(regexp = "\\S+", message = "must not be blank when set") @Nullable String type,
        @Pattern(regexp = "\\S+", message = "must not be blank when set") @Nullable String ref,
        Map<String, Object> config, @Nullable String useCaseId) {

    /** 绑定入口声明：存在多个构造器时向 Boot 绑定器指定 canonical 构造器 */
    @ConstructorBinding
    public StepDefinition {
        // null → 空配置：绑定与编程式两条入口统一归一化，消费方对 config 零判空
        config = config == null ? Map.of() : config;
    }

    /** 配置绑定与手工构造入口：useCaseId 缺省 null */
    public StepDefinition(String name, String type, String ref, Map<String, Object> config) {
        this(name, type, ref, config, null);
    }

    /** 返回附带 useCaseId 的副本（装配器构建 step 前调用） */
    public StepDefinition withUseCaseId(String useCaseId) {
        return new StepDefinition(name, type, ref, config, useCaseId);
    }

    public String nameOr(String fallback) {
        return StringUtils.hasText(name) ? name : String.valueOf(fallback);
    }
}
