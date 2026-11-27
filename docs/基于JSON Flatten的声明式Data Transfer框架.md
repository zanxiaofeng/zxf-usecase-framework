# 基于 JSON Flatten 的声明式 Data Transfer 框架 — 设计文档

> **状态**：v1.3（2026-11-27）——v0.3 完成设计定稿（依赖选型收口：json-flattener 0.18.2、json-schema-validator 3.0.6，原生支持 Jackson 3）；v1.0 三模块落地 + usecase 集成（附录 C）；v1.1 外部评审 47 条处置 + 异常体系 + validations（附录 D）；v1.2 代码 review 修复批次（附录 D.3）；**v1.3 外部评审二轮处置**——数值完整性、表达式安全模型、不可变执行快照、重复键拒绝、契约测试假通过路径修复与行为变更清单（附录 D.6）
>
> **基线**：Java 21 · Jackson 3（`tools.jackson.*`，全工程统一版本线）· Spring Boot 4（可选集成，非必需）
>
> **模块划分**：`data-transfer-core`（引擎与校验）/ `data-transfer-test`（契约测试工具）/ `data-transfer-demo`（示例与端到端演示），见 §8.2 与附录 A。

## 目录

- 一、核心思想
- 二、核心概念模型
- 三、映射 DSL 设计
- 四、引擎架构
- 五、Flatten / Unflatten 算法
- 六、高级特性
- 七、完整示例：电商订单 → CRM
- 八、Java SDK 设计
- 九、TransferSpec JSON Schema 校验
- 十、TransferAssert 契约测试工具
- 十一、工程化建议
- 附录 A：Maven 多模块工程结构
- 附录 B：依赖清单（按模块）
- 附录 C：落地注记（实现与设计的偏差、实现状态）
- 附录 D：评审处置记录（D.6 = 数值契约 / 表达式安全模型 / null 语义表 / 行为变更清单）

---

## 一、核心思想

整个框架的核心可以浓缩为一句话：

**Flatten → Map → Unflatten**

将任意嵌套的 JSON 拍平为 `{dotPath: value}` 的扁平键值空间，在这个"一等公民"的平面上完成所有映射与变换，再根据目标结构声明还原为嵌套 JSON。

```text
┌──────────┐  flatten   ┌──────────────┐  transform  ┌──────────────┐ unflatten ┌──────────┐
│  Source  │ ─────────► │  Flat KV Map │ ──────────► │  Flat KV Map │ ────────► │  Target  │
│   JSON   │            │  {path:val}  │             │  {path:val}  │           │   JSON   │
└──────────┘            └──────────────┘             └──────────────┘           └──────────┘
                          （拍平）       （声明式规则）      （还原）
```

这样做的好处是：所有转换逻辑都降维到一维键值空间，映射规则只需处理字符串路径的匹配与改写，天然支持通配符、正则、前缀替换等声明式操作，无需关心源/目标各自的嵌套深度。

代价是拍平把嵌套结构降为键字符串：含保留字符的源键不可映射、容器类型只能由路径段推断，这些固有限制与对策在 §5.3 明确列出，属于框架的设计边界。

---

## 二、核心概念模型

### 2.1 FlatKey（扁平键）

框架的一等公民。用点号分隔的路径字符串表示原 JSON 中的位置：

```text
user.name              → "Alice"
user.addresses[0].city → "Shanghai"
items[2].price         → 99.5
```

约定：

- `.` 分隔对象层级；
- `[n]` 表示数组索引（零基），索引段紧跟标识符、不加分隔符（如 `items[0].price`）；
- `[*]` 表示数组通配（匹配所有元素）；
- 分隔符可自定义（如 `/`），限定单字符（与 §9.1 Schema 中 `separator` 的 `maxLength: 1` 一致）。
- 标识符允许 `-`（如 `unit-price`），但**表达式里的 `.` 导航不识别带 `-` 的键**（JEXL 把 `m.unit-price` 解释为减法）：表达式中访问此类键须用方括号 `m['unit-price']`；
- 自定义分隔符第一版未实现（表达式侧按 `.` 还原嵌套视图）：引擎构造期 fail-fast，当前仅支持 `.`。

### 2.2 FlatMap（扁平映射）

`Map<FlatKey, LeafValue>`，其中 `LeafValue` 是 JSON 叶子节点（string / number / boolean / null）。

### 2.3 TransferRule（传输规则）

一条规则描述"从源 FlatKey 到目标 FlatKey 的数据搬运 + 变换"：

```yaml
- from: "user.name"
  to: "customer.fullName"
  transform: "upper"

- from: "user.addresses[*].city"
  to: "customer.locations[*].name"

- from: "items[*].price"
  to: "items[*].cost"
  transform: "multiply(1.13)"   # 含税价
```

### 2.4 TransferSpec（传输规格）

一组规则的集合，外加全局配置，构成一份完整的"数据搬运合同"：

```yaml
# order-transfer.yaml
version: "1.0"
name: "OrderSystem → CRM"

options:
  separator: "."
  arrayWildcard: "[*]"
  nullPolicy: "skip"        # skip（null 不产出目标键）| keep（原样写入 null）| default（null 进入变换链），见 §4.2 行为表
  missingPolicy: "warn"     # warn | error | ignore

rules:
  - from: "orderId"
    to: "crm.orderId"

  - from: "customer.name"
    to: "crm.customerName"
    transform: "trim"

  - from: "customer.email"
    to: "crm.contact.email"
    transform: "lower"

  - from: "items[*].name"
    to: "crm.lineItems[*].productName"

  - from: "items[*].price"
    to: "crm.lineItems[*].unitPrice"
    transform: "multiply(1.13)"

  - from: "items[*].quantity"
    to: "crm.lineItems[*].qty"

computed:
  - to: "crm.totalAmount"
    expr: "sum(crm.lineItems[*].unitPrice * crm.lineItems[*].qty)"

defaults:
  - to: "crm.currency"
    value: "CNY"
```

> 注：`computed` 表达式在映射完成后**对目标 FlatMap（+ `$` 暂存）求值**，因此只引用目标路径与暂存键（见 §6.2）；`to` 是单键目标，不允许 `[*]`，结果为容器时自动展开为 `to.` 前缀的扁平键。

---

## 三、映射 DSL 设计

### 3.1 路径表达式语法

```text
PathExpr    := Segment ( "." Segment )*
Segment     := Identifier ArrayAccess*          # 索引 / 通配紧跟标识符，不加分隔符：items[0]、items[*]
Identifier  := [a-zA-Z_][a-zA-Z0-9_-]*
ArrayAccess := "[" ( Integer | "*" ) "]"
```

> 约束：规则 `from` 与 `to` 的 `[*]` 数量必须一致，并按**出现顺序**对位（第 k 个对第 k 个；`[*]` 在两侧的段位置可以不同，如 `customer.addresses[*].city → crmOrder.buyer.cities[*]`）；`computed.to` / `defaults.to` 是单键目标，不允许 `[*]`（Schema `LiteralPathExpression`）；`intermediate.to` 是 `$` 前缀的纯点路径。

示例：

| 路径 | 含义 |
|---|---|
| `a.b.c` | 对象嵌套访问 |
| `items[0].name` | 数组第 0 个元素 |
| `items[*].price` | 所有元素的 `price` |
| `data[*].tags[*]` | 多层数组展开（按索引对位，见 §6.1） |

### 3.2 变换函数（Transform）

内置变换函数注册表，支持以 `|` 分隔的链式调用：

```yaml
transform: "trim | lower | default('N/A')"
```

| 类别 | 函数 | 说明 |
|---|---|---|
| 字符串 | `upper`, `lower`, `trim`, `replace(old, new)`, `substring(start, end)` | 基础文本处理 |
| 数值 | `multiply(n)`, `add(n)`, `round(precision)`, `abs` | 算术变换（生产实现建议 BigDecimal，见 §8.5 注） |
| 日期 | `dateFormat(fromFmt, toFmt)`, `epochToIso`, `epochToIso(zone)`, `toIsoDate(fmt)`, `toIsoDateTime(fmt)`, `now`, `now(zone)` | 时间格式转换（**已实现 2026-09-16**，见 §D.4；输出均为 String，时区默认 UTC + 可选 IANA 时区参数） |
| 类型 | `toString`, `toNumber`, `toBoolean` | 类型强转 |
| 逻辑 | `default(val)`, `coalesce(path1, path2, ...)`, `when(cond, then, else)` | 条件与兜底 |
| 聚合 | `sum`, `avg`, `count`, `min`, `max`, `concat(sep)` | 配合通配符使用 |
| 自定义 | `fn:myFunc` | 引用用户注册的扩展函数 |

> **实现状态**（以仓库 `FuncRegistry` 为准，附录 C.3）：已实现 `trim` / `lower` / `upper` / `replace` / `multiply` / `round` / `default` 与日期函数族；表中其余函数**未实现为变换函数**——聚合是 `computed.expr` 的表达式形态（§6.2），条件映射是 `when` 段（§6.3），「取首个非 null 值」用表达式的 `??` 运算符（`a ?? b ?? c`）。注意 `?:` 是**布尔语义**：会把 `0` / `false` 也当作缺省而替换掉（附录 D.6.1）。`fn:` 前缀语法未实现：自定义函数经引擎构造器 `extraFunctions` 注册后直接以函数名引用。
>
> **实参语法**：`fn(a, 'b c')`——实参按逗号切分，成对的单/双引号被剥离；不支持嵌套逗号与转义序列（含逗号的字面量请避开）；链用 `|` 分隔。
> 未加引号的 `$path` 实参是暂存引用（§6.8，运行期取值代入）；**带引号的 `'$path'` 是普通字面量**。

### 3.3 路径改写（Path Rewrite）

除了逐字段 `from → to`，还支持批量路径改写规则。`pattern` 为正则，`replace` 中的 `$1` 为捕获组反向引用：

```yaml
rewrites:
  # 前缀替换：所有 source. 开头的路径改为 target.
  - pattern: 'source\.(.*)'
    replace: 'target.$1'

  # 正则改写
  - pattern: 'data\.(.*)\.value'
    replace: 'result.$1'
```

这使得"整体重命名空间"只需一条规则，而非逐字段声明。

> **实现状态**：第一版未实现执行语义——`rewrites` 非空的 spec 在引擎构造期被拒绝（fail-fast，不静默忽略），见附录 C.3。

---

## 四、引擎架构

```text
┌─────────────────────────────────────────────────────────────┐
│                       TransferEngine                        │
│                                                             │
│  ┌───────────┐    ┌────────────────┐    ┌────────────────┐  │
│  │ SpecLoader│───►│ RuleParser     │───►│ CompiledPlan   │  │
│  │(YAML/JSON)│    │ & Validator    │    │ (immutable)     │  │
│  └───────────┘    └────────────────┘    └───────┬────────┘  │
│                                                 │           │
│  ┌───────────┐    ┌────────────┐                │           │
│  │  Source   │───►│ Flattener  │────────────────┤           │
│  │   JSON    │    │            │                ▼           │
│  └───────────┘    └────────────┘    ┌────────────────┐      │
│                                     │ TransformPipe  │      │
│                                     │ ┌────────────┐ │      │
│                                     │ │ Matcher    │ │      │
│                                     │ ├────────────┤ │      │
│                                     │ │ Resolver   │ │      │
│                                     │ ├────────────┤ │      │
│                                     │ │ FuncExec   │ │      │
│                                     │ └────────────┘ │      │
│                                     └───────┬────────┘      │
│                                             │               │
│                                             ▼               │
│                                     ┌────────────────┐      │
│                                     │  Unflattener   │      │
│                                     └───────┬────────┘      │
│                                             │               │
│                                             ▼               │
│                                     ┌────────────────┐      │
│                                     │  Target JSON   │      │
│                                     └────────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### 4.1 模块职责

| 模块 | 职责 |
|---|---|
| SpecLoader | 从 YAML/JSON/代码加载 TransferSpec：YAML 只解析一次，JSON Schema 校验与模型绑定共用同一棵解析树，重复键在解析期即被拒绝（热加载与版本管理未实现） |
| RuleParser | 把可变的 TransferSpec 深拷贝为不可变执行快照（CompiledPlan），并做装配期校验：未支持特性、通配数量、暂存区引用、目标键结构冲突、表达式语法、断言语法 |
| Flattener | 递归遍历源 JSON，生成 FlatMap，处理数组展开与通配符 |
| TransformPipe | 核心管道，按 CompiledPlan 逐条执行：路径匹配 → 值解析 → 函数执行 → 写入目标 FlatMap |
| Unflattener | 将目标 FlatMap 还原为嵌套 JSON，自动推断数组索引与对象层级 |
| FuncRegistry | 内置 + 用户自定义变换函数的注册中心 |

### 4.2 执行流程（伪代码）

```python
class TransferEngine:
    def __init__(self, spec):
        # 装配期：深拷贝为不可变执行快照并完成全部校验（失败即 TransferAssemblyException）——
        # 未支持特性 / 通配数量 / 暂存区引用 / 目标键结构冲突 / 表达式语法 / 断言语法。
        # 之后的 spec 修改不影响已构造的引擎
        self.plan = CompiledPlan.compile(spec)
        self.funcs = FuncRegistry(spec.functions)

    def transfer(self, source: dict) -> dict:
        # Phase 1: Flatten（小数一律 BigDecimal，整数维持 Integer / Long / BigInteger）
        flat_src = Flattener.flatten(source, self.plan.separator)

        # Phase 1.5: Intermediate —— 暂存区按声明序求值（§6.8；上下文 = 源 + 已产出暂存）
        flat_stage = {}
        for field in self.plan.intermediate:
            ctx = {**flat_src, **flat_stage}
            value = eval_expr(field.expr, ctx) if field.expr else apply_chain(field.transform, ctx[field.from], ctx)
            flat_stage.update(stage_put(field.to, value))    # $ 命名空间；容器值展开为扁平键集

        # Phase 2: Transform —— 按声明顺序逐规则执行（通配符先展开为具体的 (srcKey, tgtKey) 对）
        flat_tgt = {}
        for rule in self.plan.rules:
            pairs = self._expand_wildcards(rule, {**flat_src, **flat_stage})
            if not pairs:
                self._apply_missing_policy(rule)             # 规则未命中任何源键：warn | error | ignore
            for src_key, tgt_key in pairs:
                value = {**flat_src, **flat_stage}[src_key]
                if value is None and self.plan.null_policy == "skip":
                    continue                                 # skip：变换链不执行，目标键不产出
                value = self._apply_transform_chain(rule, value)   # keep / default：null 进入变换链（default() 可兜底）
                if value is None and self.plan.null_policy == "default":
                    continue                                 # 链后仍为 null：不写入，交给 defaults 兜底
                if self.plan.strict_mode and tgt_key in flat_tgt:
                    raise TargetConflict(tgt_key)            # strictMode：同一目标键不得被重复写入
                flat_tgt[tgt_key] = value                    # 非 strictMode：「后规则覆盖前规则」

        # computed：在「目标 + 暂存」合并视图上求值（表达式引用目标路径与 $ 暂存，见 §6.2 / §6.8）
        for computed in self.plan.computed:
            value = eval_expr(computed.expr, {**flat_tgt, **flat_stage})
            flat_tgt.update(stage_put(computed.to, value))  # 容器结果展开为扁平键；标量与空容器落单键

        # 注入默认值：仅当目标键**不存在**时（keep 策略写入的 null 不被覆盖）
        for default in self.plan.defaults:
            if default.to not in flat_tgt:
                flat_tgt[default.to] = default.value

        # 数据值校验（§6.7）：对完整目标数据执行，失败即抛 ValidationException（不生成脏数据）
        self._validate(flat_tgt)                             # fail_fast | collect

        # Phase 3: Unflatten（输出数值形态与历史行为一致，见附录 D.6.1）
        return Unflattener.unflatten(flat_tgt, self.plan.separator)
```

**null / missing / defaults 行为表**（v1.3 语义；`nullPolicy` 作用于规则映射与 `intermediate` 的 `from` 形态）：

| 场景 | `skip`（默认） | `keep` | `default` |
|---|---|---|---|
| 源值为 null，无变换 | 不产出目标键 | 写入 `null` | 不产出目标键 |
| 源值为 null + `transform: default('N/A')` | 不产出目标键（**`default()` 不执行**） | `N/A` | `N/A` |
| 源值为 null + `defaults` 段同目标 | 取 `defaults` 值 | 保留 `null`（`defaults` 不覆盖） | 取 `defaults` 值 |
| 源值为 null + `default('N/A')` + `defaults` | 取 `defaults` 值 | `N/A`（变换链优先） | `N/A` |
| 源键缺失（规则未命中） | 不执行变换链；`missingPolicy` 处置；目标键留给 `defaults` | 同左 | 同左 |
| 源值为 `0` / `false` / `""` | 原样映射（`default()` 只兜 null） | 同左 | 同左 |

---

## 五、Flatten / Unflatten 算法

> 本章为**算法原理**说明（拍平的递归结构、Unflatten 的容器类型推断与索引槽位垫满等）；生产实现复用 `json-flattener` 0.18.2（§8.4），边界行为（保留字符转义、空容器）以库语义为准，固有限制汇总见 §5.3。

### 5.1 Flatten（递归拍平）

```python
def flatten(node, prefix="", separator=".", result=None):
    if result is None:
        result = {}
    if isinstance(node, dict):
        for k, v in node.items():
            new_key = f"{prefix}{separator}{k}" if prefix else k
            flatten(v, new_key, separator, result)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            new_key = f"{prefix}[{i}]"           # 索引段紧跟前缀，不加分隔符
            flatten(v, new_key, separator, result)
    else:
        result[prefix] = node                    # leaf value
    return result
```

输入：

```json
{"user": {"name": "Alice", "tags": ["admin", "dev"]}}
```

输出：

```json
{"user.name": "Alice", "user.tags[0]": "admin", "user.tags[1]": "dev"}
```

### 5.2 Unflatten（结构还原）

容器类型由"下一段的类型"推断：下一段是整数索引则建数组，否则建对象。**数组槽位须先垫满再赋值**（对空列表直接索引赋值会越界）：

```python
def unflatten(flat_map, separator="."):
    root = {}
    for path, value in flat_map.items():
        segments = parse_path(path, separator)   # "user.tags[0]" → ["user", "tags", 0]
        _set_nested(root, segments, value)
    return root


def parse_path(path, separator="."):
    # "user.tags[0]" → ["user", "tags", 0]（索引段紧跟标识符，无分隔符）
    segments = []
    for part in path.split(separator):
        m = re.match(r"([^\[\]]*)((?:\[\d+\])*)", part)
        if m.group(1):
            segments.append(m.group(1))
        for idx in re.findall(r"\[(\d+)\]", m.group(2)):
            segments.append(int(idx))
    return segments


def _set_nested(container, segments, value):
    for i, seg in enumerate(segments):
        is_last = (i == len(segments) - 1)
        if isinstance(seg, int):
            while len(container) <= seg:         # 垫满索引槽位，避免越界赋值
                container.append(None)
            if is_last:
                container[seg] = value
                return
            if container[seg] is None:
                container[seg] = [] if isinstance(segments[i + 1], int) else {}
            container = container[seg]
        else:
            if is_last:
                container[seg] = value
                return
            container = container.setdefault(
                seg, [] if isinstance(segments[i + 1], int) else {})
