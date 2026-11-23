package com.example.usecase.framework.steps.config;

import java.util.Map;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

import com.example.usecase.framework.core.exception.StepValidationException;

/**
 * validator 步骤的 config schema：expression（SpEL 断言）/ schema（内联 JSON Schema）/
 * schemaRef（classpath JSON Schema 资源）三选一，互斥约束以 {@link AssertTrue} 跨字段声明。
 *
 * <p>互斥判断用 {@code hasText} 而非 {@code != null}：{@code expression: ""} 空串在绑定期
 * 即拒绝（否则会绕过互斥校验、运行期首次求值才解析失败）。这属于「可选字段配了就要合规」
 * 约束——@NotBlank 会连 null 一并拒绝、破坏可选语义，故用 hasText 参与互斥 +
 * {@code @Pattern} 拒绝显式空白值。</p>
 *
 * <p>@Data + 字段初始值模式：默认值直接写在字段上，绑定（Jackson）时仅覆盖 YAML 中出现的属性。</p>
 */
@Data
public class ValidatorConfig {

    /** 校验目标 SpEL 表达式，缺省 #payload（空白串覆盖默认值属配置错误，装配期拒绝） */
    @NotBlank
    private String target = "#payload";

    /** 函数模式：返回 boolean 的 SpEL 断言表达式（表达式可含空格——空白与否由 {@link #isExpressionBlankWhenSet} 判定） */
    private @Nullable String expression;

    /** schema 模式：内联 JSON Schema（2020-12 方言），装配期预编译 */
    private @Nullable Map<String, Object> schema;

    /**
     * schema 模式（资源形态）：classpath JSON 资源路径（如 {@code schema/create-user.json}）。
     * 独立资源不经属性绑定，无「全数字键 Map 被还原为 List」的绑定失真——含数字键对象
     * （如 {@code {"const": {"0": "a"}}}）的 schema 必须走此通道。
     */
    @Pattern(regexp = "\\S+", message = "must not be blank when set")
    private @Nullable String schemaRef;

    /** 校验失败消息模板 */
    private @Nullable String message;

    /** 校验失败错误码，缺省 VALIDATION_FAILED（空白会让客户端收到空错误码，装配期拒绝） */
    @NotBlank
    private String errorCode = StepValidationException.DEFAULT_CODE;

    /** expression / schema / schemaRef 必须恰好配其一（空 schema Map 视为未配置，与历史行为一致） */
    @AssertTrue(message = "exactly one of 'expression', 'schema' or 'schemaRef' must be configured")
    public boolean isExactlyOneMode() {
        int configured = 0;
        configured += StringUtils.hasText(expression) ? 1 : 0;
        configured += schema != null && !schema.isEmpty() ? 1 : 0;
        configured += StringUtils.hasText(schemaRef) ? 1 : 0;
        return configured == 1;
    }

    /**
     * expression「配了就要合规」：null 放行（未配置，走 schema/schemaRef 模式），空串/纯空白拒绝
     * ——否则空串会绕过互斥校验、运行期首次求值才解析失败。SpEL 表达式天然可含空格，
     * 故不用 @Pattern("\\S+")（那是单 token 字段如 as/schemaRef 的约束）。
     */
    @AssertTrue(message = "'expression' must not be blank when set")
    public boolean isExpressionBlankWhenSet() {
        return expression == null || StringUtils.hasText(expression);
    }
}
