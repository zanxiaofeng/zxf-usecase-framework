package com.example.datatransfer.test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.TransferSpec;

class TransferAssertTest {

    private static final String SPEC_YAML = """
            version: "1.0"
            name: "assert-demo"
            rules:
              - from: "orderId"
                to: "crmOrder.id"
              - from: "items[*].price"
                to: "crmOrder.lines[*].unitPrice"
            """;

    private static final String FIXTURE = """
            {"orderId": "ORD-1", "items": [{"price": 100}, {"price": 50}]}
            """;

    private static final String EXPECTED = """
            {"crmOrder": {"id": "ORD-1", "lines": [{"unitPrice": 100}, {"unitPrice": 50}]}}
            """;

    @Test
    void strictMatch_passesOnIdenticalOutput() {
        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .matchesExpectedJson(EXPECTED);
    }

    @Test
    void strictMatch_reportsUnexpectedFields() {
        String expectedWithFewerFields = """
                {"crmOrder": {"id": "ORD-1"}}
                """;

        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .matchesExpectedJson(expectedWithFewerFields))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("多余");
    }

    @Test
    void partialMatch_ignoresExtraFields() {
        String expectedWithFewerFields = """
                {"crmOrder": {"id": "ORD-1"}}
                """;

        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .partiallyMatchesJson(expectedWithFewerFields);
    }

    @Test
    void ignorePaths_excludesDynamicPaths() {
        // ignorePaths 同时作用于期望与实际两侧——期望直接写完整结构，动态路径双侧排除
        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .ignorePaths("crmOrder.lines[*].unitPrice")
                .matchesExpectedJson(EXPECTED);
    }

    @Test
    void execute_supportsChainedPathAssertions() {
        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathExists("crmOrder.id")
                .pathExists("crmOrder.lines[*]")
                .pathValueEquals("crmOrder.id", "ORD-1")
                .pathValueGreaterThan("crmOrder.lines[0].unitPrice", 99)
                .pathValueNotNull("crmOrder.lines[1].unitPrice")
                .allPathValues("crmOrder.lines[*].unitPrice",
                        v -> assertThatCode(() -> {
                            if (((Number) v).doubleValue() <= 0) {
                                throw new IllegalStateException("must be positive");
                            }
                        }).doesNotThrowAnyException());
    }

    @Test
    void execute_failsWhenPathMissing() {
        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathExists("crmOrder.missing"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("crmOrder.missing");
    }

    @Test
    void batchAssert_reportsFailuresAcrossCases() {
        assertThatThrownBy(() -> TransferAssert.batchAssert("specs/assert-demo.yaml")
                .addCaseJson(FIXTURE, EXPECTED)
                .addCaseJson("{\"orderId\": \"ORD-2\", \"items\": []}",
                        "{\"crmOrder\": {\"id\": \"WRONG\"}}")
                .runAll())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("失败 1");
    }

    @Test
    void builderSpec_programmaticUsage() {
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("prog")
                .rules(List.of(MappingRule.builder().from("a").to("b").build()))
                .build();

        TransferAssert.assertThat(spec)
                .withFixtureJson("{\"a\": 7}")
                .matchesExpectedJson("{\"b\": 7}");
    }
}
