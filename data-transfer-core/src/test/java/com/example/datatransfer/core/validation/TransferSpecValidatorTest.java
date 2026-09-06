package com.example.datatransfer.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.TransferSpec;

class TransferSpecValidatorTest {

    private final TransferSpecValidator validator = new TransferSpecValidator();

    private static final String VALID = """
            version: "1.0"
            name: "ECommerce → CRM"
            options:
              nullPolicy: "skip"
              missingPolicy: "warn"
            rules:
              - from: "orderId"
                to: "crmOrder.id"
              - from: "items[*].price"
                to: "crmOrder.lines[*].unitPrice"
            """;

    @Test
    void validSpec_passesAndLoads() throws Exception {
        var result = validator.validate(VALID);
        assertThat(result.isValid()).as(() -> String.join(";", result.getErrorMessages())).isTrue();

        TransferSpec spec = validator.validateAndLoad(
                new ByteArrayInputStream(VALID.getBytes(StandardCharsets.UTF_8)), "valid");

        assertThat(spec.getName()).isEqualTo("ECommerce → CRM");
        assertThat(spec.getOptions().getNullPolicy()).isEqualTo(NullPolicy.SKIP);
        assertThat(spec.getRules()).hasSize(2);
        assertThat(spec.getRules().get(1).getFrom()).isEqualTo("items[*].price");
    }

    @Test
    void unknownTopLevelKey_rejected() {
        var result = validator.validate(VALID + "\nfoo: bar\n");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessages().toString()).contains("foo");
    }

    @Test
    void wildcardMisalignment_rejected() {
        var result = validator.validate(VALID + """
                  - from: "items[*].qty"
                    to: "crmOrder.qty"
                """);

        assertThat(result.isValid()).isFalse();
        // networknt 错误消息定位到违例的 instance location（pattern 中的 [*] 会被转义展示）
        assertThat(result.getErrorMessages().toString()).contains("/rules/2/to");
    }

    @Test
    void conditionWithoutTransform_rejected() {
        var result = validator.validate("""
                version: "1.0"
                name: "cond"
                rules:
                  - from: "status"
                    to: "orderState"
                    when:
                      - condition: "status == 'PAID'"
                """);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void invalidPathSyntax_rejected() {
        var result = validator.validate("""
                version: "1.0"
                name: "bad-path"
                rules:
                  - from: "123.name"
                    to: "out"
                """);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void classpathSpecResource_isValid() throws Exception {
        try (var in = getClass().getResourceAsStream("/specs/valid-order.yaml")) {
            TransferSpec spec = validator.validateAndLoad(in, "specs/valid-order.yaml");

            assertThat(spec.getRules()).hasSize(8);
            assertThat(spec.computedOrEmpty()).hasSize(1);
            assertThat(spec.defaultsOrEmpty()).hasSize(2);
        }
    }

    @Test
    void validateAndLoad_throwsWithDetailsOnFailure() {
        String invalid = VALID.replace("rules:", "rulesX:");

        assertThatThrownBy(() -> validator.validateAndLoad(
                new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)), "invalid"))
                .isInstanceOf(TransferSpecValidator.SpecValidationException.class)
                .hasMessageContaining("invalid");
    }
}
