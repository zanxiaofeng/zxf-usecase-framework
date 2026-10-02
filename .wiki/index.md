# Repo Wiki — Index

内容目录：每个页面一行，按 section 分组。新页面在此登记（格式：`- [[slug]] — 一句话摘要`），并在 `log.md` 追加一条 ingest 记录。

## Concepts

- [[usecase-orchestration]] — 用例编排核心心智模型：业务逻辑=Step Bean、流程=YAML、入口=框架统一绑定，三个数据命名空间
- [[data-transfer-pipeline]] — 声明式 JSON 转换框架：spec/source/as、`$` 暂存区两阶段转换、日期时间批次与 Clock 注入

## Modules

- [[usecase-framework-core]] — 框架核心模块：core/spi + core/context + dataflow + 引擎与自动配置，顶层用例模型
- [[data-transfer]] — data-transfer 三模块聚合（core/test/demo）及 usecase 内置 dataTransfer step 集成

## Decisions

- [[adr-0001-typed-domain-exceptions]] — 业务异常表达选模式 A（类型化领域异常 + CODE 常量）；信封存量为 message 拼接变体待迁移
- [[adr-0002-no-persistence-layer]] — 不引入持久层：e2e 用内存适配器/stub 端点，@Sql/DatabaseVerifier 不适用
- [[adr-0003-orchestration-paradigm-deviations]] — demo 无 port/in、无 application/service 为范式使然（2026-09-21 裁定，review 不再报缺失）
- [[adr-0004-dataflow-key-level-lineage]] — 数据链显性化取键级血缘；字段级血缘与值采集明确不做，录制默认关
- [[adr-0005-package-restructure]] — 2026-09-21 包结构重组：com.example.usecase.framework 更名 + core 细分 spi/context

## Systems

_（暂无页面——下游依赖与外部契约引入后再登记）_

## Playbooks

- [[build-and-test-pitfalls]] — 构建与测试踩坑清单：-rf 旧包名 jar、JDT Lombok 污染、Boot4.1=JUnit6、java.time toString 语义等
