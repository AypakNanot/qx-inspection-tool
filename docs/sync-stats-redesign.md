# 数据同步重构 + 统计功能重设计方案

## 一、目标

1. 将数据同步从"全量同步所有表"改为"按网络过滤 + 冗余字段"模式
2. 在同步入口完成数据组装，简化后续功能代码
3. 重构统计功能，基于新的数据结构

## 二、SQLite 表结构设计

### 2.1 dmeo 表（核心对象表）

```sql
CREATE TABLE "dmeo" (
  "oid" TEXT NOT NULL,
  "cid" INTEGER,
  "type" INTEGER,
  "name" TEXT,
  "defName" TEXT,
  "networkOid" TEXT,
  "networkName" TEXT,
  "neName" TEXT,
  "neTypeName" TEXT,
  "ipAddr" TEXT,
  PRIMARY KEY ("oid")
);
```

| 字段 | 类型 | 说明 | 来源 |
|------|------|------|------|
| oid | TEXT | 主键 | dmeo.oid |
| cid | INTEGER | 1=网络, 2=NE, 4=盘, 5=端口 | dmeo.cid |
| type | INTEGER | 类型 | dmeo.type |
| name | TEXT | 名称 | dmeo.name |
| defName | TEXT | 默认名称 | dmeo.defName |
| networkOid | TEXT | 所属网络OID（冗余） | dmrelation.reo |
| networkName | TEXT | 所属网络名称（冗余） | dmeo cid=1.name |
| neName | TEXT | 所属NE名称（冗余） | dmeo cid=2.name |
| neTypeName | TEXT | NE类型名称（冗余） | defdmne.cName |
| ipAddr | TEXT | IP地址（冗余） | emnecomm.ipAddr (state=1) |

### 2.2 dmconnection 表（物理链路表）

```sql
CREATE TABLE "dmconnection" (
  "oid" TEXT NOT NULL,
  "cid" INTEGER NOT NULL DEFAULT 100,
  "name" TEXT,
  "aEnd" TEXT,
  "zEnd" TEXT,
  "createTime" INTEGER,
  "creator" TEXT,
  "additionInfo" TEXT,
  "aNeName" TEXT,
  "aNeTypeName" TEXT,
  "aNetworkName" TEXT,
  "aPortName" TEXT,
  "aCapacity" INTEGER,
  "aUsed" INTEGER,
  "zNeName" TEXT,
  "zNeTypeName" TEXT,
  "zNetworkName" TEXT,
  "zPortName" TEXT,
  "zCapacity" INTEGER,
  "zUsed" INTEGER,
  PRIMARY KEY ("oid")
);
```

**端口名称拼接规则**：
```
输入: aEnd = "101:1:11:1", dmeo.name = "地调1-5.1"
输出: aPortName = "地调1-5.1(1/11/1)"
```

### 2.3 同步元数据（sys_config 表）

| key | value 示例 | 说明 |
|-----|-----------|------|
| sync.networkIds | "1,70,71" | 同步的网络ID列表 |
| sync.networkNames | "鹰潭_地调,上饶北区_地调,上饶南区_地调" | 同步的网络名称列表 |
| sync.time | "2026-09-18 10:30:00" | 同步时间 |
| sync.status | "SUCCESS" | 同步状态 |

### 2.4 不再需要的表

| 原表 | 原因 |
|------|------|
| dmnet | 用 dmeo cid=1 代替 |
| dmrelation | 冗余到 dmeo.networkOid |
| defdmne | 冗余到 dmeo.neTypeName |
| defdmnetwork | 只有1条数据，硬编码 |
| deflinktype | 只用 cid=100，硬编码 |
| emnecomm | 冗余到 dmeo.ipAddr |
| portbandwidth | 冗余到 dmconnection |

## 三、同步流程设计

### 3.1 同步触发条件

1. **用户主动点击**：调用同步 API，传入目标网络 OID 列表
2. **巡检任务开始前**：检查 sys_config 中是否有 sync.networkIds，如果没有则提示用户先同步

### 3.2 同步逻辑

```
1. 清空旧数据（dmeo, dmconnection）
2. 从 MySQL 读取基础数据（全量）
   - dmeo, dmrelation, defdmne, emnecomm, portbandwidth, dmconnection
3. 构建内存索引
   - networkOid → networkName
   - neOid → networkOid
   - neOid → neName
   - neType → neTypeName
   - neOid → ipAddr
   - portOid → portName
   - portOid → bandwidth
4. 过滤并写入 dmeo 表
   - 过滤条件: networkOid IN (目标网络)
   - 冗余字段: networkOid, networkName, neName, neTypeName, ipAddr
5. 过滤并写入 dmconnection 表
   - 过滤条件: aEnd 或 zEnd 的 NE OID 在目标网络中（跨网络链路保留）
   - 冗余字段: aNeName, aNeTypeName, aNetworkName, aPortName, aCapacity, aUsed
   - 冗余字段: zNeName, zNeTypeName, zNetworkName, zPortName, zCapacity, zUsed
6. 更新 sys_config 元数据
```

