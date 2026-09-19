# 链路带宽数据同步修正方案（v4）

## 1. 问题根因

### 1.1 数据流现状

```
同步阶段：
MySQL.portbandwidth（端口级别） → SQLite.dmconnection.aUsed/zUsed（端口级别）

查询阶段：
SQLite.dmconnection.aUsed/zUsed → InspectionService → link_inspection_result → 前端显示
```

### 1.2 问题本质

| 数据来源 | 计算逻辑 | 覆盖范围 |
|---------|---------|---------|
| `portbandwidth` | 从**单个端口**出发，查询经过该端口的交叉连接和路径 | 端口级别 |
| `linkbandwidth` | 从**链路两端**出发，查询经过该链路的所有交叉连接和路径，取并集 | 链路级别 |

### 1.3 实际数据对比（链路 1139）

| 字段 | 值 | 来源 |
|-----|---|------|
| dmconnection.aUsed | 747 | portbandwidth（A端口级别） |
| dmconnection.zUsed | 746 | portbandwidth（Z端口级别） |
| linkbandwidth.dir=1 | **810** | linkbandwidth（链路级别，并集） |

**结论**：老系统显示的链路已使用带宽是 **810**（链路级别），而我们显示的是 **747/746**（端口级别），两者计算口径不同。

## 2. 修改方案

### 2.1 核心思路

在同步阶段，从 MySQL 的 `linkbandwidth` 表查询 **dir=1（双向）** 的数据，冗余到 SQLite 的 `dmconnection` 表中。保留现有端口级别字段，新增链路级别字段，两套数据并存。

**统一取双向（dir=1），不关心单向。**

### 2.2 修改范围

| 文件 | 修改内容 |
|-----|---------|
| `SQLiteSchemaInitializer.java` | dmconnection 表新增 2 字段，link_inspection_result 表新增 2 字段 |
| `DynamicSyncService.java` | 同步时查询 linkbandwidth，填充 dmconnection 新增字段 |
| `InspectionService.java` | 查询/采集/组装时使用链路级别字段 |
| `LinkInspectionResult.java` | 新增链路级别带宽字段 |
| `InspectionController.java` | 导出 Excel 增加链路级别带宽列 |
| `query.js` | 前端表格增加链路级别带宽列 |
| `InspectionServiceTest.java` | 更新相关测试 |

### 2.3 表结构变更

#### dmconnection 表新增字段

| 字段名 | 类型 | 含义 | 数据来源 |
|-------|------|------|---------|
| `linkCapacity` | INTEGER | 链路级别总容量（VC-12数） | linkbandwidth.dir=1 |
| `linkUsed` | INTEGER | 链路级别已用容量（VC-12数） | linkbandwidth.dir=1 |

#### link_inspection_result 表新增字段

| 字段名 | 类型 | 含义 |
|-------|------|------|
| `link_total_bandwidth` | INTEGER | 链路级别总容量（VC-12数，采集时写入） |
| `link_used_bandwidth` | INTEGER | 链路级别已用容量（VC-12数，采集时写入） |

### 2.4 具体实现

#### 步骤 1：修改 SQLiteSchemaInitializer.java

在 dmconnection 表的建表语句中新增 2 个字段：

```sql
linkCapacity INTEGER DEFAULT 0,
linkUsed INTEGER DEFAULT 0
```

在 link_inspection_result 表的建表语句中新增 2 个字段：

```sql
link_total_bandwidth INTEGER,
link_used_bandwidth INTEGER
```

#### 步骤 2：修改 DynamicSyncService.java

在 syncNetworks 方法中：

```java
// 1. 查询 linkbandwidth 表，只取 dir=1（双向）
List<Map<String, Object>> allLinkBandwidth = 
    mysqlJdbc.queryForList("SELECT * FROM linkbandwidth WHERE dir = 1");

// 2. 构建链路带宽索引（oid → BandWidthData）
Map<String, LinkBwData> linkBwIndex = buildLinkBwIndex(allLinkBandwidth);

// 3. 在 buildConnectionRows 中，填充新增字段
```

新增 buildLinkBwIndex 方法：

