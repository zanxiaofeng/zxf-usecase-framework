# Repo Wiki — Log

## [2026-10-02] init

- 初始化 `.wiki/` 目录结构：`_template.md` / `index.md` / `log.md` + `concepts/` `modules/` `decisions/` `systems/` `playbooks/` `bin/`
- 由 `.claude/skills/repo-wiki/scripts/wiki_lint.py` 复制 lint 脚本至 `.wiki/bin/wiki_lint.py`

## [2026-10-02] ingest — 初始内容

首轮内容沉淀，素材：CLAUDE.md「规范适配」段 + 项目 memory 中的设计裁定与踩坑记录。新增页面：

- decisions/adr-0001-typed-domain-exceptions
- decisions/adr-0002-no-persistence-layer
- decisions/adr-0003-orchestration-paradigm-deviations
- decisions/adr-0004-dataflow-key-level-lineage
- decisions/adr-0005-package-restructure
- concepts/usecase-orchestration
- concepts/data-transfer-pipeline
- modules/usecase-framework-core
- modules/data-transfer
- playbooks/build-and-test-pitfalls

另：`systems/` 仍空，补 `.gitkeep` 保持目录入库。