### 3.3 API 设计

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/sync/networks | GET | 获取可同步的网络列表 |
| /api/sync/execute | POST | 执行同步 |
| /api/sync/status | GET | 获取同步状态 |

## 四、统计功能设计

### 4.1 统计维度

| 统计项 | 数据来源 | 查询方式 |
|--------|---------|---------|
| 网络数量 | dmeo cid=1 | COUNT |
| 网元数量 | dmeo cid=2 | COUNT |
| 盘数量 | dmeo cid=4 | COUNT |
| 端口数量 | dmeo cid=5 | COUNT |
| 链路数量 | dmconnection | COUNT |
| 网元按类型统计 | dmeo cid=2 | GROUP BY neTypeName |
| 盘按类型统计 | dmeo cid=4 | GROUP BY type |
| 端口按类型统计 | dmeo cid=5 | GROUP BY type |

### 4.2 界面布局

```
┌─────────────────────────────────────────────────────────────────┐
│  统计概览                                                        │
├─────────┬─────────┬─────────┬─────────┬─────────┬───────────────┤
│ 网络数  │ 网元数  │  盘数   │ 端口数  │ 链路数  │               │
│   6     │  1417   │  5000   │  12000  │  2403   │               │
└─────────┴─────────┴─────────┴─────────┴─────────┴───────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  统计类型    [网元类型] [盘类型] [端口类型]    [柱状图] [饼图] [折线图] │
├─────────────────────────────────────────────────────────────────┤
│                        ECharts 图表区域                         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  类型名称              │  数量      │  占比                     │
├────────────────────────┼────────────┼───────────────────────────┤
│  MatrixEdge3050        │    500     │   35.3%                   │
│  MatrixEdge2050        │    300     │   21.2%                   │
└────────────────────────┴────────────┴───────────────────────────┘
```

### 4.3 API 接口

| 接口 | 说明 |
|------|------|
| GET /api/inventory/overview | 概览统计 |
| GET /api/inventory/ne-stats | 网元类型统计 |
| GET /api/inventory/slot-stats | 盘类型统计 |
| GET /api/inventory/port-stats | 端口类型统计 |

## 五、设备管理功能改动

### 5.1 改动说明

由于新数据方案中 dmeo 表已冗余所有字段（neName, neTypeName, networkName, ipAddr），设备同步功能可以大幅简化。

### 5.2 新的同步逻辑

```java
public void syncDevicesFromSQLite() {
    // 直接从 dmeo cid=2 获取所有 NE 数据
    List<Map<String, Object>> allNe = sqliteJdbc.queryForList(
        "SELECT oid, name, neTypeName, networkName, ipAddr FROM \"dmeo\" WHERE cid = 2");
    
    // 过滤没有活跃 IP 的设备
    // 生成 DeviceAccessConfig 列表
    // 保存到 device_access_config 表
}
```

### 5.3 原逻辑 vs 新逻辑

| 原逻辑 | 新逻辑 |
|--------|--------|
| 从 dmne 查询设备 | 从 dmeo cid=2 直接获取 |
| JOIN defdmne 获取类型名 | 直接用 neTypeName 字段 |
| JOIN dmeo cid=2 获取名称 | 直接用 name 字段 |
| JOIN emnecomm 获取 IP | 直接用 ipAddr 字段 |
| JOIN dmrelation + dmeo cid=1 获取网络 | 直接用 networkName 字段 |
| 按网络筛选参数 | 不需要，直接获取所有 |

### 5.4 API 变化

| 接口 | 变化 |
|------|------|
| POST /api/db/sync-devices | 移除 network 参数 |
| GET /api/db/networks | 不再需要，可移除 |

### 5.5 界面变化

| 变化点 | 说明 |
|--------|------|
| 移除网络筛选下拉框 | 同步设备时不再需要选择网络 |
| 其他保持不变 | 设备列表、启用/禁用、连接配置等 |

### 5.6 代码修改范围

| 文件 | 操作 | 说明 |
|------|------|------|
| DeviceAccessService.java | 重构 | 简化 syncDevicesFromSQLite 方法 |
| DatabaseTestController.java | 修改 | 移除 sync-devices 的 network 参数 |
| device.js | 修改 | 移除网络筛选下拉框 |

## 六、门限配置功能改动

### 6.1 改动说明

1. **移除全局门限**：不再需要 GLOBAL 级别
2. **预置默认值**：每个模块类型预置标准默认门限值
3. **只允许修改**：用户只能修改门限值，不能添加或删除
4. **简化操作**：界面只显示门限修改功能

### 6.2 预置默认门限值

