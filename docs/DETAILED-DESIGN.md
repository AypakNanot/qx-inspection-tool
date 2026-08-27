# QX设备直连巡检工具 详细设计文档

> QX Device Direct-Connection Inspection Tool Software Detail Design

| 项目 | 内容 |
|---|---|
| 文档版本 | V1.0 |
| 编写日期 | 2026-08-27 |
| 编写人 | Rwj |
| 审核人 | - |
| 状态 | 草稿 |

---

## 1. 目的 Purpose

描述和定义模块内部的详细设计。

本文档描述QX设备直连巡检工具各模块的内部实现细节，包括数据结构、算法流程、接口定义、错误处理等，为开发人员提供编码依据，为测试人员提供测试用例设计依据。

## 2. 背景 Background

描述进入详细设计阶段的背景。

| 项目 | 说明 |
|---|---|
| 项目名称 | QX设备直连巡检工具 |
| 需求文档 | REQ-QX设备直连巡检工具.md V0.4 |
| 概要设计 | README.md（架构设计部分） |
| 当前阶段 | 详细设计 |
| 前置条件 | 概要设计评审通过，技术选型确定（Java 17 + Spring Boot 3.2 + SQLite + Qx SDK） |

## 3. 范围 Scope

列出有哪些人员使用本文档。

| 角色 | 用途 |
|---|---|
| 开发人员 | 编码实现依据 |
| 测试人员 | 测试用例设计依据 |
| 评审人员 | 技术评审参考 |
| 维护人员 | 后续维护参考 |

## 4. 名称解释 Items

解释文档中使用的专用名词和缩略语。

| 术语 | 说明 |
|---|---|
| Qx | 设备管理协议，TCP 9900，登录后命令交互 |
| NE / 网元 | 一台被管理设备（Network Element） |
| OID | 冒号分隔的层级对象标识（neId:subrack:slot:port:timeslot） |
| 0x2410 | 激光器属性查询命令码（CFG_Qx_LaserAttribute_Get） |
| bLaserType | 激光器速率类型字节：1=2.5G, 2=622M, 3=155M, 4=10G, 0x10=GE |
| bDistance | 距离档字节：1=I, 2=S, 3=L, 4=V; GE: 0x10=SX, 0x11=LX |
| moduleTypeKey | 模块类型组合键（如L16.1, S4.1, 1000BASE-SX） |
| 门限 | 光功率正常范围的上下限，用于判定劣化/过载 |
| WAL | SQLite的Write-Ahead Logging模式，支持读写并发 |

## 5. 模块内部 结构 Inner Architecture

给出模块的内部的结构框图，用图表列出模块的内部的每个子模块的名称、标识符和它们之间的层次结构关系。

### 5.1 结构框图

