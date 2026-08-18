package com.example.myapp.framework.core;

/**
 * 起始步骤：用例开始执行时从请求中提取关键业务标识（businessId、租户、渠道、traceId 等），
 * 写入 {@link StepContext} 的 {@code biz} 关键数据区，并同步到日志 MDC（{@code biz.*}）。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>放在管道的第一个 step（框架不强制，但推荐，便于后续步骤经 {@code #biz.xxx} 引用）；</li>
 *   <li>{@code businessId} 是约定俗成的关键键名，日志与排障围绕它展开；</li>
 *   <li>Web 入口会在管道执行前自动写入 {@code traceId}（来自 X-Request-Id 头或生成 UUID）。</li>
 * </ul>
 */
public interface Starter extends Step {
}
