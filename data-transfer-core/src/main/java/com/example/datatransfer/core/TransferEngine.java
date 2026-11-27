package com.example.datatransfer.core;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.datatransfer.core.exception.ExpressionException;
import com.example.datatransfer.core.exception.RuleMatchException;
import com.example.datatransfer.core.exception.TransformException;
import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.exception.TransferException;
import com.example.datatransfer.core.exception.ValidationException;
import com.example.datatransfer.core.exception.ValidationFailure;
import com.example.datatransfer.core.expression.ExpressionEvaluator;
import com.example.datatransfer.core.expression.JexlExpressionEvaluator;
import com.example.datatransfer.core.flatten.FlatMapProcessor;
import com.example.datatransfer.core.flatten.PathParser;
import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.MissingPolicy;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.transform.FuncRegistry;
import com.example.datatransfer.core.transform.TransformFunction;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;

/**
 * 规则执行引擎（设计文档 §8.6）：Flatten → intermediate（源上下文求值，{@code $} 暂存区，§6.8）
 * → 规则映射（通配符对位 / 变换链 / 条件映射；上下文 = 源 + 暂存）→ computed（目标 + 暂存
 * 合并视图上求值，容器结果展开为扁平键）→ defaults 注入 → validations → Unflatten。
 *
 * <p>引擎在构造期把 spec 深拷贝为不可变执行快照 {@link CompiledPlan} 并完成全部装配期校验；
 * 实例不持有可变共享状态，线程安全。{@code sources} / {@code rewrites} 第一版未实现执行语义，
 * 构造期 fail-fast（避免配置被静默忽略）。</p>
 *
 * <p><b>null 语义</b>（{@code nullPolicy}）：</p>
 * <ul>
 *   <li>{@code skip}：源值为 null 时跳过，不产出目标键（变换链不执行）；</li>
 *   <li>{@code keep}：null 是合法值，原样写入；defaults 段不覆盖已存在的键；</li>
 *   <li>{@code default}：null 进入变换链（{@code default()} 可兜底），链后仍为 null 则不写入，
 *       交给 defaults 段。</li>
 * </ul>
 */
@Slf4j
public class TransferEngine {

    private static final Pattern FUNCTION_CALL = Pattern.compile("^(\\w+)\\s*\\((.*)\\)$");

    /** null 短路语义下的兜底函数名（唯一在 null 输入时仍执行的内置函数） */
    private static final String DEFAULT_FUNCTION = "default";

    /** transform 实参的暂存路径形态（未加引号；引号参数为字面量不代入） */
    private final CompiledPlan plan;
    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final FuncRegistry funcRegistry;
    private final ExpressionEvaluator exprEvaluator;
    private final ValidationRunner validationRunner;

    public TransferEngine(TransferSpec spec) {
        this(spec, Map.of(), new JexlExpressionEvaluator(), Clock.systemUTC());
    }

    public TransferEngine(TransferSpec spec, Map<String, TransformFunction> extraFunctions) {
        this(spec, extraFunctions, new JexlExpressionEvaluator(), Clock.systemUTC());
    }

    public TransferEngine(TransferSpec spec,
                          Map<String, TransformFunction> extraFunctions,
                          ExpressionEvaluator exprEvaluator) {
        this(spec, extraFunctions, exprEvaluator, Clock.systemUTC());
    }

    /**
     * 全参构造器：注入 {@link Clock} 供 {@code now} 变换函数使用
     * （默认 {@link Clock#systemUTC()} 行为不变；固定 Clock 用于确定性测试）。
     * extraFunctions 注册于内置 now 之后，可覆盖任意内置函数（含 now）。
     *
     * @throws TransferAssemblyException spec 非法：未支持特性、通配数量不一致、暂存区引用非法、
     *         目标键结构冲突、表达式语法错误、断言语法错误等（装配期 fail-fast）
     */
    public TransferEngine(TransferSpec spec,
                          Map<String, TransformFunction> extraFunctions,
                          ExpressionEvaluator exprEvaluator,
                          Clock clock) {
        this.exprEvaluator = Objects.requireNonNull(exprEvaluator, "exprEvaluator must not be null");
        this.funcRegistry = new FuncRegistry(clock);
        extraFunctions.forEach(funcRegistry::register);
        this.plan = CompiledPlan.compile(Objects.requireNonNull(spec, "spec must not be null"));
        ValidationRunner.validate(plan);
        validateExpressions(plan);
        this.validationRunner = new ValidationRunner(plan, exprEvaluator);
    }

