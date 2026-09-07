package com.example.datatransfer.core.exception;

import org.jspecify.annotations.Nullable;

/** 单条校验失败明细（评审 6.4：失败路径、规则、原始值、错误消息）。 */
public record ValidationFailure(String path, String rule, String message, @Nullable Object actualValue) {

    @Override
    public String toString() {
        return path + ": " + message + " (rule: " + rule
                + (actualValue == null ? "" : ", value: " + actualValue) + ")";
    }
}
