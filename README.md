# QX设备直连巡检工具

独立工具直连QX设备进行光功率巡检，使用独立用户登录，与老网管互不影响。

## 技术栈

- Java 17 + Spring Boot 3.2.0
- Qx协议SDK: opt-qx-cci-core（Netty 4.1，TCP 9900）
- 数据库: MySQL 5.6（老库只读，GBK） + SQLite（本地缓存，WAL模式）
- 前端: 原生HTML/CSS/JS（ES6模块），ECharts图表
- 构建: Maven

## 项目结构

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
│   ├── mysql/               # 老库只读实体（DmNe/DmNet/Dmeo/EmNeComm等）
│   └── sqlite/              # 本地缓存实体
│       ├── DeviceAccessConfig.java    # 设备连接配置（全局+单设备覆盖）
│       ├── ConnProfile.java           # 连接画像
│       ├── OpticalPowerInspection.java # 巡检结果（核心表）
│       ├── InspectionRound.java       # 巡检轮次
│       ├── ThresholdRule.java         # 门限规则
│       └── SysConfig.java             # 系统配置KV
├── qx/                      # Qx协议层
│   ├── QxDeviceServiceImpl.java       # 设备通信封装
│   └── error/QxErrorCode.java         # Qx错误码
├── reconnect/
│   └── QxReconnectManager.java        # 断线重连（指数退避+熔断）
├── repository/              # JPA Repository（mysql/sqlite双数据源）
├── service/                 # 业务逻辑
│   ├── QxConnectionService.java       # 连接管理（状态监听+缓存）
│   ├── InspectionService.java         # 巡检核心逻辑
│   ├── InspectionScheduler.java       # 定时巡检调度
│   ├── ThresholdService.java          # 门限判定（实时计算）
│   ├── DeviceAccessService.java       # 设备发现（从MySQL读取）
│   ├── DeviceStatsService.java        # 设备类型统计
│   ├── DynamicSyncService.java        # MySQL→SQLite动态同步
│   ├── MysqlConnectionManager.java    # MySQL按需连接
│   ├── ClockInspectionService.java    # 时钟拓扑
│   ├── SysConfigService.java          # 系统配置持久化
│   └── InventoryStatsService.java     # 库存统计
└── util/
    └── OidUtil.java                  # OID层级解析工具

src/main/resources/static/            # 前端
├── index.html                         # 主页面（SPA，侧边栏导航）
├── css/style.css                      # 全局样式
└── js/
    ├── api.js          # API请求封装 + Toast通知
    ├── app.js          # 应用入口（页面切换、全局事件）
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

## 功能模块

### F1 设备管理
- 从MySQL老库发现设备（DmNe/DmNet/DmRelation + 字典表）
- 全局连接配置 + 单设备覆盖（用户名/密码/端口）
- 一键全部连接/断开，单设备连接/断开（带loading动效）
- 断线自动重连（指数退避+抖动+熔断+全局并发信号量）
- 设备列表：网元名、IP、所属网络、设备类型、连接状态

### F2 类型统计
- 按设备类型统计数量/占比，饼图/柱状图展示
- 支持按网络筛选

### F3 光功率巡检
- **采集**: 0x2410激光器属性查询，逐端口采集收/发光功率
- **门限判定**: 三级匹配（PART精确 > MODULE类型 > GLOBAL默认），查询时实时计算
- **数据查询**: 按网元分组的树形表格，可展开/收缩，分页/排序/搜索
- **导出**: Excel（xlsx），含门限快照
- **定时调度**: Cron表达式，配置持久化到SQLite

### F4 时钟拓扑
- 全网时钟拓扑可视化（ECharts关系图）

### F5 数据维护
- MySQL→SQLite按需同步（动态建表+批量写入）
- 同步状态查看、全量/增量同步

## 特殊逻辑详解

### 1. 双数据源架构

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
- SQLite `@Transactional` 必须指定 `transactionManager = "sqliteTransactionManager"`，否则事务不生效

### 2. 门限判定（三级匹配，实时计算）

```
匹配优先级: PART(型号编码) > MODULE(模块类型) > GLOBAL(全局默认)

例: 端口 partNumber="XXXX", moduleTypeKey="L16.1"
  → 先找 PART:XXXX → 找到用它
  → 没找到 → 找 MODULE:L16.1 → 找到用它
  → 没找到 → 用 GLOBAL 默认值
```

**关键设计**: 门限**不持久化到巡检记录**，查询/导出时按当前门限实时判定。好处：调整门限后历史数据立即重新判定，无需重新采集。

**模块类型组合键**（`moduleTypeKey`）格式与老网管一致：

| laserType | distance | moduleTypeKey | 含义 |
|---|---|---|---|
| 155M | I | I1.1 | STM-1 短距 |
| 622M | S | S4.1 | STM-4 中距 |
| 2.5G | L | L16.1 | STM-16 长距 |
| 10G | S(850nm) | S64.2b | STM-64 850nm 中距 |
| GE | SX | 1000BASE-SX | GE 短距 |
| GE | LX | 1000BASE-LX | GE 长距 |

