# zxf-usecase-framework

用例编排框架：无持久层的 Spring Boot 4 编排框架（Java 21），基于六边形架构。本文件是项目级说明与规范适配记录；通用编码规范见 `.claude/rules/`（服务所有同类项目的中立规范，项目选型以下方「规范适配」段为准）。

## 规范适配（项目选型记录）

`.claude/rules/` 中的规范为通用正文，涉及项目间可变选型处均以判据形式给出；本项目的实际选型记录如下，与规范正文冲突时以本段为准：

| 选型项 | 本项目状态 | 规范出处 |
|---|---|---|
| 业务异常表达模式 | **模式 A（类型化领域异常）**：`domain/exception/` + `CODE` 常量 | `exception-handling.md` §2.1 |
| ApiResponse 信封 | **存量代码为「明细拼 message」变体（无 `errors[]`）**；规范标准结构为含 `errors[]`（`api-conventions.md`）——存量待迁移，新端点优先按标准结构 | `api-conventions.md`、`exception-handling.md` §6.1 |
| NullAway + Error Prone | **已接入**：WARN 级试点，main 源集已清零、test 源集豁免；根 pom 可直接参考集成配置（Error Prone 2.36.0 + NullAway 0.12.7 / JDK 21） | `java-coding-standard.md` §4.2 |
| lombok.config | 根目录已有：`copyableAnnotations += org.jspecify.annotations.Nullable`；`lombok.addNullAnnotations = jspecify` **未启用**（可空契约暴露面小） | `java-coding-standard.md` §5.2 |
| 错误消息外化（i18n） | **未启用**：现有代码用中文字面量消息；引入 i18n 需求时按 `validation.md` §2.10 执行 | `validation.md` §2.10 |
| 持久层 | **未引入**：`db-conventions.md` / `db-migration.md` 暂不适用；e2e 数据以内存适配器 / stub 端点提供（`@Sql`、DatabaseVerifier 相关步骤跳过） | `db-conventions.md` 适用边界、`tdd-workflow.md` 工程结构边界 |
| 契约测试 | **未引入**：`contract-test.md` 落地时执行 | `contract-test.md` 适用边界 |
| 模块结构 | 当前单模块；出现编译瓶颈或需编译期隔离时按 `architecture.md` §11 演进多模块 | `architecture.md` §11 |
| 数据链显性化 | **已落地（2026-08-31）**：`core/dataflow` 包——声明（`Step#dataflow()` + 装配期 config 推导）、运行期录制（`usecase.dataflow.record`，默认关；只记键名不记值）、对照检查（未声明写入 / 声明无人读取 WARN）；消费出口 `UseCaseRegistry#dataflowOf` / `ScenarioResult#trace()` / `UseCaseScenario#expectDataflow`；粒度=键级血缘（`payload`/`vars.x`/`biz.y`），字段级血缘与值采集明确不做 | 本项目设计文档 §3.2/§4.3 + README「排错与观测」 |
| 技术栈现役范围 | Web/Jackson/Validation/RestClient 为现役基线；MySQL/JPA/Flyway/Kafka/Spring Cloud Contract 为引入后基线 | `tech-stack.md` 适用边界 |
| 编排框架范式差异（demo 对照六边形规范） | **裁定保留（2026-09-21）**：demo 无 `application/port/in`（入口由框架 `UseCaseRouterFactory` 按 YAML endpoint 统一绑定）、无 `application/service`（编排由框架引擎承担）均为范式使然；`application/step`（自定义业务 Step Bean = 业务逻辑宿主）与 `application/client`（`AbstractUseCaseClient` 类型化子用例客户端 = 进程内入端口的具名化形态）依赖方向合规。后续 review 不再报为缺失 | `architecture.md` §2 + 本项目设计文档 |
| 框架包结构（2026-09-21 重组） | core/test 模块基础包更名 `com.example.myapp.framework.*` → `com.example.usecase.framework.*`（demo 业务应用保留 `com.example.myapp.*`）；`framework/core` 细分：Step SPI 6 接口 → `core/spi`、上下文 4 类（StepContext/StepContextHolder/RequestBodyView/MdcScopes）→ `core/context`，顶层留用例模型（UseCase/UseCaseRegistry/UseCaseTrace）；`StepContextHolder#set/restore` 因跨包提升为 public；data-transfer 自动配置包 `core/spring` → `core/autoconfigure`（与 framework 侧命名统一）；demo stub 包 `adapter/in/web/demo` → `web/stub`；demo 补 `unit/application/` 单测层（GreetingStepTest/UserProfileTransformerTest） | `architecture.md` §2/§9 |
| 设计评审处置轮（2026-11-23） | 外部设计评审裁定落地（403/403 全绿）：**事件契约**＝尽力通知（afterCommit），事务性发件箱不得实现为 publisher 回调（原子性要求同事务写 outbox）、事件快照在步骤执行时固定；**事务边界**＝框架不开/不传播事务，原子写走显式 `@Transactional` 服务（设计文档 §5.11）；**isolate**＝容器级隔离（不隔离对象图/事务/外部副作用，不做深拷贝）；**错误消息**＝SpEL 求值失败 400 但客户端消息脱敏（原文只进日志）；validator null 结果 fail-closed、非 Boolean 抛 ISE；**logging 缺省打摘要**（类型+键集不记值）；errorMappings 限 400–599、endpoint.status 限 200–299 且禁 204/304；validator 三选一（expression/schema/**schemaRef** 资源读取——数字键对象 schema 的根治通道）；装配期新增校验：表达式语法预解析、step 名唯一、endpoint 唯一；Web 入口 MDC 快照恢复（seedTraceId 须在快照后）；`UseCaseInvoker` 深度上限 32；OAuth2 提前量自适应 `min(60s, TTL/2)`（缓存键本就含 scope 三元组）；demo GET 管道不落业务数据（saveSnapshot 挪 POST 演示）；Spring 版本归因三处修正（SpelNode/HttpMethod/MapAccessor，5.3/6.1 字节码实证） | 本项目设计文档 §4.3/§5.6–§5.11/§6.1–§6.2/§7.1/第八章 + `exception-handling.md` §5/§6、`downstream-conventions.md` §1 |
| data-transfer 评审二轮处置（2026-11-27） | 外部设计评审二轮逐条实证核验后落地（评审结论全部成立，另修 3 个评审未覆盖的缺陷；详见设计文档附录 D.6 与 D.6.8 行为变更清单）。**数值完整性**：旧结论「数值已 BigDecimal」不成立——恒等映射即丢精度（json-base 窄化 + 库 unflatten 经默认 mapper 文本往返），现 flatten 出口 BigDecimal、自建 unflatten、表达式 `DecimalArithmetic`，输出形态保持历史一致；**表达式安全模型**：`JexlSandbox(true)` 实为 allow-box（零限制，拦截全靠 JEXL 默认权限且放行文件读写），现默认拒绝 + 运行期具体类型（JDK 21 `LinkedHashMap` 只直接实现 `SequencedMap`，权限继承不认 `Map`）只读白名单 + `safe` 关闭；加载链只解析一次、重复键与 YAML 别名拒绝（Jackson YAML 不解析别名，`*anchor` 会被静默读成字面量字符串而 Schema 照样通过；合并键 `<<` 同被拒）；`CompiledPlan` 不可变执行快照；nullPolicy 三态（`default` 不再等于 `keep`）；目标结构冲突装配期拒绝 + strictMode 运行期覆盖检测；契约测试严格 JSON 类型（`withNumericStringCoercion` 显式宽松）与单值断言路径必须存在；通配断言枚举元素；新增 `ExpressionException`（usecase `errorMappings` 如需映射按新类名配置）；正文 Schema 与仓库文件逐字一致并由 `DesignDocExamplesTest` 守护。**两轮独立审查后的二次加固（同日）**：标量关闭属性读取（JEXL 属性访问即方法调用，`s.class` / `s.bytes` 可达白名单外 getter）+ `Object#getClass` 在权限层封禁 + 显式收紧 `JexlFeatures`（`new` / lambda / 循环在解析期拒绝；JEXL 特性集默认全开，`createExpression` 只关脚本位）；computed / defaults 的 `to` 索引规范化；含小数点 / 指数的字符串操作数按十进制运算（`+` 仍是拼接）；数组叶值展开（`byte[]` 此前栈溢出）；带引号的 `'$path'` 实参是字面量；引用检查跳过字符串字面量 | data-transfer 设计文档附录 C.4 / D.6（含 D.6.9） |
