# Change: 类型统计页盘/端口类型显示类型名称而非数字编码

## Why

类型统计页的"盘类型/端口类型"标签页按 `dmeo.type` 原始类型码（2000/20009…）分组展示，用户不认识数字；网元类型走 `neTypeName` 显示正常。老库存在权威映射表 `defobject(cid, type → cName)`，对盘（169 条）/端口（77 条）实际出现的类型 100% 覆盖（已对真实数据验证 0 缺失）。

## What Changes

- **同步**：`DynamicSyncService` 增查 `SELECT cid, type, cName FROM defobject WHERE cid IN (4, 5)`，写入 `dmeo` 新冗余列 `typeName`（仿照现有 `neTypeName` 模式）
- **Schema**：`SQLiteSchemaInitializer` `dmeo` 建表/迁移增加 `typeName TEXT`（`addColumnIfNotExists`，非破坏）
- **统计**：`InventoryStatsService.getDmeoStats()` 分组标签改为 `typeName`，为空时回退 `type`（兼容未重新同步的旧数据）
- **不动**：网元统计（`neTypeName`）、前端（表头/图表直接渲染后端返回的 `name`）
- 部署后需在「数据维护」页重新同步一次网络数据，typeName 才会填充；未同步期间回退显示原类型码

## Impact

- Affected specs: `inventory-stats`（新增：类型统计标签口径）
- Affected code:
  - `src/main/java/com/optel/qxinspection/config/SQLiteSchemaInitializer.java`
  - `src/main/java/com/optel/qxinspection/service/DynamicSyncService.java`
  - `src/main/java/com/optel/qxinspection/service/InventoryStatsService.java`
  - 测试：`DynamicSyncServiceTest`（INSERT 列 + defobject 映射断言）、新增 `InventoryStatsServiceTest`
- 数据量：`defobject` 仅 146 行（cid 4/5），同步开销可忽略