```

### 5.3 已知限制（设计边界）

拍平方案的信息丢失点是固有属性，明确列出以便使用方规避：

1. **空容器**：空对象 `{}` / 空数组 `[]` 由 json-flattener（v0.10+）作为**叶子值保留**，往返可还原；但它们不会产生子键，规则 `from` 若指向其内部路径（如 `a.empty.x`）将不命中。`computed` / `intermediate` 结果为空容器时同样落单键（不展开）。
2. **保留字符键**：源键名包含分隔符 `.` 或 `[` 时，json-flattener 默认转义为 `matrix["agent.smith"]` 记法——此类键不属于 §3.1 路径语法、不可被规则匹配（天然无歧义，即"不可映射"）；`strictMode` 下引擎在首次 transfer 前对其 fail-fast。详见 §8.4 注。
3. **容器类型混用**：同一父路径既是数组又是对象（`a[0]` 与 `a.b`），或同一路径既是值又是容器（`a` 与 `a.b`）——无论是否开启 `strictMode`，引擎**构造期**即按目标路径模式静态判定并拒绝（`TransferAssemblyException`）；只有通配展开后才重叠的目标留到运行期写入前判定（见 D.6.4）。
4. **数值精度**：小数全程 `BigDecimal`——恒等映射不丢精度（`12345678901234567890.123` 原样输出），输出形态与十进制字面量一致（`113.00` → `113.0`；超出 double 精度的值保留全部数字）；transform、聚合与表达式三条路径遵循同一十进制语义（`0.1 + 0.2 == 0.3` 成立），整数之间保持 Java 语义（`10 / 4 == 2`）。完整数值契约见附录 D.6.1。


---

## 六、高级特性

### 6.1 通配符展开与数组对齐

当源和目标都使用 `[*]` 时，引擎按索引对齐：

```yaml
- from: "items[*].price"
  to: "lineItems[*].amount"
```

源 FlatMap：

```text
items[0].price → 100
items[1].price → 200
```

展开后生成两条映射：

```text
items[0].price → lineItems[0].amount   (value: 100)
items[1].price → lineItems[1].amount   (value: 200)
```

多级通配符（如 `data[*].tags[*]`）按出现顺序逐段对位：`from` 中第 k 个 `[*]` ↔ 展开键中第 k 个 `[n]` ↔ `to` 中第 k 个 `[*]`。**`[*]` 在 `from` 与 `to` 中的段位置可以不同**（如 `customer.addresses[*].city → crmOrder.buyer.cities[*]`：位置 2 对位置 3）；两侧 `[*]` 数量不一致的规则在引擎构造期被拒绝。

### 6.2 跨源聚合（computed）

```yaml
computed:
  - to: "summary.totalAmount"
    expr: "sum(crmOrder.lines[*].unitPrice * crmOrder.lines[*].quantity)"
```

引擎在 TransformPipe 完成后，**对目标 FlatMap** 执行聚合表达式求值——因此表达式只引用映射已生成的目标路径，而非源路径。

> 实现要点：表达式中的 `[*]` 需由引擎预处理展开为索引集合（如 `lines[0]`、`lines[1]`），并将展开后的路径以嵌套视图绑定进表达式上下文，再交给表达式引擎（JEXL 等）求值，见 §8.6 注。
>
> **两种形态**（以仓库 `JexlExpressionEvaluator` 为准）：
>
> - **聚合形态** `agg(PATH)` / `agg(PATH * PATH)`（agg ∈ `sum` / `avg` / `count` / `min` / `max`，PATH 为含 `[*]` 的通配路径）：由引擎展开后按 BigDecimal 计算；双操作数按**通配索引元组配对**（两侧索引集合不一致即报错，宁可失败不错数据）；`avg` 保留 8 位小数（HALF_UP）。聚合是整体表达式，与算术混合须标量中转。
> - **常规表达式**（其余一切）：以「目标 + `$` 暂存」的嵌套视图为上下文交 JEXL 求值。
>
> **契约**（v1.3）：`to` 是单键目标，不允许 `[*]`（Schema `LiteralPathExpression`）；结果为 Map / List / 数组时**展开为 `to.` 前缀的扁平键**（与 `intermediate` 一致，后续 computed 与 validations 可寻址其内部字段），标量与空容器落单键；表达式**语法在装配期校验**（错误在启动时暴露，而非首个请求；`new(...)`、lambda、循环等脚本特性在此即被拒绝），聚合操作数必须是含 `[*]` 的合法路径；运行期求值失败抛 `ExpressionException`（携带表达式与所属声明）；算术按十进制进行，数值契约见附录 D.6.1；表达式可访问的类型与方法受沙箱约束，见附录 D.6.2。

### 6.3 条件映射

```yaml
rules:
  - from: "status"
    to: "orderState"
    when:
      - condition: "status == 'PAID'"
        transform: "replace('PAID', 'completed')"
      - condition: "status == 'PENDING'"
        transform: "replace('PENDING', 'processing')"
      - otherwise: "default('unknown')"
```

> 语义：`condition` 为 JEXL 表达式（上下文 = 源 + `$` 暂存的嵌套视图，**语法在装配期校验**）；命中分支执行其 `transform`，无分支命中走 `otherwise`——两者都是普通变换链，`default()` 仅对 null 兜底、非 null 值原样传递；两者都没有时保留原值。源值为 null 时同样先经 `nullPolicy`（`skip` 下整条规则不产出，见 §4.2 行为表）。

### 6.4 多源合并

`sources` 为 TransferSpec 顶层声明（已纳入 §8.3 配置模型与 §9.1 Schema）：各源从输入报文中按 `path` 取子树、分别拍平，再以 `alias` 为根前缀合并进同一 FlatMap：

```yaml
sources:
  - alias: "order"
    path: "order"
  - alias: "user"
    path: "userInfo"

rules:
  - from: "order.id"
    to: "result.orderId"
  - from: "user.name"
    to: "result.customerName"
```

未声明 `sources` 时，以整个输入报文为单一源。

> **实现状态**：第一版未实现——`sources` 非空的 spec 在引擎构造期被拒绝（fail-fast），见附录 C.3。

### 6.5 批量模式（Array Input）

当输入是 JSON 数组时，引擎自动对每个元素执行同一套规则：

```python
def transfer_batch(self, source_list: list) -> list:
    return [self.transfer(item) for item in source_list]
```

> **实现状态**：未实现（调用方自行对数组逐元素调用 `transfer`），见附录 C.3。

### 6.6 Diff & Dry-Run

```python
engine.dry_run(source)
# 返回: { "planned_mappings": [...], "warnings": [...], "estimated_output": {...} }
```

> **实现状态**：未实现，见附录 C.3。

### 6.7 数据值校验（validations，v1.1 已实现；v1.3 收紧语义）

对**目标 FlatMap**（转换后的值）的校验段，执行时点在 rules + computed + defaults 全部完成后、
Unflatten 之前——校验失败即抛 `ValidationException`（携带结构化失败明细），不生成脏数据。
与 §9 的 Schema 校验互补：Schema 管**配置文件格式**，validations 管**转换过程中的数据值**
（`price: -100` 不再被忠实映射成 `unitPrice: -113.0`）。

```yaml
validations:
  # 单字段断言（path 可含 [*] 逐元素断言；断言内自带 message）
  - path: "crmOrder.lines[*].unitPrice"
    rules:
      - assert: "gt(0)"
        message: "单价必须大于0"

  - path: "crmOrder.buyer.email"
    rules:
      - assert: "regex('^[\\w.-]+@[\\w.-]+\\.\\w+$')"
        message: "邮箱格式非法"

  # 组合条件（JEXL；path 为元素路径时裸标识符解析为该元素字段；外层 message 必填）
  - path: "crmOrder.lines[*]"
    condition: "unitPrice > 0 && quantity > 0"
    message: "单价和数量必须同时大于0"
```

- **断言谓词**（内置，构造期校验语法与参数格式、未知谓词 fail-fast）：`gt(n)` / `gte(n)` /
  `lt(n)` / `lte(n)` / `eq(v)` / `neq(v)`（数值比较走 BigDecimal）+ `regex('pattern')` +
  `notNull` / `notBlank` + `dateBefore` / `dateAfter` / `dateNotBefore` / `dateNotAfter`（附录 D.4）；
  字面键不存在时以 null 值执行断言（`notNull` 场景需要）。
  `fn:` 自定义断言不做——复杂判断走 `condition`（JEXL，表达力等价且受沙箱约束）。
- **通配路径的枚举语义**（v1.3）：含 `[*]` 的断言路径**先枚举目标中实际存在的元素，再逐元素取字段**——
  元素存在但字段缺失时同样以 `null` 执行断言。例如 `{"lines":[{"unitPrice":10},{"quantity":1}]}`
  对 `lines[*].unitPrice` 做 `notNull` 会报出 `lines[1].unitPrice` 失败，而不是漏检。
  数组为空或不存在时没有可校验的元素，不产生失败；「数组必填且非空」须用字面路径的断言另行表达。
  断言形态的 `path` 应指向叶子字段；校验元素内多个字段的关系用 `condition` 形态。
- **类型不符即判失败**（v1.3）：数值断言遇到非数值（`"abc"`、`""`、`true`）记为一条失败
  （消息附 `(value is not numeric)`）并继续，`collect` 模式不再被 `NumberFormatException` 中断；
  日期断言对 `null` 与不可解析值同样判失败。
- **执行模式**（`options.validationMode`）：`fail_fast`（默认，首个失败即抛，入参校验场景）/
  `collect`（收集全部失败后统一抛，数据迁移场景）；`ValidationException#getFailures()` 取
  `List<ValidationFailure{path, rule, message, actualValue}>`。
- **装配期校验**（v1.3）：`rules` 与 `condition` 二选一、谓词白名单、数值参数可解析、
  日期参数须为无 offset 的 ISO 形态、`regex` 的 pattern 合法、`condition` 表达式语法、
  `condition` 形态的 path 至多一级 `[*]`（多级元素语义歧义；断言形态无此限制）。

### 6.8 中间结果（intermediate，暂存区；2026-09-24 落地）

复杂字段转换难以一步完成时，先计算**中间结果**、再基于原始输入与中间结果完成总体转换。
`intermediate` 为 TransferSpec 顶层声明段，执行于 Flatten 之后、规则映射之前（Phase 1.5），
结果写入 **`$` 前缀的独立命名空间**——暂存键不进入最终输出。spec 编写时建议将该段置于
`rules` 之前，保持段落顺序与执行顺序一致（intermediate → rules → computed → defaults →
validations；YAML 键序不影响解析，纯为可读性惯例）：

```yaml
intermediate:
  # 形态一：from（源/已产出暂存的字面路径）+ 可选 transform 链（复用现有函数，含 extraFunctions）
  - to: "$discountRate"
    from: "customer.tier"
    transform: "tierToRate"
  # 形态二：expr（JEXL，上下文 = 源 + 已产出暂存；可跨字段组合，整体结果可为容器）
  - to: "$fullAddress"
    expr: "customer.city + ' / ' + customer.street"

rules:
  - from: "items[*].price"
    to: "crmOrder.lines[*].unitPrice"
    transform: "multiply($discountRate) | round(2)"   # transform 未加引号的 $path 实参：从合并视图取值代入
  - from: "$fullAddress"                              # rules.from 可引暂存路径（支持 [*] 通配导航容器暂存）
    to: "crmOrder.buyer.address"

computed:
  - to: "crmOrder.totalAmount"
    expr: "sum(crmOrder.lines[*].unitPrice * crmOrder.lines[*].quantity)"   # computed 亦可引 $ 键
```

**执行语义：**

- **顺序求值**：按声明序逐条计算，上下文 = 源 FlatMap + 已产出暂存——只能**前向引用**更早声明的
  暂存键（声明序即 DAG）；引用未声明/后向键构造期 fail-fast。
- **不进输出**：暂存写入独立 Map，Unflatten 只拍目标——中间结果天然不污染响应。
- **策略复用**：from 形态的键缺失按 `missingPolicy` 处置（处置后不产出该键）；值为 null 且
  `nullPolicy=skip` 时同样不产出（后续引用以运行期缺失暴露）。
- **容器暂存展开**：expr 结果为 Map/List 时展开为 `to.` 前缀的扁平键集（如 `$x[0].sku`），
  规则 from 即可对暂存数组做 `[*]` 通配导航；空容器保留为叶子值。
- **`$` 冲突 fail-fast**：源数据存在 `$` 开头的键时（与暂存命名空间在合并视图中无法区分）拒绝转换。

**构造期校验（fail-fast）**：`to` 必须 `$` 前缀纯点路径（不含 `[*]`/`[n]`，根声明唯一——`$a` 与
`$a.b` 同根冲突）；from 与 expr 二选一（Schema oneOf 同约束）；from 不支持 `[*]`（数组派生用
expr 整体取值）；rules.from 的 `$` 引用、transform 的 `$path` 实参、computed.expr 的 `$` 引用
均须有对应 intermediate 声明。

**暂存引用的路径形态**（v1.3）：`rules.from` 引用暂存时可带索引与通配——`$x[*].f`、`$lst[*]`、`$lst[1]`、`$x[0].f`
均按其根（`$x` / `$lst`）做声明检查。容器暂存展开后的键形态：对象 → `$x.f`，数组 → `$x[0].f`。

**已知限制**：聚合形态 `sum(...)` 为整体表达式（§6.2 求值器设计），与算术混合（`sum(...) * $x`）
需经标量中转；表达式字符串字面量中的 `$ident` 会被构造期引用检查误报为引用（避免书写）；
transform 实参的暂存引用只支持 `$path` 纯点路径（如 `multiply($rate)`，须未加引号），不支持带索引。
构造期的引用检查会跳过引号字符串字面量——`'$price=' + $rate` 只引用了 `$rate`；正则字面量（`=~ /$x/`）里的 `$` 仍会被计入（已知限制）。

---

## 七、完整示例：电商订单 → CRM

### 7.1 源数据

```json
{
  "orderId": "ORD-20260906-001",
  "customer": {
    "name": " Zhang San ",
    "email": "ZHANG@EXAMPLE.COM",
    "addresses": [
      {"city": "Shanghai", "zip": "200000"},
      {"city": "Beijing", "zip": "100000"}
    ]
  },
  "items": [
    {"sku": "A001", "price": 100, "qty": 2},
    {"sku": "B002", "price": 50, "qty": 3}
  ],
  "status": "PAID"
}
```

### 7.2 TransferSpec 配置

```yaml
version: "1.0"
name: "ECommerce → CRM"

options:
  nullPolicy: "skip"
  missingPolicy: "warn"

rules:
  - from: "orderId"
    to: "crmOrder.id"

  - from: "customer.name"
    to: "crmOrder.buyer.name"
    transform: "trim"

  - from: "customer.email"
    to: "crmOrder.buyer.email"
    transform: "lower"

  - from: "customer.addresses[*].city"
    to: "crmOrder.buyer.cities[*]"

  - from: "items[*].sku"
    to: "crmOrder.lines[*].productCode"

  - from: "items[*].price"
    to: "crmOrder.lines[*].unitPrice"
    transform: "multiply(1.13) | round(2)"

  - from: "items[*].qty"
    to: "crmOrder.lines[*].quantity"

  - from: "status"
    to: "crmOrder.state"
    transform: "lower"

computed:
  - to: "crmOrder.totalAmount"
    expr: "sum(crmOrder.lines[*].unitPrice * crmOrder.lines[*].quantity)"

defaults:
  - to: "crmOrder.currency"
    value: "CNY"
  - to: "crmOrder.source"
    value: "eCommerce"
```

### 7.3 期望输出

```json
{
  "crmOrder": {
    "id": "ORD-20260906-001",
    "buyer": {
      "name": "Zhang San",
      "email": "zhang@example.com",
      "cities": ["Shanghai", "Beijing"]
    },
    "lines": [
      {"productCode": "A001", "unitPrice": 113.0, "quantity": 2},
      {"productCode": "B002", "unitPrice": 56.5, "quantity": 3}
    ],
    "state": "paid",
    "totalAmount": 395.5,
    "currency": "CNY",
    "source": "eCommerce"
  }
}
```

> 注：JSON 数值没有尾随零概念，`113.00` 与 `113.0` 是同一个数——`round(2)` 保证的是两位小数精度语义，不是序列化形态。`totalAmount = 113×2 + 56.5×3 = 395.5`。

---

## 八、Java SDK 设计

### 8.1 设计原则

- **声明式优先**：所有转换逻辑通过 YAML/JSON 配置描述，零代码修改即可完成新对接场景适配；
- **生态复用与依赖收敛**：JSON 解析采用 Jackson 3（`tools.jackson.*`）作为全工程唯一 JSON 版本线；Flatten/Unflatten 复用 `json-flattener` 0.18.2（v0.18.0 起**原生支持 Jackson 3**：`Jackson3JsonCore` / `Jackson3JsonValue`，Java 17+，Jackson 2 仍经 json-base 可用），通配符匹配为引擎自研（§8.4）；
- **扩展友好**：变换函数、表达式引擎、路径解析器均提供扩展点，支持业务自定义能力；
- **生产就绪**：内置契约测试工具与配置 / 装配期校验能力，满足企业级落地需求（可观测性埋点尚未实现，见 §11.2）。

**Jackson 3 基线要点**（相对 Jackson 2 的迁移注意项）：

| 项 | Jackson 3 |
|---|---|
| 坐标 | `tools.jackson.core:jackson-databind`、`tools.jackson.dataformat:jackson-dataformat-yaml`（注解仍为 `com.fasterxml.jackson.core:jackson-annotations`） |
| 异常体系 | 全部非受检（`JacksonException extends RuntimeException`），`readValue`/`writeValueAsString` 不再抛受检异常 |
| API 改名 | `ObjectNode#properties()` 取代 `fields()`；`TextNode#asString()` 取代 `asText()` |
| 未知属性 | **默认忽略**，需显式开启 `FAIL_ON_UNKNOWN_PROPERTIES`——本项目已有实践（Spec 配置类加载时可据此选择是否 fail-fast，与 §9 Schema `additionalProperties: false` 的"拒绝未知键"校验形成互补） |

### 8.2 模块划分

```text
data-transfer-sdk（聚合根，父 POM）
├── data-transfer-core     引擎本体：Spec 模型 / Flatten / Transform / 表达式 / 校验 / Spring Boot 4 自动配置（optional）
├── data-transfer-test     契约测试工具：TransferAssert / Diff / JUnit 5 扩展
└── data-transfer-demo     可运行示例 + 契约测试示范

依赖方向：demo → { core, test }；test → core；core 不依赖 test/demo
```

| 模块 | 定位 | 关键依赖 |
|---|---|---|
| `data-transfer-core` | 引擎与校验，无 Spring 亦可独立使用（Spring 自动配置相关依赖 `optional`） | Jackson 3、json-flattener 0.18.2、JEXL、jakarta.validation、json-schema 校验器（选型见 §11.3） |
| `data-transfer-test` | 契约测试工具，编译期依赖 JUnit 5 API（`optional`，运行时由宿主工程提供） | core、junit-jupiter-api |
| `data-transfer-demo` | 端到端示例：配置式/代码式用法 + Spring Boot 集成 + 契约测试示范 | core、test（test scope）、spring-boot-starter |

分层架构（各层职责清晰、解耦）：

```text
API 层        配置式（YAML/JSON）/ 代码式（Builder）两种使用入口        → core / demo
核心处理层    规则编译、转换管道、通配符展开                            → core
能力层        Flatten/Unflatten（json-flattener 封装）、变换函数、表达式求值 → core
扩展层        扩展点（TransformFunction / ExpressionEvaluator）、注册中心 → core
支撑层        可观测性、Schema 校验、契约测试工具                        → core / test
```

### 8.3 配置模型（TransferSpec）【core】

所有转换规则通过 `TransferSpec` 类描述，支持从 YAML/JSON 文件加载，内置校验能力（第一道校验为 JSON Schema，见 §9；
Bean Validation 注解是模型契约的文档化表达，主要约束由 Schema 与引擎装配期校验承担）：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferSpec {

    @NotBlank
    private String version;

    @NotBlank
    private String name;

    @Valid
    private TransferOptions options = new TransferOptions();

    /** 多源合并声明（§6.4；第一版未实现执行语义，引擎构造期 fail-fast） */
    private List<SourceDeclaration> sources;

    @NotEmpty
    private List<MappingRule> rules;

    /** 批量路径改写（§3.3；第一版未实现执行语义，引擎构造期 fail-fast） */
    private List<PathRewrite> rewrites;

    /** 中间结果（暂存区，§6.8）：rules 前在源上下文顺序求值，$ 命名空间不进输出 */
    private List<IntermediateField> intermediate = new ArrayList<>();

    /** 计算字段：在目标 FlatMap 上求值，表达式引用目标路径（§6.2） */
    private List<ComputedField> computed = new ArrayList<>();

    /** 默认值：仅当目标键不存在时注入 */
    private List<DefaultValue> defaults = new ArrayList<>();

    /** 数据值校验（§6.7）：对目标 FlatMap 执行，时点在 defaults 后、Unflatten 前 */
    private List<ValidationRule> validations = new ArrayList<>();

    /** 可观测性配置（§11.2；第一版仅模型承载，不产生埋点） */
    private ObservabilityConfig observability;

    /** 容器空安全视图（builder 路径可能未初始化） */
    public List<IntermediateField> intermediateOrEmpty() { ... }
    public List<ComputedField> computedOrEmpty() { ... }
    public List<DefaultValue> defaultsOrEmpty() { ... }
    public List<ValidationRule> validationsOrEmpty() { ... }
    public TransferOptions optionsOrNew() { ... }
}
```

```java
@Data
public class TransferOptions {
    /** FlatKey 分隔符，限定单字符（Schema maxLength:1）；第一版仅支持 "." */
    private String separator = ".";
    /** 数组通配符（第一版仅支持 [*]） */
    private String arrayWildcard = "[*]";
    /** 源值为 null 的处置：SKIP / KEEP / DEFAULT，见 §4.2 行为表 */
    private NullPolicy nullPolicy = NullPolicy.SKIP;
    private MissingPolicy missingPolicy = MissingPolicy.WARN;
    /** true 时装配期 / 运行期对目标键重复写入、保留字符转义记法的源键 fail-fast，见 D.6.4 */
    private boolean strictMode = false;
    /** validations 执行模式：FAIL_FAST 首个失败即抛 / COLLECT 收集全部失败 */
    private ValidationMode validationMode = ValidationMode.FAIL_FAST;
}
```

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappingRule {
    @NotBlank
    private String from;
    @NotBlank
    private String to;
    private String transform;            // "trim | multiply(1.13) | round(2)"
    /** 条件映射（§6.3）：与 transform 互斥——按条件分支选择变换链 */
    private List<ConditionMapping> when;
}
```

> 注：类级 `@Valid` 无校验语义（jakarta `@Valid` 仅用于属性级联触发），故不放在类上；如需 Spring 场景的方法级校验用 `@Validated`。
> `computed` / `defaults` / `intermediate` / `validations` 初始化为空列表，避免 YAML 绑定路径上的 NPE。
>
> **Lombok `@Builder` 注意**：`@Builder` 经由 `@AllArgsConstructor` 建实例，**不会保留字段初始化值**——builder 路径上
> `options` / `intermediate` / `computed` / `defaults` / `validations` 均为 `null`，由上面的 `xxxOrEmpty()` 空安全视图兜底。
> builder 没有 `.rule(...)` / `.defaultValue(...)` 这类便捷方法（未启用 `@Singular`）：集合段直接传 `List`
> （见 §8.7 代码式示例）。YAML / JSON 绑定走 `@NoArgsConstructor`，字段初始化值照常生效。

### 8.4 Flatten/Unflatten 模块【core，复用 json-flattener】

复用 `json-flattener` 0.18.2：v0.18.0 起原生支持 Jackson 3（`Jackson3JsonValue` 可直接包装 Jackson 3 的 `JsonNode`，
**零字符串往返**；`JsonUnflattener.unflattenAsMap(Map)` 提供 Map 直达还原）。通配符匹配为 DSL 特有能力，由引擎自研：

```java
public final class FlatMapProcessor {

    /** 反序列化开启 BigDecimal（USE_BIG_DECIMAL_FOR_FLOATS）；解析与序列化统一经 mapper() 走同一实例 */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** 解析/序列化统一入口：引擎与断言工具的 readTree 一并复用，保证 BigDecimal 语义贯穿 */
    public JsonMapper mapper() { ... }

    /** 将嵌套 JSON 树拍平为 {FlatKey: leaf}（对应 §5.1 算法）：出口把被库降为 Double 的小数统一还原为 BigDecimal */
    public Map<String, Object> flatten(JsonNode root, String separator) { ... }

    /** 通配符展开（DSL 特有，自研）：将带 [*] 的路径展开为所有匹配的键值对；匹配正则按路径缓存 */
    public List<Map.Entry<String, Object>> expandWildcard(String wildcardPath, Map<String, Object> flatMap) { ... }

    /** 将扁平 Map 还原为嵌套 JSON 树（对应 §5.2 算法）：自建实现，不经 JSON 文本往返；
     *  可经 double 无损往返的小数输出 double 形态（与历史行为一致），其余保留全精度 */
    public JsonNode unflatten(Map<String, Object> flatMap, String separator) { ... }

    /** 还原为嵌套 Java Map（表达式求值的嵌套视图绑定用）：数值保持精确类型，容器为 LinkedHashMap / ArrayList */
    public Map<String, Object> unflattenToMap(Map<String, Object> flatMap, String separator) { ... }
}
```

> **保留字符语义**：源键含 `.` / `[` 时，json-flattener 默认转义为 `matrix["agent.smith"]` 记法。此类键不属于 §3.1 路径语法，
> 无法被规则或通配符匹配（天然无歧义），实际效果即"不可映射"；`strictMode` 开启时引擎在首次 transfer 前对含转义记法的键直接
> fail-fast（对应 §5.3-2）；`unflattenToMap` / `unflatten` 遇到这类键时回退到 json-flattener 的还原语义。库的
> `ignoreReservedCharacters()`（忽略转义直接拼接）会产生歧义路径，不作为引擎暴露的配置。
>
> **数值完整性**（v1.3，附录 D.6.1）：`flatten` 出口小数一律 `BigDecimal`（整数维持 Integer / Long / BigInteger 窄化）；
> `unflattenToMap` 保持精确类型；`unflatten` 的输出形态与十进制字面量一致。引擎与断言工具的 `readTree`、
> flatten / unflatten 统一走 `mapper()` 这一实例——恒等映射不丢精度。
>
> **API 注记**：`new JsonFlattener(new Jackson3JsonValue(root)).withSeparator(char).flattenAsMap()`、
> `new JsonUnflattener(Map).withSeparator(char).unflattenAsMap()` 等用法以 0.18.x javadoc 为准（均已在落地中实证）。
> `PathParser.parse("user.tags[0]", ".")` → `["user", "tags", 0]`，用于通配符多级对位与路径校验，实现与 §5.2 `parse_path` 一致。

### 8.5 变换函数模块【core】

定义标准扩展接口，内置常用函数（不可变基表 + 实例注册表，避免全局可变静态）：

```java
/** 变换函数扩展接口：单值变换，链式调用中的每一段（单抽象方法，可直接用 lambda 实现） */
@FunctionalInterface
public interface TransformFunction {
    /**
     * @param value   当前值（链中上一段输出；可能为 null，default() 据此兜底）
     * @param args    函数实参（已剥离引号；无参为空列表）
     * @param context 源 FlatMap（源 + $ 暂存的合并视图）
     * @return 变换结果（写入目标 FlatKey 的值）
     */
    Object apply(Object value, List<String> args, Map<String, Object> context);
}

/** 函数注册中心：内置默认表 + 实例级扩展注册；算术统一走 BigDecimal */
public final class FuncRegistry {

    private static final Map<String, TransformFunction> BUILTINS = Map.ofEntries(
            Map.entry("trim",    (v, args, ctx) -> v == null ? null : String.valueOf(v).trim()),
            Map.entry("upper",   (v, args, ctx) -> v == null ? null : String.valueOf(v).toUpperCase()),
            Map.entry("multiply",(v, args, ctx) -> toDecimal(v).multiply(toDecimal(args.get(0)))),
            Map.entry("round",   (v, args, ctx) -> toDecimal(v).setScale(Integer.parseInt(args.get(0)), RoundingMode.HALF_UP)),
            Map.entry("default", (v, args, ctx) -> v == null ? args.get(0) : v),
            // lower / replace / dateFormat / epochToIso / toIsoDate / toIsoDateTime ……
            Map.entry("toIsoDateTime", (v, args, ctx) -> args.size() == 2
                    ? DateTimeFunctions.toIsoDateTimeInZone(v, args)   // toIsoDateTime(fmt, zone)：换算时区
                    : DateTimeFunctions.toIsoDateTime(v, args)));

    private final Map<String, TransformFunction> functions = new ConcurrentHashMap<>(BUILTINS);

    /** now 依赖注入的 Clock，由实例构造器注册；实例注册发生在内置函数之后——同名函数覆盖任意内置函数 */
    public FuncRegistry(Clock clock) { functions.put("now", (v, args, ctx) -> DateTimeFunctions.now(v, args, clock)); }

    public void register(String name, TransformFunction function) { functions.put(name, function); }
}
```

> 注：`multiply` / `round` 的生产实现即上例——统一经 `BigDecimal` 运算（金额场景的精度与舍入模式显式化）。
> `default()` **只对 null 兜底**，非 null 值（含 `0` / `false` / `""`）原样传递；且源值为 null 时是否执行取决于
> `nullPolicy`（`skip` 下变换链根本不执行，见 §4.2 行为表）。

### 8.6 规则执行引擎【core】

核心转换逻辑实现：处理通配符对位、变换链执行、计算字段求值。引擎在构造期把可变的 `TransferSpec` 深拷贝为
**不可变执行快照** `CompiledPlan`（设计文档 §4 的 CompiledPlan）并完成全部装配期校验；运行期只读快照：

```java
@Slf4j
public class TransferEngine {

    private final CompiledPlan plan;                    // 不可变执行快照（装配期由 spec 深拷贝而来）
    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final FuncRegistry funcRegistry;
    private final ExpressionEvaluator exprEvaluator;
    private final ValidationRunner validationRunner;

    public TransferEngine(TransferSpec spec) {
        this(spec, Map.of(), new JexlExpressionEvaluator(), Clock.systemUTC());
    }

    /** 全参构造器：extraFunctions 覆盖内置函数（含 now）；clock 供 now 变换函数使用（固定 Clock 用于确定性测试） */
    public TransferEngine(TransferSpec spec,
                          Map<String, TransformFunction> extraFunctions,
                          ExpressionEvaluator exprEvaluator,
                          Clock clock) {
        this.exprEvaluator = Objects.requireNonNull(exprEvaluator, "exprEvaluator must not be null");
        this.funcRegistry = new FuncRegistry(clock);
        extraFunctions.forEach(funcRegistry::register);
        this.plan = CompiledPlan.compile(Objects.requireNonNull(spec, "spec must not be null"));   // 装配期校验
        ValidationRunner.validate(plan);
        validateExpressions(plan);
        this.validationRunner = new ValidationRunner(plan, exprEvaluator);
    }

    /** JSON 字符串输入（readTree 复用 flatProcessor 的 BigDecimal mapper，精度语义贯穿） */
    public JsonNode transfer(String sourceJson) {
        return transferNode(flatProcessor.mapper().readTree(sourceJson));
    }

    /** JsonNode 输入（供 usecase 集成侧零序列化往返）：Flatten → intermediate → 规则映射 → computed → defaults → validations → Unflatten */
    public JsonNode transferNode(JsonNode source) { /* 见 §4.2 伪代码 */ }
}
```

> 注（表达式求值上下文）：JEXL 无法直接导航 `crmOrder.lines[*].unitPrice` 这类含 `.` 与 `[*]` 的整串键。
> 聚合形态由 `JexlExpressionEvaluator` 展开后按 BigDecimal 计算；其余表达式把 FlatMap 的嵌套视图
> （`FlatMapProcessor#unflattenToMap`）绑定进表达式上下文，再交给 JEXL 求值，详见 §6.2。
>
> 注（装配期校验）：`CompiledPlan.compile` 负责 spec 结构（未支持特性、通配数量、暂存区引用、目标键结构冲突与
> strictMode 下的重复目标）；`ValidationRunner.validate` 负责 validations 段；`validateExpressions` 负责各处表达式的语法
> （交由 `ExpressionEvaluator#validate`）。三者任一失败都抛 `TransferAssemblyException`——配置错误在启动时暴露，
> 而不是等到首个请求。
>
> 注（可观测性）：第一版未提供 Micrometer 埋点与接收 `MeterRegistry` 的构造器，见 §11.2。

### 8.7 API 使用方式

**配置式使用（推荐）**——从 YAML 配置文件加载规则，适合运维/业务人员操作：

```java
// 经 TransferSpecValidator 加载：JSON Schema 校验 + 重复键拒绝 + 模型绑定共用同一棵解析树
TransferSpec spec;
try (InputStream in = new FileInputStream("order-transfer.yaml")) {
    spec = new TransferSpecValidator().validateAndLoad(in, "order-transfer.yaml");
}
// 构造引擎即装配期校验（结构冲突 / 表达式语法等在此暴露）
TransferEngine engine = new TransferEngine(spec);

String sourceJson = "{\"orderId\":\"ORD-001\",\"items\":[{\"price\":100,\"qty\":2}]}";
JsonNode result = engine.transfer(sourceJson);
```

**代码式使用**——适合需要动态生成规则的场景（Lombok `@Builder`；集合段直接传 `List`）：

```java
TransferSpec spec = TransferSpec.builder()
        .version("1.0")
        .name("动态映射")
        .rules(List.of(MappingRule.builder()
                .from("user.name")
                .to("customer.fullName")
                .transform("trim | upper")
                .build()))
        .defaults(List.of(DefaultValue.builder().to("currency").value("CNY").build()))
        .build();

TransferEngine engine = new TransferEngine(spec);
JsonNode result = engine.transfer(sourceJson);
```

> 注：不要用裸 `JsonMapper` 直接 `readValue` 加载 spec——Jackson 3 默认忽略未知属性，拼错的键（如 `validation:`）
> 会被静默丢弃。确需绕过 Schema 时至少使用 `YAMLMapper.builder().build()`（Jackson 3 下
> `JsonMapper.builder(new YAMLFactory())` 类型不兼容、无法编译），并显式开启 `FAIL_ON_UNKNOWN_PROPERTIES`。

### 8.8 扩展能力

**自定义变换函数**——实现 `TransformFunction` 接口并经引擎构造器的 `extraFunctions` 注册：

```java
public class MaskPhoneFunction implements TransformFunction {
    @Override
    public Object apply(Object value, List<String> args, Map<String, Object> context) {
        String phone = (String) value;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}

// 注册函数：Map 的键即配置中 transform 里引用的函数名
TransferEngine engine = new TransferEngine(spec, Map.of("maskPhone", new MaskPhoneFunction()));
// 配置中使用：transform: "maskPhone"
```

**自定义表达式引擎**——默认使用轻量级 JEXL 作为表达式求值器，可替换为 Spring EL、Aviator 等：

```java
public interface ExpressionEvaluator {
    Object evaluate(String expr, Map<String, Object> context);

    /** 带预构建嵌套视图的求值：调用方对同一文档复用一次嵌套视图，避免逐表达式重拍还原 */
    default Object evaluate(String expr, Map<String, Object> context, Map<String, Object> nestedView) {
        return evaluate(expr, context);
    }

    /** 装配期校验：表达式语法与受支持形态；默认不做任何检查（自定义求值器可选实现） */
    default void validate(String expr, String owner) {
    }
}

// 替换为自定义实现（须自行处理 §6.2 的聚合形态，或在 spec 中避免使用）
TransferEngine engine = new TransferEngine(spec, Map.of(), new AviatorExpressionEvaluator(), Clock.systemUTC());
```

> 注：附录 D.6.2 的表达式安全模型针对**默认的 JEXL 求值器**（默认拒绝沙箱 + 十进制算术）。接入自定义求值器时，
> 表达式的可达类型与算术语义由该实现负责。

### 8.9 Spring Boot 4 自动配置【core，autoconfigure/ 包】

Boot 相关依赖在 core 模块中为 `optional`，非 Boot 环境零影响。配置属性与生效条件的前缀保持一致（`data-transfer`，不占用 `spring.` 保留命名空间）：

```yaml
data-transfer:
  enabled: true
  specs:
    - location: classpath:specs/order-transfer.yaml
      enabled: true
```

```java
@Data
@ConfigurationProperties(prefix = "data-transfer")
public class DataTransferProperties {

    /** 是否启用自动装配，默认 true */
    private boolean enabled = true;

    /** Spec 配置位置列表 */
    private List<SpecLocation> specs = new ArrayList<>();

    @Data
    public static class SpecLocation {
        /** 资源位置，支持 classpath: / file: 等前缀（经 ResourceLoader 解析） */
        private String location;
        /** 是否启用该份 spec */
        private boolean enabled = true;
    }
}
```

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "data-transfer", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DataTransferProperties.class)
public class DataTransferAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TransferSpecValidator transferSpecValidator() {
        return new TransferSpecValidator();
    }

    @Bean
    public TransferSpecRegistry transferSpecRegistry(
            DataTransferProperties properties,
            TransferSpecValidator validator,
            ResourceLoader resourceLoader) throws IOException {

        TransferSpecRegistry registry = new TransferSpecRegistry();
        for (DataTransferProperties.SpecLocation loc : properties.getSpecs()) {
            if (!loc.isEnabled()) {
                continue;
            }
            // classpath:/file: 等位置必须经 ResourceLoader 解析，不能直接 Path.of()
            Resource resource = resourceLoader.getResource(loc.getLocation());
            try (InputStream in = resource.getInputStream()) {
                TransferSpec spec = validator.validateAndLoad(in, loc.getLocation());
                registry.register(spec.getName(), spec);
            }
        }
        return registry;
    }
}
```

> 注：Spring Boot 3 起自动配置注册已迁移至
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
> （Spring Boot 4 已彻底移除 `spring.factories` 注册机制），见附录 A。

### 8.10 Java 端完整示例【demo】

```java
public class OrderTransferDemo {