| 模块类型 | 说明 | TX Low | TX High | RX Low | RX High |
|---------|------|--------|---------|--------|---------|
| I1.1 | 155M+I档(短距) | -10.0 | 0.0 | -28.0 | -8.0 |
| S1.1 | 155M+S档(中距) | -15.0 | -1.0 | -28.0 | -8.0 |
| L1.1 | 155M+L档(长距) | -15.0 | -1.0 | -28.0 | -8.0 |
| I4.1 | 622M+I档(短距) | -10.0 | 0.0 | -28.0 | -8.0 |
| S4.1 | 622M+S档(中距) | -15.0 | -1.0 | -28.0 | -8.0 |
| L4.1 | 622M+L档(长距) | -15.0 | -1.0 | -28.0 | -8.0 |
| I16.1 | 2.5G+I档(短距) | -10.0 | 0.0 | -28.0 | -8.0 |
| S16.1 | 2.5G+S档(中距) | -15.0 | -1.0 | -28.0 | -8.0 |
| L16.1 | 2.5G+L档(长距) | -15.0 | -1.0 | -28.0 | -8.0 |
| V16.1 | 2.5G+V档(超长距) | -15.0 | -1.0 | -28.0 | -8.0 |
| S64.2b | 10G+S档(中距) | -10.0 | 0.0 | -18.0 | -1.0 |
| L64.2 | 10G+L档(长距) | -10.0 | 0.0 | -18.0 | -1.0 |
| V64.2 | 10G+V档(超长距) | -10.0 | 0.0 | -18.0 | -1.0 |
| 1000BASE-SX | GE+SX(多模) | -10.0 | 0.0 | -17.0 | -3.0 |
| 1000BASE-LX | GE+LX(单模) | -10.0 | 0.0 | -17.0 | -3.0 |

### 6.3 新的界面布局

```
┌─────────────────────────────────────────────────────────────────┐
│  门限配置                                                        │
├─────────────────────────────────────────────────────────────────┤
│  说明：每个模块类型已预置标准默认门限值，您可以修改这些值。            │
├─────────────────────────────────────────────────────────────────┤
│  模块类型        │ TX低门限 │ TX高门限 │ RX低门限 │ RX高门限 │ 操作  │
├─────────────────┼──────────┼──────────┼──────────┼──────────┼──────┤
│  I1.1 (155M+I)  │  -10.0   │   0.0    │  -28.0   │   -8.0   │ 修改 │
│  S1.1 (155M+S)  │  -15.0   │  -1.0    │  -28.0   │   -8.0   │ 修改 │
│  L1.1 (155M+L)  │  -15.0   │  -1.0    │  -28.0   │   -8.0   │ 修改 │
│  ...            │  ...     │   ...    │  ...     │   ...    │ ...  │
└─────────────────┴──────────┴──────────┴──────────┴──────────┴──────┘
```

### 6.4 API 变化

| 接口 | 变化 |
|------|------|
| GET /api/inspection/thresholds | 返回所有模块类型的门限规则 |
| POST /api/inspection/thresholds | 修改门限值（只允许修改，不允许新增） |
| DELETE /api/inspection/thresholds/{id} | 移除，不允许删除 |

### 6.5 代码修改范围

| 文件 | 操作 | 说明 |
|------|------|------|
| ThresholdService.java | 重构 | 移除全局门限逻辑，预置默认值 |
| ThresholdRule.java | 修改 | 移除 levelType 字段，只保留 matchKey |
| ThresholdRuleRepository.java | 修改 | 简化查询方法 |
| threshold.js | 重构 | 移除添加/删除功能，只保留修改 |
| index.html | 修改 | 更新门限配置页面布局 |

## 七、任务进度功能改动

### 7.1 改动说明

任务进度功能不需要改动。所有数据来源于巡检记录本身，不依赖同步表。

### 7.2 影响分析

| 接口 | 数据来源 | 依赖同步表 | 影响 |
|------|---------|-----------|------|
| GET /inspection/progress | 内存 currentRound | 无 | 无 |
| GET /inspection/summary | inspectionRound + linkInspectionResult | 无 | 无 |
| GET /inspection/rounds | inspectionRound | 无 | 无 |

### 7.3 说明

- `thresholdService.applyThresholds()` 会受益于门限重构（移除 GLOBAL 级别，查询更简单）
- 按模块类型/设备类型分组统计基于巡检记录本身，不依赖同步表
- 前端 progress.js 无直接同步表依赖

## 八、任务配置功能改动

### 8.1 改动说明

任务配置本身逻辑不受同步重构影响，仅网络列表获取方式需要适配新的数据结构。

### 8.2 影响分析

| 模块 | 影响 | 说明 |
|------|------|------|
| InspectionScheduler | 无 | 配置存储在 sys_config，不依赖同步表 |
| triggerScheduledInspection | 自动受益 | DeviceAccessService.resolveTargets() 查询更简单 |
| loadTaskNetworks() | 需适配 | 改为直接读 dmeo cid=1 |
| /db/sync-devices 调用 | 已处理 | 移除 network 参数（见设备管理部分） |