```
┌─────────────────────────────────────────────────────────────────┐
│                    QX设备直连巡检工具                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ 设备管理  │  │ 光功率巡检 │  │ 门限配置  │  │ 数据维护  │       │
│  │ F1       │  │ F3       │  │ F3.5     │  │ F5       │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
│       │             │             │             │               │
│  ┌────┴─────────────┴─────────────┴─────────────┴─────┐       │
│  │                    连接管理层                        │       │
│  │  QxConnectionService + QxReconnectManager          │       │
│  └──────────────────────┬─────────────────────────────┘       │
│                         │                                       │
│  ┌──────────────────────┴─────────────────────────────┐       │
│  │                    Qx协议层                         │       │
│  │  QxDeviceServiceImpl + ILaserService               │       │
│  └──────────────────────┬─────────────────────────────┘       │
│                         │                                       │
│  ┌──────────────────────┴─────────────────────────────┐       │
│  │                    数据层                           │       │
│  │  SQLite(主源) + MySQL(只读副源)                      │       │
│  └────────────────────────────────────────────────────┘       │
│                                                                 │
│  ┌────────────────────────────────────────────────────┐       │
│  │                    前端层                           │       │
│  │  SPA(侧边栏导航) + ES6模块 + ECharts图表           │       │
│  └────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 子模块清单

| 序号 | 模块名称 | 标识符 | 功能描述 | 依赖关系 |
|---|---|---|---|---|
| 1 | 设备发现与管理 | DEVICE_MGR | 从MySQL发现设备、连接配置、连接状态管理 | MySQL只读副源、QxConnectionService |
| 2 | Qx连接管理 | QX_CONN | 设备连接/断开、状态监听、断线重连 | QxDeviceServiceImpl |
| 3 | 光功率巡检 | INSPECT | 定时/手动触发巡检、逐端口采集、结果存储 | QxConnectionService、ILaserService |
| 4 | 门限判定 | THRESHOLD | 三级匹配（PART>MODULE>GLOBAL）、实时计算 | ThresholdRuleRepository |
| 5 | 数据查询与导出 | QUERY | 树形分组表格、筛选排序、Excel导出 | OpticalPowerInspectionRepository |
| 6 | 定时调度 | SCHEDULER | Cron定时触发、配置持久化 | InspectionService |
| 7 | 数据同步 | SYNC | MySQL→SQLite动态建表、批量同步 | DynamicSyncService |
| 8 | 统计分析 | STATS | 设备类型统计、趋势图表、异常汇总 | 各Repository |

### 5.3 模块内的全局数据定义 Data Definition

#### 5.3.1 全局数据结构定义 Structure Definition

描述全局的数据结构定义和功能说明。

| 结构名称 | 类型 | 字段说明 | 功能说明 |
|---|---|---|---|
| DeviceAccessConfig | JPA Entity | neId, neName, ipAddr, port, username, password, networkName, neTypeName, connectionStatus | 设备访问配置，含连接信息 |
| OpticalPowerInspection | JPA Entity | roundId, neId, neName, slotNo, portNo, portName, supported, laserType, laserDistance, moduleTypeKey, partNumber, txPower, rxPower, txPowerStatus, rxPowerStatus, inspectionTime | 巡检结果记录 |
| InspectionRound | JPA Entity | id, status, triggerType, scope, totalCount, doneCount, failCount, startTime, endTime | 巡检轮次元数据 |
| ThresholdRule | JPA Entity | levelType, matchKey, txLow, txHigh, rxLow, rxHigh, description | 门限规则 |
| SysConfig | JPA Entity | key, value | 系统配置KV存储 |
| LaserAttributeAckData | Protocol POJO | laserWave, laserState, laserType, distance, partNumber, supportFlag, recvLaserPower, tranLaserPower | 0x2410响应报文解析结果 |

#### 5.3.2 全局数据变量定义 Variable Definition

描述全局的数据变量定义和功能说明。

| 变量名 | 类型 | 作用域 | 初始值 | 功能说明 |
|---|---|---|---|---|
| channelIdMap | ConcurrentHashMap\<String, ChannelID\> | QxConnectionService | 空 | neOid → ChannelID映射缓存 |
| progressCurrent | AtomicInteger | InspectionService | 0 | 当前已完成设备数 |
| progressTotal | AtomicInteger | InspectionService | 0 | 总设备数 |
| progressCurrentNe | volatile String | InspectionService | "" | 当前正在采集的网元名 |
| progressFailures | List\<Map\> | InspectionService | 空 | 失败设备列表 |
| progressRunning | volatile boolean | InspectionService | false | 巡检是否正在运行 |
| expandedGroups | Set\<String\> | query.js前端 | new Set() | 展开的网元组（neId集合） |

## 6. 模块详细定义 Module Detail Design

### 6.1 设备发现与管理模块

#### 6.1.1 模块功能

从MySQL老库发现设备清单，管理设备连接配置（全局+单设备覆盖），提供连接/断开操作。

#### 6.1.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | 设备发现与管理 |
| 标识符 | DEVICE_MGR |
| 所属子系统 | 设备管理层 |
| 源文件 | DeviceAccessService.java, ConnectionController.java, device.js |

#### 6.1.3 接口的输入、输出特性

**输入接口：**

| 接口名称 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| POST /api/connection/connect-all | 无 | - | - | 全部连接 |
| POST /api/connection/disconnect-all | 无 | - | - | 全部断开 |
| POST /api/connection/connect/{neId} | neId | String | 是 | 单设备连接 |
| POST /api/connection/disconnect/{neId} | neId | String | 是 | 单设备断开 |
| POST /api/connection/config | config | JSON | 是 | 保存连接配置 |
| POST /api/connection/refresh | 无 | - | - | 刷新设备列表 |

**输出接口：**

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| GET /api/connection/status | List\<DeviceAccessConfig\> | 设备列表+连接状态 |
| GET /api/connection/config | Map | 全局连接配置 |
| GET /api/inventory/networks | List\<String\> | 网络列表 |

#### 6.1.4 全局和局部数据结构和参数

| 结构名称 | 字段 | 类型 | 说明 |
|---|---|---|---|
| DeviceAccessConfig | neId | String | 网元ID（主键） |
| | neName | String | 网元名称 |
| | ipAddr | String | 设备IP |
| | port | Integer | Qx端口（默认9900） |
| | username | String | 登录用户名 |
| | password | String | 登录密码 |
| | networkName | String | 所属网络 |
| | neTypeName | String | 设备类型名称 |
| | connectionStatus | String | 连接状态：ONLINE/OFFLINE/CONNECTING |

#### 6.1.5 工作流程

```
设备发现流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. 读取MySQL老库                         │
│    SELECT n.oid, n.type, d.cName,       │
│           c.ipAddr, r.reo, dn2.cName    │
│    FROM dmne n                           │
│    LEFT JOIN defdmne/EmNeComm/dmrelation │
│    LEFT JOIN dmnet/defdmnetwork          │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. 合并SQLite已有配置                     │
│    保留用户自定义的username/password/port  │
│    更新neName/ipAddr/networkName等       │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. 批量写入device_access_config          │
│    INSERT OR REPLACE（保留连接状态）      │
└─────────────────────────────────────────┘
  │
  ▼
