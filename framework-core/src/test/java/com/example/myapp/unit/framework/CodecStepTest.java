package com.example.myapp.unit.framework;

import com.example.myapp.framework.codec.Base64Codec;
import com.example.myapp.framework.codec.Base64UrlCodec;
import com.example.myapp.framework.codec.Codec;
import com.example.myapp.framework.codec.DigestCodec;
import com.example.myapp.framework.codec.HexCodec;
import com.example.myapp.framework.codec.UrlCodec;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.CodecStep;
import com.example.myapp.framework.steps.CodecStepFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * encoder / decoder 步骤：算法解析、source/as 语义、decoder 可逆性装配期校验。
 */
class CodecStepTest {

    private final Map<String, Codec> codecs = java.util.stream.Stream.of(
                    new Base64Codec(), new Base64UrlCodec(), new UrlCodec(), new HexCodec(),
                    new DigestCodec("md5"), new DigestCodec("sha256"))
            .collect(Collectors.toMap(Codec::algorithm, Function.identity()));
    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);

    private final CodecStepFactory encoderFactory =
            new CodecStepFactory("encoder", CodecStep.Direction.ENCODE, codecs, evaluator);
    private final CodecStepFactory decoderFactory =
            new CodecStepFactory("decoder", CodecStep.Direction.DECODE, codecs, evaluator);

    private StepContext contextWithPathId() {
        return StepContext.of(
                TestServerRequests.getRequest(Map.of("id", "u1"), Map.of()), new ObjectMapper());
    }

    @Test
    void encoderEncodesSourceIntoNamedVar() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("algorithm", "base64url");
        config.put("source", "#path.id");
        config.put("as", "encodedUserId");

        Step step = encoderFactory.create(new StepDefinition("encodeUserId", "encoder", null, config));
        StepContext context = contextWithPathId();
        context.setPayload("original");

        step.execute(context);

        assertThat(context.getVar("encodedUserId")).isEqualTo("dTE=");   // base64url("u1")
        assertThat(context.getPayload()).isEqualTo("original");           // as 旁路不动 payload
    }

    @Test
    void decoderDecodesBackToPayload() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("algorithm", "base64url");
        config.put("source", "#payload");

        Step step = decoderFactory.create(new StepDefinition("decodeToken", "decoder", null, config));
        StepContext context = contextWithPathId();
        context.setPayload("dTE=");

        step.execute(context);

        assertThat(context.getPayload()).isEqualTo("u1");
    }

    @Test
    void encoderSupportsOneWayDigest() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("algorithm", "sha256");
        config.put("source", "#payload");

        Step step = encoderFactory.create(new StepDefinition("hash", "encoder", null, config));
        StepContext context = contextWithPathId();
        context.setPayload("u1");

        step.execute(context);

        assertThat(context.getPayload())
                .isEqualTo("bb82030dbc2bcaba32a90bf2e207a84a856fc5f033b77c480836ab6f77f40f19"); // sha256("u1")
    }

    @Test
    void decoderRejectsOneWayDigestAtAssembly() {
        assertThatThrownBy(() -> decoderFactory.create(new StepDefinition(
                "bad", "decoder", null, Map.of("algorithm", "sha256"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("does not support decode");
    }

    @Test
    void unknownAlgorithmFailsFast() {
        assertThatThrownBy(() -> encoderFactory.create(new StepDefinition(
                "bad", "encoder", null, Map.of("algorithm", "rot13"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("unknown codec algorithm");
    }
}
