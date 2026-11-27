package com.example.datatransfer.core;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.flatten.PathParser;
import com.example.datatransfer.core.spec.ComputedField;
import com.example.datatransfer.core.spec.ConditionMapping;
import com.example.datatransfer.core.spec.DefaultValue;
import com.example.datatransfer.core.spec.IntermediateField;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.MissingPolicy;
import com.example.datatransfer.core.spec.NullPolicy;
import com.example.datatransfer.core.spec.TransferOptions;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.spec.ValidationMode;
import com.example.datatransfer.core.spec.ValidationRule;

/**
 * 引擎的不可变执行快照（设计文档 §4 的 CompiledPlan）：构造期从可变的 {@link TransferSpec} 深拷贝而来，
 * 其后对 spec 的任何修改（增删规则、改策略、加未支持特性）都不影响已构造的引擎——装配期校验的结论
 * 不会被绕过（外部评审二轮 评审二-11）。嵌套的规则、条件分支、断言同样是不可变 record。
 *
 * <p>装配期校验（fail-fast）也在此完成：未支持特性、通配数量、暂存区形态与引用、目标键结构。</p>
 */
final class CompiledPlan {

    /** 全局选项快照 */
    record Options(String separator, NullPolicy nullPolicy, MissingPolicy missingPolicy,
                   boolean strictMode, ValidationMode validationMode) {
    }

    /** 条件映射分支：condition + transform，或兜底 otherwise */
    record Branch(@Nullable String condition, @Nullable String transform, @Nullable String otherwise) {
    }

    /** 映射规则；to 的解析结果与规范化形态在装配期算好，运行期只对实际源键做索引对位 */
    record Rule(int index, String from, String to, String normalizedTo, @Nullable String transform,
                List<Branch> when, List<Object> toPattern, List<Integer> fromWildcardPositions,
                List<Integer> toWildcardPositions) {

        boolean wildcard() {
            return !fromWildcardPositions.isEmpty();
        }

        String describe() {
            return "rule #" + index + " [" + from + " -> " + to + "]";
        }
    }

    /** 中间结果（暂存区）：from + transform 或 expr 二选一 */
    record Staged(int index, String to, @Nullable String from, @Nullable String transform, @Nullable String expr) {

        String describe() {
            return "intermediate #" + index + " [" + to + "]";
        }
    }

    /** to 为规范化后的单键目标（{@code t.k[01]} 与 {@code t.k[1]} 是同一目标）；与规则的 to 同口径，写入与校验才指向同一个键 */
    record Computed(int index, String to, List<Object> toPattern, String expr) {

        String describe() {
            return "computed #" + index + " [" + to + "]";
        }
    }

    record Default(int index, String to, List<Object> toPattern, @Nullable Object value) {

        String describe() {
            return "defaults #" + index + " [" + to + "]";
        }
    }

    record Assertion(String expression, String message) {
    }

    /** 数据值校验：单字段断言列表或组合条件二选一 */
    record Validation(String path, @Nullable List<Assertion> assertions, @Nullable String condition,
                      @Nullable String message) {

        String describe() {
            return "validation [path: " + path + "]";
        }
    }

    /** expr/transform 参数中的暂存根引用（{@code $a.b} 提取 {@code $a}）；字符串字面量中的 $ident 会被误计，构造期即拒绝 */
    private static final Pattern STAGED_ROOT = Pattern.compile("\\$([a-zA-Z_][a-zA-Z0-9_]*)");

    /** transform 实参的暂存路径形态（未加引号；引号参数为字面量不代入） */
    private static final Pattern STAGED_ARG =
            Pattern.compile("^\\$[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*)*$");

    /** 暂存声明 to 的合法形态：{@code $} 根 + 纯点路径（不含通配/索引——单键语义） */
    private static final Pattern STAGED_TARGET =
            Pattern.compile("^\\$[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*)*$");

    private static final Pattern FUNCTION_CALL = Pattern.compile("^(\\w+)\\s*\\((.*)\\)$");

