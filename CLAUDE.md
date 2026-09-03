# QX 设备直连巡检工具 — 代码规范

## 项目概况

Spring Boot 3.2 / Java 17，直连 QX 光网络设备进行光功率巡检。
双数据库架构：MySQL（网络资产）+ SQLite（本地巡检状态）。协议编解码由 `opt-qx-cci-codec-plugin` 从 `src/main/schema/*.yaml` 自动生成。

## SonarQube 质量门禁（新代码口径）

| 条件 | 阈值 |
|---|---|
| 覆盖率 | >= 80% |
| 重复行 | <= 3% |
| 可维护性 / 可靠性 / 安全性 | A |
| 安全热点 | 100% 审查 |

## 自查清单

### 未使用代码（S1128/S1144/S1172/S1481/S1854）

- 无用 import、私有字段/方法、未读取的局部变量、死赋值 —— 一律删除

### 坏味道

- 禁止 `System.out.println` / `printStackTrace`，统一用 Lombok `@Slf4j` 或 `LoggerFactory`（S106）
- 删除注释掉的代码块和空 TODO 方法（S125/S1186/S1135）
- 方法参数 <= 7（S107），认知复杂度 <= 15（S3776），方法体 <= 行数建议 30
- 重复字符串字面量提取为 `static final` 常量（S1192）
- 只在方法内使用的字段降为局部变量（S1450）

### 命名

- 类 PascalCase（S101），方法/字段/局部变量 camelCase（S100/S116/S117）
- 常量 UPPER_SNAKE_CASE（S115），字段不公开（S1104）

### Bug 预防

- 可空返回值判空后再调用（S2259）；Stream 结果可能为空时用 `orElse` / `orElseThrow`
- 流 / 连接 / 句柄必须 try-with-resources（S2095）；JPA EntityManager 由框架管理，不需要手动关闭
- `equals` / `hashCode` 成对覆写（S1206）；用 Lombok `@Data` 时注意集合字段的 equals 语义
- 禁止 `new BigDecimal(double)`，用 `BigDecimal.valueOf(double)`（S2111）

### 安全

- SQL 一律参数化（JPA `@Query` 占位符或 Criteria API），禁止拼接（S2077）
- 禁止硬编码凭据 / IP，从 `application.yml` 或环境变量注入（S2068/S1313）
- 反射 `setAccessible`、自定义加密算法等安全热点交付时逐个标记审查（S3011）

### Spring Boot 特定

- `@Value` 注入的配置项必须在 `application.yml` 中有默认值，避免启动失败
- `@Transactional` 方法必须 `public`，内部调用不经过代理会失效
- REST 接口入参用 `@Valid` + Bean Validation，不手动校验
- 异常统一通过 `@RestControllerAdvice` 全局处理，Controller 不 catch 后返回裸字符串

## 工作流

1. 写码 -> 按自查清单过一遍 -> 修复 -> 再做逻辑 / 安全评审
2. SonarQube 扫描有遗留时，以报告为准逐条修，不凭印象改
3. 新增业务逻辑须配套单元测试，覆盖率以新代码口径 >= 80%