### 8.3 代码修改范围

| 文件 | 操作 | 说明 |
|------|------|------|
| InventoryStatsController.java | 修改 | /api/inventory/networks 适配新查询 |
| 或 DeviceAccessService.java | 修改 | getAvailableNetworkNames() 简化为读 dmeo cid=1 |

### 8.4 说明

不需要单独的实现步骤，跟随统计模块和设备模块的改动自动完成。

## 九、数据查询（巡检结果）功能改动

### 9.1 目标

严格按照新 Excel 模板（巡检工具巡检结果-20260918-2.xlsx）重构导出和前端显示。

### 9.2 数据来源

#### 9.2.1 当前查询的表

| 数据 | 表 | 当前查询方式 |
|------|---|------------|
| 巡检记录 | optical_power_inspection | powerRecordRepository.findByRoundId() |
| 物理链路 | dmconnection (cid=100) | sqliteJdbc SELECT oid, name, aEnd, zEnd |
| 端口名称 | dmeo | loadDmeoNameMap() SELECT oid, name |
| 网元名称 | dmeo (cid=2) | loadNeNameMap() SELECT oid, name WHERE cid=2 |
| 网元类型 | dmne + defdmne | loadNeTypeNameMap() JOIN 查询 |
| 网络归属 | dmrelation + dmeo | loadNetworkMap() JOIN 查询 |
| 带宽数据 | portbandwidth | loadBandwidthMap() SELECT * |

#### 9.2.2 新方案简化

| 原表 | 新方案替代 |
|------|-----------|
| dmne + defdmne (JOIN) | dmeo.neTypeName 直接冗余 |
| dmrelation + dmeo (JOIN) | dmeo.networkName 直接冗余 |
| portbandwidth | dmconnection 冗余字段 (aCapacity, aUsed, zCapacity, zUsed) |
| dmeo (多次查询) | dmeo 一次查询获取所有冗余字段 |

#### 9.2.3 新方案最终查询

| 表 | 用途 |
|---|------|
| link_inspection_result | 巡检结果（A端+Z端光功率、性能、带宽） |
| dmconnection | 链路基础信息（同步时写入） |
| dmeo | 端口详情（同步时写入） |

### 9.3 Excel 目标格式（26列，3行表头）

```
Row 1: 序号 | 链路名称 | A端(C-N合并) | Z端(O-Z合并)
Row 2:      |         | 网元(C-E) | 光模块(F-J) | 误码(K) | 带宽(L-N) | 网元(O-Q) | 光模块(R-V) | 误码(W) | 带宽(X-Z)
Row 3:      |         | 名称 | 类型 | 端口 | 类型 | TX(dBm) | RX(dBm) | TX状态 | RX状态 | RS错误秒 | 总(Mbps) | 已用(Mbps) | 利用率 | 名称 | 类型 | 端口 | 类型 | TX(dBm) | RX(dBm) | TX状态 | RX状态 | RS错误秒 | 总(Mbps) | 已用(Mbps) | 利用率
```

### 9.4 差异分析

#### 9.4.1 表头结构差异

| 项目 | Excel（目标） | 当前实现 |
|------|-------------|---------|
| 行数 | 3行（合并单元格） | 2行（扁平） |
| 第1行 | 序号 \| 链路名称 \| A端(C-N) \| Z端(O-Z) | 同 |
| 第2行 | 网元(C-E) \| 光模块(F-J) \| 误码(K) \| 带宽(L-N) \| ... | 无（扁平列名） |
| 第3行 | 名称 \| 类型 \| 端口 \| 类型 \| TX \| RX \| TX状态 \| RX状态 \| RS错误秒 \| 总 \| 已用 \| 利用率 \| ... | A端网元 \| A端网元类型 \| A端口 \| ... |

#### 9.4.2 字段差异

| 位置 | Excel | 当前 | 差异 |
|------|-------|------|------|
| 误码列名 | RS错误秒 | B1差错率 | 名称不同 |
| 误码值 | RS-ES / RS-SES / RS-UAS / 空 | "--"（硬编码） | 当前未采集，需预留 |
| TX状态值 | 正常/过高/过低/无光 | 正常/越上限/越下限 | 命名不同 |
| RX状态值 | 正常/过高/过低/无光 | 正常/越上限/越下限 | 命名不同 |
| 网元类型 | 类型（纯数字如2010） | 当前已 strip 前缀 | 一致 |
| 带宽利用率 | 数值（如0.6） | 带%后缀（0.60%） | 格式不同 |

#### 9.4.3 导出差异

| 项目 | Excel | 当前 |
|------|-------|------|
| Sheet | 2个（巡检结果 + 说明） | 1个 |
| 说明sheet | 门限参考表 | 无 |
| 表头 | 3行合并单元格 | 2行扁平 |
| 列宽 | 自适应 | autoSizeColumn |

