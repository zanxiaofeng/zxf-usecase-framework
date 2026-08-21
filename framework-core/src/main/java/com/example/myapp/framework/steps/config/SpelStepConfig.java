package com.example.myapp.framework.steps.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * SpEL 三件套（dataLoader / dataTransformer / dataSaver）的 config schema。
 */
@Data
public class SpelStepConfig {

    /** SpEL 表达式（必填） */
    @NotBlank
    private String expression;

    /** 结果写入 #vars 的旁路键；缺省写回 payload */
    private @Nullable String as;

    /**
     * 表达式结果为 null 时的 payload 处置（仅 dataTransformer 生效；dataSaver 恒为 KEEP）：
     * OVERWRITE（默认，清空并打 WARN）/ KEEP（保留原 payload）
     */
    private OnNull onNull = OnNull.OVERWRITE;

    /** null 处置策略 */
    public enum OnNull {
        OVERWRITE, KEEP
    }
}
