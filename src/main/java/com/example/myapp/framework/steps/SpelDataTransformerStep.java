package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 配置驱动的 DataTransformer：表达式以 {@code #payload} / {@code #vars} 为输入，结果写入 payload 或 {@code as} 变量。
 */
public final class SpelDataTransformerStep extends AbstractSpelStep implements DataTransformer {

    public SpelDataTransformerStep(String name, String expression, String as, StepExpressionEvaluator evaluator) {
        super(name, expression, as, evaluator);
    }
}
