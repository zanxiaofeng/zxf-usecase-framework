package com.example.datatransfer.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.datatransfer.core.exception.ExpressionException;
import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.exception.TransferException;
import com.example.datatransfer.core.exception.ValidationFailure;
import com.example.datatransfer.core.expression.ExpressionEvaluator;
import com.example.datatransfer.core.flatten.PathParser;
import com.example.datatransfer.core.spec.ValidationMode;

/**
 * 数据值校验（评审 6.x）：对目标 FlatMap 执行，时点在 defaults 后、Unflatten 前。
 * 装配期校验 spec 的 validations 段（{@link #validate}），运行期逐条执行（{@link #execute}）。
 */
final class ValidationRunner {

    private final CompiledPlan plan;
    private final ExpressionEvaluator exprEvaluator;
    /** 校验路径各级元素前缀 → 索引匹配正则（路径来自 spec 配置，集合有限） */
    private final Map<String, Pattern> elementPatterns = new ConcurrentHashMap<>();
    /** 断言 regex 的预编译正则（按 pattern 文本缓存，不再逐值重编译） */
    private final Map<String, Pattern> assertionPatterns = new ConcurrentHashMap<>();

    ValidationRunner(CompiledPlan plan, ExpressionEvaluator exprEvaluator) {
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.exprEvaluator = Objects.requireNonNull(exprEvaluator, "exprEvaluator must not be null");
    }

    // =====================================================================
    // validations（评审 6.x）：对目标 FlatMap 的数据值校验，defaults 后、Unflatten 前
    // =====================================================================

