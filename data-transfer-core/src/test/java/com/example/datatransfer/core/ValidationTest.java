package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.exception.TransferAssemblyException;
import com.example.datatransfer.core.exception.ValidationException;
import com.example.datatransfer.core.spec.Assertion;
import com.example.datatransfer.core.spec.MappingRule;
import com.example.datatransfer.core.spec.TransferOptions;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.spec.ValidationMode;
import com.example.datatransfer.core.spec.ValidationRule;

/** validations 数据值校验（评审 6.x）：断言 DSL / 组合条件 / fail_fast 与 collect 双模式。 */
class ValidationTest {

    private static final String ORDER_JSON = """
            {"items": [{"sku": "A", "price": 100, "qty": 2}, {"sku": "B", "price": -5, "qty": 3}],
             "email": "buyer@example.com"}
            """;

    @Test
    void assertionsPass_onValidData() {
        TransferSpec spec = spec(ValidationRule.builder()
                .path("out.lines[*].unitPrice")
                .rules(List.of(Assertion.builder().assertExpr("gt(0)").message("单价必须大于0").build()))
                .message("unitPrice invalid")
                .build());

        assertThatCode(() -> new TransferEngine(spec)
                .transfer("{\"items\": [{\"price\": 100}]}"))
                .doesNotThrowAnyException();
    }

    @Test
    void wildcardAssertion_reportsExactElementPath_failFast() {
        TransferSpec spec = spec(ValidationRule.builder()
                .path("out.lines[*].unitPrice")
                .rules(List.of(Assertion.builder().assertExpr("gt(0)").message("单价必须大于0").build()))
                .message("unitPrice invalid")
                .build());

        assertThatThrownBy(() -> new TransferEngine(spec).transfer(ORDER_JSON))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("out.lines[1].unitPrice")
                .hasMessageContaining("单价必须大于0");
    }

    @Test
    void collectMode_gathersAllFailures() {
        TransferOptions options = new TransferOptions();
        options.setValidationMode(ValidationMode.COLLECT);
        TransferSpec spec = TransferSpec.builder()
                .version("1.0").name("collect").options(options)
                .rules(List.of(
                        MappingRule.builder().from("items[*].price").to("out.lines[*].unitPrice").build(),
                        MappingRule.builder().from("email").to("out.email").build()))
                .validations(List.of(
                        ValidationRule.builder().path("out.lines[*].unitPrice")
                                .rules(List.of(Assertion.builder().assertExpr("gt(0)")
                                        .message("单价必须大于0").build()))
                                .message("unitPrice").build(),
                        ValidationRule.builder().path("out.email")
                                .rules(List.of(Assertion.builder()
                                        .assertExpr("regex('^nope@.*$')").message("邮箱格式非法").build()))
                                .message("email").build()))
                .build();

        assertThatThrownBy(() -> new TransferEngine(spec).transfer(ORDER_JSON))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFailures()).hasSize(2))
                .hasMessageContaining("out.lines[1].unitPrice")
                .hasMessageContaining("out.email");
    }

    @Test
    void scalarCondition_bindsValue() {
        TransferSpec spec = spec(ValidationRule.builder()
                .path("out.lines[0].unitPrice")
                .condition("value > 50")
                .message("首行单价必须大于50")
                .build());

        assertThatCode(() -> new TransferEngine(spec)
                .transfer("{\"items\": [{\"price\": 100, \"qty\": 2}]}"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new TransferEngine(spec)
                .transfer("{\"items\": [{\"price\": 10, \"qty\": 2}]}"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("首行单价必须大于50");
    }

    @Test
    void elementCondition_resolvesBareIdentifiersPerElement() {
        // 评审 6.2：path 指元素，condition 裸标识符解析为该元素字段（unitPrice/quantity）
        TransferSpec spec = spec(ValidationRule.builder()
                .path("out.lines[*]")
                .condition("unitPrice > 0 && quantity > 0")
                .message("单价和数量必须同时大于0")
                .build());

        assertThatCode(() -> new TransferEngine(spec)
                .transfer("{\"items\": [{\"price\": 100, \"qty\": 2}]}"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new TransferEngine(spec).transfer(ORDER_JSON))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("out.lines[1]")
                .hasMessageContaining("单价和数量必须同时大于0");
    }

    @Test
    void notNull_coversMissingKey() {
        TransferSpec spec = spec(ValidationRule.builder()
                .path("out.missing")
                .rules(List.of(Assertion.builder().assertExpr("notNull").message("字段缺失").build()))
                .message("required")
                .build());

        assertThatThrownBy(() -> new TransferEngine(spec).transfer("{\"items\": []}"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("out.missing")
                .hasMessageContaining("字段缺失");
    }

    @Test
    void constructor_rejectsMalformedValidations() {
        // rules 与 condition 二选一
        assertThatThrownBy(() -> new TransferEngine(spec(ValidationRule.builder()
                .path("out.x")
                .rules(List.of(Assertion.builder().assertExpr("gt(0)").message("m").build()))
                .condition("value > 0")
                .message("both").build())))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("exactly one");

        // 未知断言谓词
        assertThatThrownBy(() -> new TransferEngine(spec(ValidationRule.builder()
                .path("out.x")
                .rules(List.of(Assertion.builder().assertExpr("frobnicate(1)").message("m").build()))
                .message("unknown").build())))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("unknown assertion 'frobnicate'");

        // 数值断言参数非数值
        assertThatThrownBy(() -> new TransferEngine(spec(ValidationRule.builder()
                .path("out.x")
                .rules(List.of(Assertion.builder().assertExpr("gt('abc')").message("m").build()))
                .message("bad-arg").build())))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("numeric argument");

        // condition 多级通配不支持
        assertThatThrownBy(() -> new TransferEngine(spec(ValidationRule.builder()
                .path("out.a[*].b[*]")
                .condition("b > 0")
                .message("multi").build())))
                .isInstanceOf(TransferAssemblyException.class)
                .hasMessageContaining("at most one [*]");
    }

    /** items[*].price → out.lines[*].unitPrice + items[*].qty → out.lines[*].quantity 的公共 spec */
    private static TransferSpec spec(ValidationRule... validations) {
        return TransferSpec.builder()
                .version("1.0").name("validation-test")
                .rules(List.of(
                        MappingRule.builder().from("items[*].price").to("out.lines[*].unitPrice").build(),
                        MappingRule.builder().from("items[*].qty").to("out.lines[*].quantity").build(),
                        MappingRule.builder().from("email").to("out.email").build()))
                .validations(List.of(validations))
                .build();
    }
}
