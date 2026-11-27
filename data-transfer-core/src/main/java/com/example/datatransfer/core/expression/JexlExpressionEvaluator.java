package com.example.datatransfer.core.expression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.apache.commons.jexl3.introspection.JexlSandbox;

import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.exception.TransferException;
import com.example.datatransfer.core.flatten.FlatMapProcessor;
import com.example.datatransfer.core.flatten.PathParser;

/**
 * 默认表达式求值器（设计文档 §6.2 / §8.6 注）：JEXL 底座。
 *
 * <p>JEXL 无法直接导航含 {@code [*]} 的整串键，本实现分两路：</p>
 * <ul>
 *   <li>聚合形态 {@code agg(PATH)} / {@code agg(PATH * PATH)}（agg ∈ sum/avg/count/min/max，
 *       PATH 为含 {@code [*]} 的通配路径）：由本类展开后按 BigDecimal 计算——
 *       双路径<b>按通配索引元组配对</b>逐元素相乘（review P0：按位置配对在
 *       {@code nullPolicy=SKIP} 下两侧索引集合不同时会静默错位相乘；索引集合不一致
 *       即抛 {@link TransferException}，宁可报错不错数据）；</li>
 *   <li>其余形态：以 FlatMap 的嵌套视图绑定顶层键后交 JEXL 常规求值
 *       （索引路径如 {@code a.b[0].c} 由 JEXL 的 list 导航支持；调用方可经
 *       {@link #evaluate(String, Map, Map)} 复用预构建视图，避免逐表达式重拍）。</li>
 * </ul>
 *
 * <p><b>表达式安全模型</b>（外部评审二轮 P1-1）：spec 表达式按不可信输入对待，
 * {@link JexlSandbox} <b>默认拒绝</b>，仅放行运行期视图的实际具体类型（表达式沙箱的权限继承只看
 * 直接接口，JDK 21 上 {@code LinkedHashMap} 只直接实现 {@code SequencedMap}，故按具体类逐个 allow）：</p>
 * <ul>
 *   <li>容器 {@code LinkedHashMap} / {@code ArrayList}：属性与索引读取放行（Map 的键不可穷举，不能做 key 白名单），
 *       写与修改方法全禁；</li>
 *   <li>标量 String / Integer / Long / Double / BigInteger / BigDecimal / Boolean：属性读取关闭——JEXL 的属性访问
 *       本身就是方法调用（{@code s.class} → getClass()、{@code s.bytes} → getBytes()、{@code n.MAX_VALUE} 读静态字段），
 *       关闭后标量只能调用 {@link #EXECUTE_ALLOWLIST} 列出的只读方法；</li>
 *   <li>其余类型一律拒绝；构造器（{@code new(...)}）在解析期即不允许——补上 JEXL 3.3 默认权限
 *       {@code RESTRICTED} 放行 {@code java.io.*} 文件读写的缺口；{@code Object#getClass} 在权限层一并封掉，
 *       容器上也取不到 {@code Class} 对象；</li>
 *   <li>表达式只能是表达式：循环、lambda、局部变量、pragma、注解、赋值等脚本特性在解析期关闭；</li>
 *   <li>{@code safe} 关闭：白名单外的方法调用、在缺失值上继续解引用直接报错，不再静默返回 null。
 *       显式的空值安全写法不受影响：{@code ?:} / {@code ??} / 三元条件里的缺键兜底、{@code empty(x)} / {@code size(x)}
 *       （不存在的路径视为空 / 0）、安全导航 {@code ?.}（返回 null）。</li>
 * </ul>
 * 运算符（算术、比较、字符串拼接）与容器的属性 / 索引访问不经方法白名单。迭代与类名解析由 JEXL 自身的
 * {@code RESTRICTED} 权限约束，不经项目沙箱。沙箱限制的是能访问什么，不限制 CPU / 内存消耗
 * （设计文档 §11.1）。
 */