```java
private Map<String, LinkBwData> buildLinkBwIndex(List<Map<String, Object>> allLinkBandwidth) {
    Map<String, LinkBwData> index = new HashMap<>();
    for (Map<String, Object> row : allLinkBandwidth) {
        String oid = toStr(row.get("oid"));
        int capacity = toInt(row.get("capacity"));
        int used = toInt(row.get("used"));
        index.put(oid, new LinkBwData(capacity, used));
    }
    return index;
}
```

新增 LinkBwData 记录：

```java
private record LinkBwData(int capacity, int used) {}
```

修改 buildConnectionRows 方法，在返回的 Object[] 中增加 2 个字段：

```java
// 从 linkBwIndex 获取链路级别数据
LinkBwData linkBw = linkBwIndex.get(oid);

return new Object[]{
    // ... 现有字段 ...
    linkBw != null ? linkBw.capacity() : 0,  // linkCapacity
    linkBw != null ? linkBw.used() : 0        // linkUsed
};
```

#### 步骤 3：修改 LinkInspectionResult.java

新增字段：

```java
@JsonProperty("linkTotalBandwidth")
@Column(name = "link_total_bandwidth")
private Double linkTotalBandwidth;

@JsonProperty("linkUsedBandwidth")
@Column(name = "link_used_bandwidth")
private Double linkUsedBandwidth;
```

#### 步骤 4：修改 InspectionService.java

修改 LINK_SELECT_SQL，增加新字段：

```java
private static final String LINK_SELECT_SQL = """
    SELECT "oid", "name", "aEnd", "zEnd",
           "aNetworkName", "aNeName", "aNeTypeName", "aPortName", "aCapacity", "aUsed",
           "zNetworkName", "zNeName", "zNeTypeName", "zPortName", "zCapacity", "zUsed",
           "linkCapacity", "linkUsed"
    FROM "dmconnection" WHERE "cid" = 100 ORDER BY "name"
    """;
```

修改 endOf 方法，优先使用链路级别字段：

```java
private static LinkEnd endOf(Map<String, Object> link, boolean aEnd) {
    String prefix = aEnd ? "a" : "z";
    String portOid = str(link, prefix + "End");
    if (portOid == null) {
        return null;
    }
    
    // 优先使用链路级别带宽（linkCapacity/linkUsed）
    int capacity = intOf(link.get("linkCapacity"));
    int used = intOf(link.get("linkUsed"));
    
    // 如果链路级别数据为 0，降级到端口级别（兼容旧数据）
    if (capacity == 0) {
        capacity = intOf(link.get(prefix + "Capacity"));
    }
    if (used == 0) {
        used = intOf(link.get(prefix + "Used"));
    }
    
    return new LinkEnd(portOid, OidUtil.getNeOid(portOid),
            str(link, prefix + "NeName"), stripNeTypePrefix(str(link, prefix + "NeTypeName")),
            portNameOf(str(link, prefix + "PortName"), portOid),
            capacity, used);
}
```

修改 assembleLinks 方法，将链路级别带宽写入结果：

```java
// 在组装结果时，同时写入链路级别带宽
result.setLinkTotalBandwidth(doubleOrNull(intOf(link.get("linkCapacity"))));
result.setLinkUsedBandwidth(doubleOrNull(intOf(link.get("linkUsed"))));
```

修改 getLinkResults 方法，转换链路级别带宽的 Mbps：

```java
// 在转换 VC-12 到 Mbps 时，同时转换链路级别带宽
result.setLinkTotalBandwidth(vc12ToMbps(result.getLinkTotalBandwidth()));
result.setLinkUsedBandwidth(vc12ToMbps(result.getLinkUsedBandwidth()));
```

#### 步骤 5：修改 InspectionController.java

修改导出表头，增加链路级别带宽列：

