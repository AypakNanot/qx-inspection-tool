# RS错误秒采集 — 设计文档

- 日期：2026-09-27
- 状态：已确认（brainstorming 三段逐段验收通过）
- 关联：`docs/sync-stats-redesign.md` §10.7（原始方案，本文档为其落地细化）

## 背景与目标

巡检结果表 `link_inspection_result` 的 `a_rs_error_sec` / `z_rs_error_sec` 字段、前端查询页 RS 列与"仅看误码"筛选、Excel 导出 `RS(错误秒)` 表头均已就位，但采集侧硬编码 `null`（`InspectionService.buildSide`，注释"性能采集未接入，字段预留"）。

目标：巡检时通过 QX 协议 0x0C07 实时采集 RS-ES / RS-SES / RS-UAS，使 RS错误秒有真实数据。口径沿用既定设计：**RS错误秒 = RS-ES + RS-SES + RS-UAS**。

## 已确认的决策

| 决策点 | 结论 |
|---|---|
| 性能编码来源 | 硬编码，来源 Uniview 库 `defpmattr` 表（网管与设备同一编号体系） |
| 时间窗口 | 0x0C07 当前24小时性能（无时间参数，设备实时累计） |
| 性能失败时端口成败 | **算成功**，`error_info` 追加哪个指标、什么原因失败 |
| 集成方式 | 方案 A：在 `collectPort` 内顺带查询，不建独立采集循环 |

## 性能编码常量

来源：`Uniview.defpmattr`（`SELECT eName, type FROM defpmattr`）。

| 指标 | eName | performanceCode |
|---|---|---|
| RS误码秒 | RS-ES | 18194 |
| RS严重误码秒 | RS-SES | 18195 |
| RS不可用秒 | RS-UAS | 18196 |

编码定义进新增常量类 `PerfCodes`，注释标明来源表。**待真机验证**：`defpmattr.type` 与 QX 协议 0x0C07 `performanceCode` 为同一编号体系（回包 `performanceCode` 对得上即闭环，对不上以回包为准反推）。

## 采集流程

```
collectPort(neId, portOid, portTypes)
  1. 查光功率（现有 LaserAttributeGetReq → laserService.attributeGet）
     ├─ 失败 → return LaserSample.error(...)        ← 行为完全不变
     └─ 成功 → 继续
  2. perfService.current24HGet(neId, reqEs, reqSes, reqUas)
     三条记录 varargs 一次发出（cmdCode 0x0C07）
     ├─ 成功 → 解析回包，rsErrorSec = ES + SES + UAS
     └─ 失败 → rsErrorSec = null，errorInfo 追加 "性能采集失败(...): <原因>"
  3. 组装 LaserSample（光功率字段 + rsErrorSec + errorInfo）
```

请求参数与激光器请求同源：`subcaseNo/slotId/portId` 从 `portOid` 解析，`portType/portSubType` 查 `portTypes`；`tsOrderId=0`、`tsAttribute=0`（物理端口本身，schema 注释口径，待真机验证）。

端口类型口径：dmeo 的 `type`（如 20008）经 Uniview `defobject(cid=5).deviceType` 拆分为 `(portType, portSubType)`（高位字节/低位字节，如 20008 → deviceType 515=0x0203 → `2/3`），兜底 `0xFF/0xFF`（defobject 查询失败时全量兜底、不中断巡检；单个类型 deviceType 为 -1/0 或缺行时该端口兜底）。

`IPerfService` / `PerfServiceImpl` 已由 codec 插件从 `perf.yaml` 自动生成，直接注入 `InspectionService` 使用。

### 回包解析（List<PerfCurrent24HGetRsp>，逐条）

1. `subcaseNo & 0x80`（bit7，Invalid PM）= 1 → 该记录无效，跳过
2. 按 `performanceCode` 归桶 → ES / SES / UAS 三个计数器
3. `subcaseNo & 0x40`（bit6，高/低32位）→ 高位 `<<32` 与低位拼接（防御性实现，实际是否拆分待真机验证）
4. 某编码回包缺失 → 该指标计入 error_info（"设备未返回"）
5. 三项全无效或列表为空 → 整体失败

## 失败分级

"端口成败"按 **该端是否采到**（`sideCollected(moduleType, errorInfo)`：光功率失败路径必写 error_info 判未采集；无 error_info 的侧视为已采集——与旧口径一致，也覆盖两侧字段全 null 的退化行）判定：光功率采到即成功，error_info 只记原因。

