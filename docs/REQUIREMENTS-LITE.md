# QX设备直连巡检工具 需求文档（精简版）

| 项目 | 内容 |
|---|---|
| 版本 | V1.0 |
| 日期 | 2026-09-13 |

---

## 1. 项目目标

直连QX光网络设备进行光功率巡检，替代人工巡检工作。

---

## 2. 核心功能

### 2.1 设备管理
- 从MySQL自动发现设备
- 支持全局/单设备连接配置
- 一键连接/断开设备
- 断线自动重连（指数退避+熔断）

### 2.2 光功率巡检
- 手动/定时触发巡检
- 逐端口采集发送/接收光功率
- 多设备并发采集（可配并发数）
- 采集激光器状态、模块信息

### 2.3 门限判定
- 支持全局/模块类型两级门限
- 查询时实时计算状态（正常/劣化/过载）
- 门限配置即时生效

### 2.4 数据查询
- 按轮次/网络/网元/状态筛选
- 树形表格展示，支持排序分页
- Excel导出
- 趋势分析（多轮次对比）

### 2.5 数据同步
- MySQL→SQLite同步
- 数据备份与恢复
- 数据清除

### 2.6 统计分析
- 设备类型统计（柱状图/饼图）
- 时钟拓扑图

---

## 3. 非功能需求

| 类型 | 要求 |
|------|------|
| 性能 | 千级设备、万级端口 |
| 可靠性 | 自动重连、熔断机制、数据备份 |
| 可用性 | 深色/浅色主题、实时响应 |
| 安全性 | 独立用户、操作审计 |

---

## 4. 约束条件

- SQLite单写（WAL模式）
- MySQL只读
- 单设备串行采集
- 不启用UDP 9910

---

## 5. 测试用例

### 5.1 设备管理

#### TC-1.1 从MySQL同步设备

| 项目 | 内容 |
|------|------|
| 用例ID | TC-1.1 |
| 用例名称 | 从MySQL同步设备 |
| 优先级 | P0 |
| 前提条件 | MySQL老库已启动，包含网元数据 |
| 测试接口 | POST /api/connection/refresh |

**测试步骤：**
1. 启动应用，访问设备管理页面
2. 点击"同步设备"按钮
3. 等待同步完成

**输入参数：** 无

**预期结果：**
- 返回成功标志和设备数量
- 设备表格显示导入的设备
- 统计卡片更新（总数、在线、离线）
- 设备数据包含：网元名、IP、网络、设备类型

---

#### TC-1.2 保存全局连接配置

| 项目 | 内容 |
|------|------|
| 用例ID | TC-1.2 |
| 用例名称 | 保存全局连接配置 |
| 优先级 | P0 |
| 前提条件 | 已同步设备 |
| 测试接口 | PUT /api/connection/global |

**测试步骤：**
1. 展开"全局默认连接配置"面板
2. 输入用户名：admin
3. 输入密码：test123
4. 输入端口：9900
5. 点击"保存"

**输入参数：**
```json
{
  "username": "admin",
  "password": "test123",
  "port": 9900
}
```

**预期结果：**
- 返回成功
- 配置保存到SQLite conn_profile表
- 全局配置生效（所有设备使用此配置）

---

#### TC-1.3 单设备连接

| 项目 | 内容 |
|------|------|
| 用例ID | TC-1.3 |
| 用例名称 | 单设备连接 |
| 优先级 | P0 |
| 前提条件 | 已配置全局连接配置，设备IP可达 |
| 测试接口 | POST /api/connection/connect/{neOid} |

**测试步骤：**
1. 在设备表格中找到目标设备
2. 点击"连接"按钮
3. 等待连接完成

**输入参数：**
- neOid: 网元ID（如"1.1"）

**预期结果：**
- 返回成功标志
- 设备状态变为"在线"（绿色）
- 登录用户显示配置的用户名

---

#### TC-1.4 一键断开所有设备

| 项目 | 内容 |
|------|------|
| 用例ID | TC-1.4 |
| 用例名称 | 一键断开所有设备 |
| 优先级 | P1 |
| 前提条件 | 已连接多台设备 |
| 测试接口 | POST /api/connection/disconnectAll |