结束
```

#### 6.1.6 存储分配

- **device_access_config表**: SQLite，存储设备清单+连接配置
- **conn_profile表**: SQLite，存储全局/单设备连接画像

#### 6.1.7 错误的命名和编码

| 错误码 | 错误名称 | 触发条件 | 处理方式 |
|---|---|---|---|
| E001 | MySQL连接失败 | MySQL容器未启动或网络断开 | 界面提示"清单加载失败"，不影响已缓存设备 |
| E002 | 设备连接超时 | 设备IP不可达或端口未开放 | 记录失败原因，跳过该设备 |
| E003 | 登录失败 | 用户名/密码错误 | 记录失败原因，跳过该设备 |

#### 6.1.8 界面资源设计

- 设备列表页：表格展示（网元名、IP、网络、类型、状态、操作）
- 连接配置弹窗：全局配置表单（用户名、密码、端口）
- 操作按钮：全部连接、全部断开、单设备连接/断开（带loading动效）

#### 6.1.9 限制条件

- MySQL老库只读，不写入任何数据
- 同一时刻仅允许一个连接/断开操作（防并发）
- 设备IP来源：EmNeComm表state=1的有效条目

---

### 6.2 Qx连接管理模块

#### 6.2.1 模块功能

管理Qx设备连接生命周期，包括连接建立、状态监听、断线重连。

#### 6.2.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | Qx连接管理 |
| 标识符 | QX_CONN |
| 所属子系统 | 连接管理层 |
| 源文件 | QxConnectionService.java, QxReconnectManager.java, QxDeviceServiceImpl.java |

#### 6.2.3 接口的输入、输出特性

**输入接口：**

| 接口名称 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| connectSingle | neId | String | 是 | 建立单设备连接 |
| disconnectSingle | neId | String | 是 | 断开单设备连接 |
| connectAll | 无 | - | - | 全部连接 |
| disconnectAll | 无 | - | - | 全部断开 |

**输出接口：**

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| connectSingle | Map\<String, Object\> | {success: boolean, message: String} |
| isConnected | boolean | 设备是否在线 |
| getChannelId | ChannelID | 设备通道ID |

#### 6.2.4 全局和局部数据结构和参数

| 结构名称 | 字段 | 类型 | 说明 |
|---|---|---|---|
| BackoffState | attempt | int | 当前重试次数 |
| | nextRetryMs | long | 下次重试时间 |
| | circuitOpen | boolean | 熔断是否开启 |
| | circuitOpenTime | long | 熔断开启时间 |

#### 6.2.5 工作流程

```
连接流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. 检查channelIdMap是否已有连接          │
│    有 → 直接返回成功                     │
│    无 → 继续                            │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. 构造ChannelProp（ip, port, user, pwd）│
│    注意：同IP:port已有连接时必须先shut()  │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. 调用manager.connect(channelId, prop)  │
│    超时5s，登录超时20s                   │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 4. 注册StateListener                    │
│    ONLINE → 更新状态为ONLINE             │
│    CLOSED → 触发重连管理器               │
└─────────────────────────────────────────┘
  │
  ▼
结束

断线重连流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. StateListener收到CLOSED事件           │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. 检查熔断状态                         │
│    连续失败≥5次 → 冷却300s              │
│    未熔断 → 继续                        │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. 计算退避时间                         │
│    base(5s) × 2^attempt + 抖动(±30%)    │
│    上限300s                             │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 4. 获取全局并发信号量（Semaphore=10）     │
│    获取成功 → 执行重连                   │
│    获取失败 → 等待                       │
└─────────────────────────────────────────┘
  │
  ▼
