package com.example.datatransfer.core.transform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变换函数注册中心（设计文档 §8.5）：不可变内置基表 + 实例级扩展注册
 * （避免全局可变静态）；算术统一走 BigDecimal（金额精度语义，设计文档 §5.3-4）。
 *
 * <p>日期时间函数族（dateFormat/epochToIso/toIsoDate/toIsoDateTime，设计文档 §3.2
 * 「日期」行）在静态基表；{@code now} 依赖 {@link Clock}，由实例构造器注册（默认
 * {@link Clock#systemUTC()} 行为不变，可注入固定 Clock 实现确定性测试）。
 * 实例注册发生在 now 之后——经 {@link #register(String, TransformFunction)} 传入的
 * 同名函数覆盖任意内置函数。</p>
 */
public final class FuncRegistry {

    private static final Map<String, TransformFunction> BUILTINS = Map.ofEntries(
            Map.entry("trim", (v, args, ctx) -> v == null ? null : String.valueOf(v).trim()),
            Map.entry("lower", (v, args, ctx) -> v == null ? null : String.valueOf(v).toLowerCase()),
            Map.entry("upper", (v, args, ctx) -> v == null ? null : String.valueOf(v).toUpperCase()),
            Map.entry("replace", (v, args, ctx) -> v == null ? null
                    : String.valueOf(v).replace(args.get(0), args.get(1))),
            Map.entry("multiply", (v, args, ctx) -> toDecimal(v).multiply(toDecimal(args.get(0)))),
            Map.entry("round", (v, args, ctx) -> toDecimal(v)
                    .setScale(Integer.parseInt(args.get(0)), RoundingMode.HALF_UP)),
            Map.entry("default", (v, args, ctx) -> v == null ? args.get(0) : v),
            Map.entry("dateFormat", (v, args, ctx) -> DateTimeFunctions.dateFormat(v, args)),
            Map.entry("epochToIso", (v, args, ctx) -> DateTimeFunctions.epochToIso(v, args)),
            Map.entry("toIsoDate", (v, args, ctx) -> DateTimeFunctions.toIsoDate(v, args)),
            Map.entry("toIsoDateTime", (v, args, ctx) -> DateTimeFunctions.toIsoDateTime(v, args)));

    private final Map<String, TransformFunction> functions = new ConcurrentHashMap<>(BUILTINS);

    public FuncRegistry() {
        this(Clock.systemUTC());
    }

    /** 注入 Clock（{@code now} 的确定性测试用；生产默认 systemUTC 行为不变） */
    public FuncRegistry(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        functions.put("now", (v, args, ctx) -> DateTimeFunctions.now(v, args, clock));
    }

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
