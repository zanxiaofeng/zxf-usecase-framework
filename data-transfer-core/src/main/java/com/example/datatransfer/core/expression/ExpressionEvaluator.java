package com.example.datatransfer.core.expression;

import java.util.Map;

/** 表达式求值接口（设计文档 §8.8）：默认 JEXL 实现，可替换为 Spring EL、Aviator 等。 */
public interface ExpressionEvaluator {

    /**
     * 对 computed 表达式求值（设计文档 §6.2）。
     *
     * @param expr    表达式；聚合形态如 {@code sum(a.b[*].x * a.b[*].y)}，其余形态按常规表达式求值
     * @param context 目标 FlatMap（表达式引用目标路径）
     * @return 求值结果（写入 computed.to）
     */
    Object evaluate(String expr, Map<String, Object> context);

    /**
     * 带预构建嵌套视图的求值（review P1 性能项）：调用方对同一文档复用一次
     * {@code FlatMapProcessor.unflattenToMap} 的产物，避免逐表达式重拍还原
     * （when 条件 × 通配元素的高频路径）。默认实现忽略视图、退化为两参版。
     *
     * @param nestedView context 的嵌套视图（与 context 内容一致；实现方不修改）
     */
    default Object evaluate(String expr, Map<String, Object> context, Map<String, Object> nestedView) {
        return evaluate(expr, context);
    }
}
