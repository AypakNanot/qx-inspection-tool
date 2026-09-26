# 适配新版 opt-qx-cci-codec-plugin

## Why

SDK 已就地升级（`opt-qx-cci-codec-plugin` / `opt-qx-cci-core` 1.0.0，2026-09-23 重装），生成物契约与 core API 同时变化，本项目编译失败（398 条报错行，真实错误约 5 处，其余为 Lombok 级联假错误 —— Enter 阶段的泛型错误使 javac 跳过注解处理）。

**对齐口径（用户指定）**：以 `opt-cloud-module/opt-cloud-dc-ext-qx` 的实现为**契约参考**（同一个插件生成代码，其适配层展示正确接法），但 **非必要不搬** —— 只改编译/契约必需的部分，不新增类、不引入其支撑设施（事件日志、BaseDataException、qx-api 类）。

参考实现揭示的必改点：

1. `QxPayloadCodec` 双泛型 `<Req,Rsp>`；`direction()` / `QxMessageRegistry.getResponse()` / `getByType()` 已删除
2. 生成物改为「每 cmdCode 一个合并 codec + Req/Rsp DTO」，且全部 `isListRecord()=true` —— 旧扫描器跳过 listRecord 的规则会清空注册表
3. 生成的 ServiceImpl 不声明构造器 → 基类必须无参构造（`@Resource` 注入，参考实现即此接法）
4. 编码需按 cmdCode 走 `codecRegistry.encode(short, obj)`（原 `getByType(Class)` 已不存在）

## What Changes

**只改 3 处代码 + 1 个测试，不新增文件：**

1. **`AbstractQxService`**（扫描器）：
   - `QxPayloadCodec<?>` → `QxPayloadCodec<?, ?>`
   - 碰撞键 `cmdCode+direction` → `cmdCode`（`direction()` 已删）
   - **删除"跳过 listRecord codec"分支**（关键坑：5 个生成 codec 全是 listRecord，跳过 = 空注册表）
2. **`AbstractGeneratedQxService`**（服务基类）：
   - 删双参构造 → 默认无参构造；`qxDeviceService` 改 `@Resource` 注入（生成代码隐式 `super()` 要求）
   - 删未读取的 `eventPublisher` 字段（S1144）
   - 编码：`encodePayload(cmdCode, records)` 内部走 `codecRegistry.encode(short, obj)`
   - 解码：`getResponseCodec` → 合并 `codecRegistry.get(short)`
   - **保留**本地 `logComm`（SLF4J）、`QxCommandException`、`QxCodecUtils`、`QxSendResult` 不动
3. **`InspectionService`**：`LaserAttributeGetData/AckData` → `LaserAttributeGetReq/Rsp`；`attributeGet` 返回 `List<LaserAttributeGetRsp>`，单端口采集取首元素
4. **`InspectionServiceTest`** 同步

**明确不搬**（参考实现有、本项目不需要）：`QxCodecScanner`/`IQxService`/`QxConfiguration` 独立类（扫描留 `AbstractQxService`）、`DeviceLogEvent` 事件日志链路（保留 SLF4J COMM 日志）、`BaseDataException`（保留 `QxCommandException`）、`QxArgs`/qx-api 类、`sendAndDecodeList`（当前生成代码无调用方，见 design）。

## Capabilities

### New Capabilities

- `qx-codec-adapter`: 适配层与生成物契约 —— 合并 codec 按 cmdCode 注册（含 listRecord）、生成服务基类无参构造、单端口采集的列表语义

### Modified Capabilities

（无 —— 尚无既有 spec）

## Impact

- **代码**：`com/optel/dc/ext/qx/service/{AbstractQxService,AbstractGeneratedQxService}.java`、`com/optel/qxinspection/service/InspectionService.java` + 测试
- **不动**：`QxCodecUtils`（已验证对新 core 可编译，MsgHead API 未变）、`QxSendResult`、`IQxDeviceService`、`QxCommandException`、`QxErrorCode`
- **生成物**：`target/generated-sources/codec/**`（插件生成，不手改）
- **行为风险点**：扫描注册规则、listRecord 解码口径、`attributeGet` 列表语义 —— 见 design.md
