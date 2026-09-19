package com.optel.qxinspection.entity.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A/Z 端字段的 JSON 键名回归测试。
 * <p>Lombok 为 {@code aNeName} 生成 {@code getANeName()}，Jackson 会把开头连续大写字母一起小写化，
 * 把键名变成 {@code aneName}。前端 query.js 按 {@code aNeName} 取值，一旦键名被“顺手改回去”，
 * 整端 12 列会静默变成空值 —— 不是报错，只是表格里全是 "-"。</p>
 */
class LinkInspectionResultJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static LinkInspectionResult sample() {
        LinkInspectionResult r = new LinkInspectionResult();
        r.setSeqNo(1);
        r.setLinkName("110kV万年变-110kV丰收变");
        r.setANeId("371");
        r.setANeName("G-110kV-万年-万年变");
        r.setANeTypeName("2050");
        r.setAPortName("至丰收变-10.1(1/12/1)");
        r.setATotalBandwidth(2488.32);
        r.setAUsedBandwidth(439.41);
        r.setABandwidthUsage(49.7);
        r.setZNeName("G-110kV-万年-丰收变");
        r.setZPortName("至万年变-12.1(1/10/1)");
        return r;
    }

    @Test
    void aSideFieldsKeepCapitalLetterInJsonKey() throws Exception {
        String json = mapper.writeValueAsString(sample());

        assertTrue(json.contains("\"aNeName\":\"G-110kV-万年-万年变\""), json);
        assertTrue(json.contains("\"aNeId\":\"371\""), json);
        assertTrue(json.contains("\"aNeTypeName\":\"2050\""), json);
        assertTrue(json.contains("\"aPortName\":\"至丰收变-10.1(1/12/1)\""), json);
        assertTrue(json.contains("\"aTotalBandwidth\":2488.32"), json);
        assertTrue(json.contains("\"aUsedBandwidth\":439.41"), json);
        assertTrue(json.contains("\"aBandwidthUsage\":49.7"), json);
    }

    @Test
    void zSideFieldsKeepCapitalLetterInJsonKey() throws Exception {
        String json = mapper.writeValueAsString(sample());

        assertTrue(json.contains("\"zNeName\":\"G-110kV-万年-丰收变\""), json);
        assertTrue(json.contains("\"zPortName\":\"至万年变-12.1(1/10/1)\""), json);
    }

    @Test
    void keysAreNotLowercasedByJackson() throws Exception {
        String json = mapper.writeValueAsString(sample());

        assertFalse(json.contains("\"aneName\""), "A 端键名被小写成 aneName，前端将全部取不到值: " + json);
        assertFalse(json.contains("\"zneName\""), "Z 端键名被小写成 zneName，前端将全部取不到值: " + json);
        assertFalse(json.contains("\"aportName\""), json);
        assertFalse(json.contains("\"atotalBandwidth\""), json);
    }

    @Test
    void plainFieldsStayAsIs() throws Exception {
        String json = mapper.writeValueAsString(sample());

        assertTrue(json.contains("\"seqNo\":1"), json);
        assertTrue(json.contains("\"linkName\":\"110kV万年变-110kV丰收变\""), json);
    }
}
