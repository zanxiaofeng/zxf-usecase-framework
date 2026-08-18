package com.example.myapp.framework.core;

/**
 * 用例管道中的最小执行单元。
 *
 * <p>框架预置四个角色子接口，对应配置中的 step.type：</p>
 * <ul>
 *   <li>{@link DataLoader} —— 从出端口（Repository/Gateway）加载数据，写入 payload</li>
 *   <li>{@link DataTransformer} —— 转换 payload（映射、聚合、丰富）</li>
 *   <li>{@link HttpRequester} —— 调用外部 HTTP 服务（可挂 AuthHandler）</li>
 *   <li>{@link DataSaver} —— 将 payload 保存到出端口</li>
 * </ul>
 *
 * <p>Step 的两种提供方式：</p>
 * <ol>
 *   <li><b>内置 type</b>：YAML 中声明 {@code type: dataLoader} 等，由对应 StepFactory 按 config 创建；</li>
 *   <li><b>自定义 ref</b>：实现本接口（或其角色子接口）并注册为 Spring Bean，YAML 中 {@code ref: beanName} 引用。</li>
 * </ol>
 */
@FunctionalInterface
public interface Step {

    /**
     * 执行本步骤。约定：
     * <ul>
     *   <li>产出主数据 → {@code context.setPayload(...)}</li>
     *   <li>产出旁路数据 → {@code context.putVar(name, ...)}（供后续步骤经 SpEL {@code #vars.xxx} 引用）</li>
     *   <li>失败 → 抛出 RuntimeException，由 {@link UseCase} 包装为 StepExecutionException</li>
     * </ul>
     */
    void execute(StepContext context);

    /** 步骤名称，用于日志与异常定位。默认为实现类名。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
