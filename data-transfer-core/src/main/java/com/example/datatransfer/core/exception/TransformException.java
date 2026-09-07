package com.example.datatransfer.core.exception;

import org.jspecify.annotations.Nullable;

import lombok.Getter;

/**
 * 运行期变换链错误：未知函数、函数执行失败（参数非法/类型不符）、when 条件求值失败。
 * 携带规则上下文（评审 4.2：错误信息必须包含规则索引、路径、原始值与失败原因）。
 */
@Getter
public class TransformException extends TransferException {

    /** 规则索引（spec.rules 中的位置，0 起；条件求值失败时为所在规则） */
    private final int ruleIndex;

    private final String from;

    private final String to;

    /** 失败环节的变换函数名或条件文本（诊断用） */
    private final @Nullable String stage;

    /** 失败时的输入值（toString 截断，防大对象刷屏） */
    private final @Nullable String value;

    public TransformException(String message, int ruleIndex, String from, String to,
                              @Nullable String stage, @Nullable Object value, @Nullable Throwable cause) {
        super(message
                + " (rule #" + ruleIndex + " [" + from + " -> " + to + "]"
                + (stage == null ? "" : ", stage: " + stage)
                + (value == null ? "" : ", value: " + abbreviate(value))
                + ")", cause);
        this.ruleIndex = ruleIndex;
        this.from = from;
        this.to = to;
        this.stage = stage;
        this.value = value == null ? null : abbreviate(value);
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() > 200 ? text.substring(0, 200) + "...(truncated)" : text;
    }
}
