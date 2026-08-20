# usecase-framework —— 配置驱动的用例编排框架

在六边形架构之上，把「应用服务编排」下沉为框架能力：**usecase 由 YAML 声明，step 自动装配成管道，RouterFunction 完成 endpoint 绑定**。新增一个 API 往往只需要一段配置，无需再写 Controller / Service 样板代码。

## 模块结构（Maven 多模块）

```
usecase-framework（聚合 POM）
├── framework-core     # 框架本体（独立库 jar）：core/assemble/steps/auth/codec/expression/web
│                      # + AutoConfiguration.imports —— 经 spring-boot 自动配置装配，可单独发布
├── framework-test     # YAML 用例测试 harness（独立库 jar，业务方 test scope 引用）：
│                      # UseCaseScenario 场景断言 + RecordingEventPublisher 事件探针
└── demo               # 演示应用（可执行 jar）：MyAppApplication + application/domain/infrastructure
                       # + application.yml 用例定义 + e2e 测试
```

## 快速开始

```bash
# 技术基线：Java 21 · Spring Boot 4.1.x · Maven 3.9+
# 根目录执行（-am 会先构建 framework-core）
mvn -pl demo -am spring-boot:run

# 或打包后运行
mvn -pl demo -am package -DskipTests
java -jar demo/target/usecase-framework-demo-1.0.0-SNAPSHOT.jar
```

启动日志会打印装配出的路由表：

```
shared usecase [userBaseEnrichment] steps=[loadUser, toDto]
route: GET /api/v1/users/{id} -> usecase [getUser] steps=[start, validateInput, loadUserBase]
route: GET /api/v1/users/{id}/profile -> usecase [getUserProfile] steps=[start, loadUserBase, fetchCredit, checkCreditPass, logCredit, encodeUserId, userProfileTransformer, saveSnapshot]
route: GET /api/v1/users/token/{token} -> usecase [getUserByToken] steps=[start, decodeToken, loadUserBase]
route: GET /api/v1/users/{id}/greeting -> usecase [greetUser] steps=[start, GreetingStep]
route: POST /api/v1/user-snapshots -> usecase [createUserSnapshot] steps=[start, validateBody, checkUserExists, logCreation, toSnapshot, saveSnapshot, publishSnapshotCreated]
```

```bash
curl http://localhost:8080/api/v1/users/u1
# {"code":"000000","data":{"id":"u1","name":"Alice"},...}

curl http://localhost:8080/api/v1/users/u1/profile
# {"code":"000000","data":{"id":"u1","name":"Alice","creditScore":760,"creditLevel":"A","snapshotId":"snap-1001",...}}
```

> demo 的信用下游由 `CreditScoreStubController`（仅演示用）在本应用内模拟；生产环境删除该类并把 `credit.base-url` 指向真实下游。

## 定义一个用例

`demo/src/main/resources/application.yml`：

```yaml
usecase:
  definitions:
    - id: getUserProfile
      endpoint: { method: GET, path: /api/v1/users/{id}/profile }
      steps:
        - name: loadUser
          type: dataLoader
          config:
            expression: "@userRepository.getById(T(com.example.myapp.domain.model.UserId).of(#path.id))"
        - name: fetchCredit
          type: httpRequester
          config:
            url: "${credit.base-url}/scores/{userId}"
            uriVariables: { userId: "#path.id" }
            auth: { scheme: bearer, options: { token: "${credit.token}" } }
            as: credit                      # 旁路输出到 #vars.credit，不动 payload
        - name: mergeProfile
          ref: userProfileTransformer       # 引用自定义 Step Bean（与 type 二选一）
        - name: saveSnapshot
          type: dataSaver
          config:
            expression: "@profileSnapshotRepository.save(#payload)"
```

## step 类型

