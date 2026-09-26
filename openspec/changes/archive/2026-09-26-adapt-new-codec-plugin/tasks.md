## 1. 适配层最小修改

- [x] 1.1 `AbstractQxService`：泛型 `<Req,Rsp>`、碰撞键去 `direction()`、**删"跳过 listRecord codec"分支**、`register` 适配新签名
- [x] 1.2 `AbstractGeneratedQxService`：删双参构造（改 `@Resource` 注入 `IQxDeviceService`）、删未读取的 `eventPublisher`
- [x] 1.3 `AbstractGeneratedQxService`：编码改 `codecRegistry.encode(short, obj)`（`encodePayload` 加 cmdCode 参数）；解码改合并 `codecRegistry.get(short)`；泛型对齐

## 2. 调用方迁移

- [x] 2.1 `InspectionService`：`LaserAttributeGetData/AckData` → `LaserAttributeGetReq/Rsp`，builder 字段核对（`backup` 保留）
- [x] 2.2 `attributeGet` 返回 `List<LaserAttributeGetRsp>`：取首元素；空列表 → `LaserSample.error("激光器查询无响应")`
- [x] 2.3 `InspectionServiceTest` 同步 mock/类型

## 3. 编译收敛与质量门禁

- [x] 3.1 `mvn compile` 迭代至 0 错误（每轮只修真错误，不逐条修 Lombok 级联假错误）
- [x] 3.2 `mvn test` 全绿；改动逻辑配套单测（新代码口径 ≥ 80%），测试文件数遵守全局上限
- [x] 3.3 按 CLAUDE.md 自查清单过一遍（未用 import/字段、复杂度、参数个数）
- [x] 3.4 代码评审（全局流程第 6 步口径）
