package com.example.myapp.framework.core;

/**
 * dev 模式 step trace 配置（{@code usecase.trace.enabled} / {@code usecase.trace.include-values}）。
 *
 * <p>默认关闭且关闭时执行路径零开销（无快照、无键集拷贝）；include-values 需显式二次开启
 * （值快照截断 {@value #VALUE_SNAPSHOT_LIMIT} 字符，防大对象撑爆日志）。</p>
 */
public record UseCaseTrace(boolean enabled, boolean includeValues) {

    /** 关闭态（默认） */
    public static final UseCaseTrace DISABLED = new UseCaseTrace(false, false);

    /** 值快照截断长度 */
    public static final int VALUE_SNAPSHOT_LIMIT = 256;
}
