package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.DataSaver;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 配置驱动的 DataSaver：表达式典型为 {@code @someRepository.save(#payload)}。
 * 表达式返回 null（如 void 方法）时保留原 payload；返回非 null 时以返回值作为新 payload。
 */
public final class SpelDataSaverStep extends AbstractSpelStep implements DataSaver {

    public SpelDataSaverStep(String name, String expression, String as, StepExpressionEvaluator evaluator) {
        super(name, expression, as, evaluator);
    }

    @Override
    protected boolean overwritePayloadWithNull() {
        return false;
    }
}
