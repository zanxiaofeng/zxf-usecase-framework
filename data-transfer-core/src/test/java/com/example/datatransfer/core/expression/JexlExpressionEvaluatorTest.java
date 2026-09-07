package com.example.datatransfer.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.jexl3.JexlException;
import org.junit.jupiter.api.Test;

/** JEXL 求值器：沙箱约束（评审 3.5）与嵌套视图导航。 */
class JexlExpressionEvaluatorTest {

    private final JexlExpressionEvaluator evaluator = new JexlExpressionEvaluator();

    @Test
    void plainExpression_navigatesNestedView() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("crmOrder.id", "ORD-1");
        context.put("crmOrder.lines[0].qty", 2);
        context.put("crmOrder.lines[1].qty", 3);

        Object result = evaluator.evaluate("crmOrder.lines[0].qty + crmOrder.lines[1].qty", context);

        assertThat(((Number) result).intValue()).isEqualTo(5);
    }

    @Test
    void sandbox_blocksDangerousConstructors() {
        // 白名单沙箱：JEXL 的真实类访问面是 new('...') 构造（评审 3.5）
        Map<String, Object> context = new LinkedHashMap<>();

        assertThatThrownBy(() -> evaluator.evaluate("new('java.lang.Runtime')", context))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("new('java.lang.ProcessBuilder')", context))
                .isInstanceOf(JexlException.class);
        // 反射链：Class 不在白名单——forName 不可见，调用静默 null（不执行，无害）
        Object reflected = evaluator.evaluate("'x'.getClass().forName('java.lang.Runtime')", context);
        assertThat(reflected).isNull();
    }

    @Test
    void spelStyleSyntaxIsHarmlesslyNull() {
        // T(...) 是 SpEL 语法，JEXL 中只是未定义变量上的方法调用——不解析类、静默返回 null
        Map<String, Object> context = new LinkedHashMap<>();

        Object result = evaluator.evaluate("T(java.lang.Runtime).getRuntime().exec('echo pwned')", context);

        assertThat(result).isNull();
    }

    @Test
    void sandbox_rejectsConstructorInvocation() {
        Map<String, Object> context = new LinkedHashMap<>();

        assertThatThrownBy(() -> evaluator.evaluate("new('java.lang.ProcessBuilder')", context))
                .isInstanceOf(JexlException.class);
    }

    @Test
    void aggregate_sumOverWildcard() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("lines[0].p", 10);
        context.put("lines[1].p", 20);

        Object result = evaluator.evaluate("sum(lines[*].p)", context);

        assertThat(((java.math.BigDecimal) result)).isEqualByComparingTo("30");
    }

    @Test
    void aggregate_twoOperandIndexMismatchFailsFast() {
        // review P0：SKIP 策略下两侧索引集合不同（左 [0],[2] vs 右 [0],[1]）——按位置配对会
        // 静默错位相乘（100×2 + 7×3=221）；索引配对下必须报错，宁可失败不错数据
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("items[0].p", 100);
        context.put("items[2].p", 7);
        context.put("items[0].q", 2);
        context.put("items[1].q", 3);

        assertThatThrownBy(() -> evaluator.evaluate(
                "sum(items[*].p * items[*].q)", context))
                .isInstanceOf(com.example.datatransfer.core.exception.TransferException.class)
                .hasMessageContaining("index mismatch");
    }

    @Test
    void aggregate_alignedIndexesPairCorrectly() {
        // 索引集合一致时（即使文档序中交错）按索引正确配对
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("items[0].p", 100);
        context.put("items[1].p", 7);
        context.put("items[1].q", 3);
        context.put("items[0].q", 2);   // 故意乱序

        Object result = evaluator.evaluate("sum(items[*].p * items[*].q)", context);

        assertThat(((java.math.BigDecimal) result)).isEqualByComparingTo("221");
    }
}
