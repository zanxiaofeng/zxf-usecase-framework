package com.example.datatransfer.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.test.TransferAssert;

/**
 * 日期时间函数族契约示例（设计文档 §D.4）：now 非确定性——确定性断言经
 * {@link TransferAssert#withClock} 注入固定 Clock 覆盖真实内置 now；
 * 不注 Clock 的第二用例展示生产语义下的形态断言（regex 弱断言）。
 */
class DateTimeTransferTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC);

    @Test
    void datetimeShowcase_withFixedClock_matchesExpected() {
        // now 的期望值得以书写的前提：固定 Clock（注解驱动三件套无 clock 属性，故 now 不进 order-transfer）
        TransferAssert.assertThat("specs/datetime-transfer.yaml")
                .withClock(FIXED_CLOCK)
                .withFixture("samples/datetime-001.json")
                .matchesExpected("expected/datetime-001.json");
    }

    @Test
    void nowRules_productionSemantics_produceParsableTimestamps() {
        // 生产语义（系统时钟）：产物随运行时刻变化，断言表达「形态正确」而非「值精确」
        TransferAssert.assertThat("specs/datetime-transfer.yaml")
                .withFixture("samples/datetime-001.json")
                .execute()
                .pathValueMatches("out.processedAtUtc",
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")
                .pathValueMatches("out.processedAtShanghai",
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+08:00$");
    }
}