public final class JexlExpressionEvaluator implements ExpressionEvaluator {

    private static final Pattern AGGREGATE = Pattern.compile("^(sum|avg|count|min|max)\\((.+)\\)$");

    /** 聚合操作数分割时保护 [*] 内 '*' 的占位符 */
    private static final String WILDCARD_GUARD = "__WC__";

    /** 运行期视图里的容器类型：属性与索引读取放行（Map 的键不可穷举，不能做 key 白名单） */
    private static final Set<String> CONTAINER_TYPES = Set.of("java.util.LinkedHashMap", "java.util.ArrayList");

    private static final Set<String> NUMBER_METHODS =
            Set.of("intValue", "longValue", "doubleValue", "floatValue", "shortValue", "byteValue",
                    "toString", "compareTo");

    /**
     * 允许显式调用的只读方法清单（按接收者运行期类型）。BigDecimal / BigInteger 的算术方法要求实参类型相同
     * （{@code bd.add(other)}，JEXL 不做 Integer → BigDecimal 的宽化），算术请用运算符。
     */
    static final Map<String, Set<String>> EXECUTE_ALLOWLIST = Map.ofEntries(
            Map.entry("java.util.LinkedHashMap", Set.of("get", "size", "isEmpty", "containsKey",
                    "containsValue", "toString")),
            Map.entry("java.util.ArrayList", Set.of("get", "size", "isEmpty", "contains", "indexOf", "toString")),
            Map.entry("java.lang.String", Set.of("length", "isEmpty", "isBlank", "trim", "strip", "toUpperCase",
                    "toLowerCase", "substring", "replace", "indexOf", "lastIndexOf", "startsWith", "endsWith",
                    "contains", "equals", "equalsIgnoreCase", "compareTo", "compareToIgnoreCase", "charAt",
                    "concat", "matches", "split", "toString", "valueOf")),
            Map.entry("java.lang.Integer", NUMBER_METHODS),
            Map.entry("java.lang.Long", NUMBER_METHODS),
            Map.entry("java.lang.Double", NUMBER_METHODS),
            Map.entry("java.math.BigInteger", union(NUMBER_METHODS, Set.of("add", "subtract", "multiply",
                    "divide", "remainder", "abs", "negate", "min", "max"))),
            Map.entry("java.math.BigDecimal", union(NUMBER_METHODS, Set.of("add", "subtract", "multiply",
                    "divide", "setScale", "stripTrailingZeros", "toPlainString", "abs", "negate", "remainder",
                    "signum", "scale", "precision", "round", "intValueExact", "longValueExact", "min", "max"))),
            Map.entry("java.lang.Boolean", Set.of("booleanValue", "toString", "compareTo")));

    /** JEXL 的 Object#getClass 一并封掉：容器的属性读取必须全放行，不封的话 {@code m.class} 仍能取到 Class 对象 */
    private static final JexlPermissions PERMISSIONS =
            JexlPermissions.RESTRICTED.compose("java.lang { Object { getClass(); } }");

    /** 表达式只能是表达式：循环 / lambda / 局部变量 / new / pragma / 注解 / 赋值等脚本特性在解析期关闭 */
    private static final JexlFeatures FEATURES = new JexlFeatures()
            .loops(false)
            .lambda(false)
            .thinArrow(false)
            .localVar(false)
            .newInstance(false)
            .pragma(false)
            .pragmaAnywhere(false)
            .namespacePragma(false)
            .importPragma(false)
            .annotation(false)
            .register(false)
            .sideEffect(false)
            .sideEffectGlobal(false);

    /** 全局共享的只读引擎：默认拒绝沙箱 + 十进制算术；权限、特性与选项全部显式声明，不依赖 JexlBuilder 的全局默认 */
    private static final JexlEngine ENGINE = new JexlBuilder()
            .sandbox(sandbox())
            .permissions(PERMISSIONS)
            .arithmetic(new DecimalArithmetic())
            .features(FEATURES)
            .strict(true)
            .silent(false)
            .safe(false)
            .create();

