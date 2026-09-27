# RS错误秒采集 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 巡检采集端口时通过 QX 0x0C07 查询 RS-ES/RS-SES/RS-UAS，写入 `link_inspection_result` 的 `a_rs_error_sec/z_rs_error_sec`。

**Architecture:** 在现有 `collectPort`（激光器查询）成功后追加一次 `IPerfService.current24HGet` 批量查询（varargs 三条记录），回包经包级静态方法 `parsePerfResponses` 解析求和，结果与失败原因并入 `LaserSample` record，`buildSide` 透传落库。性能失败只置空 rsErrorSec 并追加 error_info，不改变端口成败。

**Tech Stack:** Spring Boot 3.2 / Java 17、JUnit5 + Mockito、codec 插件生成的 `PerfCurrent24HGetReq/Rsp` 与 `IPerfService`。

**设计文档:** `docs/plans/2026-09-27-rs-error-sec-collection-design.md`（已提交）。性能编码来源 Uniview 库 `defpmattr`：RS-ES=18194、RS-SES=18195、RS-UAS=18196。

**工作目录:** `.worktrees/collect-rs-error-sec`（分支 `feature/collect-rs-error-sec`）。所有命令在该目录执行。

---

### Task 1: PerfCodes 常量 + 回包解析 parsePerfResponses

**Files:**
- Create: `src/main/java/com/optel/qxinspection/service/PerfCodes.java`
- Modify: `src/main/java/com/optel/qxinspection/service/InspectionService.java`（新增包级静态方法与 record，位置放在 `// ========== 小工具 ==========` 区块附近）
- Test: `src/test/java/com/optel/qxinspection/service/InspectionServicePerfTest.java`（新建）

**Step 1: 写失败测试**

新建 `InspectionServicePerfTest.java`：

```java
package com.optel.qxinspection.service;

import com.optel.qxinspection.perf.PerfCurrent24HGetRsp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0x0C07 性能回包解析（InspectionService.parsePerfResponses）单元测试
 */
class InspectionServicePerfTest {

    /** 合法回包一条（其余字段对解析无影响，留 null） */
    private static PerfCurrent24HGetRsp rsp(int subcaseNo, int code, int value) {
        return PerfCurrent24HGetRsp.builder()
                .subcaseNo(subcaseNo)
                .performanceCode(code)
                .performanceValue(value)
                .build();
    }

    @Test
    void parse_ThreeValidRecords_SumsAll() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 10),
                rsp(0, PerfCodes.RS_SES, 2),
                rsp(0, PerfCodes.RS_UAS, 1)));

        assertEquals(13, outcome.rsErrorSec());
        assertNull(outcome.issue());
    }

    @Test
    void parse_InvalidPmBit_SkipsRecordAndReportsMissing() {
        // bit7=1 无效记录跳过 → ES 缺失，SES+UAS 相加
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0x80, PerfCodes.RS_ES, 99),
                rsp(0, PerfCodes.RS_SES, 2),
                rsp(0, PerfCodes.RS_UAS, 1)));

        assertEquals(3, outcome.rsErrorSec());
        assertNotNull(outcome.issue());
        assertTrue(outcome.issue().contains("RS-ES"));
        assertTrue(outcome.issue().contains("性能采集缺失"));
    }

    @Test
    void parse_CodeAbsentFromResponse_ReportsMissing() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 10),
                rsp(0, PerfCodes.RS_UAS, 1)));

        assertEquals(11, outcome.rsErrorSec());
        assertTrue(outcome.issue().contains("RS-SES"));
    }

    @Test
    void parse_AllRecordsInvalid_ReturnsNullWithError() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0x80, PerfCodes.RS_ES, 10),
                rsp(0x80, PerfCodes.RS_SES, 2),
                rsp(0x80, PerfCodes.RS_UAS, 1)));

        assertNull(outcome.rsErrorSec());
        assertEquals(InspectionService.PERF_ALL_INVALID, outcome.issue());
    }

    @Test
    void parse_EmptyOrNullOrList_ReturnsNullWithError() {
        assertNull(InspectionService.parsePerfResponses(null).rsErrorSec());
        assertEquals(InspectionService.PERF_NO_DATA,
                InspectionService.parsePerfResponses(null).issue());

        InspectionService.PerfOutcome empty = InspectionService.parsePerfResponses(List.of());
        assertNull(empty.rsErrorSec());
        assertEquals(InspectionService.PERF_NO_DATA, empty.issue());
    }

    @Test
    void parse_HighWordBit_CombinesWithLowWord() {
        // bit6=1 的记录是高 32 位：值 = 低32位 + 高32位<<32，超 int 上限按 MAX_VALUE 防御
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 5),
                rsp(0x40, PerfCodes.RS_ES, 1),
                rsp(0, PerfCodes.RS_SES, 0),
                rsp(0, PerfCodes.RS_UAS, 0)));

        assertEquals(Integer.MAX_VALUE, outcome.rsErrorSec());
        assertNull(outcome.issue());
    }

    @Test
    void parse_HighWordOnly_NoLowWord_TreatedAsMissing() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0x40, PerfCodes.RS_ES, 1),
                rsp(0, PerfCodes.RS_SES, 2),
                rsp(0, PerfCodes.RS_UAS, 3)));

        assertEquals(5, outcome.rsErrorSec());
        assertTrue(outcome.issue().contains("RS-ES"));
    }
}
```

