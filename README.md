# QX设备直连巡检工具

## 预计开发工时

| 阶段 | 主要工作 | 预估工时 |
|---|---|---|
| M1 连通性 | 项目初始化、QxChannelManager接入、连接管理、断线重连 | 2天 |
| M2 统计 | 设备类型统计端点 | 0.5天 |
| M3 巡检核心 | 光功率巡检（0x2410采集+门限判定+查询导出） | 3天 |
| M4 定时调度 | 定时巡检+进度展示+摘要统计 | 1天 |
| M5 前端+趋势 | 前端仪表盘+趋势/异常API | 2天 |
| 协议层重构 | YAML schema+代码生成+OID段位修正+端口查询优化 | 2天 |
| 前端SPA化 | 侧边栏SPA重构+库存统计+时钟拓扑 | 1.5天 |
| 数据同步 | MySQL→SQLite动态同步+Toast通知+定时配置持久化 | 1.5天 |
| 前端模块化 | ES6模块拆分+分页排序筛选+SQLite迁移+UX优化 | 1.5天 |
| 功能增强 | 自动连接/断开配置+树形分组表格+老网管激光器格式 | 2天 |
| 文档 | README重写（完整需求规格+约束+特殊逻辑） | 0.5天 |
| **合计** | **从零到完整可用工具** | **约17.5天（3.5周）** |

## 1. 背景与目标

运维需要一个独立工具，绕开网管直接操作设备。利用"设备按用户名限制登录"的特性，使用独立用户登录，与老网管互不影响。

**核心场景**：
1. 批量连接设备并统一管理连接
2. 统计设备类型分布
3. 定期巡检端口光功率（耗时任务），结果可查、可筛、可导出

**与老网管的关系**：完全独立进程，使用专属设备用户登录，不干扰老网管的会话。

## 2. 技术栈

| 组件 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.2.0 |
| Qx协议SDK | opt-qx-cci-core | Netty 4.1, TCP 9900 |
| 主数据库 | SQLite | WAL模式，连接池1 |
| 老库（只读） | MySQL | 5.6, GBK编码 |
| 前端 | 原生HTML/CSS/JS | ES6模块 |
| 图表 | ECharts | 5.x |
| 构建 | Maven | - |

## 3. 术语

| 术语 | 说明 |
|---|---|
| Qx | 设备管理协议，TCP 9900，登录后命令交互 |
| NE / 网元 | 一台被管理设备 |
| 网络 | 网元的分组（对应库中 dmnet） |
| 光功率 | 光口的收/发光功率，单位 dBm |
| 巡检 | 周期性批量采集光功率的任务 |
| 门限 | 光功率正常范围的上下限，用于判定劣化/过载 |
| OID | 冒号分隔的层级对象标识（neId:subrack:slot:port:timeslot） |

## 4. 总体架构

```
┌──────────────────────────────────────────────┐
│              巡检工具（独立进程）              │
│                                              │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐  │
│  │ Web界面  │  │ 巡检调度器 │  │  数据缓存   │  │
│  └────┬────┘  └────┬─────┘  └──────┬─────┘  │
│       │            │               │        │
│  ┌────┴────────────┴───────────────┴─────┐  │
│  │        连接管理（防雪崩：限流+退避）      │  │
│  └────────────────────┬──────────────────┘  │
└───────────────────────┼──────────────────────┘
                        │ Qx (TCP:9900, 独立用户)
              ┌─────────┼─────────┐
              ▼         ▼         ▼
           设备1      设备2     设备N
                        ▲
                        │ 只读查询（设备清单）
                  ┌─────┴─────┐
                  │ MySQL 老库 │
                  │ (dmne等)   │
                  └───────────┘
```

## 5. 功能需求

优先级：P0 = 首版必须，P1 = 首版尽量，P2 = 后续迭代。

### F1 设备接入管理

#### F1.1 设备发现（P0）
- 从老库读取设备清单：`DmNe`（oid、type、commuState、location）、`DmNet`（网络）、`DmRelation`（网元-网络归属）
- 设备 IP 来源：`EmNeComm` 表（oid, ipAddr, state），取 state=1 的有效条目（多条时界面可选）
- 设备类型编码 → 名称映射：`DefDmNe`（neType → cName）；网络类型名：`DefDmNetwork`（type → cName）
- 支持手动刷新设备列表

