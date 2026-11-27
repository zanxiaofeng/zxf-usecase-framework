package com.example.datatransfer.core.flatten;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import com.github.wnameless.json.base.Jackson3JsonCore;
import com.github.wnameless.json.base.Jackson3JsonValue;
import com.github.wnameless.json.flattener.JsonFlattener;
import com.github.wnameless.json.unflattener.JsonUnflattener;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.example.datatransfer.core.exception.RuleMatchException;

/**
 * Flatten/Unflatten 门面（设计文档 §8.4）：flatten 复用 json-flattener 0.18.2
 * （v0.18.0+ 原生 Jackson 3——{@link Jackson3JsonValue} 直接包装 {@link JsonNode}，零字符串往返）；
 * unflatten 由本类自建（不经 JSON 文本往返，数值类型全程可控），仅保留字符转义记法的键回退到库语义。
 * 通配符展开为 DSL 特有能力，自研。
 *
 * <p><b>数值完整性</b>（外部评审二轮 P1-5）：json-base 的 {@code toJavaNumber} 会把「可经 double 往返」
 * 的小数（0.1 / 8.2 / 113.0）降为 {@code Double}，库的 {@code unflattenAsMap} 又经其默认 mapper 做
 * JSON 文本往返把 BigDecimal 全部变成 double（恒等映射即丢精度）。本类的约定：</p>
 * <ul>
 *   <li>flatten 出口：小数一律 {@link BigDecimal}，整数维持 Integer / Long / BigInteger 窄化；</li>
 *   <li>{@link #unflattenToMap}：数值保持精确类型，容器为 {@code LinkedHashMap} / {@code ArrayList}
 *       （表达式沙箱 allow 清单的依据）；</li>
 *   <li>{@link #unflatten}：可经 double 无损往返的值输出 double 形态（与历史行为一致，如 {@code 113.0}），
 *       超出 double 精度的值以全精度 BigDecimal 输出。</li>
 * </ul>
 *
 * <p>保留字符语义：源键含 {@code .} / {@code [} 时 json-flattener 默认转义为
 * {@code matrix["agent.smith"]} 记法——此类键不属于路径语法、天然不被通配符匹配（即"不可映射"）。</p>
 */
public final class FlatMapProcessor {

    /** 反序列化开启 BigDecimal，保证金额精度语义（设计文档 §5.3-4） */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** 规则/断言路径 → 匹配正则（路径来自 spec 配置，集合有限；每次展开不再重编译） */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /** 解析/序列化统一入口：调用方的 readTree 与 flatten/unflatten 统一走该实例，BigDecimal 语义贯穿 */
    public JsonMapper mapper() {
        return mapper;
    }

    /**
     * 将嵌套 JSON 树拍平为 {FlatKey: leaf}（设计文档 §5.1 算法）。
     * 小数统一还原为 {@link BigDecimal}：被库降为 Double 的小数必能经 {@code Double.toString} 无损还原
     * （库仅在十进制文本可往返时才降级）。
     */
    public Map<String, Object> flatten(JsonNode root, String separator) {
        Map<String, Object> flat = new JsonFlattener(new Jackson3JsonValue(root))
                .withSeparator(separator.charAt(0))
                .flattenAsMap();
        flat.replaceAll((key, value) -> value instanceof Double number ? BigDecimal.valueOf(number) : value);
        return flat;
    }

    /** 通配符展开（DSL 特有，自研）：将带 [*] 的路径展开为所有匹配的键值对（保持文档序） */
    public List<Map.Entry<String, Object>> expandWildcard(String wildcardPath, Map<String, Object> flatMap) {
        Pattern pattern = patternCache.computeIfAbsent(wildcardPattern(wildcardPath), Pattern::compile);
        return flatMap.entrySet().stream()
                .filter(e -> pattern.matcher(e.getKey()).matches())
                .toList();
    }