    /** JSON 字符串输入（readTree 复用 flatProcessor 的 BigDecimal mapper，精度语义贯穿） */
    public JsonNode transfer(String sourceJson) {
        return transferNode(flatProcessor.mapper().readTree(sourceJson));
    }

    /** JsonNode 输入（供 usecase 集成侧零序列化往返） */
    public JsonNode transferNode(JsonNode source) {
        String separator = plan.options().separator();

        // Phase 1: Flatten
        Map<String, Object> flatSource = flatProcessor.flatten(source, separator);
        rejectReservedSourceKeys(flatSource);
        TargetWriter target = new TargetWriter(plan.options().strictMode());

        // Phase 1.5: intermediate —— 源上下文顺序求值，$ 命名空间不进输出（设计文档 §6.8）
        Map<String, Object> flatStaging = executeIntermediate(flatSource);
        Map<String, Object> mappingContext = withStaging(flatSource, flatStaging);

        // Phase 2: 规则映射
        mapRules(mappingContext, flatStaging, target);
        // Phase 3: computed
        runComputed(flatStaging, target);
        // Phase 4: defaults 注入
        applyDefaults(target);

        // Phase 4.5: validations —— 对完整目标数据（rules+computed+defaults 产物）校验，
        // 失败即抛（不进入 Unflatten，不产脏数据——评审 6.4）
        List<ValidationFailure> failures = validationRunner.execute(target.values());
        if (!failures.isEmpty()) {
            throw new ValidationException(plan.name(), failures);
        }

        // Phase 5: Unflatten
        return flatProcessor.unflatten(target.values(), separator);
    }

    /** strictMode：首 transfer 前对保留字符转义记法（如 matrix["agent.smith"]）fail-fast——此类键不属于路径语法、不可被规则映射（设计文档 §5.3-2） */
    private void rejectReservedSourceKeys(Map<String, Object> flatSource) {
        if (!plan.options().strictMode()) {
            return;
        }
        flatSource.keySet().stream()
                .filter(key -> key.contains("[\""))
                .findFirst()
                .ifPresent(key -> {
                    throw new RuleMatchException(
                            "strictMode: source key uses reserved-character escape notation "
                                    + "(not mappable): " + key + " (spec: " + plan.name() + ")");
                });
    }

    /** Phase 2：规则映射（含 when 条件的 spec 预构建一次合并嵌套视图，供逐元素条件求值复用） */
    private void mapRules(Map<String, Object> mappingContext, Map<String, Object> flatStaging,
                          TargetWriter target) {
        Map<String, Object> sourceView = plan.hasConditionalRules()
                ? flatProcessor.unflattenToMap(mappingContext, plan.options().separator())
                : null;
        for (CompiledPlan.Rule rule : plan.rules()) {
            List<Map.Entry<String, Object>> matches =
                    flatProcessor.expandWildcard(rule.from(), mappingContext);
            if (matches.isEmpty()) {
                applyMissingPolicy(rule.from(), rule.to());
            }
            for (Map.Entry<String, Object> match : matches) {
                mapMatch(rule, match, mappingContext, sourceView, target);
            }
        }
    }

    /** Phase 3：computed —— 目标 + 暂存合并视图上求值（设计文档 §6.2 / §6.8），容器结果展开为扁平键 */
    private void runComputed(Map<String, Object> flatStaging, TargetWriter target) {
        String separator = plan.options().separator();
        for (CompiledPlan.Computed computed : plan.computed()) {
            Object value = evaluate(computed.expr(), computed.describe(),
                    withStaging(target.values(), flatStaging), null);
            expanded(computed.to(), value, separator)
                    .forEach((key, sub) -> target.put(key, sub, computed.describe()));
        }
    }

    /** Phase 4：defaults 注入（仅当目标键不存在时——keep 策略写入的 null 不被覆盖） */
    private void applyDefaults(TargetWriter target) {
        for (CompiledPlan.Default defaultValue : plan.defaults()) {
            target.putIfAbsent(defaultValue.to(), defaultValue.value());
        }
    }

    /** 单个源键匹配：按 nullPolicy 决定是否进入变换链、是否写入 */
    private void mapMatch(CompiledPlan.Rule rule, Map.Entry<String, Object> match,
                          Map<String, Object> context, @Nullable Map<String, Object> sourceView,
                          TargetWriter target) {
        NullPolicy nullPolicy = plan.options().nullPolicy();
        if (match.getValue() == null && nullPolicy == NullPolicy.SKIP) {
            return;
        }
        Object value = applyTransforms(rule, match.getValue(), context, sourceView);
        if (value == null && nullPolicy == NullPolicy.DEFAULT) {
            return;   // 链后仍为 null：不写入，交给 defaults 段兜底
        }
        target.put(rule.wildcard() ? align(rule, match.getKey()) : rule.normalizedTo(),
                value, rule.describe());
    }