#### F1.2 统一连接配置（P0）
- **全局默认配置**：一套用户名/密码/端口（默认 9900），用于所有设备一键连接
- **单设备覆盖**：允许对个别设备单独指定用户/密码/端口
- 独立用户账号，与老网管所用账号不同（避免单用户登录冲突）
- 配置持久化（SQLite），重启进程后保留

#### F1.3 连接管理（P0）
- 一键全部连接/断开；单设备连接/断开（带loading动效）
- 设备列表页展示：网元名、IP、端口、所属网络、设备类型、连接状态（在线/离线/连接中）、登录用户
- 断线自动重连：指数退避 + 抖动 + 熔断 + 全局并发上限
- 连接统计：在线数/离线数/总数

### F2 设备类型统计（P0）
- 按设备类型统计数量：类型名称 | 数量 | 占比
- 统计口径可选：库存口径（库里有几台）/ 在线口径（当前连通几台）
- 图表：饼图/柱状图
- 支持按网络筛选后再统计

### F3 光功率巡检任务

#### F3.1 任务配置（P0）
- 采集周期可配置（Cron或间隔）
- 采集范围可选：全网 / 指定网络 / 指定网元
- 支持手动触发一次立即巡检
- 同一时刻仅允许一个巡检任务实例

#### F3.2 采集执行（P0）
- **数据来源**：C19 激光器属性查询命令 **0x2410**（`CFG_Qx_LaserAttribute_Get`）
  - `fRecvLaserPower` / `fTranLaserPower`：float 精度 dBm（0.1）
  - `bSupportFlag` 能力位：bit0=1 支持光功率查询，0=不支持（显示 `--`）
  - 同报文自带光模块属性：`bLaserType`（速率）、`bDistance`（距离档）、`bLaserWave`（波长）、`bmPartNumber`（型号编码）
- 逐端口查询（报文按槽位+端口单端口返回）
- **无效值处理**：`bSupportFlag` bit0=0 或功率值异常 → 记录为"不支持"，界面显示 `--`，不算失败
- 离线/登录失败设备：跳过并记录，不阻塞其他设备
- **限速**：单设备串行逐口查；多设备并发数可配（默认 ≤ 10）
- 采集进度可见：已完成网元数/总数、当前正在采集的网元、失败列表

#### F3.3 数据缓存（P0）
- 巡检结果写入 SQLite，**保留最近 N 轮（默认 10，可配）**，超龄轮次自动清理
- 界面默认查询最新一轮，支持切换查看历史轮次
- 每条记录：轮次号、网络、网元、设备类型、槽位、端口、端口类型、接收光功率、发送光功率、是否支持、采集时间

#### F3.4 查询与筛选（P0）
- 界面查询缓存数据，**明确展示数据更新时间**
- 筛选维度：范围（全网/网络/网元）、端口类型、是否支持、判定状态
- **判定状态**：正常 / 劣化 / 过载 / 无效（基于门限）
- 列表列：网络 | 网元 | 设备类型 | 槽位/端口 | 端口类型 | 接收功率 | 发送功率 | 判定状态 | 采集时间
- 导出与筛选结果一致

#### F3.5 光功率门限判定（P0，工具侧）

**背景**：现网设备不支持性能越限上报，设备侧门限也无法查询，因此**门限判定完全由工具侧承担**。

**门限配置——按光模块类型定义**：
- 光模块类型来源：`bLaserType`（速率）+ `bDistance`（距离档）组合，如 `L16.1`、`S4.1`、`1000BASE-SX`
- `bmPartNumber`（型号编码）一并入库，可用于精确匹配
- 门限表按模块类型逐行配置，每行 4 个值：接收下限/上限、发送下限/上限
- **匹配优先级**：`bmPartNumber` 精确匹配 > `moduleTypeKey` 类型匹配 > 全局默认
- 全局默认门限（兜底）：接收 [-28, -8] dBm、发送 [-6, 0] dBm
- 配置界面即时生效，**无需重新采集**（判定与数据分离）

**判定规则**：

| 条件 | 状态 | 显示 |
|---|---|---|
| bSupportFlag=0 或功率值无效 | 无效 | `--`，灰色 |
| 接收功率 < 接收下限 | 劣化 | 红色 |
| 接收功率 > 接收上限 | 过载 | 橙色 |
| 发送功率超出对应上下限 | 劣化/过载 | 同色，状态注明发送侧 |
| 其余 | 正常 | 绿色 |

**数据存储注意**：`optical_power_inspection` **不落判定结果**，只存原始功率值；判定在查询/导出时按当前门限实时计算——避免门限调整后历史数据带旧判定。

