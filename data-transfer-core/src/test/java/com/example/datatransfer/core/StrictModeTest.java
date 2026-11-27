package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.exception.RuleMatchException;
import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.DefaultValue;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.TransferOptions;
import com.example.datatransfer.core.spec.TransferSpec;

import tools.jackson.databind.JsonNode;

/**
 * 目标键冲突治理（外部评审二轮 评审二-10）：strictMode 覆盖「运行期实际写入」，
 * 结构冲突（祖先 / 数组 vs 对象）无论是否 strict 都在装配期拒绝。
 */
class StrictModeTest {

    private static final String OVERLAP_INPUT = "{\"a\": [{\"x\": 1}], \"b\": [{\"x\": 2}]}";

    @Test
    void strictMode_rejectsWildcardTargetOverwriteAtRuntime() {
        // 通配规则 t[*].v 展开后覆盖字面目标 t[0].v：装配期不可穷举，运行期写入前拦截
        // 历史行为：t[0].v 被静默覆盖
        assertThatThrownBy(() -> new TransferEngine(overlappingRules(true)).transfer(OVERLAP_INPUT))
                .isInstanceOf(RuleMatchException.class)
                .hasMessageContaining("t[0].v")
                .hasMessageContaining("rule #0")
                .hasMessageContaining("rule #1");
    }

    @Test
    void nonStrict_keepsLastWriteWinsForOverlappingTargets() {
        JsonNode result = new TransferEngine(overlappingRules(false)).transfer(OVERLAP_INPUT);

        assertThat(result.toString()).isEqualTo("{\"t\":[{\"v\":2}]}");
    }

    @Test
    void strictMode_rejectsDuplicateTargetsAcrossSectionsAtAssembly() {
        // 字面重复：检测口径统一为规范化路径（[01] 与 [1] 是同一目标）
        assertThatThrownBy(() -> new TransferEngine(strict(List.of(
                MappingRule.builder().from("a").to("t.k[01]").build(),
                MappingRule.builder().from("b").to("t.k[1]").build()), List.of(), List.of())))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("duplicate literal target key 't.k[1]'");

        // 通配模式完全相同：装配期即可判定（历史行为以「数据期不可穷举」放行）
        TransferSpec samePattern = strict(List.of(
                MappingRule.builder().from("a[*].x").to("t[*].v").build(),
                MappingRule.builder().from("b[*].x").to("t[*].v").build()), List.of(), List.of());
        assertThatThrownBy(() -> new TransferEngine(samePattern))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("duplicate wildcard target 't[*].v'");

        // computed / defaults 与规则同目标
        assertThatThrownBy(() -> new TransferEngine(strict(
                List.of(MappingRule.builder().from("a").to("t.v").build()),
                List.of(ComputedField.builder().to("t.v").expr("1 + 1").build()), List.of())))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("computed #0")
                .hasMessageContaining("rule #0");
        assertThatThrownBy(() -> new TransferEngine(strict(
                List.of(MappingRule.builder().from("a").to("t.v").build()), List.of(),
                List.of(DefaultValue.builder().to("t.v").value("d").build()))))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("defaults #0")
                .hasMessageContaining("rule #0");
    }

    @Test
    void strictMode_rejectsLiteralTargetCoveredByWildcardRule_atRuntime() {
        // 通配规则 t[*].v 展开后覆盖字面目标 t[0].v——装配期不可穷举，运行期拦截
        TransferSpec spec = strict(
                List.of(MappingRule.builder().from("a[*].x").to("t[*].v").build()),
                List.of(ComputedField.builder().to("t[0].v").expr("42").build()), List.of());

        assertThatThrownBy(() -> new TransferEngine(spec).transfer("{\"a\": [{\"x\": 1}]}"))
                .isInstanceOf(RuleMatchException.class)
                .hasMessageContaining("t[0].v")
                .hasMessageContaining("computed #0");
    }

    @Test
    void nonStrict_computedStillOverwritesRuleOutput() {
        TransferSpec spec = TransferSpec.builder().version("1.0").name("computed-wins")
                .rules(List.of(MappingRule.builder().from("a").to("t.v").build()))
                .computed(List.of(ComputedField.builder().to("t.v").expr("42").build()))
                .build();

        assertThat(new TransferEngine(spec).transfer("{\"a\": 1}").toString()).isEqualTo("{\"t\":{\"v\":42}}");
    }