    public static void main(String[] args) throws Exception {
        // 1. 加载并校验配置（JSON Schema 校验 + 重复键拒绝，失败即 fail-fast）
        TransferSpec spec;
        try (InputStream in = OrderTransferDemo.class.getResourceAsStream("/specs/order-transfer.yaml")) {
            spec = new TransferSpecValidator().validateAndLoad(in, "specs/order-transfer.yaml");
        }

        // 2. 初始化引擎（装配期校验）并执行转换
        String sourceJson = readResource("/samples/order-001.json");
        JsonNode result = new TransferEngine(spec).transfer(sourceJson);

        // 3. 输出（对照 expected/order-001.json）
        System.out.println(JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
```

---

## 九、TransferSpec JSON Schema 校验

### 9.1 JSON Schema 定义

> 注意：下面的 Schema 与仓库文件 `data-transfer-core/src/main/resources/schemas/transfer-spec.json` **逐字一致**（由测试 `DesignDocExamplesTest` 守护，两者漂移即测试失败）——它是唯一的当前 Schema，`v1.3` 起包含 `intermediate` / `validations` / `options.validationMode` / 暂存路径与单键目标路径类型。
>
> Draft 2020-12 的关键字为 `$schema` / `$id` / `$ref` / `$defs`（带 `$` 前缀）；JSON 字符串中的正则反斜杠必须双写（`^\d+` → `^\\d+`），否则 Schema 本身不是合法 JSON。`PathExpression` 正则与 §3.1 语法的 Identifier 定义保持一致（允许 `-`）。自定义分隔符场景下路径正则需按 separator 参数化，此处以默认 `.` 为校验基线。

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://data-transfer.dev/schemas/transfer-spec.json",
  "title": "TransferSpec",
  "description": "声明式 Data Transfer 框架的配置规格定义",
  "type": "object",
  "required": ["version", "name", "rules"],
  "additionalProperties": false,
  "properties": {
    "version": {
      "type": "string",
      "pattern": "^\\d+\\.\\d+(\\.\\d+)?$",
      "description": "配置版本号，格式为 semver（如 1.0 或 1.0.0）"
    },
    "name": {
      "type": "string",
      "minLength": 1,
      "maxLength": 128,
      "description": "配置名称，用于标识和日志输出"
    },
    "options": { "$ref": "#/$defs/TransferOptions" },
    "sources": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/$defs/SourceDeclaration" },
      "description": "多源合并声明（§6.4）；未声明时以整个输入报文为单一源"
    },
    "rules": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/$defs/MappingRule" },
      "description": "核心映射规则列表，至少包含一条规则"
    },
    "intermediate": {
      "type": "array",
      "items": { "$ref": "#/$defs/IntermediateField" },
      "description": "中间结果（暂存区 §6.8）：rules 前在源上下文顺序求值，$ 命名空间不进最终输出；仅可前向引用更早声明的暂存键"
    },
    "computed": {
      "type": "array",
      "items": { "$ref": "#/$defs/ComputedField" }
    },
    "defaults": {
      "type": "array",
      "items": { "$ref": "#/$defs/DefaultValue" }
    },
    "validations": {
      "type": "array",
      "items": { "$ref": "#/$defs/ValidationRule" },
      "description": "数据值校验：对目标 FlatMap（转换后的值）执行，时点在 defaults 后、Unflatten 前"
    },
    "rewrites": {
      "type": "array",
      "items": { "$ref": "#/$defs/PathRewrite" }
    },
    "observability": { "$ref": "#/$defs/ObservabilityConfig" }
  },
  "$defs": {
    "TransferOptions": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "separator": {
          "type": "string", "minLength": 1, "maxLength": 1, "default": "."
        },
        "arrayWildcard": {
          "type": "string", "default": "[*]", "enum": ["[*]"]
        },
        "missingPolicy": {
          "type": "string", "enum": ["warn", "error", "ignore"], "default": "warn"
        },
        "strictMode": {
          "type": "boolean", "default": false
        },
        "nullPolicy": {
          "type": "string", "enum": ["skip", "keep", "default"], "default": "skip",
          "description": "源值为 null 时的处置：skip 不产出目标键（变换链不执行）；keep 原样写入 null（defaults 不覆盖）；default 进入变换链、链后仍为 null 则交给 defaults"
        },
        "validationMode": {
          "type": "string", "enum": ["fail_fast", "collect"], "default": "fail_fast",
          "description": "validations 执行模式：fail_fast 首个失败即抛 / collect 收集全部失败"
        }
      }
    },
    "SourceDeclaration": {
      "type": "object",
      "required": ["alias", "path"],
      "additionalProperties": false,
      "properties": {
        "alias": { "type": "string", "pattern": "^[a-zA-Z_][a-zA-Z0-9_-]*$" },
        "path": {
          "type": "string", "minLength": 1,
          "description": "该源在输入报文中的根路径"
        }
      }
    },
    "MappingRule": {
      "type": "object",
      "required": ["from", "to"],
      "additionalProperties": false,
      "properties": {
        "from": { "$ref": "#/$defs/SourceOrStagedPathExpression" },
        "to": { "$ref": "#/$defs/PathExpression" },
        "transform": { "type": "string", "minLength": 1 },
        "when": {
          "type": "array",
          "minItems": 1,
          "items": { "$ref": "#/$defs/ConditionMapping" }
        }
      },
      "allOf": [
        {
          "if": {
            "properties": { "from": { "pattern": "\\[\\*\\]" } },
            "required": ["from"]
          },
          "then": {
            "properties": { "to": { "pattern": "\\[\\*\\]" } },
            "description": "from 含 [*] 时 to 必须含 [*]（索引对齐语义，§6.1）"
          },
          "else": {
            "properties": { "to": { "not": { "pattern": "\\[\\*\\]" } } }
          }
        }
      ]
    },
    "PathExpression": {
      "type": "string",
      "minLength": 1,
      "pattern": "^[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*|\\[\\d+\\]|\\[\\*\\])*$"
    },
    "LiteralPathExpression": {
      "type": "string",
      "minLength": 1,
      "pattern": "^[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*|\\[\\d+\\])*$",
      "description": "单键目标路径（computed / defaults 写入单个键，不支持 [*]）；容器结果需展开时改用 intermediate"
    },
    "StagedPathExpression": {
      "type": "string",
      "minLength": 2,
      "pattern": "^\\$[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*)*$",
      "description": "暂存路径：$ 前缀强制，纯点路径（不含通配与索引）"
    },
    "SourceOrStagedPathExpression": {
      "type": "string",
      "minLength": 1,
      "pattern": "^\\$?[a-zA-Z_][a-zA-Z0-9_-]*(\\.[a-zA-Z_][a-zA-Z0-9_-]*|\\[\\d+\\]|\\[\\*\\])*$",
      "description": "源路径或 $ 暂存路径"
    },
    "ConditionMapping": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "condition": { "type": "string", "minLength": 1 },
        "transform": { "type": "string", "minLength": 1 },
        "otherwise": { "type": "string", "minLength": 1 }
      },
      "oneOf": [
        {
          "required": ["condition", "transform"],
          "not": { "required": ["otherwise"] }
        },
        {
          "required": ["otherwise"],
          "allOf": [
            { "not": { "required": ["condition"] } },
            { "not": { "required": ["transform"] } }
          ]
        }
      ]
    },
    "ComputedField": {
      "type": "object",
      "required": ["to", "expr"],
      "additionalProperties": false,
      "properties": {
        "to": { "$ref": "#/$defs/LiteralPathExpression" },
        "expr": { "type": "string", "minLength": 1 }
      }
    },
    "IntermediateField": {
      "type": "object",
      "required": ["to"],
      "additionalProperties": false,
      "properties": {
        "to": { "$ref": "#/$defs/StagedPathExpression" },
        "from": { "$ref": "#/$defs/SourceOrStagedPathExpression" },
        "transform": { "type": "string", "minLength": 1 },
        "expr": { "type": "string", "minLength": 1 }
      },
      "oneOf": [
        { "required": ["from"], "not": { "required": ["expr"] } },
        { "required": ["expr"], "not": { "required": ["from"] } }
      ]
    },
    "DefaultValue": {
      "type": "object",
      "required": ["to", "value"],
      "additionalProperties": false,
      "properties": {
        "to": { "$ref": "#/$defs/LiteralPathExpression" },
        "value": { "type": ["string", "number", "boolean", "null"] }
      }
    },
    "ValidationRule": {
      "type": "object",
      "required": ["path"],
      "additionalProperties": false,
      "properties": {
        "path": { "$ref": "#/$defs/PathExpression" },
        "rules": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "required": ["assert", "message"],
            "additionalProperties": false,
            "properties": {
              "assert": { "type": "string", "minLength": 1 },
              "message": { "type": "string", "minLength": 1 }
            }
          }
        },
        "condition": { "type": "string", "minLength": 1 },
        "message": {
          "type": "string", "minLength": 1,
          "description": "condition 形态的失败消息（必填）；rules 形态用断言内的 message"
        }
      },
      "oneOf": [
        { "required": ["rules"], "not": { "required": ["condition"] } },
        { "required": ["condition", "message"], "not": { "required": ["rules"] } }
      ]
    },
    "PathRewrite": {
      "type": "object",
      "required": ["pattern", "replace"],
      "additionalProperties": false,
      "properties": {
        "pattern": { "type": "string", "minLength": 1 },
        "replace": { "type": "string" }
      }
    },
    "ObservabilityConfig": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "tracing": { "type": "boolean", "default": false },
        "auditLog": { "type": "boolean", "default": false },
        "metrics": {
          "type": "array",
          "items": {
            "type": "string",
            "enum": [
              "rule_execution_count",
              "transform_latency_ms",
              "flatten_time_ms",
              "unflatten_time_ms",
              "unmapped_source_keys"
            ]
          }
        }
      }
    }
  }
}
```

> 设计说明：`ConditionMapping` 把全部合法键（`condition` / `transform` / `otherwise`）声明在同级 `properties` 中、再由 `oneOf` 约束组合——若在外层 `additionalProperties: false` 且无同级 `properties` 的节点里只放 `oneOf`，按规范任何属性都会被拒绝（additionalProperties 只看同级 properties 的定义集）。演进提示：新增顶层字段须同步此 Schema（`additionalProperties: false` 即为"拒绝未知键"的强约束）。

### 9.2 校验规则说明

**结构级校验**

| 校验项 | 规则 | 示例报错 |
|---|---|---|
| 必填字段 | `version`、`name`、`rules` 必须存在 | `.rules: is required but missing` |
| 禁止多余字段 | `additionalProperties: false` | `.foo: is not defined in the schema` |
| rules 非空 | `minItems: 1` | `.rules: must have at least 1 items` |
| 版本格式 | `^\d+\.\d+(\.\d+)?$` | `.version: does not match pattern` |
| 重复键（顶层与任意嵌套层级） | 解析期拒绝（`STRICT_DUPLICATE_DETECTION`），非 Schema 关键字 | `Duplicate Object property "rules"` |

**路径表达式校验**——通过 `PathExpression` 正则约束所有路径字段的合法性：

```text
^[a-zA-Z_][a-zA-Z0-9_-]*(\.[a-zA-Z_][a-zA-Z0-9_-]*|\[\d+\]|\[\*\])*$
```

| 合法路径 | 非法路径 | 原因 |
|---|---|---|
| `user.name` | `123.name` | 段名不能以数字开头 |
| `items[0].price` | `items[].price` | 数组索引不能为空 |
| `items[*].sku` | `items[abc].sku` | 索引只能是数字或 `*` |
| `data[*].tags[*]` | `data.**.tags` | 不支持 `**` 语法 |

> 重复键不是 JSON Schema 能表达的约束：Schema 看到的已是解析后的对象，前一个同名键在解析时就丢了。所以重复键由加载链在**解析期**拒绝，且 Schema 校验与模型绑定共用同一棵解析树（§9.3）。

**通配符对齐校验**——通过 `allOf + if/then/else` 实现：当 `from` 包含 `[*]` 时，`to` 也必须包含 `[*]`，反之亦然；两侧 `[*]` 的**数量**一致由引擎装配期校验（见本节末）。

```yaml
# ✅ 合法：通配符对齐
- from: "items[*].price"
  to: "lines[*].amount"

# ❌ 非法：from 有 [*] 但 to 没有
- from: "items[*].price"
  to: "lines.amount"
# 报错: .to: must match pattern [*] when from contains [*]
```

**条件映射校验**——通过 `oneOf` 约束条件映射必须二选一：条件分支必须同时包含 `condition + transform`；兜底分支必须包含 `otherwise`（且不得携带 `condition` / `transform`）。

```yaml
# ✅ 合法
when:
  - condition: "status == 'PAID'"
    transform: "replace('PAID', 'completed')"
  - otherwise: "default('unknown')"

# ❌ 非法：condition 缺少 transform
when:
  - condition: "status == 'PAID'"
```

**枚举值校验**

| 字段 | 允许值 |
|---|---|
| `options.nullPolicy` | `skip`、`keep`、`default` |
| `options.missingPolicy` | `warn`、`error`、`ignore` |
| `options.arrayWildcard` | `[*]` |
| `options.validationMode` | `fail_fast`、`collect` |
| `observability.metrics[]` | `rule_execution_count`、`transform_latency_ms`、`flatten_time_ms`、`unflatten_time_ms`、`unmapped_source_keys` |

**Schema 之外：引擎装配期校验**——以下约束无法（或不适合）用 JSON Schema 表达，由 `TransferEngine` 构造期承担，
任一失败抛 `TransferAssemblyException`（配置错误在启动时暴露，而不是等到首个请求）：

| 校验项 |
|---|
| `sources` / `rewrites` 非空（未实现特性）、自定义 `separator`（仅支持 `.`） |
| 规则 `from` 与 `to` 的 `[*]` 数量一致 |
| 暂存区：`to` 为 `$` 前缀纯点路径且根唯一、`from` / `expr` 二选一、`from` 禁 `[*]`、只能引用更早声明的暂存键 |
| `computed.to` / `defaults.to` 不含 `[*]`（单键目标） |
| 目标键结构冲突：同一路径既是值又是容器、同一前缀既是数组又是对象（无论是否 `strictMode`）；`strictMode` 下路径模式完全相同的重复目标 |
| 路径语法（规则 `from` / `to`、`computed.to` / `defaults.to`、校验 `path`）|
| 表达式语法（`computed.expr` / `intermediate.expr` / `when.condition` / `validations.condition`），含脚本特性（`new` / lambda / 循环 / 赋值）；聚合操作数是含 `[*]` 的合法路径且至多两个 |
| validations：`rules` / `condition` 二选一、谓词白名单、数值 / 日期参数格式、`regex` 的 pattern 合法、`condition` 的 path 至多一级 `[*]` |

### 9.3 Java 校验器实现【core，validation/ 包】

依赖引入——networknt 采用**双发布线**，选用 3.x 线（Java 17+ / **Jackson 3**）；2.x 线对应 Jackson 2，与本基线不兼容，勿混用。
3.x 相对 1.5.x 是大版本重构（`SchemaRegistry` / `Schema` / `List<Error>`），并**原生支持 JSON/YAML 输入**：

```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>3.0.6</version>
</dependency>
```

校验器封装（与仓库实现一致，`readResource` 等辅助方法从略）：

```java
public class TransferSpecValidator {

    private static final String SCHEMA_RESOURCE = "/schemas/transfer-spec.json";

    /** 全部校验器共享一个方言注册表（线程安全，meta-schema 已预加载） */
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final Schema schema;   // networknt 3.x：Schema 实例线程安全，应缓存复用

    private final YAMLMapper yamlMapper = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)   // 重复键：后值不得静默覆盖前值
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)   // nullPolicy: skip ≡ SKIP
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public TransferSpecValidator() {
        // Schema 数据自带 $schema 声明时以其声明的方言为准；未声明时用默认方言
        this.schema = SCHEMA_REGISTRY.getSchema(readResource(SCHEMA_RESOURCE), InputFormat.JSON);
    }

    /** 校验 YAML 配置文件 */
    public ValidationResult validate(Path yamlFile) throws IOException { ... }

    /** 校验 YAML 字符串内容；解析失败（含重复键）同样以校验失败返回 */
    public ValidationResult validate(String yamlContent) { ... }

    /** 校验并直接加载为 TransferSpec：只解析一次——Schema 校验与模型绑定共用同一棵解析树 */
    public TransferSpec validateAndLoad(InputStream in, String source) throws IOException {
        String yamlContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Parsed parsed = parse(yamlContent);                    // 失败时携带原因（重复键、YAML 语法错误等）
        if (parsed.failure() != null) {
            throw new SpecValidationException(
                    "TransferSpec parsing failed (" + source + "): " + parsed.failure(), List.of());
        }
        ValidationResult result = new ValidationResult(schema.validate(parsed.document()));
        if (!result.isValid()) {
            throw new SpecValidationException(
                    "TransferSpec validation failed (" + source + "):\n"
                            + String.join("\n", result.getErrorMessages()),
                    result.errors());
        }
        return yamlMapper.treeToValue(parsed.document(), TransferSpec.class);
    }

    public static class ValidationResult {
        public boolean isValid() { ... }
        public List<String> getErrorMessages() { ... }   // 解析失败时为失败原因；否则为「实例位置: 原因」
    }

    public static class SpecValidationException extends RuntimeException { ... }
}
```

使用方式：

```java
TransferSpecValidator validator = new TransferSpecValidator();

// 方式一：仅校验，获取详细错误信息
TransferSpecValidator.ValidationResult result =
        validator.validate(Path.of("src/test/resources/specs/order-transfer.yaml"));
if (!result.isValid()) {
    System.err.println("配置校验失败：");
    result.getErrorMessages().forEach(msg -> System.err.println(" - " + msg));
    return;
}

// 方式二：校验 + 加载一步到位（流式输入，classpath:/file: 资源均可）
try (InputStream in = new FileInputStream("order-transfer.yaml")) {
    TransferSpec spec = validator.validateAndLoad(in, "order-transfer.yaml");
    TransferEngine engine = new TransferEngine(spec);
}
```

> 注（v1.3）：加载链**只解析一次**——YAML 文本先解析为树，JSON Schema 校验与 `TransferSpec` 绑定共用这棵树；
> 重复键（`rules:` 写两遍、规则内 `to:` 重复、两个 `options:` 块等）与 YAML 别名（`*anchor`，见 D.6.3）在解析期即被拒绝。
> 此前 Schema 校验与绑定各解析一遍，重复键被静默后值覆盖，前一份配置丢失而校验照样通过。`TransferSpecValidator` 是生产入口的必经之路
> （usecase 的 `dataTransfer` step 与 `TransferAssert` 都经 `validateAndLoad` 加载）。

---

## 十、TransferAssert 契约测试工具【data-transfer-test】

### 10.1 覆盖场景

| 场景 | 说明 |
|---|---|
| 精确匹配 | 输出与期望完全一致（含字段数量）；JSON 类型是契约的一部分，`"123"` 与 `123` 不等 |
| 部分匹配 | 输出包含期望中的所有字段，允许多余字段（值比较口径与精确匹配相同） |
| 路径存在性 | 仅校验指定路径是否存在于输出中 |
| 路径值断言 | 校验指定路径的值是否满足条件（路径必须恰好匹配一个键，不存在即失败） |
| 批量样本 | 一个 Spec 对应多组输入/输出样本 |
| 排除路径 | 比对时忽略某些动态路径（如时间戳、UUID） |
| 失败诊断 | 断言失败时输出结构化 diff，快速定位差异 |

### 10.2 流式 API 一览

断言路径统一使用 FlatKey 风格（与引擎键格式一致，`[*]` 为通配符；兼容自动剥离可选的 `$.` JSONPath 根前缀）：

```java
// 场景一：单样本精确匹配
TransferAssert.assertThat("specs/order-transfer.yaml")
        .withFixture("samples/order-001.json")
        .matchesExpected("expected/order-001.json");

// 场景二：部分匹配 + 排除动态字段
TransferAssert.assertThat("specs/user-sync.yaml")
        .withFixture("samples/user-001.json")
        .ignorePaths("crmOrder.createdAt", "crmOrder.traceId", "crmOrder.lines[*].id")
        .partiallyMatches("expected/user-001-partial.json");

// 场景三：路径存在性 + 值断言
TransferAssert.assertThat("specs/order-transfer.yaml")
        .withFixture("samples/order-001.json")
        .execute()
        .pathExists("crmOrder.id")
        .pathExists("crmOrder.lines[*]")
        .pathValueEquals("crmOrder.currency", "CNY")
        .pathValueMatches("crmOrder.id", "^ORD-.*")
        .pathValueGreaterThan("crmOrder.totalAmount", 0);

// 场景四：批量样本测试
TransferAssert.batchAssert("specs/order-transfer.yaml")
        .addCase("samples/order-001.json", "expected/order-001.json")
        .addCase("samples/order-002.json", "expected/order-002.json")
        .addCase("samples/order-003.json", "expected/order-003.json")
        .ignorePaths("crmOrder.processedAt")
        .runAll();

// 场景五：自定义引擎配置
TransferAssert.assertThat(spec)
        .withFixtureJson(fixtureJson)
        .registerFunction("maskPhone", new MaskPhoneFunction())
        .matchesExpectedJson(expectedJson);

// 场景六：期望里的数字以字符串书写时，显式开启「数字字符串宽松」（默认关闭；仅放宽期望侧）
TransferAssert.assertThat("specs/order-transfer.yaml")
        .withFixture("samples/order-001.json")
        .withNumericStringCoercion()
        .matchesExpected("expected/order-001.json");
```

### 10.3 TransferAssert 主类

```java
public class TransferAssert {

    public enum CompareMode {
        /** 严格模式：输出必须与期望完全一致，不允许多余字段 */
        STRICT,
        /** 宽松模式：输出包含期望中的所有字段即可，允许多余字段 */
        PARTIAL
    }

    // ===== 静态工厂方法 =====
    public static TransferAssert assertThat(String specResource) { ... }   // 经 TransferSpecValidator 加载 spec
    public static TransferAssert assertThat(TransferSpec spec) { ... }
    public static BatchAssert batchAssert(String specResource) { ... }

    // ===== 配置方法 =====
    public TransferAssert withFixture(String fixtureResource) { ... }
    public TransferAssert withFixtureJson(String json) { ... }
    public TransferAssert ignorePaths(String... paths) { ... }
    public TransferAssert registerFunction(String name, TransformFunction function) { ... }
    /** 注入固定 Clock，让内置 now 变换在契约测试中确定性 */
    public TransferAssert withClock(Clock clock) { ... }
    /** 把期望侧的数字字符串当作数值比较（仅放宽期望侧）；默认关闭 */
    public TransferAssert withNumericStringCoercion() { ... }

    // ===== 断言方法 =====
    public void matchesExpected(String expectedResource) { ... }        // STRICT
    public void matchesExpectedJson(String expectedJson) { ... }
    public void partiallyMatches(String expectedResource) { ... }        // PARTIAL
    public void partiallyMatchesJson(String expectedJson) { ... }
    /** 执行引擎并返回链式路径断言上下文 */
    public AssertContext execute() { ... }

    // ===== 内部方法 =====
    private void doAssert(String expectedJson) {
        JsonNode actualResult = buildEngine().transfer(fixtureJson);
        String actualJson = mapper.writeValueAsString(actualResult);

        // 断言工具复用引擎的同一拍平实现，保证键格式完全一致
        Map<String, Object> expectedFlat = flatProcessor.flatten(mapper.readTree(expectedJson), ".");
        Map<String, Object> actualFlat = flatProcessor.flatten(mapper.readTree(actualJson), ".");

        removeIgnoredPaths(expectedFlat, ignorePaths);
        removeIgnoredPaths(actualFlat, ignorePaths);

        DiffResult diff = DiffEngine.diff(expectedFlat, actualFlat, compareMode, numericStringCoercion);
        if (!diff.isEmpty()) {
            throw new AssertionError(buildFailureMessage(diff, actualJson));
        }
    }
}
```

> `mapper` 是与引擎**同一个 BigDecimal mapper**（`flatProcessor.mapper()`）：实际输出里的长小数 / 大数在比较前不被舍入，
> 精度丢失才能被检出（v1.3 之前两侧都被先舍入成 double 再比较，恒等映射丢精度时断言照样通过）。
> `ignorePaths` 在 `execute()` 返回的 `AssertContext` 构造期即应用，与 `matchesExpected` 主流程同口径。

### 10.4 AssertContext — 链式路径断言

```java
public class AssertContext {

    /** 断言指定路径存在（[*] 通配）；语义为「键或键的祖先」 */
    public AssertContext pathExists(String jsonPath) { ... }
    /** 断言指定路径不存在 */
    public AssertContext pathNotExists(String jsonPath) { ... }

    /** 断言指定路径的值等于期望值：路径必须恰好匹配一个键，不存在即失败；
        JSON 类型是契约的一部分，String 与 Number 不等（数字字符串宽松须显式开启） */
    public AssertContext pathValueEquals(String jsonPath, Object expected) { ... }
    public AssertContext pathValueMatches(String jsonPath, String regex) { ... }
    public AssertContext pathValueGreaterThan(String jsonPath, double threshold) { ... }
    public AssertContext pathValueLessThan(String jsonPath, double threshold) { ... }
    public AssertContext pathValueContains(String jsonPath, String substring) { ... }
    public AssertContext pathValueIn(String jsonPath, Object... candidates) { ... }
    public AssertContext pathValueNotNull(String jsonPath) { ... }
    /** 断言匹配指定路径的所有值都满足断言（至少要有一个匹配） */
    public AssertContext allPathValues(String jsonPath, Consumer<Object> valueAssert) { ... }

    public JsonNode getActualResult() { ... }
    public Map<String, Object> getActualFlatMap() { ... }   // 已应用 ignorePaths 排除

    /** 单值断言的取值：路径必须恰好匹配一个键（0 个即不存在，多个请改用 allPathValues()） */
    private Object requireSingleValue(String jsonPath) {
        List<Map.Entry<String, Object>> matched = /* 按路径正则匹配 actualFlatMap */;
        if (matched.isEmpty()) {
            throw new AssertionError("路径 " + jsonPath + " 不存在（单值断言要求恰好匹配一个路径）");
        }
        if (matched.size() > 1) {
            throw new AssertionError("路径 " + jsonPath + " 匹配到多个值，请使用 allPathValues()：…");
        }
        return matched.get(0).getValue();
    }
}
```

> **v1.3：消除「缺失等于 null」的假通过路径。** 历史实现里路径不存在时取值返回 `null`，于是
> `pathValueEquals("missingField", null)` 会通过——同一个 `null` 同时表示"字段不存在"与"存在但为空"。
> 现在单值断言（`pathValueEquals` / `Matches` / `GreaterThan` / `LessThan` / `Contains` / `In` / `NotNull`）
> 都要求路径恰好匹配一个键，不存在即失败；要断言"存在且值为 JSON null"仍写 `pathValueEquals(path, null)`。

### 10.5 DiffEngine — 结构化差异比对

```java
public final class DiffEngine {

    public static DiffResult diff(Map<String, Object> expected, Map<String, Object> actual,
                                  TransferAssert.CompareMode mode) {
        return diff(expected, actual, mode, false);
    }

    /** coerceNumericStrings：是否把期望侧的数字字符串当作数值比较（仅放宽期望侧），默认关闭 */
    public static DiffResult diff(Map<String, Object> expected, Map<String, Object> actual,
                                  TransferAssert.CompareMode mode, boolean coerceNumericStrings) {
        List<DiffEntry> entries = new ArrayList<>();

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String path = entry.getKey();
            if (!actual.containsKey(path)) {
                entries.add(DiffEntry.missing(path, entry.getValue()));
                continue;
            }
            if (!looseEquals(entry.getValue(), actual.get(path), coerceNumericStrings)) {
                entries.add(DiffEntry.mismatch(path, entry.getValue(), actual.get(path)));
            }
        }

        if (mode == TransferAssert.CompareMode.STRICT) {
            for (String path : actual.keySet()) {
                if (!expected.containsKey(path)) {
                    entries.add(DiffEntry.unexpected(path, actual.get(path)));
                }
            }
        }

        return new DiffResult(entries);
    }

    /** 值等价：Objects.equals 之外只放宽「Number ↔ Number 按 BigDecimal compareTo」 */
    static boolean looseEquals(Object expected, Object actual, boolean coerceNumericStrings) { ... }
}
```

> **v1.3：比较口径收紧为 JSON 类型是契约的一部分。** 此前期望侧 String 向 Number 单向宽松
> （本意是照顾把数字读成字符串的 YAML 期望文件），代价是 `{"accountId": "000123"}` 与 `{"accountId": 123}` 判等——
> 字符串类型、前导零、下游接口约定都可能被悄悄放过，而且 `STRICT` 与 `PARTIAL` 都如此。现在 `"000123"` 与 `123` 不等；
> 确需宽松时显式 `withNumericStringCoercion()`。数值跨形态等价保留（`113` 与 `113.00` 按 BigDecimal `compareTo`，
> 超过 2^53 的大整数与长小数不经 double）。

### 10.6 DiffResult & DiffEntry

```java
/** 单条差异：缺失 / 不匹配 / 多余 */
public record DiffEntry(String path, DiffType type, Object expected, Object actual) {

    public static DiffEntry missing(String path, Object expected) {
        return new DiffEntry(path, DiffType.MISSING, expected, null);
    }

    public static DiffEntry mismatch(String path, Object expected, Object actual) {
        return new DiffEntry(path, DiffType.MISMATCH, expected, actual);
    }

    public static DiffEntry unexpected(String path, Object actual) {
        return new DiffEntry(path, DiffType.UNEXPECTED, null, actual);
    }
}

/** 差异汇总（不可变） */
public record DiffResult(List<DiffEntry> entries) {

    public DiffResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size() { return entries.size(); }
    public Map<DiffType, Long> summary() { ... }
}

public enum DiffType {
    MISSING("缺失"),
    MISMATCH("不匹配"),
    UNEXPECTED("多余");
    // toString() 返回中文标签
}
```

### 10.7 BatchAssert — 批量样本测试

```java
public class BatchAssert {

    public BatchAssert(TransferSpec spec) { ... }
    public BatchAssert addCase(String fixtureResource, String expectedResource) { ... }
    public BatchAssert addCaseJson(String fixtureJson, String expectedJson) { ... }
    public BatchAssert ignorePaths(String... paths) { ... }
    /** 注册自定义变换函数（透传给每个 case 的 TransferAssert） */
    public BatchAssert registerFunction(String name, TransformFunction function) { ... }

    /** 全部执行；任一失败在汇总报告后抛 AssertionError */
    public void runAll() {
        if (cases.isEmpty()) {
            throw new IllegalStateException(
                    "no test cases added; call addCase()/addCaseJson() before runAll()");   // 0 case 假绿
        }
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            TestCase testCase = cases.get(i);
            try {
                TransferAssert assertion = TransferAssert.assertThat(spec);
                // …按 case 设置 fixture / ignorePaths / 自定义函数，执行 matchesExpected(Json)
            } catch (AssertionError | RuntimeException e) {
                failures.add("Case #%d (%s): %s".formatted(i, testCase.description(), message(e)));
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("批量契约测试失败 [总计 %d，失败 %d]:\n%s"
                    .formatted(cases.size(), failures.size(), String.join("\n", failures)));
        }
    }

    private record TestCase(String fixtureResource, String expectedResource,
                            String fixtureJson, String expectedJson) { ... }
}
```

> `BatchAssert` 当前只做 `STRICT` 匹配；需要 `PARTIAL` 或固定 Clock 时用 `TransferAssert` 逐样本断言。

### 10.8 JUnit 扩展集成【data-transfer-test，ext/ 包】

自定义注解（`@Test` 支持作为元注解，标注后可直接被 JUnit 发现）。注解元标注 `@ExtendWith`——**扩展随注解自带**，
测试类无需再显式注册（漏注册会让断言静默跳过、空方法体假绿）：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TransferSpecExtension.class)
@org.junit.jupiter.api.Test
public @interface TransferSpecTest {
    String spec();
    String fixture();
    String expected();
    TransferAssert.CompareMode mode() default TransferAssert.CompareMode.STRICT;
    String[] ignorePaths() default {};
}
```

扩展实现在测试方法调用处执行契约断言，断言通过后再进入方法体（相比 `BeforeEachCallback`，失败堆栈归属更准确）：

```java
public class TransferSpecExtension implements InvocationInterceptor {   // org.junit.jupiter.api.extension.InvocationInterceptor

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext context) throws Throwable {
        TransferSpecTest annotation =
                invocationContext.getExecutable().getAnnotation(TransferSpecTest.class);
        if (annotation == null) {
            invocation.proceed();
            return;
        }

        TransferAssert assertion = TransferAssert.assertThat(annotation.spec())
                .withFixture(annotation.fixture())
                .ignorePaths(annotation.ignorePaths());
        if (annotation.mode() == TransferAssert.CompareMode.PARTIAL) {
            assertion.partiallyMatches(annotation.expected());
        } else {
            assertion.matchesExpected(annotation.expected());
        }

        invocation.proceed();   // 契约断言通过后再执行测试方法体（可为空）
    }
}
```

> `InvocationInterceptor` 是 **JUnit 5.5 引入**的统一拦截接口（`TestExecutionInterceptor` 自 5.5 弃用、6.0 移除）；
> 本工程 Spring Boot 4.1 解析到 JUnit 6.0.x，故只能使用 `InvocationInterceptor`——它不是「JUnit 6 才有的等价物」。

使用方式（测试类位于 `data-transfer-demo`，见 §8.10 与附录 A）：

```java
// 类上无需 @ExtendWith：@TransferSpecTest 元标注自带扩展注册
class OrderTransferContractTest {

