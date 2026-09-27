## 1. Implementation

- [x] 1.1 `SQLiteSchemaInitializer`：`dmeo` 建表语句与 `addColumnIfNotExists` 增加 `typeName TEXT`
- [x] 1.2 `DynamicSyncService`：增查 `defobject WHERE cid IN (4,5)`，`INSERT_DMEO_SQL` 增列，`buildDmeoRows` 写入类型名（盘/端口），补"类型名来自 defobject"注释
- [x] 1.3 `InventoryStatsService.getDmeoStats()`：改为按 `typeName` 分组，空则回退 `type`
- [x] 1.4 更新 `DynamicSyncServiceTest`（INSERT 列、defobject mock、typeName 断言）；新增 `InventoryStatsServiceTest`（名称分组 + 回退两口径）
- [x] 1.5 `mvn test` 全绿
- [x] 1.6 勾选本清单
