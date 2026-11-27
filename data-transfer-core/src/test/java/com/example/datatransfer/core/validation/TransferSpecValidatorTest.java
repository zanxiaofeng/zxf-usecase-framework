package com.example.datatransfer.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    @Test
    void bundledSchema_hasNoDuplicateKeys() throws Exception {
        // Schema 自身也是 JSON：重复键会被静默后值覆盖（本轮就发生过一次），用严格解析守护
        String schema = new String(
                getClass().getResourceAsStream("/schemas/transfer-spec.json").readAllBytes(), StandardCharsets.UTF_8);

        assertThatCode(() -> tools.jackson.dataformat.yaml.YAMLMapper.builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
                .readTree(schema))
                .doesNotThrowAnyException();
    }

    @Test
    void wildcardInSingleKeyTarget_rejectedBySchema() {
        // 外部评审二轮 评审四：computed / defaults 的 to 是单键目标——此前 Schema 放行 [*]，输出里生成名为 "*" 的对象
        String computed = VALID + """
                computed:
                  - to: "crmOrder.arr[*].k"
                    expr: "1"
                """;
        var computedResult = validator.validate(computed);
        assertThat(computedResult.isValid()).isFalse();
        assertThat(computedResult.getErrorMessages().toString()).contains("/computed/0/to");

        String defaults = VALID + """
                defaults:
                  - to: "crmOrder.def[*].k"
                    value: "D"
                """;
        assertThat(validator.validate(defaults).isValid()).isFalse();
    }

    @Test
    void nonObjectDocuments_areReportedAsInvalid_notCrashes() throws Exception {
        // 空文件、只有注释 / 文档分隔符、null、标量、数组：都是「校验失败」，加载抛 SpecValidationException（不是 NPE）
        for (String yaml : List.of("", "\n\n", "---\n", "# nothing here\n", "null\n", "just a string\n", "- a\n- b\n")) {
            var result = validator.validate(yaml);
            assertThat(result.isValid()).as(() -> "document <" + yaml.replace("\n", "\\n") + ">").isFalse();
            assertThat(result.getErrorMessages().toString()).contains("object expected");
            assertThatThrownBy(() -> validator.validateAndLoad(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "x.yaml"))
                    .isInstanceOf(TransferSpecValidator.SpecValidationException.class)
                    .hasMessageContaining("x.yaml");
        }
    }

    @Test
    void yamlAliases_areRejectedAtParseTime_notSilentlyMisread() throws Exception {
        // Jackson 的 YAML 解析器不解析别名：*common 会被读成字面量字符串 "common"，Schema 校验照样通过，
        // 规则的目标键/默认值却被静默写成了 "common"——加载链在解析期直接拒绝
        String aliased = """
                version: "1.0"
                name: "alias"
                rules:
                  - from: "a"
                    to: &common "x.real"
                  - from: "b"
                    to: *common
                """;
        var result = validator.validate(aliased);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessages().toString()).contains("alias '*common'").contains("to");
        assertThatThrownBy(() -> validator.validateAndLoad(
                new ByteArrayInputStream(aliased.getBytes(StandardCharsets.UTF_8)), "alias.yaml"))
                .isInstanceOf(TransferSpecValidator.SpecValidationException.class)
                .hasMessageContaining("alias.yaml")
                .hasMessageContaining("*common");

        // 字符串字面量与注释里的 * / & 不是别名；锚点本身无害（只定义不引用、或所在值原样读取）
        String harmless = """
                version: "1.0"
                name: "*star & amp"
                rules:
                  # *notAnAlias & notAnAnchor
                  - from: "a"
                    to: &target "x.a"
                computed:
                  - to: "x.c"
                    expr: "'*literal*' + ' & '"       # 自由文本字段里的 * 与 & 原样保留
                defaults:
                  - to: "x.d"
                    value: &unused "v"
                validations:
                  - path: "x.c"
                    rules:
                      - assert: "notNull"
                        message: "*m & n"
                """;
        assertThat(validator.validate(harmless).isValid()).isTrue();
    }

    @Test
    void mergeKeys_areRejectedAtParseTime() {
        // 合并键 << 不展开：它的值总是一个别名，在解析期即被拒绝（合并键被当成普通键，
        // 若无别名检查则会由 Schema 以未定义属性拒绝——两条路径都不会静默丢值）
        String merged = VALID + """
                defaults:
                  - &base
                    to: "crmOrder.a"
                    value: "A"
                  - <<: *base
                    to: "crmOrder.b"
                """;
        var result = validator.validate(merged);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessages().toString()).contains("'*base'").contains("'<<'");
    }

    @Test
    void duplicateTopLevelKey_rejected() {
        // 外部评审二轮 P1-3：历史行为——后值静默覆盖前值，前一份规则丢失而 Schema 校验通过
        String duplicated = VALID + """
                rules:
                  - from: "b"
                    to: "crmOrder.b"
                """;

        var result = validator.validate(duplicated);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessages().toString()).contains("rules");
        assertThatThrownBy(() -> validator.validateAndLoad(
                new ByteArrayInputStream(duplicated.getBytes(StandardCharsets.UTF_8)), "dup"))
                .isInstanceOf(TransferSpecValidator.SpecValidationException.class)
                .hasMessageContaining("dup")
                .hasMessageContaining("rules");
    }

    @Test
    void duplicateNestedKeys_rejected() {
        assertDuplicateKey(VALID + """
                options:
                  nullPolicy: "keep"
                """, "options");
        assertDuplicateKey("""
                version: "1.0"
                name: "dup"
                rules:
                  - from: "a"
                    to: "x.first"
                    to: "x.second"
                """, "to");
        assertDuplicateKey(VALID + """
                validations:
                  - path: "crmOrder.id"
                    rules:
                      - assert: "notNull"
                        message: "必填"
                validations:
                  - path: "crmOrder.missing"
                    rules:
                      - assert: "notNull"
                        message: "必填"
                """, "validations");
        assertDuplicateKey(VALID + """
                computed:
                  - to: "a"
                    expr: "1"
                    expr: "2"
                """, "expr");
    }

    private void assertDuplicateKey(String yaml, String key) {
        var result = validator.validate(yaml);

        assertThat(result.isValid()).as("duplicate key '%s'", key).isFalse();
        assertThat(result.getErrorMessages().toString()).as("duplicate key '%s'", key)
                .contains("Duplicate").contains(key);
    }

    @Test
    void intermediateSection_validAndLoaded() throws Exception {
        String yaml = """
                version: "1.0"
                name: "staged"
                rules:
                  - from: "orderId"
                    to: "crmOrder.id"
                  - from: "$discountRate"
                    to: "crmOrder.rate"
                intermediate:
                  - to: "$discountRate"
                    from: "customer.tier"
                    transform: "trim"
                  - to: "$label"
                    expr: "'x' + $discountRate"
                """;

        var result = validator.validate(yaml);
        assertThat(result.isValid()).as(() -> String.join(";", result.getErrorMessages())).isTrue();

        var spec = validator.validateAndLoad(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "intermediate");

        assertThat(spec.intermediateOrEmpty()).hasSize(2);
        assertThat(spec.intermediateOrEmpty().get(0).getTo()).isEqualTo("$discountRate");
        assertThat(spec.getRules().get(1).getFrom()).isEqualTo("$discountRate");
    }

    @Test
    void intermediateFormMutualExclusion_rejected() {
        String both = VALID + """
                intermediate:
                  - to: "$a"
                    from: "orderId"
                    expr: "1"
                """;
        assertThat(validator.validate(both).isValid()).isFalse();

        String neither = VALID + """
                intermediate:
                  - to: "$a"
                """;
        assertThat(validator.validate(neither).isValid()).isFalse();

        String nonPrefixedTo = VALID + """
                intermediate:
                  - to: "a"
                    expr: "1"
                """;
        assertThat(validator.validate(nonPrefixedTo).isValid()).isFalse();
    }
}