    private final String name;
    private final Options options;
    private final List<Rule> rules;
    private final List<Staged> staged;
    private final List<Computed> computed;
    private final List<Default> defaults;
    private final List<Validation> validations;

    private CompiledPlan(String name, Options options, List<Rule> rules, List<Staged> staged,
                         List<Computed> computed, List<Default> defaults, List<Validation> validations) {
        this.name = name;
        this.options = options;
        this.rules = rules;
        this.staged = staged;
        this.computed = computed;
        this.defaults = defaults;
        this.validations = validations;
    }

    /** 深拷贝 spec 并执行装配期校验；任一校验失败抛 {@link TransferAssemblyException} */
    static CompiledPlan compile(TransferSpec spec) {
        String name = spec.getName();
        TransferOptions source = spec.optionsOrNew();
        Options options = new Options(source.getSeparator(), source.getNullPolicy(), source.getMissingPolicy(),
                source.isStrictMode(), source.getValidationMode());
        rejectUnsupportedFeatures(spec, options, name);
        List<Rule> rules = compileRules(spec, options, name);
        List<Staged> staged = compileStaged(spec, name);
        List<Computed> computed = new ArrayList<>();
        List<ComputedField> computedFields = spec.computedOrEmpty();
        for (int i = 0; i < computedFields.size(); i++) {
            ComputedField field = computedFields.get(i);
            List<Object> toPattern = literalTarget(field.getTo(), "computed #" + i + " [" + field.getTo() + "]", name);
            computed.add(new Computed(i, PathParser.toPath(toPattern, "."), toPattern, field.getExpr()));
        }
        List<Default> defaults = new ArrayList<>();
        List<DefaultValue> defaultValues = spec.defaultsOrEmpty();
        for (int i = 0; i < defaultValues.size(); i++) {
            DefaultValue value = defaultValues.get(i);
            List<Object> toPattern = literalTarget(value.getTo(), "defaults #" + i + " [" + value.getTo() + "]", name);
            defaults.add(new Default(i, PathParser.toPath(toPattern, "."), toPattern, immutableValue(value.getValue())));
        }
        List<Validation> validations = compileValidations(spec);
        checkStagedReferences(rules, staged, computed, name);
        TargetStructure.validate(rules, computed, defaults, options.strictMode(), options.separator());
        return new CompiledPlan(name, options, rules, staged, computed, defaults, validations);
    }

    String name() {
        return name;
    }

    Options options() {
        return options;
    }

    List<Rule> rules() {
        return rules;
    }

    List<Staged> staged() {
        return staged;
    }

    List<Computed> computed() {
        return computed;
    }

    List<Default> defaults() {
        return defaults;
    }

    List<Validation> validations() {
        return validations;
    }

    /** 是否含 when 条件分支（决定 transfer 期是否预构建源嵌套视图，review P1 性能项） */
    boolean hasConditionalRules() {
        return rules.stream().anyMatch(rule -> !rule.when().isEmpty());
    }

    // ---------------------------------------------------------------------
    // 装配期校验
    // ---------------------------------------------------------------------

    private static void rejectUnsupportedFeatures(TransferSpec spec, Options options, String name) {
        if (spec.getSources() != null && !spec.getSources().isEmpty()) {
            throw new TransferAssemblyException(
                    "sources (multi-source merge) is not yet supported (spec: " + name + ")");
        }
        if (spec.getRewrites() != null && !spec.getRewrites().isEmpty()) {
            throw new TransferAssemblyException(
                    "rewrites (path rewrite) is not yet supported (spec: " + name + ")");
        }
        // review P1：表达式侧（computed/when/condition 的 JEXL 求值）按 "." 拍还原嵌套视图，
        // 自定义 separator 下会静默失配（校验假阴性）——fail-fast 而非静默，对齐 sources/rewrites 哲学
        if (!".".equals(options.separator())) {
            throw new TransferAssemblyException(
                    "custom separator '" + options.separator()
                            + "' is not yet supported (expression evaluation assumes '.') (spec: "
                            + name + ")");
        }
    }

