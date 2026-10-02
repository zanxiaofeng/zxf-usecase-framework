---
type: decision
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - CLAUDE.md
  - usecase-framework-demo/src/main/java/com/example/myapp
  - docs/配置驱动用例编排框架设计文档.md
---

**裁定（2026-09-21）：** demo 应用对照六边形规范的两处「缺失」均为编排框架范式使然，保留现状，后续 review 不再报为问题：

1. **无 `application/port/in`** —— 入口不是手写 Controller，而是由框架 `UseCaseRouterFactory` 按 YAML endpoint 统一绑定；入端口概念被框架本身承担。
2. **无 `application/service`** —— 用例编排（步骤顺序、上下文传递、事务性边界）由框架引擎承担，业务逻辑宿主是自定义 Step Bean。

**依赖方向合规性：** `application/step`（自定义业务 Step Bean）与 `application/client`（`AbstractUseCaseClient` 类型化子用例客户端 = 进程内入端口的具名化形态）均指向领域，不违反六边形依赖规则。

相关：[[usecase-orchestration]]、[[adr-0002-no-persistence-layer]]
