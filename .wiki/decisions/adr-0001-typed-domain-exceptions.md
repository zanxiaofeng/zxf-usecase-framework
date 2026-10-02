---
type: decision
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - CLAUDE.md
  - .claude/rules/exception-handling.md
  - usecase-framework-core/src/main/java/com/example/usecase/framework/core/exception
---

业务错误表达采用**模式 A（类型化领域异常）**：每个业务条件一个异常类 + `CODE` 常量，而非 `BusinessException` + 错误码枚举单体。

**为什么（判据出处 `.claude/rules/exception-handling.md` §2.1）：** 编排框架需要按异常类型分流（Step 失败路由、补偿、降级），且业务术语（Step 校验失败、用例未找到等）需要进入类型系统；错误码无集中治理/监控聚合需求。框架侧重 `core/exception/`（如 `StepValidationException`），demo 侧为 `domain/exception/`。

**已知偏差（存量待迁移）：** `ApiResponse` 信封存量代码为「明细拼 message」变体（无 `errors[]`），与规范标准结构不一致；新端点优先按标准结构，存量迁移另行排期，不与新代码混做。

相关：[[usecase-orchestration]]、[[usecase-framework-core]]
