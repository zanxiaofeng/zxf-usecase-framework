package com.example.datatransfer.core.expression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;

import com.example.datatransfer.core.flatten.FlatMapProcessor;

/**
 * 默认表达式求值器（设计文档 §6.2 / §8.6 注）：JEXL 底座。
 *
 * <p>JEXL 无法直接导航含 {@code [*]} 的整串键，本实现分两路：</p>
 * <ul>
 *   <li>聚合形态 {@code agg(PATH)} / {@code agg(PATH * PATH)}（agg ∈ sum/avg/count/min/max，
 *       PATH 为含 {@code [*]} 的通配路径）：由本类展开值列表按 BigDecimal 计算——
 *       双路径按索引对位逐元素相乘后聚合（要求同长度）；</li>
 *   <li>其余形态：以目标 FlatMap 的嵌套视图绑定顶层键后交 JEXL 常规求值
 *       （索引路径如 {@code a.b[0].c} 由 JEXL 的 list 导航支持）。</li>
 * </ul>
 */
public final class JexlExpressionEvaluator implements ExpressionEvaluator {

    private static final Pattern AGGREGATE = Pattern.compile("^(sum|avg|count|min|max)\\((.+)\\)$");

    /** 聚合操作数分割时保护 [*] 内 '*' 的占位符 */
    private static final String WILDCARD_GUARD = "__WC__";

    private final JexlEngine engine = new JexlBuilder().create();
    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();

    @Override
    public Object evaluate(String expr, Map<String, Object> context) {
        Matcher aggregate = AGGREGATE.matcher(expr.trim());
        if (aggregate.matches()) {
            return aggregate(aggregate.group(1), aggregate.group(2), context);
        }
        return evaluatePlain(expr, context);
    }

    private Object aggregate(String functionName, String inner, Map<String, Object> context) {
        // [*] 内的 '*' 不是运算符——先以占位符保护通配符，再按乘号分割操作数
        String guarded = inner.replace("[*]", WILDCARD_GUARD);
        String[] operands = guarded.split("\\*");
        if (operands.length > 2) {
            throw new IllegalArgumentException(
                    "unsupported aggregate expression (at most two operands): " + inner);
        }
        List<BigDecimal> left = wildcardValues(operands[0].replace(WILDCARD_GUARD, "[*]").trim(), context);
        List<BigDecimal> values = left;
        if (operands.length == 2) {
            List<BigDecimal> right =
                    wildcardValues(operands[1].replace(WILDCARD_GUARD, "[*]").trim(), context);
            if (left.size() != right.size()) {
                throw new IllegalArgumentException("wildcard operand length mismatch in aggregate: " + inner);
            }
            values = new ArrayList<>(left.size());
            for (int i = 0; i < left.size(); i++) {
                values.add(left.get(i).multiply(right.get(i)));
            }
        }
        return switch (functionName) {
            case "sum" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> values.isEmpty()
                    ? BigDecimal.ZERO
                    : sum(values).divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
            case "count" -> BigDecimal.valueOf(values.size());
            case "min" -> values.stream().reduce(BigDecimal::min)
                    .orElseThrow(() -> new IllegalArgumentException("aggregate on empty wildcard: " + inner));
            case "max" -> values.stream().reduce(BigDecimal::max)
                    .orElseThrow(() -> new IllegalArgumentException("aggregate on empty wildcard: " + inner));
            default -> throw new IllegalArgumentException("unknown aggregate function: " + functionName);
        };
    }

    /** 通配路径 → 值列表（按 FlatMap 文档序，即索引升序） */
    private List<BigDecimal> wildcardValues(String path, Map<String, Object> context) {
        if (!path.contains("[*]")) {
            throw new IllegalArgumentException(
                    "aggregate operand must be a wildcard path containing [*]: " + path);
        }
        return flatProcessor.expandWildcard(path, context).stream()
                .map(e -> toDecimal(e.getValue()))
                .toList();
    }

    /** 常规表达式：目标 FlatMap 的嵌套视图绑定顶层键 */
    private Object evaluatePlain(String expr, Map<String, Object> context) {
        MapContext jexlContext = new MapContext();
        flatProcessor.unflattenToMap(context, ".")
                .forEach(jexlContext::set);
        return engine.createExpression(expr).evaluate(jexlContext);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("aggregate operand contains null value");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
