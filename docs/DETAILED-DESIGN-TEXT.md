# QX设备直连巡检工具 详细设计文档

> QX Device Direct-Connection Inspection Tool Software Detail Design

| 项目 | 内容 |
|---|---|
| 文档版本 | V2.0 |
| 编写日期 | 2026-08-27 |
| 更新日期 | 2026-09-13 |
| 编写人 | Rwj |
| 状态 | 已实现 |

---

## 1. 目的 Purpose

描述和定义模块内部的详细设计。

本文档描述QX设备直连巡检工具各模块的内部实现细节，包括数据结构、算法流程、接口定义、错误处理等，为开发人员提供编码依据，为测试人员提供测试用例设计依据。

**更新说明：** V2.0版本根据实际实现更新了界面显示和业务逻辑部分。

## 2. 背景 Background

| 项目 | 说明 |
|---|---|
| 项目名称 | QX设备直连巡检工具 |
| 需求文档 | REQ-QX设备直连巡检工具.md V0.4 |
| 概要设计 | README.md（架构设计部分） |
| 当前阶段 | 已实现 |
| 技术栈 | Java 21 + Spring Boot 3.5 + SQLite + MySQL + QX SDK |
| 前端架构 | SPA单页面应用 + ES6模块化 + ECharts图表 |

## 3. 范围 Scope

| 角色 | 用途 |
|---|---|
| 开发人员 | 编码实现依据 |
| 测试人员 | 测试用例设计依据 |
| 评审人员 | 技术评审参考 |
| 维护人员 | 后续维护参考 |

## 4. 名称解释 Items

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
| SPA | 单页面应用（Single Page Application） |

## 5. 模块内部 结构 Inner Architecture

### 5.1 结构框图

系统采用分层架构设计，从上到下分为前端层、REST API层、业务服务层、QX协议层和数据层。

**前端层**：采用SPA单页面应用架构，包含侧边栏导航和9个功能页面模块。使用ES6模块化设计，支持深色/浅色主题切换。

**REST API层**：提供5个控制器，共35个REST接口，负责前端与后端的数据交互。

**业务服务层**：包含11个服务类，负责核心业务逻辑处理，包括设备管理、连接管理、巡检、门限判定、调度、同步、统计等。

**QX协议层**：封装QX设备通信协议，包括设备服务实现、断线重连管理器、通道管理器等。

**数据层**：采用双数据库架构，SQLite作为主存储（8张表），MySQL作为只读副源（7张表）。

### 5.2 子模块清单

| 序号 | 模块名称 | 标识符 | 功能描述 | 依赖关系 |
|---|---|---|---|---|
| 1 | 设备发现与管理 | DEVICE_MGR | 从MySQL发现设备、连接配置、连接状态管理 | MySQL只读副源、QxConnectionService |
| 2 | Qx连接管理 | QX_CONN | 设备连接/断开、状态监听、断线重连 | QxDeviceServiceImpl |
| 3 | 光功率巡检 | INSPECT | 定时/手动触发巡检、逐端口采集、结果存储 | QxConnectionService、ILaserService |
| 4 | 门限判定 | THRESHOLD | 两级匹配（MODULE>GLOBAL）、实时计算 | ThresholdRuleRepository |
| 5 | 数据查询与导出 | QUERY | 树形分组表格、筛选排序、Excel导出 | OpticalPowerInspectionRepository |
| 6 | 定时调度 | SCHEDULER | Cron定时触发、配置持久化 | InspectionService |
| 7 | 数据同步 | SYNC | MySQL→SQLite动态建表、批量同步 | DynamicSyncService |
| 8 | 统计分析 | STATS | 设备类型统计、趋势图表、异常汇总 | 各Repository |

### 5.3 前端架构设计

#### 5.3.1 技术选型

| 技术 | 用途 | 说明 |
|------|------|------|
| ES6 Modules | 模块化 | 每个页面独立模块，按需加载 |
| ECharts 5.x | 图表 | 统计图表、趋势分析、时钟拓扑 |
| 原生JS | 交互 | 无框架依赖，轻量高效 |
| CSS Variables | 主题 | 支持深色/浅色模式切换 |
| Fetch API | 网络请求 | 统一的API调用封装 |

#### 5.3.2 模块结构

前端采用模块化设计，包含以下主要文件：

- index.html：主页面，SPA入口，包含所有页面的HTML结构
- css/style.css：全局样式，使用CSS Variables实现主题切换
- js/app.js：应用入口，负责页面切换和全局事件注册
- js/api.js：API调用封装，提供get/post/put/del方法
- js/toast.js：Toast通知组件
- js/theme.js：主题切换逻辑
- js/device.js：设备管理页模块
- js/stats.js：类型统计页模块
- js/threshold.js：门限配置页模块
- js/task.js：任务配置页模块
- js/progress.js：任务进度页模块
- js/query.js：数据查询页模块
- js/clock.js：时钟拓扑页模块
- js/sync.js：数据维护页模块
- js/guide.js：操作指南页模块

#### 5.3.3 页面切换机制

系统采用侧边栏导航实现页面切换。点击侧边栏菜单项时，会执行以下操作：