#### 9.4.4 前端显示差异

| 项目 | Excel | 当前 query.js |
|------|-------|-------------|
| 表头 | 3行合并 | 2行扁平（updateTableHeader） |
| B1差错率列 | 显示 RS-ES/RS-SES/RS-UAS | 显示 "--" |
| 利用率格式 | 数值（0.6） | 带%（0.60%） |

#### 9.4.5 后端数据组装差异

| 项目 | 需改动 |
|------|--------|
| formatStatus() | 返回值改为 过高/过低/无光/正常 |
| aB1Error/zB1Error | 改为 rsError/zRsError，值预留 RS-ES/RS-SES/RS-UAS |
| loadBandwidthMap() | 新方案已冗余到 dmconnection，不再查 portbandwidth |
| loadNeNameMap() | 新方案直接读 dmeo，不再查 dmne |
| loadNeTypeNameMap() | 新方案直接读 dmeo.neTypeName，不再 JOIN dmne+defdmne |
| loadNetworkMap() | 新方案直接读 dmeo.networkName，不再查 dmrelation |
| 网络过滤 | 新方案直接用 dmeo.networkName，不需要 networkMap |

### 9.5 LinkInspectionResult 实体设计

LinkInspectionResult 作为 JPA 实体，直接映射 `link_inspection_result` 表。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 自增主键 |
| roundId | Long | 巡检轮次ID |
| linkOid | String | 链路OID |
| linkName | String | 链路名称 |
| createTime | LocalDateTime | 创建时间 |
| **A端** | | |
| aNeId | String | A端网元ID |
| aNeName | String | A端网元名称 |
| aNeTypeName | String | A端网元类型 |
| aPortOid | String | A端端口OID |
| aPortName | String | A端端口名称（拼接后） |
| aModuleType | String | A端光模块类型 |
| aTxPower | Double | A端发光功率(dBm) |
| aRxPower | Double | A端收光功率(dBm) |
| aTxStatus | String | A端发光状态（正常/过高/过低/无光） |
| aRxStatus | String | A端收光状态（正常/过高/过低/无光） |
| aRsErrorSec | Integer | A端RS错误秒 |
| aTotalBandwidth | Integer | A端总带宽(Mbps) |
| aUsedBandwidth | Integer | A端已用带宽(Mbps) |
| aBandwidthUsage | Double | A端带宽利用率 |
| aErrorInfo | String | A端采集错误信息 |
| **Z端** | | |
| zNeId | String | Z端网元ID |
| zNeName | String | Z端网元名称 |
| zNeTypeName | String | Z端网元类型 |
| zPortOid | String | Z端端口OID |
| zPortName | String | Z端端口名称（拼接后） |
| zModuleType | String | Z端光模块类型 |
| zTxPower | Double | Z端发光功率(dBm) |
| zRxPower | Double | Z端收光功率(dBm) |
| zTxStatus | String | Z端发光状态 |
| zRxStatus | String | Z端收光状态 |
| zRsErrorSec | Integer | Z端RS错误秒 |
| zTotalBandwidth | Integer | Z端总带宽(Mbps) |
| zUsedBandwidth | Integer | Z端已用带宽(Mbps) |
| zBandwidthUsage | Double | Z端带宽利用率 |
| zErrorInfo | String | Z端采集错误信息 |

### 9.6 InspectionService 改动

#### 9.6.1 formatStatus() 方法

```java
// 当前
private String formatStatus(Integer status) {
    return switch (status) {
        case 0 -> "正常";
        case 1 -> "越下限";
        case 2 -> "越上限";
        default -> "未知";
    };
}

// 改为
private String formatStatus(Integer status) {
    return switch (status) {
        case 0 -> "正常";
        case 1 -> "过低";
        case 2 -> "过高";
        default -> "无光";
    };
}
```

#### 9.6.2 getLinkResults() 方法

新方案下直接从 `link_inspection_result` 表读取，无需复杂查询：

```java
public List<LinkInspectionResult> getLinkResults(Long roundId, String network) {
    if (roundId != null) {
        return linkInspectionResultRepository.findByRoundId(roundId);
    }
    // 获取最新轮次
    return inspectionRoundRepository.findFirstByOrderByStartTimeDesc()
            .map(r -> linkInspectionResultRepository.findByRoundId(r.getId()))
            .orElse(Collections.emptyList());
}
```

#### 9.6.3 数据加载方法简化

新方案下 dmeo 表已冗余所有字段，以下方法可简化或移除：

| 方法 | 当前逻辑 | 新逻辑 |
|------|---------|--------|
| loadNeNameMap() | SELECT oid,name FROM dmeo WHERE cid=2 | 直接用 dmeo.networkName 字段 |
| loadNeTypeNameMap() | JOIN dmne+defdmne | 直接用 dmeo.neTypeName 字段 |
| loadNetworkMap() | JOIN dmrelation+dmeo | 直接用 dmeo.networkName 字段 |
| loadBandwidthMap() | SELECT FROM portbandwidth | 直接用 dmconnection 的冗余字段 |
| isPortInNetwork() | 通过 networkMap 查找 | 直接比较 networkName |

