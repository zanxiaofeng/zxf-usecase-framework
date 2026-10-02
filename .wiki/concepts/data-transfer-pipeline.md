---
type: concept
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - docs/
  - data-transfer-core/src/main/java/com/example/datatransfer/core
---

data-transfer：声明式 JSON 数据转换框架，基于 flatten/unflatten，在用例编排中以**内置 `dataTransfer` step** 形态消费（spec/source/as 三要素，装配期 Schema 校验 fail-fast）。

- **两阶段转换 + `$` 暂存区**（2026-09-24 批次）：Phase 1.5 在源上下文求值——`rules.from`、`transform` 实参、`computed` 均可引用暂存键；前向引用被约束禁止；容器暂存会展开
- **异常体系 + validations**（v1.1）；**P0 聚合索引对位**、注解元标注 `@ExtendWith`、BigDecimal 大整数比较（v1.2）
- **日期时间批次**：`dateFormat` / `epochToIso` / `toIsoDate` / `toIsoDateTime` / `now` + `dateBefore` / `dateAfter` / `dateNotBefore` / `dateNotAfter` 断言；Clock 注入（`FuncRegistry` 实例持有 + 引擎第 4 参 + `TransferAssert.withClock`），设计文档附录 D.4
- **裁剪语义**：`sources` / `rewrites` 配置 fail-fast

日期解析的坑（java.time toString 省略零秒段、ZonedDateTime 带 `[zone]` 后缀）见 [[build-and-test-pitfalls]]。模块结构见 [[data-transfer]]；接入方式见 [[adr-0005-package-restructure]]。
