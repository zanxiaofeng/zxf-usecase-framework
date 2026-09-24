package com.example.datatransfer.core;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.datatransfer.core.exception.RuleMatchException;
import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.exception.TransformException;
import com.example.datatransfer.core.exception.ValidationException;
import com.example.datatransfer.core.exception.ValidationFailure;
import com.example.datatransfer.core.expression.ExpressionEvaluator;
import com.example.datatransfer.core.expression.JexlExpressionEvaluator;
import com.example.datatransfer.core.flatten.FlatMapProcessor;
import com.example.datatransfer.core.flatten.PathParser;
import com.example.datatransfer.core.spec.Assertion;
import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.ConditionMapping;
import com.example.datatransfer.core.spec.IntermediateField;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.MissingPolicy;
import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.spec.ValidationMode;
import com.example.datatransfer.core.spec.ValidationRule;
import com.example.datatransfer.core.transform.FuncRegistry;
import com.example.datatransfer.core.transform.TransformFunction;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;

/**
 * 规则执行引擎（设计文档 §8.6）：Flatten → intermediate（源上下文求值，{@code $} 暂存区，§6.8）
 * → 规则映射（通配符对位 / 变换链 / 条件映射；上下文 = 源 + 暂存）→ computed（目标 + 暂存
 * 合并视图上求值）→ defaults 注入 → Unflatten。
 *
 * <p>引擎实例不持有可变共享状态，线程安全；{@code sources} / {@code rewrites}
 * 第一版未实现执行语义，构造期 fail-fast（避免配置被静默忽略）。</p>
 */
@Slf4j
public class TransferEngine {

    private static final Pattern FUNCTION_CALL = Pattern.compile("^(\\w+)\\s*\\((.*)\\)$");

    /** expr/transform 参数中的暂存根引用（{@code $a.b} 提取 {@code $a}）；字符串字面量中的 $ident 会误报，构造期即拒绝 */
    private static final Pattern STAGED_ROOT = Pattern.compile("\\$([a-zA-Z_][a-zA-Z0-9_]*)");

    /** transform 实参的暂存路径形态（未加引号；引号参数为字面量不代入） */
    private static final Pattern STAGED_ARG =
            Pattern.compile("^\\$[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*)*$");

    /** 暂存声明 to 的合法形态：{@code $} 根 + 纯点路径（不含通配/索引——单键语义） */
    private static final Pattern STAGED_TARGET =
            Pattern.compile("^\\$[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*)*$");


    /** null 短路语义下的兜底函数名（唯一在 null 输入时仍执行的内置函数） */
    private static final String DEFAULT_FUNCTION = "default";

