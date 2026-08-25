# 页面设计进度

## 页面清单

| # | 页面 | 状态 | 说明 |
|---|---|---|---|
| 1 | 设备管理 | ✅ 已完成 | 统计卡片 + 全局配置折叠面板 + 操作栏 + 设备表格 + 单设备配置弹窗 |
| 2 | 类型统计 | ✅ 已完成 | 总览卡片(4个) + 网络/口径筛选 + 图表(bar/pie/line) + 类型表格 |
| 3 | 门限配置 | ✅ 已完成 | 优先级说明 + 全局门限编辑 + 模块类型规则表 + 型号规则表 + 新增/编辑/删除弹窗 |
| 4 | 任务配置 | ✅ 已完成 | 定时配置(Cron) + 采集参数 + 手动触发(网络递归checkbox) |
| 5 | 任务进度 | ✅ 已完成 | 状态卡片 + 进度条 + 失败列表 + 摘要(按模块类型) + 历史轮次 |
| 6 | 数据查询 | ✅ 已完成 | 轮次选择 + 多维筛选 + 数据表格(标色) + 导出 |

---

## 1. 设备管理页

**需求来源**: F1.1 设备发现 + F1.2 统一连接配置 + F1.3 连接管理

### 统计卡片
- 设备总数（蓝色）、在线（绿色）、离线（灰色）

### 全局连接配置（折叠面板）
- 用户名、密码、端口（默认9900）
- 保存后对所有设备生效，单设备可单独覆盖

### 操作栏
- 同步设备（从MySQL刷新）、一键连接、一键断开、刷新

### 设备表格
| 列 | 说明 |
|---|---|
| 网元名 | neName |
| IP地址 | ipAddr |
| 所属网络 | networkName |
| 设备类型 | neTypeName |
| 连接状态 | 绿点=在线、灰点=离线 |
| 登录用户 | 全局或单设备覆盖的用户名 |
| 操作 | 连接/断开按钮 + 配置按钮 |

### 单设备配置弹窗
- 用户名、密码、端口（留空使用全局配置）
- 清除覆盖按钮

### API
- `GET /api/connection/status` - 设备列表
- `GET /api/connection/status/summary` - 统计
- `POST /api/connection/connect-all` - 一键连接
- `POST /api/connection/disconnect-all` - 一键断开
- `POST /api/connection/connect/{neOid}` - 单设备连接
- `POST /api/connection/disconnect/{neOid}` - 单设备断开
- `GET /api/connection/config/global` - 全局配置
- `PUT /api/connection/config/global` - 保存全局配置
- `GET /api/connection/config/{neOid}` - 单设备配置
- `PUT /api/connection/config/{neOid}` - 保存单设备配置
- `DELETE /api/connection/config/{neOid}` - 删除单设备配置
- `POST /api/database/sync-devices` - 同步设备

---

## 2. 类型统计页

**需求来源**: F2 设备类型统计 + M6 库存统计

### 统计卡片（4列）
- 网元总数（蓝色）、盘总数（紫色）、端口总数（绿色）、网络数（橙色）

### 筛选区
- 网络下拉搜索
- 口径切换：库存口径 / 在线口径

### 图表区
- 柱状图/饼图/折线图可切换
- 按设备类型展示分布

### 表格
- 类型名称 | 数量 | 占比

### API
- `GET /api/inventory/overview` - 总览
- `GET /api/inventory/networks` - 网络列表
- `GET /api/stats/type` - 库存口径统计
- `GET /api/stats/type/online` - 在线口径统计

---

## 3. 门限配置页

**需求来源**: F3.5 光功率门限判定

### 说明区
- 匹配优先级：PART > MODULE > GLOBAL
- 即时生效，无需重新采集

### 全局默认门限（折叠面板）
- 接收低/高门限、发送低/高门限
- 保存按钮

### 模块类型规则表
| 列 | 说明 |
|---|---|
| 模块类型 | moduleTypeKey（如 2.5G-L、GE-LX） |
| 接收低/高 (dBm) | - |
| 发送低/高 (dBm) | - |
| 说明 | - |
| 操作 | 编辑/删除 |

