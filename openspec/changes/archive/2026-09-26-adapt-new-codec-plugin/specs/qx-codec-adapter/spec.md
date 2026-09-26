# qx-codec-adapter

## ADDED Requirements

### Requirement: 合并 codec 按 cmdCode 注册

服务层 SHALL 将每个 cmdCode 的合并 codec（`QxGeneratedCodec<Req,Rsp>`，encode/decode 同体）注册进 `QxMessageRegistry`，注册键 SHALL 为 cmdCode；任何 `isListRecord()=true` 的 codec 也 SHALL 注册（新生成物全部为 listRecord，跳过即注册表为空）。

#### Scenario: 启动扫描注册合并 codec

- **WHEN** Spring 装配生成的 ServiceImpl 并触发 codec 扫描
- **THEN** 5 个合并 codec（0x2406/0x2410/0x0C07/0x0C08/0x0C09）全部注册成功，`codecRegistry.get(cmdCode)` 可查到

#### Scenario: cmdCode 碰撞拒绝启动

- **WHEN** 两个 codec 的 cmdCode 相同
- **THEN** 启动抛出 `IllegalStateException` 并在日志标明冲突双方

### Requirement: 生成服务基类提供无参构造

`AbstractGeneratedQxService` SHALL 提供无参构造（生成的 ServiceImpl 不声明构造器，隐式 `super()` 要求如此），设备通信依赖 SHALL 通过字段注入获得。

#### Scenario: 生成的 ServiceImpl 可被实例化

- **WHEN** 编译并启动含 `LaserServiceImpl` 等生成实现的上下文
- **THEN** 编译通过且 Spring 能完成依赖注入

### Requirement: 单端口激光器采集取列表首元素

`attributeGet` 返回 `List<LaserAttributeGetRsp>`；单端口采集场景 SHALL 取首元素，空列表 SHALL 按"激光器查询无响应"处理（记入 error_info，不抛异常）。

#### Scenario: 正常返回一条记录

- **WHEN** 设备返回 1 条激光器属性记录
- **THEN** `LaserSample` 取该记录的 supportFlag/光功率字段

#### Scenario: 返回空列表

- **WHEN** 设备返回空列表
- **THEN** `LaserSample.error("激光器查询无响应")`，采集继续
