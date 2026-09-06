package com.example.myapp.framework.core.dataflow;

import java.util.Locale;

import org.springframework.util.Assert;

/**
 * 数据链的键级词汇：一个通道 + 一个键名。
 *
 * <p>文本形式（声明与查询统一格式）：{@code payload}（主数据通道，单值无键名）、
 * {@code vars.credit}（旁路变量）、{@code biz.businessId}（关键数据区）、{@code vars.*}（通配，
 * 匹配同通道任意键）。解析失败抛 {@link IllegalArgumentException}。</p>
 *
 * @param channel 数据通道
 * @param name    键名；{@code PAYLOAD} 通道恒为空串，{@code VARS}/{@code BIZ} 通道非空白，
 *                {@value #WILDCARD} 表示通配（键名可含 {@code '.'}，以首个 {@code '.'} 分隔）
 */
public record DataflowKey(Channel channel, String name) {

    /** 通配键名，仅 {@code VARS}/{@code BIZ} 通道合法 */
    public static final String WILDCARD = "*";

    public DataflowKey {
        Assert.notNull(channel, "channel must not be null");
        if (channel == Channel.PAYLOAD) {
            Assert.isTrue(name != null && name.isEmpty(), "payload key must not carry a name");
        } else {
            Assert.hasText(name, "name must not be blank");
            Assert.isTrue(!name.isBlank() && name.chars().noneMatch(Character::isWhitespace),
                    "key name must not contain whitespace, but was: '%s'".formatted(name));
        }
    }

    /** 数据通道：payload 主数据 / vars 旁路变量 / biz 关键数据区 */
    public enum Channel {
        PAYLOAD("payload"), VARS("vars"), BIZ("biz");

        private final String label;

        Channel(String label) {
            this.label = label;
        }

        /** 文本形式中的通道前缀（小写） */
        public String label() {
            return label;
        }
    }

    /** payload 主数据键（单值通道） */
    public static DataflowKey payload() {
        return new DataflowKey(Channel.PAYLOAD, "");
    }

    /** vars 旁路键（{@code name} 为 {@value #WILDCARD} 时即通配） */
    public static DataflowKey vars(String name) {
        return new DataflowKey(Channel.VARS, name);
    }

    /** biz 关键数据区键（{@code name} 为 {@value #WILDCARD} 时即通配） */
    public static DataflowKey biz(String name) {
        return new DataflowKey(Channel.BIZ, name);
    }

    /**
     * 解析统一文本形式：{@code payload} / {@code vars.x} / {@code biz.y} / {@code vars.*}。
     *
     * @throws IllegalArgumentException 表达式为空白、通道未知、payload 带键名或 vars/biz 缺键名
     */
    public static DataflowKey parse(String expression) {
        Assert.hasText(expression, "dataflow key expression must not be blank");
        String trimmed = expression.trim();
        String channelLabel = rootOf(trimmed);
        Channel channel = switch (channelLabel.toLowerCase(Locale.ROOT)) {
            case "payload" -> Channel.PAYLOAD;
            case "vars" -> Channel.VARS;
            case "biz" -> Channel.BIZ;
            default -> throw new IllegalArgumentException(
                    "unknown dataflow channel in expression: '%s' (expected payload|vars|biz)".formatted(trimmed));
        };
        String rest = trimmed.substring(channelLabel.length());
        if (channel == Channel.PAYLOAD) {
            Assert.isTrue(rest.isEmpty(), "payload key must not carry a name, but was: '%s'".formatted(trimmed));
            return payload();
        }
        Assert.isTrue(!rest.isEmpty(), "key expression '%s' must carry a name (e.g. %s.<name>)".formatted(trimmed, channelLabel));
        Assert.isTrue(rest.charAt(0) == '.', "key expression '%s' must be of form %s.<name>".formatted(trimmed, channelLabel));
        String name = rest.substring(1);
        Assert.hasText(name, "key expression '%s' must carry a non-blank name".formatted(trimmed));
        return new DataflowKey(channel, name);
    }

    private static String rootOf(String expression) {
        int dot = expression.indexOf('.');
        return dot < 0 ? expression : expression.substring(0, dot);
    }

    /**
     * 匹配判定：同通道且任一侧为通配、或键名相等。
     * 实测侧的批量读取事件（如 keySet 遍历记为 {@code channel.*}）由此与具体键互认。
     */
    public boolean matches(DataflowKey other) {
        if (channel != other.channel) {
            return false;
        }
        return WILDCARD.equals(name) || WILDCARD.equals(other.name) || name.equals(other.name);
    }

    @Override
    public String toString() {
        if (channel == Channel.PAYLOAD) {
            return channel.label();
        }
        return channel.label() + "." + name;
    }
}