    private static JexlSandbox sandbox() {
        JexlSandbox sandbox = new JexlSandbox(false);
        EXECUTE_ALLOWLIST.forEach((type, methods) ->
                sandbox.permissions(type, CONTAINER_TYPES.contains(type), false, true)
                        .execute(methods.toArray(String[]::new)));
        return sandbox;
    }

    private static Set<String> union(Set<String> base, Set<String> extra) {
        Set<String> union = new java.util.HashSet<>(base);
        union.addAll(extra);
        return Set.copyOf(union);
    }

    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();

    @Override
    public void validate(String expr, String owner) {
        Matcher aggregate = AGGREGATE.matcher(expr.trim());
        if (aggregate.matches()) {
            validateAggregate(aggregate.group(2), expr, owner);
            return;
        }
        try {
            ENGINE.createExpression(expr);
        } catch (JexlException malformed) {
            throw new TransferAssemblyException("invalid expression at " + owner + " (expr: " + expr + "): "
                    + clean(malformed), malformed);
        }
    }

    /** 聚合形态：每个操作数都是含 [*] 的通配路径（以标识符或 $ 暂存根开头），至多两个操作数 */
    private static void validateAggregate(String inner, String expr, String owner) {
        List<String> operands = operands(inner);
        if (operands.size() > 2) {
            throw new TransferAssemblyException("invalid aggregate expression at " + owner + " (expr: "
                    + expr + "): unsupported aggregate expression (at most two operands)");
        }
        for (String operand : operands) {
            requireWildcardPath(operand, expr, owner);
        }
    }

    private static void requireWildcardPath(String operand, String expr, String owner) {
        if (!operand.contains("[*]")) {
            throw new TransferAssemblyException("invalid aggregate expression at " + owner + " (expr: "
                    + expr + "): operand '" + operand + "' must be a wildcard path containing [*]");
        }
        try {
            PathParser.parsePattern(operand, ".");
        } catch (IllegalArgumentException invalidPath) {
            throw new TransferAssemblyException("invalid aggregate expression at " + owner + " (expr: "
                    + expr + "): operand '" + operand + "' is not a valid path expression", invalidPath);
        }
        if (operand.startsWith("[")) {
            throw new TransferAssemblyException("invalid aggregate expression at " + owner + " (expr: "
                    + expr + "): operand '" + operand + "' must start with an identifier or a staged root");
        }
    }

    @Override
    public Object evaluate(String expr, Map<String, Object> context) {
        return evaluate(expr, context, null);
    }

    @Override
    public Object evaluate(String expr, Map<String, Object> context, Map<String, Object> nestedView) {
        Matcher aggregate = AGGREGATE.matcher(expr.trim());
        if (aggregate.matches()) {
            return aggregate(aggregate.group(1), aggregate.group(2), context);
        }
        Map<String, Object> view = nestedView != null ? nestedView : flatProcessor.unflattenToMap(context, ".");
        return evaluatePlain(expr, view);
    }

    private Object aggregate(String functionName, String inner, Map<String, Object> context) {
        List<String> operands = operands(inner);
        if (operands.size() > 2) {
            throw new TransferException(
                    "unsupported aggregate expression (at most two operands): " + inner);
        }
        LinkedHashMap<List<Integer>, BigDecimal> left = indexedValues(operands.get(0), context, inner);
        if (operands.size() == 1) {
            return aggregateValues(functionName, List.copyOf(left.values()), inner);
        }
        LinkedHashMap<List<Integer>, BigDecimal> right = indexedValues(operands.get(1), context, inner);
        if (!left.keySet().equals(right.keySet())) {
            throw new TransferException(
                    ("aggregate index mismatch between operands (skip-dropped null entries make "
                            + "position-based pairing unsafe): left indexes=%s, right indexes=%s (expr: %s)")
                            .formatted(left.keySet(), right.keySet(), inner));
        }
        List<BigDecimal> products = new ArrayList<>(left.size());
        left.forEach((index, value) -> products.add(value.multiply(right.get(index))));
        return aggregateValues(functionName, products, inner);
    }