| type | 角色接口 | 职责 | 内置配置 |
|------|---------|------|---------|
| `starter` | Step | 用例开始提取 businessId 等关键标识 → biz 关键数据区（+MDC） | `keys`（键→表达式 map） |
| `dataLoader` | DataLoader | 出端口读数据 → payload | `expression`, `as` |
| `dataTransformer` | DataTransformer | payload → payload（表达式为 null 默认清空并 WARN） | `expression`, `as`, `onNull`（`keep` 保留原 payload） |
| `httpRequester` | HttpRequester | 调用外部 HTTP | `method/url/uriVariables/headers/body/auth/as` |
| `logging` | Step | 管道任意位置输出日志（不改数据） | `level`, `message`（#{} 模板） |
| `encoder` | DataTransformer | 编码/摘要（base64/base64url/url/hex/md5/sha256；数据变换特例） | `algorithm`, `source`, `as` |
| `decoder` | DataTransformer | 解码（要求算法可逆，装配期校验；数据变换特例） | `algorithm`, `source`, `as` |
| `dataSaver` | DataSaver | payload → 出端口 | `expression`（返回 null 保留 payload）, `as` |
| `validator` | Step | 入口校验（schema / 函数式，二选一） | `expression` 或 `schema`, `target`, `message`, `errorCode` |
| `usecase` | Step | 嵌入 shared 用例作为子用例 | `ref`=目标用例 id, `input`, `as`, `isolate` |
| `eventPublisher` | Step | 发布领域事件（活动事务内 afterCommit、提交后才外发，回滚不发布；无事务立即发布） | `event`（SpEL 构造事件，必填）, `publisher`（Bean 名，缺省取唯一实现，装配期校验存在性/唯一性/@Primary） |
| 自定义 | 命中主数据流语义时实现对应角色接口，否则实现 Step | 任意逻辑 | `ref: beanName` |

## starter 与关键数据区（biz）

```yaml
- name: start
  type: starter
  config:
    keys:
      businessId: "#path.id"                    # 约定键：关键业务 ID
      tenantId: "#headers['X-Tenant-Id']"       # 完整 SpEL
      channel: "#{headers['X-Channel']}"        # 模板形式
      source: "app"                             # 字面量
```

- 写入 `StepContext` 的 **biz 关键数据区**，后续步骤经 `#biz.businessId` 引用；
- 非 null 值同步到日志 MDC（键 `biz.<key>`，值剥离控制字符防日志注入），请求结束由 Web 层自动清理，防止线程复用串号；
- Web 入口在管道执行前自动写入 `traceId`（取 `X-Request-Id` 头并做白名单校验 `[A-Za-z0-9_-]{8,128}`，不合法或缺失时生成 UUID），写入 MDC（键 `traceId`，供 logback `%X{traceId}` 全链路关联）并回填到响应信封 `traceId` 字段。
- **装配期护栏**：`keys` 不得写保留键 `traceId`（装配即报错）；shared 用例内含 starter 会打 WARN（串联嵌入时共享父上下文，子的 starter 会覆写父管道 biz）。

## encoder / decoder

```yaml
- name: encodeUserId
  type: encoder
  config:
    algorithm: base64url     # base64/base64url/url/hex/md5/sha256；自定义 Codec Bean 可扩展
    source: "#biz.businessId" # 缺省 #payload
    as: encodedUserId         # 缺省写回 payload
```

`md5`/`sha256` 为单向摘要，仅可用于 encoder；decoder 引用会在启动期装配失败（fail-fast）。

## shared 用例与子用例调用

用例分两级：**endpoint 用例**（绑定路由，对外服务）与 **shared 用例**（`shared: true`，无 endpoint，仅作为子用例被嵌入）：

