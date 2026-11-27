package com.example.datatransfer.core.flatten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.exception.RuleMatchException;
import com.github.wnameless.json.unflattener.JsonUnflattener;

import tools.jackson.databind.JsonNode;

/**
 * FlatMapProcessor 测试，兼作 json-flattener 0.18.2 的 Jackson 3 API 冒烟
 * （设计文档 §8.4 API 注记：实例级 flattenAsMap/unflattenAsMap 形态在此实证）。
 */
class FlatMapProcessorTest {

    private final FlatMapProcessor processor = new FlatMapProcessor();

    @Test
    void flatten_producesDotKeysWithBracketIndexes() {
        JsonNode root = processor.mapper().readTree(
                "{\"user\": {\"name\": \"Alice\", \"tags\": [\"admin\", \"dev\"]}}");

        Map<String, Object> flat = processor.flatten(root, ".");

        assertThat(flat).containsExactly(
                Map.entry("user.name", "Alice"),
                Map.entry("user.tags[0]", "admin"),
                Map.entry("user.tags[1]", "dev"));
    }

    @Test
    void unflatten_restoresNestedStructure() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("crmOrder.id", "ORD-1");
        flat.put("crmOrder.lines[0].sku", "A001");
        flat.put("crmOrder.lines[0].qty", 2);
        flat.put("crmOrder.lines[1].sku", "B002");

        JsonNode nested = processor.unflatten(flat, ".");

