package com.example.datatransfer.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.datatransfer.core.expression.ExpressionEvaluator;
import com.example.datatransfer.core.expression.JexlExpressionEvaluator;
import com.example.datatransfer.core.flatten.FlatMapProcessor;
import com.example.datatransfer.core.flatten.PathParser;
import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.ConditionMapping;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.MissingPolicy;
import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.TransferSpec;
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

    private final TransferSpec spec;
    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final FuncRegistry funcRegistry;
    private final ExpressionEvaluator exprEvaluator;

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
        this.exprEvaluator = exprEvaluator;
        this.funcRegistry = new FuncRegistry();
        extraFunctions.forEach(funcRegistry::register);
        validateUnsupportedFeatures(spec);
        validateRules(spec);
    }

    public FuncRegistry getFuncRegistry() {
        return funcRegistry;
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
        Map<String, Object> flatTarget = new LinkedHashMap<>();

        // Phase 2: 规则映射
        for (MappingRule rule : spec.getRules()) {
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
                value = applyTransforms(rule, value, flatSource);
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
    private Object applyTransforms(MappingRule rule, Object value, Map<String, Object> flatSource) {
        if (rule.getWhen() != null && !rule.getWhen().isEmpty()) {
            return applyConditions(rule.getWhen(), value, flatSource);
        }
        if (rule.getTransform() == null || rule.getTransform().isBlank()) {
            return value;
        }
        return applyChain(rule.getTransform(), value, flatSource);
    }

    /** 条件映射（设计文档 §6.3）：condition 可引用源 FlatMap 顶层键；落空走 otherwise，无 otherwise 保留原值 */
    private Object applyConditions(List<ConditionMapping> when, Object value, Map<String, Object> flatSource) {
        for (ConditionMapping branch : when) {
            if (branch.getCondition() != null
                    && Boolean.TRUE.equals(exprEvaluator.evaluate(branch.getCondition(), flatSource))) {
                return applyChain(branch.getTransform(), value, flatSource);
            }
        }
        return when.stream()
                .filter(branch -> branch.getOtherwise() != null)
                .findFirst()
                .map(branch -> applyChain(branch.getOtherwise(), value, flatSource))
                .orElse(value);
    }

    /** 变换链 {@code fn | fn(args) | ...} 逐段应用（实参剥离单/双引号，第一版不支持嵌套逗号） */
    private Object applyChain(String chain, Object value, Map<String, Object> flatSource) {
        Object current = value;
        for (String step : chain.split("\\|")) {
            String trimmed = step.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher call = FUNCTION_CALL.matcher(trimmed);
            TransformFunction function;
            List<String> args;
            if (call.matches()) {
                function = funcRegistry.get(call.group(1));
                args = parseArgs(call.group(2));
            } else {
                function = funcRegistry.get(trimmed);
                args = List.of();
            }
            current = function.apply(current, args, flatSource);
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
            case ERROR -> throw new IllegalStateException(
                    "rule matched no source key: " + rule.getFrom());
            case WARN -> log.warn("rule [{} -> {}] matched no source key (spec: {})",
                    rule.getFrom(), rule.getTo(), spec.getName());
            case IGNORE -> { /* 静默 */ }
        }
    }

    private static void validateUnsupportedFeatures(TransferSpec spec) {
        if (spec.getSources() != null && !spec.getSources().isEmpty()) {
            throw new IllegalStateException(
                    "sources (multi-source merge) is not yet supported (spec: " + spec.getName() + ")");
        }
        if (spec.getRewrites() != null && !spec.getRewrites().isEmpty()) {
            throw new IllegalStateException(
                    "rewrites (path rewrite) is not yet supported (spec: " + spec.getName() + ")");
        }
    }

    private static void validateRules(TransferSpec spec) {
        if (spec.getRules() == null || spec.getRules().isEmpty()) {
            throw new IllegalStateException("spec must contain at least one rule (spec: " + spec.getName() + ")");
        }
    }
}
