package com.example.datatransfer.core.expression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlSandbox;

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
 */
public final class JexlExpressionEvaluator implements ExpressionEvaluator {

    private static final Pattern AGGREGATE = Pattern.compile("^(sum|avg|count|min|max)\\((.+)\\)$");

    /** 聚合操作数分割时保护 [*] 内 '*' 的占位符 */
    private static final String WILDCARD_GUARD = "__WC__";

    /**
     * 安全沙箱（评审 3.5）：白名单模式——仅放行嵌套视图导航所需的基础类型与集合类，
     * {@code T(...)} 静态访问与 {@code new} 反射构造一律禁止（表达式来自 spec 配置，
     * 默认按不可信输入对待）。
     */
    private static final JexlEngine ENGINE = new JexlBuilder()
            .sandbox(sandbox())
            .create();

    private static JexlSandbox sandbox() {
        JexlSandbox sandbox = new JexlSandbox(true);   // 白名单模式：未列出的类禁止构造与执行
        Stream.of("java.util.Map", "java.util.List", "java.lang.String", "java.lang.Number",
                        "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                        "java.lang.Boolean", "java.math.BigDecimal")
                .forEach(sandbox::allow);
        return sandbox;
    }

    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();

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
        // [*] 内的 '*' 不是运算符——先以占位符保护通配符，再按乘号分割操作数
        String guarded = inner.replace("[*]", WILDCARD_GUARD);
        String[] operands = guarded.split("\\*");
        if (operands.length > 2) {
            throw new TransferException(
                    "unsupported aggregate expression (at most two operands): " + inner);
        }
        LinkedHashMap<List<Integer>, BigDecimal> left =
                indexedValues(unguard(operands[0]), context, inner);
        if (operands.length == 1) {
            return aggregateValues(functionName, List.copyOf(left.values()), inner);
        }
        LinkedHashMap<List<Integer>, BigDecimal> right =
                indexedValues(unguard(operands[1]), context, inner);
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

    private static String unguard(String operand) {
        return operand.replace(WILDCARD_GUARD, "[*]").trim();
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
