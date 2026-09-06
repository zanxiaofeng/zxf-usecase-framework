package com.example.datatransfer.core.flatten;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.github.wnameless.json.base.Jackson3JsonValue;
import com.github.wnameless.json.flattener.JsonFlattener;
import com.github.wnameless.json.unflattener.JsonUnflattener;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flatten/Unflatten 门面（设计文档 §8.4）：复用 json-flattener 0.18.2
 * （v0.18.0+ 原生 Jackson 3——{@link Jackson3JsonValue} 直接包装 {@link JsonNode}，零字符串往返；
 * {@link JsonUnflattener#unflattenAsMap(Map)} Map 直达还原）。通配符展开为 DSL 特有能力，自研。
 *
 * <p>保留字符语义：源键含 {@code .} / {@code [} 时 json-flattener 默认转义为
 * {@code matrix["agent.smith"]} 记法——此类键不属于路径语法、天然不被通配符匹配（即"不可映射"）。</p>
 */
public final class FlatMapProcessor {

    /** 反序列化开启 BigDecimal，保证金额精度语义（设计文档 §5.3-4） */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** 解析/序列化统一入口：调用方的 readTree 与 flatten/unflatten 统一走该实例，BigDecimal 语义贯穿 */
    public JsonMapper mapper() {
        return mapper;
    }

    /** 将嵌套 JSON 树拍平为 {FlatKey: leaf}（设计文档 §5.1 算法） */
    public Map<String, Object> flatten(JsonNode root, String separator) {
        return new JsonFlattener(new Jackson3JsonValue(root))
                .withSeparator(separator.charAt(0))
                .flattenAsMap();
    }

    /** 通配符展开（DSL 特有，自研）：将带 [*] 的路径展开为所有匹配的键值对（保持文档序） */
    public List<Map.Entry<String, Object>> expandWildcard(String wildcardPath, Map<String, Object> flatMap) {
        Pattern pattern = Pattern.compile(wildcardPattern(wildcardPath));
        return flatMap.entrySet().stream()
                .filter(e -> pattern.matcher(e.getKey()).matches())
                .toList();
    }

    /**
     * 规则/断言路径（可含 {@code [*]} 通配与 {@code [0]} 字面索引）→ 匹配正则：
     * {@code [*]} 展开为数字索引，字面方括号与其他元字符转义（方括号是正则元字符，
     * 直接拼接会把 {@code [0]} 当字符类——设计文档 §8.4 注记的论断已在实测中修正）。
     */
    public static String wildcardPattern(String path) {
        return path
                .replace("[*]", "__WILDCARD__")
                .replace(".", "\\.")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("__WILDCARD__", "\\[\\d+\\]");
    }

    /** 将扁平 Map 还原为嵌套 JSON 树（设计文档 §5.2 算法，容器类型推断由库完成） */
    public JsonNode unflatten(Map<String, Object> flatMap, String separator) {
        return mapper.valueToTree(unflattenToMap(flatMap, separator));
    }

    /** 还原为嵌套 Java Map（表达式求值的嵌套视图绑定用，避免再经 JsonNode 往返） */
    public Map<String, Object> unflattenToMap(Map<String, Object> flatMap, String separator) {
        return new JsonUnflattener(flatMap)
                .withSeparator(separator.charAt(0))
                .unflattenAsMap();
    }
}
