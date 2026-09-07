package com.example.datatransfer.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
 * 规则执行引擎（设计文档 §8.6）：Flatten → 规则映射（通配符对位 / 变换链 / 条件映射）
 * → computed（目标 FlatMap 上求值）→ defaults 注入 → Unflatten。
 *
 * <p>引擎实例不持有可变共享状态，线程安全；{@code sources} / {@code rewrites}
 * 第一版未实现执行语义，构造期 fail-fast（避免配置被静默忽略）。</p>
 */
@Slf4j
public class TransferEngine {

    private static final Pattern FUNCTION_CALL = Pattern.compile("^(\\w+)\\s*\\((.*)\\)$");

    /** null 短路语义下的兜底函数名（唯一在 null 输入时仍执行的内置函数） */
    private static final String DEFAULT_FUNCTION = "default";

    private final TransferSpec spec;
    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final FuncRegistry funcRegistry;
    private final ExpressionEvaluator exprEvaluator;
    /** spec 是否含 when 条件分支（决定 transfer 期是否预构建源嵌套视图，review P1 性能项） */
    private final boolean hasConditionalRules;

    public TransferEngine(TransferSpec spec) {
        this(spec, Map.of(), new JexlExpressionEvaluator());
    }

    public TransferEngine(TransferSpec spec, Map<String, TransformFunction> extraFunctions) {
        this(spec, extraFunctions, new JexlExpressionEvaluator());
    }

    public TransferEngine(TransferSpec spec,
                          Map<String, TransformFunction> extraFunctions,
                          ExpressionEvaluator exprEvaluator) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        this.exprEvaluator = Objects.requireNonNull(exprEvaluator, "exprEvaluator must not be null");
        this.funcRegistry = new FuncRegistry();
        extraFunctions.forEach(funcRegistry::register);
        validateUnsupportedFeatures(spec);
        validateRules(spec);
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

