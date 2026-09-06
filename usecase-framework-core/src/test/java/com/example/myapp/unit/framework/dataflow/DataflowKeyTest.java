package com.example.myapp.unit.framework.dataflow;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.dataflow.DataflowKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据键词汇：表达式解析、文本形式与通配匹配。
 */
class DataflowKeyTest {

    @Test
    void parsesPayloadExpression() {
        DataflowKey key = DataflowKey.parse("payload");

        assertThat(key.channel()).isEqualTo(DataflowKey.Channel.PAYLOAD);
        assertThat(key).hasToString("payload");
    }

    @Test
    void parsesVarsAndBizExpressions() {
        assertThat(DataflowKey.parse("vars.credit")).hasToString("vars.credit");
        assertThat(DataflowKey.parse("biz.businessId")).hasToString("biz.businessId");
    }

    @Test
    void parsesWildcardExpression() {
        DataflowKey key = DataflowKey.parse("vars.*");

        assertThat(key.name()).isEqualTo(DataflowKey.WILDCARD);
        assertThat(key).hasToString("vars.*");
    }

    @Test
    void parseSupportsDottedKeyName() {
        // 键级粒度以首个 '.' 分隔通道与键名，键名自身可含 '.'（用户自定义键名）
        assertThat(DataflowKey.parse("vars.order.item")).hasToString("vars.order.item");
    }

    @Test
    void parseRejectsIllegalExpressions() {
        assertThatThrownBy(() -> DataflowKey.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataflowKey.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataflowKey.parse(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataflowKey.parse("user.name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user.name");
        assertThatThrownBy(() -> DataflowKey.parse("payload.userId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataflowKey.parse("payload.*"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataflowKey.parse("vars"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataflowKey.parse("vars."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoriesProduceTypedKeys() {
        assertThat(DataflowKey.payload()).hasToString("payload");
        assertThat(DataflowKey.vars("credit")).hasToString("vars.credit");
        assertThat(DataflowKey.biz("businessId")).hasToString("biz.businessId");
    }

    @Test
    void exactMatchRequiresSameChannelAndName() {
        assertThat(DataflowKey.vars("credit").matches(DataflowKey.vars("credit"))).isTrue();
        assertThat(DataflowKey.vars("credit").matches(DataflowKey.vars("other"))).isFalse();
        assertThat(DataflowKey.vars("credit").matches(DataflowKey.biz("credit"))).isFalse();
        assertThat(DataflowKey.vars("credit").matches(DataflowKey.payload())).isFalse();
    }

    @Test
    void wildcardMatchesAnyKeyInSameChannel() {
        assertThat(DataflowKey.parse("vars.*").matches(DataflowKey.vars("credit"))).isTrue();
        assertThat(DataflowKey.parse("biz.*").matches(DataflowKey.biz("channel"))).isTrue();
        assertThat(DataflowKey.parse("vars.*").matches(DataflowKey.biz("credit"))).isFalse();
    }

    @Test
    void wildcardMatchesRegardlessOfSide() {
        // 实测侧的批量读取事件（如 keySet 遍历记 channel.*）同样视为匹配任意同通道具体键
        assertThat(DataflowKey.vars("credit").matches(DataflowKey.parse("vars.*"))).isTrue();
        assertThat(DataflowKey.payload().matches(DataflowKey.payload())).isTrue();
    }
}
