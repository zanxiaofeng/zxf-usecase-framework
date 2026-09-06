package com.example.datatransfer.test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.example.datatransfer.core.flatten.FlatMapProcessor;

import tools.jackson.databind.JsonNode;

/** {@code execute()} 之后的链式路径断言（设计文档 §10.4）。 */
public class AssertContext {

    private final Map<String, Object> actualFlatMap;
    private final JsonNode actualNested;
    private final List<String> ignorePaths;

    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();

    public AssertContext(JsonNode actualResult, List<String> ignorePaths) {
        this.actualNested = actualResult;
        this.actualFlatMap = flatProcessor.flatten(actualResult, ".");
        this.ignorePaths = ignorePaths;
    }

    /**
     * 断言指定路径存在（[*] 通配）。
     * 语义为「键或键的祖先」：路径可以是叶子键（{@code a.b}），
     * 也可以是容器前缀（{@code a.lines[*]} 匹配 {@code a.lines[0].unitPrice}）。
     */
    public AssertContext pathExists(String jsonPath) {
        if (!hasPath(jsonPath)) {
            throw new AssertionError("路径不存在: " + jsonPath
                    + "（实际所有路径: " + actualFlatMap.keySet() + "）");
        }
        return this;
    }

    /** 断言指定路径不存在（与 {@link #pathExists} 的祖先语义互补） */
    public AssertContext pathNotExists(String jsonPath) {
        if (hasPath(jsonPath)) {
            throw new AssertionError("路径不应存在: " + jsonPath);
        }
        return this;
    }

    private boolean hasPath(String jsonPath) {
        Pattern exact = TransferAssert.jsonPathToRegex(jsonPath);
        Pattern ancestor = TransferAssert.ancestorPathToRegex(jsonPath);
        return actualFlatMap.keySet().stream()
                .anyMatch(key -> exact.matcher(key).matches() || ancestor.matcher(key).matches());
    }

    /** 断言指定路径的值等于期望值 */
    public AssertContext pathValueEquals(String jsonPath, Object expected) {
        Object actual = resolveSingleValue(jsonPath);
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("路径 " + jsonPath + " 期望等于 " + expected + "，实际为 " + actual);
        }
        return this;
    }

    /** 断言指定路径的值匹配正则表达式 */
    public AssertContext pathValueMatches(String jsonPath, String regex) {
        Object actual = resolveSingleValue(jsonPath);
        if (actual == null || !Pattern.matches(regex, String.valueOf(actual))) {
            throw new AssertionError("路径 " + jsonPath + " 期望匹配正则 " + regex + "，实际为 " + actual);
        }
        return this;
    }

    /** 断言指定路径的值为数值且大于指定值 */
    public AssertContext pathValueGreaterThan(String jsonPath, double threshold) {
        Object actual = resolveSingleValue(jsonPath);
        if (!(actual instanceof Number number) || number.doubleValue() <= threshold) {
            throw new AssertionError("路径 " + jsonPath + " 期望大于 " + threshold + "，实际为 " + actual);
        }
        return this;
    }

    /** 断言指定路径的值为数值且小于指定值 */
    public AssertContext pathValueLessThan(String jsonPath, double threshold) {
        Object actual = resolveSingleValue(jsonPath);
        if (!(actual instanceof Number number) || number.doubleValue() >= threshold) {
            throw new AssertionError("路径 " + jsonPath + " 期望小于 " + threshold + "，实际为 " + actual);
        }
        return this;
    }

    /** 断言指定路径的值包含指定子串 */
    public AssertContext pathValueContains(String jsonPath, String substring) {
        Object actual = resolveSingleValue(jsonPath);
        if (actual == null || !String.valueOf(actual).contains(substring)) {
            throw new AssertionError("路径 " + jsonPath + " 期望包含 \"" + substring + "\"，实际为 " + actual);
        }
        return this;
    }

    /** 断言指定路径的值在指定集合中 */
    public AssertContext pathValueIn(String jsonPath, Object... candidates) {
        Object actual = resolveSingleValue(jsonPath);
        Set<Object> candidateSet = new HashSet<>(Arrays.asList(candidates));
        if (!candidateSet.contains(actual)) {
            throw new AssertionError("路径 " + jsonPath + " 期望在集合 " + candidateSet + " 中，实际为 " + actual);
        }
        return this;
    }

    /** 断言指定路径的值不为 null */
    public AssertContext pathValueNotNull(String jsonPath) {
        Object actual = resolveSingleValue(jsonPath);
        if (actual == null) {
            throw new AssertionError("路径 " + jsonPath + " 期望不为 null");
        }
        return this;
    }

    /** 断言匹配指定路径的所有值都满足断言（至少要有一个匹配） */
    public AssertContext allPathValues(String jsonPath, Consumer<Object> valueAssert) {
        Pattern pattern = TransferAssert.jsonPathToRegex(jsonPath);
        List<Map.Entry<String, Object>> matched = actualFlatMap.entrySet().stream()
                .filter(e -> pattern.matcher(e.getKey()).matches())
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            throw new AssertionError("路径 " + jsonPath + " 期望至少有一个匹配值，实际无匹配路径");
        }
        AtomicReference<Object> failingValue = new AtomicReference<>();
        RuntimeException failure = null;
        for (Map.Entry<String, Object> entry : matched) {
            try {
                valueAssert.accept(entry.getValue());
            } catch (RuntimeException e) {
                failingValue.set(entry.getValue());
                failure = e;
                break;
            }
        }
        if (failure != null) {
            throw new AssertionError("路径 " + jsonPath + " 的值 " + failingValue.get()
                    + " 不满足断言: " + failure.getMessage());
        }
        return this;
    }

    /** 获取原始输出结果 */
    public JsonNode getActualResult() {
        return actualNested;
    }

    /** 获取拍平后的结果 */
    public Map<String, Object> getActualFlatMap() {
        return Collections.unmodifiableMap(actualFlatMap);
    }

    /** ignorePaths 透传（execute() 前声明的动态路径排除） */
    List<String> ignorePaths() {
        return ignorePaths;
    }

    private Object resolveSingleValue(String jsonPath) {
        Pattern pattern = TransferAssert.jsonPathToRegex(jsonPath);
        List<Map.Entry<String, Object>> matched = actualFlatMap.entrySet().stream()
                .filter(e -> pattern.matcher(e.getKey()).matches())
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return null;
        }
        if (matched.size() > 1) {
            throw new AssertionError("路径 " + jsonPath + " 匹配到多个值，请使用 allPathValues()："
                    + matched.stream()
                        .map(e -> "  " + e.getKey() + " = " + e.getValue())
                        .collect(Collectors.joining("\n")));
        }
        return matched.get(0).getValue();
    }
}
