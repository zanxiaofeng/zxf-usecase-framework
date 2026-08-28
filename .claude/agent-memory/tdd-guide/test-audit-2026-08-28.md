---
name: test-audit-2026-08-28
description: 2026-08-28 全套件测试质量审计核心结论——e2e 事件断言顺序耦合已实证、errorMappings 回退链无锚定、getUserProfile 成功旅程零覆盖
metadata:
  type: project
---

161 个测试全绿基线下完成全套件 TDD/质量审计，判决 Partially Compliant（断言质量高，三类高风险缺口）。

**Why:** 高优先级发现均经代码+运行实证，修复前新增测试易翻车、关键行为回归无保护。

**How to apply:** 后续对话涉及测试补齐或 review 时按此清单核对是否已修复。

高优先级（3 项）：
1. UseCaseRouterE2eTest.createUserSnapshot_publishesDomainEventAfterSave 用 singleElement() 断言 InMemoryEventPublisherAdapter（无 clear API、测试无清理）——随机方法顺序 6 次运行 5 次失败，事实顺序依赖。修复方向：适配器加 clear() + @AfterEach 重置，或断言改过滤式。
2. errorMappings 全链路仅一条 e2e（UserNotFoundException 全限定名→404）；ErrorResponseMapperTest 构造传 Map.of()，简单名匹配/覆盖 defaultHttpStatus/@ResponseStatus 优先级/漏配回退（DomainException 不实现 ErrorCoded，拼错 key → 404 静默变 500 固定文案）全部无锚定——即「errorMappings key 写错静默失效」待决策项无测试使行为可见。
3. getUserProfile 完整成功旅程（串联子用例→httpRequester Bearer 成功→validator→encoder→ref step→dataSaver）零锚定：e2e 类级 properties 把 credit.base-url 指向不可达地址只剩 502 路径；CreditScoreStubController 无测试触达；CREDIT_TOO_LOW 400 亦无 e2e。

中优先级要点：UseCaseInvokerTest:98 测试名 "andSeedsTraceId" 无 traceId 断言；UseCaseRouterFactory 空路由防御分支（:69-74）无测试；RequestBodyView 空 body/非 JSON/GET 忽略体/惰性缓存无直接测试；HttpRequesterStep POST body 与未知 auth scheme 无测试（ErrorResponseMapper 的 HttpStepException→502 分支因此也无锚定——demo e2e 的 502 实走 ResourceAccessException 分支）；UseCaseScenario run() 异常传播契约未测。

质量亮点（保持）：断言精确（sha256/base64url 逐字符）；装配期 fail-fast 覆盖密度高；MDC/事务状态/日志 appender 均有清理；无过度 mock。
