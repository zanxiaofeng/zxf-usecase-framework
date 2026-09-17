package com.example.datatransfer.core.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 日期时间函数族单测（设计文档 §3.2「日期」行 + 路线图 D.2-2.5/2.10）：
 * 输出均为字符串形态，默认 UTC + 可选 IANA 时区参数；now 经固定 Clock 实现确定性。
 */
class DateTimeFunctionsTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:30:00Z");

    private final FuncRegistry registry = new FuncRegistry();
    private final Map<String, Object> context = Map.of();

    // ---- dateFormat ----

    @Test
    void dateFormat_reordersDateBetweenPatterns() {
        Object result = registry.get("dateFormat")
                .apply("15-01-2026", List.of("dd-MM-yyyy", "yyyy-MM-dd"), context);
        assertThat(result).isEqualTo("2026-01-15");
    }

    @Test
    void dateFormat_reordersDateTimeBetweenPatterns() {
        Object result = registry.get("dateFormat")
                .apply("15-01-2026 10:30:45", List.of("dd-MM-yyyy HH:mm:ss", "yyyy/MM/dd HH:mm"), context);
        assertThat(result).isEqualTo("2026/01/15 10:30");
    }

    @Test
    void dateFormat_timeOnlyPatterns_roundTrip() {
        Object result = registry.get("dateFormat")
                .apply("10:30:45", List.of("HH:mm:ss", "HHmmss"), context);
        assertThat(result).isEqualTo("103045");
    }

    @Test
    void dateFormat_parseFailure_messageContainsValueAndPattern() {
        assertThatThrownBy(() -> registry.get("dateFormat")
                .apply("not-a-date", List.of("dd-MM-yyyy", "yyyy-MM-dd"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-date")
                .hasMessageContaining("dd-MM-yyyy");
    }

    @Test
    void dateFormat_missingTargetField_throwsClearError() {
        // 源仅日期、目标 pattern 含时间字段 → 目标 pattern 引用了源值不具备的字段
        assertThatThrownBy(() -> registry.get("dateFormat")
                .apply("15-01-2026", List.of("dd-MM-yyyy", "yyyy-MM-dd HH:mm"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd HH:mm")
                .hasMessageContaining("not present in source value");
    }

    @Test
    void toIsoDate_invalidDateNormalizedBySmartResolver() {
        // JDK SMART 解析语义锚定：非法日值静默归一到月末而非报错（值有效性校验不在变换函数职责内）
        Object result = registry.get("toIsoDate")
                .apply("31/02/2026", List.of("dd/MM/yyyy"), context);
        assertThat(result).isEqualTo("2026-02-28");
    }

    // ---- epochToIso ----

    @Test
    void epochToIso_noArg_outputsInstantUtcForm() {
        Object result = registry.get("epochToIso").apply(1768473000000L, List.of(), context);
        assertThat(result).isEqualTo("2026-01-15T10:30:00Z");
    }

    @Test
    void epochToIso_withZone_outputsOffsetForm() {
        Object result = registry.get("epochToIso")
                .apply(1768473000000L, List.of("Asia/Shanghai"), context);
        assertThat(result).isEqualTo("2026-01-15T18:30:00+08:00");
    }

    @Test
    void epochToIso_acceptsLongStringAndBigDecimalInputs() {
        Object fromLong = registry.get("epochToIso").apply(1768473000000L, List.of(), context);
        Object fromString = registry.get("epochToIso").apply("1768473000000", List.of(), context);
        Object fromBigDecimal = registry.get("epochToIso")
                .apply(new BigDecimal("1768473000000"), List.of(), context);
        assertThat(fromLong).isEqualTo("2026-01-15T10:30:00Z");
        assertThat(fromString).isEqualTo(fromLong);
        assertThat(fromBigDecimal).isEqualTo(fromLong);
    }

    @Test
    void epochToIso_fractionalEpoch_rejected() {
        assertThatThrownBy(() -> registry.get("epochToIso")
                .apply(new BigDecimal("1768473000000.5"), List.of(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fractional");
    }

    @Test
    void epochToIso_invalidZone_errorMentionsZoneName() {
        assertThatThrownBy(() -> registry.get("epochToIso")
                .apply(1768473000000L, List.of("Mars/Olympus"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mars/Olympus");
    }

    @Test
    void epochToIso_offsetStyleZone_outputsOffsetForm() {
        // GMT+08/UTC 等 offset 形态 id 合法（zone() 行为锚定；OffsetDateTime 输出无 [id] 后缀）
        Object result = registry.get("epochToIso")
                .apply(1768473000000L, List.of("GMT+08"), context);
        assertThat(result).isEqualTo("2026-01-15T18:30:00+08:00");
    }

    @Test
    void epochAndNow_extraArgument_rejected() {
        // 参数个数校验先于 null 短路（与 dateFormat/toIsoDate 的 requireArgs 顺序一致）
        assertThatThrownBy(() -> registry.get("epochToIso")
                .apply(1768473000000L, List.of("Asia/Shanghai", "extra"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1 argument");
        assertThatThrownBy(() -> registry.get("now")
                .apply(null, List.of("Asia/Shanghai", "extra"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1 argument");
    }

    // ---- toIsoDate / toIsoDateTime ----

    @Test
    void toIsoDate_outputsIsoDate() {
        Object result = registry.get("toIsoDate")
                .apply("15/01/2026", List.of("dd/MM/yyyy"), context);
        assertThat(result).isEqualTo("2026-01-15");
    }

    @Test
    void toIsoDate_timeOnlyPattern_throws() {
        assertThatThrownBy(() -> registry.get("toIsoDate")
                .apply("10:30", List.of("HH:mm"), context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toIsoDateTime_outputsFixedSecondsForm() {
        // 输入无秒段 → 输出固定补 :00（19 字符固定形态，而非 toString() 的变长省略）
        Object result = registry.get("toIsoDateTime")
                .apply("2026/01/15 10:30", List.of("yyyy/MM/dd HH:mm"), context);
        assertThat(result).isEqualTo("2026-01-15T10:30:00");
    }

    // ---- now（Clock 注入确定性）----

    @Test
    void now_usesInjectedClock_utcForm() {
        FuncRegistry fixed = new FuncRegistry(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        assertThat(fixed.get("now").apply("ignored", List.of(), context))
                .isEqualTo("2026-01-15T10:30:00Z");
    }

    @Test
    void now_withZoneParameter_offsetForm() {
        FuncRegistry fixed = new FuncRegistry(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        assertThat(fixed.get("now").apply("ignored", List.of("Asia/Shanghai"), context))
                .isEqualTo("2026-01-15T18:30:00+08:00");
    }

    // ---- null 防卫与缺参 ----

    @Test
    void dateFunctions_nullValueReturnsNull() {
        assertThat(registry.get("dateFormat")
                .apply(null, List.of("dd-MM-yyyy", "yyyy-MM-dd"), context)).isNull();
        assertThat(registry.get("epochToIso").apply(null, List.of(), context)).isNull();
        assertThat(registry.get("toIsoDate").apply(null, List.of("dd/MM/yyyy"), context)).isNull();
        assertThat(registry.get("toIsoDateTime").apply(null, List.of("dd/MM/yyyy"), context)).isNull();
        assertThat(registry.get("now").apply(null, List.of(), context)).isNull();
    }

    @Test
    void dateFunctions_missingArguments_failWithUsageMessage() {
        assertThatThrownBy(() -> registry.get("dateFormat").apply("15-01-2026", List.of(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateFormat(fromFmt, toFmt)");
        assertThatThrownBy(() -> registry.get("toIsoDate").apply("15/01/2026", List.of(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toIsoDate(fmt)");
        assertThatThrownBy(() -> registry.get("toIsoDateTime")
                .apply("15/01/2026 10:30", List.of(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toIsoDateTime(fmt)");
    }
}