    /**
     * 内置断言谓词（评审 6.2）；数值比较统一 BigDecimal。null 与非数值一律判为断言失败
     * （外部评审二轮 P1-6：历史行为是非数值裸抛 NumberFormatException，collect 模式被中断）；
     * 日期断言的 null 与不可解析值同样判失败。
     */
    private static final Map<String, AssertPredicate> ASSERT_PREDICATES = Map.ofEntries(
            Map.entry("notNull", (value, args) -> value != null),
            Map.entry("notBlank", (value, args) -> value != null && !String.valueOf(value).isBlank()),
            Map.entry("gt", (value, args) -> numeric(value, args, comparison -> comparison > 0)),
            Map.entry("gte", (value, args) -> numeric(value, args, comparison -> comparison >= 0)),
            Map.entry("lt", (value, args) -> numeric(value, args, comparison -> comparison < 0)),
            Map.entry("lte", (value, args) -> numeric(value, args, comparison -> comparison <= 0)),
            Map.entry("eq", (value, args) -> equalsLoosely(value, args.get(0))),
            Map.entry("neq", (value, args) -> !equalsLoosely(value, args.get(0))),
            Map.entry("regex", (value, args) -> value != null),   // 正则匹配在 evaluateAssertion 中执行（Pattern 缓存）
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

    /** condition 中的裸标识符（元素字段引用）；排除属性名、引号字符串字面量内部的词与 true/false/null 字面量 */
    private static final Pattern CONDITION_IDENTIFIER =
            Pattern.compile("(?<![\\w.'\"])([a-zA-Z_][a-zA-Z0-9_]*)");

    private static final Set<String> CONDITION_LITERALS = Set.of("true", "false", "null", "and", "or", "not");

    @FunctionalInterface
    private interface AssertPredicate {

        boolean test(@Nullable Object value, List<String> args);
    }

    /** 表达式求值：JEXL 等运行期异常包装为 ExpressionException（携带表达式与所属校验） */
    private Object evaluate(String expr, String owner, Map<String, Object> context) {
        try {
            return exprEvaluator.evaluate(expr, context, null);
        } catch (TransferException alreadyTyped) {
            throw alreadyTyped;
        } catch (RuntimeException e) {
            throw new ExpressionException(expr, owner, e);
        }
    }

    /** 构造期预校验：形态二选一、路径语法、断言语法与谓词白名单、数值断言参数格式、condition 通配层级 */
    static void validate(CompiledPlan plan) {
        for (CompiledPlan.Validation validation : plan.validations()) {
            validateValidationShape(validation, plan);
            if (validation.assertions() != null && !validation.assertions().isEmpty()) {
                validation.assertions().forEach(
                        assertion -> validateAssertionSyntax(assertion, validation.path(), plan.name()));
            }
        }
    }

    /** 形态校验：rules（断言）与 condition 二选一；condition 形态至多一级 [*] 且 message 必填 */
    private static void validateValidationShape(CompiledPlan.Validation validation, CompiledPlan plan) {
        boolean hasRules = validation.assertions() != null && !validation.assertions().isEmpty();
        boolean hasCondition = validation.condition() != null && !validation.condition().isBlank();
        String owner = "validation [path: " + validation.path() + "]";
        if (hasRules == hasCondition) {
            throw new TransferAssemblyException(owner + ": exactly one of 'rules' (assertions) or 'condition' "
                    + "is required (spec: " + plan.name() + ")");
        }
        validatePathSyntax(validation.path(), owner, plan);
        if (hasCondition && countWildcardLevels(validation.path()) > 1) {
            throw new TransferAssemblyException(owner
                    + ": condition supports at most one [*] level "
                    + "(assertion form has no such limit) (spec: " + plan.name() + ")");
        }
        boolean missingMessage = validation.message() == null || validation.message().isBlank();
        if (hasCondition && missingMessage) {
            throw new TransferAssemblyException(owner + ": 'message' is required for condition form (spec: "
                    + plan.name() + ")");
        }
    }

    private static void validatePathSyntax(String path, String owner, CompiledPlan plan) {
        try {
            PathParser.parsePattern(path, plan.options().separator());
        } catch (IllegalArgumentException invalidPath) {
            throw new TransferAssemblyException(owner + ": invalid path expression (spec: " + plan.name() + ")",
                    invalidPath);
        }
    }

    private static void validateAssertionSyntax(CompiledPlan.Assertion assertion, String path, String specName) {
        String expression = String.valueOf(assertion.expression()).trim();
        Matcher call = ASSERTION_CALL.matcher(expression);
        String name = call.matches() ? call.group(1) : expression;
        List<String> args = call.matches() ? ArgumentParser.parse(call.group(2)) : List.of();
        String owner = "validation [path: " + path + "]";
        if (!ASSERT_PREDICATES.containsKey(name)) {
            throw new TransferAssemblyException(
                    ("%s: unknown assertion '%s' (available: %s) (spec: %s)")
                            .formatted(owner, name, ASSERT_PREDICATES.keySet(), specName));
        }
        if (PARAMETRIZED_ASSERTIONS.contains(name) && args.isEmpty()) {
            throw new TransferAssemblyException(
                    ("%s: assertion '%s' requires an argument (spec: %s)").formatted(owner, name, specName));
        }
        validateNumericArgument(name, args, owner, specName);
        validateDateArgument(name, args, owner, specName);
        validateRegexPattern(name, args, owner, specName);
    }

    private static void validateNumericArgument(String name, List<String> args, String owner, String specName) {
        if (!NUMERIC_ASSERTIONS.contains(name)) {
            return;
        }
        try {
            decimal(args.get(0));
        } catch (RuntimeException notNumeric) {
            throw new TransferAssemblyException(
                    ("%s: assertion '%s' requires a numeric argument, was '%s' (spec: %s)")
                            .formatted(owner, name, args.get(0), specName));
        }
    }

    private static void validateDateArgument(String name, List<String> args, String owner, String specName) {
        if (!DATE_ASSERTIONS.contains(name)) {
            return;
        }
        try {
            parseIsoDateTime(args.get(0));
        } catch (DateTimeParseException notIso) {
            throw new TransferAssemblyException(
                    ("%s: assertion '%s' requires an ISO-8601 date or date-time argument, was '%s' (spec: %s)")
                            .formatted(owner, name, args.get(0), specName));
        }
    }

    /** regex 断言的 pattern 在装配期预编译：非法正则提前暴露（历史行为是运行期裸抛 PatternSyntaxException） */
    private static void validateRegexPattern(String name, List<String> args, String owner, String specName) {
        if (!"regex".equals(name)) {
            return;
        }
        try {
            Pattern.compile(args.get(0));
        } catch (RuntimeException invalidPattern) {
            throw new TransferAssemblyException(
                    ("%s: assertion 'regex' has an invalid pattern '%s' (spec: %s)")
                            .formatted(owner, args.get(0), specName), invalidPattern);
        }
    }

    /** 断言执行：返回 null 表示通过，否则返回带原因的失败消息 */
    private @Nullable String evaluateAssertion(CompiledPlan.Assertion assertion, @Nullable Object value) {
        Matcher call = ASSERTION_CALL.matcher(assertion.expression().trim());
        String name = call.matches() ? call.group(1) : assertion.expression().trim();
        List<String> args = call.matches() ? ArgumentParser.parse(call.group(2)) : List.of();
        if (ASSERT_PREDICATES.get(name).test(value, args) && matchesRegex(name, args, value)) {
            return null;
        }
        boolean notNumeric = NUMERIC_ASSERTIONS.contains(name) && value != null && asDecimal(value) == null;
        return notNumeric ? assertion.message() + " (value is not numeric)" : assertion.message();
    }

    /** regex 断言：值非 null 且匹配（pattern 已在装配期校验合法） */
    private boolean matchesRegex(String name, List<String> args, @Nullable Object value) {
        if (!"regex".equals(name)) {
            return true;
        }
        return value != null && assertionPatterns.computeIfAbsent(args.get(0), Pattern::compile)
                .matcher(String.valueOf(value)).matches();
    }

    List<ValidationFailure> execute(Map<String, Object> flatTarget) {
        List<ValidationFailure> failures = new ArrayList<>();
        boolean failFast = plan.options().validationMode() != ValidationMode.COLLECT;
        for (CompiledPlan.Validation validation : plan.validations()) {
            collectFailures(validation, flatTarget, failures, failFast);
            if (failFast && !failures.isEmpty()) {
                return failures;
            }
        }
        return failures;
    }

    private void collectFailures(CompiledPlan.Validation validation, Map<String, Object> flatTarget,
                                 List<ValidationFailure> failures, boolean failFast) {
        if (validation.assertions() != null) {
            collectAssertionFailures(validation, flatTarget, failures, failFast);
            return;
        }
        collectConditionFailures(validation, flatTarget, failures, failFast);
    }

    /**
     * 断言模式：path 可含 [*]（逐元素断言）。含通配时先枚举实际存在的元素、再逐元素取字段——
     * 元素存在但字段缺失时同样以 null 执行（外部评审二轮 评审二-9：历史行为只校验已存在的键，
     * 部分元素缺字段会漏检）；字面键不存在时也以 null 执行（notNull 场景）。
     */
    private void collectAssertionFailures(CompiledPlan.Validation validation, Map<String, Object> flatTarget,
                                          List<ValidationFailure> failures, boolean failFast) {
        for (String concrete : assertionTargets(validation.path(), flatTarget)) {
            Object value = flatTarget.get(concrete);
            for (CompiledPlan.Assertion assertion : validation.assertions()) {
                String failedMessage = evaluateAssertion(assertion, value);
                if (failedMessage != null) {
                    failures.add(new ValidationFailure(concrete, assertion.expression(), failedMessage, value));
                    if (failFast) {
                        return;
                    }
                    break;   // 同键首个失败断言记录后跳到下一键
                }
            }
        }
    }

    /** condition 模式：标量 path 绑定 value；末级通配 path 逐元素绑定裸标识符（元素字段） */
    private void collectConditionFailures(CompiledPlan.Validation validation, Map<String, Object> flatTarget,
                                          List<ValidationFailure> failures, boolean failFast) {
        String path = validation.path();
        String condition = validation.condition();
        String owner = validation.describe();
        if (!path.contains(PathParser.WILDCARD)) {
            Object value = flatTarget.get(path);
            Map<String, Object> binding = new HashMap<>();
            binding.put("value", value);   // HashMap 容 null（Map.of 不容）
            if (!truthy(condition, binding, owner)) {
                failures.add(new ValidationFailure(path, condition, validation.message(), value));
            }
            return;
        }
        String elementPrefix = path.substring(0, path.indexOf(PathParser.WILDCARD));
        for (int index : elementIndexes(elementPrefix, flatTarget)) {
            String indexedPrefix = elementPrefix + "[" + index + "].";
            Map<String, Object> binding = new HashMap<>();
            for (String identifier : conditionIdentifiers(condition)) {
                binding.put(identifier, flatTarget.get(indexedPrefix + identifier));
            }
            if (!truthy(condition, binding, owner)) {
                failures.add(new ValidationFailure(elementPrefix + "[" + index + "]",
                        condition, validation.message(), binding));
                if (failFast) {
                    return;
                }
            }
        }
    }

    private boolean truthy(String condition, Map<String, Object> binding, String owner) {
        return Boolean.TRUE.equals(evaluate(condition, owner, binding));
    }

    /** 断言目标：字面路径原样返回（缺失按 null 校验）；含通配时枚举到每个实际元素的具体字段路径 */
    private List<String> assertionTargets(String path, Map<String, Object> flatTarget) {
        if (!path.contains(PathParser.WILDCARD)) {
            return List.of(path);
        }
        return enumerateTargets(PathParser.parsePattern(path, plan.options().separator()), flatTarget);
    }

    /** 按段逐步实例化路径模式：[*] 处枚举目标 FlatMap 中实际出现的数组索引，其余段原样追加 */
    private List<String> enumerateTargets(List<Object> pattern, Map<String, Object> flatTarget) {
        String separator = plan.options().separator();
        List<List<Object>> partials = new ArrayList<>();
        partials.add(new ArrayList<>());
        for (Object segment : pattern) {
            partials = extend(partials, segment, flatTarget, separator);
        }
        List<String> targets = new ArrayList<>(partials.size());
        for (List<Object> partial : partials) {
            targets.add(PathParser.toPath(partial, separator));
        }
        return targets;
    }

    private List<List<Object>> extend(List<List<Object>> partials, Object segment,
                                      Map<String, Object> flatTarget, String separator) {
        List<List<Object>> extended = new ArrayList<>();
        for (List<Object> partial : partials) {
            if (PathParser.WILDCARD.equals(segment)) {
                String prefix = PathParser.toPath(partial, separator);
                elementIndexes(prefix, flatTarget).forEach(index -> extended.add(appended(partial, index)));
                continue;
            }
            extended.add(appended(partial, segment));
        }
        return extended;
    }

    private static List<Object> appended(List<Object> partial, Object segment) {
        List<Object> extended = new ArrayList<>(partial.size() + 1);
        extended.addAll(partial);
        extended.add(segment);
        return extended;
    }

    /** 元素前缀（如 out.lines）→ 目标 FlatMap 中出现的索引集合（升序去重） */
    private List<Integer> elementIndexes(String elementPrefix, Map<String, Object> flatTarget) {
        Pattern prefixPattern = elementPatterns.computeIfAbsent(elementPrefix, prefix -> Pattern.compile(
                prefix.isEmpty() ? "^\\[(\\d+)\\]" : "^" + Pattern.quote(prefix) + "\\[(\\d+)\\]"));
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

    private static boolean numeric(@Nullable Object value, List<String> args, IntPredicate comparison) {
        BigDecimal left = asDecimal(value);
        return left != null && comparison.test(left.compareTo(decimal(args.get(0))));
    }

    /** 值 → BigDecimal；null 与非数值返回 null（断言判失败用，不抛） */
    private static @Nullable BigDecimal asDecimal(@Nullable Object value) {
        if (value == null || value instanceof Map || value instanceof List) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException notNumeric) {
            return null;
        }
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

    /** 构造期数值参数校验用：非数值抛 NumberFormatException */
    private static BigDecimal decimal(Object value) {
        if (value == null) {
            throw new NumberFormatException("null is not numeric");
        }
        if (value instanceof BigDecimal decimalValue) {
            return decimalValue;
        }
        return new BigDecimal(String.valueOf(value));
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
     * 须先经 {@code toIsoDateTime(fmt, zone)} 换算为统一时区的无时区形态（仅丢弃 offset 会让
     * 本地时间顺序与真实时刻相反）。
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
}