```yaml
- id: userBaseEnrichment
  shared: true                        # 不绑定 endpoint，不参与路由
  steps: [ ... ]

- id: getUser
  endpoint: { method: GET, path: /api/v1/users/{id} }
  steps:
    - name: loadUserBase
      type: usecase
      ref: userBaseEnrichment          # 目标用例 id（装配期校验存在性 + DFS 环检测）
      config:
        input: "#biz.businessId"       # 子用例初始 payload，缺省 #payload
        as: userDto                    # 可选：结果旁路到 #vars.userDto，父 payload 不动
        isolate: false                 # 可选：true 时子的 vars 隔离、biz 拷贝继承
```

数据传递约定：

| 配置 | 子用例输入 | 子结果落点 | vars / biz 可见性 |
|------|-----------|-----------|------------------|
| 默认（串联） | `input` 缺省 = 父 payload | 成为父 payload | 与父共享同一实例，子的写入对后续步骤可见 |
| `as: x`（旁路） | `input` 表达式 | 写入 `#vars.x`，父 payload 恢复 | 同上 |
| `isolate: true` | `input` 表达式 | 按 `as` 规则落点 | **vars 全新**（不污染父）；**biz 拷贝继承**（子可读父的 businessId，但子的修改不回传）；**MDC 快照恢复**（子内 MDC 写入返回时回滚，不污染父管道日志） |

异常穿透子用例边界：子用例抛出的领域异常原样沿包装链上抛，由父用例所在端点统一映射（如 `UserNotFoundException` → 404）。装配期 fail-fast：`ref` 指向不存在的用例、直接/间接循环引用（A→B→A）、`shared: false` 却缺 endpoint，都会在启动期报错。

## Java 代码调用子用例（UseCaseInvoker）

shared 用例不仅能在 YAML 里被 `type: usecase` 嵌入，也能被任意 Java 代码直接调用。框架提供两层 API：

**门面 `UseCaseInvoker`**（自动注册为 Bean，注入即用）：

| 方法 | 语义 |
|------|------|
| `invoke(id, input)` | 管道内共享当前上下文（vars/biz 互通、traceId 继承），**父 payload 自动恢复**；管道外退化为独立调用（打 DEBUG 痕迹） |
| `invokeShared(id, input)` | 严格共享：要求管道内，管道外立即抛 `IllegalStateException`（异步边界 ThreadLocal 丢失时不会静默断链） |
| `invokeIsolated(id, input)` | 隔离：子用例 vars 全新、biz 拷贝继承（子的修改不回传）、MDC 快照恢复 |
| `invokeStandalone(id, input)` | 独立：全新上下文 + 空请求抽象，自动种子化 traceId（调度任务/消息消费等管道外场景） |

> **异步边界**（@Async / CompletableFuture / 虚拟线程切换）：ThreadLocal 不随线程迁移，共享语义必然失效——跨边界显式用 `invokeStandalone`，把 traceId 与必要 biz 键作为 input 显式传入。类型化客户端结果与声明类型不符时抛 `UseCaseResultTypeException`（指明用例 id 与期望/实际类型），替代裸 `ClassCastException`。

**类型化客户端基类 `AbstractUseCaseClient<I, O>`**（推荐）：为每个 shared 用例声明一个客户端，业务代码像普通方法一样调用：

```java
@Component
public class UserBaseClient extends AbstractUseCaseClient<String, UserDto> {
    public UserBaseClient(UseCaseInvoker invoker) {
        super(invoker, "userBaseEnrichment", UserDto.class);
    }
}

@Component("greetingStep")
public class GreetingStep implements DataTransformer {
    public void execute(StepContext context) {
        UserDto user = userBaseClient.invoke(String.valueOf(context.getBiz("businessId")));
        context.setPayload(Map.of("greeting", "Hello, " + user.name()));
    }
}
```

机制：`UseCase.execute` 执行期间把当前 `StepContext` 绑定到执行线程（`StepContextHolder`），同线程 Java 代码无需传参即可继承上下文；嵌套执行保存/恢复上一层，管道结束必然清理。与 YAML 串联模式的差异：Java 调用是**函数式取值**——父 payload 总是被恢复，结果经返回值返回；异常语义一致（领域异常穿透子用例边界）。

