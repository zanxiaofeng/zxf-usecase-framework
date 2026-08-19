package com.example.myapp.framework.core;

/**
 * 日志步骤：在管道任意位置输出一条结构化日志。
 * 消息支持 #{...} 模板（可引用 biz / vars / payload 等），日志 category 为 {@code usecase.step.<stepName>}，
 * 便于按步骤名做日志级别治理；配合 starter 写入的 MDC（biz.*）实现全链路关联。
 */
public interface Logging extends Step {
}
