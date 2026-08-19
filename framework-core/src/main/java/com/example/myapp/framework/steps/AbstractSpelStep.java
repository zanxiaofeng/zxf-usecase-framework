package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * 基于 SpEL 表达式的通用 step 基类。
 *
 * <p>结果落地规则（所有内置 SpEL step 一致）：</p>
 * <ul>
 *   <li>配置 {@code as} → 结果写入 {@code #vars[as]}，payload 保持不变（旁路数据）；</li>
 *   <li>未配置 {@code as} → 结果写入 payload；返回 null 时是否覆盖由子类 {@link #overwritePayloadWithNull()} 决定。</li>
 * </ul>
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
abstract class AbstractSpelStep implements Step {

    private final String name;
    private final String expression;
    private final String as;
    protected final StepExpressionEvaluator evaluator;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        Object value = evaluator.evaluate(expression, context);
        store(context, value);
    }

    protected void store(StepContext context, Object value) {
        StepResultStore.store(context, value, as, overwritePayloadWithNull());
    }

    /** 表达式结果为 null 时是否覆盖 payload。DataSaver 返回 false（void 方法不清空数据）。 */
    protected boolean overwritePayloadWithNull() {
        return true;
    }
}
