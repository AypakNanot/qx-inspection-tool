# Design — 适配新版 codec 插件（SDK 仅作参考，非必要不搬）

## Context

本项目是独立巡检工具，携带一份裁剪版 `com.optel.dc.ext.qx.service` 适配层（不依赖云端制品）。SDK 工作区 `opt-cloud-module/opt-cloud-dc-ext-qx` 有一份与新插件配套的完整实现，本次**只把它当契约参考**：看懂新生成物需要什么，然后在本地最小改动地满足。

编译报错呈典型级联：Enter 阶段的泛型错误（`QxPayloadCodec<?>`）使 javac 跳过注解处理，Lombok `@Data/@Builder/@Slf4j` 全部失效，产生 300+ 条 getter/`log`/`builder()` 假错误（已用独立 javac 验证 Lombok 本身正常）。真错误只有 3 处。

已验证：`QxCodecUtils` 本身对新 core 可编译（`MsgHead` API 未变），`QxSendResult`/`IQxDeviceService` 无真实错误 —— 三个文件不动。

## Goals / Non-Goals

**Goals:**
- 编译通过、测试全绿，改动面最小
- 生成物契约被满足（ServiceImpl 可实例化、codec 可注册可查、编码解码走合并 codec）

**Non-Goals:**
- 不与 SDK 做源码级同步；不新增类/文件
- 不搬事件日志链路（`DeviceLogEvent`/`CommLogEntry`/Redis）—— 保留本地 SLF4J `logComm`
- 不换异常类型（保留 `QxCommandException`，不引 `BaseDataException`）
- 不改协议语义与业务口径（`InspectionService` 只做类型/签名迁移）

## Decisions

1. **扫描器就地改，不引入 `QxCodecScanner`/`QxConfiguration`。** `AbstractQxService` 已是无参构造 + 构造时扫描，保持"registry 随基类实例构建"的现状。必改三点：泛型双参、碰撞键去 `direction()`、**删"跳过 listRecord"分支** —— 新生成物 5 个 codec 全部 `isListRecord()=true`，沿用旧规则等于注册表为空，启动后 `get()` 才炸（本次最危险的坑）。

2. **基类改无参构造 + `@Resource` 注入 `IQxDeviceService`（与参考实现同接法，也是唯一能让生成代码编译的方式）。** 生成的 ServiceImpl 不声明构造器，隐式 `super()` 要求无参构造；原双参构造随生成代码换代已无调用方，删除。`eventPublisher` 赋值后从未读取，一并删除（S1144）。依赖仍走 `IQxDeviceService`（`QxSendResult` 契约未变，本项目 `QxDeviceServiceImpl` 无需改动）。

3. **编码按 cmdCode 走 `codecRegistry.encode(short, obj)`。** `getByType(Class)` 已从 registry 消失，`encodePayload` 必须接收 cmdCode（调用方 `sendAndCheck`/`sendAndDecodeRaw` 都有）。多记录仍逐条编码后拼接（listRecord 批量语义不变）。解码改合并 `codecRegistry.get(short)`（原 `getResponse` 已删）。

4. **不搬 `sendAndDecodeList`。** 参考实现有此方法，但生成器只在"空请求 + listRecord"分支调用它；当前 3 份 schema 均为有请求体的 Get，生成代码零调用点。**跳过；以后 schema 加入空请求命令时再补**（一行包装，缺了编译报错也很直白）。

5. **`attributeGet` 列表语义：取首元素。** 查询参数精确到单端口（subcase/slot/portId 全指定），设备只可能回 1 条；空列表 → `LaserSample.error("激光器查询无响应")`，沿用现有容错路径不抛异常。

6. **修复顺序先真后假：** 先改适配层两文件（消除 Enter 阶段错误）→ Lombok 恢复，假错误自动消失 → 迁移 `InspectionService` → 编译迭代收敛。不逐条修 getter/`log` 假错误。

## Risks / Trade-offs

- **级联遮蔽**：398 条报错里只有约 5 条是真的，修完可能暴露第二层错误；按编译迭代收敛，不凭清单猜。
- **listRecord 解码口径**：`attributeGet` 走 `decodeList` 返回 `List`，批量语义与旧单条不同；当前调用方只查单端口，风险可控。后续若批量采集，调用方自行展开列表。
- **`@Resource` 字段注入**（Sonar S6813）：生成器契约所迫，交付时标记审查说明。
- **与 SDK 不做源码同步**：插件再升级时需重新对照参考实现 diff —— 用本次的「必改点清单」作核对底稿。
