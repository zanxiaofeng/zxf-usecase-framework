package com.example.datatransfer.core.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FuncRegistryTest {

    private final FuncRegistry registry = new FuncRegistry();
    private final Map<String, Object> context = Map.of();

    @Test
    void builtinStringFunctions() {
        assertThat(registry.get("trim").apply("  x  ", List.of(), context)).isEqualTo("x");
        assertThat(registry.get("lower").apply("ABC", List.of(), context)).isEqualTo("abc");
        assertThat(registry.get("upper").apply("abc", List.of(), context)).isEqualTo("ABC");
        assertThat(registry.get("trim").apply(null, List.of(), context)).isNull();
    }

    @Test
    void builtinArithmeticUsesBigDecimal() {
        Object multiplied = registry.get("multiply").apply(100, List.of("1.13"), context);
        assertThat(multiplied).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) multiplied).isEqualByComparingTo("113");

        Object rounded = registry.get("round").apply("113.005", List.of("2"), context);
        assertThat((BigDecimal) rounded).isEqualByComparingTo("113.01");
    }

    @Test
    void builtinDefaultSubstitutesNullOnly() {
        assertThat(registry.get("default").apply(null, List.of("N/A"), context)).isEqualTo("N/A");
        assertThat(registry.get("default").apply("keep", List.of("N/A"), context)).isEqualTo("keep");
    }

    @Test
    void register_customFunction() {
        registry.register("shout", (v, args, ctx) -> String.valueOf(v).toUpperCase() + "!!!");

        assertThat(registry.get("shout").apply("hi", List.of(), context)).isEqualTo("HI!!!");
    }

    @Test
    void get_unknownFunctionFailsFast() {
        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }
}