    private final TransferSpec spec;
    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final FuncRegistry funcRegistry;
    private final ExpressionEvaluator exprEvaluator;
    /** spec 是否含 when 条件分支（决定 transfer 期是否预构建源嵌套视图，review P1 性能项） */
    private final boolean hasConditionalRules;

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
     */
    public TransferEngine(TransferSpec spec,
                          Map<String, TransformFunction> extraFunctions,
                          ExpressionEvaluator exprEvaluator,
                          Clock clock) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        this.exprEvaluator = Objects.requireNonNull(exprEvaluator, "exprEvaluator must not be null");
        this.funcRegistry = new FuncRegistry(clock);
        extraFunctions.forEach(funcRegistry::register);
        validateUnsupportedFeatures(spec);
        validateRules(spec);
        validateIntermediate(spec);
        validateValidations(spec);
        this.hasConditionalRules = spec.getRules().stream()
                .anyMatch(rule -> rule.getWhen() != null && !rule.getWhen().isEmpty());
    }

    /** JSON 字符串输入（readTree 复用 flatProcessor 的 BigDecimal mapper，精度语义贯穿） */
    public JsonNode transfer(String sourceJson) {
        return transferNode(flatProcessor.mapper().readTree(sourceJson));
    }

    /** JsonNode 输入（供 usecase 集成侧零序列化往返） */
    public JsonNode transferNode(JsonNode source) {
        String separator = spec.optionsOrNew().getSeparator();
        NullPolicy nullPolicy = spec.optionsOrNew().getNullPolicy();

        // Phase 1: Flatten
        Map<String, Object> flatSource = flatProcessor.flatten(source, separator);
        if (spec.optionsOrNew().isStrictMode()) {
            // strictMode：首 transfer 前对保留字符转义记法（如 matrix["agent.smith"]）fail-fast
            // ——此类键不属于路径语法、不可被规则映射（设计文档 §5.3-2）
            flatSource.keySet().stream()
                    .filter(key -> key.contains("[\""))
                    .findFirst()
                    .ifPresent(key -> {
                        throw new RuleMatchException(
                                "strictMode: source key uses reserved-character escape notation "
                                        + "(not mappable): " + key + " (spec: " + spec.getName() + ")");
                    });
        }
        Map<String, Object> flatTarget = new LinkedHashMap<>();

        // Phase 1.5: intermediate —— 源上下文顺序求值，$ 命名空间不进输出（设计文档 §6.8）
        Map<String, Object> flatStaging = executeIntermediate(flatSource, separator);
        Map<String, Object> mappingContext = withStaging(flatSource, flatStaging);

        // Phase 2: 规则映射（含 when 条件的 spec 预构建一次合并嵌套视图，供逐元素条件求值复用）
        Map<String, Object> sourceView =
                hasConditionalRules ? flatProcessor.unflattenToMap(mappingContext, separator) : null;
        List<MappingRule> rules = spec.getRules();
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            MappingRule rule = rules.get(ruleIndex);
            List<Map.Entry<String, Object>> matches =
                    flatProcessor.expandWildcard(rule.getFrom(), mappingContext);
            if (matches.isEmpty()) {
                applyMissingPolicy(rule.getFrom(), rule.getTo());
            }
            for (Map.Entry<String, Object> match : matches) {
                Object value = match.getValue();
                if (value == null && nullPolicy == NullPolicy.SKIP) {
                    continue;
                }
                // 多级 [*] 按出现顺序逐段对位（设计文档 §6.1）
                String targetPath = alignWildcards(rule.getTo(), match.getKey(), rule.getFrom(), separator);
                value = applyTransforms(rule, ruleIndex, value, mappingContext, sourceView);
                flatTarget.put(targetPath, value);
            }
        }

        // Phase 3: computed —— 目标 + 暂存合并视图上求值（设计文档 §6.2 / §6.8）
        for (ComputedField computed : spec.computedOrEmpty()) {
            Map<String, Object> context = withStaging(flatTarget, flatStaging);
            flatTarget.put(computed.getTo(), exprEvaluator.evaluate(computed.getExpr(), context));
        }

        // Phase 4: defaults 注入（仅当目标键不存在时）
        for (var defaultValue : spec.defaultsOrEmpty()) {
            flatTarget.putIfAbsent(defaultValue.getTo(), defaultValue.getValue());
        }

        // Phase 4.5: validations —— 对完整目标数据（rules+computed+defaults 产物）校验，
        // 失败即抛（不进入 Unflatten，不产脏数据——评审 6.4）
        List<ValidationFailure> failures = executeValidations(flatTarget);
        if (!failures.isEmpty()) {
            throw new ValidationException(spec.getName(), failures);
        }

        // Phase 5: Unflatten
        return flatProcessor.unflatten(flatTarget, separator);
    }

    /** 规则 to 中的第 k 个 [*] 替换为 from 中第 k 个 [*] 在实际键上的索引（多级对位） */
    private String alignWildcards(String to, String sourceKey, String from, String separator) {
        List<Object> fromPattern = PathParser.parsePattern(from, separator);
        List<Object> actualKey = PathParser.parse(sourceKey, separator);
        List<Object> actualIndexes = new ArrayList<>();
        for (int i = 0; i < fromPattern.size(); i++) {
            if (PathParser.WILDCARD.equals(fromPattern.get(i))) {
                actualIndexes.add(actualKey.get(i));
            }
        }
        List<Object> toPattern = PathParser.parsePattern(to, separator);
        int cursor = 0;
        for (int i = 0; i < toPattern.size(); i++) {
            if (PathParser.WILDCARD.equals(toPattern.get(i))) {
                toPattern.set(i, actualIndexes.get(cursor++));
            }
        }
        return PathParser.toPath(toPattern, separator);
    }

    /** 变换入口：when 条件映射优先（与 transform 互斥），否则链式 transform；context 为源 + 暂存合并视图 */
    private Object applyTransforms(MappingRule rule, int ruleIndex, Object value,
                                   Map<String, Object> context, Map<String, Object> sourceView) {
        if (rule.getWhen() != null && !rule.getWhen().isEmpty()) {
            return applyConditions(rule, ruleIndex, rule.getWhen(), value, context, sourceView);
        }
        if (rule.getTransform() == null || rule.getTransform().isBlank()) {
            return value;
        }
        return applyChain(rule.getFrom(), rule.getTo(), ruleIndex, rule.getTransform(), value, context);
    }

    /** 条件映射（设计文档 §6.3）：condition 可引用合并视图顶层键（含 $ 暂存）；落空走 otherwise，无 otherwise 保留原值 */
    private Object applyConditions(MappingRule rule, int ruleIndex, List<ConditionMapping> when,
                                   Object value, Map<String, Object> context,
                                   Map<String, Object> sourceView) {
        for (ConditionMapping branch : when) {
            if (branch.getCondition() != null) {
                boolean matched;
                try {
                    matched = Boolean.TRUE.equals(
                            exprEvaluator.evaluate(branch.getCondition(), context, sourceView));
                } catch (RuntimeException e) {
                    throw new TransformException("when condition evaluation failed: " + e.getMessage(),
                            ruleIndex, rule.getFrom(), rule.getTo(), branch.getCondition(), value, e);
                }
                if (matched) {
                    return applyChain(rule.getFrom(), rule.getTo(), ruleIndex, branch.getTransform(), value, context);
                }
            }
        }
        return when.stream()
                .filter(branch -> branch.getOtherwise() != null)
                .findFirst()
                .map(branch -> applyChain(rule.getFrom(), rule.getTo(), ruleIndex, branch.getOtherwise(), value, context))
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
    private Object applyChain(String from, String to, int ruleIndex, String chain, Object value,
                              Map<String, Object> context) {
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
                    ? resolveStagedArgs(parseArgs(call.group(2)), context, from, to, trimmed)
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

    /** transform 实参的暂存引用代入：{@code $path} 形态（构造期已校验声明存在）取值后以 String.valueOf 参与 */
    private static List<String> resolveStagedArgs(List<String> args, Map<String, Object> context,
                                                  String from, String to, String step) {
        boolean hasStagedRef = args.stream().anyMatch(arg -> STAGED_ARG.matcher(arg).matches());
        if (!hasStagedRef) {
            return args;
        }
        List<String> resolved = new ArrayList<>(args.size());
        for (String arg : args) {
            if (!STAGED_ARG.matcher(arg).matches()) {
                resolved.add(arg);
                continue;
            }
            if (!context.containsKey(arg)) {
                throw new TransformException("transform argument '" + arg
                        + "' is not present in the mapping context (intermediate not produced or null-dropped)",
                        -1, from, to, step, arg, null);
            }
            resolved.add(String.valueOf(context.get(arg)));
        }
        return resolved;
    }

    private static List<String> parseArgs(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String arg : rawArgs.split(",")) {
            String trimmed = arg.trim();
            if (trimmed.length() >= 2
                    && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                            || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            args.add(trimmed);
        }
        return args;
    }

    private void applyMissingPolicy(String from, String to) {
        MissingPolicy policy = spec.optionsOrNew().getMissingPolicy();
        switch (policy) {
            case ERROR -> throw new RuleMatchException(
                    "rule [" + from + " -> " + to
                            + "] matched no source key: " + from
                            + " (spec: " + spec.getName() + ")");
            case WARN -> log.warn("rule [{} -> {}] matched no source key (spec: {})",
                    from, to, spec.getName());
            case IGNORE -> { /* 静默 */ }
        }
    }

    private static void validateUnsupportedFeatures(TransferSpec spec) {
        if (spec.getSources() != null && !spec.getSources().isEmpty()) {
            throw new TransferAssemblyException(
                    "sources (multi-source merge) is not yet supported (spec: " + spec.getName() + ")");
        }
        if (spec.getRewrites() != null && !spec.getRewrites().isEmpty()) {
            throw new TransferAssemblyException(
                    "rewrites (path rewrite) is not yet supported (spec: " + spec.getName() + ")");
        }
        // review P1：表达式侧（computed/when/condition 的 JEXL 求值）当前按 "." 拍还原嵌套视图，
        // 自定义 separator 下会静默失配（校验假阴性）——fail-fast 而非静默，对齐 sources/rewrites 哲学
        if (!".".equals(spec.optionsOrNew().getSeparator())) {
            throw new TransferAssemblyException(
                    "custom separator '" + spec.optionsOrNew().getSeparator()
                            + "' is not yet supported (expression evaluation assumes '.') (spec: "
                            + spec.getName() + ")");
        }
    }

    private static void validateRules(TransferSpec spec) {
        if (spec.getRules() == null || spec.getRules().isEmpty()) {
            throw new TransferAssemblyException("spec must contain at least one rule (spec: " + spec.getName() + ")");
        }
        String separator = spec.optionsOrNew().getSeparator();
        for (int i = 0; i < spec.getRules().size(); i++) {
            MappingRule rule = spec.getRules().get(i);
            validateWildcardCount(rule, i, separator);
        }
        if (spec.optionsOrNew().isStrictMode()) {
            validateDistinctLiteralTargets(spec, separator);
        }
    }

    /**
     * from 与 to 的 {@code [*]} 数量必须一致（Schema 仅约束"有无对齐"，数量不一致时
     * 多级对位会静默错位——评审 1.1，按声明顺序逐段对位的前提）。
     */
    private static void validateWildcardCount(MappingRule rule, int index, String separator) {
        int fromCount = countWildcards(rule.getFrom(), separator);
        int toCount = countWildcards(rule.getTo(), separator);
        if (fromCount != toCount) {
            throw new TransferAssemblyException(
                    ("rule #%d [%s -> %s]: wildcard count mismatch (from has %d [*], to has %d); "
                            + "indexed alignment requires equal counts")
                            .formatted(index, rule.getFrom(), rule.getTo(), fromCount, toCount));
        }
    }

    private static int countWildcards(String path, String separator) {
        return (int) PathParser.parsePattern(path, separator).stream()
                .filter(PathParser.WILDCARD::equals)
                .count();
    }

    /**
     * strictMode：多条规则映射到同一字面目标键（无通配的 to）即报错——静默"后覆盖前"
     * 属数据丢失隐患；通配目标的运行期覆盖语义为"后规则覆盖前规则"（见设计文档附录 D）。
     */
    private static void validateDistinctLiteralTargets(TransferSpec spec, String separator) {
        Map<String, String> literalTargets = new LinkedHashMap<>();
        for (int i = 0; i < spec.getRules().size(); i++) {
            MappingRule rule = spec.getRules().get(i);
            String to = rule.getTo();
            if (to.contains("[*]")) {
                continue;   // 通配目标的数据期覆盖按"后覆盖前"，构造期不可穷举
            }
            PathParser.parsePattern(to, separator);
            String previous = literalTargets.put(to, "rule #" + i + " [" + rule.getFrom() + " -> " + to + "]");
            if (previous != null) {
                throw new TransferAssemblyException(
                        "strictMode: duplicate literal target key '" + to + "' mapped by " + previous
                                + " and rule #" + i + " (spec: " + spec.getName() + ")");
            }
        }
    }

    // =====================================================================
    // intermediate（设计文档 §6.8）：暂存区 —— 构造期校验与 Phase 1.5 执行
    // =====================================================================

    /**
     * 构造期校验（fail-fast）：
     * <ol>
     *   <li>to 形态：{@code $} 前缀 + 纯点路径，根声明唯一（{@code $a} 与 {@code $a.b} 同根冲突）；</li>
     *   <li>形态二选一：from（禁 {@code [*]}，数组派生用 expr）与 expr 互斥；</li>
     *   <li>前向引用：intermediate 的 from/expr、rules 的 from、transform 实参、computed 的 expr
     *       中的 {@code $root} 引用必须已在<b>更早的</b> intermediate 中声明——声明序即 DAG，
     *       未声明/后向引用一律拒绝。</li>
     * </ol>
     */
    private static void validateIntermediate(TransferSpec spec) {
        List<IntermediateField> fields = spec.intermediateOrEmpty();
        Set<String> declaredRoots = new LinkedHashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            IntermediateField field = fields.get(i);
            if (!STAGED_TARGET.matcher(field.getTo()).matches()) {
                throw new TransferAssemblyException(
                        "intermediate #" + i + ": 'to' must be a '$'-prefixed dotted path without "
                                + "wildcards/indexes, was '" + field.getTo() + "' (spec: " + spec.getName() + ")");
            }
            String root = stagedRoot(field.getTo());
            if (!declaredRoots.add(root)) {
                throw new TransferAssemblyException(
                        "intermediate #" + i + ": duplicate staged root '" + root + "' (spec: " + spec.getName() + ")");
            }
            boolean hasFrom = field.getFrom() != null && !field.getFrom().isBlank();
            boolean hasExpr = field.getExpr() != null && !field.getExpr().isBlank();
            if (hasFrom == hasExpr) {
                throw new TransferAssemblyException(
                        "intermediate #" + i + " [" + field.getTo() + "]: exactly one of 'from' or 'expr' "
                                + "is required (spec: " + spec.getName() + ")");
            }
            List<String> references = new ArrayList<>();
            if (hasFrom) {
                if (field.getFrom().contains("[*]")) {
                    throw new TransferAssemblyException(
                            "intermediate #" + i + " [" + field.getTo() + "]: 'from' does not support "
                                    + "[*] (array derivation uses 'expr') (spec: " + spec.getName() + ")");
                }
                references.add(field.getFrom());
            }
            if (hasExpr) {
                references.add(field.getExpr());
            }
            for (String reference : references) {
                for (String referenced : stagedRoots(reference)) {
                    if (!declaredRoots.contains(referenced)) {
                        throw new TransferAssemblyException(
                                ("intermediate #%d [%s]: references staged key '%s' that is not declared "
                                        + "by an earlier intermediate (forward references and undeclared keys "
                                        + "are rejected) (spec: %s)")
                                        .formatted(i, field.getTo(), referenced, spec.getName()));
                    }
                }
            }
        }
        for (int i = 0; i < spec.getRules().size(); i++) {
            MappingRule rule = spec.getRules().get(i);
            if (rule.getFrom().startsWith("$")) {
                requireDeclared(rule.getFrom(), declaredRoots, "rule #" + i, spec.getName());
            }
            requireDeclaredTransformArgs(rule.getTransform(), declaredRoots, "rule #" + i, spec.getName());
            if (rule.getWhen() != null) {
                for (ConditionMapping branch : rule.getWhen()) {
                    requireDeclaredTransformArgs(branch.getTransform(), declaredRoots, "rule #" + i, spec.getName());
                    requireDeclaredTransformArgs(branch.getOtherwise(), declaredRoots, "rule #" + i, spec.getName());
                }
            }
        }
        for (int i = 0; i < spec.computedOrEmpty().size(); i++) {
            for (String referenced : stagedRoots(spec.computedOrEmpty().get(i).getExpr())) {
                if (!declaredRoots.contains(referenced)) {
                    throw new TransferAssemblyException(
                            ("computed #%d [%s]: references staged key '%s' that is not declared by any "
                                    + "intermediate (spec: %s)")
                                    .formatted(i, spec.computedOrEmpty().get(i).getTo(), referenced, spec.getName()));
                }
            }
        }
    }

    /** transform 链中 {@code $path} 形态实参的声明存在性校验（引号字面量不校验——parseArgs 已剥引号，此处放行） */
    private static void requireDeclaredTransformArgs(String chain, Set<String> declaredRoots,
                                                     String owner, String specName) {
        if (chain == null || chain.isBlank()) {
            return;
        }
        for (String step : chain.split("\\|")) {
            Matcher call = FUNCTION_CALL.matcher(step.trim());
            if (!call.matches()) {
                continue;
            }
            for (String arg : call.group(2).split(",")) {
                String candidate = arg.trim();
                if (STAGED_ARG.matcher(candidate).matches()) {
                    requireDeclared(candidate, declaredRoots, owner, specName);
                }
            }
        }
    }

    private static void requireDeclared(String stagedPath, Set<String> declaredRoots,
                                        String owner, String specName) {
        String root = stagedRoot(stagedPath);
        if (!declaredRoots.contains(root)) {
            throw new TransferAssemblyException(
                    owner + ": references staged key '" + root + "' that is not declared by any "
                            + "intermediate (spec: " + specName + ")");
        }
    }

    /** 提取暂存路径/表达式的根引用集合：{@code $a.b} → {@code $a}；表达式扫描全部 {@code $ident} */
    private static String stagedRoot(String stagedPath) {
        return stagedPath.startsWith("$") ? stagedPath.substring(0, stagedPath.indexOf('.') > 0
                ? stagedPath.indexOf('.') : stagedPath.length()) : stagedPath;
    }

    private static List<String> stagedRoots(String text) {
        List<String> roots = new ArrayList<>();
        Matcher matcher = STAGED_ROOT.matcher(text);
        while (matcher.find()) {
            String root = "$" + matcher.group(1);
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    /**
     * Phase 1.5：按声明序求值各中间结果。上下文为源 + 已产出暂存的合并视图；
     * from 形态复用 missingPolicy（键不存在）/nullPolicy（值为 null 跳过产出）语义。
     */
    private Map<String, Object> executeIntermediate(Map<String, Object> flatSource, String separator) {
        List<IntermediateField> fields = spec.intermediateOrEmpty();
        if (fields.isEmpty()) {
            return Map.of();
        }
        // 源键以 $ 开头会与暂存命名空间在合并视图中无法区分——运行期 fail-fast
        flatSource.keySet().stream().filter(key -> key.startsWith("$")).findFirst()
                .ifPresent(key -> {
                    throw new RuleMatchException("source key '" + key
                            + "' conflicts with the '$'-prefixed intermediate namespace (spec: "
                            + spec.getName() + ")");
                });
        NullPolicy nullPolicy = spec.optionsOrNew().getNullPolicy();
        Map<String, Object> flatStaging = new LinkedHashMap<>();
        for (IntermediateField field : fields) {
            Map<String, Object> context = withStaging(flatSource, flatStaging);
            Object value;
            if (field.getExpr() != null && !field.getExpr().isBlank()) {
                value = exprEvaluator.evaluate(field.getExpr(), context);
            } else {
                if (!context.containsKey(field.getFrom())) {
                    applyMissingPolicy(field.getFrom(), field.getTo());
                    continue;   // 键缺失按 missingPolicy 处置后不产出该暂存键
                }
                value = context.get(field.getFrom());
                if (value == null && nullPolicy == NullPolicy.SKIP) {
                    continue;   // 对齐规则映射的 null 语义：不产出，后续引用以运行期缺失暴露
                }
                if (field.getTransform() != null && !field.getTransform().isBlank()) {
                    value = applyChain(field.getFrom(), field.getTo(), -1, field.getTransform(),
                            value, context);
                }
            }
            putStaged(flatStaging, field.getTo(), value, separator);
        }
        return flatStaging;
    }

    /** 暂存值写入：容器值展开为 to 前缀的扁平键集（支持 rules 的 [*] 通配导航），标量/空容器直接落键 */
    private void putStaged(Map<String, Object> flatStaging, String to, Object value, String separator) {
        if (value == null || !(value instanceof Map || value instanceof List)) {
            flatStaging.put(to, value);
            return;
        }
        Map<String, Object> flatValue =
                flatProcessor.flatten(flatProcessor.mapper().valueToTree(value), separator);
        if (flatValue.isEmpty()) {
            flatStaging.put(to, value);
            return;
        }
        flatValue.forEach((key, sub) -> flatStaging.put(to + separator + key, sub));
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
    // validations（评审 6.x）：对目标 FlatMap 的数据值校验，defaults 后、Unflatten 前
    // =====================================================================

    /**
     * 内置断言谓词（评审 6.2）；数值比较统一 BigDecimal，null 值判定为断言失败（review P1）。
     * 日期断言（设计文档 §3.2「日期」行批次）：ISO 日期/日期时间统一经
     * {@link #parseIsoDateTime} 比较，null 与不可解析值均判失败
     * （不裸抛——比数值断言的非数值裸抛 NFE 更进一步，见设计文档附录 D.4）。
     */
    private static final Map<String, AssertPredicate> ASSERT_PREDICATES = Map.ofEntries(
            Map.entry("notNull", (value, args) -> value != null),
            Map.entry("notBlank", (value, args) -> value != null && !String.valueOf(value).isBlank()),
            Map.entry("gt", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) > 0),
            Map.entry("gte", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) >= 0),
            Map.entry("lt", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) < 0),
            Map.entry("lte", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) <= 0),
            Map.entry("eq", (value, args) -> equalsLoosely(value, args.get(0))),
            Map.entry("neq", (value, args) -> !equalsLoosely(value, args.get(0))),
            Map.entry("regex", (value, args) -> value != null && Pattern.matches(args.get(0), String.valueOf(value))),
            Map.entry("dateBefore", (value, args) -> dateMatches(value, args, cmp -> cmp < 0)),
            Map.entry("dateAfter", (value, args) -> dateMatches(value, args, cmp -> cmp > 0)),
            Map.entry("dateNotBefore", (value, args) -> dateMatches(value, args, cmp -> cmp >= 0)),
            Map.entry("dateNotAfter", (value, args) -> dateMatches(value, args, cmp -> cmp <= 0)));

    /** 数值断言集合（构造期校验参数可转数值；eq/neq 参数可为字符串故不在其列） */
    private static final Set<String> NUMERIC_ASSERTIONS = Set.of("gt", "gte", "lt", "lte");

    /** 日期断言集合（构造期校验参数为 ISO 日期/日期时间；带 'Z'/offset 形态不支持） */
    private static final Set<String> DATE_ASSERTIONS =
            Set.of("dateBefore", "dateAfter", "dateNotBefore", "dateNotAfter");

    /** 需要实参的断言集合（构造期校验参数非空） */
    private static final Set<String> PARAMETRIZED_ASSERTIONS =
            Set.of("gt", "gte", "lt", "lte", "eq", "neq", "regex",
                    "dateBefore", "dateAfter", "dateNotBefore", "dateNotAfter");

    private static final Pattern ASSERTION_CALL = Pattern.compile("^(\\w+)\\s*\\((.*)\\)$");

    /** condition 中的裸标识符（元素字段引用）；排除 true/false/null 字面量 */
    private static final Pattern CONDITION_IDENTIFIER =
            Pattern.compile("(?<![\\w.'])([a-zA-Z_][a-zA-Z0-9_]*)");

    private static final Set<String> CONDITION_LITERALS = Set.of("true", "false", "null", "and", "or", "not");

    @FunctionalInterface
    private interface AssertPredicate {

        boolean test(@Nullable Object value, List<String> args);
    }

    /** 构造期预校验：形态二选一、断言语法与谓词白名单、数值断言参数格式、condition 通配层级 */
    private static void validateValidations(TransferSpec spec) {
        for (ValidationRule validation : spec.validationsOrEmpty()) {
            boolean hasRules = validation.getRules() != null && !validation.getRules().isEmpty();
            boolean hasCondition = validation.getCondition() != null && !validation.getCondition().isBlank();
            if (hasRules == hasCondition) {
                throw new TransferAssemblyException(
                        "validation [path: " + validation.getPath() + "]: exactly one of 'rules' "
                                + "(assertions) or 'condition' is required (spec: " + spec.getName() + ")");
            }
            if (hasCondition && countWildcardLevels(validation.getPath()) > 1) {
                throw new TransferAssemblyException(
                        "validation [path: " + validation.getPath()
                                + "]: condition supports at most one [*] level "
                                + "(assertion form has no such limit) (spec: " + spec.getName() + ")");
            }
            if (hasCondition && (validation.getMessage() == null || validation.getMessage().isBlank())) {
                throw new TransferAssemblyException(
                        "validation [path: " + validation.getPath()
                                + "]: 'message' is required for condition form (spec: " + spec.getName() + ")");
            }
            if (hasRules) {
                for (Assertion assertion : validation.getRules()) {
                    validateAssertionSyntax(assertion, validation.getPath(), spec.getName());
                }
            }
        }
    }

    private static void validateAssertionSyntax(Assertion assertion, String path, String specName) {
        Matcher call = ASSERTION_CALL.matcher(assertion.getAssertExpr().trim());
        String name = call.matches() ? call.group(1) : assertion.getAssertExpr().trim();
        List<String> args = call.matches() ? parseArgsStatic(call.group(2)) : List.of();
        if (!ASSERT_PREDICATES.containsKey(name)) {
            throw new TransferAssemblyException(
                    ("validation [path: %s]: unknown assertion '%s' (available: %s) (spec: %s)")
                            .formatted(path, name, ASSERT_PREDICATES.keySet(), specName));
        }
        if (PARAMETRIZED_ASSERTIONS.contains(name) && args.isEmpty()) {
            throw new TransferAssemblyException(
                    ("validation [path: %s]: assertion '%s' requires an argument (spec: %s)")
                            .formatted(path, name, specName));
        }
        if (NUMERIC_ASSERTIONS.contains(name)) {
            try {
                decimal(args.get(0));
            } catch (RuntimeException e) {
                throw new TransferAssemblyException(
                        ("validation [path: %s]: assertion '%s' requires a numeric argument, was '%s' (spec: %s)")
                                .formatted(path, name, args.get(0), specName));
            }
        }
        if (DATE_ASSERTIONS.contains(name)) {
            try {
                parseIsoDateTime(args.get(0));
            } catch (DateTimeParseException e) {
                throw new TransferAssemblyException(
                        ("validation [path: %s]: assertion '%s' requires an ISO-8601 date or date-time argument, was '%s' (spec: %s)")
                                .formatted(path, name, args.get(0), specName));
            }
        }
    }

    /** 日期断言执行：null 与不可解析值判失败（记 ValidationFailure），不裸抛 */
    private static boolean dateMatches(@Nullable Object value, List<String> args, IntPredicate op) {
        if (value == null) {
            return false;
        }
        try {
            return op.test(parseIsoDateTime(String.valueOf(value))
                    .compareTo(parseIsoDateTime(args.get(0))));
        } catch (DateTimeParseException unparseable) {
            return false;
        }
    }

    /**
     * ISO 日期或日期时间统一解析为 {@link LocalDateTime}（日期按当日零点参与比较，
     * LocalDate 值与 LocalDateTime 参数可混比）；断言执行与构造期参数预校验共用。
     * 先按 LocalDateTime 尝试、失败回退 LocalDate（小写 't' 的合法 ISO 串 JDK 亦接受）；
     * 含 {@code 'Z'}/offset 形态两侧均拒绝——目标形态合同校验不做时区折算，
     * 须先经转换函数归一为无时区形态。
     *
     * @throws DateTimeParseException 文本非 ISO 日期/日期时间形态
     */
    private static LocalDateTime parseIsoDateTime(String text) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException notDateTime) {
            return LocalDate.parse(text).atStartOfDay();
        }
    }

    private static int countWildcardLevels(String path) {
        return path.split(Pattern.quote("[*]"), -1).length - 1;
    }

    private List<ValidationFailure> executeValidations(Map<String, Object> flatTarget) {
        List<ValidationFailure> failures = new ArrayList<>();
        boolean failFast = spec.optionsOrNew().getValidationMode() != ValidationMode.COLLECT;
        for (ValidationRule validation : spec.validationsOrEmpty()) {
            if (validation.getRules() != null && !validation.getRules().isEmpty()) {
                collectAssertionFailures(validation, flatTarget, failures, failFast);
                if (failFast && !failures.isEmpty()) {
                    return failures;
                }
                continue;
            }
            collectConditionFailures(validation, flatTarget, failures, failFast);
            if (failFast && !failures.isEmpty()) {
                return failures;
            }
        }
        return failures;
    }

    /** 断言模式：path 可含 [*]（逐元素断言）；字面键不存在时以 null 值执行（notNull 场景） */
    private void collectAssertionFailures(ValidationRule validation, Map<String, Object> flatTarget,
                                          List<ValidationFailure> failures, boolean failFast) {
        List<Map.Entry<String, Object>> targets =
                flatProcessor.expandWildcard(validation.getPath(), flatTarget);
        if (targets.isEmpty() && !validation.getPath().contains("[*]")) {
            // 字面键不存在：仍以 null 值执行断言（notNull 场景）——Map.entry 不容 null，用 SimpleEntry
            targets = List.of(new java.util.AbstractMap.SimpleEntry<>(validation.getPath(), null));
        }
        for (Map.Entry<String, Object> target : targets) {
            for (Assertion assertion : validation.getRules()) {
                if (!evaluateAssertion(assertion.getAssertExpr(), target.getValue())) {
                    failures.add(new ValidationFailure(target.getKey(), assertion.getAssertExpr(),
                            assertion.getMessage(), target.getValue()));
                    if (failFast) {
                        return;
                    }
                    break;   // 同键首个失败断言记录后跳到下一键
                }
            }
        }
    }

    /** condition 模式：标量 path 绑定 value；末级通配 path 逐元素绑定裸标识符（元素字段） */
    private void collectConditionFailures(ValidationRule validation, Map<String, Object> flatTarget,
                                          List<ValidationFailure> failures, boolean failFast) {
        String path = validation.getPath();
        String condition = validation.getCondition();
        if (!path.contains("[*]")) {
            Object value = flatTarget.get(path);
            Map<String, Object> binding = new HashMap<>();
            binding.put("value", value);   // HashMap 容 null（Map.of 不容）
            if (!truthy(condition, binding)) {
                failures.add(new ValidationFailure(path, condition, validation.getMessage(), value));
            }
            return;
        }
        String elementPrefix = path.substring(0, path.indexOf("[*]"));
        for (int index : elementIndexes(elementPrefix, flatTarget)) {
            String indexedPrefix = elementPrefix + "[" + index + "].";
            Map<String, Object> binding = new HashMap<>();
            for (String identifier : conditionIdentifiers(condition)) {
                binding.put(identifier, flatTarget.get(indexedPrefix + identifier));
            }
            if (!truthy(condition, binding)) {
                failures.add(new ValidationFailure(elementPrefix + "[" + index + "]",
                        condition, validation.getMessage(), binding));
                if (failFast) {
                    return;
                }
            }
        }
    }

    private boolean evaluateAssertion(String assertExpr, @Nullable Object value) {
        Matcher call = ASSERTION_CALL.matcher(assertExpr.trim());
        String name = call.matches() ? call.group(1) : assertExpr.trim();
        List<String> args = call.matches() ? parseArgsStatic(call.group(2)) : List.of();
        return ASSERT_PREDICATES.get(name).test(value, args);   // 谓词存在性构造期已校验
    }

    private boolean truthy(String condition, Map<String, Object> binding) {
        return Boolean.TRUE.equals(exprEvaluator.evaluate(condition, binding));
    }

    /** 元素前缀（如 crmOrder.lines）→ 目标 FlatMap 中出现的索引集合（升序去重） */
    private static List<Integer> elementIndexes(String elementPrefix, Map<String, Object> flatTarget) {
        Pattern prefixPattern = Pattern.compile("^" + Pattern.quote(elementPrefix) + "\\[(\\d+)\\]\\.");
        Set<Integer> indexes = new TreeSet<>();
        for (String key : flatTarget.keySet()) {
            Matcher matcher = prefixPattern.matcher(key);
            if (matcher.find()) {
                indexes.add(Integer.valueOf(matcher.group(1)));
            }
        }
        return List.copyOf(indexes);
    }

    private static List<String> conditionIdentifiers(String condition) {
        List<String> identifiers = new ArrayList<>();
        Matcher matcher = CONDITION_IDENTIFIER.matcher(condition);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!CONDITION_LITERALS.contains(candidate) && !identifiers.contains(candidate)) {
                identifiers.add(candidate);
            }
        }
        return identifiers;
    }

    private static boolean equalsLoosely(@Nullable Object value, @Nullable String expected) {
        if (value == null || expected == null) {
            return value == null && expected == null;
        }
        if (String.valueOf(value).equals(expected)) {
            return true;
        }
        try {
            return decimal(value).compareTo(decimal(expected)) == 0;
        } catch (RuntimeException notNumeric) {
            return false;
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            throw new NumberFormatException("null is not numeric");
        }
        if (value instanceof BigDecimal decimalValue) {
            return decimalValue;
        }
        return new BigDecimal(String.valueOf(value));
    }

    /** parseArgs 的静态视图（构造期校验复用） */
    private static List<String> parseArgsStatic(String rawArgs) {
        return parseArgs(rawArgs);
    }
}
