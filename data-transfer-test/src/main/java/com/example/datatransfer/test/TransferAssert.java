package com.example.datatransfer.test;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.example.datatransfer.core.TransferEngine;
import com.example.datatransfer.core.flatten.FlatMapProcessor;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.transform.TransformFunction;
import com.example.datatransfer.core.validation.TransferSpecValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TransferSpec 契约测试工具（设计文档 §10.3）：spec + fixture → 引擎执行 →
 * 与期望输出的 FlatMap 级 diff（STRICT / PARTIAL 两种模式，ignorePaths 排除动态路径）。
 *
 * <p>断言路径统一 FlatKey 风格（与引擎键格式一致，{@code [*]} 为通配符；
 * {@link #jsonPathToRegex} 兼容剥离可选的 {@code $.} JSONPath 根前缀）。
 * 断言工具复用引擎的同一拍平实现（{@link FlatMapProcessor}），保证键格式完全一致。</p>
 */
public class TransferAssert {

    private final TransferSpec spec;
    private String fixtureJson;
    private final List<String> ignorePaths = new ArrayList<>();
    private final Map<String, TransformFunction> customFunctions = new HashMap<>();
    private CompareMode compareMode = CompareMode.STRICT;

    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public enum CompareMode {
        /** 严格模式：输出必须与期望完全一致，不允许多余字段 */
        STRICT,
        /** 宽松模式：输出包含期望中的所有字段即可，允许多余字段 */
        PARTIAL
    }

    private TransferAssert(TransferSpec spec) {
        this.spec = Objects.requireNonNull(spec, "TransferSpec must not be null");
    }

    // ===== 静态工厂方法 =====

    /** spec 资源位置：classpath 优先、文件系统兜底（.yaml/.yml 走 YAML mapper） */
    public static TransferAssert assertThat(String specResource) {
        return new TransferAssert(loadSpecFromResource(specResource));
    }

    public static TransferAssert assertThat(TransferSpec spec) {
        return new TransferAssert(spec);
    }

    public static BatchAssert batchAssert(String specResource) {
        return new BatchAssert(loadSpecFromResource(specResource));
    }

    // ===== 配置方法 =====

    public TransferAssert withFixture(String fixtureResource) {
        this.fixtureJson = loadResourceAsString(fixtureResource);
        return this;
    }

    public TransferAssert withFixtureJson(String json) {
        this.fixtureJson = json;
        return this;
    }

    public TransferAssert ignorePaths(String... paths) {
        this.ignorePaths.addAll(Arrays.asList(paths));
        return this;
    }

    public TransferAssert registerFunction(String name, TransformFunction function) {
        this.customFunctions.put(name, function);
        return this;
    }

    // ===== 断言方法 =====

    /** 精确匹配（STRICT）：输出与期望完全一致 */
    public void matchesExpected(String expectedResource) {
        this.compareMode = CompareMode.STRICT;
        doAssert(loadResourceAsString(expectedResource));
    }

    public void matchesExpectedJson(String expectedJson) {
        this.compareMode = CompareMode.STRICT;
        doAssert(expectedJson);
    }

    /** 部分匹配（PARTIAL）：输出包含期望中的所有字段，允许多余字段 */
    public void partiallyMatches(String expectedResource) {
        this.compareMode = CompareMode.PARTIAL;
        doAssert(loadResourceAsString(expectedResource));
    }

    public void partiallyMatchesJson(String expectedJson) {
        this.compareMode = CompareMode.PARTIAL;
        doAssert(expectedJson);
    }

    /** 执行引擎并返回链式路径断言上下文 */
    public AssertContext execute() {
        Objects.requireNonNull(fixtureJson, "Fixture must be set before execute()");
        JsonNode result = buildEngine().transfer(fixtureJson);
        return new AssertContext(result, ignorePaths);
    }

    // ===== 内部方法 =====

    private void doAssert(String expectedJson) {
        Objects.requireNonNull(fixtureJson, "Fixture must be set before assertion");

        JsonNode actualResult = buildEngine().transfer(fixtureJson);
        String actualJson = mapper.writeValueAsString(actualResult);

        Map<String, Object> expectedFlat = flatProcessor.flatten(mapper.readTree(expectedJson), ".");
        Map<String, Object> actualFlat = flatProcessor.flatten(mapper.readTree(actualJson), ".");

        removeIgnoredPaths(expectedFlat);
        removeIgnoredPaths(actualFlat);

        DiffResult diff = DiffEngine.diff(expectedFlat, actualFlat, compareMode);
        if (!diff.isEmpty()) {
            throw new AssertionError(buildFailureMessage(diff, actualJson));
        }
    }

    private TransferEngine buildEngine() {
        return new TransferEngine(spec, customFunctions);
    }

    private void removeIgnoredPaths(Map<String, Object> flatMap) {
        for (String ignorePath : ignorePaths) {
            Pattern pattern = jsonPathToRegex(ignorePath);
            flatMap.keySet().removeIf(key -> pattern.matcher(key).matches());
        }
    }

    private String buildFailureMessage(DiffResult diff, String actualJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nTransferSpec 契约测试失败 [spec: ").append(spec.getName()).append("]\n");
        sb.append("差异明细:\n");
        for (DiffEntry entry : diff.getEntries()) {
            sb.append("  路径: ").append(entry.getPath())
                    .append(" | 类型: ").append(entry.getType())
                    .append(" | 期望: ").append(entry.getExpected())
                    .append(" | 实际: ").append(entry.getActual()).append('\n');
        }
        sb.append("实际输出: ").append(actualJson);
        return sb.toString();
    }

    // ===== 资源与路径工具 =====

    /** spec 加载统一走校验器：Schema 校验 + 大小写不敏感枚举绑定（nullPolicy: skip ≡ SKIP） */
    private static final TransferSpecValidator SPEC_VALIDATOR = new TransferSpecValidator();

    private static TransferSpec loadSpecFromResource(String resource) {
        try (InputStream in = getResourceStream(resource)) {
            return SPEC_VALIDATOR.validateAndLoad(in, resource);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load TransferSpec from: " + resource, e);
        }
    }

    private static String loadResourceAsString(String resource) {
        try (InputStream in = getResourceStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load resource: " + resource, e);
        }
    }

    private static InputStream getResourceStream(String resource) {
        InputStream in = TransferAssert.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            try {
                in = new FileInputStream(resource);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException(
                        "Resource not found in classpath or filesystem: " + resource);
            }
        }
        return in;
    }

    /**
     * 断言路径 → 匹配正则。统一路径风格：剥离可选的 JSONPath 根前缀 "$."；
     * 方括号（[*] 通配与 [0] 字面索引）统一经 {@link FlatMapProcessor#wildcardPattern} 转义。
     */
    static Pattern jsonPathToRegex(String path) {
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        return Pattern.compile(FlatMapProcessor.wildcardPattern(normalized));
    }

    /**
     * 断言路径 → 祖先前缀正则：匹配「以该路径为前缀的更深键」（容器存在性，
     * 供 {@link AssertContext#pathExists} 使用——{@code a.lines[*]} 应能命中
     * {@code a.lines[0].unitPrice}）。
     */
    static Pattern ancestorPathToRegex(String path) {
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        return Pattern.compile("^" + FlatMapProcessor.wildcardPattern(normalized) + "(\\..*)?$");
    }
}
