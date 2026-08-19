package com.example.myapp.framework.steps.config;

import java.util.Map;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import com.example.myapp.framework.core.exception.StepValidationException;

/**
 * validator 步骤的 config schema：expression（SpEL 断言）与 schema（JSON Schema）二选一，
 * 互斥约束以 {@link AssertTrue} 跨字段声明。
 *
 * <p>@Data + 字段初始值模式：默认值直接写在字段上，Jackson 绑定时仅覆盖 YAML 中出现的属性。</p>
 */
@Data
public class ValidatorConfig {

    /** 校验目标 SpEL 表达式，缺省 #payload */
    private String target = "#payload";

    /** 函数模式：返回 boolean 的 SpEL 断言表达式 */
    private String expression;

    /** schema 模式：JSON Schema（2020-12 方言），装配期预编译 */
    private Map<String, Object> schema;

    /** 校验失败消息模板 */
    private String message;

    /** 校验失败错误码，缺省 VALIDATION_FAILED */
    private String errorCode = StepValidationException.DEFAULT_CODE;

    /** expression 与 schema 必须二选一（空 schema Map 视为未配置，与历史行为一致） */
    @AssertTrue(message = "exactly one of 'expression' or 'schema' must be configured")
    public boolean isExactlyOneMode() {
        return (expression != null) != (schema != null && !schema.isEmpty());
    }
}