    /**
     * 多级通配按出现顺序逐段对位（设计文档 §6.1）：to 中第 k 个 [*] ← from 中第 k 个 [*]
     * 在实际键上的索引。[*] 在两侧的段位置可以不同（{@code a[*].b → x.y[*]}）。
     */
    private String align(CompiledPlan.Rule rule, String sourceKey) {
        List<Object> actualKey = PathParser.parse(sourceKey, plan.options().separator());
        List<Object> target = new ArrayList<>(rule.toPattern());
        for (int k = 0; k < rule.toWildcardPositions().size(); k++) {
            target.set(rule.toWildcardPositions().get(k), actualKey.get(rule.fromWildcardPositions().get(k)));
        }
        return PathParser.toPath(target, plan.options().separator());
    }

    /**
     * 目标 FlatMap 的写入入口：strictMode 下同一目标键被重复写入即 fail-fast
     * （外部评审二轮 评审二-10：历史行为是后规则静默覆盖前规则；装配期能判定的重复已在 CompiledPlan 拒绝，
     * 这里兜住通配展开后才重叠的目标）。
     */
    private static final class TargetWriter {

        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, String> owners = new HashMap<>();
        private final boolean strict;

        private TargetWriter(boolean strict) {
            this.strict = strict;
        }

        private void put(String key, @Nullable Object value, String owner) {
            if (strict && values.containsKey(key)) {
                throw new RuleMatchException("strictMode: target key '" + key + "' is written twice: '"
                        + owners.get(key) + "' and '" + owner + "'");
            }
            values.put(key, value);
            if (strict) {
                owners.put(key, owner);
            }
        }

        private void putIfAbsent(String key, @Nullable Object value) {
            if (!values.containsKey(key)) {
                values.put(key, value);
            }
        }

        private Map<String, Object> values() {
            return values;
        }
    }

    /** 变换入口：when 条件映射优先（与 transform 互斥），否则链式 transform；context 为源 + 暂存合并视图 */
    private Object applyTransforms(CompiledPlan.Rule rule, Object value, Map<String, Object> context,
                                   @Nullable Map<String, Object> sourceView) {
        if (!rule.when().isEmpty()) {
            return applyConditions(rule, rule.when(), value, context, sourceView);
        }
        return applyChain(rule.from(), rule.to(), rule.index(), rule.transform(), value, context);
    }

    /** 条件映射（设计文档 §6.3）：condition 可引用合并视图顶层键（含 $ 暂存）；落空走 otherwise，无 otherwise 保留原值 */
    private Object applyConditions(CompiledPlan.Rule rule, List<CompiledPlan.Branch> when, Object value,
                                   Map<String, Object> context, @Nullable Map<String, Object> sourceView) {
        for (CompiledPlan.Branch branch : when) {
            if (branch.condition() != null) {
                boolean matched;
                try {
                    matched = Boolean.TRUE.equals(
                            exprEvaluator.evaluate(branch.condition(), context, sourceView));
                } catch (RuntimeException e) {
                    throw new TransformException("when condition evaluation failed: " + e.getMessage(),
                            rule.index(), rule.from(), rule.to(), branch.condition(), value, e);
                }
                if (matched) {
                    return applyChain(rule.from(), rule.to(), rule.index(), branch.transform(), value, context);
                }
            }
        }
        return when.stream()
                .filter(branch -> branch.otherwise() != null)
                .findFirst()
                .map(branch -> applyChain(rule.from(), rule.to(), rule.index(), branch.otherwise(), value, context))
                .orElse(value);
    }