### 9.7 Excel 导出改动

#### 9.7.1 表头改为3行合并

```
Row 0: 序号(A0:A2) | 链路名称(B0:B2) | A端(C0:N0) | Z端(O0:Z0)
Row 1:              |                 | 网元(C1:E1) | 光模块(F1:J1) | 误码(K1:K1) | 带宽(L1:N1) | 网元(O1:Q1) | 光模块(R1:V1) | 误码(W1:W1) | 带宽(X1:Z1)
Row 2:              |                 | 名称 | 类型 | 端口 | 类型 | TX(dBm) | RX(dBm) | TX状态 | RX状态 | RS错误秒 | 总(Mbps) | 已用(Mbps) | 利用率 | 名称 | 类型 | 端口 | 类型 | TX(dBm) | RX(dBm) | TX状态 | RX状态 | RS错误秒 | 总(Mbps) | 已用(Mbps) | 利用率
```

#### 9.7.2 新增说明 Sheet

| 速率 | 光模块类型 | 发光过高门限 | 发光过低门限 | 收光过高门限 | 收光过低门限 |
|------|-----------|------------|------------|------------|------------|
| STM-1 | S1.1 | -8 | -15 | -8 | -28 |
| STM-1 | L1.1 | | -5 | -10 | -34 |
| ... | ... | ... | ... | ... | ... |

#### 9.7.3 利用率格式

导出时不带%后缀，直接输出数值（如 0.6 而非 0.60%）。

### 9.8 前端 query.js 改动

#### 9.8.1 updateTableHeader() 改为3行合并表头

```javascript
// Row 1: 序号(rowspan=3) | 链路名称(rowspan=3) | A端(colspan=12) | Z端(colspan=12)
// Row 2: 网元(colspan=3) | 光模块(colspan=5) | 误码 | 带宽(colspan=3) | ...
// Row 3: 名称 | 类型 | 端口 | 类型 | TX | RX | TX状态 | RX状态 | RS错误秒 | 总 | 已用 | 利用率 | ...
```

#### 9.8.2 renderLinkQueryTable() 适配新字段

- `r.aB1Error` → `r.aRsErrorSec`
- `r.zB1Error` → `r.zRsErrorSec`
- 利用率显示去掉%后缀，与Excel一致

### 9.9 代码修改范围

| 文件 | 操作 | 说明 |
|------|------|------|
| LinkInspectionResult.java | 重构 | 改为 JPA 实体，映射 link_inspection_result 表 |
| LinkInspectionResultRepository.java | 新增 | 新增Repository接口 |
| InspectionService.java | 重构 | getLinkResults() 简化为直接查询 |
| InspectionController.java | 修改 | exportLinkExcel 改为3行表头+说明sheet |
| query.js | 重构 | updateTableHeader 3行合并，renderLinkQueryTable 适配新字段 |

## 十、巡检任务采集功能改动

### 10.1 目标

将巡检从"以设备为主"改为"以链路为主"，采集光功率+性能数据，按链路存储结果。

### 10.2 核心变化

| 项目 | 当前 | 新方案 |
|------|------|--------|
| 巡检对象 | 设备（device_access_config） | 链路（dmconnection） |
| 端口来源 | 该设备所有光口 | 链路中涉及的端口 |
| 数据采集 | 仅光功率 | 光功率 + 性能（RS错误秒） |
| 结果存储 | optical_power_inspection（每端口一条） | link_inspection_result（每链路一条） |
| 端口名称 | 直接用 dmeo.name | 拼接：原始名称 + (子架/槽位/端口) |
| 带宽数据 | 巡检时查询 portbandwidth | 直接从 dmconnection 冗余字段读取 |

### 10.3 新表结构：link_inspection_result

```sql
CREATE TABLE "link_inspection_result" (
  "id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "round_id" INTEGER NOT NULL,
  "link_oid" TEXT,
  "link_name" TEXT,
  "create_time" TEXT,
  "a_ne_id" TEXT,
  "a_ne_name" TEXT,
  "a_ne_type_name" TEXT,
  "a_port_oid" TEXT,
  "a_port_name" TEXT,
  "a_module_type" TEXT,
  "a_tx_power" REAL,
  "a_rx_power" REAL,
  "a_tx_status" TEXT,
  "a_rx_status" TEXT,
  "a_rs_error_sec" INTEGER,
  "a_total_bandwidth" INTEGER,
  "a_used_bandwidth" INTEGER,
  "a_bandwidth_usage" REAL,
  "a_error_info" TEXT,
  "z_ne_id" TEXT,
  "z_ne_name" TEXT,
  "z_ne_type_name" TEXT,
  "z_port_oid" TEXT,
  "z_port_name" TEXT,
  "z_module_type" TEXT,
  "z_tx_power" REAL,
  "z_rx_power" REAL,
  "z_tx_status" TEXT,
  "z_rx_status" TEXT,
  "z_rs_error_sec" INTEGER,
  "z_total_bandwidth" INTEGER,
  "z_used_bandwidth" INTEGER,
  "z_bandwidth_usage" REAL,
  "z_error_info" TEXT
);
```