    @Test
    void constructor_rejectsAncestorConflict_regardlessOfStrictMode() {
        // 历史行为：构造通过，运行期裸抛 ClassCastException
        for (boolean strictMode : new boolean[]{false, true}) {
            TransferOptions options = new TransferOptions();
            options.setStrictMode(strictMode);
            TransferSpec spec = TransferSpec.builder().version("1.0").name("ancestor").options(options)
                    .rules(List.of(MappingRule.builder().from("a").to("x.a").build(),
                            MappingRule.builder().from("b").to("x.a.b").build()))
                    .build();

            assertThatThrownBy(() -> new TransferEngine(spec))
                    .as("strictMode=%s", strictMode)
                    .isInstanceOf(TransferAssemblyException.class)
                    .hasMessageContaining("rule #0 [a -> x.a]")
                    .hasMessageContaining("rule #1 [b -> x.a.b]");
        }
    }

    @Test
    void constructor_rejectsArrayObjectConflict_regardlessOfStrictMode() {
        String[][] conflicts = {
                {"x[0]", "x.y"}, {"x[*].a", "x.b"}, {"x[0].a", "x[*]"},
                {"x.a[0]", "x.a.b"}, {"x[*]", "x[0].a"}};
        for (boolean strictMode : new boolean[]{false, true}) {
            for (String[] pair : conflicts) {
                TransferOptions options = new TransferOptions();
                options.setStrictMode(strictMode);
                TransferSpec spec = TransferSpec.builder().version("1.0").name("shape").options(options)
                        .rules(List.of(MappingRule.builder().from(sourceFor(pair[0])).to(pair[0]).build(),
                                MappingRule.builder().from(sourceFor(pair[1])).to(pair[1]).build()))
                        .build();

                assertThatThrownBy(() -> new TransferEngine(spec))
                        .as("strictMode=%s %s vs %s", strictMode, pair[0], pair[1])
                        .isInstanceOf(TransferAssemblyException.class)
                        .hasMessageContaining(pair[0])
                        .hasMessageContaining(pair[1]);
            }
        }
    }

    @Test
    void constructor_checksSectionsAgainstEachOther() {
        TransferSpec defaultsIntoValue = TransferSpec.builder().version("1.0").name("cross")
                .rules(List.of(MappingRule.builder().from("a").to("x.a").build()))
                .defaults(List.of(DefaultValue.builder().to("x.a.b").value(1).build()))
                .build();
        assertThatThrownBy(() -> new TransferEngine(defaultsIntoValue))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("defaults #0")
                .hasMessageContaining("x.a.b");

        TransferSpec computedIntoArray = TransferSpec.builder().version("1.0").name("cross2")
                .rules(List.of(MappingRule.builder().from("a[*]").to("x[*]").build()))
                .computed(List.of(ComputedField.builder().to("x.count").expr("1").build()))
                .build();
        assertThatThrownBy(() -> new TransferEngine(computedIntoArray))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("computed #0")
                .hasMessageContaining("x.count");
    }

    @Test
    void siblingAndIndexedSiblingTargets_coexist() {
        // 同一父路径下的兄弟键、同一数组的不同索引、通配与字面索引并存：合法
        TransferSpec spec = TransferSpec.builder().version("1.0").name("ok-shape")
                .rules(List.of(
                        MappingRule.builder().from("a").to("x.a").build(),
                        MappingRule.builder().from("b").to("x.b").build(),
                        MappingRule.builder().from("c").to("y[2]").build(),
                        MappingRule.builder().from("d").to("y[0]").build(),
                        MappingRule.builder().from("e[*]").to("z[*].k").build(),
                        MappingRule.builder().from("f").to("z[1].j").build()))
                .computed(List.of(ComputedField.builder().to("x.c").expr("1").build()))
                .defaults(List.of(DefaultValue.builder().to("x.d").value("d").build()))
                .build();

        JsonNode result = new TransferEngine(spec)
                .transfer("{\"a\": 1, \"b\": 2, \"c\": 3, \"d\": 4, \"e\": [5], \"f\": 6}");

        assertThat(result.toString()).isEqualTo(
                "{\"x\":{\"a\":1,\"b\":2,\"c\":1,\"d\":\"d\"},\"y\":[4,null,3],\"z\":[{\"k\":5},{\"j\":6}]}");
    }