        assertThat(nested.path("crmOrder").path("id").asString()).isEqualTo("ORD-1");
        assertThat(nested.path("crmOrder").path("lines").isArray()).isTrue();
        assertThat(nested.path("crmOrder").path("lines").size()).isEqualTo(2);
        assertThat(nested.path("crmOrder").path("lines").get(0).path("sku").asString()).isEqualTo("A001");
        assertThat(nested.path("crmOrder").path("lines").get(1).path("sku").asString()).isEqualTo("B002");
    }

    @Test
    void flattenUnflatten_roundTripPreservesValues() {
        String json = """
                {"orderId": "ORD-1", "items": [
                    {"sku": "A001", "price": 100, "active": true},
                    {"sku": "B002", "price": 50.5, "active": false}
                ]}
                """;
        JsonNode root = processor.mapper().readTree(json);

        JsonNode restored = processor.unflatten(processor.flatten(root, "."), ".");

        assertThat(restored.path("items").get(0).path("price").asInt()).isEqualTo(100);
        assertThat(restored.path("items").get(1).path("price").asDouble()).isEqualTo(50.5);
        assertThat(restored.path("items").get(0).path("active").asBoolean()).isTrue();
    }

    @Test
    void expandWildcard_matchesIndexedKeys() {
        // LinkedHashMap 保持文档序（Map.of 无序会让 containsExactly 不稳定）
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("items[0].price", 100);
        flat.put("items[1].price", 200);
        flat.put("items[2].price", 300);
        flat.put("other.price", 9);

        var matches = processor.expandWildcard("items[*].price", flat);

        assertThat(matches).hasSize(3);
        assertThat(matches).extracting(Map.Entry::getKey)
                .containsExactly("items[0].price", "items[1].price", "items[2].price");
    }

    @Test
    void emptyContainers_areKeptAsLeafValues() {
        // json-flattener v0.10+ 实测：空对象/空数组作为叶子值保留（不产生子键，但容器本身不丢失）
        JsonNode root = processor.mapper().readTree(
                "{\"data\": {\"emptyObj\": {}, \"emptyArr\": [], \"n\": 1}}");

        Map<String, Object> flat = processor.flatten(root, ".");

        assertThat(flat.keySet()).containsExactly("data.emptyObj", "data.emptyArr", "data.n");
        assertThat(flat.get("data.emptyObj")).isNotNull();
        assertThat(flat.get("data.emptyArr")).isNotNull();

        JsonNode restored = processor.unflatten(flat, ".");
        assertThat(restored.path("data").path("emptyObj").isObject()).isTrue();
        assertThat(restored.path("data").path("emptyArr").isArray()).isTrue();
    }

    @Test
    void expandWildcard_supportsLiteralIndexRules() {
        // 字面索引规则路径：[0] 的方括号必须转义（[0] 直接拼正则会成字符类）
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("items[0].price", 100);
        flat.put("items[1].price", 200);

        var matches = processor.expandWildcard("items[0].price", flat);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getKey()).isEqualTo("items[0].price");
    }

    @Test
    void expandWildcard_escapedReservedCharacterKeysNeverMatch() {
        // 源键含 "." 时 json-flattener 转义为 matrix["agent.smith"] 记法——不属于 DSL 语法，天然不参与匹配
        JsonNode root = processor.mapper().readTree("{\"matrix\": {\"agent.smith\": \"1999\"}}");
        Map<String, Object> flat = processor.flatten(root, ".");

        assertThat(processor.expandWildcard("matrix[*]", flat)).isEmpty();
        assertThat(processor.expandWildcard("matrix.name", flat)).isEmpty();
    }

    // ---- 数值完整性（外部评审二轮 P1-5：恒等映射不得丢失小数精度）----

    @Test
    void flatten_keepsDecimalsAsBigDecimal() {
        // json-base 的 toJavaNumber 会把可经 double 往返的小数（1.5 / 0.1 / 113.0）降为 Double——
        // 引擎在 flatten 出口统一还原为 BigDecimal；整数维持 Integer / BigInteger 窄化
        Map<String, Object> flat = processor.flatten(processor.mapper().readTree("""
                {"a": 1.5, "b": 0.1, "c": 113.0, "n": 100, "big": 12345678901234567890}
                """), ".");

        assertThat(flat.get("a")).isEqualTo(new BigDecimal("1.5"));
        assertThat(flat.get("b")).isEqualTo(new BigDecimal("0.1"));
        assertThat(flat.get("c")).isEqualTo(new BigDecimal("113.0"));
        assertThat(flat.get("n")).isEqualTo(100);
        assertThat(flat.get("big")).isEqualTo(new BigInteger("12345678901234567890"));
    }

    @Test
    void unflattenToMap_keepsNumbersAndContainerTypesExact() {
        // 表达式求值的嵌套视图：数值不经 double、容器为具体类型 LinkedHashMap / ArrayList（沙箱 allow 清单的依据）
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("x.big", new BigDecimal("12345678901234567890.123"));
        flat.put("x.n", new BigDecimal("123456789.123456789"));
        flat.put("x.e", new BigDecimal("8.2"));
        flat.put("x.i", 2);
        flat.put("x.list[0]", new BigDecimal("0.3"));

        Map<String, Object> nested = processor.unflattenToMap(flat, ".");

        assertThat(nested.getClass()).isEqualTo(LinkedHashMap.class);
        Map<?, ?> x = (Map<?, ?>) nested.get("x");
        assertThat(x.getClass()).isEqualTo(LinkedHashMap.class);
        assertThat(x.get("big")).isEqualTo(new BigDecimal("12345678901234567890.123"));
        assertThat(x.get("n")).isEqualTo(new BigDecimal("123456789.123456789"));
        assertThat(x.get("e")).isEqualTo(new BigDecimal("8.2"));
        assertThat(x.get("i")).isEqualTo(2);
        assertThat(x.get("list").getClass()).isEqualTo(ArrayList.class);
        assertThat(((List<?>) x.get("list")).get(0)).isEqualTo(new BigDecimal("0.3"));
    }

    @Test
    void unflatten_identityMappingKeepsHighPrecisionDecimals() {
        String json = """
                {"big": 12345678901234567890.123, "n": 123456789.123456789, "amt": 9007199254740993.5,
                 "prec": 1.0000000000000000001, "id": 12345678901234567890}
                """;
        Map<String, Object> flat = processor.flatten(processor.mapper().readTree(json), ".");

        JsonNode restored = processor.unflatten(flat, ".");

        assertThat(restored.toString()).isEqualTo(
                "{\"big\":12345678901234567890.123,\"n\":123456789.123456789,\"amt\":9007199254740993.5,"
                        + "\"prec\":1.0000000000000000001,\"id\":12345678901234567890}");
    }

    @Test
    void unflatten_outputKeepsHistoricalNumberShapeForDoubleRepresentableValues() {
        // 历史行为：数值经 double 输出（113.00 → 113.0、1.10 → 1.1、1e3 → 1000）——修复精度后形态保持不变，
        // 只有超出 double 精度的值才以全精度 BigDecimal 输出
        String json = """
                {"tail": 113.00, "tenths": 1.10, "sci": 1e3, "rate": 0.1, "small": 0.000001,
                 "large": 10000000.0, "count": 42}
                """;
        Map<String, Object> flat = processor.flatten(processor.mapper().readTree(json), ".");

        JsonNode restored = processor.unflatten(flat, ".");

        assertThat(restored.toString()).isEqualTo(
                "{\"tail\":113.0,\"tenths\":1.1,\"sci\":1000,\"rate\":0.1,\"small\":1.0E-6,"
                        + "\"large\":1.0E7,\"count\":42}");
    }

    @Test
    void unflatten_matchesJsonFlattenerOnRegularKeys() {
        // 对照测试：自建 unflatten 与 json-flattener 0.18.2 的还原结构一致（键序与值；数值按 compareTo）
        String json = """
                {"o": {"n": 1, "s": "x", "b": true, "nil": null, "arr": [1, "y", [2, 3], {"k": "v"}],
                       "e": {}, "ea": [], "d": 1.5},
                 "top": [true], "sp": {"a": [1, 2, 3]}}
                """;
        Map<String, Object> flat = processor.flatten(processor.mapper().readTree(json), ".");

        JsonNode ours = processor.unflatten(flat, ".");
        JsonNode library = processor.mapper().readTree(
                new JsonUnflattener(flat).withSeparator('.').unflatten());

        assertThat(decimalFlat(ours)).isEqualTo(decimalFlat(library));
        assertThat(ours.toString()).isEqualTo(processor.mapper().readTree(json).toString());
    }

    @Test
    void unflatten_fallsBackToLibrarySemanticsOnReservedCharacterKeys() {
        // 非 strictMode 下源数据可含保留字符转义记法的键（不可映射但须能还原）——沿用库的还原语义
        Map<String, Object> flat = processor.flatten(
                processor.mapper().readTree("{\"matrix\": {\"agent.smith\": \"1999\"}, \"n\": 1}"), ".");

        Map<String, Object> nested = processor.unflattenToMap(flat, ".");
        JsonNode restored = processor.unflatten(flat, ".");

        assertThat(((Map<?, ?>) nested.get("matrix")).get("agent.smith")).isEqualTo("1999");
        assertThat(restored.path("matrix").path("agent.smith").asString()).isEqualTo("1999");
        assertThat(restored.path("n").asInt()).isEqualTo(1);
    }

    @Test
    void unflatten_rejectsStructuralConflictsWithClearMessage() {
        // 历史行为：裸抛 ClassCastException（IntNode cannot be cast to ObjectNode）
        Map<String, Object> ancestor = new LinkedHashMap<>();
        ancestor.put("x.a", 1);
        ancestor.put("x.a.b", 2);
        assertThatThrownBy(() -> processor.unflatten(ancestor, "."))
                .isInstanceOf(RuleMatchException.class)
                .hasMessageContaining("x.a.b")
                .hasMessageContaining("x.a");

        Map<String, Object> arrayVsObject = new LinkedHashMap<>();
        arrayVsObject.put("x[0]", 1);
        arrayVsObject.put("x.y", 2);
        assertThatThrownBy(() -> processor.unflatten(arrayVsObject, "."))
                .isInstanceOf(RuleMatchException.class)
                .hasMessageContaining("x.y")
                .hasMessageContaining("'x' is used as an array")
                .hasMessageContaining("requires an object");
    }

    @Test
    void unflatten_padsArraySlotsAndKeepsEmptyContainers() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("a[2]", "c");
        flat.put("e.obj", new LinkedHashMap<>());
        flat.put("e.arr", new ArrayList<>());
        flat.put("nested", Map.of("k", List.of(1, 2)));

        JsonNode restored = processor.unflatten(flat, ".");

        assertThat(restored.toString()).isEqualTo(
                "{\"a\":[null,null,\"c\"],\"e\":{\"obj\":{},\"arr\":[]},\"nested\":{\"k\":[1,2]}}");
    }

    /** JsonNode → {flatKey: 数值统一 BigDecimal 的字符串}，用于与库产物做值级比较 */
    private Map<String, String> decimalFlat(JsonNode node) {
        Map<String, Object> flat = processor.flatten(processor.mapper().readTree(node.toString()), ".");
        Map<String, String> decimal = new LinkedHashMap<>();
        flat.forEach((key, value) -> decimal.put(key, value instanceof Number number
                ? new BigDecimal(number.toString()).stripTrailingZeros().toPlainString() : String.valueOf(value)));
        return decimal;
    }

    @Test
    void unflatten_expandsJavaArrayLeafValues() {
        // 历史行为：byte[] 经 mapper 往返仍是原数组，reshape 无限递归到 StackOverflowError
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("p.bytes", new byte[] {1, 2, 3});
        flat.put("p.chars", new char[] {'x', 'y'});

        assertThat(processor.unflatten(flat, ".").toString())
                .isEqualTo("{\"p\":{\"bytes\":[1,2,3],\"chars\":[\"x\",\"y\"]}}");
    }
}