1. 更新侧边栏导航状态，高亮当前选中项
2. 切换页面显示，隐藏其他页面，显示目标页面
3. 更新页面标题和面包屑导航
4. 触发目标页面的数据初始化加载

设备管理页支持自动刷新（每10秒），其他页面在离开时停止自动刷新。

---

## 6. 模块详细定义 Module Detail Design

### 6.1 设备发现与管理模块

#### 6.1.1 模块功能

从MySQL老库发现设备清单，管理设备连接配置（全局+单设备覆盖），提供连接/断开操作。

#### 6.1.2 界面显示

**页面整体布局：**

页面采用顶部统计卡片+折叠面板+操作栏+筛选栏+数据表格的布局结构。

**统计卡片区域：**

页面顶部显示三个统计卡片，分别显示设备总数、在线设备数和离线设备数。卡片采用不同颜色区分状态，总数为蓝色，在线为绿色，离线为灰色。

**全局连接配置面板：**

采用可折叠面板设计，默认收起。展开后显示全局默认连接配置表单，包含用户名、密码、端口三个输入框和一个保存按钮。表单下方有提示文字说明配置的生效范围。

**操作栏：**

包含四个主要操作按钮：同步设备、一键连接、一键断开、刷新。同步设备按钮用于从MySQL老库导入设备清单；一键连接/断开用于批量操作当前筛选范围内的设备；刷新按钮用于重新加载设备列表。

操作栏还包含一个网络下拉框，用于选择特定网络进行批量连接/断开操作。

**筛选栏：**

提供三个筛选条件：搜索框（支持网元名/IP搜索）、网络下拉框、状态下拉框（在线/离线）。筛选结果实时更新，显示当前筛选结果的统计信息。

**分页控件：**

支持分页浏览，每页可选200/500/1000条记录。显示当前页码、总页数和总记录数。

**设备表格：**

显示设备详细信息，包含以下列：网元名、IP地址、所属网络、设备类型、连接状态、登录用户、操作。

连接状态列使用彩色圆点指示器：绿色表示在线，灰色表示离线，黄色表示连接中。

操作列包含三个按钮：连接（或断开）、配置。连接按钮根据当前状态动态切换文字。

**单设备配置弹窗：**

点击配置按钮弹出模态框，显示设备信息和配置表单。表单包含用户名、密码、端口三个输入框，支持清除覆盖配置操作。

#### 6.1.3 工作流程

**设备发现流程：**

1. 用户点击"同步设备"按钮，前端调用刷新接口
2. 后端从MySQL老库查询设备清单，包括网元信息、设备类型、IP地址、网络信息
3. 合并SQLite已有配置，保留用户自定义的用户名、密码、端口
4. 更新设备基本信息（网元名、IP、网络等）
5. 批量写入device_access_config表，使用INSERT OR REPLACE
6. 返回设备列表给前端，前端刷新表格和统计卡片

**设备连接流程：**

1. 用户点击"一键连接"或单设备"连接"按钮
2. 后端检查是否已有连接，有则直接返回成功
3. 构造连接参数（IP、端口、用户名、密码）
4. 检查同IP:port是否已有连接，有则先断开
5. 调用QX SDK建立连接，超时时间8秒
6. 注册状态监听器，连接成功更新状态为ONLINE
7. 连接失败触发重连管理器

#### 6.1.4 接口定义

**REST接口清单：**

| 方法 | 路径 | 功能 | 参数 | 返回 |
|------|------|------|------|------|
| POST | /api/connection/connectAll | 一键全部连接 | 无 | 成功标志 |
| POST | /api/connection/disconnectAll | 一键全部断开 | 无 | 成功标志和断开数量 |
| POST | /api/connection/connect/{neOid} | 单设备连接 | 网元ID | 成功标志和错误信息 |
| POST | /api/connection/disconnect/{neOid} | 单设备断开 | 网元ID | 成功标志 |
| PUT | /api/connection/global | 保存全局配置 | 配置JSON | 配置对象 |
| GET | /api/connection/global | 获取全局配置 | 无 | 配置对象 |
| POST | /api/connection/refresh | 从老库刷新设备 | 无 | 成功标志和设备数量 |
| GET | /api/connection/devices | 获取设备列表 | 无 | 设备列表和统计数据 |
| GET | /api/connection/config/{neOid} | 获取设备配置 | 网元ID | 配置对象 |
| PUT | /api/connection/config/{neOid} | 保存设备配置 | 网元ID和配置JSON | 配置对象 |
| DELETE | /api/connection/config/{neOid} | 删除设备配置 | 网元ID | 成功标志 |

#### 6.1.5 数据结构

**device_access_config表结构：**

