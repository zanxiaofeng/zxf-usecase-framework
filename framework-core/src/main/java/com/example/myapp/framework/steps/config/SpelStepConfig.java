package com.example.myapp.framework.steps.config;

import jakarta.validation.constraints.NotBlank;

/**
 * SpEL 三件套（dataLoader / dataTransformer / dataSaver）的 config schema。
 */
public record SpelStepConfig(

        /** SpEL 表达式（必填） */
        @NotBlank String expression,

        /** 结果写入 #vars 的旁路键；缺省写回 payload */
        String as) {
}
