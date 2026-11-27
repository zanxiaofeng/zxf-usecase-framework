package com.example.datatransfer.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.jexl3.JexlException;
import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.exception.TransferException;

/** JEXL 求值器：数值契约、沙箱约束（外部评审二轮 P1-1 / P1-4 / P1-5）与嵌套视图导航。 */
class JexlExpressionEvaluatorTest {

    private final JexlExpressionEvaluator evaluator = new JexlExpressionEvaluator();

    /** 与引擎 unflattenToMap 同形态的视图：LinkedHashMap / ArrayList / BigDecimal / String / Boolean */
    private static Map<String, Object> view() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", " Zhang San ");
        customer.put("tier", "gold");
        customer.put("nick", null);
        List<Object> lines = new ArrayList<>();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("unitPrice", new BigDecimal("113.0"));
        line.put("quantity", 2);
        lines.add(line);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("customer", customer);
        root.put("lines", lines);
        root.put("status", "PAID");
        root.put("rate", new BigDecimal("0.9"));
        root.put("zero", 0);
        root.put("flag", false);
        root.put("big", new BigDecimal("12345678901234567890.123"));
        return root;
    }

    @Test
    void plainExpression_navigatesNestedView() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("crmOrder.id", "ORD-1");
        context.put("crmOrder.lines[0].qty", 2);
        context.put("crmOrder.lines[1].qty", 3);

        Object result = evaluator.evaluate("crmOrder.lines[0].qty + crmOrder.lines[1].qty", context);

        assertThat(((Number) result).intValue()).isEqualTo(5);
    }

    // ---- 数值契约 ----

    @Test
    void decimalArithmetic_doubleLiteralsAreExact() {
        // JEXL 把无后缀小数字面量解析为 Double（0.1 + 0.2 = 0.30000000000000004）——算术层统一提升为 BigDecimal
        assertThat((BigDecimal) evaluator.evaluate("0.1 + 0.2", Map.of())).isEqualByComparingTo("0.3");
        assertThat((BigDecimal) evaluator.evaluate("1.13 * 100", Map.of())).isEqualByComparingTo("113");
        assertThat((Object) evaluator.evaluate("0.1 + 0.2 == 0.3", Map.of())).isEqualTo(true);
        assertThat((Number) evaluator.evaluate("10 / 4", Map.of())).isEqualTo(2);   // 整数相除保持 Java 整除语义
        assertThat((BigDecimal) evaluator.evaluate("10.0 / 4", Map.of())).isEqualByComparingTo("2.5");
        assertThat((BigDecimal) evaluator.evaluate("-1.13", Map.of())).isEqualByComparingTo("-1.13");
    }

    @Test
    void decimalArithmetic_viewDecimalsStayExact() {
        assertThat((BigDecimal) evaluator.evaluate("lines[0].unitPrice * lines[0].quantity", view()))
                .isEqualByComparingTo("226");
        assertThat((BigDecimal) evaluator.evaluate("rate * 100", view())).isEqualByComparingTo("90");
        assertThat((BigDecimal) evaluator.evaluate("big * 2", view()))
                .isEqualByComparingTo("24691357802469135780.246");
        assertThat((Object) evaluator.evaluate("0.1 + 0.2 > 0.29 ? 'ok' : 'ko'", view())).isEqualTo("ok");
        assertThat((Object) evaluator.evaluate("big > 12345678901234567000", view())).isEqualTo(true);
    }

    @Test
    void nullishCoalescing_keepsZeroAndFalse() {
        // ?: 是布尔语义（0 / false 会被替换）；?? 才是「首个非 null 值」
        assertThat((Object) evaluator.evaluate("zero ?? 'fallback'", view())).isEqualTo(0);
        assertThat((Object) evaluator.evaluate("flag ?? 'fallback'", view())).isEqualTo(false);
        assertThat((Object) evaluator.evaluate("customer.nick ?? 'anonymous'", view())).isEqualTo("anonymous");
        assertThat((Object) evaluator.evaluate("customer.absent ?? customer.tier", view())).isEqualTo("gold");
        assertThat((Object) evaluator.evaluate("zero ?: 'fallback'", view())).isEqualTo("fallback");
    }

    // ---- 沙箱：默认拒绝 + 运行期具体类型只读白名单 ----

    @Test
    void sandbox_allowsReadOnlyNavigationAndMethods() {
        Map<String, Object> view = view();

        assertThat((Object) evaluator.evaluate("customer.name.trim() + ' <' + status.toUpperCase() + '>'", view))
                .isEqualTo("Zhang San <PAID>");
        assertThat((Object) evaluator.evaluate("lines.size()", view)).isEqualTo(1);
        assertThat((Object) evaluator.evaluate("customer.containsKey('tier')", view)).isEqualTo(true);
        assertThat(new BigDecimal(evaluator.evaluate("lines[0]['unitPrice'] + 1", view).toString()))
                .isEqualByComparingTo("114");
        assertThat((Object) evaluator.evaluate("status == 'PAID' ? rate : 1", view)).isEqualTo(new BigDecimal("0.9"));
        assertThat((Object) evaluator.evaluate("rate.setScale(1)", view)).isEqualTo(new BigDecimal("0.9"));
    }

    @Test
    void sandbox_rejectsMutation() {
        Map<String, Object> view = view();

        assertThatThrownBy(() -> evaluator.evaluate("lines.add('INJECTED')", view)).isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("customer.put('k', 1)", view)).isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("customer.remove('tier')", view)).isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("customer.clear()", view)).isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("lines[0] = 'y'", view)).isInstanceOf(JexlException.class);
        Map<?, ?> customer = (Map<?, ?>) view.get("customer");
        assertThat(((List<?>) view.get("lines"))).hasSize(1);
        assertThat(customer.containsKey("tier")).isTrue();
        assertThat(customer.containsKey("k")).isFalse();
    }

    @Test
    void sandbox_rejectsConstructors() {
        Map<String, Object> view = view();

        for (String expr : List.of("new('java.util.ArrayList')", "new('java.lang.String', 'x')",
                "new('java.lang.StringBuilder')", "new('java.lang.ProcessBuilder')", "new('java.lang.Runtime')",
                "new('javax.script.ScriptEngineManager')")) {
            assertThatThrownBy(() -> evaluator.evaluate(expr, view))
                    .as(expr)
                    .isInstanceOf(JexlException.class);
        }
    }

    @Test
    void sandbox_rejectsFileIoThroughJexlDefaultPermissionsAllowList() {
        // JEXL 3.3 默认 RESTRICTED 权限放行 java.io.*（仅禁 File/FileDescriptor）——文件读写须由项目沙箱拦住
        String path = System.getProperty("java.io.tmpdir") + "/dt-sandbox-should-not-exist";
        Map<String, Object> view = view();

        assertThatThrownBy(() -> evaluator.evaluate(
                "new('java.io.FileOutputStream', '" + path + "').write(65)", view))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate(
                "new('java.io.BufferedReader', new('java.io.InputStreamReader', "
                        + "new('java.io.FileInputStream', '" + path + "'))).readLine()", view))
                .isInstanceOf(JexlException.class);
        assertThat(new java.io.File(path)).doesNotExist();
    }

    @Test
    void sandbox_rejectsReflection() {
        Map<String, Object> view = view();

        assertThatThrownBy(() -> evaluator.evaluate("'x'.getClass().forName('java.lang.Runtime')", view))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("customer.getClass().getClassLoader()", view))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("customer.class.name", view))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("T(java.lang.Runtime).getRuntime().exec('echo pwned')", view))
                .isInstanceOf(JexlException.class);
    }

    @Test
    void sandbox_deniesTypesOutsideTheAllowList() {
        // 未列入名单的类型默认拒绝（非白名单容器的 HashMap、任意 POJO）
        // 视图构建会把容器统一成 JDK 的 LinkedHashMap / ArrayList、把 POJO 取成 plain 形态，
        // 所以直接注入未经整形的嵌套视图，验证沙箱对清单外类型默认拒绝
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("other", new HashMap<>(Map.of("k", "v")));
        view.put("alien", new java.util.Date(0));

        assertThatThrownBy(() -> evaluator.evaluate("other.k", view, view))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("alien.time", view, view))
                .isInstanceOf(JexlException.class);
    }

    @Test
    void unsolvableMethodsAndMisspelledPathsFailLoudly() {
        // safe 关闭：白名单外的方法调用与「在缺失值上继续解引用」不再静默返回 null
        Map<String, Object> view = view();

        assertThatThrownBy(() -> evaluator.evaluate("customer.absent.size()", view))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("customer.name.chop()", view))
                .isInstanceOf(JexlException.class);
        assertThatThrownBy(() -> evaluator.evaluate("customer.absent.deep", view))
                .isInstanceOf(JexlException.class);
        // 缺键兜底写法不受影响：?: / ?? / == null / empty
        assertThatCode(() -> evaluator.evaluate("customer.absent ?: 'd'", view)).doesNotThrowAnyException();
        assertThatCode(() -> evaluator.evaluate("customer.absent ?? 'd'", view)).doesNotThrowAnyException();
        assertThatCode(() -> evaluator.evaluate("customer.absent == null ? 'd' : 'v'", view)).doesNotThrowAnyException();
        assertThatCode(() -> evaluator.evaluate("empty(customer.absent) ? 'd' : 'v'", view)).doesNotThrowAnyException();
    }

    @Test
    void divisionByZeroFailsAsJexlError() {
        assertThatThrownBy(() -> evaluator.evaluate("1 / 0", Map.of())).isInstanceOf(JexlException.class);
    }

    // ---- 装配期校验 ----

    @Test
    void bracketAccess_reachesKeysWithHyphen() {
        // 路径语法允许 '-'（如 unit-price），而 JEXL 把 m.unit-price 解释为 m.unit - price（减法）——
        // 含 '-' 的键必须用方括号访问；减法形态在 safe 关闭下明确报错而不是静默算错
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("m.unit-price", new BigDecimal("8.2"));

        assertThat((Object) evaluator.evaluate("m['unit-price'] * 2", flat)).isEqualTo(new BigDecimal("16.4"));
        assertThatThrownBy(() -> evaluator.evaluate("m.unit-price * 2", flat))
                .isInstanceOf(JexlException.class);
    }

    @Test
    void validate_acceptsWellFormedExpressions() {
        assertThatCode(() -> evaluator.validate("customer.name.trim() + ' <' + $buyerEmail + '>'", "computed #0"))
                .doesNotThrowAnyException();
        assertThatCode(() -> evaluator.validate("sum(crm.lines[*].unitPrice * crm.lines[*].quantity)", "computed #1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> evaluator.validate("status == 'PAID' ? 0.9 : 1.0", "intermediate #0"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsSyntaxErrorsAtAssemblyTime() {
        // 历史行为：语法错误在构造期通过、首个请求才裸抛 JexlException.Parsing
        for (String expr : List.of("sum(", "((", "value >", "a +* b")) {
            assertThatThrownBy(() -> evaluator.validate(expr, "computed #0 [out.total]"))
                    .as(expr)
                    .isInstanceOf(TransferAssemblyException.class)
                    .hasMessageContaining("computed #0 [out.total]")
                    .hasMessageContaining(expr);
        }
    }

    @Test
    void validate_rejectsMalformedAggregateForms() {
        assertThatThrownBy(() -> evaluator.validate("sum(out.total)", "computed #0"))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("[*]");
        assertThatThrownBy(() -> evaluator.validate("sum(a[*].x * b[*].y * c[*].z)", "computed #1"))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("at most two operands");
        assertThatThrownBy(() -> evaluator.validate("nosuch(a[*].x)", "computed #2"))
                .isInstanceOf(TransferAssemblyException.class);
    }

    // ---- 聚合 ----

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
                .isInstanceOf(TransferException.class)
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

    @Test
    void aggregate_decimalsAreExact() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("items[0].p", new BigDecimal("0.1"));
        context.put("items[1].p", new BigDecimal("0.2"));
        context.put("items[0].q", 3);
        context.put("items[1].q", 7);

        assertThat((BigDecimal) evaluator.evaluate("sum(items[*].p)", context)).isEqualByComparingTo("0.3");
        assertThat((BigDecimal) evaluator.evaluate("sum(items[*].p * items[*].q)", context))
                .isEqualByComparingTo("1.7");
        assertThat((BigDecimal) evaluator.evaluate("avg(items[*].p)", context)).isEqualByComparingTo("0.15");
        assertThat((BigDecimal) evaluator.evaluate("count(items[*].p)", context)).isEqualByComparingTo("2");
    }

    // ---- 独立审查后的加固 ----

    @Test
    void decimalArithmetic_numericStringsStayExact() {
        // 上游以字符串传递的小数（金额 "8.20"）此前走 JEXL 的 double 路径：0.1 * 3 = 0.30000000000000004
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("price", "0.1");
        context.put("amount", "8.20");
        context.put("qty", 3);
        context.put("n", "10");
        context.put("m", "4");
        context.put("code", "010");
        context.put("serial", "123");

        assertThat((BigDecimal) evaluator.evaluate("price * 3", context)).isEqualByComparingTo("0.3");
        assertThat((BigDecimal) evaluator.evaluate("amount * qty", context)).isEqualByComparingTo("24.6");
        assertThat((Object) evaluator.evaluate("price == 0.1", context)).isEqualTo(true);
        // JEXL 的操作数分类不变：纯数字字符串仍按整数处理（整除）
        assertThat(new BigDecimal(evaluator.evaluate("n / m", context).toString())).isEqualByComparingTo("2");
        // + 遇到字符串仍是拼接：数值字符串不会被当成算术（'010' + '123' 不能变成 133）
        assertThat((Object) evaluator.evaluate("code + serial", context)).isEqualTo("010123");
        assertThat((Object) evaluator.evaluate("price + 0.2", context)).isEqualTo("0.10.2");
    }

    @Test
    void sandbox_scalarPropertyFormsAreRejected() {
        // JEXL 的属性访问本身就是方法调用：s.class → getClass()、s.bytes → getBytes()、n.MAX_VALUE 读静态字段。
        // 标量的属性读取关闭后，只能调用白名单里的方法
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("s", "value");
        context.put("n", 1);
        context.put("f", true);
        context.put("bd", new BigDecimal("1.10"));
        context.put("m.k", "v");

        for (String expr : List.of("s.class", "s.bytes", "s.empty", "n.MAX_VALUE", "f.TRUE", "bd.scale", "bd.ZERO")) {
            assertThatThrownBy(() -> evaluator.evaluate(expr, context))
                    .as(expr)
                    .isInstanceOf(JexlException.class);
        }
        assertThat((Object) evaluator.evaluate("bd.scale()", context)).isEqualTo(2);   // 白名单方法仍可用（1.10 的 scale）
        // getClass 在权限层一并封掉：容器的属性读取必须全放行（Map 的键不可穷举），但 m.class 只能得到 Map 的缺失键语义
        assertThat((Object) evaluator.evaluate("m.class", context)).isNull();
        assertThat((Object) evaluator.evaluate("m['class']", context)).isNull();
        assertThatThrownBy(() -> evaluator.evaluate("m.k.class", context)).isInstanceOf(JexlException.class);
    }

    @Test
    void nullSafeBuiltins_treatMissingPathsAsEmpty() {
        // 显式的空值安全写法不受 safe 关闭影响：empty()/size() 对不存在的路径返回 true / 0，?. 返回 null
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("m.k", "v");

        assertThat((Object) evaluator.evaluate("empty(m.absent.deep)", context)).isEqualTo(true);
        assertThat((Object) evaluator.evaluate("size(m.absent.deep)", context)).isEqualTo(0);
        assertThat((Object) evaluator.evaluate("m.absent?.x", context)).isNull();
        assertThat((Object) evaluator.evaluate("m.absent?.x ?: 'fallback'", context)).isEqualTo("fallback");
        // 写错路径不再是静默 null
        assertThatThrownBy(() -> evaluator.evaluate("m.absent.deep", context)).isInstanceOf(JexlException.class);
    }

    @Test
    void scriptFeatures_areRejectedAtParseTime() {
        // 表达式只能是表达式：lambda / new / 局部变量 / 多条语句 / 赋值在解析期拒绝，装配期即暴露
        for (String expr : List.of("(x) -> x + 1", "x -> x + 1", "var v = 1; v", "1 + 1; 2 + 2", "m.k = 1")) {
            assertThatThrownBy(() -> evaluator.validate(expr, "computed #0"))
                    .as(expr)
                    .isInstanceOf(TransferAssemblyException.class);
        }
    }

    @Test
    void validate_errorMessages_carryExpressionPositionButNotInternalCallSite() {
        // 错误消息保留表达式内的出错位置（@列:行），不带 JEXL 内部调用点前缀
        // （历史实现：清洗正则多写了一个冒号，从未匹配，消息里一直带着
        //   "com.example.datatransfer.core.expression.JexlExpressionEvaluator.validate:153@1:4 …"）
        assertThatThrownBy(() -> evaluator.validate("a +* b", "computed #0 [out.x]"))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("@1:3 parsing error in '+'")
                .hasMessageNotContaining("com.example.");
        assertThatThrownBy(() -> evaluator.validate("'unterminated", "computed #0 [out.x]"))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("tokenization error")
                .hasMessageNotContaining("com.example.");
    }

    @Test
    void validate_rejectsMalformedAggregateOperands() {
        // 聚合操作数此前只查「含 [*]」与个数，非法路径留到运行期
        for (String expr : List.of("sum(a..b[*])", "sum([*])", "sum(a[*]..b)")) {
            assertThatThrownBy(() -> evaluator.validate(expr, "computed #2 [out.total]"))
                    .as(expr)
                    .isInstanceOf(TransferAssemblyException.class)
                    .hasMessageContaining("computed #2 [out.total]");
        }
        assertThatCode(() -> evaluator.validate("sum($x[*].p * out.lines[*].q)", "computed #0"))
                .doesNotThrowAnyException();
    }
}