```sql
CREATE TABLE device_access_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ne_id VARCHAR(64) NOT NULL UNIQUE,
  ne_name VARCHAR(100),
  ne_type_name VARCHAR(100),
  network_name VARCHAR(200),
  ip_addr VARCHAR(50) NOT NULL,
  port INTEGER,
  username VARCHAR(100),
  password VARCHAR(200),
  enabled INTEGER DEFAULT 1,
  connection_status INTEGER DEFAULT 0,
  last_connect_time TIMESTAMP,
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  remark VARCHAR(500)
);
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 自增主键 |
| ne_id | VARCHAR(64) | 网元ID，唯一标识 |
| ne_name | VARCHAR(100) | 网元名称 |
| ne_type_name | VARCHAR(100) | 设备类型名称 |
| network_name | VARCHAR(200) | 所属网络名称 |
| ip_addr | VARCHAR(50) | 设备IP地址 |
| port | INTEGER | QX端口号，默认9900 |
| username | VARCHAR(100) | 登录用户名 |
| password | VARCHAR(200) | 登录密码 |
| enabled | INTEGER | 是否启用，1=启用，0=禁用 |
| connection_status | INTEGER | 连接状态：0=未连接，1=已连接，2=连接失败 |
| last_connect_time | TIMESTAMP | 最后连接时间 |
| create_time | TIMESTAMP | 记录创建时间 |
| update_time | TIMESTAMP | 记录更新时间 |
| remark | VARCHAR(500) | 备注信息 |

---

### 6.2 Qx连接管理模块

#### 6.2.1 模块功能

管理Qx设备连接生命周期，包括连接建立、状态监听、断线重连。

#### 6.2.2 界面显示

**状态指示器：**

系统使用彩色圆点指示器显示设备连接状态：

- 绿色圆点（●）：在线状态，设备已成功连接
- 灰色圆点（○）：离线状态，设备未连接
- 黄色圆点（◐）：连接中状态，正在建立连接

**批量连接进度条：**

当执行批量连接操作时，显示进度条，包含以下信息：

- 当前完成数量/总数量
- 成功数量和失败数量
- 进度百分比
- 进度条动画效果

#### 6.2.3 工作流程

**连接流程：**

1. 检查channelToOid映射是否已有该设备的连接
2. 如果已有连接，直接返回成功
3. 构造连接参数（IP、端口、用户名、密码）
4. 检查同IP:port是否有其他连接，有则先断开（重要：ChannelID只含ip+port）
5. 调用QxChannelManager.connect()建立连接，设置超时时间
6. 注册状态监听器，监听连接状态变化
7. 连接成功：更新设备状态为ONLINE
8. 连接失败：更新设备状态为OFFLINE，触发重连管理器

**断线重连流程：**

1. 状态监听器收到CLOSED事件
2. 检查熔断状态：连续失败次数是否达到阈值（默认5次）
3. 如果触发熔断，进入冷却期（默认300秒）
4. 计算退避时间：基础间隔 × 2^重试次数 + 随机抖动
5. 获取全局并发信号量（默认最多20个并发重连）
6. 获取成功：执行重连操作
7. 获取失败：等待信号量释放

**重连算法说明：**

- 退避策略：指数退避，基础间隔5秒，最大间隔300秒
- 抖动：±30%的随机抖动，避免惊群效应
- 熔断机制：连续失败5次触发熔断，冷却300秒后重试
- 并发控制：使用信号量限制最多20个并发重连

#### 6.2.4 配置参数

| 参数 | 配置项 | 默认值 | 说明 |
|------|--------|--------|------|
| 连接超时 | qx.connect.timeout-ms | 8000 | 连接建立超时时间（毫秒） |
| 批量并发 | qx.connect.batch-concurrency | 20 | 批量连接并发数 |
| 重连基础间隔 | qx.reconnect.base-sec | 5 | 重连基础等待时间（秒） |
| 重连最大间隔 | qx.reconnect.max-sec | 300 | 重连最大等待时间（秒） |
| 最大并发重连 | qx.reconnect.max-concurrent | 20 | 并发重连数上限 |
| 熔断阈值 | qx.reconnect.circuit-threshold | 5 | 连续失败次数触发熔断 |
| 熔断冷却时间 | qx.reconnect.circuit-cooldown-sec | 300 | 熔断冷却时间（秒） |

---

### 6.3 光功率巡检模块

#### 6.3.1 模块功能

定时或手动触发巡检，逐端口采集光功率，存储结果到SQLite。

#### 6.3.2 界面显示

**任务配置页：**

页面包含三个折叠面板：采集参数、定时巡检配置、手动触发巡检。

采集参数面板：显示并发设备数和保留轮次上限两个输入框，以及三个复选框（自动连接、自动断开、保存无效记录）。下方有说明文字解释各参数的含义。

定时巡检配置面板：包含启用开关、执行频率下拉框（预设7个常用频率+自定义）、采集范围选择（全网/指定网络）。下方显示上次运行状态和时间。

手动触发巡检面板：包含巡检范围选择（全网/指定网络/指定网元）和立即巡检按钮。

**任务进度页：**

页面顶部显示巡检完成横幅（仅在巡检完成后显示），包含摘要信息和查看结果按钮。

统计卡片区域显示任务状态、已完成数、失败数。

进度条区域显示当前采集进度、进度百分比、当前网元和当前端口信息。

失败设备面板（可折叠）显示失败设备列表和失败原因。

巡检报告面板（可折叠）显示本轮巡检的详细统计：总端口数、支持光功率数、越限端口数、巡检耗时，以及按模块类型和设备类型的统计表格。

历史轮次面板（可折叠）显示所有巡检轮次的历史记录，支持查看详情和对比。

#### 6.3.3 工作流程

**巡检主流程：**

1. 检查是否已有巡检任务在运行，如果是则返回错误
2. 创建巡检轮次记录，设置状态为RUNNING
3. 根据巡检范围解析目标网元清单（全网/指定网络/指定网元）
4. 使用线程池并发采集（默认并发数10）
5. 对每个网元执行以下操作：
   - 检查连接状态，未连接且开启自动连接则尝试连接
   - 查询光口端口信息（从MySQL同步的dmeo表）
   - 单设备串行逐口采集光功率（发送0x2410命令）
   - 解析响应数据，构建巡检结果记录
   - 批量写入SQLite数据库
6. 全部完成后，根据配置决定是否自动断开连接
7. 更新轮次状态为COMPLETED
8. 清理超龄轮次（保留最近N轮）

**端口查询逻辑：**

从dmeo表查询光口端口，筛选条件：
- OID以网元ID开头
- cid=5（表示端口类型）
- defName匹配STM*、GE*等光口模式

**光功率采集逻辑：**

1. 从OID提取slotId和portId
2. 构造0x2410激光器属性查询命令
3. 发送命令并等待响应（超时20秒）
4. 解析响应数据：激光器状态、速率类型、距离档、模块型号、收发光功率
5. 处理特殊情况：不支持光功率的端口标记为unsupported

#### 6.3.4 接口定义

**REST接口清单：**

| 方法 | 路径 | 功能 | 参数 | 返回 |
|------|------|------|------|------|
| POST | /api/inspection/start | 触发巡检 | network?, neId? | 轮次ID、状态、总设备数 |
| GET | /api/inspection/progress | 查询巡检进度 | 无 | 进度、总数、当前网元、失败列表 |
| GET | /api/inspection/summary | 查询巡检摘要 | 无 | 端口统计、模块统计、设备类型统计 |
| GET | /api/inspection/rounds | 查询轮次列表 | 无 | 轮次列表 |
| GET | /api/inspection/results | 查询巡检结果 | roundId?, neId?, status? | 巡检结果列表 |
| GET | /api/inspection/export | 导出Excel | roundId?, network? | Excel文件 |
| POST | /api/inspection/compare | 对比轮次 | roundA, roundB | 差异对比结果 |

#### 6.3.5 数据结构

**inspection_round表结构：**

```sql
CREATE TABLE inspection_round (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  trigger_type VARCHAR(20) NOT NULL,
  scope_type VARCHAR(20) NOT NULL,
  scope_param VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  total_count INTEGER DEFAULT 0,
  done_count INTEGER DEFAULT 0,
  fail_count INTEGER DEFAULT 0,
  start_time TIMESTAMP,
  end_time TIMESTAMP
);
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 自增主键 |
| trigger_type | VARCHAR(20) | 触发类型：MANUAL=手动，SCHEDULE=定时 |
| scope_type | VARCHAR(20) | 范围类型：ALL=全网，NETWORK=指定网络，SINGLE=指定网元 |
| scope_param | VARCHAR(200) | 范围参数（网络名或网元ID） |
| status | VARCHAR(20) | 状态：RUNNING=运行中，COMPLETED=已完成 |
| total_count | INTEGER | 总设备数 |
| done_count | INTEGER | 已完成数 |
| fail_count | INTEGER | 失败数 |
| start_time | TIMESTAMP | 开始时间 |
| end_time | TIMESTAMP | 结束时间 |

