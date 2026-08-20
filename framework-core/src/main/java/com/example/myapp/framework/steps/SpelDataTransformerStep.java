package com.example.myapp.framework.steps;

import lombok.extern.slf4j.Slf4j;

import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.SpelStepConfig.OnNull;

/**
 * 配置驱动的 DataTransformer：表达式以 {@code #payload} / {@code #vars} 为输入，结果写入 payload 或 {@code as} 变量。
 *
 * <p>表达式求值为 null 且未配置 {@code as} 时，默认清空 payload 并打 WARN（配置写错字段名是最常见的
 * null 成因）；配置 {@code onNull: keep} 可保留原 payload（null 视为「无需变换」）。</p>
 */
@Slf4j
public final class SpelDataTransformerStep extends AbstractSpelStep implements DataTransformer {

    private final OnNull onNull;

    public SpelDataTransformerStep(String name, String expression, String as, OnNull onNull,
                                   StepExpressionEvaluator evaluator) {
        super(name, expression, as, evaluator);
        this.onNull = onNull;
    }

    @Override
    protected boolean overwritePayloadWithNull() {
        return onNull == OnNull.OVERWRITE;
    }

    @Override
    protected void onNullOverwrite() {
        log.warn("step [{}] evaluated to null, payload cleared（若非预期请检查表达式；保留原 payload 请配置 onNull: keep）",
                name());
    }
}