    private static List<Rule> compileRules(TransferSpec spec, Options options, String name) {
        List<MappingRule> source = spec.getRules();
        if (source == null || source.isEmpty()) {
            throw new TransferAssemblyException("spec must contain at least one rule (spec: " + name + ")");
        }
        List<Rule> rules = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            rules.add(compileRule(source.get(i), i, options.separator(), name));
        }
        return List.copyOf(rules);
    }

    private static Rule compileRule(MappingRule source, int index, String separator, String specName) {
        String from = source.getFrom();
        String to = source.getTo();
        // from 与 to 的 [*] 数量必须一致（Schema 仅约束"有无对齐"，数量不一致时多级对位会静默错位——评审 1.1）
        List<Object> fromPattern = parsePattern(from, "rule #" + index, specName);
        List<Object> toPattern = parsePattern(to, "rule #" + index, specName);
        // [*] 在 from 与 to 中的段位置可以不同：按出现顺序（第 k 个）对位，见 §6.1
        List<Integer> fromWildcardPositions = wildcardPositions(fromPattern);
        List<Integer> toWildcardPositions = wildcardPositions(toPattern);
        if (fromWildcardPositions.size() != toWildcardPositions.size()) {
            throw new TransferAssemblyException(
                    ("rule #%d [%s -> %s]: wildcard count mismatch (from has %d [*], to has %d); "
                            + "indexed alignment requires equal counts)")
                            .formatted(index, from, to, fromWildcardPositions.size(), toWildcardPositions.size()));
        }
        List<Branch> branches = new ArrayList<>();
        for (ConditionMapping mapping : source.getWhen() == null ? List.<ConditionMapping>of() : source.getWhen()) {
            branches.add(new Branch(mapping.getCondition(), mapping.getTransform(), mapping.getOtherwise()));
        }
        return new Rule(index, from, to, PathParser.toPath(toPattern, separator), source.getTransform(),
                List.copyOf(branches), List.copyOf(toPattern), List.copyOf(fromWildcardPositions),
                List.copyOf(toWildcardPositions));
    }


    private static List<Integer> wildcardPositions(List<Object> pattern) {
        List<Integer> positions = new ArrayList<>();
        for (int position = 0; position < pattern.size(); position++) {
            if (PathParser.WILDCARD.equals(pattern.get(position))) {
                positions.add(position);
            }
        }
        return positions;
    }

    /**
     * computed / defaults 的目标是单键语义：含 [*] 时历史行为输出名为 "*" 的对象，装配期拒绝；
     * 返回规范化用的段模式（索引按数值比较，{@code [01]} 与 {@code [1]} 是同一段）。
     */
    private static List<Object> literalTarget(String to, String owner, String name) {
        if (to == null || to.isBlank() || to.contains(PathParser.WILDCARD)) {
            throw new TransferAssemblyException(owner
                    + ": target must be a literal path without [*] (computed and defaults write a single key; "
                    + "expand containers via intermediate) (spec: " + name + ")");
        }
        return parsePattern(to, owner, name);
    }

    /** 路径文本 → 段模式；语法非法时抛装配期异常（而非裸 IllegalArgumentException） */
    private static List<Object> parsePattern(String path, String owner, String name) {
        try {
            return List.copyOf(PathParser.parsePattern(path, "."));
        } catch (IllegalArgumentException invalidPath) {
            throw new TransferAssemblyException(owner + ": '" + path + "' is not a valid path expression (spec: "
                    + name + ")", invalidPath);
        }
    }

    /**
     * 构造期校验：
     * <ol>
     *   <li>to 形态：{@code $} 前缀 + 纯点路径，根声明唯一（{@code $a} 与 {@code $a.b} 同根冲突）；</li>
     *   <li>形态二选一：from（禁 {@code [*]}，数组派生用 expr）与 expr 互斥；</li>
     *   <li>前向引用：intermediate 的 from/expr、rules 的 from、transform 实参、computed 的 expr
     *       中的 {@code $root} 引用必须已在<b>更早的</b> intermediate 中声明——声明序即 DAG，
     *       未声明/后向引用一律拒绝。</li>
     * </ol>
     */
    private static List<Staged> compileStaged(TransferSpec spec, String name) {
        List<IntermediateField> fields = spec.intermediateOrEmpty();
        List<Staged> staged = new ArrayList<>(fields.size());
        Set<String> declaredRoots = new LinkedHashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            IntermediateField field = fields.get(i);
            String to = field.getTo();
            if (to == null || !STAGED_TARGET.matcher(to).matches()) {
                throw new TransferAssemblyException(
                        "intermediate #" + i + ": 'to' must be a '$'-prefixed dotted path without "
                                + "wildcards/indexes, was '" + to + "' (spec: " + name + ")");
            }
            String root = stagedRoot(to);
            if (!declaredRoots.add(root)) {
                throw new TransferAssemblyException(
                        "intermediate #" + i + ": duplicate staged root '" + root + "' (spec: " + name + ")");
            }
            String from = blankToNull(field.getFrom());
            String expr = blankToNull(field.getExpr());
            if ((from == null) == (expr == null)) {
                throw new TransferAssemblyException(
                        "intermediate #" + i + " [" + to + "]: exactly one of 'from' or 'expr' "
                                + "is required (spec: " + name + ")");
            }
            List<String> references = new ArrayList<>();
            if (from != null) {
                if (from.contains(PathParser.WILDCARD)) {
                    throw new TransferAssemblyException(
                            "intermediate #" + i + " [" + to + "]: 'from' does not support "
                                    + "[*] (array derivation uses 'expr') (spec: " + name + ")");
                }
                references.add(from);
            }
            references.add(expr);
            for (String reference : references) {
                requireDeclaredBefore(reference, declaredRoots, "intermediate #" + i + " [" + to + "]", name);
            }
            staged.add(new Staged(i, to, from, field.getTransform(), expr));
        }
        return List.copyOf(staged);
    }

    /** rules 的 from / transform 实参与 computed 的 expr 可引用任一已声明的暂存键 */
    private static void checkStagedReferences(List<Rule> rules, List<Staged> staged,
                                              List<Computed> computed, String name) {
        Set<String> declaredRoots = new LinkedHashSet<>();
        staged.forEach(field -> declaredRoots.add(stagedRoot(field.to())));
        for (Rule rule : rules) {
            if (rule.from().startsWith("$")) {
                requireDeclared(rule.from(), declaredRoots, "rule #" + rule.index(), name);
            }
            requireDeclaredInTransform(rule.transform(), declaredRoots, "rule #" + rule.index(), name);
            for (Branch branch : rule.when()) {
                requireDeclaredInTransform(branch.transform(), declaredRoots, "rule #" + rule.index(), name);
                requireDeclaredInTransform(branch.otherwise(), declaredRoots, "rule #" + rule.index(), name);
            }
        }
        for (Computed field : computed) {
            for (String referenced : stagedRoots(field.expr())) {
                if (!declaredRoots.contains(referenced)) {
                    throw new TransferAssemblyException(
                            ("computed #%d [%s]: references staged key '%s' that is not declared by any "
                                    + "intermediate (spec: %s)")
                                    .formatted(field.index(), field.to(), referenced, name));
                }
            }
        }
    }

    private static List<Validation> compileValidations(TransferSpec spec) {
        List<Validation> validations = new ArrayList<>();
        for (ValidationRule rule : spec.validationsOrEmpty()) {
            List<com.example.datatransfer.core.spec.Assertion> sources =
                    rule.getRules() == null ? List.of() : rule.getRules();
            List<Assertion> assertions = new ArrayList<>(sources.size());
            for (com.example.datatransfer.core.spec.Assertion source : sources) {
                assertions.add(new Assertion(source.getAssertExpr(), source.getMessage()));
            }
            validations.add(new Validation(rule.getPath(), rule.getRules() == null ? null : List.copyOf(assertions),
                    rule.getCondition(), rule.getMessage()));
        }
        return List.copyOf(validations);
    }

    /** transform 链中 {@code $path} 形态实参的声明存在性校验（引号字面量不校验——parseArgs 已剥引号，此处放行） */
    private static void requireDeclaredInTransform(@Nullable String chain, Set<String> declaredRoots,
                                                   String owner, String name) {
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
                    requireDeclared(candidate, declaredRoots, owner, name);
                }
            }
        }
    }

    /** 引用必须由<b>更早的</b> intermediate 声明（声明序即 DAG：不做拓扑排序，后向引用拒绝） */
    private static void requireDeclaredBefore(@Nullable String reference, Set<String> declaredSoFar,
                                              String owner, String name) {
        if (reference == null) {
            return;
        }
        for (String referenced : stagedRoots(reference)) {
            if (!declaredSoFar.contains(referenced)) {
                throw new TransferAssemblyException(
                        ("%s: references staged key '%s' that is not declared "
                                + "by an earlier intermediate (forward references and undeclared keys "
                                + "are rejected) (spec: %s)")
                                .formatted(owner, referenced, name));
            }
        }
    }

    private static void requireDeclared(String stagedPath, Set<String> declaredRoots, String owner, String name) {
        String root = stagedRoot(stagedPath);
        if (!declaredRoots.contains(root)) {
            throw new TransferAssemblyException(
                    owner + ": references staged key '" + root + "' that is not declared by any "
                            + "intermediate (spec: " + name + ")");
        }
    }

    /**
     * 暂存路径/表达式的根引用：{@code $a.b} → {@code $a}；{@code $x[*].f} / $lst[1]} → {@code $x} / {@code $lst}。
     * 切分到首个 {@code .} 或 {@code [} 为止（只按 {@code .} 切会把带索引的引用误判为未声明）。
     */
    static String stagedRoot(String stagedPath) {
        int dot = stagedPath.indexOf('.');
        int bracket = stagedPath.indexOf('[');
        int cut = dot < 0 ? bracket : bracket < 0 ? dot : Math.min(dot, bracket);
        return cut < 0 ? stagedPath : stagedPath.substring(0, cut);
    }

    /**
     * 提取表达式中全部 {@code $ident} 根引用（去重、保序）。引号字符串字面量里的内容不参与检查——
     * {@code '$price=' + $rate} 只引用了 {@code $rate}。正则字面量（{@code =~ /$x/}）中的 {@code $} 仍会被计入（已知限制）。
     */
    static List<String> stagedRoots(String text) {
        List<String> roots = new ArrayList<>();
        Matcher matcher = STAGED_ROOT.matcher(withoutStringLiterals(text));
        while (matcher.find()) {
            String root = "$" + matcher.group(1);
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    /** 去掉引号字符串字面量后的表达式文本 */
    static String withoutStringLiterals(String text) {
        StringBuilder code = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"') {
                index = skipStringLiteral(text, index);
                continue;
            }
            code.append(current);
            index++;
        }
        return code.toString();
    }

    /** 跳过从 {@code start} 处的引号开始的字符串字面量（含闭合引号），返回其后下标；未闭合时交由表达式语法校验报告 */
    private static int skipStringLiteral(String text, int start) {
        char quote = text.charAt(start);
        int index = start + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\') {
                index += 2;   // 转义序列（如 \'）
                continue;
            }
            if (current == quote) {
                return index + 1;
            }
            index++;
        }
        return index;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** defaults 值的不可变快照：标量原样，Map / List / 数组递归转为不可变（其余对象类型不在 Schema 允许范围内，按引用保留） */
    private static @Nullable Object immutableValue(@Nullable Object value) {
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                copy.add(immutableValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, element) -> copy.put(key, immutableValue(element)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(element -> copy.add(immutableValue(element)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