**optical_power_inspection表结构：**

```sql
CREATE TABLE optical_power_inspection (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id INTEGER NOT NULL,
  ne_id VARCHAR(64) NOT NULL,
  ne_name VARCHAR(100),
  network_name VARCHAR(100),
  ne_type_name VARCHAR(100),
  slot_no INTEGER,
  port_no INTEGER,
  port_name VARCHAR(256),
  port_type INTEGER,
  port_sub_type INTEGER,
  supported INTEGER,
  laser_state INTEGER,
  laser_type VARCHAR(20),
  laser_distance VARCHAR(20),
  module_type_key VARCHAR(40),
  part_number VARCHAR(32),
  vendor_name VARCHAR(32),
  laser_wave VARCHAR(20),
  tx_power REAL,
  rx_power REAL,
  tx_power_status INTEGER DEFAULT 0,
  rx_power_status INTEGER DEFAULT 0,
  low_threshold REAL,
  high_threshold REAL,
  tx_low_threshold REAL,
  tx_high_threshold REAL,
  inspection_time TIMESTAMP,
  create_time TIMESTAMP,
  fail_reason VARCHAR(200)
);
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 自增主键 |
| round_id | INTEGER | 关联巡检轮次ID |
| ne_id | VARCHAR(64) | 网元ID |
| ne_name | VARCHAR(100) | 网元名称 |
| network_name | VARCHAR(100) | 所属网络 |
| ne_type_name | VARCHAR(100) | 设备类型名称 |
| slot_no | INTEGER | 槽位号 |
| port_no | INTEGER | 端口号 |
| port_name | VARCHAR(256) | 端口名称 |
| port_type | INTEGER | 端口类型 |
| port_sub_type | INTEGER | 端口子类型 |
| supported | INTEGER | 是否支持光功率查询：1=支持，0=不支持 |
| laser_state | INTEGER | 激光器状态：0=未知，1=开，2=关 |
| laser_type | VARCHAR(20) | 光模块速率类型（如2.5G、10G、GE） |
| laser_distance | VARCHAR(20) | 光模块距离档（如L、S、I、SX、LX） |
| module_type_key | VARCHAR(40) | 模块类型组合键（与老网管一致） |
| part_number | VARCHAR(32) | 模块型号编码 |
| vendor_name | VARCHAR(32) | 生产厂商 |
| laser_wave | VARCHAR(20) | 波长（如1310nm、1550nm） |
| tx_power | REAL | 发送光功率（dBm） |
| rx_power | REAL | 接收光功率（dBm） |
| tx_power_status | INTEGER | 发送光功率状态：0=正常，1=越下限，2=越上限 |
| rx_power_status | INTEGER | 接收光功率状态：0=正常，1=越下限，2=越上限 |
| low_threshold | REAL | 接收低光功率门限（dBm） |
| high_threshold | REAL | 接收高光功率门限（dBm） |
| tx_low_threshold | REAL | 发送低光功率门限（dBm） |
| tx_high_threshold | REAL | 发送高光功率门限（dBm） |
| inspection_time | TIMESTAMP | 巡检时间 |
| create_time | TIMESTAMP | 记录创建时间 |
| fail_reason | VARCHAR(200) | 采集失败原因 |

**索引：**

```sql
CREATE INDEX idx_opi_round ON optical_power_inspection(round_id);
CREATE INDEX idx_opi_ne ON optical_power_inspection(ne_id);
CREATE INDEX idx_opi_round_ne ON optical_power_inspection(round_id, ne_id);
```

---

### 6.4 门限判定模块

#### 6.4.1 模块功能

两级匹配（MODULE>GLOBAL），查询时实时计算光功率判定结果。

#### 6.4.2 界面显示

**门限配置页：**

页面顶部显示匹配优先级说明：MODULE（模块类型匹配）> GLOBAL（全局默认），修改后即时生效，无需重新采集。

全局默认门限面板（可折叠）：显示四个输入框（接收低门限、接收高门限、发送低门限、发送高门限）和保存按钮。

模块类型规则表格：显示所有模块类型的门限规则，包含模块类型、接收低/高门限、发送低/高门限、说明、操作列。支持新增和删除规则。

新增规则弹窗：包含模块类型下拉选择（显示ITU-T G.957参考值）、四个门限输入框、说明输入框。

#### 6.4.3 工作流程

**门限匹配流程：**

1. 加载所有门限规则到内存HashMap
2. 对每条巡检记录，按优先级匹配：
   - 优先级1：MODULE级，按moduleTypeKey匹配
   - 优先级2：GLOBAL级，使用全局默认规则
3. 应用门限判定：
   - txPower < txLow → status=1（越下限）
   - txPower > txHigh → status=2（越上限）
   - 其他 → status=0（正常）
   - rxPower同理

**默认门限值：**

- 接收低门限：-27.0 dBm
- 接收高门限：3.0 dBm
- 发送低门限：-27.0 dBm
- 发送高门限：3.0 dBm

#### 6.4.4 接口定义

**REST接口清单：**

| 方法 | 路径 | 功能 | 参数 | 返回 |
|------|------|------|------|------|
| POST | /api/inspection/thresholds | 保存门限规则 | 规则JSON | 规则对象 |
| GET | /api/inspection/thresholds | 获取门限规则 | 无 | 规则列表 |
| DELETE | /api/inspection/thresholds/{id} | 删除门限规则 | 规则ID | 成功标志 |

#### 6.4.5 数据结构

**threshold_rule表结构：**

```sql
CREATE TABLE threshold_rule (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  level_type VARCHAR(20) NOT NULL,
  match_key VARCHAR(64) NOT NULL,
  rx_low REAL,
  rx_high REAL,
  tx_low REAL,
  tx_high REAL,
  description VARCHAR(200),
  UNIQUE(level_type, match_key)
);
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 自增主键 |
| level_type | VARCHAR(20) | 匹配级别：GLOBAL=全局，MODULE=模块类型 |
| match_key | VARCHAR(64) | 匹配键：GLOBAL时为"GLOBAL"，MODULE时为模块类型键 |
| rx_low | REAL | 接收低门限（dBm） |
| rx_high | REAL | 接收高门限（dBm） |
| tx_low | REAL | 发送低门限（dBm） |
| tx_high | REAL | 发送高门限（dBm） |
| description | VARCHAR(200) | 规则说明 |

