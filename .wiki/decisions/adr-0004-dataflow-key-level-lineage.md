---
type: decision
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - CLAUDE.md
  - usecase-framework-core/src/main/java/com/example/usecase/framework/core/dataflow
  - docs/配置驱动用例编排框架设计文档.md
---

数据链显性化（2026-08-31 落地）的**粒度裁定：键级血缘**，键空间为 `payload` / `vars.x` / `biz.y`。**字段级血缘与值采集明确不做**——避免录制成本与敏感值泄漏面。

三件套形态：

- **声明**：`Step#dataflow()` + 装配期由 step config 自动推导
- **运行期录制**：`usecase.dataflow.record` 开关，**默认关**；只记键名、不记值
- **对照检查**：未声明写入 / 声明无人读取 → WARN（不阻断）

消费出口：`UseCaseRegistry#dataflowOf` / `ScenarioResult#trace()` / `UseCaseScenario#expectDataflow`。设计出处：项目设计文档 §3.2/§4.3 + README「排错与观测」。

**已知边界（录制模型的近似清单）：** 录制对 Map.of 构造的集合顺序、间接写入等场景有已知近似——断言勿依赖录制条目顺序。相关：[[usecase-orchestration]]、[[usecase-framework-core]]
