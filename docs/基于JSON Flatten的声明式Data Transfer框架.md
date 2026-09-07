# 基于 JSON Flatten 的声明式 Data Transfer 框架 — 设计文档

> **状态**：设计稿 v0.3（2026-09-06）——v0.2 完成结构重写与三模块/Jackson 3 基线裁定；v0.3 完成依赖选型收口（json-flattener 0.18.2、json-schema-validator 3.0.6，均已确认原生支持 Jackson 3）
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

代价是拍平会丢失部分结构信息（空容器、容器类型边界），这些固有限制与对策在 §5.3 明确列出，属于框架的设计边界。

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
  nullPolicy: "skip"        # skip | keep | default
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

> 注：`computed` 表达式在映射完成后**对目标 FlatMap 求值**，因此只引用目标路径（见 §6.2）。

---

## 三、映射 DSL 设计

### 3.1 路径表达式语法

```text
PathExpr    := Segment ( "." Segment )*
Segment     := Identifier | ArrayAccess
Identifier  := [a-zA-Z_][a-zA-Z0-9_-]*
ArrayAccess := "[" ( Integer | "*" ) "]"
```

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
| 日期 | `dateFormat(fromFmt, toFmt)`, `epochToIso`, `now` | 时间格式转换 |
| 类型 | `toString`, `toNumber`, `toBoolean` | 类型强转 |
| 逻辑 | `default(val)`, `coalesce(path1, path2, ...)`, `when(cond, then, else)` | 条件与兜底 |
| 聚合 | `sum`, `avg`, `count`, `min`, `max`, `concat(sep)` | 配合通配符使用 |
| 自定义 | `fn:myFunc` | 引用用户注册的扩展函数 |

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

---

## 四、引擎架构

```text
┌─────────────────────────────────────────────────────────────┐
│                       TransferEngine                        │
│                                                             │
│  ┌───────────┐    ┌────────────────┐    ┌────────────────┐  │
│  │ SpecLoader│───►│ RuleParser     │───►│ CompiledPlan   │  │
│  │(YAML/JSON)│    │ & Validator    │    │ (DAG of Ops)   │  │
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
| SpecLoader | 从 YAML/JSON/代码加载 TransferSpec，支持热加载与版本管理 |
| RuleParser | 解析规则，校验路径合法性、函数签名、类型兼容性，生成编译后的执行计划 |
| Flattener | 递归遍历源 JSON，生成 FlatMap，处理数组展开与通配符 |
| TransformPipe | 核心管道，按 CompiledPlan 逐条执行：路径匹配 → 值解析 → 函数执行 → 写入目标 FlatMap |
| Unflattener | 将目标 FlatMap 还原为嵌套 JSON，自动推断数组索引与对象层级 |
| FuncRegistry | 内置 + 用户自定义变换函数的注册中心 |

### 4.2 执行流程（伪代码）

```python
class TransferEngine:
    def __init__(self, spec):
        self.plan = RuleParser.compile(spec)
        self.funcs = FuncRegistry(spec.functions)

    def transfer(self, source: dict) -> dict:
        # Phase 1: Flatten（多源时按 sources 声明分别拍平，以 alias 为根前缀合并，见 §6.4）
        flat_src = Flattener.flatten(source, self.plan.separator)

        # Phase 2: Transform —— 按 CompiledPlan 逐规则执行
        flat_tgt = {}
        for rule in self.plan.rules:
            # 展开通配符，得到所有匹配的 (srcKey, tgtKey) 对；
            # 规则未命中任何源键时按 missingPolicy（warn/error/ignore）处置
            pairs = self._expand_wildcards(rule, flat_src)
            for src_key, tgt_key in pairs:
                value = flat_src.get(src_key)
                if value is None and self.plan.null_policy == "skip":
                    continue
                # 执行变换链："trim | multiply(1.13) | round(2)" 逐段应用
                value = self._apply_transform_chain(value, rule.transform, flat_src)
                flat_tgt[tgt_key] = value

        # 计算字段：在目标 FlatMap 上求值（表达式引用目标路径，见 §6.2）
        for computed in self.plan.computed:
            flat_tgt[computed.to] = self._eval_expr(computed.expr, flat_tgt)

        # 注入默认值（仅当目标键不存在时）
        for default in self.plan.defaults:
            flat_tgt.setdefault(default.to, default.value)

        # Phase 3: Unflatten
        return Unflattener.unflatten(flat_tgt, self.plan.separator)