**Step 2: 运行确认失败**

Run: `mvn test -Dtest=InspectionServicePerfTest`
Expected: 编译失败（`PerfCodes`、`parsePerfResponses`、`PerfOutcome`、`PERF_ALL_INVALID`、`PERF_NO_DATA` 不存在）

**Step 3: 最小实现**

新建 `PerfCodes.java`：

```java
package com.optel.qxinspection.service;

/**
 * QX 0x0C07 性能代码常量。
 * <p>来源：Uniview 库 defpmattr 表（eName → type），网管与设备同一编号体系。
 * 真机联调时需确认回包 performanceCode 与此一致。</p>
 */
final class PerfCodes {

    /** RS误码秒 */
    static final int RS_ES = 18194;
    /** RS严重误码秒 */
    static final int RS_SES = 18195;
    /** RS不可用秒 */
    static final int RS_UAS = 18196;

    private PerfCodes() {
    }

    /** 性能代码 → 指标名（未知代码返回数字串），用于 error_info 定位出错指标 */
    static String nameOf(int code) {
        if (code == RS_ES) return "RS-ES";
        if (code == RS_SES) return "RS-SES";
        if (code == RS_UAS) return "RS-UAS";
        return String.valueOf(code);
    }
}
```

`InspectionService.java` 新增（imports 补 `com.optel.qxinspection.perf.PerfCurrent24HGetRsp`、`java.util.HashMap`——HashMap 已有则不加）：

