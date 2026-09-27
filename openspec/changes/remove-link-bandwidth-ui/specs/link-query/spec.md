## ADDED Requirements

### Requirement: 数据查询表格展示 A/Z 端口级带宽

数据查询页链路结果表格 SHALL 展示序号、链路名称、A端 12 列、Z端 12 列，其中 A/Z 端的"总/已用/利用率"SHALL 取自端口级带宽（dmconnection 的 aCapacity/aUsed 与 zCapacity/zUsed），SHALL NOT 受链路级别带宽（linkCapacity/linkUsed）影响；表格 SHALL NOT 展示链路级别带宽列。

#### Scenario: 表头不含链路带宽

- **WHEN** 用户打开数据查询页查看链路巡检结果
- **THEN** 表头为 序号 + 链路名称 + A端(colspan 12) + Z端(colspan 12)，无"链路带宽"分组

#### Scenario: A/Z 端带宽取端口级数据

- **WHEN** 某链路的 linkCapacity/linkUsed 与 aCapacity/aUsed 同时存在且不一致
- **THEN** 表格 A端/Z端的"总/已用/利用率"按端口级字段展示

### Requirement: Excel 导出与表格列一致

巡检结果 Excel 导出 SHALL 为 26 列（序号 + 链路名称 + A端 12 列 + Z端 12 列），SHALL NOT 包含链路级别带宽列，且导出列结构 SHALL 与前端表格一致。

#### Scenario: 导出列数与表头

- **WHEN** 用户点击导出巡检结果
- **THEN** 生成的 Excel 主表为 26 列，首行分组表头为 序号 | 链路名称 | A端(2-13) | Z端(14-25)，无"链路带宽"分组

### Requirement: 链路级别带宽数据预留采集

系统 SHALL 继续在数据同步时从 linkbandwidth(dir=1) 写入 dmconnection.linkCapacity/linkUsed，并在巡检结果中记录 linkTotalBandwidth/linkUsedBandwidth 且通过 API 返回，作为预留数据；该数据 SHALL NOT 出现在前端表格与 Excel 导出中。

#### Scenario: 同步与 API 保留

- **WHEN** 用户执行网络数据同步并触发巡检
- **THEN** dmconnection 与 link_inspection_result 的链路级带宽字段照常写入，查询接口仍返回 linkTotalBandwidth/linkUsedBandwidth，但界面与导出均不展示
