---
type: module
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - data-transfer-core/src/main/java/com/example/datatransfer/core
  - pom.xml
---

data-transfer 聚合（三模块，groupId `com.example`，并入根 reactor 聚合）：

- **data-transfer-core**：转换引擎与 Schema 校验，基础包 `com.example.datatransfer.core`（自动配置包 `core/autoconfigure`，与 framework 侧命名统一）
- **data-transfer-test**：`TransferAssert` 等测试支撑
- **data-transfer-demo**：独立示例应用

与 usecase 框架的集成：usecase 侧提供**内置 `dataTransfer` step**（spec/source/as，装配期校验，`dataflow()` 覆写）。概念与能力见 [[data-transfer-pipeline]]；包结构历史见 [[adr-0005-package-restructure]]，集成坑见 [[build-and-test-pitfalls]]。
