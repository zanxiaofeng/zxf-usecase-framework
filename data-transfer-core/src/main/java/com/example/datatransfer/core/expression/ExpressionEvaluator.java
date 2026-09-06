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
}