结束
```

#### 6.2.6 存储分配

- **channelIdMap**: 内存ConcurrentHashMap，neOid → ChannelID映射
- **states**: 内存ConcurrentHashMap，neOid → BackoffState重连状态

#### 6.2.7 错误的命名和编码

| 错误码 | 错误名称 | 触发条件 | 处理方式 |
|---|---|---|---|
| E010 | 连接超时 | 设备IP不可达 | 触发重连管理器 |
| E011 | 登录失败 | 用户名/密码错误 | 触发重连管理器 |
| E012 | 连接中断 | 网络异常或设备重启 | 触发重连管理器 |
| E013 | 熔断触发 | 连续失败≥5次 | 冷却300s后重试 |

#### 6.2.8 界面资源设计

- 设备列表页：连接状态列显示（ONLINE绿色/OFFLINE灰色/CONNECTING黄色）
- 单设备操作按钮：连接/断开（带loading动效）

#### 6.2.9 限制条件

- **ChannelID陷阱**: equals/hashCode只含ip+port，换用户必须先shut()再重连
- 全局并发重连数上限10（Semaphore控制）
- 进程退出必须manager.stop()干净登出
- 单设备串行逐口采集，避免压垮设备

---

### 6.3 光功率巡检模块

#### 6.3.1 模块功能

定时或手动触发巡检，逐端口采集光功率，存储结果到SQLite。

#### 6.3.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | 光功率巡检 |
| 标识符 | INSPECT |
| 所属子系统 | 巡检核心 |
| 源文件 | InspectionService.java, InspectionController.java, InspectionScheduler.java |

#### 6.3.3 接口的输入、输出特性

**输入接口：**

| 接口名称 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| POST /api/inspection/start | network | String | 否 | 指定网络 |
| | neId | String | 否 | 指定网元 |
| POST /api/inspection/collect-params | concurrency | Integer | 否 | 并发数 |
| | maxRounds | Integer | 否 | 保留轮次 |
| | autoConnect | Boolean | 否 | 自动连接 |
| | autoDisconnect | Boolean | 否 | 自动断开 |

**输出接口：**

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| GET /api/inspection/progress | Map | {progress, total, currentNe, failures, running} |
| GET /api/inspection/results | List\<OpticalPowerInspection\> | 巡检结果列表 |
| GET /api/inspection/rounds | List\<InspectionRound\> | 轮次列表 |
| GET /api/inspection/export | Excel文件 | 导出xlsx |

#### 6.3.4 全局和局部数据结构和参数

| 结构名称 | 字段 | 类型 | 说明 |
|---|---|---|---|
| InspectionRound | id | Long | 轮次ID（自增） |
| | status | String | RUNNING/COMPLETED |
| | triggerType | String | MANUAL/SCHEDULE |
| | scope | String | ALL/NETWORK/NE |
| | totalCount | Integer | 总设备数 |
| | doneCount | Integer | 已完成数 |
| | failCount | Integer | 失败数 |
| | startTime | LocalDateTime | 开始时间 |
| | endTime | LocalDateTime | 结束时间 |
| OpticalPowerInspection | roundId | Long | 轮次ID |
| | neId/neName | String | 网元信息 |
| | slotNo/portNo | Integer | 槽位/端口号 |
| | supported | Boolean | 是否支持光功率 |
| | txPower/rxPower | Double | 收发光功率(dBm) |
| | txPowerStatus/rxPowerStatus | Integer | 0=正常,1=越下限,2=越上限 |
| | moduleTypeKey | String | 模块类型键（门限匹配用） |
| | inspectionTime | LocalDateTime | 采集时间 |

#### 6.3.5 工作流程

```
巡检主流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. 检查是否已有巡检在跑                   │
│    是 → 返回错误                         │
│    否 → 继续                            │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. 创建轮次记录                          │
│    InspectionRound(status=RUNNING)       │
│    设置triggerType/scope/totalCount      │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. 按范围解析目标网元清单                 │
│    ALL → 全部设备                        │
│    NETWORK → 指定网络下的设备            │
│    NE → 指定网元                         │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 4. 线程池并发逐网元（concurrency=10）    │
│    ┌─────────────────────────────────┐  │
│    │ a. 检查连接状态                  │  │
│    │    未连接+autoConnect=true → 连接│  │
│    │    未连接+autoConnect=false → 跳过│  │
│    │    已连接 → 继续                 │  │
│    └──────────────┬──────────────────┘  │
│                   │                      │
│    ┌──────────────▼──────────────────┐  │
│    │ b. 查询光口端口                  │  │
│    │    SELECT * FROM dmeo            │  │
│    │    WHERE oid LIKE 'neOid:%'      │  │
│    │    AND cid=5                     │  │
│    │    AND defName LIKE 'STM%'       │  │
│    │    OR defName LIKE 'GE%' ...     │  │
│    └──────────────┬──────────────────┘  │
│                   │                      │
│    ┌──────────────▼──────────────────┐  │
│    │ c. 单设备串行逐口采集            │  │
│    │    从OID提取slotId/portId        │  │
│    │    发送0x2410激光器属性查询       │  │
│    │    解析响应：功率值+模块属性      │  │
│    │    构建OpticalPowerInspection    │  │
│    └──────────────┬──────────────────┘  │
│                   │                      │
│    ┌──────────────▼──────────────────┐  │
│    │ d. 批量写入SQLite               │  │
│    │    powerRecordRepository         │  │
│    │    .saveAll(records)             │  │
│    └─────────────────────────────────┘  │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 5. 全部完成                             │
│    autoDisconnect=true → 遍历断开连接   │
│    更新轮次状态(COMPLETED)              │
│    清理超龄轮次                         │
└─────────────────────────────────────────┘
  │
  ▼