### 3. 巡检自动连接/断开配置

两个可配置项（持久化到SQLite `sys_config`表）：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `inspect.autoConnect` | true | 设备未连接时是否主动建立连接 |
| `inspect.autoDisconnect` | true | 巡检完成后是否主动断开所有连接 |

**特殊逻辑**:
- `autoConnect=false` 时，未在线设备**跳过**（记录"设备未连接"），不阻塞后续设备
- `autoDisconnect=true` 时，断开循环**在保存轮次状态之前**执行，防止进度查询提前返回COMPLETED

### 4. 树形分组表格（数据查询页）

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

### 5. 断线重连管理器

```
QxReconnectManager:
├── 指数退避: base(5s) → 10s → 20s → ... → max(300s)
├── 抖动: ±30% 随机偏移，防止同时重连
├── 熔断: 连续失败 ≥ 5次 → 冷却300s
└── 并发控制: Semaphore(10) 全局限制同时重连数
```

### 6. OID层级解析

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

### 7. 动态表同步（MySQL→SQLite）

`DynamicSyncService` 实现按需同步：
- 自动读取MySQL表结构，映射类型到SQLite（varchar→TEXT, int→INTEGER, float→REAL等）
- 自动在SQLite建表（`CREATE TABLE IF NOT EXISTS`）
- 批量写入（默认5000条/批），事务保护
- 排除巡检内部表（`device_access_config`, `optical_power_inspection`等）

### 8. SQLite WAL模式

```
PRAGMA journal_mode = WAL;     -- 读写并发不阻塞
PRAGMA busy_timeout = 5000;    -- 写锁等待5秒
PRAGMA synchronous = NORMAL;   -- WAL模式下安全且快
```

连接池设为1（SQLite单写限制），HikariCP `maximumPoolSize=1`。

## 配置项

```yaml
app:
  qx:
    connect-timeout-ms: 5000       # Qx连接超时
    login-timeout-sec: 20          # 登录超时
    heartbeat-interval-sec: 60     # 心跳间隔
    reconnect:
      base-sec: 5                  # 重连基础退避
      max-sec: 300                 # 最大退避
      max-concurrent: 10           # 全局重连并发数
      circuit-threshold: 5         # 熔断阈值
  inspection:
    concurrency: 10                # 巡检并发设备数
    max-rounds: 10                 # 保留轮次上限
    port-defname-patterns: "STM%,GE%,10GE%,40GE%,100GE%"  # 光口过滤
    scheduled:
      enabled: false
      cron: "0 0 2 * * ?"         # 默认每天凌晨2点
      scope: ALL                   # ALL / NETWORK
```

## API端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/connection/connect-all` | 全部连接 |
| POST | `/api/connection/disconnect-all` | 全部断开 |
| POST | `/api/connection/connect/{neId}` | 单设备连接 |
| POST | `/api/connection/disconnect/{neId}` | 单设备断开 |
| GET | `/api/connection/status` | 连接状态列表 |
| POST | `/api/inspection/start` | 触发巡检 |
| GET | `/api/inspection/progress` | 巡检进度 |
| GET | `/api/inspection/results` | 查询结果 |
| GET | `/api/inspection/rounds` | 轮次列表 |
| GET | `/api/inspection/export` | 导出Excel |
| GET/POST | `/api/inspection/schedule/*` | 定时巡检配置 |
| GET/POST | `/api/inspection/collect-params` | 采集参数 |
| GET | `/api/inspection/trend/port` | 单端口趋势 |
| GET | `/api/inspection/trend/ne` | 网元趋势 |
| GET | `/api/inspection/anomaly/*` | 异常汇总 |
| GET/POST | `/api/inspection/thresholds` | 门限规则 |
| GET | `/api/inspection/clock/topology` | 时钟拓扑 |
| GET | `/api/inventory/stats` | 类型统计 |
| GET | `/api/sync/status` | 同步状态 |
| POST | `/api/sync/essential` | 同步必要表 |

## 巡检采集流程

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

## 构建与运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/qx-inspection-tool-1.0.0-SNAPSHOT.jar

# 访问
http://localhost:8080/qx-inspection
```

前置条件:
- MySQL 5.6容器运行中（`mysql-uniview`，端口3306，GBK编码）
- SQLite数据库自动创建（`./data/qx_inspection.db`）

## 注意事项

1. **ChannelID陷阱**: `ChannelID`的equals/hashCode只含ip+port（name不参与）。同IP:port已在线时换用户连接，必须先`manager.shut(id)`再重连
2. **SQLite单写**: 连接池必须设为1，`@Transactional`必须指定`sqliteTransactionManager`
3. **老库编码**: MySQL表级GBK，JDBC必须指定`characterEncoding=GBK`
4. **UDP 9910绕开**: 直接构造`QxChannelManager`不触发UDP监听，同机部署无端口冲突
5. **门限实时计算**: 不落判定结果到记录，查询时按当前门限重新判定
6. **autoDisconnect时序**: 断开循环在保存轮次状态之前，防止进度API提前返回COMPLETED

## 作者

Rwj

## 版本

1.0.0-SNAPSHOT
