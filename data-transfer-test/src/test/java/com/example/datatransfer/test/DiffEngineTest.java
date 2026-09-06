package com.example.datatransfer.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DiffEngineTest {

    @Test
    void missingAndUnexpectedReported() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a.x", 1);
        expected.put("a.gone", "v");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("a.x", 1);
        actual.put("a.extra", 9);

        DiffResult result = DiffEngine.diff(expected, actual, TransferAssert.CompareMode.STRICT);

        assertThat(result.size()).isEqualTo(2);
        assertThat(result.getEntries())
                .anySatisfy(e -> {
                    assertThat(e.getType()).isEqualTo(DiffType.MISSING);
                    assertThat(e.getPath()).isEqualTo("a.gone");
                })
                .anySatisfy(e -> {
                    assertThat(e.getType()).isEqualTo(DiffType.UNEXPECTED);
                    assertThat(e.getPath()).isEqualTo("a.extra");
                });
    }

    @Test
    void partialModeIgnoresExtraFields() {
        Map<String, Object> expected = Map.of("a.x", 1);
        Map<String, Object> actual = Map.of("a.x", 1, "a.extra", 9);

        assertThat(DiffEngine.diff(expected, actual, TransferAssert.CompareMode.PARTIAL).isEmpty())
                .isTrue();
        assertThat(DiffEngine.diff(expected, actual, TransferAssert.CompareMode.STRICT).isEmpty())
                .isFalse();
    }

    @Test
    void numericLooseComparison() {
        assertThat(DiffEngine.diff(Map.of("p", 113), Map.of("p", 113.00),
                TransferAssert.CompareMode.STRICT).isEmpty()).isTrue();
        assertThat(DiffEngine.diff(Map.of("p", 113), Map.of("p", 112.87),
                TransferAssert.CompareMode.STRICT).isEmpty()).isFalse();
        // 期望侧 String 宽松化（YAML 期望文件常把数字读成字符串）
        assertThat(DiffEngine.diff(Map.of("p", "113.0"), Map.of("p", 113.0),
                TransferAssert.CompareMode.STRICT).isEmpty()).isTrue();
        // 反向不宽松：实际侧字符串不与期望数字等价
        assertThat(DiffEngine.diff(Map.of("p", 113.0), Map.of("p", "113.0"),
                TransferAssert.CompareMode.STRICT).isEmpty()).isFalse();
    }

    @Test
    void jsonPathToRegex_stripsJsonPathRootPrefixAndEscapes() {
        java.util.regex.Pattern pattern = TransferAssert.jsonPathToRegex("$.crm.lines[*].id");
        assertThat(pattern.matcher("crm.lines[0].id").matches()).isTrue();
        assertThat(pattern.matcher("crm.lines[12].id").matches()).isTrue();
        assertThat(pattern.matcher("crmX.lines[0].id").matches()).isFalse();
    }

    @Test
    void jsonPathToRegex_escapesLiteralIndexBrackets() {
        // [0] 字面索引的方括号必须转义（不转义会成字符类，匹配失败）
        java.util.regex.Pattern pattern = TransferAssert.jsonPathToRegex("crm.lines[0].id");
        assertThat(pattern.matcher("crm.lines[0].id").matches()).isTrue();
        assertThat(pattern.matcher("crm.lines[1].id").matches()).isFalse();
    }

    @Test
    void ancestorPathToRegex_matchesDeeperKeys() {
        java.util.regex.Pattern pattern = TransferAssert.ancestorPathToRegex("crm.lines[*]");
        assertThat(pattern.matcher("crm.lines[0].unitPrice").matches()).isTrue();
        assertThat(pattern.matcher("crm.lines[1]").matches()).isTrue();
        assertThat(pattern.matcher("crm.lines").matches()).isFalse();
        assertThat(pattern.matcher("other.lines[0].x").matches()).isFalse();
    }
}
