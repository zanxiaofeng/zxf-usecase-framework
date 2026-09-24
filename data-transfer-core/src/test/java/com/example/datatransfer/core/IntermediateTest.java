package com.example.datatransfer.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.IntermediateField;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.TransferOptions;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.exception.RuleMatchException;
import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.transform.TransformFunction;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 中间结果（暂存区，设计文档 §6.8）：Phase 1.5 求值、$ 命名空间隔离、前向引用约束。 */
class IntermediateTest {

    private static final String ORDER_JSON = """
            {
              "customer": {"tier": "gold", "city": "Shanghai", "street": "Nanjing Rd"},
              "items": [
                {"sku": "A001", "price": 100},
                {"sku": "B002", "price": 50}
              ]
            }
            """;

    /** 测试桩函数：tier 字符串映射折扣率（gold → 0.8，其余 1.0） */
    private static TransformFunction tierToRate() {
        return (value, args, context) -> "gold".equals(String.valueOf(value)) ? "0.8" : "1.0";
    }

    private static TransferSpec.TransferSpecBuilder specBuilder() {
        return TransferSpec.builder()
                .version("1.0")
                .name("intermediate-test")
                .options(new TransferOptions())
                .rules(List.of(MappingRule.builder().from("customer.tier").to("out.tier").build()));
    }