```java
    // ========== 性能采集（0x0C07） ==========

    /** subcaseNo bit7：1 = Invalid PM，该记录无效 */
    static final int PM_INVALID_BIT = 0x80;
    /** subcaseNo bit6：1 = 高 32 位，0 = 低 32 位 */
    static final int PM_HIGH_WORD_BIT = 0x40;

    static final String PERF_FAIL_PREFIX = "性能采集失败(RS-ES/RS-SES/RS-UAS): ";
    static final String PERF_NO_DATA = "性能采集失败: 设备未返回数据";
    static final String PERF_ALL_INVALID = "性能采集失败: 设备返回无效数据";
    static final String PERF_MISSING_FMT = "性能采集缺失(%s): 设备未返回";

    /** 0x0C07 回包解析结果。rsErrorSec=null 表示整体失败；issue 非空时写入 error_info */
    record PerfOutcome(Integer rsErrorSec, String issue) {
    }

    /**
     * 解析 0x0C07 回包列表：跳过 bit7 无效记录，按 performanceCode 归桶，
     * bit6 高 32 位与低 32 位拼接，RS错误秒 = RS-ES + RS-SES + RS-UAS。
     * 缺某指标时相加已有项并在 issue 中标明；三项全缺/空列表为整体失败。
     */
    static PerfOutcome parsePerfResponses(List<PerfCurrent24HGetRsp> rsps) {
        if (rsps == null || rsps.isEmpty()) {
            return new PerfOutcome(null, PERF_NO_DATA);
        }
        Map<Integer, Integer> low = new HashMap<>();
        Map<Integer, Integer> high = new HashMap<>();
        for (PerfCurrent24HGetRsp rsp : rsps) {
            if (rsp == null || rsp.getSubcaseNo() == null || rsp.getPerformanceCode() == null) {
                continue;
            }
            if ((rsp.getSubcaseNo() & PM_INVALID_BIT) != 0) {
                continue;
            }
            int value = rsp.getPerformanceValue() != null ? rsp.getPerformanceValue() : 0;
            if ((rsp.getSubcaseNo() & PM_HIGH_WORD_BIT) != 0) {
                high.put(rsp.getPerformanceCode(), value);
            } else {
                low.put(rsp.getPerformanceCode(), value);
            }
        }
        long total = 0;
        boolean anyPresent = false;
        List<String> missing = new ArrayList<>();
        for (int code : new int[]{PerfCodes.RS_ES, PerfCodes.RS_SES, PerfCodes.RS_UAS}) {
            Integer lo = low.get(code);
            if (lo == null) {
                missing.add(PerfCodes.nameOf(code));
                continue;
            }
            long hi = high.getOrDefault(code, 0);
            total += (lo & 0xFFFFFFFFL) + (hi << 32);
            anyPresent = true;
        }
        if (!anyPresent) {
            return new PerfOutcome(null, PERF_ALL_INVALID);
        }
        int sum = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        String issue = missing.isEmpty() ? null
                : String.format(PERF_MISSING_FMT, String.join("/", missing));
        return new PerfOutcome(sum, issue);
    }
```

**Step 4: 运行确认通过**

Run: `mvn test -Dtest=InspectionServicePerfTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`（计划预估，**以实际为准**）

**Step 5: Commit**

```bash
git add src/main/java/com/optel/qxinspection/service/PerfCodes.java \
        src/main/java/com/optel/qxinspection/service/InspectionService.java \
        src/test/java/com/optel/qxinspection/service/InspectionServicePerfTest.java
git commit -m "feat: 0x0C07 性能回包解析 parsePerfResponses（bit7无效/缺失指标/高低32位）"
```

---

### Task 2: LaserSample 增加 rsErrorSec + collectPort 集成性能查询

**Files:**
- Modify: `src/main/java/com/optel/qxinspection/service/InspectionService.java`
  - `private final ILaserService laserService;` 旁新增 `private final IPerfService perfService;`（`@RequiredArgsConstructor` 自动注入；import `com.optel.qxinspection.perf.service.IPerfService`）
  - `LaserSample` record（约 918 行）：可见性 `private` → 包级（去掉 `private`），新增 `Integer rsErrorSec` 组件
  - `collectPort`（约 649 行）：`private` → 包级，拆出性能查询
  - 新增常量 `TS_ORDER_PHYSICAL_PORT = 0`、`TS_ATTR_PHYSICAL_PORT = 0`
- Test: `src/test/java/com/optel/qxinspection/service/InspectionServicePerfTest.java`（追加用例）

**Step 1: 写失败测试**

在 `InspectionServicePerfTest` 中追加（imports 增补：`@Mock/@InjectMocks/MockitoExtension`、`ILaserService`、`IPerfService`、`LaserAttributeGetReq/Rsp`、`PerfCurrent24HGetReq`、`QxCommandException`、Mockito 静态方法）：