> 注意：`UseCaseInvoker` 内部以 `Supplier<UseCaseRegistry>` 延迟解析注册表——若直接持有 registry，`registry → ref step Bean → client → invoker` 会形成 Bean 创建循环（demo 的 `GreetingStep` 正是此形态）。

## validator（入口校验）

一般放在管道入口（`target: "#body"` 校验请求体，缺省 `#payload`）。两种互斥模式，装配期强制二选一：

```yaml
# schema 模式：JSON Schema 2020-12（networknt 3.x，装配期预编译）
- name: validateBody
  type: validator
  config:
    target: "#body"
    schema:
      type: object
      required: [userId, name]
      properties:
        userId: { type: string, minLength: 1 }
      additionalProperties: false

# 函数模式：SpEL 返回 false 即失败；表达式抛出的领域异常原样传播走领域映射
- name: checkCreditPass
  type: validator
  config:
    expression: "#vars.credit.score >= 600"
    message: "用户 #{biz.businessId} 信用分不足"   # 支持 #{} 模板
    errorCode: "CREDIT_TOO_LOW"                    # 缺省 VALIDATION_ERROR
```

失败抛 `StepValidationException`：schema 模式附全部字段错误明细（最多 5 条）；HTTP 默认映射 **400**（`ErrorCoded.defaultHttpStatus()`），可被 `usecase.error-mappings` 覆盖。异常语义：函数模式中 SpEL **求值失败**（如 `#vars.credit` 为 null 时取 `.score`）同样映射 400（消息附求值错误）；而表达式调用的 Bean 方法抛出的**领域异常**不经 SpEL 包装，原样传播走领域映射。

## logging

```yaml
- name: logCredit
  type: logging
  config:
    level: INFO              # TRACE/DEBUG/INFO/WARN/ERROR，缺省 INFO
    message: "用户 #{biz.businessId} 信用分: #{vars.credit.score}"   # 缺省打印 payload
```

日志 category 为 `usecase.<useCaseId>.step.<stepName>`，可按用例或步骤名定向治理级别；配合 MDC 的 `biz.*` 实现全链路关联。

## SpEL 求值上下文

| 变量 | 含义 | 示例 |
|------|------|------|
| `#path` / `#query` / `#headers` | 路径变量 / 查询参数 / 请求头 | `#path.id` |
| `#body` | 请求体（JSON→Map/List） | `#body.items[0]` |
| `#payload` | 当前主数据 | `#payload.id` |
| `#vars` | 旁路命名结果 | `#vars.credit.score` |
| `#biz` | 关键数据区（starter 写入） | `#biz.businessId` |
| `@beanName` | 容器内任意 Bean | `@userRepository.getById(...)` |
| `T(fqcn)` | 类型引用 | `T(com.example.UserId).of(...)` |

内嵌字段（header 值、uriVariable 值）支持三种写法：字面量原样返回；`#{...}` 走模板拼接（如 `"Bearer #{vars.token}"`，模板以 StepContext 为根对象）；以 `#` / `@` / `T(` 开头按完整 SpEL 求值。

> **Spring 7 注意**：内置 `MapAccessor` 自 6.1 起仅当 key 存在时才认领读取（为表达式编译服务），探测可选字段（如 `#body.reason`）会抛 EL1008E；且自 Framework 7 起已 deprecated forRemoval。框架注册了宽容的 `LenientMapAccessor`：Map 上缺失 key 一律返回 null，使 `#body.userId` 这类可选字段探测安全可用。表达式按原文缓存解析结果（键空间仅来自 YAML 配置，有限集合），避免每次执行重复构建语法树。

## 认证（httpRequester.auth）

