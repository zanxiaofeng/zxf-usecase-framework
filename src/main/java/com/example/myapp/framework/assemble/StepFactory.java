package com.example.myapp.framework.assemble;

import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.Step;

/**
 * 步骤工厂 SPI：把 YAML 中的 {@code type + config} 翻译成 Step 实例。
 *
 * <p>框架内置 dataLoader / dataTransformer / httpRequester / dataSaver 四种类型；
 * 扩展新类型只需注册一个 StepFactory Bean，{@code type()} 返回新类型名即可在 YAML 中使用。
 */
public interface StepFactory {

    /** 步骤类型名（YAML 中 step.type 的值）。 */
    String type();

    /** 按配置创建 Step 实例。配置非法时抛出 UseCaseAssemblyException（启动期 fail-fast）。 */
    Step create(StepDefinition definition);
}