```java
@ExtendWith(MockitoExtension.class)
class InspectionServicePerfTest {

    @Mock
    private ILaserService laserService;
    @Mock
    private IPerfService perfService;
    @InjectMocks
    private InspectionService inspectionService;

    // … Task 1 的 parse 用例保持不变 …

    /** 支持光功率的合法激光器回包 */
    private static LaserAttributeGetRsp laserOk() {
        return LaserAttributeGetRsp.builder()
                .supportFlag(1)
                .laserType(0x10)
                .distance(0x11)
                .tranLaserPower(1.5f)
                .recvLaserPower(-2.0f)
                .build();
    }

    private static final String PORT_OID = "101:1:11:2:1";

    /** 一次 0x0C07 的正常回包：三项各一条 */
    private static List<PerfCurrent24HGetRsp> perfOk(int es, int ses, int uas) {
        return List.of(
                rsp(0, PerfCodes.RS_ES, es),
                rsp(0, PerfCodes.RS_SES, ses),
                rsp(0, PerfCodes.RS_UAS, uas));
    }

    @Test
    void collectPort_LaserFails_PerfNeverQueried() {
        when(laserService.attributeGet(anyString(), any())).thenThrow(new RuntimeException("boom"));

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertNotNull(sample.errorInfo());
        assertTrue(sample.errorInfo().contains("采集失败"));
        assertNull(sample.rsErrorSec());
        verifyNoInteractions(perfService);
    }

    @Test
    void collectPort_LaserNoResponse_PerfNeverQueried() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(Collections.emptyList());

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertEquals("激光器查询无响应", sample.errorInfo());
        verifyNoInteractions(perfService);
    }

    @Test
    void collectPort_LaserAndPerfOk_SumsErrorSeconds() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenReturn(perfOk(10, 2, 1));

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertNull(sample.errorInfo());
        assertEquals(13, sample.rsErrorSec());
        assertEquals(1.5, sample.txPower(), 1e-9);
    }

    @Test
    void collectPort_PerfRequestHasThreeCodesAndPhysicalPortParams() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenReturn(perfOk(0, 0, 0));

        inspectionService.collectPort("ne1", PORT_OID, Map.of());

        ArgumentCaptor<PerfCurrent24HGetReq> captor = ArgumentCaptor.forClass(PerfCurrent24HGetReq.class);
        verify(perfService).current24HGet(eq("ne1"), captor.capture());
        List<PerfCurrent24HGetReq> reqs = captor.getAllValues();
        // varargs 三个参数被 Mockito 拆成三次 capture —— 实际签名是 (String, Req...),
        // getAllValues() 为空时改用 ArgumentCaptor 无法拆 varargs，改为断言数组：
        assertEquals(3, reqs.size());
    }

    @Test
    void collectPort_PerfThrows_ErrorInfoExplainsAndPortStillOk() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenThrow(new QxCommandException(1, "设备拒绝"));

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertNull(sample.rsErrorSec());
        assertTrue(sample.errorInfo().startsWith(InspectionService.PERF_FAIL_PREFIX));
        assertTrue(sample.errorInfo().contains("设备拒绝"));
        assertEquals(1.5, sample.txPower(), 1e-9);   // 光功率数据保留，端口仍是成功态
    }

    @Test
    void collectPort_PerfReturnsMissingMetric_StillOkWithIssue() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenReturn(List.of(
                        rsp(0, PerfCodes.RS_ES, 10),
                        rsp(0, PerfCodes.RS_UAS, 1)));   // 缺 RS-SES

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertEquals(11, sample.rsErrorSec());
        assertTrue(sample.errorInfo().contains("RS-SES"));
    }
}
```

> **varargs 捕获注意（执行时按实际 Mockito 行为调整）：** `current24HGet(String, PerfCurrent24HGetReq...)` 的 varargs 参数，`ArgumentCaptor.forClass(PerfCurrent24HGetReq.class)` + `captor.getAllValues()` 拿到的是三个元素。若执行时发现拿到的是嵌套数组（一个元素），改用 `ArgumentCaptor<PerfCurrent24HGetReq[]>` 或 `verify(perfService).current24HGet(eq("ne1"), reqCaptor.capture(), …)`（Mockito 对 varargs 逐个 capture 是标准做法：`captor.capture()` 重复三次的 verify 形式不适用于动态次数，此时退回 `ArgumentCaptor<Object>` 对数组整体断言 `((PerfCurrent24HGetReq[]) captor.getValue())[i].getPerformanceCode()`）。断言内容固定为：三个 `performanceCode` ∈ {18194,18195,18196}、`tsOrderId=0`、`tsAttribute=0`、`portType=0xFF`（portTypes 传空 Map 时）。

**Step 2: 运行确认失败**

Run: `mvn test -Dtest=InspectionServicePerfTest`
Expected: 编译失败（`LaserSample` 无 `rsErrorSec`、`collectPort` 不可见、`perfService` 字段不存在）