#### F3.6 导出（P0）
- 按当前筛选结果导出 Excel（xlsx）
- 文件名含导出时间与筛选条件摘要，如 `光功率_全网_202608201530.xlsx`
- 导出文件中记录本次导出所用的门限快照

#### F3.7 自动连接/断开配置（P0）
- **自动连接**：巡检时是否主动连接未在线的设备（默认：是）
- **自动断开**：巡检完成后是否主动断开所有连接（默认：是）
- 配置持久化到SQLite，重启后保留

### F4 时钟拓扑（P2）
- 全网时钟拓扑可视化（ECharts关系图）
- 支持刷新拓扑数据

### F5 数据维护
- MySQL→SQLite按需同步（动态建表+批量写入）
- 同步状态查看、全量/增量同步
- 数据库连通测试

## 6. 非功能需求

| 编号 | 需求 | 说明 |
|---|---|---|
| N1 | 防雪崩 | 批量连接/巡检并发受限流+退避保护 |
| N2 | 会话保活 | SDK 自带 60s 心跳；进程退出必须 `manager.stop()` 干净登出，否则设备用户会话最长滞留 ~3 分钟 |
| N3 | 编码 | 老库 GBK，读库与设备报文解析注意编码转换 |
| N4 | 端口占用 | 与原服务器**同机部署**。不启用 UDP 9910：绕过 `TCPChannel.initialize()`，直接构造 `QxChannelManager` |
| N5 | 权限 | 工具自身登录（操作员账号）为 P2，首版内网信任环境可不做 |
| N6 | 规模预估 | 设备千级（1417台）、端口万级，设计缓存与查询分页 |

## 7. 约束条件

### 7.1 部署约束
- **同机部署**：与老网管服务器部署在同一台机器上
- **端口冲突**：Web端口自定（默认8080），避开已占用端口；UDP 9910不启用
- **MySQL容器**：`mysql-uniview` Docker容器，端口3306，MySQL 5.6
- **SQLite文件**：`./data/qx_inspection.db`，与进程同目录

### 7.2 编码约束
- MySQL老库表级 `CHARSET=gbk`，JDBC必须指定 `characterEncoding=GBK`
- Docker实库中表名**全部小写**（`dmne`/`defdmne`/`emnecomm`等），Linux MySQL `lower_case_table_names=0` 表名区分大小写
- 设备报文为二进制格式，需按Qx协议规范解析

### 7.3 协议约束
- Qx协议：TCP 9900，登录后命令交互
- 命令响应默认超时 10s
- 心跳间隔 60s，180s无响应判离线
- **ChannelID陷阱**：`ChannelID` 的 equals/hashCode **只含 ip+port**（name不参与）。同IP:port已在线时，换用户的connect/send拿到的是既有通道——**单设备覆盖用户/端口后必须先 `manager.shut(id)` 再重连**

### 7.4 数据库约束
- **SQLite单写**：连接池必须设为1，`@Transactional`必须指定`sqliteTransactionManager`
- **WAL模式**：读写并发不阻塞，`busy_timeout=5000`，`synchronous=NORMAL`
- **MySQL只读**：工具对老库只SELECT，不建表不写数据
- **老库不可用**：容器停止/网络断不影响已发现设备的连接与巡检，界面提示"清单加载失败"并可重试

### 7.5 巡检约束
- **单实例**：同一时刻仅允许一个巡检任务实例
- **串行逐口**：单设备内串行逐口采集，避免压垮设备
- **并发控制**：多设备并发数可配（默认10），全局信号量限制
- **门限实时计算**：不落判定结果到记录，查询时重新判定

## 8. 数据库设计

### 8.1 MySQL老库（只读）

**用途**：仅设备发现（F1.1）——启动加载 + 手动刷新

**部署环境**：

| 项 | 值 |
|---|---|
| 容器名 | `mysql-uniview`（镜像 `mysql:5.6`） |
| 端口 | `0.0.0.0:3306 -> 3306`（同机访问 `127.0.0.1:3306`） |
| 库名 | `Uniview` |
| 账号 | `root` / `root123` |
| 字符集 | 表级 `CHARSET=gbk` |
| 现网规模 | 网元 1417 台 |

**涉及表**：

