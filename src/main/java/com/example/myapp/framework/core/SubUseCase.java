package com.example.myapp.framework.core;

/**
 * 子用例调用步骤：把另一个 usecase（通常 {@code shared: true}）嵌入当前管道执行。
 *
 * <p>数据传递语义（关键设计）：</p>
 * <ul>
 *   <li><b>输入</b>：{@code config.input} 表达式求值结果作为子用例初始 payload（缺省 {@code #payload} 继承父）；</li>
 *   <li><b>输出</b>：遵循统一 as 规则——配置 {@code as} 时子用例结果写入 {@code #vars[as]} 且父 payload 恢复不变
 *       （旁路调用）；缺省时子用例结果成为父 payload（串联模式）；</li>
 *   <li><b>vars / biz</b>：默认与父共享同一实例——子用例可读父的旁路结果与关键数据，其写入父亦可见；
 *       {@code isolate: true} 时子用例 vars 全新（不污染父），biz 拷贝继承（子的修改不回传）；</li>
 *   <li><b>循环引用</b>：装配期 DFS 检测，fail-fast。</li>
 * </ul>
 */
public interface SubUseCase extends Step {
}
