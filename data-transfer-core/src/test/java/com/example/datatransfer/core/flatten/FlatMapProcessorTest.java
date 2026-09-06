package com.example.datatransfer.core.flatten;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
