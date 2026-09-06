package com.example.datatransfer.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.example.datatransfer.test.AssertContext;
import com.example.datatransfer.test.TransferAssert;
import com.example.datatransfer.test.ext.TransferSpecExtension;
import com.example.datatransfer.test.ext.TransferSpecTest;

/** 契约测试示范（设计文档 §10.8）：注解驱动 + 手动链式断言两种风格。 */
@ExtendWith(TransferSpecExtension.class)
class OrderTransferContractTest {

    @TransferSpecTest(
            spec = "specs/order-transfer.yaml",
            fixture = "samples/order-001.json",
            expected = "expected/order-001.json"
    )
    void order001_matchesExpectedExactly() {
        // 注解驱动，方法体无需代码（TransferSpecExtension 在方法调用处执行契约断言）
    }

    @Test
    void order001_chainedPathAssertions() {
        AssertContext context = TransferAssert.assertThat("specs/order-transfer.yaml")
                .withFixture("samples/order-001.json")
                .execute();

        context.pathExists("crmOrder.id")
                .pathExists("crmOrder.buyer.cities[*]")
                .pathValueEquals("crmOrder.currency", "CNY")
                .pathValueMatches("crmOrder.id", "^ORD-.*")
                .pathValueGreaterThan("crmOrder.totalAmount", 395)
                .pathValueLessThan("crmOrder.totalAmount", 396)
                .allPathValues("crmOrder.lines[*].unitPrice",
                        v -> {
                            if (((Number) v).doubleValue() <= 0) {
                                throw new IllegalStateException("unitPrice must be positive");
                            }
                        });
    }
}