#### 字段说明

| 字段 | 类型 | 说明 | 来源 |
|------|------|------|------|
| round_id | INTEGER | 巡检轮次ID | inspection_round.id |
| link_oid | TEXT | 链路OID | dmconnection.oid |
| link_name | TEXT | 链路名称 | dmconnection.name |
| a_ne_id | TEXT | A端网元ID | 从 aEnd OID 解析 |
| a_ne_name | TEXT | A端网元名称 | dmeo cid=2.name |
| a_ne_type_name | TEXT | A端网元类型 | dmeo.neTypeName |
| a_port_oid | TEXT | A端端口OID | dmconnection.aEnd |
| a_port_name | TEXT | A端端口名称 | 拼接：dmeo.name + (子架/槽位/端口) |
| a_module_type | TEXT | A端光模块类型 | laserService 采集 |
| a_tx_power | REAL | A端发光功率(dBm) | laserService 采集 |
| a_rx_power | REAL | A端收光功率(dBm) | laserService 采集 |
| a_tx_status | TEXT | A端发光状态 | 正常/过高/过低/无光 |
| a_rx_status | TEXT | A端收光状态 | 正常/过高/过低/无光 |
| a_rs_error_sec | INTEGER | A端RS错误秒 | RS-ES + RS-SES + RS-UAS |
| a_total_bandwidth | INTEGER | A端总带宽(Mbps) | dmconnection.aCapacity |
| a_used_bandwidth | INTEGER | A端已用带宽(Mbps) | dmconnection.aUsed |
| a_bandwidth_usage | REAL | A端带宽利用率 | a_used_bandwidth / a_total_bandwidth |
| a_error_info | TEXT | A端采集错误信息 | 采集失败时记录 |
| z_* | | Z端字段 | 同A端 |

### 10.4 端口名称拼接规则

```
输入: portOid = "101:1:11:1", dmeo.name = "地调1-5.1"
解析: subrack = OidUtil.getSubrackId(portOid) = 1
      slot = OidUtil.getSlotId(portOid) = 11
      port = OidUtil.getPortId(portOid) = 1
输出: portName = "地调1-5.1(1/11/1)"
```

### 10.5 采集流程

```
1. 从 dmconnection 获取所有链路（已按网络过滤）
2. 提取所有涉及的网元OID（去重）
3. 按网元分组，每台网元连接一次
4. 查询该网元在链路中涉及的端口：
   a. 采集光功率（laser attribute get）
   b. 采集性能（RS-ES、RS-SES、RS-UAS，一次查询多个属性）
   c. RS错误秒 = RS-ES + RS-SES + RS-UAS
   d. 拼接端口完整名称（原始名称 + 子架/槽位/端口）
   e. 采集失败不中断，记录到 error_info
5. 读取带宽数据（从 dmconnection 冗余字段 aCapacity/aUsed/zCapacity/zUsed）
6. 按链路组装 A端+Z端 数据（含带宽）
7. 保存到 link_inspection_result 表
```

### 10.6 数据来源汇总

| 数据 | 来源 | 时机 |
|------|------|------|
| 链路信息 | dmconnection | 同步时写入 |
| 网元信息 | dmeo（冗余字段） | 同步时写入 |
| 带宽数据 | dmconnection（冗余字段） | 同步时写入 |
| 光功率 | QX 协议（laserService） | 巡检时实时采集 |
| 性能（RS错误秒） | QX 协议（perfService） | 巡检时实时采集 |
| 端口名称 | dmeo.name + OID 解析 | 巡检时拼接 |
| 门限数据 | threshold_rule | 巡检后应用 |

### 10.7 性能采集（PerfService）

#### 10.7.1 采集内容

| 性能指标 | 说明 | 单位 |
|---------|------|------|
| RS-ES | RS误码秒 | 秒 |
| RS-SES | RS严重误码秒 | 秒 |
| RS-UAS | RS不可用秒 | 秒 |
| RS错误秒 | RS-ES + RS-SES + RS-UAS | 秒 |

#### 10.7.2 采集方式

通过 QX 协议的性能查询接口，一次查询多个属性：

```java
// 伪代码
PerfData perf = perfService.getPerf(neId, slotId, portType, portId, 
    Arrays.asList("RS-ES", "RS-SES", "RS-UAS"));
int rsErrorSec = perf.getValue("RS-ES") + perf.getValue("RS-SES") + perf.getValue("RS-UAS");
```