---

### 6.5 数据查询与导出模块

#### 6.5.1 模块功能

树形分组表格展示巡检结果，支持筛选、排序、搜索、分页、Excel导出、趋势分析。

#### 6.5.2 界面显示

**数据查询页：**

页面顶部显示筛选工具栏，包含以下筛选条件：

- 轮次下拉框：选择要查询的巡检轮次
- 网络输入框：按网络筛选
- 网元输入框：按网元筛选
- 状态下拉框：按光功率状态筛选（正常/劣化/过载/无效）
- 复选框：显示无效记录、仅关注端口
- 搜索框：支持网元名/端口名/ID搜索

工具栏右侧显示操作按钮：全部展开、全部收缩、导出Excel、趋势分析。

分页控件显示当前页码和总记录数。

数据表格显示巡检结果，包含以下列：网元名、关注、槽位、端口、端口名称、波长、激光器类型、激光器状态、生产厂商、发送功率、接收功率、状态、门限(发送)、门限(接收)、巡检时间。

支持点击表头排序，状态列使用颜色标识（绿色=正常，红色=劣化，橙色=过载）。

**趋势分析弹窗：**

全屏弹窗，左侧为筛选面板，右侧为图表和数据表格。

左侧筛选面板包含：轮次多选列表（支持全选/清空）、网络/网元/端口三级联动下拉框、图表模式选择（接收功率/发送功率/综合）。

