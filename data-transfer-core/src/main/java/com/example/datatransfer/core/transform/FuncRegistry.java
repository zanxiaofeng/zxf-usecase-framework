package com.example.datatransfer.core.transform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变换函数注册中心（设计文档 §8.5）：不可变内置基表 + 实例级扩展注册
 * （避免全局可变静态）；算术统一走 BigDecimal（金额精度语义，设计文档 §5.3-4）。
 */
public final class FuncRegistry {

    private static final Map<String, TransformFunction> BUILTINS = Map.of(
            "trim", (v, args, ctx) -> v == null ? null : String.valueOf(v).trim(),
            "lower", (v, args, ctx) -> v == null ? null : String.valueOf(v).toLowerCase(),
            "upper", (v, args, ctx) -> v == null ? null : String.valueOf(v).toUpperCase(),
            "replace", (v, args, ctx) -> v == null ? null
                    : String.valueOf(v).replace(args.get(0), args.get(1)),
            "multiply", (v, args, ctx) -> toDecimal(v).multiply(toDecimal(args.get(0))),
            "round", (v, args, ctx) -> toDecimal(v)
                    .setScale(Integer.parseInt(args.get(0)), RoundingMode.HALF_UP),
            "default", (v, args, ctx) -> v == null ? args.get(0) : v);

    private final Map<String, TransformFunction> functions = new ConcurrentHashMap<>(BUILTINS);

    public void register(String name, TransformFunction function) {
        functions.put(name, function);
    }

    public TransformFunction get(String name) {
        TransformFunction function = functions.get(name);
        if (function == null) {
            throw new IllegalArgumentException("unknown transform function: " + name);
        }
        return function;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