| scheme | options | 说明 |
|--------|---------|------|
| `none` | — | 无认证（默认） |
| `basic` | `username`, `password` | HTTP Basic |
| `bearer` | `token` 或 `tokenProvider` | 静态令牌 / TokenProvider Bean 动态令牌 |
| `apiKey` | `header`, `value` | 请求头形式的 API Key |
| `clientCredentials` | `tokenUrl`, `clientId`, `clientSecret`, `scope?` | OAuth2 CC，自动换 token 并缓存（提前 60s 过期；按 (tokenUrl, clientId) 分键原子刷新，不同端点互不阻塞；令牌端点调用默认连接 3s / 读取 10s） |
| 自定义 | 任意 | 实现 `AuthHandler` 注册为 Bean，`scheme()` 即类型名 |

## 错误映射

```yaml
usecase:
  error-mappings:
    com.example.myapp.domain.exception.UserNotFoundException: 404   # 全限定名或简单类名
```

- 领域/框架异常（实现 `ErrorCoded` 或提供 `getErrorCode()`）：状态码按 **配置 → `@ResponseStatus` → `ErrorCoded.defaultHttpStatus()`（如校验失败默认 400）→ 500** 解析，<500 透传业务消息，≥500 固定文案；
- 内置 step 的 SpEL **求值失败**统一收口为 400（消息附 step 名与求值错误）；Bean 方法抛出的领域异常不经 SpEL 包装，原样传播走领域映射；
- 失败 WARN/ERROR 日志附带**键级数据现场**（payload 类型 + vars/biz 键名，不带值，防 PII 入日志；嵌套子用例取最内层现场）；
- 请求体 JSON 语法错误 → 400 `BAD_REQUEST`（明确报错，而非静默置 null 导致误导性 schema 明细；空体仍为 null，非 JSON 内容类型按纯文本处理）；
- 下游 HTTP 失败 / 连接超时 → 502 `DOWNSTREAM_ERROR`；
- 兜底 → 500 `INTERNAL_ERROR`，绝不回显内部异常消息。

> **Boot 绑定注意**：`Map<String, Object>` 类型的 step config 中，YAML 列表（如 validator 的 `required: [a, b]`）会被绑定成索引 Map（`{0=a, 1=b}`）。validator 装配期会把「键全为非负整数」的 Map 递归还原为 List；若自定义 step 的 config 含嵌套列表，需同样留意。

> **覆盖顺序保证**：自定义 AuthHandler / Codec 与内置实现同名时**自定义覆盖内置**——内置实现在注册表 Map 装配时先落位，用户自定义 Bean 后覆盖，不依赖 Bean 注入顺序。

> RouterFunction `onError` 之外的异常（Filter / 容器层 / 无匹配路由 404）会落到 Boot 默认 `/error` 端点；demo 已配置 `server.error.include-message/stacktrace/binding-errors: never` 关闭信息泄露。

## 排错与观测

```yaml
usecase:
  report: true            # 默认开：启动期输出每用例数据流报告（biz/vars 的静态读写视图，仅日志）
  trace:
    enabled: false        # 默认关：开启后每步 INFO 轨迹（payload 类型迁移、新增 vars 键、耗时）
    include-values: false # 默认关：显式二次开启才输出值快照（截断 256 字符，防大对象/敏感值）
```

- **装配期 as 键碰撞 WARN**：同一 vars 键存在多个声明式写入点（含串联子用例合并，写入点带 `childId.` 前缀）时启动期打 WARN——只 WARN 不 fail（存在有意覆盖的合法用法）；静态可见范围仅声明式 `as` 键，自定义 step 的运行期写入不在其列；
- **数据流报告**：`usecase.report` 输出各用例 `dataflow: <id>` 段——biz 写入（starter keys）、vars 写入（as 键）、表达式读取（SpEL AST 首段静态分析），供配置审查与新人上手；
- **dev trace**：`usecase.trace.enabled` 开启后逐条 step 输出 `payload null -> UserDto, vars +[credit], 3 ms` 形态轨迹；关闭时执行路径零开销（无快照、无键集拷贝）。