右侧显示摘要信息、ECharts趋势图表和详细数据表格。

#### 6.5.3 工作流程

**数据查询流程：**

1. 加载巡检轮次列表，填充轮次下拉框
2. 加载筛选条件（网络列表、网元列表）
3. 根据筛选条件查询巡检结果
4. 应用门限判定（实时计算）
5. 前端筛选（状态、搜索文本）
6. 排序（按列排序）
7. 分页显示
8. 渲染数据表格

**Excel导出流程：**

1. 根据当前筛选条件查询数据
2. 创建Excel工作簿
3. 写入表头（网元名、槽位、端口、功率、状态等）
4. 遍历数据写入行
5. 自动调整列宽
6. 输出文件流

**趋势分析流程：**

1. 用户选择多个轮次
2. 选择网络、网元、端口（三级联动）
3. 查询选定轮次的巡检结果
4. 生成ECharts趋势图表
5. 显示详细数据表格

#### 6.5.4 接口定义

**REST接口清单：**

| 方法 | 路径 | 功能 | 参数 | 返回 |
|------|------|------|------|------|
| GET | /api/inspection/results | 查询巡检结果 | roundId?, neId?, status? | 巡检结果列表 |
| GET | /api/inspection/export | 导出Excel | roundId?, network? | Excel文件 |
| GET | /api/inspection/trend/port | 单端口趋势 | neId, slotNo, portNo | 趋势数据列表 |
| GET | /api/inspection/trend/ne | 网元趋势 | neId | 趋势数据列表 |
| GET | /api/inspection/anomaly/summary | 异常汇总 | roundId? | 异常汇总列表 |
| GET | /api/inspection/anomaly/details | 异常详情 | roundId?, neId? | 异常详细列表 |
| POST | /api/inspection/port-watch | 切换端口监控 | neId, slotNo, portNo | 成功标志 |

---

### 6.6 定时调度模块

#### 6.6.1 模块功能

Cron定时触发巡检，配置持久化到SQLite。

#### 6.6.2 界面显示

**定时巡检配置面板：**

位于任务配置页，包含以下配置项：

- 启用开关：启用/禁用定时巡检
- 执行频率下拉框：预设7个常用频率（每天凌晨2:00、每2小时、每小时整点、每30分钟、工作日早8:00、每周一凌晨2:00、每月1号凌晨2:00）和自定义选项
- 采集范围：全网或指定网络
- 保存配置按钮
- 上次运行状态和时间显示

#### 6.6.3 工作流程

**定时调度流程：**

1. 应用启动时从数据库加载定时配置
2. 如果启用，注册CronTrigger
3. Cron触发时执行以下操作：
   - 检查是否有巡检在运行，有则跳过
   - 调用巡检服务触发巡检
   - 更新运行状态（SUCCESS/FAILED/SKIPPED）
   - 记录运行时间
   - 持久化到数据库

**配置变更流程：**

1. 用户修改定时配置
2. 前端调用保存接口
3. 后端更新内存配置
4. 持久化到数据库
5. 如果启用，重新注册CronTrigger

#### 6.6.4 接口定义

**REST接口清单：**

| 方法 | 路径 | 功能 | 参数 | 返回 |
|------|------|------|------|------|
| POST | /api/inspection/schedule/toggle | 启用/禁用定时 | enabled | 成功标志 |
| POST | /api/inspection/schedule/config | 保存定时配置 | enabled, scope, network, cron | 成功标志 |
| GET | /api/inspection/schedule | 获取定时配置 | 无 | 配置对象 |

#### 6.6.5 数据结构

**sys_config表结构：**

```sql
CREATE TABLE sys_config (
  config_key VARCHAR(64) PRIMARY KEY,
  config_value VARCHAR(512)
);
```

**定时配置项：**

| 配置键 | 说明 | 示例值 |
|--------|------|--------|
| schedule.enabled | 是否启用定时 | true/false |
| schedule.scope | 采集范围 | ALL/NETWORK |
| schedule.network | 网络名 | 骨干网A |
| schedule.cron | Cron表达式 | 0 0 2 * * ? |
| schedule.lastRunStatus | 上次运行状态 | NEVER/RUNNING/SUCCESS/FAILED |
| schedule.lastRunTime | 上次运行时间 | 2026-09-13T02:00:00 |

---

### 6.7 数据同步模块

#### 6.7.1 模块功能

MySQL→SQLite动态建表、批量同步设备数据。

#### 6.7.2 界面显示

**数据维护页：**

页面包含以下区域：