    /**
     * 规则/断言路径（可含 {@code [*]} 通配与 {@code [0]} 字面索引）→ 匹配正则：
     * {@code [*]} 展开为数字索引，字面方括号与其他元字符转义（方括号是正则元字符，
     * 直接拼接会把 {@code [0]} 当字符类——设计文档 §8.4 注记的论断已在实测中修正）；
     * {@code $} 同为正则元字符（行尾锚），不转义会使 {@code $} 暂存前缀路径永不匹配（§6.8）。
     */
    public static String wildcardPattern(String path) {
        return path
                .replace("[*]", "__WILDCARD__")
                .replace(".", "\\.")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("$", "\\$")
                .replace("__WILDCARD__", "\\[\\d+\\]");
    }

    /**
     * 将扁平 Map 还原为嵌套 JSON 树（设计文档 §5.2 算法，容器类型由路径段推断、数组槽位以 null 垫满）。
     * 输出数值形态与历史行为一致：可经 double 无损往返的小数输出 double 形态。
     */
    public JsonNode unflatten(Map<String, Object> flatMap, String separator) {
        return mapper.valueToTree(unflattenInternal(flatMap, separator, FlatMapProcessor::outputNumber));
    }

    /**
     * 还原为嵌套 Java Map（表达式求值的嵌套视图绑定用）：数值保持精确类型，
     * 容器为 {@code LinkedHashMap} / {@code ArrayList}。
     */
    public Map<String, Object> unflattenToMap(Map<String, Object> flatMap, String separator) {
        return unflattenInternal(flatMap, separator, FlatMapProcessor::exactNumber);
    }

    private Map<String, Object> unflattenInternal(Map<String, Object> flatMap, String separator,
                                                  UnaryOperator<Object> numberShape) {
        List<List<Object>> parsed = new ArrayList<>(flatMap.size());
        for (String key : flatMap.keySet()) {
            List<Object> segments = parseOrFail(key, separator);
            if (segments == null || segments.isEmpty() || segments.get(0) instanceof Integer) {
                // 保留字符转义记法（如 matrix["agent.smith"]）与根级数组不属于 DSL 路径语法：沿用库的还原语义
                return libraryUnflatten(flatMap, separator, numberShape);
            }
            parsed.add(segments);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Object> entry : flatMap.entrySet()) {
            insert(root, parsed.get(index++), entry.getKey(), entry.getValue(), separator, numberShape);
        }
        return root;
    }

    private static List<Object> parseOrFail(String key, String separator) {
        try {
            return PathParser.parse(key, separator);
        } catch (IllegalArgumentException reservedOrMalformed) {
            return null;
        }
    }

    /** 回退路径：键无法按路径语法解析时交给 json-flattener（用项目的 BigDecimal mapper，保住大数精度） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> libraryUnflatten(Map<String, Object> flatMap, String separator,
                                                 UnaryOperator<Object> numberShape) {
        Map<String, Object> nested = new JsonUnflattener(new Jackson3JsonCore(mapper), flatMap)
                .withSeparator(separator.charAt(0))
                .unflattenAsMap();
        return (Map<String, Object>) reshape(nested, numberShape);
    }

    /** 逐段下潜到目标容器并写入叶子；路径段之间的类型不一致即结构冲突（fail-fast，不静默覆盖） */
    private void insert(Object container, List<Object> segments, String key, Object value,
                        String separator, UnaryOperator<Object> numberShape) {
        for (int i = 0; i < segments.size() - 1; i++) {
            String prefix = PathParser.toPath(segments.subList(0, i + 1), separator);
            container = childContainer(container, segments.get(i), segments.get(i + 1) instanceof Integer,
                    prefix, key);
        }
        writeLeaf(container, segments.get(segments.size() - 1), reshape(value, numberShape), key);
    }

    private static Object childContainer(Object container, Object segment, boolean nextIsIndex,
                                         String prefix, String key) {
        if (container instanceof List<?>) {
            List<Object> list = castList(container);
            int index = requireIndex(segment, key);
            pad(list, index);
            // 数组槽位的 null 即空槽（垫位产生）：真实 null 元素与其无法区分，被更深的键覆盖为容器
            Object existing = list.get(index);
            Object aligned = alignSlot(existing, existing != null, nextIsIndex, prefix, key);
            if (existing == null) {
                list.set(index, aligned);
            }
            return aligned;
        }
        Map<Object, Object> map = castMap(container);
        Object name = String.valueOf(segment);
        boolean present = map.containsKey(name);
        Object aligned = alignSlot(map.get(name), present, nextIsIndex, prefix, key);
        if (!present) {
            map.put(name, aligned);
        }
        return aligned;
    }