| 表 | 关键字段 | 用途 |
|---|---|---|
| `dmne` | oid, type, location, commuState | 网元清单、类型编码、通讯状态 |
| `defdmne` | neType, cName, eName | 网元类型编码 → 名称字典 |
| `dmrelation` | oid(成员), reo(容器), type | 网元 → 网络归属关系 |
| `dmnet` | oid, type | 网络清单（表本身无名称列） |
| `defdmnetwork` | type, cName | 网络类型 → 名称字典 |
| `emnecomm` | oid, ipAddr, state | 网元 IP（一对多，取state=1有效条） |
| `dmeo` | oid, name, type, defName, cid | 端口/板卡/网元层级对象 |

**设备发现SQL**：

```sql
SELECT n.oid, n.type, n.commuState,
       d.cName    AS neTypeName,
       c.ipAddr,
       r.reo      AS netOid, dn.type AS netType, dn2.cName AS netName
FROM dmne n
LEFT JOIN defdmne d        ON d.neType = n.type
LEFT JOIN emnecomm c       ON c.oid = n.oid AND c.state = 1
LEFT JOIN dmrelation r     ON r.oid = n.oid AND r.type = 1
LEFT JOIN dmnet dn         ON dn.oid = r.reo
LEFT JOIN defdmnetwork dn2 ON dn2.type = dn.type
ORDER BY n.oid
```

### 8.2 SQLite缓存库（主源，可写）

**定位**：工具唯一可写存储——连接配置、门限配置、任务配置、巡检结果全部落此。

**PRAGMA配置**：

```sql
PRAGMA journal_mode = WAL;     -- 读写并发不阻塞
PRAGMA busy_timeout = 5000;    -- 写锁等待5秒
PRAGMA synchronous = NORMAL;   -- WAL模式下安全且快
```

**表结构**：

```sql
-- 连接配置：全局一行(scope='GLOBAL') + 单设备覆盖(scope='NE')
CREATE TABLE conn_profile (
  scope        TEXT NOT NULL,
  ne_oid       TEXT NOT NULL DEFAULT '',
  username     TEXT NOT NULL,
  password     TEXT NOT NULL,
  port         INTEGER NOT NULL DEFAULT 9900,
  auto_connect INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (scope, ne_oid)
);

-- 门限配置：全局兜底 + 按模块类型 + 按型号覆盖
CREATE TABLE threshold_rule (
  rule_id    INTEGER PRIMARY KEY AUTOINCREMENT,
  level_type TEXT NOT NULL,              -- 'GLOBAL' | 'MODULE' | 'PART'
  match_key  TEXT NOT NULL DEFAULT '',   -- MODULE:'L16.1'等；PART:模块型号编码
  tx_low     REAL, tx_high REAL,        -- 发送低/高门限 dBm
  rx_low     REAL, rx_high REAL,        -- 接收低/高门限 dBm
  description TEXT,
  UNIQUE (level_type, match_key)
);

-- 设备访问配置（设备清单+连接信息）
CREATE TABLE device_access_config (
  ne_id       TEXT PRIMARY KEY,
  ne_name     TEXT,
  ip_addr     TEXT,
  port        INTEGER DEFAULT 9900,
  username    TEXT,
  password    TEXT,
  network_name TEXT,
  ne_type_name TEXT,
  connection_status TEXT DEFAULT 'OFFLINE'
);

-- 巡检轮次
CREATE TABLE inspection_round (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  status      TEXT NOT NULL,            -- 'RUNNING' | 'COMPLETED'
  trigger_type TEXT,                    -- 'MANUAL' | 'SCHEDULE'
  scope       TEXT,
  total_count INTEGER,
  done_count  INTEGER,
  fail_count  INTEGER,
  start_time  TEXT,
  end_time    TEXT
);

-- 巡检结果（核心表）
CREATE TABLE optical_power_inspection (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id        INTEGER NOT NULL,
  ne_id           TEXT NOT NULL,
  ne_name         TEXT,
  network_name    TEXT,
  ne_type_name    TEXT,
  slot_no         INTEGER,
  port_no         INTEGER,
  port_name       TEXT,
  port_type       INTEGER,
  port_sub_type   INTEGER,
  supported       INTEGER NOT NULL,
  laser_type      TEXT,                 -- 速率：2.5G, 10G, GE
  laser_distance  TEXT,                 -- 距离档：L, S, I, SX, LX
  module_type_key TEXT,                 -- 组合型号：L16.1, S4.1, 1000BASE-SX
  part_number     TEXT,
  laser_wave      TEXT,
  tx_power        REAL,
  rx_power        REAL,
  tx_power_status INTEGER DEFAULT 0,    -- 0=正常, 1=越下限, 2=越上限
  rx_power_status INTEGER DEFAULT 0,
  low_threshold   REAL,
  high_threshold  REAL,
  tx_low_threshold REAL,
  tx_high_threshold REAL,
  inspection_time TEXT,
  fail_reason     TEXT
);

-- 系统配置KV
CREATE TABLE sys_config (
  key   TEXT PRIMARY KEY,
  value TEXT
);
```

