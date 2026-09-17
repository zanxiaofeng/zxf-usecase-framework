package com.example.datatransfer.core.transform;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.function.Function;

import lombok.experimental.UtilityClass;

/**
 * 日期时间变换函数实现（设计文档 §3.2「日期」行 + 路线图 D.2-2.5/2.10）：输入输出均为
 * 字符串/标量形态（框架不引入独立 datetime 值类型），时区默认 UTC、可选 IANA 时区参数
 * （如 {@code epochToIso('Asia/Shanghai')}），IANA 区域名与 offset 形态（{@code GMT+08}、
 * {@code UTC}）均接受。
 *
 * <p>函数实现统一抛 {@link IllegalArgumentException}，由引擎 applyChain 包装为
 * {@code TransformException}（携带 ruleIndex/from/to 与当前值）；{@code now} 的
 * {@link Clock} 由 {@link FuncRegistry} 实例持有并捕获（可注入固定 Clock 实现确定性测试）。
 * 日期断言的 ISO 解析口径归引擎私有（{@code TransferEngine} 是唯一消费者），本类仅承载
 * 变换函数——包级可见即足。</p>
 *
 * <p><b>解析精度</b>：pattern 解析走 JDK 默认 SMART 模式——非法日值（如 {@code 31/02}）
 * 会被<b>静默归一</b>到月末（{@code 02-28}）而非报错（字段完整性缺失仍报错）；值有效性
 * 校验不在变换函数职责内，见设计文档附录 D.4。locale 相关 pattern（{@code a}/{@code MMM}/
 * {@code EEE}）走平台默认 Locale，建议仅使用数值/ISO 类 pattern。</p>
 */
@UtilityClass
class DateTimeFunctions {

    /** 固定秒级 ISO 输出形态（LocalDateTime.toString() 会省略零秒段，须显式 pattern 固定 19 字符） */
    private static final DateTimeFormatter FIXED_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * dateFormat(fromFmt, toFmt)：字符串日期在两种 pattern 间重排（TemporalAccessor 透传）。
     * 目标 pattern 引用源值未解析出的字段（如源仅日期、目标含 HH:mm）时报错。
     */
    static String dateFormat(Object value, List<String> args) {
        requireArgs(args, 2, "dateFormat(fromFmt, toFmt)");
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        try {
            return DateTimeFormatter.ofPattern(args.get(1)).format(
                    DateTimeFormatter.ofPattern(args.get(0)).parse(text));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "value '" + text + "' cannot be parsed with pattern '" + args.get(0) + "'", e);
        } catch (DateTimeException e) {
            // 含 UnsupportedTemporalTypeException：目标 pattern 引用了源值不具备的字段
            throw new IllegalArgumentException("target pattern '" + args.get(1)
                    + "' references date-time fields not present in source value: " + e.getMessage(), e);
        }
    }

    /** epochToIso [zone]：epoch 毫秒 → ISO-8601（无参 UTC Instant 形态；带参 OffsetDateTime 形态） */
    static String epochToIso(Object value, List<String> args) {
        requireAtMostOneArg(args, "epochToIso([zone])");
        if (value == null) {
            return null;
        }
        return formatInstant(Instant.ofEpochMilli(toEpochMilli(value)), args);
    }

    /**
     * now [zone]：注入 Clock 的当前时间，输出形态同 epochToIso。
     * 注意引擎层 null 短路（仅 default 豁免）——now 须挂在必然存在的源字段上。
     */
    static String now(Object value, List<String> args, Clock clock) {
        requireAtMostOneArg(args, "now([zone])");
        if (value == null) {
            return null;
        }
        return formatInstant(clock.instant(), args);
    }

    /** toIsoDate(fmt)：按 fmt 解析（须含完整日期字段），输出 ISO yyyy-MM-dd */
    static String toIsoDate(Object value, List<String> args) {
        requireArgs(args, 1, "toIsoDate(fmt)");
        if (value == null) {
            return null;
        }
        return DateTimeFormatter.ISO_LOCAL_DATE.format(
                parseWithPattern(value, args.get(0), "full date fields", LocalDate::from));
    }

    /** toIsoDateTime(fmt)：按 fmt 解析（须含日期时间字段），输出固定 yyyy-MM-dd'T'HH:mm:ss（亚秒截断） */
    static String toIsoDateTime(Object value, List<String> args) {
        requireArgs(args, 1, "toIsoDateTime(fmt)");
        if (value == null) {
            return null;
        }
        return FIXED_SECONDS.format(
                parseWithPattern(value, args.get(0), "date-time fields", LocalDateTime::from));
    }

    /**
     * epochToIso/now 共用输出：无参 → Instant UTC 形态；带参 → OffsetDateTime 形态（无 [zone] 后缀）。
     * 带参路径用 ISO_OFFSET_DATE_TIME format 而非 toString()——后者省略零秒段（18:30+08:00），
     * formatter 的 optional 秒段在 format 时恒输出（18:30:00+08:00），毫秒语义与 Instant 路径一致。
     */
    private static String formatInstant(Instant instant, List<String> args) {
        return args.isEmpty()
                ? instant.toString()
                : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        OffsetDateTime.ofInstant(instant, zone(args.get(0))));
    }

    /**
     * pattern 解析共用：解析（含 extractor 的类型提取，如 {@code LocalDate::from}）失败——
     * {@link DateTimeParseException} 与字段缺失的 {@link DateTimeException}（含子类
     * UnsupportedTemporalTypeException）统一为 IAE，消息含值与 pattern。
     */
    private static <T extends TemporalAccessor> T parseWithPattern(Object value, String pattern,
                                                                   String fieldsDescription,
                                                                   Function<TemporalAccessor, T> extractor) {
        String text = String.valueOf(value);
        try {
            return extractor.apply(DateTimeFormatter.ofPattern(pattern).parse(text));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "value '" + text + "' cannot be parsed with pattern '" + pattern + "'", e);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("value '" + text + "' does not contain the "
                    + fieldsDescription + " required by pattern '" + pattern + "': " + e.getMessage(), e);
        }
    }

    /** epoch 值统一入口（对齐 FuncRegistry.toDecimal 先例）；longValueExact 防小数静默截断 */
    private static long toEpochMilli(Object value) {
        BigDecimal decimal;
        try {
            decimal = value instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("epoch millis value is not a valid number: '" + value + "'", e);
        }
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "epoch millis value has a fractional part or exceeds long range: " + value, e);
        }
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            // ZoneRulesException extends DateTimeException：格式合法但未知的 IANA id 亦在此
            throw new IllegalArgumentException("unknown time-zone: '" + id + "'", e);
        }
    }

    private static void requireArgs(List<String> args, int expected, String usage) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    usage + ": expected " + expected + " argument(s), got " + args.size());
        }
    }

    private static void requireAtMostOneArg(List<String> args, String usage) {
        if (args.size() > 1) {
            throw new IllegalArgumentException(usage + " accepts at most 1 argument (zone)");
        }
    }
}