```

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

1. **空容器丢失**：空对象 `{}` / 空数组 `[]` 拍平后不产生任何键，Unflatten 无法还原。若业务需要保留空容器，需引入哨兵键或容器骨架声明（列入路线图）。
2. **保留字符键**：源键名包含分隔符 `.` 或 `[` 时，json-flattener 默认转义为 `matrix["agent.smith"]` 记法——此类键不属于 §3.1 路径语法、不可被规则匹配（天然无歧义，即"不可映射"，体现在 `unmapped_source_keys` 指标）；`strictMode` 下引擎对其 fail-fast。详见 §8.4 注。
3. **容器类型混用**：同一父路径下索引键与命名键并存（`a[0]` 与 `a.b`）在 Unflatten 时类型冲突，库会抛出异常（fail-fast），Schema 校验与装配期检查亦应拒绝。
4. **数值精度**：`multiply` / `round` 等示意实现基于 double；引擎侧通过 Jackson `USE_BIG_DECIMAL_FOR_FLOATS` + 变换函数的 BigDecimal 实现消除精度隐患（见 §8.4 / §8.5 注）。

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

多级通配符（如 `data[*].tags[*]`）按出现顺序逐段对位：`from` 中第 k 个 `[*]` ↔ 展开键中第 k 个 `[n]` ↔ `to` 中第 k 个 `[*]`。

### 6.2 跨源聚合（computed）

```yaml
computed:
  - to: "summary.totalAmount"
    expr: "sum(crmOrder.lines[*].unitPrice * crmOrder.lines[*].quantity)"
```

引擎在 TransformPipe 完成后，**对目标 FlatMap** 执行聚合表达式求值——因此表达式只引用映射已生成的目标路径，而非源路径。

> 实现要点：表达式中的 `[*]` 需由引擎预处理展开为索引集合（如 `lines[0]`、`lines[1]`），并将展开后的路径以嵌套视图绑定进表达式上下文，再交给表达式引擎（JEXL 等）求值，见 §8.6 注。

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

### 6.5 批量模式（Array Input）

当输入是 JSON 数组时，引擎自动对每个元素执行同一套规则：

```python
def transfer_batch(self, source_list: list) -> list:
    return [self.transfer(item) for item in source_list]
```

### 6.6 Diff & Dry-Run

```python
engine.dry_run(source)
# 返回: { "planned_mappings": [...], "warnings": [...], "estimated_output": {...} }
```

### 6.7 数据值校验（validations，v1.1 已实现）

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
      - assert: "regex('^[\\w.-]+@[\\w.-]+\\.\\w+$')"
        message: "邮箱格式非法"

  # 组合条件（JEXL；path 为元素路径时裸标识符解析为该元素字段；外层 message 必填）
  - path: "crmOrder.lines[*]"
    condition: "unitPrice > 0 && quantity > 0"
    message: "单价和数量必须同时大于0"
```

- **断言谓词**（内置，构造期校验语法与参数格式、未知谓词 fail-fast）：`gt(n)` / `gte(n)` /
  `lt(n)` / `lte(n)` / `eq(v)` / `neq(v)`（数值比较走 BigDecimal）+ `regex('pattern')` +
  `notNull` / `notBlank`；字面键不存在时以 null 值执行断言（`notNull` 场景需要）。
  `fn:` 自定义断言不做——复杂判断走 `condition`（JEXL，表达力等价且已过沙箱加固）。
- **执行模式**（`options.validationMode`）：`fail_fast`（默认，首个失败即抛，入参校验场景）/
  `collect`（收集全部失败后统一抛，数据迁移场景）；`ValidationException#getFailures()` 取
  `List<ValidationFailure{path, rule, message, actualValue}>`。
- **限制**：condition 形态的 path 至多一级 `[*]`（多级元素语义歧义，断言形态无此限制）。

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
- **生产就绪**：内置可观测性埋点、契约测试工具、配置校验能力，满足企业级落地需求。

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

所有转换规则通过 `TransferSpec` 类描述，支持从 YAML/JSON 文件加载，内置校验能力（第一道校验为 JSON Schema，见 §9；Bean Validation 作为加载后的兜底）：

```java
@Data
@Builder
public class TransferSpec {

    @NotBlank
    private String version;

    @NotBlank
    private String name;

    @Valid
    private TransferOptions options = new TransferOptions();

    /** 多源合并声明（§6.4）；未声明时以整个输入报文为单一源 */
    private List<SourceDeclaration> sources;

    @NotEmpty
    private List<MappingRule> rules;

    /** 批量路径改写（§3.3） */
    private List<PathRewrite> rewrites;

    private List<ComputedField> computed = new ArrayList<>();

    private List<DefaultValue> defaults = new ArrayList<>();

    /** 可观测性配置（§11.2） */
    private ObservabilityConfig observability;
}
```

```java
@Data
public class TransferOptions {
    private String separator = ".";
    private String arrayWildcard = "[*]";
    private NullPolicy nullPolicy = NullPolicy.SKIP;
    private MissingPolicy missingPolicy = MissingPolicy.WARN;
    private boolean strictMode = false;
}

@Data
@Builder
public class MappingRule {
    @NotBlank
    private String from;
    @NotBlank
    private String to;
    private String transform;            // "trim | multiply(1.13) | round(2)"
    private List<ConditionMapping> when;
}
```

> 注：类级 `@Valid` 无校验语义（jakarta `@Valid` 仅用于属性级联触发），故不放在类上；如需 Spring 场景的方法级校验用 `@Validated`。`computed` / `defaults` 初始化为空列表，避免引擎侧 NPE。建议 Lombok `@Builder` + `@Singular` 简化代码式构建（§8.7）。

### 8.4 Flatten/Unflatten 模块【core，复用 json-flattener】

复用 `json-flattener` 0.18.2：v0.18.0 起原生支持 Jackson 3（`Jackson3JsonValue` 可直接包装 Jackson 3 的 `JsonNode`，**零字符串往返**；`JsonUnflattener.unflattenAsMap(Map)` 提供 Map 直达还原）。通配符匹配为 DSL 特有能力，由引擎自研：

```java
public final class FlatMapProcessor {

    /** 反序列化开启 BigDecimal，保证金额精度语义（§5.3-4） */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** 解析/序列化统一入口：引擎与断言工具的 readTree 一并复用，保证 BigDecimal 语义贯穿 */
    public JsonMapper mapper() {
        return mapper;
    }

    /** 将嵌套 JSON 树拍平为 {FlatKey: leaf}（对应 §5.1 算法） */
    public Map<String, Object> flatten(JsonNode root, String separator) {
        // Jackson3JsonValue 直接包装 JsonNode，无需序列化往返
        return new JsonFlattener(new Jackson3JsonValue(root))
                .withSeparator(separator.charAt(0))
                .flattenAsMap();
    }

    /** 通配符展开（DSL 特有，自研）：将带 [*] 的路径展开为所有匹配的键值对 */
    public List<Map.Entry<String, Object>> expandWildcard(
            String wildcardPath, Map<String, Object> flatMap) {
        // FlatKey 中除 "." 与 "[*]" 外不含正则元字符（标识符/索引均为受限字符集，见 §3.1），
        // 因此只需转义 "."、将 [*] 替换为 [数字索引]；保留字符键的转义记法（见下注）天然不参与匹配
        String regex = wildcardPath
                .replace(".", "\\.")
                .replace("[*]", "\\[\\d+\\]");
        Pattern pattern = Pattern.compile(regex);
        return flatMap.entrySet().stream()
                .filter(e -> pattern.matcher(e.getKey()).matches())
                .toList();
    }

    /** 将扁平 Map 还原为嵌套 JSON 树（对应 §5.2 算法，容器类型推断由库完成） */
    public JsonNode unflatten(Map<String, Object> flatMap, String separator) {
        Map<String, Object> nested = new JsonUnflattener(flatMap)
                .withSeparator(separator.charAt(0))
                .unflattenAsMap();
        return mapper.valueToTree(nested);
    }
}
```

> **保留字符语义**：源键含 `.` / `[` 时，json-flattener 默认转义为 `matrix["agent.smith"]` 记法。此类键不属于 §3.1 路径语法，无法被规则或通配符匹配（天然无歧义），实际效果即"不可映射"，会体现在 `unmapped_source_keys` 观测指标中；`strictMode` 开启时引擎在首次 transfer 前对含转义记法的键直接 fail-fast（对应 §5.3-2）。库的 `ignoreReservedCharacters()`（忽略转义直接拼接）会产生歧义路径，不作为引擎暴露的配置。
>
> **数值精度**：`FlatMapProcessor` 持有配置了 `USE_BIG_DECIMAL_FOR_FLOATS` 的 mapper 并经 `mapper()` 暴露——引擎与断言工具的 `readTree` 与 flatten/unflatten 统一走该实例（§8.6），浮点数全程保留为 `BigDecimal`；变换函数侧的算术亦按 BigDecimal 实现（§8.5 注）。
>
> **API 注记**：`new JsonFlattener(new Jackson3JsonValue(root))`、实例链式 `flattenAsMap()` / `unflattenAsMap()`、`new JsonUnflattener(Map)` 构造等用法以 0.18.x javadoc 为准——官方 README 已证实 `Jackson3JsonValue` 包装 `JsonNode`、静态 `flattenAsMap(JsonValueBase)` 与静态 `unflattenAsMap(Map)`，其余实例/构造器变体为设计示意，落地时按实际 API 微调。
>
> `PathParser.parse("user.tags[0]", ".")` → `["user", "tags", 0]`，用于通配符多级对位与路径校验，实现与 §5.2 `parse_path` 一致。

### 8.5 变换函数模块【core】

定义标准扩展接口，内置常用函数（不可变基表 + 实例注册表，避免全局可变静态）：

```java
/** 变换函数扩展接口 */
public interface TransformFunction {
    String name();
    Object apply(Object value, List<String> args, Map<String, Object> context);
}

/** 函数注册中心：内置默认表 + 实例级扩展注册 */
public final class FuncRegistry {

    private static final Map<String, TransformFunction> BUILTINS = Map.of(
            "trim",    (v, args, ctx) -> String.valueOf(v).trim(),
            "lower",   (v, args, ctx) -> String.valueOf(v).toLowerCase(),
            "upper",   (v, args, ctx) -> String.valueOf(v).toUpperCase(),
            "multiply",(v, args, ctx) -> ((Number) v).doubleValue()
                                        * Double.parseDouble(args.get(0)),
            "round",   (v, args, ctx) -> { /* 按精度四舍五入，示意从略 */ return v; },
            "default", (v, args, ctx) -> v == null ? args.get(0) : v);

    private final Map<String, TransformFunction> functions =
            new ConcurrentHashMap<>(BUILTINS);

    public void register(String name, TransformFunction func) {
        functions.put(name, func);
    }

    public TransformFunction get(String name) {
        TransformFunction f = functions.get(name);
        if (f == null) {
            throw new IllegalArgumentException("Unknown transform function: " + name);
        }
        return f;
    }
}
```

> 注：`multiply` / `round` 的示意实现基于 double；生产实现应对 `Number` 参数统一走 `BigDecimal`（金额场景的精度与舍入模式必须显式化），见 §5.3-4。

### 8.6 规则执行引擎【core】

核心转换逻辑实现，处理通配符对齐、变换链执行、计算字段求值：

```java
public class TransferEngine {

    private final TransferSpec spec;
    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final FuncRegistry funcRegistry;
    private final ExpressionEvaluator exprEvaluator;

    public TransferEngine(TransferSpec spec) {
        this(spec, Map.of(), new JexlExpressionEvaluator());
    }

    public TransferEngine(TransferSpec spec, Map<String, TransformFunction> extraFunctions) {
        this(spec, extraFunctions, new JexlExpressionEvaluator());
    }

    public TransferEngine(TransferSpec spec,
                          Map<String, TransformFunction> extraFunctions,
                          ExpressionEvaluator exprEvaluator) {
        this.spec = spec;
        this.exprEvaluator = exprEvaluator;
        this.funcRegistry = new FuncRegistry();
        extraFunctions.forEach(funcRegistry::register);
    }

    public FuncRegistry getFuncRegistry() {
        return funcRegistry;
    }

    public JsonNode transfer(String sourceJson) {
        // 1. 源数据拍平（readTree 复用 flatProcessor 的 BigDecimal mapper，精度语义贯穿）
        Map<String, Object> flatSrc = flatProcessor.flatten(
                flatProcessor.mapper().readTree(sourceJson),
                spec.getOptions().getSeparator());
        Map<String, Object> flatTarget = new LinkedHashMap<>();

        // 2. 执行映射规则
        for (MappingRule rule : spec.getRules()) {
            List<Map.Entry<String, Object>> srcMatches =
                    flatProcessor.expandWildcard(rule.getFrom(), flatSrc);

            for (Map.Entry<String, Object> srcEntry : srcMatches) {
                // 多级 [*] 时按出现顺序逐段对位替换（§6.1），实现从略
                String targetPath = alignWildcards(rule.getTo(), srcEntry.getKey(), rule.getFrom());

                Object value = srcEntry.getValue();
                if (value == null
                        && spec.getOptions().getNullPolicy() == NullPolicy.SKIP) {
                    continue;
                }
                if (StringUtils.isNotBlank(rule.getTransform())) {
                    value = applyTransformChain(value, rule.getTransform(), flatSrc);
                }
                flatTarget.put(targetPath, value);
            }
        }

        // 3. 执行计算字段（在目标 FlatMap 上求值，见 §6.2 注）
        for (ComputedField computed : spec.getComputed()) {
            flatTarget.put(computed.getTo(),
                    exprEvaluator.evaluate(computed.getExpr(), flatTarget));
        }

        // 4. 注入默认值
        for (DefaultValue defaultVal : spec.getDefaults()) {
            flatTarget.putIfAbsent(defaultVal.getTo(), defaultVal.getValue());
        }

        // 5. 还原为嵌套 JSON
        return flatProcessor.unflatten(flatTarget, spec.getOptions().getSeparator());
    }

    // applyTransformChain：按 "|" 切分变换链，逐段解析 "fn(args)" 并调用
    // funcRegistry.get(fn).apply(value, args, flatSrc)，解析细节从略
}
```

> 注（表达式求值上下文）：JEXL 无法直接导航 `crmOrder.lines[*].unitPrice` 这类含 `.` 与 `[*]` 的整串键。求值前引擎须将表达式中的 `[*]` 展开为索引集合（`lines[0]`、`lines[1]`...），并把展开后的路径以**嵌套视图**（unflatten 后的树）绑定进表达式上下文，聚合函数（`sum` 等）注册为 JEXL 自定义函数。详见 §8.8。
>
> 注（可观测性）：`TransferEngine` 另提供接收 `MeterRegistry` 的重载构造器，见 §11.2。

### 8.7 API 使用方式

**配置式使用（推荐）**——从 YAML 配置文件加载规则，适合运维/业务人员操作：

```java
JsonMapper yamlMapper = JsonMapper.builder(new YAMLFactory()).build();
TransferSpec spec = yamlMapper.readValue(new File("order-transfer.yaml"), TransferSpec.class);

TransferEngine engine = new TransferEngine(spec);

String sourceJson = "{\"orderId\":\"ORD-001\",\"items\":[{\"price\":100,\"qty\":2}]}";
JsonNode result = engine.transfer(sourceJson);
```

**代码式使用**——适合需要动态生成规则的场景（建议 Lombok `@Builder` + `@Singular` 支撑）：

```java
TransferSpec spec = TransferSpec.builder()
        .version("1.0")
        .name("动态映射")
        .rule(MappingRule.builder()
                .from("user.name")
                .to("customer.fullName")
                .transform("trim | upper")
                .build())
        .defaultValue("currency", "CNY")
        .build();

TransferEngine engine = new TransferEngine(spec);
JsonNode result = engine.transfer(sourceJson);
```

### 8.8 扩展能力

**自定义变换函数**——实现 `TransformFunction` 接口并注册：

```java
public class MaskPhoneFunction implements TransformFunction {
    @Override
    public String name() { return "maskPhone"; }

    @Override
    public Object apply(Object value, List<String> args, Map<String, Object> context) {
        String phone = (String) value;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}

// 注册函数
engine.getFuncRegistry().register("maskPhone", new MaskPhoneFunction());
// 配置中使用：transform: "maskPhone"
```

**自定义表达式引擎**——默认使用轻量级 JEXL 作为表达式求值器，可替换为 Spring EL、Aviator 等：

```java
public interface ExpressionEvaluator {
    Object evaluate(String expr, Map<String, Object> context);
}

// 替换为自定义实现
TransferEngine engine = new TransferEngine(spec, Map.of(), new AviatorExpressionEvaluator());
```

### 8.9 Spring Boot 4 自动配置【core，spring/ 包】

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
        // 1. 加载配置（Jackson 3 YAML）
        JsonMapper yamlMapper = JsonMapper.builder(new YAMLFactory()).build();
        TransferSpec spec = yamlMapper.readValue(
                OrderTransferDemo.class.getResourceAsStream("/specs/order-transfer.yaml"),
                TransferSpec.class);

        // 2. 初始化引擎
        TransferEngine engine = new TransferEngine(spec);

        // 3. 执行转换
        String sourceJson = """
                {
                  "orderId": "ORD-20260906-001",
                  "customer": {
                    "name": " Zhang San ",
                    "email": "ZHANG@EXAMPLE.COM",
                    "addresses": [{"city": "Shanghai"}, {"city": "Beijing"}]
                  },
                  "items": [
                    {"sku": "A001", "price": 100, "qty": 2},
                    {"sku": "B002", "price": 50, "qty": 3}
                  ]
                }
                """;

        JsonNode result = engine.transfer(sourceJson);
        System.out.println(JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(result));
    }
}
```

---

## 九、TransferSpec JSON Schema 校验

### 9.1 JSON Schema 定义

> 注意：Draft 2020-12 的关键字为 `$schema` / `$id` / `$ref` / `$defs`（带 `$` 前缀）；JSON 字符串中的正则反斜杠必须双写（`^\d+` → `^\\d+`），否则 Schema 本身不是合法 JSON。`PathExpression` 正则与 §3.1 语法的 Identifier 定义保持一致（允许 `-`）。自定义分隔符场景下路径正则需按 separator 参数化，此处以默认 `.` 为校验基线。

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
    "computed": {
      "type": "array",
      "items": { "$ref": "#/$defs/ComputedField" }
    },
    "defaults": {
      "type": "array",
      "items": { "$ref": "#/$defs/DefaultValue" }
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
        "nullPolicy": {
          "type": "string", "enum": ["skip", "keep", "default"], "default": "skip"
        },
        "missingPolicy": {
          "type": "string", "enum": ["warn", "error", "ignore"], "default": "warn"
        },
        "strictMode": {
          "type": "boolean", "default": false
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
        "from": { "$ref": "#/$defs/PathExpression" },
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
        "to": { "$ref": "#/$defs/PathExpression" },
        "expr": { "type": "string", "minLength": 1 }
      }
    },
    "DefaultValue": {
      "type": "object",
      "required": ["to", "value"],
      "additionalProperties": false,
      "properties": {
        "to": { "$ref": "#/$defs/PathExpression" },
        "value": { "type": ["string", "number", "boolean", "null"] }
      }
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

**通配符对齐校验**——通过 `allOf + if/then/else` 实现：当 `from` 包含 `[*]` 时，`to` 也必须包含 `[*]`，反之亦然。

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
| `observability.metrics[]` | `rule_execution_count`、`transform_latency_ms`、`flatten_time_ms`、`unflatten_time_ms`、`unmapped_source_keys` |

### 9.3 Java 校验器实现【core，validation/ 包】

依赖引入——networknt 采用**双发布线**，选用 3.x 线（Java 17+ / **Jackson 3**）；2.x 线对应 Jackson 2，与本基线不兼容，勿混用。注意 3.x 相对 1.5.x 是大版本重构，API 全面更新（`SchemaRegistry` / `Schema` / `List<Error>`），并**原生支持 JSON/YAML 输入**（schema 与数据均可）——`validateAndLoad` 可直接以 YAML 文本校验，省一次解析往返。以下代码为设计示意：README 已证实 `getSchema(String, InputFormat)` 与三参 `validate(String, InputFormat, Consumer<ExecutionContext>)` 形态，`withDefaultDialect` 单参重载、`validate` 双参重载等以 3.0.x javadoc 为准，落地时按实际 API 微调：

```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>3.0.6</version>
</dependency>
```

校验器封装：

```java
public class TransferSpecValidator {

