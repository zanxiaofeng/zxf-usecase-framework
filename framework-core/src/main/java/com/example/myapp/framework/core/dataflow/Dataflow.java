package com.example.myapp.framework.core.dataflow;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * step 的键级数据流声明（应然视图）：本步会读取与写入哪些数据键。
 *
 * <p>自定义 step 经 {@code Step#dataflow()} 声明；内置 step 由装配器从配置推导。
 * {@link #UNKNOWN} 表示「未声明」——运行期录制照常记录其真实读写，但不参与对照检查。</p>
 *
 * @param declared 是否已声明（false 即未声明，读写集无意义）
 * @param reads    声明读取的键（可含 {@code vars.*} 通配）
 * @param writes   声明写入的键（可含 {@code vars.*} 通配，覆盖动态键写入场景）
 */
public record Dataflow(boolean declared, Set<DataflowKey> reads, Set<DataflowKey> writes) {

    /** 未声明常量：录制照常、对照豁免 */
    public static final Dataflow UNKNOWN = new Dataflow(false, Set.of(), Set.of());

    public Dataflow {
        // 保持传入顺序（LinkedHashSet + 不可变视图）：报告渲染与测试断言的确定性依赖声明顺序
        reads = reads == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(reads));
        writes = writes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(writes));
    }

    /** @return 是否未声明 */
    public boolean unknown() {
        return !declared;
    }

    /**
     * 声明写入集是否覆盖该实测写入键（通配声明覆盖同通道任意键）。
     * 对照检查规则 1（未声明写入告警）的判定原语。
     */
    public boolean covers(DataflowKey writeKey) {
        return writes.stream().anyMatch(write -> write.matches(writeKey));
    }

    /** 声明 builder：键以统一文本形式（{@code vars.credit}）或 {@link DataflowKey} 给出 */
    public static Builder declaring() {
        return new Builder();
    }

    /** {@link Dataflow} 声明 builder（表达式解析失败立即抛 {@link IllegalArgumentException}） */
    public static final class Builder {

        private final Set<DataflowKey> reads = new LinkedHashSet<>();
        private final Set<DataflowKey> writes = new LinkedHashSet<>();

        /** 追加声明读取的键（统一文本形式） */
        public Builder reads(String... expressions) {
            for (String expression : expressions) {
                reads.add(DataflowKey.parse(expression));
            }
            return this;
        }

        /** 追加声明读取的键 */
        public Builder reads(DataflowKey... keys) {
            for (DataflowKey key : keys) {
                reads.add(key);
            }
            return this;
        }

        /** 追加声明写入的键（统一文本形式） */
        public Builder writes(String... expressions) {
            for (String expression : expressions) {
                writes.add(DataflowKey.parse(expression));
            }
            return this;
        }

        /** 追加声明写入的键 */
        public Builder writes(DataflowKey... keys) {
            for (DataflowKey key : keys) {
                writes.add(key);
            }
            return this;
        }

        /** 构建已声明的 {@link Dataflow} */
        public Dataflow build() {
            return new Dataflow(true, Set.copyOf(reads), Set.copyOf(writes));
        }
    }
}
