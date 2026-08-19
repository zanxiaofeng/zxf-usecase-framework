package com.example.myapp.framework.steps;

import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.core.Decoder;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 解码步骤：与编码互逆。算法可逆性在装配期由工厂校验（fail-fast）。
 */
public final class DecoderStep implements Decoder {

    private final String name;
    private final Codec codec;
    private final String sourceExpression;
    private final String as;
    private final StepExpressionEvaluator evaluator;

    public DecoderStep(String name, Codec codec, String sourceExpression, String as, StepExpressionEvaluator evaluator) {
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
        String result = source == null ? null : codec.decode(String.valueOf(source));
        StepResultStore.store(context, result, as, true);
    }
}