    /** 槽位现有内容与「下一步需要的容器类型」对齐：空则创建，容器则校验类型，值则冲突 */
    private static Object alignSlot(Object existing, boolean present, boolean nextIsIndex,
                                    String prefix, String key) {
        if (!present) {
            return nextIsIndex ? new ArrayList<Object>() : new LinkedHashMap<String, Object>();
        }
        boolean isArray = existing instanceof List<?>;
        if (isArray || existing instanceof Map<?, ?>) {
            if (isArray != nextIsIndex) {
                throw new RuleMatchException("target path conflict at '" + key + "': '" + prefix
                        + "' is used as " + (isArray ? "an array" : "an object") + " by an earlier path but '"
                        + key + "' requires " + (nextIsIndex ? "an array" : "an object"));
            }
            return existing;
        }
        throw new RuleMatchException("target path conflict at '" + key + "': '" + prefix
                + "' already holds a value (" + existing + ") but '" + key + "' requires a container under it");
    }

    private static void writeLeaf(Object container, Object segment, Object leaf, String key) {
        if (container instanceof List<?>) {
            List<Object> list = castList(container);
            int index = requireIndex(segment, key);
            pad(list, index);
            list.set(index, leaf);
            return;
        }
        castMap(container).put(String.valueOf(segment), leaf);
    }

    private static int requireIndex(Object segment, String key) {
        if (segment instanceof Integer index) {
            return index;
        }
        throw new RuleMatchException("target path conflict at '" + key
                + "': object key '" + segment + "' required under an array");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object container) {
        return (List<Object>) container;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Object container) {
        return (Map<Object, Object>) container;
    }

    private static void pad(List<Object> list, int index) {
        while (list.size() <= index) {
            list.add(null);
        }
    }

    /**
     * 叶值/已嵌套容器统一整形：数值按目标形态归一；容器转为 JDK 的 LinkedHashMap / ArrayList；
     * Java 数组展开为列表；其余对象（自定义变换函数返回的 POJO 等）经 mapper 取 plain 形态。
     */
    private Object reshape(Object value, UnaryOperator<Object> numberShape) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), reshape(v, numberShape)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(reshape(element, numberShape));
            }
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            // 必须先于 mapper 往返：byte[] 等会被包成 POJONode，往返后仍是原数组而无限递归
            List<Object> copy = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                copy.add(reshape(Array.get(value, index), numberShape));
            }
            return copy;
        }
        if (value instanceof Number) {
            return numberShape.apply(value);
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        return reshape(plainForm(value), numberShape);
    }

    /** 其余对象经 mapper 取 plain 形态；mapper 无法转换（往返后仍是原对象）时明确报错而不是无限递归 */
    private Object plainForm(Object value) {
        Object plain = mapper.treeToValue(mapper.valueToTree(value), Object.class);
        if (plain == value) {
            throw new RuleMatchException("leaf value of type " + value.getClass().getName()
                    + " cannot be represented in JSON");
        }
        return plain;
    }

    /** 视图形态：所有浮点数一律 BigDecimal（{@code Double.toString} 是其最短十进制表示，可无损还原） */
    static Object exactNumber(Object value) {
        return value instanceof Double number ? BigDecimal.valueOf(number) : value;
    }

    /** 输出形态：可经 double 无损往返的值输出 double（113.00 → 113.0，与历史行为一致），其余保留全精度 */
    static Object outputNumber(Object value) {
        if (!(value instanceof BigDecimal decimal)) {
            return value;
        }
        double asDouble = decimal.doubleValue();
        boolean representable = Double.isFinite(asDouble)
                && BigDecimal.valueOf(asDouble).compareTo(decimal) == 0;
        return representable ? asDouble : decimal;
    }
}