**设计说明**：
- `optical_power_inspection` **不落判定结果**，判定在查询/导出时实时计算
- 写入模式：巡检按网元分批事务提交（一网元一事务）
- 保留清理：新轮次收尾时删除超出 `maxRounds` 的最旧轮次
- 规模：千级网元、单轮 1~4 万行、默认保留 10 轮 ≤ 40 万行

## 9. Qx协议与设备命令

### 9.1 SDK接入

```java
QxChannelManager manager = new QxChannelManager(QxConfig.defaults());
manager.start();                                    // 绕开TCPChannel.initialize()，不启UDP 9910
manager.addStateListener((id, prev, cur) -> ...);   // 在线/离线 → 界面状态 + 触发重连
```

**QxConfig关键默认值**：connectTimeout 5s、loginTimeout 20s、心跳 60s（180s无响应判离线）、businessThreads 4、命令响应默认超时 10s

### 9.2 巡检涉及的设备命令

| 命令 | 命令码 | 用途 |
|---|---|---|
| NE安装查询 | 0x2401 | 网元标识/设备类型（备用） |
| 槽位查询 | 0x2403 | 槽位清单（slotId=0xFF查全部） |
| 物理端口安装查询 | 0x2406 | 端口清单 → 筛光口 |
| **激光器属性查询** | **0x2410** | **单口收/发光功率 + 模块属性** |

### 9.3 激光器属性报文（0x2410）

**请求** `STRU_CFG_Qx_LaserAttribute_Get`（8字节）：

| 字段 | 类型 | 说明 |
|---|---|---|
| bSubCaseNo | Byte | 子架号（=1） |
| bSlotID | Byte | 槽位号 |
| bPortType | Byte | 端口类型 |
| bPortSubType | Byte | 端口子类型 |
| wPortID | Word | 端口号 |
| backup | Byte×2 | 备用 |

**响应** `STRU_CFG_Qx_LaserAttribute_Ack`：

| 字段 | 类型 | 说明 |
|---|---|---|
| bSubCaseNo / bSlotID / bPortType / bPortSubType / wPortID | Byte×4 + Word | 端口定位（回显请求） |
| bLaserWave | Byte | 波长：1=1310nm，2=1550nm，3=850nm |
| bAutoProState ×2 | Byte×2 | ALS自动开关协议状态 |
| bManualControl | Byte | 手动打开 |
| bLaserState | Byte | 激光器实际状态：1=开，2=关 |
| wDelayTime | Word | ALS延迟时间 |
| **bLaserType** | Byte | 速率：1=2.5G，2=622M，3=155M，4=10G，0x10=GE |
| **bDistance** | Byte | 距离档：1=I(短)，2=S(中)，3=L(长)，4=V(超长)；GE 0x10=SX，0x11=LX |
| bBackup1 / bBackup2 | Byte×2 | 备用 |
| bmVendorName[16] | Byte[16] | 厂商ASCII（不入库） |
| **bmPartNumber[16]** | Byte[16] | 模块型号编码ASCII（门限PART级匹配） |
| bmSerialNumber[16] | Byte[16] | 序列号（不入库） |
| bmLaserVersion[4] | Byte[4] | 版本号 |
| bmProductDate[8] | Byte[8] | 生产日期 |
| bLaserOpenTime | Byte | LOS后激光器打开时间（2s/9s） |
| bmBackup[6] | Byte[6] | 备用 |
| **bSupportFlag** | Byte | bit0：1=支持光功率查询，0=不支持 |
| **fRecvLaserPower** | float | 接收光功率 dBm（精确到0.1） |
| **fTranLaserPower** | float | 发送光功率 dBm（精确到0.1） |

### 9.4 模块类型组合规则

`moduleTypeKey` 格式与老网管一致，由 `bLaserType` + `bDistance` 组合：

| laserType | distance | moduleTypeKey | 含义 |
|---|---|---|---|
| 155M (3) | I (1) | **I1.1** | STM-1 短距 |
| 622M (2) | S (2) | **S4.1** | STM-4 中距 |
| 2.5G (1) | L (3) | **L16.1** | STM-16 长距 |
| 2.5G (1) | V (4) | **L16.2** | STM-16 超长距 |
| 10G (4) | S (2) | **S64.2b** | STM-64 850nm中距 |
| 10G (4) | L (3) | **L64.2** | STM-64 850nm长距 |
| 10G (4) | V (4) | **V64.2** | STM-64 850nm超长距 |
| GE (0x10) | SX (0x10) | **1000BASE-SX** | GE短距 |
| GE (0x10) | LX (0x11) | **1000BASE-LX** | GE长距 |