    private static final String SCHEMA_RESOURCE = "/schemas/transfer-spec.json";

    /** networknt 3.x：Schema 实例线程安全，应缓存复用 */
    private final Schema schema;
    private final JsonMapper yamlMapper = JsonMapper.builder(new YAMLFactory()).build();

    public TransferSpecValidator() {
        String schemaData = readResource(SCHEMA_RESOURCE);
        // Schema 数据自带 $schema 声明时以其声明的方言为准；未声明时用默认方言
        SchemaRegistry registry = SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.schema = registry.getSchema(schemaData, InputFormat.JSON);
    }

    private static String readResource(String resource) {
        try (InputStream is = TransferSpecValidator.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("Schema resource not found: " + resource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load TransferSpec schema", e);
        }
    }

    /** 校验 YAML 配置文件 */
    public ValidationResult validate(Path yamlFile) throws IOException {
        return validate(Files.readString(yamlFile, StandardCharsets.UTF_8));
    }

    /** 校验 YAML 字符串内容（3.x 原生支持 YAML 输入；Jackson 3 异常为非受检） */
    public ValidationResult validate(String yamlContent) {
        List<Error> errors = schema.validate(yamlContent, InputFormat.YAML);
        return new ValidationResult(errors);
    }

    /** 校验并直接加载为 TransferSpec，校验失败则抛异常 */
    public TransferSpec validateAndLoad(InputStream in, String source) throws IOException {
        String yamlContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        ValidationResult result = validate(yamlContent);
        if (!result.isValid()) {
            throw new SpecValidationException(
                    "TransferSpec validation failed (" + source + "):\n"
                            + String.join("\n", result.getErrorMessages()),
                    result.getErrors());
        }
        return yamlMapper.readValue(yamlContent, TransferSpec.class);
    }

    @Data
    public static class ValidationResult {
        private final List<Error> errors;

        public boolean isValid() {
            return errors == null || errors.isEmpty();
        }

        public List<String> getErrorMessages() {
            if (errors == null) {
                return Collections.emptyList();
            }
            return errors.stream()
                    .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                    .collect(Collectors.toList());
        }
    }

    public static class SpecValidationException extends RuntimeException {
        private final List<Error> validationErrors;

        public SpecValidationException(String message, List<Error> errors) {
            super(message);
            this.validationErrors = errors;
        }

        public List<Error> getValidationErrors() {
            return validationErrors;
        }
    }
}
```

使用方式：

```java
TransferSpecValidator validator = new TransferSpecValidator();

// 方式一：仅校验，获取详细错误信息
TransferSpecValidator.ValidationResult result =
        validator.validate(Path.of("order-transfer.yaml"));
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

> 注：Jackson 3 默认忽略未知属性，因此"拒绝未知键"由 Schema 校验（`additionalProperties: false`）承担；若不走 Schema 加载路径，需在 mapper 上显式开启 `FAIL_ON_UNKNOWN_PROPERTIES` 作为兜底（与本项目 Spec 配置加载实践一致）。

---

## 十、TransferAssert 契约测试工具【data-transfer-test】

### 10.1 覆盖场景

| 场景 | 说明 |
|---|---|
| 精确匹配 | 输出与期望完全一致（含字段数量） |
| 部分匹配 | 输出包含期望中的所有字段，允许多余字段 |
| 路径存在性 | 仅校验指定路径是否存在于输出中 |
| 路径值断言 | 校验指定路径的值是否满足条件 |
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
```

### 10.3 TransferAssert 主类

```java
public class TransferAssert {

