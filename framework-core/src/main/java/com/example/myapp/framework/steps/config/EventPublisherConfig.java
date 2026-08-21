package com.example.myapp.framework.steps.config;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

/**
 * eventPublisher 步骤的 config schema。
 */
public record EventPublisherConfig(

        /** 事件对象 SpEL 表达式（必填），求值结果即待发布事件 */
        @NotBlank String event,

        /** EventPublisher 实现 Bean 名；缺省取容器中唯一实现 */
        @Nullable String publisher) {
}
