package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.exception.ExpressionException;
import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.exception.TransferException;
import com.example.datatransfer.core.spec.Assertion;
import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.DefaultValue;
import com.example.datatransfer.core.spec.IntermediateField;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.spec.ValidationRule;

import tools.jackson.databind.JsonNode;

/**
 * 表达式的装配期与运行期契约（外部评审二轮 新发现 2 / 评审四「computed 返回容器」）：
 * 语法错误在装配期拒绝，运行期失败包装为 TransferException，computed 的容器结果展开为扁平键。
 */
class ExpressionAssemblyTest {

    @Test
    void constructor_rejectsSyntaxErrorsInEveryExpressionSite() {
        // 历史行为：computed / intermediate.expr / validations.condition 的语法错误构造通过、首个请求才裸抛
        assertAssemblyError(specWithComputed("sum("), "computed #0 [out.total]");
        assertAssemblyError(specWithIntermediate("(("), "intermediate #0 [$x]");
        assertAssemblyError(specWithCondition("value >"), "validation [path: out.a]");
        assertAssemblyError(specWithWhen("status >"), "rule #0 [a -> out.a]");
    }

    @Test
    void runtimeExpressionFailures_areWrappedAsTransferExceptions() {
        // 历史行为：computed / intermediate.expr / condition 的 JEXL 运行期异常裸抛（非 TransferException）
        assertExpressionError(specWithComputed("out.a / 0"), "computed #0 [out.total]", "out.a / 0");
        assertExpressionError(specWithIntermediate("1 / 0"), "intermediate #0 [$x]", "1 / 0");
        assertExpressionError(specWithCondition("value / 0"), "validation [path: out.a]", "value / 0");
    }

    @Test
    void computed_containersExpandToFlatKeys() {
        // 历史行为：Map / List 结果直接落为嵌套值（validations 寻址不到内部字段），数组字面量裸抛 StreamReadException
        TransferSpec spec = TransferSpec.builder().version("1.0").name("containers")
                .rules(List.of(MappingRule.builder().from("amount").to("x.src.amount").build(),
                        MappingRule.builder().from("list[*]").to("x.list[*]").build()))
                .computed(List.of(
                        ComputedField.builder().to("x.copy").expr("x.src").build(),
                        ComputedField.builder().to("x.clone").expr("x.list").build(),
                        ComputedField.builder().to("x.lit").expr("[1, 2, 3]").build(),
                        ComputedField.builder().to("x.obj").expr("{'k': 'v'}").build(),
                        ComputedField.builder().to("x.first").expr("x.copy.amount * 2").build()))
                .validations(List.of(ValidationRule.builder().path("x.copy.amount")
                        .rules(List.of(Assertion.builder().assertExpr("gt(0)").message("金额必须为正").build()))
                        .build()))
                .build();

        JsonNode result = new TransferEngine(spec).transfer("{\"amount\": 2.5, \"list\": [\"a\", \"b\"]}");

        assertThat(result.toString()).isEqualTo("{\"x\":{\"src\":{\"amount\":2.5},\"list\":[\"a\",\"b\"],"
                + "\"copy\":{\"amount\":2.5},\"clone\":[\"a\",\"b\"],\"lit\":[1,2,3],\"obj\":{\"k\":\"v\"},\"first\":5.0}}");
    }

    @Test
    void computed_emptyContainerStaysAsEmptyValue() {
        // 历史行为：根级空容器经 flatten 被 json-flattener 的 root 键包裹，展开成 to.root
        TransferSpec fromData = TransferSpec.builder().version("1.0").name("empty")
                .rules(List.of(MappingRule.builder().from("a").to("x.a").build(),
                        MappingRule.builder().from("obj").to("y.obj").build(),
                        MappingRule.builder().from("arr").to("y.arr").build()))
                .computed(List.of(ComputedField.builder().to("x.emptyObj").expr("y.obj").build(),
                        ComputedField.builder().to("x.emptyArr").expr("y.arr").build(),
                        ComputedField.builder().to("x.literal").expr("[]").build()))
                .build();

        assertThat(new TransferEngine(fromData).transfer("{\"a\": 1, \"obj\": {}, \"arr\": []}").toString())
                .isEqualTo("{\"x\":{\"a\":1,\"emptyObj\":{},\"emptyArr\":[],\"literal\":[]},"
                        + "\"y\":{\"obj\":{},\"arr\":[]}}");
    }

