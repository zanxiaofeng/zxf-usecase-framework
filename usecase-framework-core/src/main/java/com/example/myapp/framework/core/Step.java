package com.example.myapp.framework.core;

import com.example.myapp.framework.core.dataflow.Dataflow;

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
 * <p>角色接口是主数据流的核心抽象，也是治理锚点（按角色做配置审查/耗时统计）。其余内置 step 类型
 * 按语义就近归并：encoder/decoder 是数据变换的特例（实现 DataTransformer）；starter / logging /
 * validator / usecase / eventPublisher 为守卫/横切/组合动作，直接实现本接口。自定义 Step 同样自由
 * 选择：命中主数据流语义时实现对应角色，否则实现 Step 即可。</p>
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

    /**
     * 本步的键级数据流声明（读写哪些 {@code payload} / {@code vars.x} / {@code biz.y} 键），
     * 供装配期静态报告与运行期声明-实测对照检查使用。
     *
     * <p>默认 {@link Dataflow#UNKNOWN}（未声明）：运行期录制照常记录真实读写，但不参与对照检查。
     * 内置 step 的声明由装配器从配置推导，无需覆写。</p>
     */
    default Dataflow dataflow() {
        return Dataflow.UNKNOWN;
    }
}