```java
// 第 1 行表头
row0.getCell(0).setCellValue("序号");
row0.getCell(1).setCellValue("链路名称");
row0.getCell(2).setCellValue("A端");
row0.getCell(14).setCellValue("Z端");
row0.getCell(26).setCellValue("链路带宽");  // 新增
sheet.addMergedRegion(new CellRangeAddress(0, 0, 2, 13));
sheet.addMergedRegion(new CellRangeAddress(0, 0, 14, 25));
sheet.addMergedRegion(new CellRangeAddress(0, 0, 26, 27));  // 新增
mergeVertically(sheet, 0, 2);

// 第 3 行明细列名
String[] labels = {
    null, null,
    "名称", "类型", "端口", "类型", "TX\n(dBm)", "RX\n(dBm)", "TX\n状态", "RX\n状态", "RS\n错误秒",
    "总(Mbps)", "已用(Mbps)", "利用率",
    "名称", "类型", "端口", "类型", "TX\n(dBm)", "RX\n(dBm)", "TX\n状态", "RX\n状态", "RS\n错误秒",
    "总(Mbps)", "已用(Mbps)", "利用率",
    "总(Mbps)", "已用(Mbps)"  // 新增
};
```

修改 EXPORT_COLUMNS 常量：

```java
private static final int EXPORT_COLUMNS = 28;  // 从 26 改为 28
```

修改 writeResultSheet 方法，写入链路级别带宽：

```java
// 在写入数据时，增加链路级别带宽
writeSide(row, 2, ...);  // A端
writeSide(row, 14, ...); // Z端
// 新增：链路级别带宽
row.getCell(26).setCellValue(r.getLinkTotalBandwidth() != null ? r.getLinkTotalBandwidth() : 0);
row.getCell(27).setCellValue(r.getLinkUsedBandwidth() != null ? r.getLinkUsedBandwidth() : 0);
```

#### 步骤 6：修改 query.js

修改 HEADER_ROWS，增加链路级别带宽列：

```javascript
const HEADER_ROWS = [
    [
        { text: '序号', rowspan: 3, width: '50px', center: true },
        { text: '链路名称', rowspan: 3, minWidth: '150px' },
        { text: 'A端', colspan: 12, className: 'query-th-a' },
        { text: 'Z端', colspan: 12, className: 'query-th-z' },
        { text: '链路带宽', colspan: 2, className: 'query-th-link' }  // 新增
    ],
    // ... 其他行
];
```

修改 renderLinkQueryTable 方法，在表格中增加链路级别带宽列：

```javascript
// 在渲染每一行时，增加链路级别带宽
pageData.forEach(r => {
    const tr = document.createElement('tr');
    tr.appendChild(createTextCell(r.seqNo || '', 'center-cell'));
    const linkTd = truncCell(r.linkName);
    tr.appendChild(linkTd);
    appendSide(tr, r, 'a');
    appendSide(tr, r, 'z');
    // 新增：链路级别带宽
    tr.appendChild(numberCell(r.linkTotalBandwidth, 'center-cell'));
    tr.appendChild(numberCell(r.linkUsedBandwidth, 'center-cell'));
    tbody.appendChild(tr);
});
```

## 3. 数据验证

修改后，链路 1139 的数据应该是：

| 字段 | 修改前 | 修改后 |
|-----|-------|-------|
| aCapacity | 1008（VC-12数） | 1008（保持不变） |
| aUsed | 747（端口级别） | 747（保持不变） |
| zCapacity | 1008 | 1008（保持不变） |
| zUsed | 746 | 746（保持不变） |
| linkCapacity | 无 | **1008**（链路级别，双向） |
| linkUsed | 无 | **810**（链路级别，双向） |
| linkTotalBandwidth | 无 | **2064.38 Mbps**（采集时写入） |
| linkUsedBandwidth | 无 | **1658.88 Mbps**（采集时写入） |

前端展示时使用 `linkTotalBandwidth/linkUsedBandwidth`，与老系统一致。

## 4. 风险评估

### 4.1 兼容性

- 现有字段保持不变，不影响现有功能
- 新增字段有默认值 0，旧数据自动兼容
- 查询逻辑有降级处理（链路级别为 0 时用端口级别）

### 4.2 性能

- 同步时增加一次 MySQL 查询（linkbandwidth 表，仅 dir=1）
- 查询时无额外开销（字段已在 dmconnection 表中）
- 导出时增加 2 列，对 Excel 文件大小影响可忽略

### 4.3 回滚方案

如果出现问题，可以：
1. 回滚代码
2. 新增字段可以保留（不影响现有功能）
3. 或者删除新增字段（ALTER TABLE DROP COLUMN）