    /** 聚合操作数列表：[*] 内的 '*' 不是运算符——先以占位符保护通配符，再按乘号分割 */
    private static List<String> operands(String inner) {
        String[] operands = inner.replace("[*]", WILDCARD_GUARD).split("\\*");
        List<String> restored = new ArrayList<>(operands.length);
        for (String operand : operands) {
            restored.add(operand.replace(WILDCARD_GUARD, "[*]").trim());
        }
        return restored;
    }

    /**
     * 通配路径 →（通配索引元组 → 值），保持文档序。索引元组取路径中全部 {@code [*]}
     * 位置在展开键上的实际索引（多级通配天然支持），配对以此为准（review P0）。
     */
    private LinkedHashMap<List<Integer>, BigDecimal> indexedValues(
            String path, Map<String, Object> context, String expr) {
        if (!path.contains("[*]")) {
            throw new TransferException(
                    "aggregate operand must be a wildcard path containing [*]: " + path + " (expr: " + expr + ")");
        }
        List<Integer> wildcardPositions = wildcardPositions(path);
        LinkedHashMap<List<Integer>, BigDecimal> indexed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : flatProcessor.expandWildcard(path, context)) {
            List<Object> keySegments = PathParser.parse(entry.getKey(), ".");
            List<Integer> tuple = wildcardPositions.stream()
                    .map(position -> (Integer) keySegments.get(position))
                    .toList();
            indexed.put(tuple, toDecimal(entry.getValue(), expr));
        }
        return indexed;
    }

    private static List<Integer> wildcardPositions(String path) {
        List<Integer> positions = new ArrayList<>();
        List<Object> segments = PathParser.parsePattern(path, ".");
        for (int i = 0; i < segments.size(); i++) {
            if (PathParser.WILDCARD.equals(segments.get(i))) {
                positions.add(i);
            }
        }
        return positions;
    }

    private Object aggregateValues(String functionName, List<BigDecimal> values, String expr) {
        return switch (functionName) {
            case "sum" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> values.isEmpty()
                    ? BigDecimal.ZERO
                    : sum(values).divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
            case "count" -> BigDecimal.valueOf(values.size());
            case "min" -> values.stream().reduce(BigDecimal::min)
                    .orElseThrow(() -> new TransferException("aggregate on empty wildcard (expr: " + expr + ")"));
            case "max" -> values.stream().reduce(BigDecimal::max)
                    .orElseThrow(() -> new TransferException("aggregate on empty wildcard (expr: " + expr + ")"));
            default -> throw new TransferException("unknown aggregate function: " + functionName);
        };
    }

    /** 常规表达式：嵌套视图绑定顶层键 */
    private Object evaluatePlain(String expr, Map<String, Object> nestedView) {
        MapContext jexlContext = new MapContext();
        nestedView.forEach(jexlContext::set);
        return ENGINE.createExpression(expr).evaluate(jexlContext);
    }

    /** JEXL 异常消息形如 {@code com.x.Class.method:12@1:4 detail}：去掉调用点前缀，只留定位与原因 */
    static String clean(JexlException exception) {
        String message = String.valueOf(exception.getMessage());
        Matcher locator = LOCATION.matcher(message);
        return locator.find() ? locator.group(1) : message;
    }

    private static final Pattern LOCATION = Pattern.compile("^[^@\\s]*+(@\\d+:\\d+\\s*.*)$", Pattern.DOTALL);

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal toDecimal(Object value, String expr) {
        if (value == null) {
            throw new TransferException("aggregate operand contains null value (expr: " + expr + ")");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