**测试步骤：**
1. 点击"一键断开"按钮
2. 等待断开完成

**输入参数：** 无

**预期结果：**
- 返回成功标志和断开数量
- 所有设备状态变为"离线"（灰色）
- 统计卡片更新

---

### 5.2 光功率巡检

#### TC-2.1 手动触发全网巡检

| 项目 | 内容 |
|------|------|
| 用例ID | TC-2.1 |
| 用例名称 | 手动触发全网巡检 |
| 优先级 | P0 |
| 前提条件 | 已连接至少1台设备，设备支持光功率查询 |
| 测试接口 | POST /api/inspection/start |

**测试步骤：**
1. 进入"任务配置"页面
2. 在"手动触发巡检"区域，选择范围为"全网"
3. 点击"立即巡检"
4. 切换到"任务进度"页面查看进度

**输入参数：** 无（或 network=null, neId=null）

**预期结果：**
- 返回轮次ID、状态、总设备数
- 创建巡检轮次记录（status=RUNNING）
- 进度条开始更新
- 采集完成后轮次状态变为COMPLETED

---

#### TC-2.2 按网元巡检

| 项目 | 内容 |
|------|------|
| 用例ID | TC-2.2 |
| 用例名称 | 按网元巡检 |
| 优先级 | P0 |
| 前提条件 | 已连接目标网元 |
| 测试接口 | POST /api/inspection/start |

**测试步骤：**
1. 进入"任务配置"页面
2. 选择范围为"指定网元"
3. 输入或选择网元ID
4. 点击"立即巡检"

**输入参数：**
- neId: 指定网元ID

**预期结果：**
- 仅采集指定网元的光功率
- 其他网元不受影响

---

#### TC-2.3 查询巡检进度

| 项目 | 内容 |
|------|------|
| 用例ID | TC-2.3 |
| 用例名称 | 查询巡检进度 |
| 优先级 | P1 |
| 前提条件 | 巡检任务正在运行或已完成 |
| 测试接口 | GET /api/inspection/progress |

**测试步骤：**
1. 进入"任务进度"页面
2. 点击"刷新状态"

**输入参数：** 无

**预期结果：**
- 返回进度信息：progress, total, currentNe, failures, running
- 进度条显示当前进度百分比
- 显示当前采集的网元和端口
- 失败设备列表显示失败原因

---

#### TC-2.4 导出巡检结果

| 项目 | 内容 |
|------|------|
| 用例ID | TC-2.4 |
| 用例名称 | 导出巡检结果 |
| 优先级 | P1 |
| 前提条件 | 已完成巡检，有巡检结果数据 |
| 测试接口 | GET /api/inspection/export |

**测试步骤：**
1. 进入"数据查询"页面
2. 选择要导出的轮次
3. 点击"导出Excel"
4. 等待文件下载

**输入参数：**
- roundId: 轮次ID（可选）

**预期结果：**
- 下载Excel文件
- 文件包含所有巡检结果字段
- 列宽自动调整

---

### 5.3 门限判定

#### TC-3.1 保存全局门限规则

| 项目 | 内容 |
|------|------|
| 用例ID | TC-3.1 |
| 用例名称 | 保存全局门限规则 |
| 优先级 | P0 |
| 前提条件 | 无 |
| 测试接口 | POST /api/inspection/thresholds |

**测试步骤：**
1. 进入"门限配置"页面
2. 在"全局默认门限"区域输入：
   - 接收低门限：-28
   - 接收高门限：-8
   - 发送低门限：-6
   - 发送高门限：0
3. 点击"保存"

**输入参数：**
```json
{
  "levelType": "GLOBAL",
  "matchKey": "GLOBAL",
  "rxLow": -28,
  "rxHigh": -8,
  "txLow": -6,
  "txHigh": 0
}
```

**预期结果：**
- 保存成功
- 门限规则保存到threshold_rule表
- 查询巡检结果时使用新门限计算状态

---

