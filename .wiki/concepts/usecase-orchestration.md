---
type: concept
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - docs/配置驱动用例编排框架设计文档.md
  - usecase-framework-core/src/main/java/com/example/usecase/framework/core/spi
  - usecase-framework-core/src/main/java/com/example/usecase/framework/core/context
---

用例编排框架的核心心智模型：**业务逻辑写成 Step Bean，流程写成 YAML，入口由框架统一绑定**。

- **Step SPI**：自定义业务 Step 实现 `core/spi` 的 6 个接口之一，业务逻辑宿主在 Step Bean 内（而非 Service）
- **声明式编排**：用例 = YAML 中的步骤序列 + endpoint 声明；`UseCaseRouterFactory` 按 endpoint 统一绑定路由，业务侧零 Controller（范式裁定见 [[adr-0003-orchestration-paradigm-deviations]]）
- **三个数据命名空间**：`payload`（响应载荷）/ `vars.x`（步骤间变量）/ `biz.y`（业务数据）——dataflow 血缘的键空间即此三者（见 [[adr-0004-dataflow-key-level-lineage]]）
- **子用例调用**：`AbstractUseCaseClient` 类型化客户端 = 进程内入端口的具名化形态
- **排错与观测**：开 `usecase.dataflow.record` 看键级读写链路，配合对照检查发现未声明写入/无人读取

框架代码结构见 [[usecase-framework-core]]；数据转换步骤见 [[data-transfer-pipeline]]。无持久层，见 [[adr-0002-no-persistence-layer]]。
