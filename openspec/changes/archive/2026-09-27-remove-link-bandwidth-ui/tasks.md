## 1. Implementation

- [x] 1.1 `query.js`：移除"链路带宽"表头（第 1 行 colspan 2）与第 3 行 2 个 link 列头、渲染处 2 个 `numberCell`
- [x] 1.2 `style.css`：删除 `.query-th-link`（明主题 + 暗主题）2 条规则
- [x] 1.3 `InspectionController`：`EXPORT_COLUMNS` 28→26、去掉"链路带宽"合并表头与 2 个数据单元格写入，更新注释
- [x] 1.4 `InspectionService.endOf()`：移除 linkCapacity/linkUsed 优先级逻辑，恢复纯端口级；实体 `linkTotalBandwidth/linkUsedBandwidth` 与同步处补"预留、暂不展示"注释
- [x] 1.5 全量检查无残留引用（grep `query-th-link` / `链路带宽` / `linkTotalBandwidth` 展示点）
- [x] 1.6 运行测试 `mvn test`，全部通过（169/169，BUILD SUCCESS）
- [x] 1.7 更新本 tasks 勾选状态