    private final TransferSpec spec;
    private String fixtureJson;
    private final List<String> ignorePaths = new ArrayList<>();
    private final Map<String, TransformFunction> customFunctions = new HashMap<>();
    private CompareMode compareMode = CompareMode.STRICT;

    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public enum CompareMode {
        /** 严格模式：输出必须与期望完全一致，不允许多余字段 */
        STRICT,
        /** 宽松模式：输出包含期望中的所有字段即可，允许多余字段 */
        PARTIAL
    }

    // ===== 静态工厂方法 =====

    public static TransferAssert assertThat(String specResource) {
        TransferSpec spec = loadSpecFromResource(specResource);
        return new TransferAssert(spec);
    }

    public static TransferAssert assertThat(TransferSpec spec) {
        return new TransferAssert(spec);
    }

    public static BatchAssert batchAssert(String specResource) {
        return new BatchAssert(loadSpecFromResource(specResource));
    }

    private TransferAssert(TransferSpec spec) {
        this.spec = Objects.requireNonNull(spec, "TransferSpec must not be null");
    }

    // ===== 配置方法 =====

    public TransferAssert withFixture(String fixtureResource) {
        this.fixtureJson = loadResourceAsString(fixtureResource);
        return this;
    }

    public TransferAssert withFixtureJson(String json) {
        this.fixtureJson = json;
        return this;
    }

    public TransferAssert ignorePaths(String... paths) {
        this.ignorePaths.addAll(Arrays.asList(paths));
        return this;
    }

    public TransferAssert registerFunction(String name, TransformFunction function) {
        this.customFunctions.put(name, function);
        return this;
    }

    public TransferAssert withCompareMode(CompareMode mode) {
        this.compareMode = mode;
        return this;
    }

    // ===== 断言方法 =====

    public void matchesExpected(String expectedResource) {
        this.compareMode = CompareMode.STRICT;
        doAssert(loadResourceAsString(expectedResource));
    }

    public void matchesExpectedJson(String expectedJson) {
        this.compareMode = CompareMode.STRICT;
        doAssert(expectedJson);
    }

    public void partiallyMatches(String expectedResource) {
        this.compareMode = CompareMode.PARTIAL;
        doAssert(loadResourceAsString(expectedResource));
    }

    public void partiallyMatchesJson(String expectedJson) {
        this.compareMode = CompareMode.PARTIAL;
        doAssert(expectedJson);
    }

    public AssertContext execute() {
        Objects.requireNonNull(fixtureJson, "Fixture must be set before execute()");
        JsonNode result = buildEngine().transfer(fixtureJson);
        return new AssertContext(result, ignorePaths);
    }

    // ===== 内部方法 =====

    private void doAssert(String expectedJson) {
        Objects.requireNonNull(fixtureJson, "Fixture must be set before assertion");

        JsonNode actualResult = buildEngine().transfer(fixtureJson);
        String actualJson = mapper.writeValueAsString(actualResult);

        // 断言工具复用引擎的同一拍平实现，保证键格式完全一致
        Map<String, Object> expectedFlat =
                flatProcessor.flatten(mapper.readTree(expectedJson), ".");
        Map<String, Object> actualFlat =
                flatProcessor.flatten(mapper.readTree(actualJson), ".");

        removeIgnoredPaths(expectedFlat);
        removeIgnoredPaths(actualFlat);

        DiffResult diff = DiffEngine.diff(expectedFlat, actualFlat, compareMode);

        if (!diff.isEmpty()) {
            throw new AssertionError(buildFailureMessage(diff, actualJson));
        }
    }

    private TransferEngine buildEngine() {
        return new TransferEngine(spec, customFunctions);
    }

    private void removeIgnoredPaths(Map<String, Object> flatMap) {
        for (String ignorePath : ignorePaths) {
            Pattern pattern = jsonPathToRegex(ignorePath);
            flatMap.keySet().removeIf(key -> pattern.matcher(key).matches());
        }
    }

