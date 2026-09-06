package com.example.myapp.framework.steps;

import java.util.Locale;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.codec.ReversibleCodec;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.CodecStepConfig;

/**
 * encoder / decoder 步骤共用工厂（按 {@link CodecStep.Direction} 区分实例）。
 * config schema 见 {@link CodecStepConfig}；算法存在性与 decoder 可逆性依赖注册表，在此校验。
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
@RequiredArgsConstructor
public final class CodecStepFactory implements StepFactory {

    private final String type;
    private final CodecStep.Direction direction;
    private final Map<String, Codec> codecs;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return type;
    }

    @Override
    public Step create(StepDefinition definition) {
        CodecStepConfig config = StepConfigs.bind(definition, CodecStepConfig.class);
        String name = definition.nameOr(type);
        String algorithm = config.getAlgorithm().toLowerCase(Locale.ROOT);
        Codec codec = codecs.get(algorithm);
        if (codec == null) {
            throw new UseCaseAssemblyException(
                    "step [%s]: unknown codec algorithm '%s', available: %s"
                            .formatted(name, algorithm, codecs.keySet()));
        }
        if (direction == CodecStep.Direction.DECODE && !(codec instanceof ReversibleCodec)) {
            throw new UseCaseAssemblyException(
                    "step [%s]: codec algorithm '%s' does not support decode (one-way digest)"
                            .formatted(name, algorithm));
        }
        return new CodecStep(name, codec, direction, config.getSource(), config.getAs(), evaluator);
    }
}