## YAML 用例测试（framework-test）

配置驱动意味着 **YAML 用例本身就是回归对象**。`framework-test` 模块（业务方以 test scope 引入）提供 `UseCaseScenario`：构造接近真实的 `ServerRequest`（MockHttpServletRequest 打底，path/query/header/body 语义与路由入口一致），经 `StepContext.of` 走真实管道执行，然后断言最终 payload、vars、biz 与已发布事件：

```java
UseCaseScenario.given(registry, objectMapper)          // @SpringBootTest 中注入二者
        .request("POST", "/api/v1/user-snapshots")     // 按 method + path 模板定位用例（也可 .useCase("id")）
        .body("{\"userId\":\"u1\",\"name\":\"alice-snap\"}")
        .recordingEventsTo(eventRecorder)              // RecordingEventPublisher 探针（@Primary 注册即接管 eventPublisher 步骤）
        .expectBiz("businessId", "u1")
        .expectVar("userDto", dto -> ...)
        .expectPayload(payload -> ...)
        .expectEventPublished(SnapshotCreatedEvent.class, event -> ...)
        .run();                                        // 返回 ScenarioResult（payload + 上下文）供进一步断言
```

与 Web 入口的对齐与差异：执行前种子化 traceId（默认固定值 `scenario-trace`，可覆盖）、执行后清理 MDC；不经过路由与异常→HTTP 映射——管道异常原样从 `run()` 抛出，失败场景用 `assertThatThrownBy(scenario::run)` 断言；expectXxx 断言只在管道成功完成后执行。

## 扩展点

1. **自定义 step**：`@Component("myStep") class MyStep implements DataTransformer` → YAML `ref: myStep`；
2. **自定义 step 类型**：实现 `StepFactory`（`type()` 返回新类型名）注册为 Bean → YAML `type: myType`；
3. **自定义认证**：实现 `AuthHandler` 注册为 Bean（同名 scheme 覆盖内置）；
4. **自定义编解码算法**：实现 `Codec` 注册为 Bean，`algorithm()` 即算法名；
5. **事件发布**：实现 `EventPublisher` 注册为 Bean（Kafka / 事务性发件箱 / webhook……）；事务时机（afterCommit）由框架的 eventPublisher 步骤统一保障，实现方只管真实外发（建议幂等 + 自行重试）。别名防护：事件表达式直接引用 `#payload` 会打 WARN，Map/List 事件发布前浅拷贝脱钩顶层引用（嵌套结构仍共享——构造全新事件对象是最稳妥写法）；
6. **覆盖 RestClient**：定义名为 `useCaseRestClient` 的 Bean（自定义超时/拦截器/代理）；
7. **替换任意内置 Bean**：自动配置的内置 Bean 均带 `@ConditionalOnMissingBean`——定义**同名 Bean**（如 `dataLoaderStepFactory`、`useCaseRestClient`）即整体替换内置实现；`StepExpressionEvaluator` / `UseCaseRegistry` / `UseCaseInvoker` / `ClientCredentialsTokenSupplier` 按**类型**判断（任意 Bean 名均可替换）。内置 AuthHandler / Codec 非独立 Bean，覆盖走第 3、4 条的 scheme / algorithm 机制；
8. **非 Web 应用**：路由绑定仅 Servlet Web 环境装配（`@ConditionalOnWebApplication`）；非 Web 项目引入 framework-core 时管道装配（Registry / UseCaseInvoker / StepFactory）仍然可用，经 `UseCaseInvoker.invokeStandalone` 在管道外编程调用用例（无入站请求，上下文经 `StepContext.standalone()` 创建）。`usecase.definitions` 为空时应用正常启动（空路由，不绑定任何端点）。

**自定义 step 数据纪律**（payload / vars / biz 均为引用传递，遵守以下约定避免跨步骤污染）：