    private String buildFailureMessage(DiffResult diff, String actualJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════╗\n");
        sb.append("║           TransferSpec 契约测试失败                   ║\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");
        sb.append("║ Spec: ").append(spec.getName()).append("\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");
        sb.append("║ 差异明细：\n");

        for (DiffEntry entry : diff.getEntries()) {
            sb.append("║\n");
            sb.append("║ 路径: ").append(entry.getPath()).append("\n");
            sb.append("║ 类型: ").append(entry.getType()).append("\n");
            sb.append("║ 期望: ").append(entry.getExpected()).append("\n");
            sb.append("║ 实际: ").append(entry.getActual()).append("\n");
        }

        sb.append("║\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");
        sb.append("║ 实际输出：\n");
        sb.append(actualJson).append("\n");
        sb.append("╚══════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }

    // ===== 资源加载工具 =====

    private static TransferSpec loadSpecFromResource(String resource) {
        try (InputStream is = getResourceStream(resource)) {
            JsonMapper m = resource.endsWith(".yaml") || resource.endsWith(".yml")
                    ? JsonMapper.builder(new YAMLFactory()).build()
                    : JsonMapper.builder().build();
            return m.readValue(is, TransferSpec.class);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to load TransferSpec from: " + resource, e);
        }
    }

    private static String loadResourceAsString(String resource) {
        try (InputStream is = getResourceStream(resource)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load resource: " + resource, e);
        }
    }

    private static InputStream getResourceStream(String resource) {
        InputStream is = TransferAssert.class.getClassLoader()
                .getResourceAsStream(resource);
        if (is == null) {
            try {
                is = new FileInputStream(resource);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException(
                        "Resource not found in classpath or filesystem: " + resource);
            }
        }
        return is;
    }

    /**
     * 断言路径 → 匹配正则。
     * 统一路径风格：剥离可选的 JSONPath 根前缀 "$."；
     * FlatKey 中除 "." 与 "[*]" 外不含正则元字符（标识符/索引均为受限字符集）。
     */
    static Pattern jsonPathToRegex(String path) {
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        String regex = normalized
                .replace(".", "\\.")
                .replace("[*]", "\\[\\d+\\]");
        return Pattern.compile(regex);
    }
}
```

### 10.4 AssertContext — 链式路径断言

```java
public class AssertContext {

    private final Map<String, Object> actualFlatMap;
    private final JsonNode actualNested;
    private final List<String> ignorePaths;

    private final FlatMapProcessor flatProcessor = new FlatMapProcessor();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AssertContext(JsonNode actualResult, List<String> ignorePaths) {
        this.actualNested = actualResult;
        this.actualFlatMap = flatProcessor.flatten(actualResult, ".");
        this.ignorePaths = ignorePaths;
    }

    /** 断言指定路径存在（[*] 通配） */
    public AssertContext pathExists(String jsonPath) {
        Pattern pattern = TransferAssert.jsonPathToRegex(jsonPath);
        boolean found = actualFlatMap.keySet().stream()
                .anyMatch(key -> pattern.matcher(key).matches());
        if (!found) {
            fail("路径不存在: " + jsonPath,
                    "期望路径存在", "未找到匹配路径",
                    "实际所有路径: " + actualFlatMap.keySet());
        }
        return this;
    }

    /** 断言指定路径不存在 */
    public AssertContext pathNotExists(String jsonPath) {
        Pattern pattern = TransferAssert.jsonPathToRegex(jsonPath);
        boolean found = actualFlatMap.keySet().stream()
                .anyMatch(key -> pattern.matcher(key).matches());
        if (found) {
            fail("路径不应存在: " + jsonPath,
                    "期望路径不存在", "但路径存在", null);
        }
        return this;
    }

    /** 断言指定路径的值等于期望值 */
    public AssertContext pathValueEquals(String jsonPath, Object expected) {
        Object actual = resolveSingleValue(jsonPath);
        if (!Objects.equals(expected, actual)) {
            fail(jsonPath, "等于 " + expected, "实际为 " + actual, null);
        }
        return this;
    }

    /** 断言指定路径的值匹配正则表达式 */
    public AssertContext pathValueMatches(String jsonPath, String regex) {
        Object actual = resolveSingleValue(jsonPath);
        if (actual == null || !Pattern.matches(regex, String.valueOf(actual))) {
            fail(jsonPath, "匹配正则 " + regex, "实际为 " + actual, null);
        }
        return this;
    }

    /** 断言指定路径的值为数值且大于指定值 */
    public AssertContext pathValueGreaterThan(String jsonPath, double threshold) {
        Object actual = resolveSingleValue(jsonPath);
        if (!(actual instanceof Number) || ((Number) actual).doubleValue() <= threshold) {
            fail(jsonPath, "大于 " + threshold, "实际为 " + actual, null);
        }
        return this;
    }

    /** 断言指定路径的值为数值且小于指定值 */
    public AssertContext pathValueLessThan(String jsonPath, double threshold) {
        Object actual = resolveSingleValue(jsonPath);
        if (!(actual instanceof Number) || ((Number) actual).doubleValue() >= threshold) {
            fail(jsonPath, "小于 " + threshold, "实际为 " + actual, null);
        }
        return this;
    }

    /** 断言指定路径的值包含指定子串 */
    public AssertContext pathValueContains(String jsonPath, String substring) {
        Object actual = resolveSingleValue(jsonPath);
        if (actual == null || !String.valueOf(actual).contains(substring)) {
            fail(jsonPath, "包含 \"" + substring + "\"", "实际为 " + actual, null);
        }
        return this;
    }

    /** 断言指定路径的值在指定集合中 */
    public AssertContext pathValueIn(String jsonPath, Object... candidates) {
        Object actual = resolveSingleValue(jsonPath);
        Set<Object> candidateSet = new HashSet<>(Arrays.asList(candidates));
        if (!candidateSet.contains(actual)) {
            fail(jsonPath, "在集合 " + candidateSet + " 中", "实际为 " + actual, null);
        }
        return this;
    }

    /** 断言指定路径的值不为 null */
    public AssertContext pathValueNotNull(String jsonPath) {
        Object actual = resolveSingleValue(jsonPath);
        if (actual == null) {
            fail(jsonPath, "不为 null", "实际为 null", null);
        }
        return this;
    }

    /** 断言匹配指定路径的所有值都满足断言 */
    public AssertContext allPathValues(String jsonPath, Consumer<Object> valueAssert) {
        Pattern pattern = TransferAssert.jsonPathToRegex(jsonPath);
        List<Map.Entry<String, Object>> matched = actualFlatMap.entrySet().stream()
                .filter(e -> pattern.matcher(e.getKey()).matches())
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            fail(jsonPath, "至少有一个匹配值", "无匹配路径", null);
        }

        for (Map.Entry<String, Object> entry : matched) {
            try {
                valueAssert.accept(entry.getValue());
            } catch (Throwable t) {
                fail(entry.getKey(), "满足断言条件",
                        "值 " + entry.getValue() + " 不满足: " + t.getMessage(), null);
            }
        }
        return this;
    }

    /** 获取原始输出结果 */
    public JsonNode getActualResult() {
        return actualNested;
    }

    /** 获取拍平后的结果 */
    public Map<String, Object> getActualFlatMap() {
        return Collections.unmodifiableMap(actualFlatMap);
    }

    // ===== 内部方法 =====

    private Object resolveSingleValue(String jsonPath) {
        Pattern pattern = TransferAssert.jsonPathToRegex(jsonPath);
        List<Map.Entry<String, Object>> matched = actualFlatMap.entrySet().stream()
                .filter(e -> pattern.matcher(e.getKey()).matches())
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return null;
        }
        if (matched.size() > 1) {
            throw new AssertionError("路径 " + jsonPath + " 匹配到多个值，请使用 allPathValues()：\n"
                    + matched.stream()
                        .map(e -> "  " + e.getKey() + " = " + e.getValue())
                        .collect(Collectors.joining("\n")));
        }
        return matched.get(0).getValue();
    }

    private void fail(String path, String expected, String actual, Object extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════╗\n");
        sb.append("║           TransferAssert 路径断言失败                ║\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");
        sb.append("║ 路径: ").append(path).append("\n");
        sb.append("║ 期望: ").append(expected).append("\n");
        sb.append("║ 实际: ").append(actual).append("\n");
        if (extra != null) {
            sb.append("║ 详情: ").append(extra).append("\n");
        }
        sb.append("╚══════════════════════════════════════════════════════╝\n");
        throw new AssertionError(sb.toString());
    }
}
```

### 10.5 DiffEngine — 结构化差异比对

```java
public class DiffEngine {

