package com.example.myapp.framework.steps;

import lombok.RequiredArgsConstructor;

import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.codec.ReversibleCodec;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 编解码步骤：{@code source} 表达式取值（缺省 #payload）→ Codec 按 {@link Direction} 编码或解码
 * → as/payload 规则落地。编码与解码仅方向不同，共用本实现。
 *
 * <p>输入为 null 时结果亦为 null（不抛错，由后续步骤决定如何对待）；decoder 的算法可逆性
 * 在装配期由工厂校验（fail-fast，类型系统保证 decode 能力存在）。</p>
 */
@RequiredArgsConstructor
public final class CodecStep implements DataTransformer {

    /** 编解码方向，携带对应的 Codec 调用 */
    public enum Direction {
        ENCODE {
            @Override
            public String apply(Codec codec, String value) {
                return codec.encode(value);
            }
        },
        DECODE {
            @Override
            public String apply(Codec codec, String value) {
                if (codec instanceof ReversibleCodec reversible) {
                    return reversible.decode(value);
                }
                throw new IllegalStateException("unreachable: reversibility validated at assembly");
            }
        };

        public abstract String apply(Codec codec, String value);
    }

    private final String name;
    private final Codec codec;
    private final Direction direction;
    private final String sourceExpression;
    private final String as;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        Object source = evaluator.evaluate(sourceExpression, context, name);
        String result = source == null ? null : direction.apply(codec, String.valueOf(source));
        context.storeResult(result, as, true);
    }
}