组合逻辑（与老网管 `LaserInfoTableRowDecoder.getDistance()` 一致）：

```java
// 速率代号: 2.5G→16, 622M→4, 155M→1, 10G→64, GE→X
// 距离档模板: 850nm用.2/.2b后缀，其他用.1
// GE: 1000BASE-SX / 1000BASE-LX
```

## 10. 特殊逻辑详解

### 10.1 双数据源架构

```
MySQL（只读副源）                SQLite（主源，可写）
├── dmne, defdmne              ├── device_access_config
├── emnecomm                   ├── optical_power_inspection
├── dmrelation, dmeo           ├── inspection_round
├── dmnet, defdmnetwork        ├── threshold_rule
└── 设备发现用                  ├── sys_config, conn_profile
                               └── 巡检数据+配置
```

- MySQL: `@EnableJpaRepositories(basePackages="repository.mysql")`，GBK编码，连接池3
- SQLite: `@EnableJpaRepositories(basePackages="repository.sqlite")`，WAL模式，连接池1（单写限制）
- SQLite `@Transactional` **必须指定** `transactionManager = "sqliteTransactionManager"`，否则事务不生效

### 10.2 门限判定（三级匹配，实时计算）

```
匹配优先级: PART(型号编码) > MODULE(模块类型) > GLOBAL(全局默认)

例: 端口 partNumber="XXXX", moduleTypeKey="L16.1"
  → 先找 PART:XXXX → 找到用它
  → 没找到 → 找 MODULE:L16.1 → 找到用它
  → 没找到 → 用 GLOBAL 默认值
```

**关键设计**: 门限**不持久化到巡检记录**，查询/导出时按当前门限实时判定。好处：调整门限后历史数据立即重新判定，无需重新采集。

### 10.3 巡检自动连接/断开

两个可配置项（持久化到SQLite `sys_config`表）：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `inspect.autoConnect` | true | 设备未连接时是否主动建立连接 |
| `inspect.autoDisconnect` | true | 巡检完成后是否主动断开所有连接 |

**特殊逻辑**：
- `autoConnect=false` 时，未在线设备**跳过**（记录"设备未连接"），不阻塞后续设备
- `autoDisconnect=true` 时，断开循环**在保存轮次状态之前**执行，防止进度查询提前返回COMPLETED

### 10.4 树形分组表格（数据查询页）

数据按网元分组显示，父行显示网元汇总，子行显示端口详情：

```
▼ NE名称（12 个端口）    正常 10 / 异常 2        2026-08-27 10:00:00
    2    1    GE口       1550nm    -3.2    -18.5    正常    -6~0    -27~-8    ...
    2    2    GE口       1550nm    -2.8    -30.1    劣化    -6~0    -27~-8    ...
▶ NE名称2（8 个端口）    正常 8                    2026-08-27 10:00:01
```

- 分页按**网元组**分页（非单条记录）
- 展开/收缩状态用 `Set<neId>` 追踪
- 支持全部展开/全部收缩

### 10.5 断线重连管理器

```
QxReconnectManager:
├── 指数退避: base(5s) → 10s → 20s → ... → max(300s)
├── 抖动: ±30% 随机偏移，防止同时重连
├── 熔断: 连续失败 ≥ 5次 → 冷却300s
└── 并发控制: Semaphore(10) 全局限制同时重连数
```

### 10.6 OID层级解析

OID格式: `neId:subrack:slot:port:timeslot`（冒号分隔）

```
例: "101:1:11:2:1"
  segment[0] = 101  → 网元ID
  segment[1] = 1    → 子架号 (bSubCaseNo)
  segment[2] = 11   → 槽位号 (bSlotID)
  segment[3] = 2    → 端口号 (wPortID)
  segment[4] = 1    → 时隙
```

`OidUtil` 提供: `getSubrackId()`, `getSlotId()`, `getPortId()`, `getTsPortSubType()` 等方法，用于从dmeo表的OID中提取0x2410命令所需参数。

### 10.7 动态表同步（MySQL→SQLite）