    @Test
    void fromTransformStaging_feedsTransformArgumentAndOutput() {
        TransferSpec spec = specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$discountRate").from("customer.tier")
                                .transform("tierToRate").build()))
                .rules(List.of(
                        MappingRule.builder().from("items[*].price").to("out.lines[*].price")
                                .transform("multiply($discountRate)").build(),
                        MappingRule.builder().from("$discountRate").to("out.rate").build()))
                .build();
        TransferEngine engine = new TransferEngine(spec, Map.of("tierToRate", tierToRate()));

        JsonNode result = engine.transfer(ORDER_JSON);

        assertThat(result.path("out").path("lines").get(0).path("price").asString()).isEqualTo("80.0");
        assertThat(result.path("out").path("lines").get(1).path("price").asString()).isEqualTo("40.0");
        assertThat(result.path("out").path("rate").asString()).isEqualTo("0.8");
    }

    @Test
    void exprStaging_crossFieldComposition_staysOutOfOutput() {
        TransferSpec spec = specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$fullAddress")
                                .expr("customer.city + ' / ' + customer.street").build()))
                .rules(List.of(
                        MappingRule.builder().from("$fullAddress").to("out.address").build()))
                .build();

        JsonNode result = new TransferEngine(spec).transfer(ORDER_JSON);

        assertThat(result.path("out").path("address").asString()).isEqualTo("Shanghai / Nanjing Rd");
        // $ 命名空间不进输出
        assertThat(result.path("$fullAddress").isMissingNode()).isTrue();
        assertThat(result.toString()).doesNotContain("$fullAddress");
    }

    @Test
    void stagingChaining_laterIntermediateReadsEarlierOne() {
        TransferSpec spec = specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$rate").from("customer.tier")
                                .transform("tierToRate").build(),
                        IntermediateField.builder().to("$label")
                                .expr("'$rate=' + $rate").build()))
                .rules(List.of(
                        MappingRule.builder().from("$label").to("out.label").build()))
                .build();
        TransferEngine engine = new TransferEngine(spec, Map.of("tierToRate", tierToRate()));

        JsonNode result = engine.transfer(ORDER_JSON);

        assertThat(result.path("out").path("label").asString()).isEqualTo("$rate=0.8");
    }

    @Test
    void computedReadsStaging_targetValueTimesDiscount() {
        TransferSpec spec = specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$rate").from("customer.tier")
                                .transform("tierToRate").build()))
                .rules(List.of(
                        MappingRule.builder().from("items[*].price").to("out.lines[*].price").build(),
                        MappingRule.builder().from("items[0].price").to("out.firstPrice").build()))
                .computed(List.of(
                        // 注：聚合形态 sum(...) 为整体表达式（求值器既有限制），与算术混合须经标量中转
                        ComputedField.builder().to("out.discountedFirstPrice")
                                .expr("out.firstPrice * $rate").build()))
                .build();
        TransferEngine engine = new TransferEngine(spec, Map.of("tierToRate", tierToRate()));

        JsonNode result = engine.transfer(ORDER_JSON);

        // 100 × 0.8 = 80
        assertThat(result.path("out").path("discountedFirstPrice").asString()).isEqualTo("80.0");
    }

    @Test
    void containerStaging_expandsForWildcardNavigation() {
        TransferSpec spec = specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$cities")
                                .expr("customer").build()))
                .rules(List.of(
                        MappingRule.builder().from("$cities.city").to("out.city").build()))
                .build();

        JsonNode result = new TransferEngine(spec).transfer(ORDER_JSON);

        assertThat(result.path("out").path("city").asString()).isEqualTo("Shanghai");
    }

    @Test
    void sourceKeyDollarConflict_failsFast() {
        TransferSpec spec = specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$x").expr("customer.tier").build()))
                .build();

        assertThatThrownBy(() -> new TransferEngine(spec).transfer("""
                {"$reserved": "v", "customer": {"tier": "gold"}}
                """))
                .isInstanceOf(RuleMatchException.class)
                .hasMessageContaining("intermediate namespace");
    }

    @Test
    void assemblyErrors_failFast() {
        // to 非 $ 前缀
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .intermediate(List.of(IntermediateField.builder().to("rate").expr("1").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("'-prefixed dotted path");
        // to 含通配
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .intermediate(List.of(IntermediateField.builder().to("$x[*]").expr("1").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class);
        // 重复根声明
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$a").expr("1").build(),
                        IntermediateField.builder().to("$a.b").expr("2").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("duplicate staged root '$a'");
        // from 与 expr 并存 / 都缺
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .intermediate(List.of(IntermediateField.builder().to("$a").from("x").expr("1").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("exactly one of 'from' or 'expr'");
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .intermediate(List.of(IntermediateField.builder().to("$a").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("exactly one of 'from' or 'expr'");
        // from 带通配
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .intermediate(List.of(IntermediateField.builder().to("$a").from("items[*].sku").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("does not support");
    }

    @Test
    void forwardOrUndeclaredReferences_failFast() {
        // 后向引用（引用比自身更晚声明的暂存键）
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$a").expr("$b + 1").build(),
                        IntermediateField.builder().to("$b").expr("1").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("not declared by an earlier intermediate");
        // rules.from 引未声明
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .rules(List.of(MappingRule.builder().from("$nope").to("out.x").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("not declared by any intermediate");
        // transform 实参引未声明
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .rules(List.of(MappingRule.builder().from("customer.tier").to("out.x")
                        .transform("multiply($nope)").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("not declared by any intermediate");
        // computed.expr 引未声明
        assertThatThrownBy(() -> new TransferEngine(specBuilder()
                .computed(List.of(ComputedField.builder().to("out.t").expr("$nope * 2").build()))
                .build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("not declared by any intermediate");
    }

    @Test
    void missingFrom_appliesMissingPolicy() {
        TransferSpec errorSpec = specBuilder()
                .options(new TransferOptions())
                .intermediate(List.of(
                        IntermediateField.builder().to("$x").from("customer.absent").build()))
                .build();
        errorSpec.getOptions().setMissingPolicy(com.example.datatransfer.core.spec.MissingPolicy.ERROR);
        TransferSpec finalSpec = errorSpec;

        assertThatThrownBy(() -> new TransferEngine(finalSpec).transfer(ORDER_JSON))
                .isInstanceOf(RuleMatchException.class)
                .hasMessageContaining("matched no source key");
    }

    @Test
    void nullFromWithSkipPolicy_stagingNotProduced() {
        TransferSpec spec = specBuilder()
                .intermediate(List.of(
                        IntermediateField.builder().to("$x").from("customer.absent").build()))
                .rules(List.of(
                        MappingRule.builder().from("customer.tier").to("out.tier").build()))
                .build();
        spec.getOptions().setMissingPolicy(com.example.datatransfer.core.spec.MissingPolicy.IGNORE);

        JsonNode result = new TransferEngine(spec).transfer(ORDER_JSON);

        assertThat(result.path("out").path("tier").asString()).isEqualTo("gold");
        assertThat(result.toString()).doesNotContain("$x");
    }
}
