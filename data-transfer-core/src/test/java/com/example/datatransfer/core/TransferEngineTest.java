package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.ConditionMapping;
import com.example.datatransfer.core.spec.DefaultValue;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.MissingPolicy;
import com.example.datatransfer.core.spec.SourceDeclaration;
import com.example.datatransfer.core.spec.TransferOptions;
import com.example.datatransfer.core.flatten.FlatMapProcessor;
import com.example.datatransfer.core.spec.TransferSpec;

import tools.jackson.databind.JsonNode;

/** 引擎全旅程与策略行为测试（设计文档 §7/§8.6 语义）。 */
class TransferEngineTest {

    private static final String ORDER_JSON = """
            {
              "orderId": "ORD-20260906-001",
              "customer": {
                "name": " Zhang San ",
                "email": "ZHANG@EXAMPLE.COM",
                "addresses": [
                  {"city": "Shanghai", "zip": "200000"},
                  {"city": "Beijing", "zip": "100000"}
                ]
              },
              "items": [
                {"sku": "A001", "price": 100, "qty": 2},
                {"sku": "B002", "price": 50, "qty": 3}
              ],
              "status": "PAID"
            }
            """;

    @Test
    void orderToCrm_fullJourney_matchesDesignDocExpectation() {
        TransferEngine engine = new TransferEngine(orderToCrmSpec());

        JsonNode result = engine.transfer(ORDER_JSON);

        assertThat(result.path("crmOrder").path("id").asString()).isEqualTo("ORD-20260906-001");
        assertThat(result.path("crmOrder").path("buyer").path("name").asString()).isEqualTo("Zhang San");
        assertThat(result.path("crmOrder").path("buyer").path("email").asString()).isEqualTo("zhang@example.com");
        assertThat(result.path("crmOrder").path("buyer").path("cities").get(0).asString()).isEqualTo("Shanghai");
        assertThat(result.path("crmOrder").path("buyer").path("cities").get(1).asString()).isEqualTo("Beijing");

        JsonNode lines = result.path("crmOrder").path("lines");
        assertThat(lines.size()).isEqualTo(2);
        assertThat(lines.get(0).path("productCode").asString()).isEqualTo("A001");
        assertThat(lines.get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("113.0");
        assertThat(lines.get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(lines.get(1).path("productCode").asString()).isEqualTo("B002");
        assertThat(lines.get(1).path("unitPrice").decimalValue()).isEqualByComparingTo("56.5");
        assertThat(lines.get(1).path("quantity").asInt()).isEqualTo(3);

        assertThat(result.path("crmOrder").path("state").asString()).isEqualTo("paid");
        assertThat(result.path("crmOrder").path("totalAmount").decimalValue()).isEqualByComparingTo("395.5");
        assertThat(result.path("crmOrder").path("currency").asString()).isEqualTo("CNY");
        assertThat(result.path("crmOrder").path("source").asString()).isEqualTo("eCommerce");
    }

    @Test
    void transferNode_overloadProducesSameResult() {
        TransferEngine engine = new TransferEngine(orderToCrmSpec());
        JsonNode source = new FlatMapProcessor().mapper().readTree(ORDER_JSON);

        JsonNode result = engine.transferNode(source);

        assertThat(result.path("crmOrder").path("totalAmount").decimalValue()).isEqualByComparingTo("395.5");
    }

    @Test
    void nullPolicy_skipDropsNullTargetKey() {
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("null-skip")
                .rules(List.of(MappingRule.builder().from("a").to("x").build()))
                .build();

        JsonNode result = new TransferEngine(spec).transfer("{\"a\": null, \"b\": 1}");

        assertThat(result.has("x")).isFalse();
    }

    @Test
    void nullPolicy_keepWritesNull() {
        TransferOptions options = new TransferOptions();
        options.setNullPolicy(NullPolicy.KEEP);
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("null-keep").options(options)
                .rules(List.of(MappingRule.builder().from("a").to("x").build()))
                .build();

        JsonNode result = new TransferEngine(spec).transfer("{\"a\": null, \"b\": 1}");

        assertThat(result.has("x")).isTrue();
        assertThat(result.path("x").isNull()).isTrue();
    }

    @Test
    void missingPolicy_errorFailsFastAndWarnDoesNot() {
        MappingRule missing = MappingRule.builder().from("nope").to("y").build();

        TransferOptions warnOptions = new TransferOptions();
        warnOptions.setMissingPolicy(MissingPolicy.WARN);
        TransferSpec warnSpec = TransferSpec.builder()
                .version("1.0").name("m-warn").options(warnOptions)
                .rules(List.of(missing, MappingRule.builder().from("a").to("x").build()))
                .build();
        assertThat(new TransferEngine(warnSpec).transfer("{\"a\": 1}").path("x").asInt()).isEqualTo(1);

        TransferOptions errorOptions = new TransferOptions();
        errorOptions.setMissingPolicy(MissingPolicy.ERROR);
        TransferSpec errorSpec = TransferSpec.builder()
                .version("1.0").name("m-error").options(errorOptions)
                .rules(List.of(missing))
                .build();
        assertThatThrownBy(() -> new TransferEngine(errorSpec).transfer("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void multiLevelWildcards_alignByPosition() {
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("multi-wildcard")
                .rules(List.of(MappingRule.builder()
                        .from("data[*].tags[*]").to("out[*].t[*]").build()))
                .build();

        JsonNode result = new TransferEngine(spec).transfer(
                "{\"data\": [{\"tags\": [\"a\", \"b\"]}, {\"tags\": [\"c\"]}]}");

        assertThat(result.path("out").get(0).path("t").get(0).asString()).isEqualTo("a");
        assertThat(result.path("out").get(0).path("t").get(1).asString()).isEqualTo("b");
        assertThat(result.path("out").get(1).path("t").get(0).asString()).isEqualTo("c");
    }

    @Test
    void conditionalMapping_branchAndOtherwise() {
        // otherwise 是普通变换链：default() 仅对 null 兜底（非 null 原样传递）
        ConditionMapping paid = new ConditionMapping();
        paid.setCondition("status == 'PAID'");
        paid.setTransform("replace('PAID', 'completed')");
        ConditionMapping pending = new ConditionMapping();
        pending.setCondition("status == 'PENDING'");
        pending.setTransform("replace('PENDING', 'processing')");
        ConditionMapping fallback = new ConditionMapping();
        fallback.setOtherwise("default('unknown')");
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("cond")
                .rules(List.of(MappingRule.builder()
                        .from("status").to("orderState")
                        .when(List.of(paid, pending, fallback)).build()))
                .build();

        TransferEngine engine = new TransferEngine(spec);
        assertThat(engine.transfer("{\"status\": \"PAID\"}").path("orderState").asString())
                .isEqualTo("completed");
        assertThat(engine.transfer("{\"status\": \"PENDING\"}").path("orderState").asString())
                .isEqualTo("processing");
        // 值为 null（nullPolicy=KEEP 才会进入变换）时 default 兜底生效
        TransferOptions keepNull = new TransferOptions();
        keepNull.setNullPolicy(NullPolicy.KEEP);
        TransferSpec keepSpec = TransferSpec.builder()
                .version("1.0").name("cond-null").options(keepNull)
                .rules(List.of(MappingRule.builder()
                        .from("status").to("orderState")
                        .when(List.of(paid, pending, fallback)).build()))
                .build();
        assertThat(new TransferEngine(keepSpec).transfer("{\"status\": null}")
                .path("orderState").asString()).isEqualTo("unknown");
    }

    @Test
    void extraFunctions_registeredIntoChain() {
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("custom-fn")
                .rules(List.of(MappingRule.builder()
                        .from("name").to("shouted").transform("shout").build()))
                .build();

        TransferEngine engine = new TransferEngine(spec,
                Map.of("shout", (v, args, ctx) -> String.valueOf(v).toUpperCase() + "!"));

        assertThat(engine.transfer("{\"name\": \"ok\"}").path("shouted").asString()).isEqualTo("OK!");
    }

    @Test
    void constructor_failsFastOnUnsupportedFeatures() {
        TransferSpec withSources = TransferSpec.builder()
                .version("1.0").name("s")
                .sources(List.of(SourceDeclaration.builder().alias("a").path("a").build()))
                .rules(List.of(MappingRule.builder().from("x").to("y").build()))
                .build();
        assertThatThrownBy(() -> new TransferEngine(withSources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sources");
    }

    @Test
    void defaults_doNotOverrideMappedValues() {
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("defaults")
                .rules(List.of(MappingRule.builder().from("a").to("x").build()))
                .defaults(List.of(
                        DefaultValue.builder().to("x").value("mapped").build(),
                        DefaultValue.builder().to("y").value("defaulted").build()))
                .build();

        JsonNode result = new TransferEngine(spec).transfer("{\"a\": \"real\"}");

        assertThat(result.path("x").asString()).isEqualTo("real");
        assertThat(result.path("y").asString()).isEqualTo("defaulted");
    }

    /** 设计文档 §7.2 的代码式等价 spec */
    private static TransferSpec orderToCrmSpec() {
        return TransferSpec.builder()
                .version("1.0").name("ECommerce → CRM")
                .rules(List.of(
                        MappingRule.builder().from("orderId").to("crmOrder.id").build(),
                        MappingRule.builder().from("customer.name").to("crmOrder.buyer.name")
                                .transform("trim").build(),
                        MappingRule.builder().from("customer.email").to("crmOrder.buyer.email")
                                .transform("lower").build(),
                        MappingRule.builder().from("customer.addresses[*].city")
                                .to("crmOrder.buyer.cities[*]").build(),
                        MappingRule.builder().from("items[*].sku").to("crmOrder.lines[*].productCode").build(),
                        MappingRule.builder().from("items[*].price").to("crmOrder.lines[*].unitPrice")
                                .transform("multiply(1.13) | round(2)").build(),
                        MappingRule.builder().from("items[*].qty").to("crmOrder.lines[*].quantity").build(),
                        MappingRule.builder().from("status").to("crmOrder.state").transform("lower").build()))
                .computed(List.of(ComputedField.builder().to("crmOrder.totalAmount")
                        .expr("sum(crmOrder.lines[*].unitPrice * crmOrder.lines[*].quantity)").build()))
                .defaults(List.of(
                        DefaultValue.builder().to("crmOrder.currency").value("CNY").build(),
                        DefaultValue.builder().to("crmOrder.source").value("eCommerce").build()))
                .build();
    }
}
