package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.DataLoader;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 配置驱动的 DataLoader：执行 SpEL 表达式（典型为调用出端口 Bean），结果写入 payload 或 {@code as} 变量。
 */
public final class SpelDataLoaderStep extends AbstractSpelStep implements DataLoader {

    public SpelDataLoaderStep(String name, String expression, String as, StepExpressionEvaluator evaluator) {
        super(name, expression, as, evaluator);
    }
}
