package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.exception.RuleMatchException;
import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.MissingPolicy;
import com.example.datatransfer.core.spec.SourceDeclaration;
import com.example.datatransfer.core.spec.TransferOptions;
import com.example.datatransfer.core.spec.TransferSpec;

/** 引擎执行快照不可变（外部评审二轮 评审二-11）：构造后修改 spec 不得绕过装配期校验。 */
class ImmutabilityTest {

    private static final String INPUT = "{\"a\": 1, \"zzz\": 9, \"nope\": \"later\"}";

    @Test
    void mutatingSpecAfterConstruction_doesNotAffectTheEngine() {
        // 历史行为：加规则 / 改策略 / 加未支持特性全部在运行期生效，绕过构造期校验
        TransferSpec spec = TransferSpec.builder().version("1.0").name("snapshot")
                .options(new TransferOptions())
                .rules(new ArrayList<>(List.of(MappingRule.builder().from("a").to("x.a").build())))
                .build();
        TransferEngine engine = new TransferEngine(spec);
        String before = engine.transfer(INPUT).toString();

        spec.getRules().add(MappingRule.builder().from("zzz[*].p[*].q").to("y.w").build());   // 通配数量不一致
        spec.getRules().get(0).setTo("x.changed");                                              // 改已有规则
        spec.getRules().add(MappingRule.builder().from("zzz").to("x.a").build());               // strict 重复目标
        spec.getOptions().setStrictMode(true);
        spec.getOptions().setMissingPolicy(MissingPolicy.ERROR);
        spec.setSources(List.of(SourceDeclaration.builder().alias("s").path("p").build()));      // 未支持特性

        assertThat(engine.transfer(INPUT).toString()).isEqualTo(before).isEqualTo("{\"x\":{\"a\":1}}");
    }

    @Test
    void rebuildingFromTheMutatedSpec_isRejected() {
        // 对照：同一份被改动的 spec 重新构造时，装配期校验照常拦截
        TransferSpec spec = TransferSpec.builder().version("1.0").name("snapshot2")
                .rules(List.of(MappingRule.builder().from("a").to("x.a").build()))
                .build();
        new TransferEngine(spec);
        spec.setSources(List.of(SourceDeclaration.builder().alias("s").path("p").build()));

        assertThatThrownBy(() -> new TransferEngine(spec))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("sources");
    }

    @Test
    void policySnapshot_isFixedAtConstruction() {
        TransferSpec spec = TransferSpec.builder().version("1.0").name("policy").options(new TransferOptions())
                .rules(List.of(MappingRule.builder().from("nope").to("y.nope").build()))
                .build();
        TransferEngine engine = new TransferEngine(spec);

        spec.getOptions().setMissingPolicy(MissingPolicy.ERROR);

        assertThat(engine.transfer("{\"a\": 1}").toString()).isEqualTo("{}");   // 仍是构造时的 WARN 语义（nope 未命中不报错）
    }

    @Test
    void runtimePoliciesOfTheSnapshot_areHonoured() {
        // 对照：构造时即声明的 ERROR 策略生效（证明快照读取的是真实配置，而非恒为默认值）
        TransferSpec spec = TransferSpec.builder().version("1.0").name("policy-error")
                .options(new TransferOptions())
                .rules(List.of(MappingRule.builder().from("nope").to("y.nope").build()))
                .build();
        spec.getOptions().setMissingPolicy(MissingPolicy.ERROR);

        assertThatThrownBy(() -> new TransferEngine(spec).transfer("{\"a\": 1}"))
                .isInstanceOf(RuleMatchException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void concurrentTransfers_runAgainstTheSameSnapshot() throws Exception {
        // 并发请求共享同一引擎（同一份不可变快照）：构造后对 spec 的修改不会让任何线程看到不同的合同
        TransferSpec spec = TransferSpec.builder().version("1.0").name("concurrent").options(new TransferOptions())
                .rules(new ArrayList<>(List.of(MappingRule.builder().from("items[*].price").to("out[*]")
                        .transform("multiply(1.13) | round(2)").build())))
                .computed(List.of(com.example.datatransfer.core.spec.ComputedField.builder().to("total")
                        .expr("sum(out[*])").build()))
                .build();
        TransferEngine engine = new TransferEngine(spec);
        String source = "{\"items\":[{\"price\":10.5},{\"price\":0.1},{\"price\":3}]}";
        String expected = engine.transfer(source).toString();

        spec.getRules().add(MappingRule.builder().from("items[*].zzz[*]").to("other[*]").build());
        spec.setSources(List.of(SourceDeclaration.builder().alias("s").path("p").build()));

        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                results.add(pool.submit(() -> engine.transfer(source).toString()));
            }
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo(expected);
            }
        }
        assertThat(expected).isEqualTo("{\"out\":[11.87,0.11,3.39],\"total\":15.37}");
    }

    @Test
    void defaultValueCollections_areSnapshotted() {
        // defaults.value 为集合 / 数组时同样深拷贝：构造后修改原值不影响引擎
        List<Object> tags = new ArrayList<>(List.of("a"));
        TransferSpec spec = TransferSpec.builder().version("1.0").name("default-snapshot")
                .rules(List.of(MappingRule.builder().from("a").to("x.a").build()))
                .defaults(List.of(
                        com.example.datatransfer.core.spec.DefaultValue.builder().to("x.tags").value(tags).build(),
                        com.example.datatransfer.core.spec.DefaultValue.builder().to("x.codes").value(new int[] {1, 2})
                                .build()))
                .build();
        TransferEngine engine = new TransferEngine(spec);

        tags.add("b");

        assertThat(engine.transfer("{}").toString()).isEqualTo("{\"x\":{\"tags\":[\"a\"],\"codes\":[1,2]}}");
    }
}
