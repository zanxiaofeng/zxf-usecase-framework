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
| 技术栈现役范围 | Web/Jackson/Validation/RestClient 为现役基线；MySQL/JPA/Flyway/Kafka/Spring Cloud Contract 为引入后基线 | `tech-stack.md` 适用边界 |