### 型号规则表
| 列 | 说明 |
|---|---|
| 型号编码 | partNumber |
| 接收低/高 (dBm) | - |
| 发送低/高 (dBm) | - |
| 说明 | - |
| 操作 | 编辑/删除 |

### 新增/编辑弹窗
- 匹配键（moduleTypeKey 或 partNumber）
- 接收低/高门限、发送低/高门限
- 说明

### API
- `GET /api/inspection/thresholds` - 查询所有规则
- `POST /api/inspection/thresholds` - 创建或更新规则
- `DELETE /api/inspection/thresholds/{id}` - 删除规则
- `GET /api/inspection/thresholds/snapshot` - 门限快照（导出用）

---

## 4. 任务配置页

**需求来源**: F3.1 任务配置

### 定时巡检配置（折叠面板）
- 启用/禁用定时巡检（开关）
- 调度表达式：Cron（如 `0 0 2 * * ?` 每天凌晨2点）
- 采集范围：全网 / 指定网络 / 指定网元
- 最后一次运行状态和时间

### 采集参数配置
- 多设备并发数（默认 10）
- 保留轮次上限（默认 10，超龄自动清理）

### 手动触发区
- 巡检范围：全网 / 指定网络 / 指定网元
- 当选择"指定网络"时：
  - 网络下拉（支持搜索）
  - **递归复选框**：是否包含子网络（待实现后端逻辑）
- 当选择"指定网元"时：网元搜索下拉
- 立即巡检按钮

### 待实现（后端改动）
- `InspectionService.resolveTargets()` 需支持递归查询子网络
- 网络层级关系查询：从 DmRelation 或 DmNet 表获取父子关系
- 递归时需加载所有子网络下的设备

### API
- `POST /api/inspection/start` - 手动触发（参数：network/neId）
- `GET /api/inspection/schedule` - 查询定时状态
- `POST /api/inspection/schedule/toggle` - 启用/禁用定时
- `GET /api/inspection/progress` - 查询进度

---

## 5. 任务进度页

**需求来源**: F3.2 采集执行 + M4 进度展示

### 当前任务状态
- 状态标识：空闲 / 进行中 / 已完成 / 失败
- 进度条：已完成/总数 + 百分比
- 当前采集设备名

### 失败设备列表
- 失败设备名 + 失败原因（错误信息）
- 失败数统计
- **关键要求**：采集某网元失败时，记录错误信息后立即跳到下一个，不阻塞其他设备

### 本轮摘要
- 总端口数、支持光功率端口数、越限端口数
- 按模块类型分组统计：类型 | 总数 | 越限数

### 历史轮次列表
- 轮次ID、触发方式、范围、状态、设备数、完成/失败、开始时间

---

## 6. 数据查询页

**需求来源**: F3.3 数据缓存 + F3.4 查询筛选 + F3.6 导出

### 轮次选择
- 下拉选择历史轮次，默认最新

### 筛选区
- 网络（下拉搜索）
- 网元（下拉搜索）
- 端口类型（下拉）
- 是否支持（下拉：全部/支持/不支持）
- 判定状态（下拉：全部/正常/劣化/过载/无效）

### 数据表格
| 列 | 说明 |
|---|---|
| 网元名 | - |
| 槽位/端口 | slotNo/portNo |
| 模块类型 | moduleTypeKey |
| 发送功率 | dBm，越限标红 |
| 接收功率 | dBm，越限标红 |
| 状态 | 正常(绿)/劣化(红)/过载(橙)/无效(灰) |
| 门限值 | 接收/发送低高 |
| 巡检时间 | - |

### 操作栏
- 导出 Excel（按当前筛选结果）
- 趋势入口：点击网元查看历史趋势

### API
- `GET /api/inspection/results` - 查询结果（参数：roundId/neId/network）
- `GET /api/inspection/rounds` - 轮次列表
- `GET /api/inspection/export` - 导出 Excel（参数：roundId/network）
- `GET /api/inspection/trend/port` - 单端口趋势
- `GET /api/inspection/trend/ne` - 网元趋势