**Step 3: 实现**

`InspectionService.java`：

1. 字段区新增（`ILaserService` 之后）：

```java
    private final IPerfService perfService;
```

2. 常量区新增：

```java
    /** 0x0C07 tsOrderId：0 = 物理端口本身（perf.yaml 注释，待真机验证） */
    private static final int TS_ORDER_PHYSICAL_PORT = 0;
    /** 0x0C07 tsAttribute：0 = 物理端口（perf.yaml 注释，待真机验证） */
    private static final int TS_ATTR_PHYSICAL_PORT = 0;
```

3. `collectPort` 整体替换为（可见性改包级）：

```java
    /**
     * 采集单个端口：先查光功率，成功后追加 0x0C07 性能查询。
     * 光功率失败只记录该端口的 error_info；性能失败只影响 rsErrorSec 与 error_info，端口仍为成功态。
     */
    LaserSample collectPort(String neId, String portOid, Map<String, Integer> portTypes) {
        LaserSample sample;
        try {
            LaserAttributeGetReq req = LaserAttributeGetReq.builder()
                    .subcaseNo(OidUtil.getSubrackId(portOid))
                    .slotId(OidUtil.getSlotId(portOid))
                    .portType(portTypes.getOrDefault(portOid, DEFAULT_PORT_TYPE))
                    .portSubType(DEFAULT_PORT_SUB_TYPE)
                    .portId(OidUtil.getPortId(portOid))
                    .backup(0)
                    .build();
            sample = LaserSample.of(laserService.attributeGet(neId, req));
        } catch (Exception e) {
            log.debug("端口采集失败: ne={}, port={}, {}", neId, portOid, e.getMessage());
            return LaserSample.error("采集失败: " + e.getMessage());
        }
        if (sample.errorInfo() != null) {
            return sample;
        }
        return collectRsErrorSec(neId, portOid, portTypes, sample);
    }

    /** RS错误秒采集：失败分级见设计文档，任何情况不改变端口成功态 */
    private LaserSample collectRsErrorSec(String neId, String portOid, Map<String, Integer> portTypes,
                                           LaserSample sample) {
        try {
            PerfOutcome outcome = parsePerfResponses(perfService.current24HGet(neId,
                    perfReq(portOid, portTypes, PerfCodes.RS_ES),
                    perfReq(portOid, portTypes, PerfCodes.RS_SES),
                    perfReq(portOid, portTypes, PerfCodes.RS_UAS)));
            return new LaserSample(sample.moduleType(), sample.txPower(), sample.rxPower(),
                    outcome.rsErrorSec(), outcome.issue());
        } catch (Exception e) {
            log.debug("性能采集失败: ne={}, port={}, {}", neId, portOid, e.getMessage());
            return new LaserSample(sample.moduleType(), sample.txPower(), sample.rxPower(),
                    null, PERF_FAIL_PREFIX + e.getMessage());
        }
    }

    private PerfCurrent24HGetReq perfReq(String portOid, Map<String, Integer> portTypes,
                                          int performanceCode) {
        return PerfCurrent24HGetReq.builder()
                .subcaseNo(OidUtil.getSubrackId(portOid))
                .slotId(OidUtil.getSlotId(portOid))
                .portType(portTypes.getOrDefault(portOid, DEFAULT_PORT_TYPE))
                .portSubType(DEFAULT_PORT_SUB_TYPE)
                .portId(OidUtil.getPortId(portOid))
                .tsOrderId(TS_ORDER_PHYSICAL_PORT)
                .tsAttribute(TS_ATTR_PHYSICAL_PORT)
                .performanceCode(performanceCode)
                .build();
    }
```

4. `LaserSample` record 改为（去掉 `private`，加组件，`of`/`error` 补参）：

