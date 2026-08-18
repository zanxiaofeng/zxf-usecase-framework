package com.example.myapp.framework.steps;

import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.core.Encoder;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 编码步骤：{@code source} 表达式取值（缺省 #payload）→ Codec 编码 → as/payload 规则落地。
 * 输入为 null 时结果亦为 null（不写 MDC、不抛错，由后续步骤决定如何对待）。
 */
public final class EncoderStep implements Encoder {

    private final String name;
    private final Codec codec;
    private final String sourceExpression;
    private final String as;
    private final StepExpressionEvaluator evaluator;

    public EncoderStep(String name, Codec codec, String sourceExpression, String as, StepExpressionEvaluator evaluator) {
        this.name = name;
        this.codec = codec;
        this.sourceExpression = sourceExpression;
        this.as = as;
        this.evaluator = evaluator;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        Object source = evaluator.evaluate(sourceExpression, context);
        String result = source == null ? null : codec.encode(String.valueOf(source));
        StepResultStore.store(context, result, as, true);
    }
}