MySQL连接配置区域：显示主机地址、用户名、密码输入框，以及测试连接和保存配置按钮。

数据备份与恢复区域：显示备份数据库和恢复数据库按钮。

MySQL→SQLite数据同步区域：显示各表的同步状态（表名、记录数、同步时间），以及同步必要表、同步全部表、刷新状态按钮。

数据清除区域：显示三组复选框（同步的数据、生成的配置数据、巡检产生的数据），支持全选/取消和清除选中操作。

操作日志区域：显示审计日志表格，包含时间、操作、对象、结果、备注列。

#### 6.7.3 工作流程

**数据同步流程：**

1. 连接MySQL数据库
2. 获取MySQL中所有用户表列表
3. 遍历每个表，执行以下操作：
   - 读取MySQL表结构
   - 映射类型到SQLite（varchar→TEXT, int→INTEGER等）
   - 在SQLite中创建表（如果不存在）
   - 批量读取MySQL数据（每批5000条）
   - 批量写入SQLite（使用事务）
4. 断开MySQL连接
5. 返回同步结果

**数据清除流程：**

1. 用户选择要清除的数据类型
2. 点击清除按钮
3. 后端执行DELETE语句清除选定表的数据
4. 返回清除结果

#### 6.7.4 接口定义

**REST接口清单：**

| 方法 | 路径 | 功能 | 参数 | 返回 |
|------|------|------|------|------|
| POST | /api/sync/essential | 同步必要表 | 无 | 同步结果 |
| POST | /api/sync/all | 同步全部表 | 无 | 同步结果 |
| POST | /api/sync/clear | 清空表 | tables | 清除结果 |
| GET | /api/sync/status | 同步状态 | 无 | 各表状态 |

---

### 6.8 统计分析模块

#### 6.8.1 模块功能

设备类型统计、趋势图表、异常汇总。

#### 6.8.2 界面显示

**类型统计页：**

页面顶部显示四个统计卡片：网元总数、盘总数、端口总数、网络数。

工具栏包含网络筛选框、口径选择（库存口径/在线口径）、筛选按钮，以及图表类型切换按钮（柱状图/饼图/折线图）。

图表区域使用ECharts显示统计图表。

数据表格显示各类型的数量和占比。

**时钟拓扑页：**

页面顶部显示四个统计卡片：网元总数、锁定数、保持数、自由振荡数。

工具栏包含刷新按钮和操作提示。

拓扑图区域使用ECharts力导向图显示时钟拓扑关系，节点颜色表示时钟状态（绿色=锁定，黄色=保持，红色=自由振荡）。

右侧显示选中设备的详细信息。

#### 6.8.3 工作流程

**统计分析流程：**

1. 查询设备统计数据
2. 按设备类型分组统计
3. 计算各类型数量和占比
4. 生成统计图表
5. 显示数据表格

**时钟拓扑流程：**

1. 查询所有网元的时钟状态
2. 查询网元间的连接关系
3. 构建拓扑图数据结构
4. 使用ECharts渲染力导向图
5. 支持点击节点查看详情

#### 6.8.4 接口定义

**REST接口清单：**

| 方法 | 路径 | 功能 | 参数 | 返回 |
|------|------|------|------|------|
| GET | /api/stats/type | 按类型统计（库存） | network? | 统计列表 |
| GET | /api/stats/type/online | 按类型统计（在线） | network? | 统计列表 |
| GET | /api/stats/networks | 获取网络列表 | 无 | 网络名列表 |
| GET | /api/inventory/stats | 库存统计 | 无 | 统计对象 |
| GET | /api/inventory/networks | 网络列表 | 无 | 网络名列表 |

---

## 7. 数据库设计

### 7.1 SQLite表（本地存储）

| 表名 | 用途 | 主要字段 | 记录数估算 |
|------|------|---------|-----------|
| device_access_config | 设备接入配置 | ne_id, ne_name, ip_addr, port, username, password, connection_status | 千级 |
| conn_profile | 连接配置（全局+单设备覆盖） | scope, ne_oid, username, password, port, auto_connect | 十级 |
| inspection_round | 巡检轮次 | id, trigger_type, scope_type, status, total_count, done_count, fail_count | 百级（自动清理） |
| optical_power_inspection | 巡检结果 | round_id, ne_id, slot_no, port_no, tx_power, rx_power, tx_power_status, rx_power_status | 万级×轮次 |
| threshold_rule | 门限规则 | level_type, match_key, rx_low, rx_high, tx_low, tx_high | 十级 |
| sys_config | 系统配置 | config_key, config_value | 十级 |
| port_watched | 端口监控 | ne_id, slot_no, port_no, port_name | 百级 |
| audit_log | 审计日志 | op_time, op_type, target, result, remark | 万级 |

### 7.2 MySQL表（只读副源）

| 表名 | 用途 | 说明 |
|------|------|------|
| dmne | 网元信息 | 设备清单，包含网元ID、类型、状态 |
| defdmne | 网元类型定义 | 设备型号定义，包含类型ID和类型名 |
| emnecomm | 网元通信配置 | 设备IP地址，包含网元ID和IP |
| dmrelation | 网元关系 | 网络归属关系，包含网元ID和网络ID |
| dmnet | 网络信息 | 网络清单，包含网络ID和类型 |
| defdmnetwork | 网络类型定义 | 网络类型定义 |
| dmeo | 端口对象 | 端口信息，包含端口OID、类型、名称 |