```java
    /** 单端口采集结果。rsErrorSec=null 表示性能未采到（失败原因在 errorInfo） */
    record LaserSample(String moduleType, Double txPower, Double rxPower,
                       Integer rsErrorSec, String errorInfo) {

        /** 单端口查询按记录列表返回；精确到端口的查询只会有一条，取首元素即可。 */
        static LaserSample of(List<LaserAttributeGetRsp> rsps) {
            if (rsps == null || rsps.isEmpty()) {
                return error("激光器查询无响应");
            }
            LaserAttributeGetRsp ack = rsps.get(0);
            if ((ack.getSupportFlag() & LASER_SUPPORT_BIT) != 1) {
                return error("端口不支持光功率采集");
            }
            return new LaserSample(
                    toModuleTypeName(ack.getLaserType(), ack.getDistance()),
                    toOpticalPower(ack.getTranLaserPower()),
                    toOpticalPower(ack.getRecvLaserPower()),
                    null,
                    null);
        }

        static LaserSample error(String message) {
            return new LaserSample(null, null, null, null, message);
        }
    }
```

新增 imports：`com.optel.qxinspection.perf.PerfCurrent24HGetReq`、`com.optel.qxinspection.perf.service.IPerfService`。

> 注意：`collectRsErrorSec` 只在激光器成功（errorInfo==null）时调用，所以 outcome.issue 直接作为 errorInfo，无需字符串拼接已有内容——设计文档的"追加"语义由此满足（光功率错误时根本不进这条分支）。

**Step 4: 运行确认通过**

Run: `mvn test -Dtest=InspectionServicePerfTest`
Expected: `Tests run: 13, Failures: 0, Errors: 0`（7 + 6 新增；计划预估，**以实际为准**）

**Step 5: 全量回归**

Run: `mvn test`
Expected: 全绿。`InspectionServiceTest` 编译报错处（如有）——它不直接引用 `LaserSample`，`@InjectMocks` 会因新增 `perfService` 构造参数自动适配，理论上无需改动；若报"unmocked"仅在真实调用链路用到时才需给 `InspectionServiceTest` 补 `@Mock IPerfService perfService`（补上即可，lenient 不需要 stub，因为现有流程测试连接即失败走不到性能分支）。

**Step 6: Commit**

```bash
git add src/main/java/com/optel/qxinspection/service/InspectionService.java \
        src/test/java/com/optel/qxinspection/service/InspectionServicePerfTest.java
git commit -m "feat: 巡检端口采集集成 0x0C07 RS错误秒（失败只记error_info不改端口成败）"
```

---

### Task 3: buildSide 透传 rsErrorSec 落库

**Files:**
- Modify: `src/main/java/com/optel/qxinspection/service/InspectionService.java`
  - `buildSide`（约 771 行）：`private static` → `static`，`null` 参数换 `sample.rsErrorSec()`
  - `LinkEnd`、`SideData` record：`private` → 包级
- Test: `src/test/java/com/optel/qxinspection/service/InspectionServicePerfTest.java`（追加）

**Step 1: 写失败测试**

```java
    @Test
    void buildSide_PassesRsErrorSecThrough() {
        Map<String, InspectionService.LaserSample> samples = Map.of(PORT_OID,
                new InspectionService.LaserSample("1000BASE-SX", 1.5, -2.0, 13, null));
        InspectionService.LinkEnd end = new InspectionService.LinkEnd(
                PORT_OID, "ne1", "NE-001", "Type", "P1(1/11/2)", 63, 10);

        InspectionService.SideData side = InspectionService.buildSide(end, samples, Map.of());

        assertNotNull(side);
        assertEquals(13, side.rsErrorSec());
        assertEquals("正常", side.txStatus());
    }

    @Test
    void buildSide_NullRsErrorSecStaysNull() {
        Map<String, InspectionService.LaserSample> samples = Map.of(PORT_OID,
                new InspectionService.LaserSample("1000BASE-SX", 1.5, -2.0, null, null));
        InspectionService.LinkEnd end = new InspectionService.LinkEnd(
                PORT_OID, "ne1", "NE-001", "Type", "P1(1/11/2)", 63, 10);

        InspectionService.SideData side = InspectionService.buildSide(end, samples, Map.of());

        assertNotNull(side);
        assertNull(side.rsErrorSec());
    }
```

> `txStatus` 断言依赖 `ThresholdService.evaluateStatus` 的返回常量（`STATUS_NORMAL`，值为"正常"——执行时以实际常量为准，若字面不同改为引用 `ThresholdService.STATUS_NORMAL`）。