    @TransferSpecTest(
            spec = "specs/order-transfer.yaml",
            fixture = "samples/order-001.json",
            expected = "expected/order-001.json"
    )
    void order001_matchesExpectedExactly() {
        // 注解驱动，方法体无需代码
    }

    @Test
    void order001_chainedPathAssertions() {
        TransferAssert.assertThat("specs/order-transfer.yaml")
                .withFixture("samples/order-001.json")
                .execute()
                .pathExists("crmOrder.id")
                .pathValueEquals("crmOrder.currency", "CNY")
                .pathValueGreaterThan("crmOrder.totalAmount", 0)
                .allPathValues("crmOrder.lines[*].unitPrice",
                        v -> Assertions.assertTrue(((Number) v).doubleValue() > 0));
    }
}
```

### 10.9 测试资源目录约定【data-transfer-demo】

```text
src/test/resources/
├── specs/                     # TransferSpec 配置文件
│   ├── order-transfer.yaml
│   └── user-sync.yaml
├── samples/                   # 输入样本数据
│   ├── order-001.json
│   ├── order-002.json
│   └── user-001.json
└── expected/                  # 期望输出数据
    ├── order-001.json
    ├── order-002.json
    └── user-001-partial.json
```

### 10.10 断言失败输出示例

```text
TransferSpec 契约测试失败 [spec: ECommerce → CRM]
差异明细:
  路径: crmOrder.lines[0].unitPrice | 类型: 不匹配 | 期望: 113.0 | 实际: 112.87
  路径: crmOrder.lines[1].productCode | 类型: 缺失 | 期望: B002 | 实际: null
  路径: crmOrder.traceId | 类型: 多余 | 期望: null | 实际: abc-123-def
实际输出: {"crmOrder":{"id":"ORD-001","lines":[{"unitPrice":112.87,...}]}}
```

### 10.11 与 JSON Schema 校验的协同

`TransferSpecValidator`（配置格式校验）与 `TransferAssert`（行为契约校验）在测试流程中分工明确：

```text
配置格式校验（TransferSpecValidator） → 行为契约校验（TransferAssert） → JUnit 5 报告
```

| 阶段 | 工具 | 校验内容 |
|---|---|---|
| 配置加载 | TransferSpecValidator | YAML 格式、字段类型、路径语法、枚举值、通配符对齐 |
| 行为验证 | TransferAssert | 给定输入，输出是否符合期望的映射行为 |

推荐的测试类结构（位于 `data-transfer-demo`）：

```java
class OrderTransferTest {