`DynamicSyncService` 实现按需同步：
- 自动读取MySQL表结构，映射类型到SQLite（varchar→TEXT, int→INTEGER, float→REAL等）
- 自动在SQLite建表（`CREATE TABLE IF NOT EXISTS`）
- 批量写入（默认5000条/批），事务保护
- 排除巡检内部表（`device_access_config`, `optical_power_inspection`等）

## 11. 项目结构

```
src/main/java/com/optel/qxinspection/
├── config/                  # 数据源配置
│   ├── SQLiteDataSourceConfig.java    # SQLite双数据源 + JPA配置
│   ├── SchedulingConfig.java          # 定时任务线程池
│   └── SyncConfig.java               # MySQL同步白名单
├── controller/              # REST API
│   ├── ConnectionController.java      # 设备连接管理
│   ├── InspectionController.java      # 巡检核心（触发/进度/结果/导出/门限/时钟）
│   ├── StatsController.java           # 设备类型统计
│   ├── SyncController.java            # MySQL数据同步
│   └── DatabaseTestController.java    # 数据库连通测试
├── entity/
│   ├── mysql/               # 老库只读实体
│   └── sqlite/              # 本地缓存实体
├── qx/                      # Qx协议层
│   ├── QxDeviceServiceImpl.java       # 设备通信封装
│   └── error/QxErrorCode.java         # Qx错误码
├── reconnect/
│   └── QxReconnectManager.java        # 断线重连（指数退避+熔断）
├── repository/              # JPA Repository
├── service/                 # 业务逻辑
│   ├── QxConnectionService.java       # 连接管理（状态监听+缓存）
│   ├── InspectionService.java         # 巡检核心逻辑
│   ├── InspectionScheduler.java       # 定时巡检调度
│   ├── ThresholdService.java          # 门限判定（实时计算）
│   ├── DeviceAccessService.java       # 设备发现（从MySQL读取）
│   ├── DynamicSyncService.java        # MySQL→SQLite动态同步
│   └── SysConfigService.java          # 系统配置持久化
└── util/
    └── OidUtil.java                  # OID层级解析工具

src/main/resources/static/            # 前端
├── index.html                         # 主页面（SPA，侧边栏导航）
├── css/style.css                      # 全局样式
└── js/
    ├── api.js          # API请求封装
    ├── app.js          # 应用入口（页面切换）
    ├── device.js       # 设备管理页
    ├── stats.js        # 统计页
    ├── threshold.js    # 门限配置页
    ├── task.js         # 任务配置页
    ├── progress.js     # 任务进度页
    ├── query.js        # 数据查询页（树形分组表格）
    ├── clock.js        # 时钟拓扑页
    ├── sync.js         # 数据维护页
    └── toast.js        # Toast通知组件

src/main/schema/            # Qx协议YAML Schema
├── laser.yaml              # 0x2410 激光器属性查询
├── device.yaml             # 设备命令
└── clock.yaml              # 时钟命令
```

## 12. API端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/connection/connect-all` | 全部连接 |
| POST | `/api/connection/disconnect-all` | 全部断开 |
| POST | `/api/connection/connect/{neId}` | 单设备连接 |
| POST | `/api/connection/disconnect/{neId}` | 单设备断开 |
| GET | `/api/connection/status` | 连接状态列表 |
| GET | `/api/connection/config` | 连接配置 |
| POST | `/api/connection/config` | 保存连接配置 |
| POST | `/api/inspection/start` | 触发巡检 |
| GET | `/api/inspection/progress` | 巡检进度 |
| GET | `/api/inspection/results` | 查询结果 |
| GET | `/api/inspection/rounds` | 轮次列表 |
| GET | `/api/inspection/export` | 导出Excel |
| GET | `/api/inspection/schedule` | 定时巡检状态 |
| POST | `/api/inspection/schedule/toggle` | 启用/禁用定时 |
| POST | `/api/inspection/schedule/config` | 保存定时配置 |
| GET | `/api/inspection/collect-params` | 采集参数 |
| POST | `/api/inspection/collect-params` | 保存采集参数 |
| GET | `/api/inspection/trend/port` | 单端口趋势 |
| GET | `/api/inspection/trend/ne` | 网元趋势 |
| GET | `/api/inspection/anomaly/summary` | 异常汇总 |
| GET | `/api/inspection/anomaly/details` | 异常详情 |
| GET | `/api/inspection/thresholds` | 门限规则列表 |
| POST | `/api/inspection/thresholds` | 创建/更新门限 |
| DELETE | `/api/inspection/thresholds/{id}` | 删除门限 |
| GET | `/api/inspection/thresholds/snapshot` | 门限快照 |
| GET | `/api/inspection/clock/topology` | 时钟拓扑 |
| POST | `/api/inspection/clock/refresh` | 刷新时钟拓扑 |
| GET | `/api/inventory/stats` | 类型统计 |
| GET | `/api/sync/status` | 同步状态 |
| POST | `/api/sync/essential` | 同步必要表 |
| POST | `/api/sync/all` | 同步全部表 |
| POST | `/api/sync/clear` | 清空同步数据 |