**Step 2: 运行确认失败**

Run: `mvn test -Dtest=InspectionServicePerfTest`
Expected: 编译失败（`buildSide`/`LinkEnd`/`SideData` 不可见）

**Step 3: 实现**

- `private SideData buildSide(...)` → `static SideData buildSide(...)`（若原本已是 static 则只去 `private`）
- 方法体内 `null, // RS错误秒：性能采集未接入，字段预留` → `sample.rsErrorSec(),`
- `private record LinkEnd(...)` → `record LinkEnd(...)`
- `private record SideData(...)` → `record SideData(...)`
- 调用点 `buildSide(...)` 若有编译歧义（实例方法 vs static）保持原调用不变即可

**Step 4: 运行确认通过**

Run: `mvn test -Dtest=InspectionServicePerfTest`
Expected: `Tests run: 15, Failures: 0, Errors: 0`（计划预估；**以实际为准**，最终该类 23 例，见 Task 4）

**Step 5: Commit**

```bash
git add src/main/java/com/optel/qxinspection/service/InspectionService.java \
        src/test/java/com/optel/qxinspection/service/InspectionServicePerfTest.java
git commit -m "feat: buildSide 透传 RS错误秒（替换预留 null）"
```

---

### Task 3.5: error_info 读取端适配（质量评审新增，Critical）

> **来源：** Task 2 质量评审 Critical #1——Task 2 引入首种"光功率采到但 errorInfo 非空"的状态，破坏不变量：`isCollected`（:545 saveInvalid=false 保存过滤）把两端性能失败的链路当"未采集"丢弃；`sideAbnormal`（:377）→ `getSummary` 全部统计在**默认配置下无条件**把性能失败的光功率正常链路计为"异常"（异常明细出现 status 全"正常"行）。
> **裁决：** 方案 A——采集/异常判定从 errorInfo 改为 `moduleType == null`（成功路径 moduleType 恒非空、失败路径恒 null，判别精确）；error_info 保留为纯原因记录字段。设计文档 :69 的"口径不变"断言同时修正（随本任务提交）。
> **最终落地（复审裁决接受）：** `sideCollected = !isEmpty(moduleType) || isEmpty(errorInfo)`——成功路径（moduleType 非空）恒真，光功率失败路径（moduleType null + error_info 非空）恒假，与字面 `moduleType == null` 方案在正常数据上等价；额外对"moduleType 与 error_info 全 null 的退化行"（endOf 返回 null 等）判为已采集，与旧口径一致，严格优于字面方案。

**Files:**
- Modify: `src/main/java/com/optel/qxinspection/service/InspectionService.java`（`isCollected`、`sideAbnormal` 两处判定）
- Modify: `docs/plans/2026-09-27-rs-error-sec-collection-design.md`（修正 :69 与失败分级表表述）
- Test: `InspectionServiceTest` / `InspectionServicePerfTest`（新用例）

**Step 1: 写失败测试（3 个，先红）**
1. 两端 perf 失败（moduleType 有值 + errorInfo=性能文案）+ saveInvalid=false → 链路仍保存
2. perf 失败 → getSummary abnormalLinks 不增
3. 光功率采集失败（moduleType=null）→ isCollected/sideAbnormal 行为与基线一致（现有 :440 用例保持绿）

**Step 2-5: 实现判定改造 → 全绿 → 全量回归 → 提交**

配套（随 Task 2 修复提交一并完成）：`LaserSample.withPerf` 消重复、异常日志传堆栈、supportFlag=0 / PERF_NO_DATA 端到端 / portTypes 命中 3 个新用例。

**延后项（评审 Important #2，不做进本分支）：** 真机验收清单增补"设备对不支持的 0x0C07 是快速报错还是 10s 静默超时"；若存在静默场景再评估连续失败熔断或 `qx.perf.enabled` 开关。

---

### Task 4: 全量回归 + 自查清单

**Files:**
- Test: 无新增，跑全量

**Step 1: 全量测试**

Run: `mvn test`
Expected: `Tests run: 169+15, Failures: 0, Errors: 0`（基线 169 + 新增；计划预估，**以实际为准**——最终全量 194+）