    @Test
    void constructor_rejectsWildcardInComputedAndDefaultTargets() {
        // 历史行为：Schema 放行，输出里生成名为 "*" 的对象——{"x":{"arr":{"*":{"k":1}}}}
        TransferSpec computed = TransferSpec.builder().version("1.0").name("wild-computed")
                .rules(List.of(MappingRule.builder().from("a").to("x.a").build()))
                .computed(List.of(ComputedField.builder().to("x.arr[*].k").expr("1").build()))
                .build();
        assertThatThrownBy(() -> new TransferEngine(computed))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("computed #0")
                .hasMessageContaining("[*]");

        TransferSpec defaults = TransferSpec.builder().version("1.0").name("wild-defaults")
                .rules(List.of(MappingRule.builder().from("a").to("x.a").build()))
                .defaults(List.of(DefaultValue.builder().to("x.def[*].k").value("D").build()))
                .build();
        assertThatThrownBy(() -> new TransferEngine(defaults))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("defaults #0")
                .hasMessageContaining("[*]");
    }

    private static TransferSpec specWithComputed(String expr) {
        return TransferSpec.builder().version("1.0").name("computed")
                .rules(List.of(MappingRule.builder().from("a").to("out.a").build()))
                .computed(List.of(ComputedField.builder().to("out.total").expr(expr).build()))
                .build();
    }

    private static TransferSpec specWithIntermediate(String expr) {
        return TransferSpec.builder().version("1.0").name("intermediate")
                .intermediate(List.of(IntermediateField.builder().to("$x").expr(expr).build()))
                .rules(List.of(MappingRule.builder().from("$x").to("out.x").build()))
                .build();
    }

    private static TransferSpec specWithCondition(String condition) {
        return TransferSpec.builder().version("1.0").name("condition")
                .rules(List.of(MappingRule.builder().from("a").to("out.a").build()))
                .validations(List.of(ValidationRule.builder().path("out.a").condition(condition)
                        .message("m").build()))
                .build();
    }

    private static TransferSpec specWithWhen(String condition) {
        com.example.datatransfer.core.spec.ConditionMapping branch = new com.example.datatransfer.core.spec.ConditionMapping();
        branch.setCondition(condition);
        branch.setTransform("upper");
        return TransferSpec.builder().version("1.0").name("when")
                .rules(List.of(MappingRule.builder().from("a").to("out.a").when(List.of(branch)).build()))
                .build();
    }

    private static void assertAssemblyError(TransferSpec spec, String owner) {
        assertThatThrownBy(() -> new TransferEngine(spec))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining(owner);
    }

    private static void assertExpressionError(TransferSpec spec, String owner, String expr) {
        assertThatThrownBy(() -> new TransferEngine(spec).transfer("{\"a\": 1}"))
                .isInstanceOf(ExpressionException.class)
                .isInstanceOf(TransferException.class)
                .hasMessageContaining(owner)
                .hasMessageContaining(expr);
    }

    @Test
    void computedContainerOverScalar_isAStructureConflict_evenWithoutStrictMode() {
        // 非 strict 的「后覆盖前」只针对同形态的值：computed 的容器结果要把 a 变成对象，
        // 而规则已把 a 写成标量——这是结构冲突，运行期 fail-fast（清晰报错，而不是静默丢弃其中一个）
        TransferSpec spec = TransferSpec.builder().version("1.0").name("shape-runtime")
                .rules(List.of(MappingRule.builder().from("x").to("a").build()))
                .computed(List.of(ComputedField.builder().to("a").expr("{'b': 1}").build()))
                .build();

        assertThatThrownBy(() -> new TransferEngine(spec).transfer("{\"x\": 5}"))
                .isInstanceOf(com.example.datatransfer.core.exception.RuleMatchException.class)
                .hasMessageContaining("a.b")
                .hasMessageContaining("'a' already holds a value");
    }
}
