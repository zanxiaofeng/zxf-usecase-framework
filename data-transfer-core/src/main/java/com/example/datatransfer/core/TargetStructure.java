package com.example.datatransfer.core;

import java.util.ArrayList;
import java.util.List;

import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.flatten.PathParser;

/**
 * 目标键结构的装配期静态分析（外部评审二轮 评审二-10）：把规则 to / computed.to / defaults.to
 * 统一为「规范化路径模式」两两比对。
 *
 * <ul>
 *   <li><b>结构冲突</b>（祖先：同一路径既是值又是容器；数组 vs 对象：同一前缀被要求为两种容器）
 *       无论是否 strictMode 一律拒绝——历史行为是运行期裸抛 ClassCastException；</li>
 *   <li><b>重复目标</b>（两条声明的路径模式完全相同）在 strictMode 下拒绝；索引按规范化比较
 *       （{@code t.k[01]} 与 {@code t.k[1]} 是同一目标）。非 strict 维持「后覆盖前」；</li>
 *   <li>模式不同但展开后可能重叠的声明（通配目标与字面目标）留到运行期写入前判定。</li>
 * </ul>
 */
final class TargetStructure {

    /** 冲突类型 */
    private enum Kind {
        /** 路径模式完全相同 */
        DUPLICATE,
        /** 模式重叠但不相同（通配 vs 字面索引）：运行期再判 */
        OVERLAP,
        /** 一条路径是另一条的前缀：值与容器冲突 */
        ANCESTOR,
        /** 同一前缀既被要求数组又被要求对象 */
        SHAPE
    }

    /** 比对结论：冲突类型 + 冲突发生的段下标 */
    private record Conflict(Kind kind, int position) {
    }

    /** 一条目标声明：所属者描述 + 规范化前的路径 + 解析后的段模式 */
    record Declaration(String owner, String path, List<Object> pattern) {

        String normalized() {
            return PathParser.toPath(pattern, ".");
        }
    }

    private TargetStructure() {
    }

    static void validate(List<CompiledPlan.Rule> rules, List<CompiledPlan.Computed> computed,
                         List<CompiledPlan.Default> defaults, boolean strictMode, String specName) {
        List<Declaration> declarations = new ArrayList<>(rules.size() + computed.size() + defaults.size());
        rules.forEach(rule -> declarations.add(new Declaration(rule.describe(), rule.to(), rule.toPattern())));
        computed.forEach(field -> declarations.add(new Declaration(field.describe(), field.to(), field.toPattern())));
        defaults.forEach(value -> declarations.add(new Declaration(value.describe(), value.to(), value.toPattern())));

        for (int i = 0; i < declarations.size(); i++) {
            for (int j = i + 1; j < declarations.size(); j++) {
                Conflict conflict = conflict(declarations.get(i), declarations.get(j));
                if (conflict == null) {
                    continue;
                }
                switch (conflict.kind()) {
                    case DUPLICATE -> {
                        if (strictMode) {
                            throw new TransferAssemblyException(
                                    duplicateMessage(declarations.get(i), declarations.get(j), specName));
                        }
                    }
                    case ANCESTOR -> throw new TransferAssemblyException(
                            ancestorMessage(declarations.get(i), declarations.get(j), specName));
                    case SHAPE -> throw new TransferAssemblyException(
                            shapeMessage(declarations.get(i), declarations.get(j), conflict.position(), specName));
                    default -> { /* OVERLAP：运行期写入前判定 */ }
                }
            }
        }
    }

    private static Conflict conflict(Declaration first, Declaration second) {
        List<Object> x = first.pattern();
        List<Object> y = second.pattern();
        int limit = Math.min(x.size(), y.size());
        for (int i = 0; i < limit; i++) {
            boolean xIndex = isIndex(x.get(i));
            boolean yIndex = isIndex(y.get(i));
            if (xIndex != yIndex) {
                return new Conflict(Kind.SHAPE, i);
            }
            if (!xIndex && !x.get(i).equals(y.get(i))) {
                return null;   // 不同的对象键：互不相干
            }
            if (x.get(i) instanceof Integer left && y.get(i) instanceof Integer right && !left.equals(right)) {
                return null;   // 不同的字面索引：互不相干
            }
        }
        if (x.size() != y.size()) {
            return new Conflict(Kind.ANCESTOR, limit);
        }
        return new Conflict(x.equals(y) ? Kind.DUPLICATE : Kind.OVERLAP, limit);
    }

    private static boolean isIndex(Object segment) {
        return segment instanceof Integer || PathParser.WILDCARD.equals(segment);
    }

    private static String duplicateMessage(Declaration first, Declaration second, String specName) {
        boolean wildcard = first.pattern().stream().anyMatch(PathParser.WILDCARD::equals);
        return "strictMode: duplicate " + (wildcard ? "wildcard target '" : "literal target key '")
                + first.normalized() + "' mapped by " + first.owner() + " and " + second.owner()
                + " (spec: " + specName + ")";
    }

    private static String ancestorMessage(Declaration first, Declaration second, String specName) {
        Declaration value = first.pattern().size() <= second.pattern().size() ? first : second;
        Declaration container = value == first ? second : first;
        return "target path conflict: '" + value.normalized() + "' (" + value.owner()
                + ") is written as a value but '" + container.normalized() + "' (" + container.owner()
                + ") requires a container under it (spec: " + specName + ")";
    }

    private static String shapeMessage(Declaration first, Declaration second, int position, String specName) {
        String prefix = PathParser.toPath(first.pattern().subList(0, position + 1), ".");
        return "target path conflict: '" + first.normalized() + "' (" + first.owner() + ") requires "
                + (isIndex(first.pattern().get(position)) ? "an array" : "an object") + " at '" + prefix
                + "' but '" + second.normalized() + "' (" + second.owner() + ") requires "
                + (isIndex(second.pattern().get(position)) ? "an array" : "an object") + " (spec: " + specName + ")";
    }
}
