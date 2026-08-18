package com.example.myapp.framework.core;

/**
 * 校验步骤：一般放在管道入口（starter 之后），对 {@code target}（缺省 #payload，入口常为 #body）做校验。
 *
 * <p>两种互斥模式：</p>
 * <ul>
 *   <li><b>schema</b>：内联 JSON Schema（装配期预编译，失败时收集全部字段错误）；</li>
 *   <li><b>expression</b>：函数式校验，表达式返回 {@code false} 即失败（可直接调用 Bean：
 *       {@code @orderValidator.check(#payload)}）；表达式自身抛异常则原样传播（走领域异常映射）。</li>
 * </ul>
 *
 * <p>失败抛 {@link StepValidationException}（默认 400 + errorCode，message 支持 #{...} 模板）。</p>
 */
public interface Validator extends Step {
}
