package com.example.datatransfer.test.ext;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EngineTestKit.engine;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import org.junit.jupiter.api.Test;

/**
 * review P1：{@code @TransferSpecTest} 元标注 {@code @ExtendWith} 后，测试类不再需要
 * 显式注册 {@link TransferSpecExtension}——此前漏注册会让契约断言静默跳过、空方法体假绿。
 * 用 TestKit 执行内嵌失败样本，验证注解自带的扩展确实执行了断言（而非空跑）。
 */
class MetaAnnotatedTransferSpecTest {

    /** 内嵌失败样本：期望输出故意写错——若注解自带扩展未生效，此用例会假绿 */
    static class WrongExpectationSample {
        @TransferSpecTest(spec = "specs/assert-demo.yaml",
                fixture = "fixtures/order-mini.json",
                expected = "expected/order-wrong.json")
        void contractMustFail() {
        }
    }

    @TransferSpecTest(spec = "specs/assert-demo.yaml",
            fixture = "fixtures/order-mini.json",
            expected = "expected/order-mini.json")
    void annotationDrivesContract_withoutExplicitExtendWith() {
        // 本类未声明 @ExtendWith：契约断言完全由注解元标注的扩展在方法调用处执行
    }

    @Test
    void metaAnnotatedExtension_actuallyRunsAssertion() {
        engine("junit-jupiter")
                .selectors(selectClass(WrongExpectationSample.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, event(test(), finishedWithFailure(
                        message(m -> m.contains("契约测试失败")))));
    }
}