**Step 2: 按 CLAUDE.md 自查清单过一遍**

- 无用 import / 死代码（改动文件 `git diff` 后逐一核对）
- 无 `System.out`/`printStackTrace`；文案字面量已提取常量（`PERF_*`、`S1192`）
- `collectRsErrorSec` 方法体 ≤30 行、复杂度 ≤15（不满足则把 catch 分支再拆）
- 新增代码被测试覆盖：parse 7 例 + collectPort 6 例 + buildSide 2 例，新代码口径目测 ≥80%

**Step 3: 设计文档勾验收项**

在 `docs/plans/2026-09-27-rs-error-sec-collection-design.md` 末尾追加实现状态：

```markdown
## 实现状态（2026-09-27）

- [x] PerfCodes 常量 + parsePerfResponses（含测试）
- [x] collectPort 集成 0x0C07（含测试）
- [x] buildSide 透传（含测试）
- [ ] 真机联调验收（performanceCode=18194 回包核对、tsOrderId/tsAttribute、bit6、端到端一轮巡检）
```

**Step 4: Commit**

```bash
git add docs/plans/2026-09-27-rs-error-sec-collection-design.md
git commit -m "docs: RS错误秒采集实现状态（真机联调待办）"
```

**Step 5: 汇报**

向用户汇报：改动文件清单、测试结果（真实数字）、真机联调清单待执行。**不推送**（除非用户明确要求）。

---

### Task 5: portType/portSubType 转换（用户口述规则，2026-09-27 追加）

**规则（已抓包验证）：** 取 `dmeo(cid, type)` → 查 Uniview `defobject(cid, type).deviceType` → `portType = (deviceType >> 8) & 0xFF`，`portSubType = deviceType & 0xFF`。
验证：20008(STM4_O) → deviceType 515 = 0x0203 → 报文 `02 03`（用户抓包 `02030001`）；20021 → 0xC05 → `0C 05`。
**背景 bug：** 现 `loadPortTypes` 把 dmeo.type 原值塞 BYTE，codec `(byte)` 强转截断（20008 → 0x28），激光器与 0x0C07 查询都错。`defobject` 未同步进 SQLite。

**Files:**
- Modify: `DynamicSyncService` — 新增 `public Map<Integer, Integer> loadPortDeviceTypes()`：`mysqlJdbc.queryForList("SELECT type, deviceType FROM defobject WHERE cid = 5")` → type→deviceType（异常抛出由调用方兜底）
- Modify: `InspectionService`
  - `loadPortTypes()` 改造（方法名不变）：SQLite 查 oid+type（SQL 不变），逐行经 deviceType map 拆成 `Map<String, int[]>`（oid → [portType, portSubType]）；deviceType 缺失/负数 → `[0xFF, 0xFF]`（兜底值，每次新建数组不共享）
  - deviceType map 在巡检启动处一次性加载（`dynamicSyncService.loadPortDeviceTypes()`），**查询失败 → log.warn + 空 map 全量兜底 0xFF/0xFF，不中断巡检**
  - `collectPort` / `perfReq` 的 portType/portSubType 全部改取 pair；删除 `DEFAULT_PORT_TYPE/DEFAULT_PORT_SUB_TYPE` 常量，兜底收敛为 `defaultPortPair()`
  - `collectNe`/调用链参数类型 `Map<String,Integer>` → `Map<String,int[]>`（或小值类型，参数数不变）
- Test: `DynamicSyncServiceTest`（loadPortDeviceTypes：正常映射 / mysql 异常抛出）+ `InspectionServicePerfTest`（拆分数学：515→(2,3)、3077→(12,5)、-1→(255,255)、缺失→兜底；collectPort/perfReq 捕获断言 portType=2/portSubType=3 —— **既有 `collectPort_PerfRequest...` 用例的 0xFF 断言按新语义更新**）
- Modify: 设计文档 — 采集流程/改动文件表补 Task 5 条目

**Step 1-6:** TDD 照旧（先失败测试 → 实现 → 定向绿 → `mvn test` 全量绿 → commit → 自查）。