#### TC-3.2 按模块类型配置门限

| 项目 | 内容 |
|------|------|
| 用例ID | TC-3.2 |
| 用例名称 | 按模块类型配置门限 |
| 优先级 | P0 |
| 前提条件 | 已配置全局门限 |
| 测试接口 | POST /api/inspection/thresholds |

**测试步骤：**
1. 点击"新增规则"
2. 选择模块类型（如L16.1）
3. 输入门限值
4. 点击"保存"

**输入参数：**
```json
{
  "levelType": "MODULE",
  "matchKey": "L16.1",
  "rxLow": -28,
  "rxHigh": -8,
  "txLow": -6,
  "txHigh": 0
}
```

**预期结果：**
- 保存成功
- 模块类型规则显示在表格中
- 该模块类型使用专属门限，而非全局门限

---

#### TC-3.3 门限匹配验证

| 项目 | 内容 |
|------|------|
| 用例ID | TC-3.3 |
| 用例名称 | 门限匹配验证 |
| 优先级 | P0 |
| 前提条件 | 已配置门限规则，有巡检结果 |
| 测试接口 | GET /api/inspection/results |

**测试步骤：**
1. 确保有巡检结果数据
2. 查询巡检结果
3. 检查状态字段

**输入参数：**
- roundId: 轮次ID

**预期结果：**
- 功率在门限内：status=0（正常）
- 功率低于低门限：status=1（劣化）
- 功率高于高门限：status=2（过载）

---

### 5.4 数据查询

#### TC-4.1 按条件筛选巡检结果

| 项目 | 内容 |
|------|------|
| 用例ID | TC-4.1 |
| 用例名称 | 按条件筛选巡检结果 |
| 优先级 | P0 |
| 前提条件 | 有多个轮次的巡检结果 |
| 测试接口 | GET /api/inspection/results |

**测试步骤：**
1. 进入"数据查询"页面
2. 选择轮次
3. 输入网络筛选条件
4. 选择状态为"劣化"
5. 点击"查询"

**输入参数：**
- roundId: 轮次ID
- network: 网络名（可选）
- status: 1（劣化）

**预期结果：**
- 仅显示符合条件的记录
- 状态列显示"劣化"（红色）
- 统计信息更新

---

#### TC-4.2 趋势分析

| 项目 | 内容 |
|------|------|
| 用例ID | TC-4.2 |
| 用例名称 | 趋势分析 |
| 优先级 | P1 |
| 前提条件 | 有多个轮次的巡检结果 |
| 测试接口 | GET /api/inspection/trend/port |

**测试步骤：**
1. 点击"趋势分析"按钮
2. 选择多个轮次
3. 选择网络、网元、端口
4. 选择图表模式（接收功率）
5. 点击"查询趋势"

**输入参数：**
- neId: 网元ID
- slotNo: 槽位号
- portNo: 端口号

**预期结果：**
- 显示趋势图表（ECharts）
- 显示详细数据表格
- 显示摘要信息

---

### 5.5 数据同步

#### TC-5.1 同步必要表

| 项目 | 内容 |
|------|------|
| 用例ID | TC-5.1 |
| 用例名称 | 同步必要表 |
| 优先级 | P0 |
| 前提条件 | MySQL已配置且可连接 |
| 测试接口 | POST /api/sync/essential |

**测试步骤：**
1. 进入"数据维护"页面
2. 配置MySQL连接参数
3. 点击"测试连接"
4. 点击"同步必要表"
5. 等待同步完成

**输入参数：** 无

**预期结果：**
- 测试连接成功
- 同步成功，返回同步结果
- SQLite中创建相应表
- 数据记录数正确

---

#### TC-5.2 数据备份

| 项目 | 内容 |
|------|------|
| 用例ID | TC-5.2 |
| 用例名称 | 数据备份 |
| 优先级 | P1 |
| 前提条件 | SQLite数据库有数据 |
| 测试接口 | 下载文件 |

**测试步骤：**
1. 进入"数据维护"页面
2. 点击"备份数据库"
3. 等待文件下载

**输入参数：** 无