        // Phase 2: 规则映射（含 when 条件的 spec 预构建一次源嵌套视图，供逐元素条件求值复用）
        Map<String, Object> sourceView =
                hasConditionalRules ? flatProcessor.unflattenToMap(flatSource, separator) : null;
        List<MappingRule> rules = spec.getRules();
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            MappingRule rule = rules.get(ruleIndex);
            List<Map.Entry<String, Object>> matches =
                    flatProcessor.expandWildcard(rule.getFrom(), flatSource);
            if (matches.isEmpty()) {
                applyMissingPolicy(rule);
            }
            for (Map.Entry<String, Object> match : matches) {
                Object value = match.getValue();
                if (value == null && nullPolicy == NullPolicy.SKIP) {
                    continue;
                }
                // 多级 [*] 按出现顺序逐段对位（设计文档 §6.1）
                String targetPath = alignWildcards(rule.getTo(), match.getKey(), rule.getFrom(), separator);
                value = applyTransforms(rule, ruleIndex, value, flatSource, sourceView);
                flatTarget.put(targetPath, value);
            }
        }

        // Phase 3: computed —— 在目标 FlatMap 上求值，表达式引用目标路径（设计文档 §6.2）
        for (ComputedField computed : spec.computedOrEmpty()) {
            flatTarget.put(computed.getTo(), exprEvaluator.evaluate(computed.getExpr(), flatTarget));
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

    /** 变换入口：when 条件映射优先（与 transform 互斥），否则链式 transform */
    private Object applyTransforms(MappingRule rule, int ruleIndex, Object value,
                                   Map<String, Object> flatSource, Map<String, Object> sourceView) {
        if (rule.getWhen() != null && !rule.getWhen().isEmpty()) {
            return applyConditions(rule, ruleIndex, rule.getWhen(), value, flatSource, sourceView);
        }
        if (rule.getTransform() == null || rule.getTransform().isBlank()) {
            return value;
        }
        return applyChain(rule, ruleIndex, rule.getTransform(), value, flatSource);
    }

    /** 条件映射（设计文档 §6.3）：condition 可引用源 FlatMap 顶层键；落空走 otherwise，无 otherwise 保留原值 */
    private Object applyConditions(MappingRule rule, int ruleIndex, List<ConditionMapping> when,
                                   Object value, Map<String, Object> flatSource,
                                   Map<String, Object> sourceView) {
        for (ConditionMapping branch : when) {
            if (branch.getCondition() != null) {
                boolean matched;
                try {
                    matched = Boolean.TRUE.equals(
                            exprEvaluator.evaluate(branch.getCondition(), flatSource, sourceView));
                } catch (RuntimeException e) {
                    throw new TransformException("when condition evaluation failed: " + e.getMessage(),
                            ruleIndex, rule.getFrom(), rule.getTo(), branch.getCondition(), value, e);
                }
                if (matched) {
                    return applyChain(rule, ruleIndex, branch.getTransform(), value, flatSource);
                }
            }
        }
        return when.stream()
                .filter(branch -> branch.getOtherwise() != null)
                .findFirst()
                .map(branch -> applyChain(rule, ruleIndex, branch.getOtherwise(), value, flatSource))
                .orElse(value);
    }

    /**
     * 变换链 {@code fn | fn(args) | ...} 逐段应用（实参剥离单/双引号，第一版不支持嵌套逗号）。
     *
     * <p><b>null 短路</b>（评审 2.1）：中间结果为 null 时跳过后续变换——仅 {@code default()}
     * 兜底函数例外（default(null) 返回缺省值后，链继续按非 null 执行）；
     * 最终仍为 null 时按 {@code nullPolicy} 处置。</p>
     *
     * <p>未知函数与函数执行失败包装为 {@link TransformException}（携带规则索引、路径、
     * 函数名与当前值——评审 4.2 错误上下文要求）。</p>
     */
    private Object applyChain(MappingRule rule, int ruleIndex, String chain, Object value,
                              Map<String, Object> flatSource) {
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
                        rule.getFrom(), rule.getTo(), trimmed, current, e);
            }
            List<String> args = call.matches() ? parseArgs(call.group(2)) : List.of();
            try {
                current = function.apply(current, args, flatSource);
            } catch (RuntimeException e) {
                throw new TransformException("transform function '" + functionName + "' failed: "
                        + e.getMessage(), ruleIndex, rule.getFrom(), rule.getTo(),
                        trimmed, current, e);
            }
        }
        return current;
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

    private void applyMissingPolicy(MappingRule rule) {
        MissingPolicy policy = spec.optionsOrNew().getMissingPolicy();
        switch (policy) {
            case ERROR -> throw new RuleMatchException(
                    "rule [" + rule.getFrom() + " -> " + rule.getTo()
                            + "] matched no source key: " + rule.getFrom()
                            + " (spec: " + spec.getName() + ")");
            case WARN -> log.warn("rule [{} -> {}] matched no source key (spec: {})",
                    rule.getFrom(), rule.getTo(), spec.getName());
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
    // validations（评审 6.x）：对目标 FlatMap 的数据值校验，defaults 后、Unflatten 前
    // =====================================================================

    /** 内置断言谓词（评审 6.2）；数值比较统一 BigDecimal，null 值判定为断言失败（review P1） */
    private static final Map<String, AssertPredicate> ASSERT_PREDICATES = Map.of(
            "notNull", (value, args) -> value != null,
            "notBlank", (value, args) -> value != null && !String.valueOf(value).isBlank(),
            "gt", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) > 0,
            "gte", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) >= 0,
            "lt", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) < 0,
            "lte", (value, args) -> value != null && decimal(value).compareTo(decimal(args.get(0))) <= 0,
            "eq", (value, args) -> equalsLoosely(value, args.get(0)),
            "neq", (value, args) -> !equalsLoosely(value, args.get(0)),
            "regex", (value, args) -> value != null && Pattern.matches(args.get(0), String.valueOf(value)));

    /** 数值断言集合（构造期校验参数可转数值；eq/neq 参数可为字符串故不在其列） */
    private static final Set<String> NUMERIC_ASSERTIONS = Set.of("gt", "gte", "lt", "lte");

    /** 需要实参的断言集合（构造期校验参数非空） */
    private static final Set<String> PARAMETRIZED_ASSERTIONS =
            Set.of("gt", "gte", "lt", "lte", "eq", "neq", "regex");

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
