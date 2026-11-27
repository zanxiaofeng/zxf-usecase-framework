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
        assertThat(result.entries())
                .anySatisfy(e -> {
                    assertThat(e.type()).isEqualTo(DiffType.MISSING);
                    assertThat(e.path()).isEqualTo("a.gone");
                })
                .anySatisfy(e -> {
                    assertThat(e.type()).isEqualTo(DiffType.UNEXPECTED);
                    assertThat(e.path()).isEqualTo("a.extra");
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
    void numericComparison_isExactAcrossNumberTypesOnly() {
        assertThat(DiffEngine.diff(Map.of("p", 113), Map.of("p", 113.00),
                TransferAssert.CompareMode.STRICT).isEmpty()).isTrue();
        assertThat(DiffEngine.diff(Map.of("p", 113), Map.of("p", 112.87),
                TransferAssert.CompareMode.STRICT).isEmpty()).isFalse();
        // 外部评审二轮 P1-7：JSON 类型是契约的一部分——String 与 Number 默认不等
        // （历史行为：期望侧 String 向 Number 单向宽松，"000123" 与 123 判相等）
        assertThat(DiffEngine.diff(Map.of("p", "113.0"), Map.of("p", 113.0),
                TransferAssert.CompareMode.STRICT).isEmpty()).isFalse();
        assertThat(DiffEngine.diff(Map.of("p", 113.0), Map.of("p", "113.0"),
                TransferAssert.CompareMode.STRICT).isEmpty()).isFalse();
        assertThat(DiffEngine.looseEquals("000123", 123)).isFalse();
        assertThat(DiffEngine.looseEquals("000123", 123, true)).isTrue();
        assertThat(DiffEngine.looseEquals(123, "000123", true)).isFalse();   // 仅放宽期望侧
    }

    @Test
    void highPrecisionDecimals_compareExactly() {
        java.math.BigDecimal exact = new java.math.BigDecimal("12345678901234567890.123");
        java.math.BigDecimal lossy = new java.math.BigDecimal("12345678901234567000");

        assertThat(DiffEngine.looseEquals(exact, exact)).isTrue();
        assertThat(DiffEngine.looseEquals(exact, lossy)).isFalse();
    }

    @Test
    void bigIntegers_compareWithoutDoublePrecisionLoss() {
        // review P1：> 2^53 的大整数经 double 中转会舍入漏检（旧 Double.compare 路径下两者相等），
        // BigDecimal compareTo 精确比较
        assertThat(DiffEngine.diff(Map.of("p", 9_007_199_254_740_993L), Map.of("p", 9_007_199_254_740_992L),
                TransferAssert.CompareMode.STRICT).isEmpty()).isFalse();
        assertThat(DiffEngine.diff(Map.of("p", 9_007_199_254_740_993L), Map.of("p", 9_007_199_254_740_993L),
                TransferAssert.CompareMode.STRICT).isEmpty()).isTrue();
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