    /**
     * 变换链 {@code fn | fn(args) | ...} 逐段应用（实参剥离单/双引号，第一版不支持嵌套逗号）。
     *
     * <p><b>null 短路</b>（评审 2.1）：中间结果为 null 时跳过后续变换——仅 {@code default()}
     * 兜底函数例外（default(null) 返回缺省值后，链继续按非 null 执行）；
     * 最终仍为 null 时按 {@code nullPolicy} 处置。</p>
     *
     * <p><b>暂存引用实参</b>（§6.8）：未加引号的 {@code $path} 形态实参从 context 取值代入
     * （如 {@code multiply($discountRate)}）——引用键缺失即抛（构造期已校验声明存在，
     * 运行期缺失说明 intermediate 未产出或被 nullPolicy 丢弃）。</p>
     *
     * <p>未知函数与函数执行失败包装为 {@link TransformException}（携带规则索引、路径、
     * 函数名与当前值——评审 4.2 错误上下文要求）。</p>
     */
    private Object applyChain(String from, String to, int ruleIndex, @Nullable String chain, Object value,
                              Map<String, Object> context) {
        if (chain == null || chain.isBlank()) {
            return value;
        }
        Object current = value;
        for (String step : chain.split("\\|")) {
            String trimmed = step.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher call = FUNCTION_CALL.matcher(trimmed);
            String functionName = call.matches() ? call.group(1) : trimmed;
            if (current == null && !DEFAULT_FUNCTION.equals(functionName)) {
                continue;   // null 短路：普通函数跳过，default 仍执行以兜底
            }
            TransformFunction function;
            try {
                function = funcRegistry.get(functionName);
            } catch (IllegalArgumentException e) {
                throw new TransformException(e.getMessage(), ruleIndex,
                        from, to, trimmed, current, e);
            }
            List<String> args = call.matches()
                    ? resolveStagedArgs(ArgumentParser.split(call.group(2)), context, from, to)
                    : List.of();
            try {
                current = function.apply(current, args, context);
            } catch (RuntimeException e) {
                throw new TransformException("transform function '" + functionName + "' failed: "
                        + e.getMessage(), ruleIndex, from, to, trimmed, current, e);
            }
        }
        return current;
    }

    /**
     * transform 实参解析：带引号的实参是字面量（剥离引号后原样使用，包括形如 {@code '$rate'} 的文本）；
     * 未加引号的 {@code $path} 形态实参从 context 取值代入（如 {@code multiply($discountRate)}）——
     * 引用键缺失即抛（构造期已校验声明存在，运行期缺失说明 intermediate 未产出或被 nullPolicy 丢弃）。
     */
    private static List<String> resolveStagedArgs(List<String> rawArgs, Map<String, Object> context,
                                                  String from, String to) {
        List<String> args = new ArrayList<>(rawArgs.size());
        for (String rawArg : rawArgs) {
            args.add(STAGED_ARG.matcher(rawArg).matches()
                    ? stagedValue(rawArg, context, from, to)
                    : ArgumentParser.unquote(rawArg));
        }
        return args;
    }

    private static String stagedValue(String stagedPath, Map<String, Object> context, String from, String to) {
        if (!context.containsKey(stagedPath)) {
            throw new TransformException("transform argument '" + stagedPath
                    + "' is not present in the mapping context (intermediate not produced or null-dropped)",
                    -1, from, to, stagedPath, stagedPath, null);
        }
        return String.valueOf(context.get(stagedPath));
    }

    private static final Pattern STAGED_ARG =
            Pattern.compile("^\\$[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*)*$");

    private void applyMissingPolicy(String from, String to) {
        MissingPolicy policy = plan.options().missingPolicy();
        switch (policy) {
            case ERROR -> throw new RuleMatchException(
                    "rule [" + from + " -> " + to
                            + "] matched no source key: " + from
                            + " (spec: " + plan.name() + ")");
            case WARN -> log.warn("rule [{} -> {}] matched no source key (spec: {})",
                    from, to, plan.name());
            case IGNORE -> { /* 静默 */ }
        }
    }

    // =====================================================================
    // intermediate（设计文档 §6.8）：暂存区 —— Phase 1.5 执行
    // =====================================================================

    /**
     * Phase 1.5：按声明序求值各中间结果。上下文为源 + 已产出暂存的合并视图；
     * from 形态沿用 nullPolicy / missingPolicy，容器结果展开为 to 前缀的扁平键集。
     */
    private Map<String, Object> executeIntermediate(Map<String, Object> flatSource) {
        List<CompiledPlan.Staged> staged = plan.staged();
        if (staged.isEmpty()) {
            return Map.of();
        }
        rejectDollarPrefixedSourceKeys(flatSource);
        String separator = plan.options().separator();
        Map<String, Object> flatStaging = new LinkedHashMap<>();
        for (CompiledPlan.Staged field : staged) {
            Map<String, Object> context = withStaging(flatSource, flatStaging);
            if (field.expr() != null) {
                expanded(field.to(), evaluate(field.expr(), field.describe(), context, null), separator)
                        .forEach(flatStaging::put);
                continue;
            }
            stagedFromSource(field, context, separator).forEach(flatStaging::put);
        }
        return flatStaging;
    }

