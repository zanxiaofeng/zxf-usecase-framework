---
type: decision
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - CLAUDE.md
  - usecase-framework-core/src/main/java/com/example/usecase/framework
---

2026-09-21 包结构重组，动机是把「框架自身」与「demo 业务应用」的命名空间分开：

- core/test 模块基础包 `com.example.myapp.framework.*` → **`com.example.usecase.framework.*`**（demo 业务应用保留 `com.example.myapp.*`）
- `framework/core` 细分：Step SPI 6 接口 → `core/spi`；上下文 4 类（StepContext / StepContextHolder / RequestBodyView / MdcScopes）→ `core/context`；顶层留用例模型（UseCase / UseCaseRegistry / UseCaseTrace）
- data-transfer 自动配置包 `core/spring` → `core/autoconfigure`（两侧命名统一）
- demo stub 包 `adapter/in/web/demo` → `web/stub`；demo 补 `unit/application/` 单测层
- `StepContextHolder#set/restore` 因跨包提升为 public

重组后 375 测试全绿。**坑：** 恢复构建（`-rf`）会从 `~/.m2` 取旧包名 jar，必须全量 reactor 跑——见 [[build-and-test-pitfalls]]。相关：[[usecase-framework-core]]、[[data-transfer]]