**预期结果：**
- 下载.db文件
- 文件大小合理
- 文件可被恢复功能使用

---

### 5.6 统计分析

#### TC-6.1 设备类型统计

| 项目 | 内容 |
|------|------|
| 用例ID | TC-6.1 |
| 用例名称 | 设备类型统计 |
| 优先级 | P1 |
| 前提条件 | 已同步设备数据 |
| 测试接口 | GET /api/stats/type |

**测试步骤：**
1. 进入"类型统计"页面
2. 查看统计卡片
3. 查看图表
4. 切换图表类型（柱状图/饼图/折线图）

**输入参数：**
- network: 网络名（可选）

**预期结果：**
- 统计卡片显示总数、网元数、端口数、网络数
- 图表正确显示各类型占比
- 图表类型切换正常

---

## 6. 测试用例汇总

| 模块 | 用例数 | P0 | P1 | P2 |
|------|--------|----|----|----|
| 设备管理 | 4 | 3 | 1 | 0 |
| 光功率巡检 | 4 | 2 | 2 | 0 |
| 门限判定 | 3 | 3 | 0 | 0 |
| 数据查询 | 2 | 1 | 1 | 0 |
| 数据同步 | 2 | 1 | 1 | 0 |
| 统计分析 | 1 | 0 | 1 | 0 |
| **总计** | **16** | **10** | **6** | **0** |

---

## 7. 单元测试

### 7.1 测试概况

| 测试类 | 被测类 | 测试数 | 技术框架 |
|--------|--------|--------|----------|
| ThresholdServiceTest | ThresholdService | 7 | Mockito + JUnit 5 |
| InspectionServiceTest | InspectionService | 7 | Mockito + JUnit 5 |
| QxConnectionServiceTest | QxConnectionService | 8 | Mockito + JUnit 5 |
| DeviceAccessServiceTest | DeviceAccessService | 9 | Mockito + JUnit 5 |
| OidUtilTest | OidUtil | 20 | JUnit 5 |
| **总计** | | **51** | |

### 7.2 ThresholdServiceTest — 门限判定服务

被测类：`com.optel.qxinspection.service.ThresholdService`
测试类路径：`src/test/java/com/optel/qxinspection/service/ThresholdServiceTest.java`

| 测试方法 | 测试场景 | 前置条件 | 输入 | 预期结果 |
|----------|----------|----------|------|----------|
| testApplyThresholds_WithGlobalRule | 全局门限匹配 | 数据库有1条GLOBAL规则 | 记录txPower=-3.0, rxPower=-15.0, moduleTypeKey=S4.1 | txPowerStatus=0(正常), rxPowerStatus=0(正常) |
| testApplyThresholds_WithModuleRule | 模块类型门限匹配 | 数据库有GLOBAL+MODULE(L16.1)规则 | 记录txPower=-3.0, rxPower=-15.0, moduleTypeKey=L16.1 | 使用L16.1专属门限，txPowerStatus=0, rxPowerStatus=0 |
| testApplyThresholds_DegradationStatus | 劣化状态判定 | 数据库有1条GLOBAL规则 | 记录txPower=-3.0, rxPower=-30.0(低于-27低门限) | rxPowerStatus=1(劣化) |
| testApplyThresholds_OverloadStatus | 过载状态判定 | 数据库有1条GLOBAL规则 | 记录txPower=5.0(高于3.0高门限), rxPower=-15.0 | txPowerStatus=2(过载) |
| testApplyThresholds_UnsupportedRecord | 不支持光功率的记录 | 数据库有1条GLOBAL规则 | 记录supported=false, txPower=null, rxPower=null | txPowerStatus=null, rxPowerStatus=null |
| testApplyThresholds_EmptyRules | 无门限规则 | 数据库门限规则为空 | 记录txPower=-3.0, rxPower=-15.0 | 使用默认值，txPowerStatus=0, rxPowerStatus=0 |
| testApplyThresholds_BoundaryValues | 边界值测试 | 数据库有1条GLOBAL规则 | 记录txPower=-27.0(等于低门限), rxPower=3.0(等于高门限) | 边界值视为正常，txPowerStatus=0, rxPowerStatus=0 |

