package com.example.myapp.framework.steps;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

import java.util.Locale;
import java.util.Map;

/**
 * encoder / decoder 步骤共用工厂（按方向区分实例）。
 *
 * <p>配置：</p>
 * <pre>{@code
 * - name: encodeUserId
 *   type: encoder                # 或 decoder
 *   config:
 *     algorithm: base64url       # 必填；decoder 要求算法可逆
 *     source: "#path.id"         # 可选，缺省 #payload
 *     as: encodedUserId          # 可选，缺省写回 payload
 * }</pre>
 */
public final class CodecStepFactory implements StepFactory {

    public enum Direction {
        ENCODE, DECODE
    }

    private final String type;
    private final Direction direction;
    private final Map<String, Codec> codecs;
    private final StepExpressionEvaluator evaluator;

    public CodecStepFactory(String type, Direction direction, Map<String, Codec> codecs,
                            StepExpressionEvaluator evaluator) {
        this.type = type;
        this.direction = direction;
        this.codecs = codecs;
        this.evaluator = evaluator;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Step create(StepDefinition definition) {
        StepConfig config = StepConfig.of(definition);
        String name = definition.nameOr(type);
        String algorithm = config.requiredString("algorithm").toLowerCase(Locale.ROOT);
        Codec codec = codecs.get(algorithm);
        if (codec == null) {
            throw new UseCaseAssemblyException(
                    "step [%s]: unknown codec algorithm '%s', available: %s"
                            .formatted(name, algorithm, codecs.keySet()));
        }
        if (direction == Direction.DECODE && !codec.supportsDecode()) {
            throw new UseCaseAssemblyException(
                    "step [%s]: codec algorithm '%s' does not support decode (one-way digest)"
                            .formatted(name, algorithm));
        }
        String source = config.stringOr("source", "#payload");
        String as = config.optionalString("as");
        return direction == Direction.ENCODE
                ? new EncoderStep(name, codec, source, as, evaluator)
                : new DecoderStep(name, codec, source, as, evaluator);
    }
}