#### 10.7.3 错误处理

- 单个性能指标查询失败：记录到 error_info，不影响其他指标
- 性能查询整体失败：记录到 error_info，rs_error_sec 设为 null

#### 10.7.4 代码位置

| 文件 | 说明 |
|------|------|
| PerfService.java | 性能采集服务（新增或适配） |
| PerfData.java | 性能数据实体（新增） |

### 10.8 错误处理

- 单端口采集失败：记录 error_info，不影响其他端口
- 单台网元连接失败：该网元下所有端口记录 error_info
- A端失败、Z端成功：链路仍保存，A端字段为空，error_info 记录原因

### 10.9 旧表处理

| 旧表 | 处理 |
|------|------|
| optical_power_inspection | 删除，不再使用 |
| portbandwidth | 数据已冗余到 dmconnection，可删除 |

### 10.9 代码修改范围

| 文件 | 操作 | 说明 |
|------|------|------|
| LinkInspectionResult.java | 重构 | 改为 JPA 实体，映射 link_inspection_result 表 |
| LinkInspectionResultRepository.java | 新增 | 新增Repository接口 |
| InspectionService.java | 重写 | 新增链路巡检逻辑，移除设备巡检逻辑 |
| InspectionController.java | 修改 | 适配新的巡检触发方式 |
| InspectionScheduler.java | 修改 | 适配新的巡检触发方式 |
| DeviceAccessService.java | 简化 | 移除设备巡检相关逻辑 |
| PerfService.java | 新增/适配 | 性能采集（RS-ES/RS-SES/RS-UAS） |
| ThresholdService.java | 修改 | 适配新表的门限应用 |
| progress.js | 修改 | 进度按链路数统计 |

### 10.10 与其他模块适配

| 模块 | 适配内容 |
|------|---------|
| 数据查询 | 改为从 link_inspection_result 读取 |
| 统计功能 | 基于 link_inspection_result 统计 |
| 导出 Excel | 从 link_inspection_result 直接导出 |

### 10.11 门限应用

#### 10.11.1 应用时机

巡检完成后，对 `link_inspection_result` 表中的光功率数据应用门限规则。

#### 10.11.2 应用逻辑

```java
// 伪代码
public void applyThresholds(Long roundId) {
    List<LinkInspectionResult> results = repository.findByRoundId(roundId);
    List<ThresholdRule> rules = thresholdRuleRepository.findAll();
    
    for (LinkInspectionResult r : results) {
        // A端门限应用
        if (r.getAModuleType() != null) {
            ThresholdRule rule = findRule(r.getAModuleType(), rules);
            if (rule != null) {
                r.setATxStatus(formatStatus(r.getATxPower(), rule.getTxLow(), rule.getTxHigh()));
                r.setARxStatus(formatStatus(r.getARxPower(), rule.getRxLow(), rule.getRxHigh()));
            }
        }
        // Z端门限应用（同理）
    }
    repository.saveAll(results);
}
```

#### 10.11.3 状态判断

| 状态 | 条件 |
|------|------|
| 正常 | 低门限 <= 值 <= 高门限 |
| 过低 | 值 < 低门限 |
| 过高 | 值 > 高门限 |
| 无光 | 值为 null 或 0 |

## 十一、实现步骤

1. **第一步**：修改 DynamicSyncService，实现新的同步逻辑
2. **第二步**：修改 SyncController，新增网络列表接口
3. **第三步**：修改前端 sync.js，支持网络多选
4. **第四步**：重构 InventoryStatsService
5. **第五步**：修改 InventoryStatsController
6. **第六步**：重构前端 stats.js
7. **第七步**：修改 index.html 统计页面
8. **第八步**：重构 DeviceAccessService
9. **第九步**：修改 DatabaseTestController
10. **第十步**：修改前端 device.js
11. **第十一步**：重构 ThresholdService
12. **第十二步**：修改 ThresholdRule 实体
13. **第十三步**：重构前端 threshold.js
14. **第十四步**：修改 index.html 门限配置页面
15. **第十五步**：新增 link_inspection_result 表实体
16. **第十六步**：重写 InspectionService（链路巡检逻辑、端口名称拼接、性能采集）
17. **第十七步**：适配/新增 PerfService（性能采集 RS-ES/RS-SES/RS-UAS）
18. **第十八步**：修改 InspectionController（适配新巡检触发）
19. **第十九步**：修改 InspectionScheduler（适配新巡检触发）
20. **第二十步**：重构数据查询（从 link_inspection_result 读取）
21. **第二十一步**：重构 InspectionController 导出（3行表头+说明sheet）
22. **第二十二步**：重构前端 query.js（3行合并表头+字段适配）
23. **第二十三步**：适配 BandwidthService
24. **第二十四步**：删除旧表（optical_power_inspection、portbandwidth）
25. **第二十五步**：测试验证