## 13. 巡检采集流程

```
触发（定时/手动）
 1. 创建轮次记录（InspectionRound, status=RUNNING）
 2. 按范围解析目标网元清单（全网/指定网络/指定网元）
 3. 线程池并发逐网元（concurrency=10）：
    a. 设备在线确认；离线先连一次（autoConnect控制）
    b. 从dmeo表查询光口端口（cid=5, defName匹配STM%/GE%等模式）
    c. 单设备串行逐口发送0x2410激光器属性查询：
       - bSupportFlag bit0=1 → 读取收/发光功率 + 模块属性
       - 不支持 → supported=false，界面显示"--"
       - 单口异常 → 记录失败，继续下一口不中断
    d. 本网元结果批量写入optical_power_inspection
 4. 全部完成 → 自动断开连接（autoDisconnect控制）
 5. 更新轮次状态（COMPLETED）→ 清理超龄轮次
```

## 14. 配置项

```yaml
app:
  qx:
    connect-timeout-ms: 5000       # Qx连接超时
    login-timeout-sec: 20          # 登录超时
    heartbeat-interval-sec: 60     # 心跳间隔
    heartbeat-timeout-sec: 180     # 心跳超时判离线
    business-threads: 4            # 业务线程数
    reconnect:
      base-sec: 5                  # 重连基础退避
      max-sec: 300                 # 最大退避
      max-concurrent: 10           # 全局重连并发数
      circuit-threshold: 5         # 熔断阈值（连续失败次数）
      circuit-cooldown-sec: 300    # 熔断冷却时间
  inspection:
    concurrency: 10                # 巡检并发设备数
    max-rounds: 10                 # 保留轮次上限
    port-defname-patterns: "STM%,GE%,10GE%,40GE%,100GE%"  # 光口过滤模式
    scheduled:
      enabled: false
      cron: "0 0 2 * * ?"         # 默认每天凌晨2点
      scope: ALL                   # ALL / NETWORK
      network: ""
  sync:
    essential:                     # 必要表（同步模式=essential时）
      - dmne
      - defdmne
      - emnecomm
      - dmrelation
      - dmeo
      - dmnet
      - defdmnetwork
    batch-size: 5000               # 批量写入大小
```

## 15. 构建与运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/qx-inspection-tool-1.0.0-SNAPSHOT.jar

# 访问
http://localhost:8080/qx-inspection
```

**前置条件**：
- MySQL 5.6容器运行中（`mysql-uniview`，端口3306，GBK编码）
- SQLite数据库自动创建（`./data/qx_inspection.db`）

## 16. 注意事项

1. **ChannelID陷阱**: `ChannelID`的equals/hashCode只含ip+port（name不参与）。同IP:port已在线时换用户连接，必须先`manager.shut(id)`再重连
2. **SQLite单写**: 连接池必须设为1，`@Transactional`必须指定`sqliteTransactionManager`
3. **老库编码**: MySQL表级GBK，JDBC必须指定`characterEncoding=GBK`
4. **老库表名**: Docker实库中表名**全部小写**，写成`DmNe`会报`Table doesn't exist`
5. **UDP 9910绕开**: 直接构造`QxChannelManager`不触发UDP监听，同机部署无端口冲突
6. **门限实时计算**: 不落判定结果到记录，查询时按当前门限重新判定
7. **autoDisconnect时序**: 断开循环在保存轮次状态之前，防止进度API提前返回COMPLETED
8. **进程退出**: 必须`manager.stop()`干净登出全部设备，否则设备用户会话最长滞留~3分钟

## 17. 参考资源

- **需求文档**: `D:\ai-workspace\mtp\REQ-QX设备直连巡检工具.md`
- **Qx协议文档**: `D:\ai-workspace\mtp\qx-md\`
- **老网管激光器显示逻辑**: `PROG/client/.../laser/LaserInfoTableRowDecoder.java`

## 作者

Rwj

## 版本

1.0.0-SNAPSHOT
