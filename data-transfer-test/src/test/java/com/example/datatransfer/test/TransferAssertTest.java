package com.example.datatransfer.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.TransferOptions;
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
    void execute_appliesIgnorePathsToChainedAssertions() {
        // review P1：链式断言与主流程口径统一——ignorePaths 在 AssertContext 构造期排除
        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .ignorePaths("crmOrder.lines[*].unitPrice")
                .execute()
                .pathExists("crmOrder.id")
                .pathNotExists("crmOrder.lines[0].unitPrice");
    }

    @Test
    void pathValueEquals_comparesNumericsLooselyLikeDiffEngine() {
        // review P1：与 DiffEngine 同口径——数值跨形态宽松（实际 100 int vs 期望 100.0 double）
        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathValueEquals("crmOrder.lines[0].unitPrice", 100.0);
    }

    @Test
    void allPathValues_wrapsAssertionErrorWithFailingValueContext() {
        // review P1：AssertJ/JUnit 断言抛 AssertionError——包装进「哪个值失败」的上下文而非裸抛
        String negativePrice = """
                {"orderId": "ORD-9", "items": [{"price": 100}, {"price": -5}]}
                """;

        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(negativePrice)
                .execute()
                .allPathValues("crmOrder.lines[*].unitPrice",
                        v -> assertThat(((Number) v).doubleValue()).isPositive()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("crmOrder.lines[*].unitPrice")
                .hasMessageContaining("-5");
    }

    @Test
    void strictMatch_keepsJsonTypes_stringAndNumberAreDifferent() {
        // 外部评审二轮 P1-7：历史行为——期望 "100" 与实际 100 判相等
        String expectedWithStringNumber = """
                {"crmOrder": {"id": "ORD-1", "lines": [{"unitPrice": "100"}, {"unitPrice": 50}]}}
                """;

        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .matchesExpectedJson(expectedWithStringNumber))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("crmOrder.lines[0].unitPrice");
    }

    @Test
    void withNumericStringCoercion_isAnExplicitOptIn() {
        // YAML / 手写期望常把数字写成字符串——宽松口径保留为显式开关（仅放宽期望侧）
        String expectedWithStringNumber = """
                {"crmOrder": {"id": "ORD-1", "lines": [{"unitPrice": "100"}, {"unitPrice": 50}]}}
                """;

        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .withNumericStringCoercion()
                .matchesExpectedJson(expectedWithStringNumber);
        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .withNumericStringCoercion()
                .matchesExpectedJson("{\"crmOrder\": {\"id\": \"ORD-1\", \"lines\": [{\"unitPrice\": \"100 dollars\"}, "
                        + "{\"unitPrice\": 50}]}}"))   // 非数值字符串仍然不等价
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("crmOrder.lines[0].unitPrice");
    }

    @Test
    void pathValueEquals_missingPathFails_evenWhenExpectingNull() {
        // 外部评审二轮 P1-8：历史行为——resolveSingleValue 对不存在的路径返回 null，
        // pathValueEquals("out.missing", null) 假通过（同一个 null 同时表示「缺失」与「存在但为空」）
        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathValueEquals("crmOrder.missing", null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("crmOrder.missing");
        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathValueEquals("crmOrder.id", null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("crmOrder.id");
        // 路径存在且值为 JSON null：通过
        TransferSpec nullable = TransferSpec.builder()
                .version("1.0").name("nullable")
                .options(nullableKeep())
                .rules(List.of(MappingRule.builder().from("a").to("out.a").build()))
                .build();
        TransferAssert.assertThat(nullable)
                .withFixtureJson("{\"a\": null}")
                .execute()
                .pathValueEquals("out.a", null);
    }

    @Test
    void pathValueEquals_noLongerCoercesStringsToNumbers() {
        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathValueEquals("crmOrder.lines[0].unitPrice", "100"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathValueIn("crmOrder.lines[0].unitPrice", "100", "101"))
                .isInstanceOf(AssertionError.class);
        TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute()
                .pathValueIn("crmOrder.lines[0].unitPrice", 100, 101)
                .pathValueEquals("crmOrder.lines[0].unitPrice", 100.0);   // Number ↔ Number 仍按数值比较
    }

    @Test
    void singleValueAssertions_reportMissingPathExplicitly() {
        AssertContext context = TransferAssert.assertThat("specs/assert-demo.yaml")
                .withFixtureJson(FIXTURE)
                .execute();

        assertThatThrownBy(() -> context.pathValueMatches("crmOrder.missing", ".*"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("crmOrder.missing");
        assertThatThrownBy(() -> context.pathValueGreaterThan("crmOrder.missing", 0))
                .isInstanceOf(AssertionError.class).hasMessageContaining("crmOrder.missing");
        assertThatThrownBy(() -> context.pathValueLessThan("crmOrder.missing", 0))
                .isInstanceOf(AssertionError.class).hasMessageContaining("crmOrder.missing");
        assertThatThrownBy(() -> context.pathValueContains("crmOrder.missing", "x"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("crmOrder.missing");
    }

    @Test
    void matchesExpected_detectsPrecisionLossInEngineOutput() {
        // 外部评审二轮：历史行为——期望与实际在比较前同样被舍入，恒等映射丢精度时断言仍然通过
        String fixture = "{\"big\": 12345678901234567890.123, \"n\": 123456789.123456789}";
        TransferSpec identity = TransferSpec.builder()
                .version("1.0").name("identity")
                .rules(List.of(MappingRule.builder().from("big").to("out.big").build(),
                        MappingRule.builder().from("n").to("out.n").build()))
                .build();

        TransferAssert.assertThat(identity)
                .withFixtureJson(fixture)
                .matchesExpectedJson("{\"out\": {\"big\": 12345678901234567890.123, \"n\": 123456789.123456789}}");
        assertThatThrownBy(() -> TransferAssert.assertThat(identity)
                .withFixtureJson(fixture)
                .matchesExpectedJson("{\"out\": {\"big\": 12345678901234567000, \"n\": 1.2345678912345679E8}}"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("out.big")
                .hasMessageContaining("out.n");
    }

    private static TransferOptions nullableKeep() {
        TransferOptions options = new TransferOptions();
        options.setNullPolicy(NullPolicy.KEEP);
        return options;
    }

    @Test
    void batchAssert_rejectsEmptyCaseList() {
        // review P2：空 case 静默通过是假绿，runAll 前置防护
        assertThatThrownBy(() -> TransferAssert.batchAssert("specs/assert-demo.yaml").runAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no test cases");
    }

    @Test
    void batchAssert_passesRegisteredFunctionsThrough() {
        // review P2：spec 使用自定义函数时，BatchAssert 也能注册（此前无入口、批量断言必失败）
        TransferAssert.batchAssert("specs/shout-demo.yaml")
                .addCaseJson("{\"name\": \"ok\"}", "{\"shouted\": \"OK!\"}")
                .registerFunction("shout", (v, args, ctx) -> String.valueOf(v).toUpperCase() + "!")
                .runAll();
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

    @Test
    void withClock_makesBuiltinNowDeterministic() {
        // Clock 注入覆盖真实内置 now（契约级验证内置函数；registerFunction("now", …) 是遮蔽路径，见下例）
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("now-kit")
                .rules(List.of(MappingRule.builder()
                        .from("orderId").to("processedAt").transform("now").build()))
                .build();

        TransferAssert.assertThat(spec)
                .withClock(Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC))
                .withFixtureJson("{\"orderId\": \"ORD-1\"}")
                .matchesExpectedJson("{\"processedAt\": \"2026-01-15T10:30:00Z\"}");
    }

    @Test
    void registerFunction_now_stillOverridesBuiltin() {
        // 遮蔽语义锚定：registerFunction 发生于内置 now 注册之后，可覆盖
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("now-override")
                .rules(List.of(MappingRule.builder()
                        .from("orderId").to("processedAt").transform("now").build()))
                .build();

        TransferAssert.assertThat(spec)
                .registerFunction("now", (v, args, ctx) -> "override-ts")
                .withFixtureJson("{\"orderId\": \"ORD-1\"}")
                .matchesExpectedJson("{\"processedAt\": \"override-ts\"}");
    }

    @Test
    void withClock_andRegisterFunction_combined_overrideWins() {
        // 组合语义：extraFunctions 在内置 now（Clock 绑定）之后注册——遮蔽胜出
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("now-combo")
                .rules(List.of(MappingRule.builder()
                        .from("orderId").to("processedAt").transform("now").build()))
                .build();

        TransferAssert.assertThat(spec)
                .withClock(Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC))
                .registerFunction("now", (v, args, ctx) -> "override-ts")
                .withFixtureJson("{\"orderId\": \"ORD-1\"}")
                .matchesExpectedJson("{\"processedAt\": \"override-ts\"}");
    }

    @Test
    void isoTimestampExpected_matchesActualString() {
        // epochToIso 输出为 String——DiffEngine 既有宽松比较精确命中（无需类型扩展的锚定）
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("epoch-kit")
                .rules(List.of(MappingRule.builder()
                        .from("ts").to("eventTime").transform("epochToIso").build()))
                .build();

        TransferAssert.assertThat(spec)
                .withFixtureJson("{\"ts\": 1768473000000}")
                .matchesExpectedJson("{\"eventTime\": \"2026-01-15T10:30:00Z\"}");
    }
}
