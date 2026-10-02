---
type: playbook
status: accepted
owner: davis
stale_after: 2027-04-02
sources:
  - pom.xml
  - usecase-framework-core
  - data-transfer-core
---

构建与测试的已实证踩坑清单（按症状索引）：

## 构建

- **`-rf` 恢复构建后编译错/旧类**：`~/.m2` 里残留旧包名 jar（2026-09-21 [[adr-0005-package-restructure]] 更名后）→ 必须全量 reactor 跑，别用 `-rf`
- **`-pl` 单模块跑不到最新依赖**：`-pl` 须搭配 `-am`
- **全模块测试突然全 Error**：IDE（JDT）的 Lombok 误报坏 class 污染 `target/` → `mvn clean test` 解；勿信 IDE 诊断先行重跑 Maven

## 版本/依赖

- **Boot 4.1 = JUnit 6**：测试注解/断言按 JUnit 6 行为核对
- **Jackson 3 YAML**：必须 `YAMLMapper.builder()` 构造，直接 new 行为不符
- **json-flattener**：依赖 json-base 3.0.0，坐标别引错
- **wildcardPattern 含 `$`**：`$` 是正则锚字符，须转义

## 数据/断言语义

- **Map.of 顺序不定**：录制/断言勿依赖其迭代顺序
- **YAML 测试拼接顶层键重复**：静默覆盖，肉眼难察——测试资源拼键时先查重
- **java.time toString 省略零秒段**（`...:08:00` 而非 `08:00:00`）：断言用 formatter 解析（ISO_OFFSET_DATE_TIME）
- **ZonedDateTime toString 带 `[zone]` 后缀**：直接 equals 会挂
- **空容器保留为叶子值 / `[0]` 方括号**：flatten 语义，`[0]` 须转义
- **MOCK 环境无自引用端口**：e2e 中自引用端口的 stub 方式受限
- **default() 仅兜 null**：不兜「键不存在」以外的场景