结束
```

#### 6.3.6 存储分配

- **inspection_round表**: SQLite，轮次元数据
- **optical_power_inspection表**: SQLite，巡检结果（核心表）
- **sys_config表**: SQLite，采集参数配置（concurrency, maxRounds, autoConnect, autoDisconnect）

#### 6.3.7 错误的命名和编码

| 错误码 | 错误名称 | 触发条件 | 处理方式 |
|---|---|---|---|
| E020 | 设备未连接 | autoConnect=false且设备离线 | 记录"设备未连接"，跳过该设备 |
| E021 | 连接失败 | 设备IP不可达或登录失败 | 记录失败原因，跳过该设备 |
| E022 | 端口查询失败 | dmeo表无光口端口 | 记录日志，跳过该设备 |
| E023 | 激光器查询超时 | 0x2410命令无响应 | 记录失败端口，继续下一口 |
| E024 | 不支持光功率 | bSupportFlag bit0=0 | supported=false，界面显示"--" |
| E025 | 巡检已运行 | 重复触发 | 返回错误，不创建新轮次 |

#### 6.3.8 界面资源设计

- 任务配置页：采集参数面板（并发数、保留轮次、自动连接/断开开关）
- 任务进度页：进度条、当前网元、失败列表
- 数据查询页：树形分组表格、筛选、排序、搜索、导出

#### 6.3.9 限制条件

- 同一时刻仅允许一个巡检任务实例
- 单设备串行逐口采集（避免压垮设备）
- 多设备并发数可配（默认10）
- 超龄轮次自动清理（默认保留10轮）

---

### 6.4 门限判定模块

#### 6.4.1 模块功能

三级匹配（PART>MODULE>GLOBAL），查询时实时计算光功率判定结果。

#### 6.4.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | 门限判定 |
| 标识符 | THRESHOLD |
| 所属子系统 | 巡检核心 |
| 源文件 | ThresholdService.java, threshold.js |

#### 6.4.3 接口的输入、输出特性

**输入接口：**

| 接口名称 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| applyThresholds | records | List\<OpticalPowerInspection\> | 是 | 巡检结果列表 |
| POST /api/inspection/thresholds | rule | ThresholdRule | 是 | 门限规则 |

**输出接口：**

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| applyThresholds | List\<OpticalPowerInspection\> | 带判定结果的列表 |
| GET /api/inspection/thresholds | List\<ThresholdRule\> | 门限规则列表 |
| GET /api/inspection/thresholds/snapshot | Map | 门限快照 |

#### 6.4.4 全局和局部数据结构和参数

| 结构名称 | 字段 | 类型 | 说明 |
|---|---|---|---|
| ThresholdRule | levelType | String | GLOBAL/MODULE/PART |
| | matchKey | String | 匹配键（如L16.1, XXXX型号编码） |
| | txLow/txHigh | Double | 发送低/高门限 dBm |
| | rxLow/rxHigh | Double | 接收低/高门限 dBm |
| | description | String | 说明 |

#### 6.4.5 工作流程

```
门限匹配流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. 加载所有门限规则到HashMap             │
│    key = "levelType:matchKey"           │
│    例: "MODULE:L16.1", "PART:XXXX"      │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. 对每条巡检记录，按优先级匹配：        │
│                                         │
│ 优先级1: PART级（精确匹配）              │
│   key = "PART:" + record.partNumber     │
│   找到 → 使用该规则                     │
│   未找到 → 继续                         │
│                                         │
│ 优先级2: MODULE级（类型匹配）            │
│   key = "MODULE:" + record.moduleTypeKey│
│   找到 → 使用该规则                     │
│   未找到 → 继续                         │
│                                         │
│ 优先级3: GLOBAL级（全局默认）            │
│   key = "GLOBAL:GLOBAL"                 │
│   使用默认规则                          │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. 应用门限判定                         │
│    txPower < txLow → status=1(越下限)   │
│    txPower > txHigh → status=2(越上限)  │
│    其余 → status=0(正常)                │
│    rxPower同理                          │
└─────────────────────────────────────────┘
  │
  ▼