### 7.3 InspectionServiceTest — 巡检服务

被测类：`com.optel.qxinspection.service.InspectionService`
测试类路径：`src/test/java/com/optel/qxinspection/service/InspectionServiceTest.java`

| 测试方法 | 测试场景 | 前置条件 | 输入 | 预期结果 |
|----------|----------|----------|------|----------|
| testTriggerInspectionAll_Success | 手动触发全网巡检 | 有1台已连接设备 | 触发全网巡检 | 返回InspectionRound对象，triggerType=MANUAL, scopeType=ALL，保存轮次记录 |
| testTriggerInspectionByNe_Success | 按网元触发巡检 | 有1台已连接设备 | neId=1.1 | 返回InspectionRound对象，scopeType=SINGLE，仅采集指定网元 |
| testGetProgress_NoRunningInspection | 查询进度-无运行中巡检 | 无运行中巡检 | 无 | 返回progress=0, total=0, running=false |
| testListRounds_Empty | 查询轮次列表-空 | 无巡检轮次记录 | 无 | 返回空列表 |
| testListRounds_WithRecords | 查询轮次列表-有记录 | 有2条巡检轮次记录 | 无 | 返回2条记录，按开始时间倒序排列 |
| testGetSummary_NoData | 查询统计摘要-无数据 | 无巡检结果数据 | 无 | 返回totalPorts=0, supportedPorts=0, overThresholdPorts=0 |
| testTriggerInspectionAll_ConcurrentProtection | 并发保护测试 | 有1台已连接设备 | 两次触发全网巡检 | 仅第一次成功创建轮次，第二次被拒绝 |

### 7.4 QxConnectionServiceTest — 连接服务

被测类：`com.optel.qxinspection.service.QxConnectionService`
测试类路径：`src/test/java/com/optel/qxinspection/service/QxConnectionServiceTest.java`

| 测试方法 | 测试场景 | 前置条件 | 输入 | 预期结果 |
|----------|----------|----------|------|----------|
| testIsConnected_NotConnected | 查询未连接状态 | 设备未连接 | neId=1.1 | 返回false |
| testConnectOne_Success | 单设备连接成功 | 设备已配置，全局配置存在 | neId=1.1 | 返回true，调用channelManager.connect() |
| testConnectOne_DeviceNotFound | 单设备连接-设备不存在 | 设备未同步 | neId=99.99 | 抛出IllegalArgumentException |
| testDisconnectOne_Success | 单设备断开 | 设备已连接 | neId=1.1 | 调用channelManager.shut() |
| testConnectAll_MultipleDevices | 批量连接多设备 | 有2台设备已配置 | 全量连接 | 返回true，调用2次connect |
| testDisconnectAll_Success | 批量断开多设备 | 有2台设备已连接 | 全量断开 | 返回断开数量2，调用2次shut |
| testGetEffectiveProfile_GlobalOnly | 获取有效配置-仅全局 | 仅有GLOBAL配置 | neId=1.1 | 返回全局配置(username=admin, port=9900) |
| testGetEffectiveProfile_WithOverride | 获取有效配置-网元覆盖 | 有GLOBAL+NE覆盖配置 | neId=1.1 | 返回覆盖配置(username=admin2, port=9901) |

### 7.5 DeviceAccessServiceTest — 设备同步服务

被测类：`com.optel.qxinspection.service.DeviceAccessService`
测试类路径：`src/test/java/com/optel/qxinspection/service/DeviceAccessServiceTest.java`