    /** 源键以 $ 开头会与暂存命名空间在合并视图中无法区分——运行期 fail-fast */
    private void rejectDollarPrefixedSourceKeys(Map<String, Object> flatSource) {
        flatSource.keySet().stream().filter(key -> key.startsWith("$")).findFirst()
                .ifPresent(key -> {
                    throw new RuleMatchException("source key '" + key
                            + "' conflicts with the '$'-prefixed intermediate namespace (spec: "
                            + plan.name() + ")");
                });
    }

    /**
     * from 形态的暂存值：键缺失按 missingPolicy 处置后不产出；null 沿用规则映射的三态策略
     * （skip 不产出 / default 链后仍为 null 不产出 / keep 原样）。返回空 Map 表示本次不产出。
     */
    private Map<String, Object> stagedFromSource(CompiledPlan.Staged field, Map<String, Object> context,
                                                 String separator) {
        if (!context.containsKey(field.from())) {
            applyMissingPolicy(field.from(), field.to());
            return Map.of();
        }
        NullPolicy nullPolicy = plan.options().nullPolicy();
        Object value = context.get(field.from());
        if (value == null && nullPolicy == NullPolicy.SKIP) {
            return Map.of();   // 对齐规则映射的 null 语义：不产出，后续引用以运行期缺失暴露
        }
        value = applyChain(field.from(), field.to(), -1, field.transform(), value, context);
        if (value == null && nullPolicy == NullPolicy.DEFAULT) {
            return Map.of();
        }
        return expanded(field.to(), value, separator);
    }

    /**
     * 容器值展开为 {@code to.} 前缀的扁平键集（支持 rules 的 [*] 通配导航与 validations 寻址内部字段）；
     * 标量与空容器直接落单键。
     */
    private Map<String, Object> expanded(String to, @Nullable Object value, String separator) {
        Map<String, Object> expanded = new LinkedHashMap<>();
        if (!container(value) || emptyContainer(value)) {
            // 标量与空容器直接落单键（根级空容器经 flatten 会被库的 root 键包裹，不能展开）
            expanded.put(to, value);
            return expanded;
        }
        Map<String, Object> flat = flatProcessor.flatten(flatProcessor.mapper().valueToTree(value), separator);
        // 数组的扁平键以索引段开头（[0].f）：紧跟 to 拼接，不加分隔符
        flat.forEach((key, sub) -> expanded.put(key.startsWith("[") ? to + key : to + separator + key, sub));
        return expanded;
    }

    private static boolean container(@Nullable Object value) {
        return value instanceof Map || value instanceof List
                || (value != null && value.getClass().isArray());
    }

    private static boolean emptyContainer(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return value != null && value.getClass().isArray() && Array.getLength(value) == 0;
    }

    /** 暂存合并视图：无暂存时零拷贝返回 base（既有 spec 行为不变） */
    private static Map<String, Object> withStaging(Map<String, Object> base, Map<String, Object> flatStaging) {
        if (flatStaging.isEmpty()) {
            return base;
        }
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(flatStaging);
        return merged;
    }

    // =====================================================================
    // 表达式：装配期校验与运行期包装
    // =====================================================================

    private void validateExpressions(CompiledPlan plan) {
        for (CompiledPlan.Rule rule : plan.rules()) {
            for (CompiledPlan.Branch branch : rule.when()) {
                if (branch.condition() != null) {
                    validateExpression(branch.condition(), rule.describe());
                }
            }
        }
        for (CompiledPlan.Staged field : plan.staged()) {
            if (field.expr() != null) {
                validateExpression(field.expr(), field.describe());
            }
        }
        for (CompiledPlan.Computed field : plan.computed()) {
            validateExpression(field.expr(), field.describe());
        }
        for (CompiledPlan.Validation validation : plan.validations()) {
            if (validation.condition() != null) {
                validateExpression(validation.condition(), validation.describe());
            }
        }
    }

    private void validateExpression(String expr, String owner) {
        try {
            exprEvaluator.validate(expr, owner);
        } catch (TransferAssemblyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TransferAssemblyException("invalid expression at " + owner + " (expr: " + expr + "): "
                    + e.getMessage(), e);
        }
    }

    /** 表达式求值：JEXL 等运行期异常包装为 {@link ExpressionException}（携带表达式与所属声明） */
    private Object evaluate(String expr, String owner, Map<String, Object> context,
                            @Nullable Map<String, Object> nestedView) {
        try {
            return exprEvaluator.evaluate(expr, context, nestedView);
        } catch (TransferException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExpressionException(expr, owner, e);
        }
    }

}