结束
```

#### 6.4.6 存储分配

- **threshold_rule表**: SQLite，门限规则配置

#### 6.4.7 错误的命名和编码

| 错误码 | 错误名称 | 触发条件 | 处理方式 |
|---|---|---|---|
| E030 | 规则重复 | 同levelType+matchKey多条规则 | 警告日志，使用最新一条 |
| E031 | 门限为空 | 无任何规则配置 | 使用默认值（rx:[-28,-8], tx:[-6,0]） |

#### 6.4.8 界面资源设计

- 门限配置页：三级配置面板（全局默认、按模块类型、按型号编码）
- 数据查询页：状态列标色（正常绿色、劣化红色、过载橙色）

#### 6.4.9 限制条件

- 门限不持久化到巡检记录，查询时实时计算
- 模块类型键格式必须与老网管一致（L16.1, S4.1等）
- 门限配置即时生效，无需重新采集

---

### 6.5 数据查询与导出模块

#### 6.5.1 模块功能

树形分组表格展示巡检结果，支持筛选、排序、搜索、分页、Excel导出。

#### 6.5.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | 数据查询与导出 |
| 标识符 | QUERY |
| 所属子系统 | 数据展示 |
| 源文件 | InspectionController.java, query.js |

#### 6.5.3 接口的输入、输出特性

**输入接口：**

| 接口名称 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| GET /api/inspection/results | roundId | Long | 否 | 指定轮次 |
| | network | String | 否 | 指定网络 |
| | neId | String | 否 | 指定网元 |
| GET /api/inspection/export | roundId | Long | 否 | 指定轮次 |
| | network | String | 否 | 指定网络 |

**输出接口：**

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| GET /api/inspection/results | List\<OpticalPowerInspection\> | 巡检结果列表 |
| GET /api/inspection/export | Excel文件 | xlsx格式 |

#### 6.5.4 全局和局部数据结构和参数

前端树形分组结构：

```
Map<neId, List<OpticalPowerInspection>>
  ├── neId1 → [port1, port2, ...]  // 父行：汇总信息
  │     ├── port1 → {slotNo, portNo, portName, ...}  // 子行：端口详情
  │     └── port2 → {...}
  └── neId2 → [port1, port2, ...]
```

#### 6.5.5 工作流程

```
数据查询流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. 从数据库查询原始数据                  │
│    按roundId/network/neId筛选           │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. 应用门限判定（实时计算）              │
│    thresholdService.applyThresholds()   │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. 前端筛选                             │
│    按状态/搜索文本筛选                   │
│    expandedGroups = new Set()（重置）    │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 4. 排序                                 │
│    先按neName分组排序                    │
│    组内按sortField+sortOrder排序        │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 5. 按网元组分页                         │
│    pageSize = 每页网元组数（10/20/50/100）│
│    start = (currentPage-1) × pageSize   │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 6. 渲染树形表格                         │
│    父行：arrowTd + nameTd(colSpan=3)    │
│          + statusTd(colSpan=2)          │
│          + 4空cell + inspectionTime     │
│    子行：indentTd + slotNo + portNo     │
│          + portName + laserWave         │
│          + txPower + rxPower + status   │
│          + thresholdTx + thresholdRx    │
│          + inspectionTime（共11列）      │
└─────────────────────────────────────────┘
  │
  ▼
结束
```

#### 6.5.6 存储分配

- 查询结果全部来自SQLite数据库
- 导出文件为xlsx格式，使用Apache POI生成

#### 6.5.7 错误的命名和编码

| 错误码 | 错误名称 | 触发条件 | 处理方式 |
|---|---|---|---|
| E040 | 无数据 | 查询结果为空 | 显示"暂无数据" |
| E041 | 导出失败 | 数据量过大或POI异常 | 提示错误信息 |

#### 6.5.8 界面资源设计

- 数据查询页：树形表格、筛选面板、分页控件、导出按钮
- 表格列：网元名、槽位、端口、端口名称、波长、发送功率、接收功率、状态、门限(发送)、门限(接收)、巡检时间

#### 6.5.9 限制条件

- 分页按网元组分页（非单条记录）
- 导出文件大小受服务器内存限制
- 搜索支持：neName, neId, portName, laserWave, slotNo, portNo

---

### 6.6 定时调度模块

#### 6.6.1 模块功能

Cron定时触发巡检，配置持久化到SQLite。

#### 6.6.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | 定时调度 |
| 标识符 | SCHEDULER |
| 所属子系统 | 巡检核心 |
| 源文件 | InspectionScheduler.java, task.js |

#### 6.6.3 接口的输入、输出特性

**输入接口：**

| 接口名称 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| POST /api/inspection/schedule/toggle | enabled | Boolean | 是 | 启用/禁用 |
| POST /api/inspection/schedule/config | enabled | Boolean | 是 | 启用状态 |
| | scope | String | 是 | ALL/NETWORK |
| | network | String | 否 | 指定网络 |
| | cronExpression | String | 是 | Cron表达式 |

**输出接口：**

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| GET /api/inspection/schedule | Map | {enabled, scope, network, cronExpression, lastRunStatus, lastRunTime} |

#### 6.6.4 全局和局部数据结构和参数

| 结构名称 | 字段 | 类型 | 说明 |
|---|---|---|---|
| SysConfig (schedule.*) | schedule.enabled | String | "true"/"false" |
| | schedule.scope | String | ALL/NETWORK |
| | schedule.network | String | 网络名 |
| | schedule.cron | String | Cron表达式 |
| | schedule.lastRunStatus | String | NEVER/RUNNING/SUCCESS/FAILED |
| | schedule.lastRunTime | String | 上次运行时间 |

#### 6.6.5 工作流程

```
定时调度流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. 应用启动时加载配置                    │
│    从sys_config读取schedule.*配置        │
│    enabled=true → 注册CronTrigger       │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. Cron触发时执行                        │
│    检查是否有巡检在跑                    │
│    是 → 跳过                            │
│    否 → 调用inspectionService            │
│         .triggerInspectionAll()         │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. 更新运行状态                          │
│    lastRunStatus = SUCCESS/FAILED       │
│    lastRunTime = 当前时间               │
│    持久化到sys_config                    │
└─────────────────────────────────────────┘
  │
  ▼