| 测试方法 | 测试场景 | 前置条件 | 输入 | 预期结果 |
|----------|----------|----------|------|----------|
| testSyncDevices_Success | 同步设备成功 | MySQL有1条网元记录 | 同步设备 | 返回1，保存1条DeviceAccessConfig |
| testSyncDevices_EmptyDatabase | 同步设备-空数据库 | MySQL无网元记录 | 同步设备 | 返回0，不保存任何设备 |
| testSyncDevices_MultipleDevices | 同步多台设备 | MySQL有2条网元记录 | 同步设备 | 返回2，保存2条DeviceAccessConfig |
| testSyncDevices_NoIpAddr | 同步设备-无IP地址 | MySQL网元有记录但无IP | 同步设备 | 返回1，设备IP为空 |
| testGetAllDevices | 查询所有设备 | SQLite有2条设备记录 | 无 | 返回2条设备记录 |
| testGetDeviceByNeId_Found | 按网元ID查询-找到 | SQLite有neId=1.1的设备 | neId=1.1 | 返回Optional包含设备信息 |
| testGetDeviceByNeId_NotFound | 按网元ID查询-未找到 | SQLite无neId=99.99的设备 | neId=99.99 | 返回Optional.empty |
| testUpdateConnectionStatus_Success | 更新连接状态成功 | SQLite有neId=1.1的设备 | neId=1.1, status=1 | 设备connectionStatus更新为1 |
| testUpdateConnectionStatus_DeviceNotFound | 更新连接状态-设备不存在 | SQLite无neId=99.99的设备 | neId=99.99, status=1 | 不抛出异常，不保存 |

### 7.6 OidUtilTest — OID工具类

被测类：`com.optel.qxinspection.util.OidUtil`
测试类路径：`src/test/java/com/optel/qxinspection/util/OidUtilTest.java`

| 测试方法 | 测试场景 | 输入 | 预期结果 |
|----------|----------|------|----------|
| testExtractSlotId_ValidOid | 提取槽位号-有效OID | "1.1:1:2:3:0" | 1 |
| testExtractSlotId_InvalidOid | 提取槽位号-无效OID | "invalid" | -1 |
| testExtractSlotId_EmptyOid | 提取槽位号-空字符串 | "" | -1 |
| testExtractSlotId_NullOid | 提取槽位号-null | null | -1 |
| testExtractPortId_ValidOid | 提取端口号-有效OID | "1.1:1:2:3:0" | 2 |
| testExtractPortId_InvalidOid | 提取端口号-无效OID | "invalid" | -1 |
| testExtractPortId_EmptyOid | 提取端口号-空字符串 | "" | -1 |
| testExtractPortId_NullOid | 提取端口号-null | null | -1 |
| testExtractNeId_ValidOid | 提取网元ID-有效OID | "1.1:1:2:3:0" | "1.1" |
| testExtractNeId_InvalidOid | 提取网元ID-无效OID | "invalid" | "" |
| testExtractNeId_EmptyOid | 提取网元ID-空字符串 | "" | "" |
| testExtractNeId_NullOid | 提取网元ID-null | null | "" |
| testBuildOid_ValidParams | 构建OID-有效参数 | neId="1.1", slotId=1, portId=2 | "1.1:1:2" |
| testBuildOid_WithTimeslot | 构建OID-带时隙 | neId="1.1", slotId=1, portId=2, timeslot=0 | "1.1:1:2:0" |
| testBuildOid_NullNeId | 构建OID-null网元ID | neId=null, slotId=1, portId=2 | ":1:2" |
| testBuildOid_EmptyNeId | 构建OID-空网元ID | neId="", slotId=1, portId=2 | ":1:2" |
| testIsValidOid_Valid | 验证OID有效性-有效 | "1.1:1:2:3:0" | true |
| testIsValidOid_Invalid | 验证OID有效性-无效 | "invalid" | false |
| testIsValidOid_Empty | 验证OID有效性-空字符串 | "" | false |
| testIsValidOid_Null | 验证OID有效性-null | null | false |
| testParseOid_Valid | 解析OID-有效 | "1.1:1:2:3:0" | 长度5的数组["1.1","1","2","3","0"] |
| testParseOid_Invalid | 解析OID-无效 | "invalid" | 长度1的数组["invalid"] |
| testParseOid_Empty | 解析OID-空字符串 | "" | 长度0的数组 |
| testParseOid_Null | 解析OID-null | null | 长度0的数组 |

---

*版本: V1.0 | 日期: 2026-09-13*
