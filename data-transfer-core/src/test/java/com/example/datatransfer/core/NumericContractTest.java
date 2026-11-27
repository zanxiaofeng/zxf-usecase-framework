package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.IntermediateField;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.TransferSpec;

import tools.jackson.databind.JsonNode;

/**
 * 数值契约（外部评审二轮 P1-5）：transform / 聚合 / 表达式三条路径遵循同一十进制语义，
 * 恒等映射不丢精度，输出数值形态与历史行为一致。
 */
class NumericContractTest {

    @Test
    void identityMapping_keepsHighPrecisionDecimals() {
        // 历史行为：unflatten 经库默认 mapper 的 JSON 文本往返 → 12345678901234567000 / 9007199254740994
        JsonNode result = new TransferEngine(identity("big", "n", "amt", "prec", "id")).transfer("""
                {"big": 12345678901234567890.123, "n": 123456789.123456789, "amt": 9007199254740993.5,
                 "prec": 1.0000000000000000001, "id": 12345678901234567890}
                """);

        assertThat(result.toString()).isEqualTo(
                "{\"out\":{\"big\":12345678901234567890.123,\"n\":123456789.123456789,"
                        + "\"amt\":9007199254740993.5,\"prec\":1.0000000000000000001,\"id\":12345678901234567890}}");
    }

    @Test
    void identityMapping_keepsHistoricalNumberShape() {
        JsonNode result = new TransferEngine(identity("tail", "tenths", "sci", "rate", "small", "count")).transfer("""
                {"tail": 113.00, "tenths": 1.10, "sci": 1e3, "rate": 0.1, "small": 0.000001, "count": 42}
                """);

        assertThat(result.toString()).isEqualTo(
                "{\"out\":{\"tail\":113.0,\"tenths\":1.1,\"sci\":1000,\"rate\":0.1,\"small\":1.0E-6,\"count\":42}}");
    }

    @Test
    void transformAggregateAndExpressionPaths_agree() {
        // 历史行为：transform multiply(3) 得 24.6，表达式 out.e * 3 得 24.599999999999998
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("paths")
                .rules(List.of(
                        MappingRule.builder().from("e").to("out.eT").transform("multiply(3)").build(),
                        MappingRule.builder().from("c").to("out.cT").transform("multiply(3)").build(),
                        MappingRule.builder().from("e").to("out.e").build(),
                        MappingRule.builder().from("c").to("out.c").build(),
                        MappingRule.builder().from("items[*].price").to("out.items[*].price").build(),
                        MappingRule.builder().from("items[*].qty").to("out.items[*].qty").build()))
                .computed(List.of(
                        ComputedField.builder().to("out.eE").expr("out.e * 3").build(),
                        ComputedField.builder().to("out.cE").expr("out.c * 3").build(),
                        ComputedField.builder().to("out.cPlus").expr("out.c + out.c + out.c").build(),
                        ComputedField.builder().to("out.sumAgg")
                                .expr("sum(out.items[*].price * out.items[*].qty)").build()))
                .build();

        JsonNode out = new TransferEngine(spec).transfer("""
                {"e": 8.2, "c": 0.1, "items": [{"price": 0.1, "qty": 3}, {"price": 8.2, "qty": 7}]}
                """).path("out");

        assertThat(out.path("eT").decimalValue()).isEqualByComparingTo("24.6");
        assertThat(out.path("eE").decimalValue()).isEqualByComparingTo("24.6");
        assertThat(out.path("cT").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(out.path("cE").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(out.path("cPlus").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(out.path("sumAgg").decimalValue()).isEqualByComparingTo("57.7");
        assertThat(out.path("eT").toString()).isEqualTo(out.path("eE").toString());
        assertThat(out.path("cT").toString()).isEqualTo(out.path("cE").toString());
    }

    @Test
    void intermediateExpression_isExactAndNullable() {
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("staged-numeric")
                .intermediate(List.of(
                        IntermediateField.builder().to("$tenth").expr("0.1").build(),
                        IntermediateField.builder().to("$three").expr("$tenth + 0.2").build(),
                        IntermediateField.builder().to("$count").expr("0").build(),
                        IntermediateField.builder().to("$enabled").expr("false").build()))
                .rules(List.of(
                        MappingRule.builder().from("$three").to("out.three").build(),
                        MappingRule.builder().from("$count").to("out.count").build(),
                        MappingRule.builder().from("$enabled").to("out.enabled").build()))
                .build();

        JsonNode out = new TransferEngine(spec).transfer("{}").path("out");

        assertThat(out.path("three").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(out.path("count").asInt()).isZero();
        assertThat(out.path("enabled").asBoolean()).isFalse();
    }

    @Test
    void validationCondition_comparesExactDecimals() {
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("validate-numeric")
                .rules(List.of(MappingRule.builder().from("a").to("out.a").build()))
                .computed(List.of(ComputedField.builder().to("out.sum").expr("0.1 + 0.2").build()))
                .validations(List.of(com.example.datatransfer.core.spec.ValidationRule.builder()
                        .path("out.sum")
                        .condition("value == 0.3")
                        .message("求和必须等于 0.3").build()))
                .build();

        // double 算术下 0.1 + 0.2 = 0.30000000000000004 ≠ 0.3（校验失败）；十进制算术下相等，校验通过
        JsonNode out = new TransferEngine(spec).transfer("{\"a\": 8.2}");

        assertThat(out.path("out").path("sum").decimalValue()).isEqualByComparingTo("0.3");
    }

    private static TransferSpec identity(String... fields) {
        List<MappingRule> rules = Arrays.stream(fields)
                .map(field -> MappingRule.builder().from(field).to("out." + field).build())
                .toList();
        return TransferSpec.builder().version("1.0").name("identity").rules(rules).build();
    }
}