结束
```

#### 6.6.6 存储分配

- **sys_config表**: SQLite，定时配置KV存储

#### 6.6.7 错误的命名和编码

| 错误码 | 错误名称 | 触发条件 | 处理方式 |
|---|---|---|---|
| E050 | Cron表达式无效 | 格式错误 | 拒绝保存，提示错误 |
| E051 | 巡检冲突 | 定时触发时已有巡检在跑 | 跳过本次 |

#### 6.6.8 限制条件

- 同一时刻仅允许一个定时任务
- 配置变更即时生效（重新注册CronTrigger）

---

### 6.7 数据同步模块

#### 6.7.1 模块功能

MySQL→SQLite动态建表、批量同步设备数据。

#### 6.7.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | 数据同步 |
| 标识符 | SYNC |
| 所属子系统 | 数据维护 |
| 源文件 | DynamicSyncService.java, SyncController.java, sync.js |

#### 6.7.3 接口的输入、输出特性

**输入接口：**

| 接口名称 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| POST /api/sync/essential | 无 | - | - | 同步必要表 |
| POST /api/sync/all | 无 | - | - | 同步全部表 |
| POST /api/sync/clear | tables | List\<String\> | 否 | 清空指定表 |

**输出接口：**

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| GET /api/sync/status | Map | 各表同步状态 |

#### 6.7.4 工作流程

```
同步流程:
开始
  │
  ▼
┌─────────────────────────────────────────┐
│ 1. 读取MySQL表结构                      │
│    SHOW COLUMNS FROM {table}            │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. 映射类型到SQLite                     │
│    varchar/char/text → TEXT             │
│    int/bigint → INTEGER                 │
│    float/double → REAL                  │
│    datetime/timestamp → TEXT            │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. SQLite建表                           │
│    CREATE TABLE IF NOT EXISTS {table}   │
│    （排除巡检内部表）                    │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 4. 批量读取MySQL数据                    │
│    SELECT * FROM {table}                │
│    分批（5000条/批）                     │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 5. 批量写入SQLite                       │
│    INSERT OR REPLACE INTO {table}       │
│    事务保护                             │
└─────────────────────────────────────────┘
  │
  ▼