- transformer / 自定义 step **产出新对象**，不原地修改 payload 与 `#body`（原地改 Map 会污染后续所有 `#body.xxx` 读取与 afterCommit 才发出的事件）；
- 不持有 `StepContext` 引用到步骤之外（事件、回调场景先取值再脱钩）；
- 写 biz 只写业务标识键；保留键 `traceId` 由 Web 入口种子化，starter 写入会在装配期被拒绝。

## 测试

```bash
mvn test     # 根目录执行：framework-core + framework-test + demo 三模块全量运行（135 个）
```

- `unit/framework/UseCaseTest`：管道顺序 / payload 流转 / 异常包装 + 键级数据现场（最内层优先）/ dev trace 开关（零容器）；
- `unit/framework/SpelStepTest`：SpEL 变量、`as` 旁路、saver null 保护、transformer `onNull: keep` 与默认清空 WARN、求值失败 400 收口（零容器）；
- `unit/framework/ExpressionInspectorTest`：SpEL AST 静态读取分析（变量/根属性/模板/索引器/字面量）；
- `unit/framework/StarterStepTest`：biz 关键数据区 + MDC 同步（含控制字符净化）、keys 必填校验；
- `unit/framework/CodecStepTest`：编解码互逆、单向摘要、decoder 可逆性装配期校验；
- `unit/framework/LoggingStepTest`：消息模板渲染、级别路由（logback ListAppender 断言）；
- `unit/framework/HttpRequesterStepTest`：MockRestServiceServer 验证 URI 模板、认证头、错误分支；
- `unit/framework/SubUseCaseStepTest`：子用例 input/串联/旁路/as/isolate 数据传递语义（零容器）；
- `unit/framework/ValidatorStepTest`：expression/schema 双模式、互斥校验、错误码与默认 400、求值失败映射 400；
- `unit/framework/UseCaseAssemblerTest`：shared 端点豁免、id 唯一性、子用例 ref 存在性、循环引用（含自引用）检测、starter 保留键（traceId）护栏与 shared-starter WARN、as 键碰撞 WARN（含串联合并与 isolate 豁免）、数据流报告输出；
- `unit/framework/UseCaseInvokerTest`：Java 调用子用例三种语义 + invokeShared 严格变体、父 payload 恢复、StepContextHolder 嵌套恢复、isolate/standalone 的 MDC 快照恢复、结果类型不匹配显式报错；
- `unit/framework/ClientCredentialsAuthHandlerTest`：OAuth2 token 缓存命中与过期原子刷新；
- `unit/framework/EventPublisherStepTest`：事件发布事务时机（无事务立即发 / afterCommit 提交后发 / 回滚不发）、发布器延迟解析、#payload 别名 WARN 与浅拷贝脱钩；
- `unit/framework/EventPublisherStepFactoryTest`：装配期发布器校验（缺失 / 类型不符 / 多候选无 @Primary fail-fast，@Primary 运行期解析命中）；
- `framework/autoconfigure/AutoConfigurationMapTest`：自定义 AuthHandler/Codec 同名覆盖内置（不依赖注入顺序）；
- `framework/test/UseCaseScenarioTest`（framework-test 模块）：harness 自身语义——请求构造（path/query/header/body）、端点/id 双定位、traceId 种子化与 MDC 清理、payload/vars/biz/事件断言与失败路径；
- `e2e/UseCaseRouterE2eTest`：全上下文 + MockMvc，验证 200 信封 / traceId 生成、透传与白名单 / decoder 端点 / 404 领域映射（含穿透子用例与 Java 调用边界）/ 502 下游失败 / POST schema 校验 400 / 坏 JSON 400 / Java 调用子用例端点；
- `e2e/UseCaseScenarioDemoTest`：UseCaseScenario 在真实装配产物上的示范——getUser/getUserByToken/createUserSnapshot 三条管道的 payload/vars/biz/事件断言（RecordingEventPublisher @Primary 探针接管事件发布）。