    /** 规则 #0 通配目标 t[*].v（展开后含 t[0].v）+ 规则 #1 字面目标 t[0].v：模式不同、展开后重叠 */
    /** 与目标路径通配数量一致的源路径（结构冲突检测只看 to） */
    private static String sourceFor(String to) {
        return to.replaceAll("[a-zA-Z_][a-zA-Z0-9_-]*", "a");
    }

    private static TransferSpec overlappingRules(boolean strictMode) {
        return spec(strictMode, List.of(
                MappingRule.builder().from("a[*].x").to("t[*].v").build(),
                MappingRule.builder().from("b[0].x").to("t[0].v").build()), List.of(), List.of());
    }

    private static TransferSpec strict(List<MappingRule> rules, List<ComputedField> computed,
                                       List<DefaultValue> defaults) {
        return spec(true, rules, computed, defaults);
    }

    private static TransferSpec spec(boolean strictMode, List<MappingRule> rules,
                                     List<ComputedField> computed, List<DefaultValue> defaults) {
        TransferOptions options = new TransferOptions();
        options.setStrictMode(strictMode);
        return TransferSpec.builder().version("1.0").name("strict").options(options)
                .rules(rules).computed(computed).defaults(defaults).build();
    }

    @Test
    void computedAndDefaultTargets_areIndexNormalized() {
        // 独立审查发现：computed / defaults 的 to 此前未做索引规范化，t.k[01] 与规则的 t.k[1] 成了两个键——
        // defaults 覆盖了规则已写的值，validations 读到的还是旧值
        TransferSpec withDefault = TransferSpec.builder().version("1.0").name("normalized-default")
                .rules(List.of(MappingRule.builder().from("a").to("t.k[1]").build()))
                .defaults(List.of(DefaultValue.builder().to("t.k[01]").value("DEFAULT").build()))
                .build();
        assertThat(new TransferEngine(withDefault).transfer("{\"a\": \"r\"}").toString())
                .isEqualTo("{\"t\":{\"k\":[null,\"r\"]}}");   // defaults 不覆盖已存在的键

        TransferSpec withComputed = TransferSpec.builder().version("1.0").name("normalized-computed")
                .rules(List.of(MappingRule.builder().from("a").to("t.k[1]").build()))
                .computed(List.of(ComputedField.builder().to("t.k[01]").expr("'c'").build()))
                .validations(List.of(com.example.datatransfer.core.spec.ValidationRule.builder()
                        .path("t.k[1]")
                        .rules(List.of(com.example.datatransfer.core.spec.Assertion.builder()
                                .assertExpr("eq('c')").message("须为 computed 的值").build()))
                        .build()))
                .build();
        // 非 strict 下 computed 覆盖规则值（后覆盖前）；校验看到的是同一个键上的新值
        assertThat(new TransferEngine(withComputed).transfer("{\"a\": \"r\"}").toString())
                .isEqualTo("{\"t\":{\"k\":[null,\"c\"]}}");
    }

    @Test
    void invalidPathExpressions_areAssemblyErrors() {
        // 路径语法错误（编程式构建可绕过 Schema）是装配期异常，而不是裸 IllegalArgumentException
        assertThatThrownBy(() -> new TransferEngine(TransferSpec.builder().version("1.0").name("bad-path")
                .rules(List.of(MappingRule.builder().from("a..b").to("x").build())).build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("a..b");
        assertThatThrownBy(() -> new TransferEngine(TransferSpec.builder().version("1.0").name("bad-computed")
                .rules(List.of(MappingRule.builder().from("a").to("x").build()))
                .defaults(List.of(DefaultValue.builder().to("x..y").value(1).build())).build()))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("defaults #0")
                .hasMessageContaining("x..y");
    }
}