    public static DiffResult diff(Map<String, Object> expected,
                                  Map<String, Object> actual,
                                  TransferAssert.CompareMode mode) {
        List<DiffEntry> entries = new ArrayList<>();

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String path = entry.getKey();
            Object expectedValue = entry.getValue();

            if (!actual.containsKey(path)) {
                entries.add(DiffEntry.missing(path, expectedValue));
            } else {
                Object actualValue = actual.get(path);
                if (!valuesEqual(expectedValue, actualValue)) {
                    entries.add(DiffEntry.mismatch(path, expectedValue, actualValue));
                }
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

    /**
     * 数值宽松比较：113 与 113.00 等价（YAML 期望文件常把数字读成字符串，
     * 故对 String↔Number 做单向宽松化，仅放宽期望侧）。
     */
    private static boolean valuesEqual(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number exp && actual instanceof Number act) {
            return Double.compare(exp.doubleValue(), act.doubleValue()) == 0;
        }
        if (expected instanceof String str && actual instanceof Number act) {
            try {
                return Double.parseDouble(str) == act.doubleValue();
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
```

### 10.6 DiffResult & DiffEntry

```java
@Data
public class DiffResult {
    private final List<DiffEntry> entries;

    public boolean isEmpty() {
        return entries == null || entries.isEmpty();
    }

    public int size() {
        return entries == null ? 0 : entries.size();
    }

    public Map<DiffType, Long> summary() {
        return entries.stream()
                .collect(Collectors.groupingBy(DiffEntry::getType, Collectors.counting()));
    }
}

@Data
@AllArgsConstructor(staticName = "of")
public class DiffEntry {
    private final String path;
    private final DiffType type;
    private final Object expected;
    private final Object actual;

    public static DiffEntry missing(String path, Object expected) {
        return of(path, DiffType.MISSING, expected, null);
    }

    public static DiffEntry mismatch(String path, Object expected, Object actual) {
        return of(path, DiffType.MISMATCH, expected, actual);
    }

    public static DiffEntry unexpected(String path, Object actual) {
        return of(path, DiffType.UNEXPECTED, null, actual);
    }
}

public enum DiffType {
    MISSING("缺失"),
    MISMATCH("不匹配"),
    UNEXPECTED("多余");

    private final String label;

    DiffType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
```

### 10.7 BatchAssert — 批量样本测试

```java
public class BatchAssert {

    private final TransferSpec spec;
    private final List<TestCase> cases = new ArrayList<>();
    private final List<String> ignorePaths = new ArrayList<>();

    public BatchAssert(TransferSpec spec) {
        this.spec = spec;
    }

    public BatchAssert addCase(String fixtureResource, String expectedResource) {
        cases.add(new TestCase(fixtureResource, expectedResource));
        return this;
    }

    public BatchAssert addCaseJson(String fixtureJson, String expectedJson) {
        cases.add(new TestCase(fixtureJson, expectedJson, true));
        return this;
    }

    public BatchAssert ignorePaths(String... paths) {
        this.ignorePaths.addAll(Arrays.asList(paths));
        return this;
    }

    public BatchResult runAll() {
        List<CaseResult> results = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            TestCase tc = cases.get(i);
            try {
                TransferAssert assertInstance = TransferAssert.assertThat(spec)
                        .withFixtureJson(tc.isInline() ? tc.getFixtureJson()
                                                       : loadResource(tc.getFixtureResource()));

                if (!ignorePaths.isEmpty()) {
                    assertInstance.ignorePaths(ignorePaths.toArray(new String[0]));
                }

                if (tc.isInline()) {
                    assertInstance.matchesExpectedJson(tc.getExpectedJson());
                } else {
                    assertInstance.matchesExpected(tc.getExpectedResource());
                }

                results.add(CaseResult.pass(i, tc.getDescription()));
            } catch (AssertionError e) {
                results.add(CaseResult.fail(i, tc.getDescription(), e.getMessage()));
            } catch (Exception e) {
                results.add(CaseResult.error(i, tc.getDescription(), e));
            }
        }

        BatchResult batchResult = new BatchResult(results);
        if (!batchResult.isAllPassed()) {
            throw new AssertionError(batchResult.getSummaryMessage());
        }
        return batchResult;
    }

    // loadResourceAsString：classpath 优先、文件系统兜底（同 §10.3 loadResourceAsString / getResourceStream），从略

    @Data
    private static class TestCase {
        private String fixtureResource;
        private String expectedResource;
        private boolean inline;
        private String fixtureJson;
        private String expectedJson;

        public TestCase(String fixtureResource, String expectedResource) {
            this.fixtureResource = fixtureResource;
            this.expectedResource = expectedResource;
        }

        public TestCase(String fixtureJson, String expectedJson, boolean inline) {
            this.fixtureJson = fixtureJson;
            this.expectedJson = expectedJson;
            this.inline = inline;
        }

        public String getDescription() {
            return inline ? "inline-case"
                          : fixtureResource + " → " + expectedResource;
        }
    }

    @Data
    public static class CaseResult {
        private final int index;
        private final String description;
        private final Status status;
        private final String errorMessage;

        public enum Status { PASS, FAIL, ERROR }

        public static CaseResult pass(int index, String desc) {
            return new CaseResult(index, desc, Status.PASS, null);
        }

        public static CaseResult fail(int index, String desc, String error) {
            return new CaseResult(index, desc, Status.FAIL, error);
        }

        public static CaseResult error(int index, String desc, Exception e) {
            return new CaseResult(index, desc, Status.ERROR, e.getMessage());
        }
    }

    @Data
    public static class BatchResult {
        private final List<CaseResult> results;

        public boolean isAllPassed() {
            return results.stream()
                    .allMatch(r -> r.getStatus() == CaseResult.Status.PASS);
        }

        public long passCount() {
            return results.stream()
                    .filter(r -> r.getStatus() == CaseResult.Status.PASS).count();
        }

        public long failCount() {
            return results.stream()
                    .filter(r -> r.getStatus() == CaseResult.Status.FAIL).count();
        }

        public long errorCount() {
            return results.stream()
                    .filter(r -> r.getStatus() == CaseResult.Status.ERROR).count();
        }

        public String getSummaryMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n╔══════════════════════════════════════════════════════╗\n");
            sb.append("║                  批量契约测试结果                     ║\n");
            sb.append("╠══════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║ 总计: %d | 通过: %d | 失败: %d | 异常: %d%n",
                    results.size(), passCount(), failCount(), errorCount()));
            sb.append("╠══════════════════════════════════════════════════════╣\n");

            for (CaseResult r : results) {
                if (r.getStatus() != CaseResult.Status.PASS) {
                    sb.append(String.format("║ [%s] Case #%d: %s%n",
                            r.getStatus(), r.getIndex(), r.getDescription()));
                    if (r.getErrorMessage() != null) {
                        String msg = r.getErrorMessage();
                        if (msg.length() > 200) {
                            msg = msg.substring(0, 200) + "...";
                        }
                        sb.append("║ ")
                          .append(msg.replace("\n", "\n║ "))
                          .append("\n");
                    }
                }
            }

            sb.append("╚══════════════════════════════════════════════════════╝\n");
            return sb.toString();
        }
    }
}
```

### 10.8 JUnit 5 扩展集成【data-transfer-test，ext/ 包】

自定义注解（`@Test` 支持作为元注解，标注后可直接被 JUnit 发现）：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@org.junit.jupiter.api.Test
public @interface TransferSpecTest {
    String spec();
    String fixture();
    String expected();
    TransferAssert.CompareMode mode() default TransferAssert.CompareMode.STRICT;
    String[] ignorePaths() default {};
}
```

扩展实现（用 `TestExecutionInterceptor` 在测试方法调用处执行契约断言，断言通过后再进入方法体；相比在 `BeforeEachCallback` 中断言，失败堆栈归属更准确）：

```java
public class TransferSpecExtension implements TestExecutionInterceptor {

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext context) throws Throwable {
        TransferSpecTest anno =
                invocationContext.getExecutable().getAnnotation(TransferSpecTest.class);
        if (anno == null) {
            invocation.proceed();
            return;
        }

        TransferAssert assertInstance = TransferAssert.assertThat(anno.spec())
                .withFixture(anno.fixture())
                .ignorePaths(anno.ignorePaths());

        if (anno.mode() == TransferAssert.CompareMode.PARTIAL) {
            assertInstance.partiallyMatches(anno.expected());
        } else {
            assertInstance.matchesExpected(anno.expected());
        }

        invocation.proceed();   // 契约断言通过后再执行测试方法体（可为空）
    }
}
```

使用方式（测试类位于 `data-transfer-demo`，见 §8.10 与附录 A）：

```java
@ExtendWith(TransferSpecExtension.class)
class OrderTransferContractTest {

    @TransferSpecTest(
            spec = "specs/order-transfer.yaml",
            fixture = "samples/order-001.json",
            expected = "expected/order-001.json"
    )
    void testOrder001Transfer() {
        // 注解驱动，方法体无需代码
    }

    @TransferSpecTest(
            spec = "specs/order-transfer.yaml",
            fixture = "samples/order-002.json",
            expected = "expected/order-002.json",
            ignorePaths = {"crmOrder.processedAt", "crmOrder.traceId"}
    )
    void testOrder002Transfer() {
        // 自动忽略动态字段
    }

    @Test
    void testWithCustomAssertions() {
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
╔══════════════════════════════════════════════════════╗
║           TransferSpec 契约测试失败                   ║
╠══════════════════════════════════════════════════════╣
║ Spec: ECommerce → CRM
╠══════════════════════════════════════════════════════╣
║ 差异明细：
║
║ 路径: crmOrder.lines[0].unitPrice
║ 类型: 不匹配
║ 期望: 113.0
║ 实际: 112.87
║
║ 路径: crmOrder.lines[1].productCode
║ 类型: 缺失
║ 期望: B002
║ 实际: null
║
║ 路径: crmOrder.traceId
║ 类型: 多余
║ 期望: null
║ 实际: abc-123-def
║
╠══════════════════════════════════════════════════════╣
║ 实际输出：
{"crmOrder":{"id":"ORD-001","lines":[{"unitPrice":112.87,...}]}}
╚══════════════════════════════════════════════════════╝
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
        TransferSpecValidator.ValidationResult result =
                VALIDATOR.validate(Path.of("src/test/resources/specs/order-transfer.yaml"));
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

| 策略 | 说明 |
|---|---|
| 规则编译 | Spec 加载时预编译为 DAG 执行计划，运行时零解析开销 |
| 路径 Trie | 用前缀树索引 FlatMap 的 key，加速通配符匹配 |
| 惰性 Flatten | 对超大 JSON，仅在规则涉及的路径子树上执行 flatten |
| 大数组分片并行 | 同构数组的逐元素变换可用 parallel stream / ForkJoin 分片执行 |

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

内置 Micrometer 指标埋点，支持链路追踪（`TransferEngine` 提供接收 `MeterRegistry` 的重载构造器）：

```java
MeterRegistry registry = ...;
TransferEngine engine = new TransferEngine(spec, registry);
```

### 11.3 技术选型参考

| 层 | 推荐 | 说明 |
|---|---|---|
| Spec 格式 | YAML（人类可读）+ JSON Schema 校验 | — |
| JSON 解析 | Jackson 3（`tools.jackson.core:jackson-databind` + `tools.jackson.dataformat:jackson-dataformat-yaml`） | 全工程唯一 JSON 版本线，随 BOM 管理（附录 B） |
| Flatten/Unflatten | `json-flattener` 0.18.2（`com.github.wnameless.json:json-flattener`） | v0.18.0 起原生支持 Jackson 3（默认 JsonCore 切换为 `Jackson3JsonCore`，Jackson 2 仍经 json-base 支持）；Java 17+；`Jackson3JsonValue` 包装 `JsonNode` 零往返、`unflattenAsMap(Map)` Map 直达还原；通配符匹配为引擎自研（§8.4） |
| 路径解析 | 自研轻量解析器 | 避免引入 jq 等重依赖 |
| 表达式引擎 | Apache Commons JEXL 3（可替换为 Aviator、Spring EL） | `[*]` 由引擎预处理展开后再求值（§8.6 注） |
| Schema 校验 | networknt json-schema-validator **3.0.6**（3.x 发布线） | 官方双发布线：2.x = Java 8+/Jackson 2，**3.x = Java 17+/Jackson 3**（本项目基线选 3.x，勿与 2.x 混用）；支持 Draft 2020-12；原生接受 JSON/YAML 输入（schema 与数据均可），§9.3 的 `validateAndLoad` 直接以 YAML 校验；API 相对 1.5.x 全面重构（`SchemaRegistry`/`Schema`/`List<Error>`） |
| 可观测性 | Micrometer + SLF4J | optional 依赖 |
| 集成方式 | CLI 工具 / SDK 库 / HTTP 微服务 / Spring Boot Starter | core 模块无 Spring 亦可独立使用 |

---

## 附录 A：Maven 多模块工程结构

```text
data-transfer-sdk/                               # 聚合根（父 POM）
├── pom.xml                                      # dependencyManagement：jackson-bom（Jackson 3）等
├── data-transfer-core/                          # 引擎本体（无 Spring 亦可独立使用）
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/example/datatransfer/core/
│       │   │   ├── TransferEngine.java
│       │   │   ├── TransferSpecRegistry.java
│       │   │   ├── spec/                        # 配置模型
│       │   │   │   ├── TransferSpec.java
│       │   │   │   ├── TransferOptions.java
│       │   │   │   ├── MappingRule.java
│       │   │   │   ├── ComputedField.java
│       │   │   │   ├── DefaultValue.java
│       │   │   │   ├── ConditionMapping.java
│       │   │   │   ├── SourceDeclaration.java
│       │   │   │   ├── PathRewrite.java
│       │   │   │   └── ObservabilityConfig.java
│       │   │   ├── flatten/
│       │   │   │   ├── FlatMapProcessor.java    # json-flattener 封装 + 自研通配符匹配（§8.4）
│       │   │   │   └── PathParser.java          # 路径解析（通配符多级对位、路径校验）
│       │   │   ├── transform/
│       │   │   │   ├── TransformFunction.java
│       │   │   │   └── FuncRegistry.java
│       │   │   ├── expression/
│       │   │   │   ├── ExpressionEvaluator.java
│       │   │   │   └── JexlExpressionEvaluator.java
│       │   │   ├── validation/
│       │   │   │   └── TransferSpecValidator.java
│       │   │   └── spring/                      # Spring Boot 4 自动配置（Boot 依赖 optional）
│       │   │       ├── DataTransferAutoConfiguration.java
│       │   │       └── DataTransferProperties.java
│       │   └── resources/
│       │       ├── schemas/
│       │       │   └── transfer-spec.json
│       │       └── META-INF/
│       │           └── spring/
│       │               └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│       └── test/java/com/example/datatransfer/core/
│           ├── TransferEngineTest.java
│           ├── FlatMapProcessorTest.java
│           └── TransferSpecValidatorTest.java
├── data-transfer-test/                          # 契约测试工具（依赖 core + JUnit 5 API）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/datatransfer/test/
│       │   ├── TransferAssert.java
│       │   ├── AssertContext.java
│       │   ├── BatchAssert.java
│       │   ├── DiffEngine.java
│       │   ├── DiffResult.java
│       │   ├── DiffEntry.java
│       │   ├── DiffType.java
│       │   └── ext/
│       │       ├── TransferSpecTest.java        # @TransferSpecTest 注解
│       │       └── TransferSpecExtension.java
│       └── test/java/com/example/datatransfer/test/
│           └── TransferAssertTest.java
└── data-transfer-demo/                          # 可运行示例 + 契约测试示范
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/example/datatransfer/demo/
        │   │   └── OrderTransferDemo.java       # §8.10 示例
        │   └── resources/
        │       └── specs/
        │           └── order-transfer.yaml      # §7.2 配置
        └── test/
            ├── java/com/example/datatransfer/demo/
            │   └── OrderTransferContractTest.java   # §10.8 / §10.11 契约测试示范
            └── resources/
                ├── samples/                     # 输入样本
                │   ├── order-001.json
                │   ├── order-002.json
                │   └── order-003.json
                └── expected/                    # 期望输出
                    ├── order-001.json
                    ├── order-002.json
                    └── order-003.json
```

> 注：Spring Boot 4 的自动配置注册文件为
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
> （内容为自动配置类全限定名，一行一个），`spring.factories` 注册机制已移除。

---

## 附录 B：依赖清单（按模块）

**版本策略**：Jackson 3 全工程统一，经父 POM 导入 `tools.jackson.core:jackson-bom` 管理（不锁小版本，落地时对齐 Central 最新 3.0.x 或宿主工程 BOM）；JUnit / Micrometer / Spring 由宿主工程（或 Spring Boot 4 BOM）提供；仅 JEXL、json-flattener 与 Schema 校验器由本 SDK 自锁版本。

**父 POM（data-transfer-sdk）**：

```xml
<dependencyManagement>
    <dependencies>
        <!-- Jackson 3 全工程统一版本线 -->
        <dependency>
            <groupId>tools.jackson.core</groupId>
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

**data-transfer-core**：

```xml
<dependencies>
    <!-- JSON 解析（Jackson 3，版本随 BOM） -->
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

    <!-- Bean 校验（兜底） -->
    <dependency>
        <groupId>jakarta.validation</groupId>
        <artifactId>jakarta.validation-api</artifactId>
        <scope>provided</scope>
    </dependency>

    <!-- Spring Boot 4 自动配置（optional，非 Boot 环境零影响） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- 可观测性（optional） -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- 测试 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
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

    <!-- JUnit 5 API：编译期需要，运行时由宿主工程提供 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter-api</artifactId>
        <optional>true</optional>
    </dependency>

    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
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

    <!-- 演示 §8.9 自动配置 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>

    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 总结

该框架的设计哲学是"降维打击"：**Flatten** 把任意复杂的嵌套 JSON 拍平为一维键值空间，消除结构差异；**声明式映射**让所有转换逻辑用 YAML 配置表达——零代码、可版本化、可热更新；**Unflatten** 根据目标路径自动重建嵌套结构。最终效果是：对接一个新的上下游系统，只需写一份 TransferSpec 配置文件，无需改任何代码。

工程落地按三模块推进：`data-transfer-core`（引擎与校验，Jackson 3 统一版本线、Flatten/Unflatten 复用 json-flattener 0.18.2——v0.18.0 起原生支持 Jackson 3）、`data-transfer-test`（TransferAssert 契约测试工具）、`data-transfer-demo`（端到端示例与契约测试示范）。配合 JSON Schema 校验（配置格式）与 TransferAssert（运行时行为），从配置格式到映射行为形成完整的校验链路，任何一层出问题都能在测试阶段被拦截。

---

## 附录 C：落地注记（v1.0 实现，2026-09-07）

三模块已在本工程落地（groupId 统一 `com.example`，与 usecase-framework 六模块聚合；设计文档中的 `com.example.datatransfer` groupId 为独立发布形态，仓库内不适用），并以**内置 `dataTransfer` step 类型**集成进 usecase-framework-core（config：`spec`（classpath 引用）/`source`（SpEL，缺省 `#payload`）/`as`（旁路键，缺省覆盖 payload）；装配期 Schema 校验 fail-fast；默认 source 时覆写 `dataflow()` 声明键级血缘）。`mvn clean test` 全量绿：core 38 + test 14 + demo 6 + usecase 集成 10 + demo e2e。

### C.1 实现中实证并修正的文档表述

| 原表述 | 实测结论（已按此实现） |
|---|---|
| §5.3-1「空对象/空数组拍平后不产生任何键，Unflatten 无法还原」 | **不成立**：json-flattener v0.10+ 将空容器**作为叶子值保留**（`{"a": {}}` → 键 `a`、值为空 Map），往返可还原（`FlatMapProcessorTest.emptyContainers_areKeptAsLeafValues` 实证） |
| §8.4/§10.3「FlatKey 中除 `.` 与 `[*]` 外不含正则元字符，只需转义 `.`」 | **论断错误**：字面索引 `[0]` 的方括号是正则元字符（不转义即成字符类），`expandWildcard` 与断言路径统一经 `FlatMapProcessor.wildcardPattern`（占位保护 `[*]` → 转义 `[]` → 还原 `[\d+]`）处理 |
| §6.3 `otherwise: "default('unknown')"` 的直觉语义 | `default()` **仅对 null 兜底**，非 null 值原样传递；「无分支命中的兜底输出」需写针对当前值的变换（如 `replace`）或依赖 nullPolicy=KEEP 下的 null 值 |
| §9.3 networknt API「待 javadoc 核实」 | 全部实证可用：`SchemaRegistry.withDefaultDialect(SpecificationVersion)` 单参、`getSchema(String, InputFormat)`、`validate(String, InputFormat)` 双参（本项目 `ValidatorStepFactory` 亦为同形态） |
| §8.4 json-flattener 实例级 API「设计示意」 | 全部实证可用：`Jackson3JsonValue`（`com.github.wnameless.json.base`）包装 `JsonNode`、`new JsonFlattener(...).withSeparator(char).flattenAsMap()`、`new JsonUnflattener(Map).withSeparator(char).unflattenAsMap()` |

### C.2 与设计文档的实现偏差（环境事实）

- **Jackson 3 YAML mapper**：`JsonMapper.builder(new YAMLFactory())` 不可用（类型不兼容），须 `YAMLMapper.builder()...build()`（§9.3 代码已按此实现）。
- **JUnit**：Spring Boot 4.1 解析到 JUnit 6.0.x，`TestExecutionInterceptor` 已移除，`TransferSpecExtension` 改用统一拦截接口 `InvocationInterceptor`（§10.8 所述形态的 JUnit 6 等价物）。
- **computed 求值**：实现了受限聚合形态 `agg(PATH)` / `agg(PATH * PATH)`（agg ∈ sum/avg/count/min/max，双路径按索引对位逐元素相乘，BigDecimal 精度）；其余表达式以目标 FlatMap 的嵌套视图绑定顶层键后交 JEXL。
- **TransferAssert 的 spec 加载**：统一经 `TransferSpecValidator.validateAndLoad`（加载即 Schema 校验，对齐 §10.11 校验链路）；`pathExists` 支持「键或键祖先」匹配（`a.lines[*]` 可命中 `a.lines[0].unitPrice` 的容器存在性）。

### C.3 第一版裁剪清单（装配期/构造期 fail-fast 拒绝，不静默忽略）

`sources`（多源合并）与 `rewrites`（路径改写）保留模型字段但引擎构造期抛「not yet supported」；未实现：批量模式 `transfer_batch`、dry-run、`fn:` 前缀函数语法（自定义函数经构造器 `extraFunctions` 注册后直接以函数名引用）、指标埋点（ObservabilityConfig 仅模型承载）、§3.2 函数表中的其余内置函数（已实现 trim/lower/upper/replace/multiply/round/default，其余经 FuncRegistry.register 扩展）。

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
| 1.7 目标键冲突 | 默认「后规则覆盖前规则」；`strictMode: true` 时构造期检测**字面目标键**重复即报错（通配目标的数据期覆盖维持"后覆盖前"——构造期不可穷举） |
| 3.4 strictMode 保留字符 | `strictMode: true` 时首 transfer 前对含 `["` 转义记法的源键 fail-fast（§5.3-2 承诺补齐） |
| 3.5 JEXL 沙箱 | `JexlSandbox(true)` 白名单模式：仅放行 Map/List/String/Number/Integer/Long/Double/Boolean/BigDecimal，`new('类')` 构造被拒（JexlException）、反射链静默 null（不可见即不执行）；注：`T(...)` 是 SpEL 语法，JEXL 中天然不可达 |

### D.2 设计采纳、列入路线图（评审建议，待实现）

- **1.4 rewrites 时机**：Phase 1（拍平后）与 Phase 2（规则匹配前）之间，按声明顺序串行应用于源 FlatMap 所有键
- **1.6/9.2 多源约束**：各源拍平后以 `alias.` 根前缀合并；规则 `from` 必须以某 alias 开头（否则按 missingPolicy 处置）；装配期 alias 唯一性校验
- **1.3 computed 依赖**：按声明顺序执行（当前实现已隐式支持前向引用——flatTarget 逐步更新），补拓扑循环检测；后向引用以运行期求值失败暴露
- **2.5 日期函数**：默认 UTC + 可选时区参数（函数族实现时执行）
- **2.9 coalesce**：Phase 2 执行，仅引用源 FlatMap 路径
- **2.10 类型转换函数族**：`toEnum(className)` / `toLocalDate(fmt)` / `toLocalDateTime(fmt)` / `toBigDecimal`（金额场景替代 toNumber）；不引入独立 ConversionHandler（保持 TransformFunction 单一扩展点，接口文档补类型转换专项示例）
- **3.1/9.3 数组稀疏**：索引忠实传递（`items[1]` → `lineItems[1]`，空洞由 Unflattener 垫槽位为 null——当前实现即此行为，文档固化）；压缩重排经显式变换函数
- **2.3 otherwise 字面量**：可加 `const(v)` 内置函数实现无条件字面输出（default 仅兜 null 的语义不变）
- **2.4 函数参数编译期校验**：内置函数数值参数装配期正则校验（`multiply("abc")` 提前拦截）
- **4.3/4.4**：dry-run 输出结构标准化（planned_mappings/warnings/estimated_output 入 Schema）；`version` 兼容性策略（1.x 向后兼容）
- **7.1 passthrough**：整棵子树原样搬运（不进拍平管道），大 JSON 少字段场景的逃逸阀
- **2.8 标量→数组广播**：维持第一版禁止（与索引对齐语义正交）；广播需求收集后再放宽 Schema
- **8.1/8.2 可观测性**：审计日志结构化（OpenTelemetry 语义约定）、指标收集时机与去重策略——随指标埋点排期
