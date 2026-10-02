---
type: decision
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - CLAUDE.md
  - pom.xml
---

本项目（编排框架及其 demo）**不引入持久层**：无 MySQL / JPA / Flyway / Kafka。

**含义：** `db-conventions.md`、`db-migration.md` 暂不适用；e2e 测试不写 `@Sql` 种子数据、不用 DatabaseVerifier——数据以内存适配器 / stub 端点提供（见 `tdd-workflow.md` 工程结构边界）。契约测试同样未引入。

**技术栈现役基线：** Web MVC / Jackson 3 / Validation / RestClient。MySQL / JPA / Flyway / Kafka / Spring Cloud Contract 为「引入后基线」，落地时按 `tech-stack.md` 执行并更新本页。

**不要做的事：** review 时不要把「缺 Repository/实体层」报为缺失——无持久层是选型，不是遗漏。相关：[[usecase-orchestration]]、[[adr-0003-orchestration-paradigm-deviations]]
