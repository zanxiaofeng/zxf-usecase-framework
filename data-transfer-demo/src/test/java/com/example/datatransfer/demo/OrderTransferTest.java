package com.example.datatransfer.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.test.TransferAssert;
import com.example.datatransfer.core.validation.TransferSpecValidator;

/** 推荐的测试类结构示范（设计文档 §10.11）：Schema 校验 + 精确匹配 + 部分匹配 + 批量。 */
class OrderTransferTest {

    private static final TransferSpecValidator VALIDATOR = new TransferSpecValidator();

    @Test
    void specShouldBeValid() throws Exception {
        String yaml;
        try (var in = getClass().getResourceAsStream("/specs/order-transfer.yaml")) {
            yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = VALIDATOR.validate(yaml);

        assertThat(result.isValid())
                .as(() -> "Spec 格式校验失败: " + result.getErrorMessages())
                .isTrue();
    }

    @Test
    void order001ShouldTransferCorrectly() {
        TransferAssert.assertThat("specs/order-transfer.yaml")
                .withFixture("samples/order-001.json")
                .matchesExpected("expected/order-001.json");
    }

    @Test
    void order001PartialMatchIgnoresSourceStamp() {
        TransferAssert.assertThat("specs/order-transfer.yaml")
                .withFixture("samples/order-001.json")
                .ignorePaths("crmOrder.source", "crmOrder.currency")
                .partiallyMatches("expected/order-001.json");
    }

    @Test
    void batchSamplesShouldPass() {
        TransferAssert.batchAssert("specs/order-transfer.yaml")
                .addCase("samples/order-001.json", "expected/order-001.json")
                .addCaseJson("{\"orderId\": \"ORD-2\", \"customer\": {\"name\": \"Ann\", "
                        + "\"email\": \"ANN@x.com\", \"addresses\": []}, \"items\": "
                        + "[{\"sku\": \"Z9\", \"price\": 10, \"qty\": 1}], \"status\": \"NEW\"}",
                        "{\"crmOrder\": {\"id\": \"ORD-2\", \"buyer\": {\"name\": \"Ann\", "
                                + "\"email\": \"ann@x.com\"}, \"lines\": [{\"productCode\": \"Z9\", "
                                + "\"unitPrice\": 11.30, \"quantity\": 1}], \"state\": \"new\", "
                                + "\"totalAmount\": 11.3, \"currency\": \"CNY\", \"source\": \"eCommerce\"}}")
                .runAll();
    }
}