| 场景 | rsErrorSec | error_info | 端口成败（moduleType 判定） |
|---|---|---|---|
| 光功率查询失败 | — | 照旧覆盖（moduleType=null） | **失败**（现有行为不变） |
| 光功率 OK，0x0C07 发送失败 | null | 追加 `性能采集失败(RS-ES/RS-SES/RS-UAS): <原因>` | 成功 |
| 光功率 OK，回包缺某指标 | 相加已有项 | 追加 `性能采集缺失(RS-UAS): 设备未返回` | 成功 |
| 光功率 OK，三项全 Invalid | null | 追加 `性能采集失败: 设备返回无效数据` | 成功 |

追加（非覆盖）保证光功率错误不被性能错误冲掉。
**"error_info 空即采集成功"的旧口径已被证伪**（性能失败会写 error_info 但端口算成功）：`isCollected` 保存过滤与 `sideAbnormal` 异常统计均改用 `sideCollected(moduleType, errorInfo)` 判定，error_info 仅作原因记录——性能失败既不丢链路（saveInvalid=false 仍保存），也不计入 abnormalLinks/异常明细。

## 改动文件

| 文件 | 改动 |
|---|---|
| `PerfCodes`（新增） | 三个性能编码常量 + 来源注释 |
| `LaserSample` | 加 `rsErrorSec` 字段，工厂方法适配 |
| `InspectionService.collectPort` | 追加性能查询段（超行数/复杂度则把回包解析拆私有静态方法） |
| `DynamicSyncService.loadPortDeviceTypes`（新增） | 从 MySQL `defobject(cid=5)` 查 type→deviceType 映射（不吞异常，方法内归还连接池） |
| `InspectionService.loadPortTypes` / `loadDeviceTypes` / `splitPortType` | `portTypes` 签名 `Map<String,Integer>` → `Map<String,int[]>`；dmeo.type 经 defobject.deviceType 拆成 (portType, portSubType)，查询失败/非法值兜底 `{0xFF, 0xFF}`（兜底数组每次新建） |
| `InspectionService.isCollected` / `sideAbnormal` | 采集成功判定由 error_info 改为 `sideCollected(moduleType, errorInfo)`（性能失败不再丢链路、不再计入异常） |
| `InspectionService.buildSide` | `null` → `sample.rsErrorSec()` |

**不改：** 表结构、实体、前端 query.js、Excel 导出、统计口径（全部已就位）；`perf.yaml` 及生成代码。

## 测试

单元测试（新代码口径 ≥ 80%，沿用 `InspectionServiceTest` mock 模式）：

- **响应解析**：三条正常相加；bit7 跳过；缺编码计缺失；空列表整体失败；bit6 拼接
- **collectPort 分支**：光功率失败不发性能查询（verify `IPerfService` 零调用）；双成功 → rsErrorSec 有值 errorInfo 不动；性能抛 `QxCommandException` → null + "性能采集失败"字样 + 成功态
- **buildSide 透传**：有值/ null 两种落库
- **回归**：现有光功率用例全绿（性能 mock 默认返回正常三元组）

坏味道自查：错误文案提取 `static final`（S1192）；`collectPort` 控制在方法体 ≤30 行。

## 真机联调验收清单

1. `performanceCode=18194` 发 0x0C07，回包编码一致、`performanceValue` 为合理秒数；确认回包 subcaseNo 低位子架号 < 0x40（bit6/bit7 与子架号同字节）
2. `tsOrderId/tsAttribute=0` 是否命中物理端口
3. bit6 是否实际出现（不出现则拼接分支仅为防御）
4. 巡检跑一轮 → query 页 RS 列有数 → 导出 `RS(错误秒)` 列有数 → "仅看误码"筛选生效
5. 确认设备对不支持/未知的 0x0C07 是立即回错误码还是 10s 静默超时；若存在静默场景，评估连续失败熔断或 `qx.perf.enabled` 开关
6. 抓包核对激光器与 0x0C07 请求的 portType/portSubType 与 defobject.deviceType 拆分一致（如 20008 → `02 03`）

**验收标准：** 巡检一轮后 `a_rs_error_sec/z_rs_error_sec` 有真实数值（或失败时 null 且 error_info 有原因），全量测试通过、新代码覆盖率 ≥80%。

## 实现状态（2026-09-27）

- [x] PerfCodes 常量 + parsePerfResponses（11 测试）
- [x] collectPort 集成 0x0C07（10 测试）
- [x] 读取端适配：isCollected/sideAbnormal 改 sideCollected 口径（4 测试）
- [x] buildSide 透传（3 测试）
- [x] portType/portSubType defobject.deviceType 拆分（8 测试）
- [x] 全量回归 205 绿
- [ ] 真机联调验收（performanceCode=18194 回包核对、tsOrderId/tsAttribute、bit6、端到端一轮巡检、验收清单第 5 项超时行为）
