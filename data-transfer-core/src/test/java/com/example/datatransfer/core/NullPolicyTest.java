package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.spec.DefaultValue;
import com.example.datatransfer.core.spec.IntermediateField;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.TransferOptions;
import com.example.datatransfer.core.spec.TransferSpec;

import tools.jackson.databind.JsonNode;

/**
 * null / missing / defaults 行为表（外部评审二轮 评审二-12）：
 * <ul>
 *   <li>skip：null 源值跳过，不产出目标键（变换链不执行）；</li>
 *   <li>keep：null 是合法值，原样写入；defaults 段不覆盖已存在的键（即使值是 null）；</li>
 *   <li>default：null 进入变换链（default() 可兜底）；链后仍为 null 则不写入，交给 defaults 段。</li>
 * </ul>
 * 键缺失（规则未命中）在任何策略下都不执行变换链，目标键留给 defaults 段。
 */
class NullPolicyTest {

    private static final String NULL_SOURCE = "{\"a\": null}";
    private static final String WITH_DEFAULT = "default('N/A')";

    @Test
    void skip_dropsNullBeforeTransformChain() {
        assertThat(transfer(NullPolicy.SKIP, null, null, NULL_SOURCE).toString()).isEqualTo("{}");
        // 历史行为锚定：skip（默认策略）下 default() 根本不执行
        assertThat(transfer(NullPolicy.SKIP, WITH_DEFAULT, null, NULL_SOURCE).toString()).isEqualTo("{}");
        assertThat(transfer(NullPolicy.SKIP, null, "D", NULL_SOURCE).toString()).isEqualTo("{\"x\":{\"a\":\"D\"}}");
        assertThat(transfer(NullPolicy.SKIP, WITH_DEFAULT, "D", NULL_SOURCE).toString())
                .isEqualTo("{\"x\":{\"a\":\"D\"}}");
    }

    @Test
    void keep_writesNullAndDefaultsDoNotOverrideIt() {
        assertThat(transfer(NullPolicy.KEEP, null, null, NULL_SOURCE).toString()).isEqualTo("{\"x\":{\"a\":null}}");
        assertThat(transfer(NullPolicy.KEEP, WITH_DEFAULT, null, NULL_SOURCE).toString())
                .isEqualTo("{\"x\":{\"a\":\"N/A\"}}");
        // 历史行为锚定：putIfAbsent 把「值为 null」视为 absent → null 被 defaults 覆盖
        assertThat(transfer(NullPolicy.KEEP, null, "D", NULL_SOURCE).toString()).isEqualTo("{\"x\":{\"a\":null}}");
        assertThat(transfer(NullPolicy.KEEP, WITH_DEFAULT, "D", NULL_SOURCE).toString())
                .isEqualTo("{\"x\":{\"a\":\"N/A\"}}");
    }

    @Test
    void default_runsTransformChainAndLeavesResidualNullToDefaults() {
        // 历史行为锚定：default 与 keep 输出完全相同（null 原样写入）
        assertThat(transfer(NullPolicy.DEFAULT, null, null, NULL_SOURCE).toString()).isEqualTo("{}");
        assertThat(transfer(NullPolicy.DEFAULT, WITH_DEFAULT, null, NULL_SOURCE).toString())
                .isEqualTo("{\"x\":{\"a\":\"N/A\"}}");
        assertThat(transfer(NullPolicy.DEFAULT, null, "D", NULL_SOURCE).toString()).isEqualTo("{\"x\":{\"a\":\"D\"}}");
        assertThat(transfer(NullPolicy.DEFAULT, WITH_DEFAULT, "D", NULL_SOURCE).toString())
                .isEqualTo("{\"x\":{\"a\":\"N/A\"}}");
    }

    @Test
    void missingKey_isLeftToDefaultsUnderEveryPolicy() {
        for (NullPolicy policy : NullPolicy.values()) {
            assertThat(transfer(policy, WITH_DEFAULT, null, "{}").toString())
                    .as("%s: 规则未命中不执行变换链", policy).isEqualTo("{}");
            assertThat(transfer(policy, null, "D", "{}").toString())
                    .as("%s: defaults 注入", policy).isEqualTo("{\"x\":{\"a\":\"D\"}}");
        }
    }

    @Test
    void zeroFalseAndEmptyString_areNeverTreatedAsNull() {
        for (NullPolicy policy : NullPolicy.values()) {
            Map<String, String> samples = new LinkedHashMap<>();
            samples.put("{\"a\": 0}", "0");
            samples.put("{\"a\": false}", "false");
            samples.put("{\"a\": \"\"}", "\"\"");
            samples.forEach((json, expected) -> assertThat(
                    transfer(policy, WITH_DEFAULT, "D", json).path("x").path("a").toString())
                    .as("%s %s", policy, json).isEqualTo(expected));
        }
    }

    @Test
    void defaults_neverOverrideMappedValues_nullDefaultWritesNull() {
        for (NullPolicy policy : NullPolicy.values()) {
            assertThat(transfer(policy, null, "D", "{\"a\": \"real\"}").path("x").path("a").asString())
                    .as("%s: 已映射值不被覆盖", policy).isEqualTo("real");
        }
        TransferSpec nullDefault = TransferSpec.builder().version("1.0").name("null-default")
                .rules(List.of(MappingRule.builder().from("a").to("x.a").build()))
                .defaults(List.of(DefaultValue.builder().to("x.b").value(null).build()))
                .build();
        assertThat(new TransferEngine(nullDefault).transfer("{}").toString()).isEqualTo("{\"x\":{\"b\":null}}");
    }

    @Test
    void intermediateFromForm_followsTheSamePolicies() {
        for (NullPolicy policy : NullPolicy.values()) {
            TransferOptions options = new TransferOptions();
            options.setNullPolicy(policy);
            TransferSpec spec = TransferSpec.builder().version("1.0").name("staged-" + policy).options(options)
                    .intermediate(List.of(IntermediateField.builder().to("$d").from("a")
                            .transform(WITH_DEFAULT).build()))
                    .rules(List.of(MappingRule.builder().from("$d").to("out.d").build(),
                            MappingRule.builder().from("a").to("out.raw").build()))
                    .defaults(List.of(DefaultValue.builder().to("out.d").value("D").build()))
                    .build();

            JsonNode out = new TransferEngine(spec).transfer(NULL_SOURCE).path("out");

            assertThat(out.path("raw").toString()).as("%s: 规则映射的 null 处置", policy)
                    .isEqualTo(policy == NullPolicy.KEEP ? "null" : "");
            assertThat(out.path("d").toString()).as("%s: 暂存 + defaults", policy)
                    .isEqualTo(policy == NullPolicy.SKIP ? "\"D\"" : "\"N/A\"");
        }
    }

    /** 规则 a → x.a（可选 transform 链）+ 可选 defaults(x.a = defaultValue)，按给定 nullPolicy 转换 */
    private static JsonNode transfer(NullPolicy policy, String transform, String defaultValue, String json) {
        TransferOptions options = new TransferOptions();
        options.setNullPolicy(policy);
        TransferSpec.TransferSpecBuilder builder = TransferSpec.builder()
                .version("1.0").name("null-" + policy).options(options)
                .rules(List.of(MappingRule.builder().from("a").to("x.a").transform(transform).build()));
        if (defaultValue != null) {
            builder.defaults(List.of(DefaultValue.builder().to("x.a").value(defaultValue).build()));
        }
        return new TransferEngine(builder.build()).transfer(json);
    }
}