    private static final TransferSpecValidator VALIDATOR = new TransferSpecValidator();

    @Test
    void specShouldBeValid() throws Exception {
        String yaml;
        try (var in = getClass().getResourceAsStream("/specs/order-transfer.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        TransferSpecValidator.ValidationResult result = VALIDATOR.validate(yaml);
        Assertions.assertTrue(result.isValid(),
                "Spec 格式校验失败: " + result.getErrorMessages());
    }

    @Test
    void order001ShouldTransferCorrectly() {
        TransferAssert.assertThat("specs/order-transfer.yaml")
                .withFixture("samples/order-001.json")
                .matchesExpected("expected/order-001.json");
    }

    @Test
    void order002WithDynamicFields() {
        TransferAssert.assertThat("specs/order-transfer.yaml")
                .withFixture("samples/order-002.json")
                .ignorePaths("crmOrder.processedAt")
                .partiallyMatches("expected/order-002-partial.json");
    }

    @Test
    void allSamplesShouldPass() {
        TransferAssert.batchAssert("specs/order-transfer.yaml")
                .addCase("samples/order-001.json", "expected/order-001.json")
                .addCase("samples/order-002.json", "expected/order-002.json")
                .addCase("samples/order-003.json", "expected/order-003.json")
                .ignorePaths("crmOrder.processedAt")
                .runAll();
    }
}
```

---

## 十一、工程化建议

### 11.1 性能优化

| 策略 | 说明 | 状态 |
|---|---|---|
| 装配期编译 | spec 加载时预解析为不可变执行快照 `CompiledPlan`：规则 `to` 的段模式与通配位置、路径匹配正则、断言 regex、日期 formatter 均在构造期准备好 | 已实现（v1.3） |
| 零文本往返的 Unflatten | `unflattenToMap` / `unflatten` 自建，不再经 JSON 文本往返（历史实现每次还原要往返两次） | 已实现（v1.3） |
| 路径 Trie | 用前缀树索引 FlatMap 的 key，加速通配符匹配（当前为逐键正则匹配，工作量 ≈ 规则数 × 扁平键数） | 未实现，实测有需要再做 |
| 惰性 Flatten | 对超大 JSON，仅在规则涉及的路径子树上执行 flatten | 未实现 |
| 大数组分片并行 | 同构数组的逐元素变换可用 parallel stream / ForkJoin 分片执行 | 未实现 |

> 「运行时零解析开销」只对装配期能确定的部分成立：每次 transfer 仍要做通配匹配、变换链调用与 JEXL 表达式求值
> （JEXL 自带表达式缓存）。
>
> **资源预算**：引擎不对输入大小、嵌套深度、展开后的键数量设上限，也不限制单次转换的 CPU / 内存——
> 表达式沙箱限制的是**能访问什么**，不限制消耗多少。输入来自不可信方时，调用方须在入口处限制报文大小与超时。

### 11.2 可观测性

```yaml
observability:
  tracing: true            # 记录每条规则的输入/输出
  metrics:
    - rule_execution_count
    - transform_latency_ms
    - flatten_time_ms
    - unmapped_source_keys # 源数据中未被任何规则命中的 key
  auditLog: true           # 输出完整的映射审计日志
```

> **实现状态**：`observability` 段目前**仅模型承载**——上述埋点、审计日志与接收 `MeterRegistry` 的构造器均未实现，
> 配置后不产生任何效果（附录 C.3）。若排期实现，须同时设计**值采集与脱敏策略**：完整的输入 / 输出审计会直接包含业务数据。

当前已有的可观测面：

- `missingPolicy: warn` 对未命中任何源键的规则打 WARN 日志（只记规则路径，不记值）；
- 异常携带排错上下文：`TransformException`（规则索引、from / to、函数名、截断到 200 字符的当前值）、
  `ValidationFailure`（路径、规则、实际值）、`ExpressionException`（表达式与所属声明）。
  **这些异常消息会包含实际数据值**——面向客户端返回时按 `exception-handling.md` 脱敏，细节只进日志；
- 数据链显性化：usecase 侧 `dataTransfer` step 的 `dataflow()` 声明键级血缘（只记键名，不记值）。

### 11.3 技术选型参考

| 层 | 推荐 | 说明 |
|---|---|---|
| Spec 格式 | YAML（人类可读）+ JSON Schema 校验 | — |
| JSON 解析 | Jackson 3（`tools.jackson.core:jackson-databind` + `tools.jackson.dataformat:jackson-dataformat-yaml`） | 全工程唯一 JSON 版本线，随 BOM 管理（附录 B） |
| Flatten/Unflatten | `json-flattener` 0.18.2（`com.github.wnameless.json:json-flattener`） | v0.18.0 起原生支持 Jackson 3（默认 JsonCore 切换为 `Jackson3JsonCore`，Jackson 2 仍经 json-base 支持）；Java 17+；`Jackson3JsonValue` 包装 `JsonNode` 零往返、`unflattenAsMap(Map)` Map 直达还原；通配符匹配为引擎自研（§8.4） |
| 路径解析 | 自研轻量解析器 | 避免引入 jq 等重依赖 |
| 表达式引擎 | Apache Commons JEXL 3.3（可替换为 Aviator、Spring EL） | `[*]` 由引擎预处理展开后再求值（§8.6 注）；默认求值器带默认拒绝沙箱与十进制算术（附录 D.6.1 / D.6.2） |
| Schema 校验 | networknt json-schema-validator **3.0.6**（3.x 发布线） | 官方双发布线：2.x = Java 8+/Jackson 2，**3.x = Java 17+/Jackson 3**（本项目基线选 3.x，勿与 2.x 混用）；支持 Draft 2020-12；原生接受 JSON/YAML 输入（schema 与数据均可），§9.3 的 `validateAndLoad` 直接以 YAML 校验；API 相对 1.5.x 全面重构（`SchemaRegistry`/`Schema`/`List<Error>`） |
| 可观测性 | SLF4J（已实现：WARN 日志与异常上下文）；Micrometer（未实现） | — |
| 集成方式 | SDK 库（已实现）/ Spring Boot 自动配置（已实现：`data-transfer.enabled` + `specs[].location`）/ CLI 工具、HTTP 微服务（未实现） | core 模块无 Spring 亦可独立使用（`data-transfer-demo` 即无 Spring 的实证） |

---

## 附录 A：Maven 多模块工程结构

仓库为六模块聚合（groupId 统一 `com.example`，父 POM 继承 `spring-boot-starter-parent` 4.1.0）；data-transfer 三个模块
自锁版本的依赖（json-flattener / JEXL / json-schema-validator）在父 POM 的 `<properties>` 声明：

```text
usecase-framework/                                 # 聚合根（父 POM）
├── pom.xml
├── docs/                                          # 本设计文档 + 用例编排框架设计文档
├── data-transfer-core/                            # 引擎本体（无 Spring 亦可独立使用）
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/example/datatransfer/core/
│       │   │   ├── TransferEngine.java            # 运行期执行：Flatten → intermediate → 规则 → computed → defaults → validations → Unflatten
│       │   │   ├── CompiledPlan.java              # 不可变执行快照 + spec 结构装配期校验
│       │   │   ├── TargetStructure.java           # 目标键结构冲突的静态分析
│       │   │   ├── ValidationRunner.java          # validations 段：装配期校验 + 运行期执行
│       │   │   ├── ArgumentParser.java            # 变换链 / 断言共用的实参解析
│       │   │   ├── TransferSpecRegistry.java
│       │   │   ├── spec/                          # 配置模型（TransferSpec / TransferOptions / MappingRule / …）
│       │   │   ├── flatten/
│       │   │   │   ├── FlatMapProcessor.java      # json-flattener 封装 + 自研通配符匹配 + 自建 unflatten
│       │   │   │   └── PathParser.java
│       │   │   ├── transform/
│       │   │   │   ├── TransformFunction.java
│       │   │   │   ├── FuncRegistry.java
│       │   │   │   └── DateTimeFunctions.java     # 日期函数族
│       │   │   ├── expression/
│       │   │   │   ├── ExpressionEvaluator.java
│       │   │   │   ├── JexlExpressionEvaluator.java   # 默认 JEXL 求值器：默认拒绝沙箱
│       │   │   │   └── DecimalArithmetic.java        # 十进制算术
│       │   │   ├── exception/                     # TransferException 体系（装配 / 匹配 / 变换 / 校验 / 表达式）
│       │   │   ├── validation/
│       │   │   │   └── TransferSpecValidator.java
│       │   │   └── autoconfigure/                 # Spring Boot 4 自动配置（Boot 依赖 optional）
│       │   │       ├── DataTransferAutoConfiguration.java
│       │   │       └── DataTransferProperties.java
│       │   └── resources/
│       │       ├── schemas/transfer-spec.json
│       │       └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│       └── test/                                  # 单元与引擎行为测试（数值契约 / 空值策略 / strictMode / 不可变性 / 暂存区 / 校验 …）
├── data-transfer-test/                            # 契约测试工具（依赖 core + JUnit Jupiter API）
│   └── src/main/java/com/example/datatransfer/test/
│       ├── TransferAssert.java / AssertContext.java / BatchAssert.java
│       ├── DiffEngine.java / DiffResult.java / DiffEntry.java / DiffType.java
│       └── ext/TransferSpecTest.java + TransferSpecExtension.java
├── data-transfer-demo/                            # 可运行示例 + 契约测试示范（specs / samples / expected 三件套）
└── usecase-framework-{core,test,demo}/            # 用例编排框架；core 内置 dataTransfer step（引用式集成引擎）
```

> 注：Spring Boot 4 的自动配置注册文件为
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
> （内容为自动配置类全限定名，一行一个），`spring.factories` 注册机制已移除。

---

## 附录 B：依赖清单（按模块）

**版本策略**：Jackson 3 全工程统一，随 Spring Boot 4 BOM（仓库内即 `spring-boot-starter-parent`）管理；JUnit / Spring 同理。
仅 JEXL、json-flattener 与 Schema 校验器由本 SDK 自锁版本。**Jackson 3 的 BOM 坐标是 `tools.jackson:jackson-bom`**
（groupId 为 `tools.jackson`，不带 `.core`——`tools.jackson.core` 是 `jackson-databind` / `jackson-core` 等**工件**的 groupId）。

**父 POM**（独立发布形态；仓库内由 `spring-boot-starter-parent` 承担）：

```xml
<dependencyManagement>
    <dependencies>
        <!-- Jackson 3 全工程统一版本线 -->
        <dependency>
            <groupId>tools.jackson</groupId>
            <artifactId>jackson-bom</artifactId>
            <version>${jackson3.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- 模块间依赖 -->
        <dependency>
            <groupId>com.example.datatransfer</groupId>
            <artifactId>data-transfer-core</artifactId>
            <version>${revision}</version>
        </dependency>
        <dependency>
            <groupId>com.example.datatransfer</groupId>
            <artifactId>data-transfer-test</artifactId>
            <version>${revision}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**data-transfer-core**（与仓库 `data-transfer-core/pom.xml` 一致）：

```xml
<dependencies>
    <!-- 空安全契约（JSpecify；版本随 Boot BOM） -->
    <dependency>
        <groupId>org.jspecify</groupId>
        <artifactId>jspecify</artifactId>
    </dependency>

    <!-- JSON 解析（Jackson 3，版本随 Boot BOM） -->
    <dependency>
        <groupId>tools.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>tools.jackson.dataformat</groupId>
        <artifactId>jackson-dataformat-yaml</artifactId>
    </dependency>

    <!-- Flatten/Unflatten（v0.18.0+ 原生支持 Jackson 3；json-base 等传递依赖自动引入） -->
    <dependency>
        <groupId>com.github.wnameless.json</groupId>
        <artifactId>json-flattener</artifactId>
        <version>0.18.2</version>
    </dependency>

    <!-- 表达式引擎 -->
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-jexl3</artifactId>
        <version>3.3</version>
    </dependency>

    <!-- JSON Schema 校验（3.x 发布线：Java 17+ / Jackson 3；勿与 2.x/Jackson 2 线混用） -->
    <dependency>
        <groupId>com.networknt</groupId>
        <artifactId>json-schema-validator</artifactId>
        <version>3.0.6</version>
    </dependency>

    <!-- Bean 校验：模型注解是契约的文档化表达（主要约束由 Schema 与装配期校验承担） -->
    <dependency>
        <groupId>jakarta.validation</groupId>
        <artifactId>jakarta.validation-api</artifactId>
    </dependency>

    <!-- Spring Boot 4 自动配置（optional，非 Boot 环境零影响） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Lombok：仅编译期 -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**data-transfer-test**：

```xml
<dependencies>
    <dependency>
        <groupId>com.example.datatransfer</groupId>
        <artifactId>data-transfer-core</artifactId>
    </dependency>

    <!-- JUnit Jupiter API：编译期需要，运行时由宿主工程提供 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter-api</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**data-transfer-demo**：

```xml
<dependencies>
    <dependency>
        <groupId>com.example.datatransfer</groupId>
        <artifactId>data-transfer-core</artifactId>
    </dependency>

    <!-- 契约测试示范 -->
    <dependency>
        <groupId>com.example.datatransfer</groupId>
        <artifactId>data-transfer-test</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- 不依赖 Spring：core 独立可用的实证 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 总结

该框架的设计哲学是"降维打击"：**Flatten** 把任意复杂的嵌套 JSON 拍平为一维键值空间，消除结构差异；**声明式映射**让所有转换逻辑用 YAML 配置表达——零代码、可版本化（热加载未实现）；**Unflatten** 根据目标路径自动重建嵌套结构。最终效果是：对接一个新的上下游系统，只需写一份 TransferSpec 配置文件，无需改任何代码。

工程落地按三模块推进：`data-transfer-core`（引擎与校验，Jackson 3 统一版本线、Flatten/Unflatten 复用 json-flattener 0.18.2——v0.18.0 起原生支持 Jackson 3）、`data-transfer-test`（TransferAssert 契约测试工具）、`data-transfer-demo`（端到端示例与契约测试示范）。配合 JSON Schema 校验 + 装配期校验（配置格式与结构）与 TransferAssert（运行时行为），从配置格式到映射行为形成完整的校验链路，任何一层出问题都能在启动或测试阶段被拦截。

---

## 附录 C：落地注记（v1.0 实现，2026-09-07）

三模块已在本工程落地（groupId 统一 `com.example`，与 usecase-framework 六模块聚合；设计文档中的 `com.example.datatransfer`
groupId 为独立发布形态，仓库内不适用），并以**内置 `dataTransfer` step 类型**集成进 usecase-framework-core（config：`spec`（classpath 引用）/
`source`（SpEL，缺省 `#payload`）/ `as`（旁路键，缺省覆盖 payload）；装配期 Schema 校验 fail-fast；默认 source 时覆写 `dataflow()`
声明键级血缘）。`mvn clean test` 全量绿（v1.3 现值）：data-transfer-core 184 + data-transfer-test 33 + data-transfer-demo 9 +
usecase-framework-core 227 + usecase-framework-test 14 + usecase-framework-demo 29，合计 496。

### C.1 实现中实证并修正的文档表述

| 原表述 | 实测结论（已按此实现） |
|---|---|
| §5.3-1「空对象/空数组拍平后不产生任何键，Unflatten 无法还原」 | **不成立**：json-flattener v0.10+ 将空容器**作为叶子值保留**（`{"a": {}}` → 键 `a`、值为空 Map），往返可还原（`FlatMapProcessorTest.emptyContainers_areKeptAsLeafValues` 实证）；但根级空容器会被库的 `root` 键包裹，引擎展开容器结果时须单独处理 |
| §8.4/§10.3「FlatKey 中除 `.` 与 `[*]` 外不含正则元字符，只需转义 `.`」 | **论断错误**：字面索引 `[0]` 的方括号是正则元字符（不转义即成字符类），`$` 是行尾锚；`expandWildcard` 与断言路径统一经 `FlatMapProcessor.wildcardPattern`（占位保护 `[*]` → 转义 `[]` / `.` / `$` → 还原 `[\d+]`）处理 |
| §6.3 `otherwise: "default('unknown')"` 的直觉语义 | `default()` **仅对 null 兜底**，非 null 值原样传递；「无分支命中的兜底输出」需写针对当前值的变换（如 `replace`）或依赖 `nullPolicy=KEEP / DEFAULT` 下的 null 值 |
| §9.3 networknt API「待 javadoc 核实」 | 全部实证可用：`SchemaRegistry.withDefaultDialect(SpecificationVersion)` 单参、`getSchema(String, InputFormat)`、`validate(JsonNode)` |
| §8.4 json-flattener 实例级 API「设计示意」 | 全部实证可用：`Jackson3JsonValue`（`com.github.wnameless.json.base`）包装 `JsonNode`、`new JsonFlattener(...).withSeparator(char).flattenAsMap()`、`new JsonUnflattener(JsonCore, Map).withSeparator(char).unflattenAsMap()` |

### C.2 与设计文档的实现偏差（环境事实）

- **Jackson 3 YAML mapper**：`JsonMapper.builder(new YAMLFactory())` 不可用（类型不兼容），须 `YAMLMapper.builder()...build()`（正文 §8.7 已注明）。
- **JUnit**：Spring Boot 4.1 解析到 JUnit 6.0.x，`TestExecutionInterceptor` 已移除，`TransferSpecExtension` 改用
  `InvocationInterceptor`——该接口自 **JUnit 5.5** 即存在（`TestExecutionInterceptor` 自 5.5 弃用、6.0 移除），并非 JUnit 6 才有的等价物。
- **computed 求值**：实现了受限聚合形态 `agg(PATH)` / `agg(PATH * PATH)`（agg ∈ sum/avg/count/min/max，双路径按索引元组对位逐元素相乘，
  BigDecimal 精度）；其余表达式以目标 FlatMap 的嵌套视图绑定顶层键后交 JEXL，算术经 `DecimalArithmetic` 统一为十进制（v1.3）。
- **TransferAssert 的 spec 加载**：统一经 `TransferSpecValidator.validateAndLoad`（加载即 Schema 校验，对齐 §10.11 校验链路）；
  `pathExists` 支持「键或键祖先」匹配（`a.lines[*]` 可命中 `a.lines[0].unitPrice` 的容器存在性）。

### C.3 第一版裁剪清单（装配期/构造期 fail-fast 拒绝，不静默忽略）

`sources`（多源合并）与 `rewrites`（路径改写）保留模型字段但引擎构造期抛「not yet supported」；未实现：批量模式 `transfer_batch`、
dry-run、热加载、`fn:` 前缀函数语法（自定义函数经构造器 `extraFunctions` 注册后直接以函数名引用）、指标埋点与审计日志
（`ObservabilityConfig` 仅模型承载，配置后不产生效果）、§3.2 函数表中的其余内置函数（已实现 trim/lower/upper/replace/multiply/round/default
+ 日期函数族 dateFormat/epochToIso/toIsoDate/toIsoDateTime/now，其余经 `FuncRegistry.register` 扩展）、输入大小 / 深度 / 表达式资源预算（§11.1）。

### C.4 v1.3 实证并修正的文档表述（2026-11-27）

| 原表述 | 实测结论 |
|---|---|
| §5.3-4 / §8.4 / 附录 D「数值已 BigDecimal，浮点数全程保留」 | **不成立**（v1.3 已修复，见 D.6.1）：json-base 的 `toJavaNumber` 把可经 double 往返的小数降为 `Double`；库的 `unflattenAsMap` 又经其默认 mapper 做 JSON 文本往返，BigDecimal 全部变成 double——恒等映射即丢精度 |
| D.1「3.5 JEXL 沙箱：`JexlSandbox(true)` 白名单模式」 | **不成立**（v1.3 已修复，见 D.6.2）：`JexlSandbox(true)` 是 allow-box，未配置的类默认全部允许；拦截效果全部来自 JEXL 默认权限，而它放行文件读写 |
| §6.8「`rules.from` 支持 `[*]` 通配导航」 | v1.3 之前带索引 / 通配的暂存引用（`$x[*].f`、`$lst[1]`）被构造期误判为「未声明」；数组暂存被展开成 `$x.[0].f` 畸形键。均已修复 |
| §9.1 正文 Schema | 滞后于仓库文件（缺 `intermediate` / `validations` / `validationMode` / 暂存路径）；现与仓库文件逐字一致并由测试守护 |
| 附录 B「`tools.jackson.core:jackson-bom`」 | 坐标错误：BOM 的 groupId 是 `tools.jackson` |
| §8.5「`TransformFunction` 有 `name()` + `apply()` 两个抽象方法」 | 与仓库不符（单抽象方法，lambda 不能作双抽象方法接口的目标类型） |
| §8.3 / §8.7「`@Builder` + `.rule(...)` / `.defaultValue(...)`」 | `@Builder` 不保留字段初始化值，仓库模型也没有这两个便捷方法 |
| §11.2「内置 Micrometer 指标埋点」「`TransferEngine` 提供接收 `MeterRegistry` 的构造器」 | 未实现 |
| D.2「2.9 coalesce：`a ?: b ?: c`」 | `?:` 是布尔语义，会丢 `0` / `false`；应为 `??` |

---

## 附录 D：评审采纳记录（2026-09-07，v1.0.1 → v1.1）

外部评审 47 条建议的核对与处置：9 条已被实现/实测推翻（missingPolicy 已实现、空容器实测保留而非丢失、networknt 3.x 无 Jackson 2 传递、数值已 BigDecimal、线程安全已声明、mapper 配置不可变、Batch 每 case 独立引擎、路径转义已统一 wildcardPattern、自定义分隔符校验边界已注明）；5 条不采纳（表达式 IR 编译、Builder 替代构造器、契约测试 Trie、ByteBuddy JIT、FlatMap 容量预计算——均与当前规模/「配置即合同」定位不匹配）。

**v1.1 追加裁定落地**（六模块全绿）：① 异常体系（评审 4.2）——`TransferException` 基类 +
`TransferAssemblyException`（构造期）/ `RuleMatchException`（运行期匹配）/ `TransformException`
（变换链，携带规则索引、from/to、函数名与当前值）/ `ValidationException`（校验失败，携带
`List<ValidationFailure>`）；usecase 侧工厂 catch 同步为 `TransferException`，类型化后可经
usecase `errorMappings` 把数据类错误映射 4xx。② validations 校验段（评审六，A 方案完整实现）——
见 §6.7。③ 标量→数组广播（评审 2.8）维持禁止（实现需打破规则顺序无关性；usecase 场景 SpEL
列表投影已等价）。

### D.1 本轮已修复（构造期/运行期语义固化，46/46 测试）

| 评审项 | 固化语义 |
|---|---|
| 1.1 通配数量 | `from` 与 `to` 的 `[*]` 数量必须一致，引擎构造期 fail-fast（报错含规则索引与路径；Schema 仅约束有无，数量校验归引擎） |
| 2.1 链 null 短路 | 变换链中间结果为 null 时跳过后续函数，仅 `default()` 兜底函数例外（default(null) → 缺省值后链继续）；最终仍 null 按 `nullPolicy` 处置 |
| 1.7 目标键冲突 | 默认「后规则覆盖前规则」；`strictMode: true` 时路径模式完全相同的重复目标在构造期报错（含通配模式与 computed / defaults，索引按规范化比较），模式不同但展开后重叠的目标在运行期写入前报错；目标键结构冲突（祖先 / 数组 vs 对象）无论是否 strict 都在构造期拒绝（v1.3 重做，见 D.6.4） |
| 3.4 strictMode 保留字符 | `strictMode: true` 时首 transfer 前对含 `["` 转义记法的源键 fail-fast（§5.3-2 承诺补齐） |
| 3.5 JEXL 沙箱 | **（v1.3 重做，见 D.6.2）** 原记录有误：`JexlSandbox(true)` 实为 allow-box（未配置的类默认全部允许），那一版配置实际不产生任何限制；当时的拦截效果全部来自 JEXL 3.3 的默认权限 `RESTRICTED`，而它放行 `java.io.*` 文件读写。现行模型：默认拒绝 + 显式放行运行期具体类型 + 只读白名单。`T(...)` 是 SpEL 语法，JEXL 中天然不可达 |

### D.2 设计采纳、列入路线图（评审建议，待实现）

- **1.4 rewrites 时机**：Phase 1（拍平后）与 Phase 2（规则匹配前）之间，按声明顺序串行应用于源 FlatMap 所有键
- **1.6/9.2 多源约束**：各源拍平后以 `alias.` 根前缀合并；规则 `from` 必须以某 alias 开头（否则按 missingPolicy 处置）；装配期 alias 唯一性校验
- **1.3 computed 依赖**：按声明顺序执行（当前实现已隐式支持前向引用——flatTarget 逐步更新），补拓扑循环检测；后向引用以运行期求值失败暴露
- **2.5 日期函数**：默认 UTC + 可选时区参数（**已落地 2026-09-16**，§D.4）
- **2.9 coalesce**：Phase 2 执行，仅引用源 FlatMap 路径（**注**：§6.8 intermediate 落地后，多字段取「首个非 null 值」用 `??`：`a ?? b ?? c`，独立内置函数降为语法糖，按需再实现。**v1.3 勘误**：此处原写作 `a ?: b ?: c`——JEXL 的 `?:` 是布尔语义，`0` / `false` 会被当作缺省替换掉）
- **2.10 类型转换函数族**：`toEnum(className)` / `toIsoDate(fmt)` / `toIsoDateTime(fmt)` / `toBigDecimal`（金额场景替代 toNumber）；不引入独立 ConversionHandler（保持 TransformFunction 单一扩展点，接口文档补类型转换专项示例）。**toIsoDate/toIsoDateTime 已落地 2026-09-16**（§D.4）；`toEnum`/`toBigDecimal` 仍未实现
- **3.1/9.3 数组稀疏**：索引忠实传递（`items[1]` → `lineItems[1]`，空洞由 Unflattener 垫槽位为 null——当前实现即此行为，文档固化）；压缩重排经显式变换函数
- **2.3 otherwise 字面量**：可加 `const(v)` 内置函数实现无条件字面输出（default 仅兜 null 的语义不变）
- **2.4 函数参数编译期校验**：内置函数数值参数装配期正则校验（`multiply("abc")` 提前拦截）
- **4.3/4.4**：dry-run 输出结构标准化（planned_mappings/warnings/estimated_output 入 Schema）；`version` 兼容性策略（1.x 向后兼容）
- **7.1 passthrough**：整棵子树原样搬运（不进拍平管道），大 JSON 少字段场景的逃逸阀
- **2.8 标量→数组广播**：维持第一版禁止（与索引对齐语义正交）；广播需求收集后再放宽 Schema
- **8.1/8.2 可观测性**：审计日志结构化（OpenTelemetry 语义约定）、指标收集时机与去重策略——随指标埋点排期

**v1.3 追加待办**（外部评审二轮「四、其他边界」中未落地的部分）：路径 Trie / 惰性 Flatten（§11.1，实测有需要再做）；输入大小 / 嵌套深度 / 展开数量 / 表达式资源预算（§11.1）；可观测性的值采集与脱敏策略（§11.2）；transform 实参的转义规则与装配期参数校验（§3.2）；引用检查对字符串字面量中 `$ident` 的误报（§6.8）；`BatchAssert` 的 `PARTIAL` 模式参数（§10.7）。

### D.3 代码 review 修复批次（2026-09-07，v1.1 → v1.2，330/330 全绿）

实现代码的两轮 agent review（core：P0×1 + P1×6 + P2×12；test/demo：P1×6 + P2×13）合并处置。

**P0（数据正确性，已修）**：聚合双路径按位置配对——`nullPolicy: skip` 下两操作数索引集合不同（如左 `[0],[2]`、右 `[0],[1]`）时静默错位相乘（实测 100×2 + 7×3 = 221 仍"合法"返回）。改为**索引元组配对**：对展开键取全部 `[*]` 位索引组为 key，双操作数 keySet 不一致即抛 `TransferException`（含两侧索引集合与表达式文本），宁可失败不错数据。

**P1（已修）**：

| 修复 | 语义 |
|---|---|
| 数值断言 null 防卫 | `gt/gte/lt/lte` 遇 null 值判为校验失败（记 ValidationFailure），不再裸抛 NumberFormatException |
| 自定义 separator fail-fast | 非 `.` 分隔符构造期拒绝（computed/when/condition 的表达式按 `.` 拍还原，自定义分隔符会静默失配） |
| 条件视图复用 | 含条件规则时 transfer 全程复用一次 unflatten 嵌套视图（原先 when/condition 每条规则重复还原） |
| `@Validated` 配置校验 | `DataTransferProperties.specs[].location` 加 `@NotBlank`，非法配置启动期 fail-fast |
| 注解自带扩展 | `@TransferSpecTest` 元标注 `@ExtendWith(TransferSpecExtension.class)`——测试类无需显式注册（漏注册 = 断言静默跳过 = 空方法体假绿）；JUnit 6 `@ExtendWith` 的 TYPE target 实证兼容注解类型声明，TestKit 驱动失败样本守护 |
| ignorePaths 口径统一 | `AssertContext`（execute() 后链式断言）构造期即应用 ignorePaths 排除，与 matchesExpected 主流程同口径 |
| 大整数比较 | DiffEngine 数值比较改 BigDecimal `compareTo`（原先 double 中转，> 2^53 大整数舍入漏检）；`looseEquals` 提为包级静态，`pathValueEquals`/`pathValueIn` 复用同一宽松口径 |
| allPathValues 包装 | catch 扩为 `AssertionError \| RuntimeException`——AssertJ/JUnit 断言抛 AssertionError 时包装为含失败值上下文的 AssertionError（保留 cause），不再裸抛 |

**P2（择优修复）**：BatchAssert 空 case 列表 `runAll()` 抛 IllegalStateException（0 case 假绿）+ `registerFunction` 透传；DiffEntry/DiffResult/BatchAssert.TestCase record 化（`List.copyOf` 不可变）；demo 主类 sample 加载 try-with-resources + `readResource` 复用（消同文件双标）+ 资源 null 防卫；data-transfer-test 测试依赖瘦身（spring-boot-starter-test → junit-jupiter + assertj-core + engine-test-kit，不拉 Spring context）；删除 `TransferEngine.getFuncRegistry()` 公开逃逸口。

**裁定两项**：① `TransferException` 体系**暂不加 `CODE` 常量**——库定位按类型 catch 即可（usecase `errorMappings` 按简单类名映射已够），出现对外错误码需求时再引入；② **空数组 × missingPolicy=ERROR 维持现状语义**：`items[*]` 展开为空集 = 规则未命中任何键 = 按 missingPolicy 处置（与"键不存在"同路径），不视为特殊错误——已在此固化，不另行区分"数组存在但为空"。

**P2 未修项留档**（低收益/待规模信号）：TransferSpec 深拷贝防御、FlatMap 预估容量、regex Pattern 缓存化、SpecificationVersion 升 DRAFT_2020_12 显式声明（当前默认方言一致）、diff 输出按路径排序、BatchAssert 支持 PARTIAL mode 参数、demo 可执行 jar shade。

### D.4 日期时间批次（2026-09-16，D.2-2.5 全量 + 2.10 部分落地；2026-09-17 review 修复，369/369 全绿）

**transform 日期函数族**（实现集中于 `DateTimeFunctions`，FuncRegistry 仅薄转发）：

| 函数 | 语义 |
|---|---|
| `dateFormat(fromFmt, toFmt)` | 字符串日期在两种 pattern 间重排（TemporalAccessor 透传）；目标 pattern 引用源值未解析出的字段（源仅日期、目标含 HH:mm）即报错 |
| `epochToIso` / `epochToIso(zone)` | epoch 毫秒 → ISO-8601；无参 `Instant` UTC 形态（变长精度），带参 `OffsetDateTime` 形态；`longValueExact()` 防小数静默截断 |
| `toIsoDate(fmt)` | 按 fmt 解析并强校验「完整日期」，输出 ISO `yyyy-MM-dd` |
| `toIsoDateTime(fmt)` | 按 fmt 解析，输出固定 `yyyy-MM-dd'T'HH:mm:ss`（亚秒截断） |
| `now` / `now(zone)` | 注入 Clock 的当前时间，输出形态同 epochToIso |
| `toIsoDateTime(fmt, zone)`（v1.3） | 按 fmt 解析**带 offset 的时间点**，换算到 zone 后输出该时区墙上时间的无 offset 形态（`yyyy-MM-dd'T'HH:mm:ss`）；源值不带 offset / 时区字段即报错 |

**关键裁定**：

- **解析精度（2026-09-17 review 裁定）**：pattern 解析走 JDK 默认 SMART 模式——非法日值（`31/02/2026` → `2026-02-28`）**静默归一到月末而非报错**（字段完整性缺失仍报错）。值有效性校验不在变换函数职责内；若需严格校验，上游数据入口负责。锚定测试 `toIsoDate_invalidDateNormalizedBySmartResolver`。
- **命名裁定（2026-09-17）**：2.10 提案原名 `toLocalDate(fmt)` / `toLocalDateTime(fmt)` 落地即更名 `toIsoDate` / `toIsoDateTime`——`LocalDate/LocalDateTime` 是 java.time 内部类型名，框架为隐式类型设计（配置值均为字符串），命名应表达**形态转换**（任意格式 → 固定 ISO 形态）而非「到一个不存在的类型」的实现细节；与 `epochToIso` 构成 `*ToIso*` 家族词根（输出 ISO 形态）。
- **Clock 注入**：`FuncRegistry` 实例持 Clock（默认 `Clock.systemUTC()` 行为不变），`now` 由实例构造器注册；`TransferEngine` 新增第 4 参构造器（Clock），现有 3 个委托。否决经 `context` 传递（context 语义是源 FlatMap，塞 Clock 与源字段命名冲突）；否决引擎动态注册（内置表劈两处）。`TransferAssert.withClock(Clock)` 供契约级覆盖真实内置 now（`registerFunction("now", …)` 是同名遮蔽路径，两者语义不同）。
- **输出用 `OffsetDateTime` 而非 `ZonedDateTime`**：后者 toString 追加 `[Asia/Shanghai]` 后缀非纯 ISO offset 形态；且带参路径用 `ISO_OFFSET_DATE_TIME.format()` 而非 `toString()`——toString 省略零秒段（`18:30+08:00`），formatter 的 optional 秒段在 format 时恒输出（`18:30:00+08:00`）。`toIsoDateTime` 同理须显式 pattern（`LocalDateTime.toString()` 省略零秒）。
- **`now` 受 null 短路约束**（评审 2.1 语义不变）：源值 null 时 now 被跳过——须挂在必然存在的源字段上；「无条件时间戳」需求待真实场景出现再议。
- **异常策略**：函数内统一抛 `IllegalArgumentException`（解析失败/字段缺失/缺参/非法时区/小数 epoch），applyChain 包装为 `TransformException`（携带 ruleIndex/from/to 与当前值）——与 `replace` 缺参、`round` 非数值现状同级，不新造异常类型。**函数参数不做构造期校验**（对齐 D.2-2.4 独立路线图项边界；pattern 合法性与 locale 相关）。
- **locale pattern 提示**：`a`（上下午）/`MMM`/`EEE` 等文本 pattern 走平台默认 Locale，建议仅用数值/ISO 类 pattern；`uuuu`/`yyyy` 的 STRICT/SMART 差异照 JDK 默认（SMART）。
- **YAML 时间戳提示**：YAML 有 timestamp 标量隐式类型化，spec/samples 中的日期时间值须加引号（demo 三件套为 JSON 无此问题）。

**validations 日期断言**（与 gt/gte/lt/lte 结构同构的 4 个对称断言）：`dateBefore(<)` / `dateAfter(>)` / `dateNotBefore(≥)` / `dateNotAfter(≤)`。值与参数统一经引擎私有 `parseIsoDateTime`（含 `T` → `LocalDateTime`，否则 `LocalDate.atStartOfDay()`——日期按当日零点参与比较，LocalDate 值与 LocalDateTime 参数可混比；唯一消费者是引擎，不进 `DateTimeFunctions` 公共面）。

- **null 与不可解析值均判失败**（记 ValidationFailure，不裸抛）——比数值断言的非数值裸抛 NFE 更进一步（D.3 P1 只防卫了 null），差异在此固化。
- **带 `'Z'`/offset 的 ISO 字符串不支持**（`LocalDateTime.parse` 天然拒绝 → 判失败）：断言是「目标形态合同」校验，静默折算时区会掩盖上游未归一——须先归一为无时区形态。**归一必须换算而不能只丢弃 offset**（v1.3）：`dateFormat` 与无参 `toIsoDateTime(fmt)` 只重排字段，`2026-01-01T00:30:00+02:00` 会变成 `2026-01-01T00:30:00`，本地时间顺序与真实时刻相反（它其实早于 `2025-12-31T23:00:00Z`）；带 offset 的数据用 `toIsoDateTime(fmt, zone)` 按同一时刻换算。
- **「不生成脏数据」的适用范围**：validations 校验的是**目标值**。日期函数按 JDK 默认 SMART 模式解析，`31/02/2026` 会被归一成 `2026-02-28`；归一发生在校验之前，校验看到的是归一后的合法值，无法发现原始日期非法。需要严格校验原始日期时，在数据入口处完成。
- **构造期参数预校验**（`DATE_ASSERTIONS` 集合 + `validateAssertionSyntax` 试解析，抛 `TransferAssemblyException`）：缺参 / 非 ISO（`'2026/01/15'`、`'abc'`）/ `Z` 后缀均在装配期拦截——与函数参数「运行期才炸」的边界对比见上。

**D.4.1 review 修复批次（2026-09-17，独立 agent review：P0×0 / P1×3 / P2×7，用户裁定处置）**：

- **P1 修复**：① `DateTimeFunctions` 类头重复 Javadoc（编辑残留）；② SMART 静默归一明示（见上「解析精度」裁定 + 锚定测试）；③ IDE 生成文件退库（30 个 `.factorypath`/`.settings` 含 usecase 侧存量，`git rm --cached` + `.gitignore` 补不锚定 `.settings/` 与 `.factorypath`——usecase 侧 JDT prefs 正是「坏 class 污染 target」坑的源头之一）。
- **P2 修复**：`parseIsoDateTime` 改「先 `LocalDateTime.parse` 失败回退 `LocalDate.parse`」（修复 `contains("T")` 启发式对小写 `t` 合法 ISO 串的误拒）；`DateTimeFunctions` 异常消息统一英文（跟随库内基调）；`epochToIso`/`now` 参数个数校验前置到 null 短路之前（`now` 签名统一为 value 首参、null 防卫内聚）；`toIsoDate`/`toIsoDateTime` 抽取 `parseWithPattern`（含 extractor，字段缺失消息不回退为裸 `DateTimeException`）；新增 5 个锚定测试（SMART 归一 / offset-id 时区 `GMT+08` / 多参拒绝 / offset 值判失败 / `withClock`+`registerFunction` 组合遮蔽胜出）。
- **留档未修**（并入 D.3 P2 清单）：`DateTimeFormatter.ofPattern` 每值重编译（热路径，可与 D.3 的 regex Pattern 缓存化合并处理）；dateFormat 的 locale pattern（`a`/`MMM`/`EEE`）行为不测（Javadoc 已提示平台 Locale 依赖）。

### D.5 中间结果批次（2026-09-24，§6.8 落地，387/387 全绿）

**功能**：`intermediate` 暂存区（§6.8）——复杂字段转换的两阶段表达：Phase 1.5 在源上下文顺序
计算中间结果（`$` 命名空间，不进输出），rules 的 from/transform 实参、条件、computed.expr 均
可引用。双形态：`from + transform`（复用变换链）与 `expr`（JEXL 跨字段组合，容器结果自动展开
为扁平键集支持 `[*]` 导航）。

**构造期校验（fail-fast）**：`to` 形态与根唯一性、双形态二选一、from 禁 `[*]`、前向引用约束
（intermediate 自身 + rules.from + transform `$path` 实参 + computed.expr 的 `$` 引用必须有
更早的 intermediate 声明——声明序即 DAG，不做拓扑排序）；运行期源键 `$` 前缀冲突 fail-fast。

**实现踩坑（留档）**：

- `FlatMapProcessor.wildcardPattern` 生成正则时未转义 `$`——`$` 是正则行尾锚，`$path` 模式
  永不匹配。修复为替换链中统一 `replace("$", "\\$")`（连同 `[*]`/`[0]`/`.` 的既有转义教训：
  路径语法每引入一个新元字符，wildcardPattern 必须同步转义并补锚定测试）。
- `mvn test -rf :模块` 恢复构建时被跳过模块取自 `~/.m2` 旧 jar，引发大面积
  「package does not exist」误报——改包名/改 API 后必须全量 reactor 构建。
- YAML 顶层键重复（`VALID + 追加 rules:` 的测试拼接模式）静默后值覆盖，Schema 校验对覆盖后
  文档通过——**v1.3 起由加载链在解析期拒绝**（见 D.6.3），这类配置不再能通过加载；测试构造多段 YAML 仍建议用完整独立文档。

**范围裁定**：computed 间拓扑循环检测仍维持 D.2-1.3 待办（本次仅覆盖 `$` 引用维度的静态检查）；
聚合与算术混合（`sum(...) * $x`）受 §6.2 求值器整体表达式限制，经标量中转表达；usecase 侧
`DataTransferStep#dataflow()` 为 step 级键级血缘（读 payload/写输出），intermediate 属 spec
内部产物，血缘声明无需变更。

### D.6 外部评审二轮处置（2026-11-27，v1.3）

外部评审对本文档 v1.2 提出 8 个 P1、5 个设计风险、7 处正文示例问题与若干边界补充。评审方没有项目源码，本轮逐条对照源码与
运行实证核验：**评审结论全部成立**，其中两条比评审描述的更严重（数值完整性、表达式沙箱），另有 3 个评审未覆盖的缺陷一并修复。
处置前后 `mvn clean test` 均为全绿（403 → 496，新增 93 个测试）。处置之后另做了两轮独立审查（D.6.9）。

#### D.6.1 数值契约

**历史问题**（评审 P1-5，实测根因比「JEXL 字面量是 Double」更深）：

- json-base 的 `toJavaNumber` 把「可经 double 往返」的小数（`0.1`、`8.2`、`113.0`）降为 `Double`；
- 库的 `unflattenAsMap` 经其**默认 mapper** 做 JSON 文本往返，BigDecimal 全部变成 double。

结果是**不做任何变换的恒等映射就丢精度**：`12345678901234567890.123` → `12345678901234567000`、`9007199254740993.5` → `9007199254740994`；
同一份 spec 里 `transform: multiply(3)` 得 `24.6`、`expr: "out.e * 3"` 得 `24.599999999999998`；契约测试工具在比较前把期望与实际同样舍入，所以发现不了。

**现行契约**：

| 环节 | 约定 |
|---|---|
| Flatten 出口 | 小数一律 `BigDecimal`，整数维持 `Integer` / `Long` / `BigInteger` 窄化 |
| 嵌套视图（表达式上下文） | 自建 unflatten，不经 JSON 文本往返；数值保持精确类型，容器为 `LinkedHashMap` / `ArrayList` |
| transform（`multiply` / `round`） | `BigDecimal` 运算；`round` 为 HALF_UP |
| 聚合（`sum` / `avg` / `count` / `min` / `max`） | `BigDecimal`；`avg` 保留 8 位小数（HALF_UP）；双操作数按通配索引元组配对 |
| 表达式算术 | `DecimalArithmetic`：原本会走 double 路径的操作数——无后缀的小数字面量、`Double` 值、**含小数点或指数的字符串**（上游以字符串传金额：`"8.20" * 3` = `24.60`）——先提升为 `BigDecimal`，精度 `DECIMAL128`（34 位有效数字，HALF_EVEN）；`0.1 + 0.2 == 0.3` 成立 |
| 字符串操作数的分类 | 沿用 JEXL 的规则，只把 double 路径换成十进制：含小数点或指数的字符串按小数处理，纯数字字符串（`"10"`）仍按整数处理（`"10" / "4"` = `2` 整除） |
| `+` 与字符串 | 仍是拼接（JEXL 语义，不提升）：`"0.1" + 0.2` = `"0.10.2"`、`"010" + "123"` = `"010123"`；数值字符串做加法先乘 1 转成数值（`"0.1" * 1 + 0.2` = `0.3`） |
| 整数 | 整数之间保持 Java 语义：`10 / 4 == 2`（整除），除零抛错。需要小数结果时让一侧成为小数（`total * 1.0 / count`） |
| 小数结果 | 不窄化为整数（`113.0 * 100 = 11300.0`） |
| 输出形态 | 可经 double 无损往返的值输出 double 形态，与十进制字面量一致（`113.00` → `113.0`、`1.10` → `1.1`、`1e3` → `1000`）；超出 double 精度的值以全精度 `BigDecimal` 输出 |
| 断言（`gt` 等）与契约测试比较 | `BigDecimal` `compareTo`，不经 double |
| BigDecimal / BigInteger 的算术方法 | 白名单里有 `add` / `multiply` 等，但 JEXL 不做参数宽化，实参类型必须相同（`bd.add(other)`）；`bd.add(1)` 不可用——算术请用运算符 |

#### D.6.2 表达式安全模型

spec 里的表达式（`computed.expr`、`intermediate.expr`、`when.condition`、`validations.condition`）**按不可信输入对待**。
威胁模型取决于 spec 的来源：spec 是 classpath 上的部署产物时这不是远程漏洞；一旦 spec 可由非开发人员编写或从外部加载，它就是真实的攻击面。

**历史问题**（评审 P1-1）：`JexlSandbox(true)` 被当作白名单，实际是 **allow-box**——未配置的类默认全部允许，那一版配置不产生任何限制。
当时的拦截全部来自 JEXL 3.3 的默认权限 `RESTRICTED`，而它放行 `java.io.*`：实测经真实 spec 加载链，`computed.expr` 里的
`new('java.io.FileInputStream', …).readLine()` 能读出服务器文件，`FileOutputStream` 能写文件。

**现行模型**（默认的 `JexlExpressionEvaluator`）：

- **默认拒绝**：`JexlSandbox(false)`，只显式放行运行期视图里实际出现的具体类型。JEXL 的权限继承只看直接接口，
  JDK 21 上 `LinkedHashMap` 只直接实现 `SequencedMap`，所以必须按具体类逐个放行（`java.util.LinkedHashMap`、`java.util.ArrayList`）；
- **容器只读**：`LinkedHashMap` / `ArrayList` 的属性与索引读取放行（Map 的键不可穷举，不能做 key 白名单），写与修改方法
  （`put` / `remove` / `clear` / `add` / 赋值）全禁；
- **标量只能调用白名单方法**：String / Integer / Long / Double / BigInteger / BigDecimal / Boolean 的**属性读取关闭**。
  JEXL 的属性访问本身就是方法调用——`s.class` 是 `getClass()`、`s.bytes` 是 `getBytes()`、`n.MAX_VALUE` 是读静态字段；
  关闭后标量只能调用 `JexlExpressionEvaluator.EXECUTE_ALLOWLIST` 里的只读方法（`s.length()`、`bd.setScale(2)` …）；
- **其余类型一律拒绝**；`Object#getClass` 在 JEXL 权限层一并封掉（容器上 `m.class` 只得到 Map 的缺失键语义 `null`），
  从任何值都取不到 `Class` 对象——补上 JEXL 默认权限 `RESTRICTED` 放行 `java.io.*` 文件读写的缺口；
- **表达式只能是表达式**：`new(...)`、lambda、循环、`var` / `let`、多条语句、pragma、注解、赋值等脚本特性在**解析期**关闭，
  写进 spec 即在装配期被拒绝；
- **`safe` 关闭**：白名单外的方法调用、在缺失值上继续解引用直接报错，不再静默返回 null。
  **显式的空值安全写法不受影响**，它们本来就是「不存在也不算错」的语义：`?:` / `??` / 三元条件里的缺键兜底；
  内置函数 `empty(x)` / `size(x)` 对不存在的路径返回 `true` / `0`（也会吞掉路径里的错误）；安全导航 `?.` 返回 `null`；
- **装配期校验**：表达式语法在引擎构造期检查（此前语法错误要到首个请求才暴露）；聚合操作数必须是含 `[*]` 的合法路径。
  运行期求值失败抛 `ExpressionException`（携带表达式与所属声明）。

运算符（算术、比较、字符串拼接）与容器的属性 / 索引访问不经方法白名单。两点边界须知：**迭代与类名解析由 JEXL 自身的
`RESTRICTED` 权限约束，不经项目沙箱**（没有实际越界，但「未放行的类型在每条路径上都拒绝」对这两条路径不严格成立）；
沙箱限制的是**能访问什么**，不限制 CPU / 内存消耗——range 字面量（`0..n`）可以创建，序列化时才展开（§11.1）。
如需开放新的只读方法，改 `EXECUTE_ALLOWLIST` 并补测试；被拒绝的调用在求值期报错，而不是悄悄得到 null。

#### D.6.3 配置加载：只解析一次，重复键与别名即拒绝

评审 P1-3：YAML 重复键（`rules:` 写两遍、规则内 `to:` 重复、两个 `options:` 块）此前被静默后值覆盖——前一份配置丢失，而 Schema 校验照样通过
（JSON Schema 看到的已是解析后的对象）。现行加载链（`TransferSpecValidator.validateAndLoad`）：YAML 文本**只解析一次**得到解析树，
解析期开启 `STRICT_DUPLICATE_DETECTION` 拒绝任意层级的重复键；Schema 校验与 `TransferSpec` 绑定共用同一棵树。
`TransferAssert` 与 usecase 的 `dataTransfer` step 都经这一入口加载。

**YAML 别名同样在解析期被拒绝**（独立审查后的自查发现，此前无人报告）：Jackson 的 YAML 解析器不解析别名——
`*common` 会被读成字面量字符串 `"common"`。下面这段配置此前 Schema 校验**通过**，数据却被静默写到了错误的键下：

```yaml
# ❌ 反例：第二个 to 用了别名，数据会被写到键 "common" 下（现行加载链直接拒绝这种写法）
rules:
  - from: "a"
    to: &common "x.real"     # 规则 #0：目标 x.real ✓
  - from: "b"
    to: *common              # 规则 #1：目标是字面量字符串 "common"，数据被写到顶层键 "common" 下 ✗
```

现行加载链扫描 token 流，遇到别名即报错（指明别名名与所在字段）；合并键 `<<` 的值总是一个别名，因此同样被拒绝
（没有别名检查时它会作为未定义属性被 Schema 拒绝，两条路径都不会静默丢值）。锚点本身（`&name`）无害。
需要复用片段时把值写出来。

#### D.6.4 目标键冲突治理

评审二-10：`strictMode` 此前只在构造期比较字面目标字符串。现行：

- **结构冲突**（同一路径既是值又是容器、同一前缀既是数组又是对象）：把规则 `to` / `computed.to` / `defaults.to` 统一为规范化路径模式
  两两比对，**无论是否 strictMode 都在构造期拒绝**（此前运行期裸抛 `ClassCastException`）；
- **strictMode 下的重复目标**：路径模式完全相同的（含通配模式、含 computed / defaults 与规则之间）构造期拒绝，索引按规范化比较
  （`t.k[01]` 与 `t.k[1]` 是同一目标）；模式不同但展开后重叠的，运行期写入前拒绝；
- computed / defaults 的 `to` 与规则的 `to` 同口径**规范化索引**（`t.k[01]` 与 `t.k[1]` 是同一个键），写入、覆盖检测与校验指向一致；
- 非 strictMode 维持「后规则覆盖前规则」——**仅限同形态的值**。后写入的是容器而该路径已是标量（或相反）属于结构冲突，运行期写入前报 `RuleMatchException`（静态期无法知道 computed 的结果是不是容器，只能在运行期判定）；
- `defaults` 永远不覆盖已存在的键。

#### D.6.5 不可变执行快照

评审二-11：引擎此前保留可变 `TransferSpec` 的引用，构造后再改 spec（加规则、改策略、加未支持特性）能绕过全部装配期校验。
现行：构造期把 spec 深拷贝为不可变的 `CompiledPlan`（嵌套的规则、条件分支、断言同样是不可变 record），运行期只读快照。

#### D.6.6 契约测试工具的假通过路径

- **类型宽松**（评审 P1-7）：`"000123"` 与 `123` 此前判等（`STRICT` 与 `PARTIAL` 都是）。现行为 JSON 类型是契约的一部分；
  数字字符串宽松改为显式开关 `withNumericStringCoercion()`（仅放宽期望侧）；
- **缺失等于 null**（评审 P1-8）：`pathValueEquals("missingField", null)` 此前通过。现单值断言要求路径恰好匹配一个键，不存在即失败；
- **共同盲区**：断言工具此前用自己的默认 mapper 读实际输出，长小数 / 大数在比较前被舍入；现与引擎共用同一 BigDecimal mapper，
  精度丢失能被检出。引擎的自建 unflatten 另有与 json-flattener 的对照测试（`FlatMapProcessorTest`）。

#### D.6.7 其他落地项

| 评审条目 | 处置 |
|---|---|
| P1-2 正文 Schema 与仓库不一致 | 正文 §9.1 改为与仓库文件逐字一致，由 `DesignDocExamplesTest` 守护；文档中的完整 YAML 示例进入同一套 Schema 校验 |
| P1-4 `?:` 丢 `0` / `false` | 文档改用 `??`（§3.2 / D.2-2.9），锚定测试 `nullishCoalescing_keepsZeroAndFalse` |
| P1-6 数值断言遇非数值裸抛、中断 `collect` | 非数值记为一条失败并继续（§6.7） |
| 二-9 通配 `notNull` 漏检缺字段的元素 | 断言路径先枚举元素再逐元素取字段（§6.7） |
| 二-12 null / missing / defaults 语义不统一 | `default` 与 `keep` 区分开，行为表见 §4.2 |
| 二-13 去 offset 不等于时区归一 | 新增 `toIsoDateTime(fmt, zone)`；文档明示「归一必须换算」（D.4） |
| 三（7 条）正文示例无法构建 / 与仓库不符 | §八、§九、§十、§十一 按仓库实现重写；未实现特性逐处标注状态 |
| 四 路径与表达式语法 | EBNF 修正（§3.1）；带 `-` 的键须用方括号访问（§2.1）；`computed.to` / `defaults.to` 禁 `[*]`；实参语法说明（§3.2）；computed 容器结果展开为扁平键（§6.2） |
| 四 性能与可观测性 | 装配期编译、零文本往返 unflatten、正则与 formatter 缓存已实现；Trie、惰性 Flatten、资源预算、值脱敏列入待办（§11.1 / §11.2） |
| 新发现：带索引 / 通配的暂存引用被构造期误拒 | 修复（§6.8） |
| 新发现：表达式语法无装配期预解析、运行期异常未包装 | 修复（§6.2、D.6.2） |

#### D.6.8 行为变更清单（升级到 v1.3 须知）

**语义变更**

1. `nullPolicy: default`：此前与 `keep` 完全相同；现在链后仍为 null 时不写入、交给 `defaults`。
2. `nullPolicy: keep` + `defaults` 同目标：此前 null 被默认值覆盖；现在保留 null。
3. 表达式在缺失值上继续解引用（`a.b.c` 而 `a.b` 缺失）：此前静默得 null，现在报错（`?:` / `??` / 三元条件里的兜底不受影响）。
4. 表达式调用白名单外的方法、访问未放行的类型、`new(...)`：此前可执行（含文件读写），现在运行期报错。
5. 通配断言路径现在枚举元素：元素存在但字段缺失会报失败；以元素容器路径（如 `lines[*]`）配**断言形态**会因取不到叶子值而失败，请改用 `condition` 形态。
6. 数值断言遇非数值：此前抛 `NumberFormatException`，现在记为校验失败。

**此前被接受、现在在加载或构造期失败的配置**

7. 含重复键的 spec（任意层级）。
8. `computed.to` / `defaults.to` 含 `[*]`（此前输出名为 `"*"` 的对象）。
9. 目标键结构冲突（祖先、数组 vs 对象；此前运行期 `ClassCastException`）。
10. 表达式语法错误、断言 `regex` 的 pattern 非法、校验 `path` 非法（此前运行期才失败）。
11. `strictMode` 下：路径模式完全相同的重复目标、computed / defaults 与规则的同目标声明。

**输出变化**

12. 恒等映射的高精度小数不再被舍入；其余数值的输出形态不变（`113.00` 仍输出 `113.0`）。
13. `computed` / `intermediate` 的结果为数组时，展开键紧跟目标路径拼接（暂存 `$x[0].f`、普通目标 `out.list[0]`）；此前是 `$x.[0].f` 这类无法被规则寻址的畸形键。根级空容器不再被展开成 `to.root`。
14. `computed` 的容器结果展开为扁平键（此前直接落为嵌套值，validations 寻址不到其内部字段）。

**契约测试**

15. 依赖「数字字符串与数字相等」的期望会开始失败：改正期望的类型，或显式 `withNumericStringCoercion()`。
16. `pathValueEquals("missing", null)` 这类断言开始失败。

**对 usecase 集成的影响**

17. 新增异常类型 `ExpressionException`（computed / intermediate.expr / condition 的运行期求值失败）：`errorMappings` 如需映射须按新类名配置，未配置时走兜底。

**独立审查后的二次加固（D.6.9）**

18. 标量上的**属性形式**访问被拒（`s.class`、`s.bytes`、`n.MAX_VALUE`、`bd.scale` 不带括号）：只能调用白名单方法（`s.length()`、`bd.scale()`）。
19. 表达式里的 `new(...)`、lambda、`var` / `let`、多条语句、赋值：在装配期（解析期）被拒绝（`new` 此前是运行期被沙箱拦截）。
20. 含小数点或指数的字符串操作数按十进制运算（`"8.20" * 3` = `24.60`，此前是 double 的 `24.599999999999998`）；`+` 与字符串仍是拼接。
21. transform 实参里带引号的 `'$rate'` 是字面量（此前剥引号后被当成暂存引用，上下文里取不到即报错）；未加引号的 `$rate` 仍是引用。
22. 表达式字符串字面量里的 `$ident` 不再被构造期引用检查误报。
23. `computed.to` / `defaults.to` 的索引规范化：`t.k[01]` 与规则的 `t.k[1]` 指向同一个键（此前是两个键，defaults 会覆盖规则已写的值、校验读到旧值）。
24. 变换函数返回的 Java 数组输出为 JSON 数组（`byte[]` 此前导致栈溢出，其他数组报 JSON 解析错误）。
25. 非 strictMode 下，computed 的容器结果与规则写入的标量落在同一路径：运行期报结构冲突（「后覆盖前」只针对同形态的值）。
26. 聚合操作数必须是含 `[*]` 的合法路径，且以标识符或 `$` 暂存根开头（`sum(a..b[*])`、`sum([*])` 装配期拒绝）。
27. 路径语法错误（编程式构建绕过 Schema 时）是装配期异常 `TransferAssemblyException`，不再是裸 `IllegalArgumentException`。
28. YAML 别名（`*anchor`）在加载时被拒绝。此前它被静默读成字面量字符串（锚点名字）——`to: *common` 的目标键成了 `"common"`，Schema 校验通过而数据写错位置。
29. 合并键 `<<` 在加载时被拒绝（此前作为未定义属性被 Schema 拒绝，报错位置不如现在明确）。

**对 usecase 集成的影响**

#### D.6.9 独立审查与二次加固（2026-11-27）

v1.3 落地后另起两轮**只读**独立审查（一轮查正确性与回归，一轮专项尝试绕过表达式沙箱），它们的发现逐条经实测裁定：

| 审查发现 | 实测裁定 | 处置 |
|---|---|---|
| 用「属性形式」能调到白名单外的 getter 与静态字段（`s.class`、`s.bytes`、`n.MAX_VALUE`、`f.TRUE`） | 属实（返回值都是死胡同，未越界） | 标量关闭属性读取；`Object#getClass` 在权限层一并封掉 |
| `new(...)`、lambda 等脚本特性在 JEXL 默认特性集里是开启的 | 属实：`createExpression` 只关掉「脚本」位 | 显式收紧特性集，这些写法在解析期被拒 |
| `size()` / `empty()` 会吞掉路径错误返回 `0` / `true` | 属实 | **保留**：这是显式的空值安全语义（与 `?:` 同类），文档 D.6.2 如实说明 |
| 迭代与类名解析不经项目沙箱 | 属实，无实际越界 | 文档 D.6.2 如实说明 |
| `sum(a[*].x) + 1` 这类混合形态装配期能通过 | **不成立**：JEXL 解析期即拒绝 | 无需处理；聚合操作数另补了路径合法性校验 |
| computed / defaults 的 `to` 未做索引规范化，`t.k[01]` 与 `t.k[1]` 成了两个键 | 属实（defaults 覆盖规则已写的值、校验读到旧值） | 与规则的 `to` 同口径规范化 |
| 变换函数返回 `byte[]` 时 unflatten 无限递归到 `StackOverflowError` | 属实（改动前同样失败，只是异常类型不同） | 数组在 mapper 往返前展开为列表 |
| 含小数点 / 指数的字符串操作数仍走 double 路径（`"0.1" * 3`） | 属实 | 提升为十进制；`+` 的拼接语义不变 |
| 非 strictMode 下 computed 的容器结果无法覆盖同路径的标量 | 属实 | **保持 fail-fast**：这是结构冲突而非「后覆盖前」，文档 D.6.4 澄清 |

同轮顺带修复（非审查发现）：带引号的 `'$rate'` transform 实参被误当暂存引用；构造期引用检查误判字符串字面量里的 `$ident`；
不可变快照对数组形态的 `defaults.value` 未做深拷贝。另查明 `BigDecimal` / `BigInteger` 的算术方法因 JEXL 不做参数宽化
而基本不可用（`bd.add(1)`）——未改行为，在 D.6.1 注明算术请用运算符。

**补查审查方标注「只读未实跑」的区域时另发现两个缺陷**（都已修复并补测试）：

| 发现 | 影响 | 处置 |
|---|---|---|
| YAML 别名被静默读成字面量字符串（D.6.3） | Schema 校验通过，数据写到错误的键下 | 加载链扫描 token 流，遇到别名即拒绝 |
| 装配期表达式错误消息的清洗从未生效（正则多写了一个冒号） | 错误消息里一直带着 `com.example.…JexlExpressionEvaluator.validate:153@1:4 …` 这样的内部调用点前缀；现有测试只断言「包含所属声明与表达式」，没抓到 | 修正正则，补测试断言消息保留表达式位置、不含内部类名 |

同一轮补测并确认无问题的边界：空文件、只有注释或文档分隔符、`null`、标量、数组形式的 spec 都报「校验失败」而不是崩溃；
字符串与注释里的 `*` / `&`、只定义不引用的锚点不会被误判为别名。

两轮审查各自到达轮次上限后交付的都是**部分结果**：正确性审查的前三条实跑复现过、第 4 条标注「仅阅读推断」（后经实测确认）；
沙箱审查全部是源码推断、未运行任何表达式（其结论由本项目逐项实测复核）。
