package com.example.myapp.framework.steps.config;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * starter 步骤的 config schema：keys 为「业务标识名 → 提取表达式」映射，
 * 键与表达式均不允许空白。
 */
public record StarterConfig(

        /** 业务标识提取表达式（必填且非空），如 {@code businessId: "#path.id"} */
        @NotEmpty Map<@NotBlank String, @NotBlank String> keys) {
}
