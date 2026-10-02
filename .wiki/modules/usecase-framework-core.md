---
type: module
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - usecase-framework-core/src/main/java/com/example/usecase/framework
---

框架核心模块，基础包 `com.example.usecase.framework.*`（2026-09-21 更名与细分，见 [[adr-0005-package-restructure]]）。

| 包 | 职责 |
|---|---|
| `core/spi` | Step SPI 6 接口——自定义业务 Step 的契约 |
| `core/context` | 运行上下文 4 类：StepContext / StepContextHolder / RequestBodyView / MdcScopes |
| `core/dataflow` | 数据链显性化：声明、录制（DataflowRecorder/RecordingMap）、对照检查（DataflowConformance） |
| `core/exception` | 框架级场景异常（模式 A，见 [[adr-0001-typed-domain-exceptions]]） |
| `core/invoke` | 引擎执行（含错误映射入 invoke 保 MDC 现场、afterCommit publish 兜底 catch） |
| `assemble` / `autoconfigure` | 装配期（config 推导 dataflow、未知键 fail-fast）与自动配置 |
| `steps` / `steps/config` | 内置 step（含 dataTransfer step）与 step 配置解析 |
| `expression` / `codec` / `http` / `web` / `auth` | 表达式求值、编解码、HTTP 客户端、路由 Web 绑定、认证 |

顶层留用例模型：`UseCase` / `UseCaseRegistry` / `UseCaseTrace`。概念总览见 [[usecase-orchestration]]；配套 `usecase-framework-test`（场景测试 API）与 `usecase-framework-demo`（业务示例，包名 `com.example.myapp.*`）。
