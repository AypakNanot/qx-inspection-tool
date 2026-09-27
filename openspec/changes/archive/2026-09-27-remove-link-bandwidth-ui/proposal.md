# Change: 隐藏链路级别带宽的界面展示与导出（后台数据保留）

## Why

用户确认链路级别带宽（`3ad09ec` 引入的绿色"链路带宽"2 列）当前不需要，但希望保留后台采集与存储，以便未来需要时直接放出来。

## What Changes

- **前端隐藏**：`query.js` 数据查询表格移除"链路带宽"表头（colspan 2）与对应 2 个数据单元格；`style.css` 移除 `.query-th-link` 样式（明/暗主题各 1 条）
- **导出裁剪**：`InspectionController` Excel 导出从 28 列恢复为 26 列，移除"链路带宽"合并表头与 2 个数据列
- **数据口径修正**：`InspectionService.endOf()` 移除"优先使用链路级别带宽（linkCapacity/linkUsed）、降级端口级"的逻辑，恢复纯端口级（aCapacity/zCapacity），使可见的 A端/Z端"总/已用/利用率"不再受链路级数据影响
- **后台保留（明确不动）**：
  - `DynamicSyncService` 继续从 `linkbandwidth WHERE dir = 1` 同步并写入 `dmconnection.linkCapacity/linkUsed`
  - `LinkInspectionResult` 实体与 API 继续返回 `linkTotalBandwidth/linkUsedBandwidth`（预留字段）
  - SQLite 建表列、历史轮次数据、巡检前自动同步均不变
- 实体字段与同步逻辑补充"预留、暂不展示"注释，防止后人误删

## Impact

- Affected specs: `link-query`（新增：数据查询展示口径）
- Affected code:
  - `src/main/resources/static/js/query.js`
  - `src/main/resources/static/css/style.css`
  - `src/main/java/com/optel/qxinspection/controller/InspectionController.java`
  - `src/main/java/com/optel/qxinspection/service/InspectionService.java`（仅 `endOf()`）
  - `src/main/java/com/optel/qxinspection/entity/sqlite/LinkInspectionResult.java`（仅注释）
  - 测试：`DynamicSyncServiceTest` / `InspectionServiceTest` 无需改动（同步保留；`linkCapacity` 仅以 0 入参，无优先级断言；无导出列数断言）
- 未来恢复方式：`git revert` 本变更单个提交即可（数据一直在）
- 性能影响：≈ 0（同步多 1 张千级行表查询、巡检多 2 列读取，均在噪声级别）