结束
```

#### 6.7.5 存储分配

- 同步的数据存储在SQLite对应表中
- 排除的表：device_access_config, conn_profile, inspection_round, optical_power_inspection, threshold_rule, sys_config

#### 6.7.6 错误的命名和编码

| 错误码 | 错误名称 | 触发条件 | 处理方式 |
|---|---|---|---|
| E060 | MySQL不可用 | 容器未启动 | 提示"同步失败" |
| E061 | 表不存在 | MySQL中无此表 | 跳过该表 |
| E062 | 编码错误 | GBK转换异常 | 记录日志，继续 |

#### 6.7.7 限制条件

- MySQL只读，不写入
- 同步为按需操作，不自动同步
- 排除巡检内部表

---

### 6.8 统计分析模块

#### 6.8.1 模块功能

设备类型统计、趋势图表、异常汇总。

#### 6.8.2 定义模块名称

| 项目 | 内容 |
|---|---|
| 模块名称 | 统计分析 |
| 标识符 | STATS |
| 所属子系统 | 数据展示 |
| 源文件 | StatsController.java, InventoryStatsController.java, stats.js |

#### 6.8.3 接口的输入、输出特性

| 接口名称 | 返回类型 | 说明 |
|---|---|---|
| GET /api/inventory/stats | Map | 按类型统计数量/占比 |
| GET /api/inspection/trend/port | List | 单端口历史趋势 |
| GET /api/inspection/trend/ne | List | 网元历史趋势 |
| GET /api/inspection/anomaly/summary | List | 异常汇总（按网元分组） |
| GET /api/inspection/anomaly/details | List | 异常详细记录 |

## 7. 可靠性设计 Reliability Design

预计开发规模和故障数，定义功能实现文件的命名。

### 7.1 开发规模预估

| 模块 | 预估代码行数 | 预估故障数 | 说明 |
|---|---|---|---|
| 设备发现与管理 | ~500 | 5 | 含MySQL查询+配置持久化 |
| Qx连接管理 | ~600 | 8 | 含重连管理器+状态监听 |
| 光功率巡检 | ~800 | 10 | 核心模块，逻辑复杂 |
| 门限判定 | ~200 | 2 | 逻辑简单 |
| 数据查询与导出 | ~400 | 5 | 含前端树形表格 |
| 定时调度 | ~150 | 2 | 配置持久化 |
| 数据同步 | ~300 | 3 | 动态建表+批量写入 |
| 前端（全部） | ~2000 | 15 | SPA+ES6模块 |
| **合计** | **~4950** | **~50** | - |

### 7.2 文件命名规范

| 文件类型 | 命名规则 | 示例 |
|---|---|---|
| Java实体 | 大驼峰，表名转类名 | DeviceAccessConfig.java |
| Java服务 | 大驼峰 + Service后缀 | InspectionService.java |
| Java控制器 | 大驼峰 + Controller后缀 | InspectionController.java |
| Java工具类 | 大驼峰 + Util后缀 | OidUtil.java |
| 前端JS模块 | 小驼峰 | query.js, device.js |
| YAML Schema | 小写 | laser.yaml, device.yaml |

## 8. 难点和风险点分析 Difficulty & Risk

分析系统尚存的技术难点和系统开发风险点，给出开发风险点的应对措施，列出尚未确定但必须在系统完成之前解决的问题和系统存在的限制。

### 8.1 技术难点

| 序号 | 难点描述 | 影响范围 | 应对措施 |
|---|---|---|---|
| 1 | ChannelID的equals/hashCode只含ip+port，换用户连接时行为异常 | 连接管理 | 先shut()再重连，代码中明确注释 |
| 2 | SQLite单写限制，并发写入会锁冲突 | 巡检写入 | 连接池设为1，WAL模式，事务按网元分批 |
| 3 | MySQL老库GBK编码，JDBC连接必须指定 | 数据同步 | characterEncoding=GBK，表名小写 |
| 4 | 0x2410报文解析精度（float dBm） | 采集精度 | Float.intBitsToFloat转换，0xFFFFFFFF处理 |
| 5 | 门限实时计算性能（万级数据） | 查询性能 | HashMap索引，批量处理 |

### 8.2 风险点

| 序号 | 风险描述 | 风险等级 | 影响范围 | 应对措施 | 责任人 |
|---|---|---|---|---|---|
| 1 | UDP 9910端口冲突（同机部署） | 高 | 连接层 | 绕过TCPChannel.initialize()，不启UDP | Rwj |
| 2 | 设备会话残留（进程异常退出） | 中 | 设备侧 | @PreDestroy调用manager.stop() | Rwj |
| 3 | 数据量增长（单轮4万行×10轮） | 中 | 存储 | WAL+索引，定期清理超龄轮次 | Rwj |
| 4 | 老库不可用（Docker停止） | 低 | 设备发现 | 不影响已缓存设备，界面提示重试 | Rwj |

### 8.3 待解决问题

| 序号 | 问题描述 | 优先级 | 要求解决时间 | 负责人 |
|---|---|---|---|---|
| 1 | 操作员登录权限（F1.4 P2） | 低 | 后续迭代 | Rwj |
| 2 | CSV导出（F3.6 P2） | 低 | 后续迭代 | Rwj |
| 3 | 劣化趋势对比上一轮（P2） | 低 | 后续迭代 | Rwj |

### 8.4 系统限制

| 序号 | 限制描述 | 影响范围 | 说明 |
|---|---|---|---|
| 1 | 与老网管使用不同用户登录 | 连接层 | 避免单用户登录冲突 |
| 2 | 不启用UDP 9910 | 连接层 | 同机部署，绕过dying-gasp监听 |
| 3 | SQLite单写 | 存储层 | 连接池1，WAL模式读写分离 |
| 4 | MySQL只读 | 数据同步 | 不写入老库任何数据 |
| 5 | 设备千级、端口万级 | 全局 | 缓存和查询分页需优化 |

---

*文档版本: V1.0*
*编写日期: 2026-08-27*