---

## 8. REST接口汇总

### 8.1 接口统计

| 控制器 | 接口数量 | 说明 |
|--------|---------|------|
| ConnectionController | 11 | 设备连接管理 |
| InspectionController | 15 | 巡检核心功能 |
| SyncController | 4 | 数据同步 |
| StatsController | 3 | 统计分析 |
| InventoryStatsController | 2 | 库存统计 |
| **总计** | **35** | |

### 8.2 接口分类

**设备管理接口（11个）：**
- 连接操作：connectAll, disconnectAll, connect, disconnect
- 配置管理：saveGlobal, getGlobal, getDeviceConfig, saveDeviceConfig, deleteDeviceConfig
- 数据操作：refresh, getDevices

**巡检核心接口（15个）：**
- 巡检操作：start, progress, summary
- 轮次管理：rounds, compare
- 结果查询：results, export
- 门限管理：saveThreshold, getThresholds, deleteThreshold
- 趋势分析：getPortTrend, getNeTrend
- 异常统计：getAnomalySummary, getAnomalyDetails
- 端口监控：togglePortWatched
- 定时调度：toggleSchedule, saveScheduleConfig, getSchedule
- 采集参数：saveCollectParams, getCollectParams

**数据同步接口（4个）：**
- 同步操作：syncEssential, syncAll, clearTables
- 状态查询：getSyncStatus

**统计分析接口（5个）：**
- 设备统计：statsByType, statsByTypeOnline
- 网络列表：getNetworkNames, getInventoryNetworks
- 库存统计：getInventoryStats

---

## 9. 可靠性设计 Reliability Design

### 9.1 开发规模预估

| 模块 | 实际代码行数 | 说明 |
|---|---|---|
| 设备发现与管理 | ~800 | 含MySQL查询+配置持久化 |
| Qx连接管理 | ~600 | 含重连管理器+状态监听 |
| 光功率巡检 | ~1000 | 核心模块，逻辑复杂 |
| 门限判定 | ~300 | 两级匹配逻辑 |
| 数据查询与导出 | ~500 | 含树形表格+趋势分析 |
| 定时调度 | ~200 | 配置持久化 |
| 数据同步 | ~400 | 动态建表+批量写入 |
| 统计分析 | ~300 | 趋势图表+异常汇总 |
| 前端（全部） | ~3000 | SPA+ES6模块+ECharts |
| **合计** | **~7100** | - |

### 9.2 文件命名规范

| 文件类型 | 命名规则 | 示例 |
|---|---|---|
| Java实体 | 大驼峰，表名转类名 | DeviceAccessConfig.java |
| Java服务 | 大驼峰 + Service后缀 | InspectionService.java |
| Java控制器 | 大驼峰 + Controller后缀 | InspectionController.java |
| Java工具类 | 大驼峰 + Util后缀 | OidUtil.java |
| 前端JS模块 | 小驼峰 | query.js, device.js |
| 前端CSS | 小写+连字符 | style.css |

---

## 10. 难点和风险点分析 Difficulty & Risk

### 10.1 技术难点

| 序号 | 难点描述 | 影响范围 | 应对措施 |
|---|---|---|---|
| 1 | ChannelID的equals/hashCode只含ip+port，换用户连接时行为异常 | 连接管理 | 先shut()再重连，代码中明确注释 |
| 2 | SQLite单写限制，并发写入会锁冲突 | 巡检写入 | 连接池设为1，WAL模式，事务按网元分批 |
| 3 | MySQL老库GBK编码，JDBC连接必须指定 | 数据同步 | characterEncoding=GBK，表名小写 |
| 4 | 0x2410报文解析精度（float dBm） | 采集精度 | Float.intBitsToFloat转换，0xFFFFFFFF处理 |
| 5 | 门限实时计算性能（万级数据） | 查询性能 | HashMap索引，批量处理 |

### 10.2 系统限制

| 序号 | 限制描述 | 影响范围 | 说明 |
|---|---|---|---|
| 1 | 与老网管使用不同用户登录 | 连接层 | 避免单用户登录冲突 |
| 2 | 不启用UDP 9910 | 连接层 | 同机部署，绕过dying-gasp监听 |
| 3 | SQLite单写 | 存储层 | 连接池1，WAL模式读写分离 |
| 4 | MySQL只读 | 数据同步 | 不写入老库任何数据 |
| 5 | 设备千级、端口万级 | 全局 | 缓存和查询分页需优化 |

---

## 11. 附录

### 11.1 版本历史

| 版本 | 日期 | 修改人 | 修改内容 |
|------|------|--------|---------|
| V1.0 | 2026-08-27 | Rwj | 初始版本 |
| V2.0 | 2026-09-13 | Rwj | 根据实际实现更新界面显示和逻辑部分，去掉代码示例 |

### 11.2 参考文档

- REQ-QX设备直连巡检工具.md V0.4
- README.md（架构设计部分）
- QX SDK API文档

---

*文档版本: V2.0*
*更新日期: 2026-09-13*
*状态: 已实现*
