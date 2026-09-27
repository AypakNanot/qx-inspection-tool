# inventory-stats Specification

## Purpose
TBD - created by archiving change fix-slot-port-type-labels. Update Purpose after archive.
## Requirements
### Requirement: 盘/端口类型统计显示类型名称

类型统计页的盘类型（dmeo cid=4）与端口类型（dmeo cid=5）统计 SHALL 按类型名称分组展示，类型名称 SHALL 取自老库权威映射表 defobject(cid, type → cName)（经同步冗余写入 dmeo.typeName）；当 typeName 缺失（数据尚未重新同步）时 SHALL 回退显示原始 type 编码。

#### Scenario: 盘类型显示名称

- **WHEN** 用户切换到"盘类型"统计
- **THEN** 表格与图表展示如 MCP、STM16、FE 等类型名称，而非 2001、2008、2010 数字编码

#### Scenario: 端口类型显示名称

- **WHEN** 用户切换到"端口类型"统计
- **THEN** 表格与图表展示如 STM16_O、HKA 等类型名称，而非 20009、20023 数字编码

#### Scenario: 未重新同步时回退

- **WHEN** 部署后尚未重新同步网络数据，dmeo.typeName 为空
- **THEN** 该类型标签回退显示原始 type 编码，统计功能不受影响

### Requirement: 网元类型统计口径不变

网元类型统计 SHALL 继续按 neTypeName（defdmne 映射）分组展示，本次变更 SHALL NOT 影响其行为。

#### Scenario: 网元统计保持原样

- **WHEN** 用户查看"网元类型"统计
- **THEN** 展示结果与变更前一致（如 MatrixEdge2050 等）

