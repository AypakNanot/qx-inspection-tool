package com.optel.dc.ext.qx.service;

import com.optel.qx.cci.payload.QxMessageRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * codec 扫描注册回归测试。
 *
 * <p>钉住新插件合并 codec 的注册口径：listRecord codec 即该 cmdCode 的合并 codec，
 * 必须注册（旧规则"跳过 listRecord"会让注册表为空，运行期才炸）。</p>
 */
class CodecRegistrationTest {

    private static final int[] EXPECTED_CMD_CODES = {
            0x2406, 0x2410, 0x0C07, 0x0C08, 0x0C09
    };

    @Test
    void registersAllMergedCodecsIncludingListRecord() {
        QxMessageRegistry registry = new AbstractQxService() { }.codecRegistry;

        for (int cmd : EXPECTED_CMD_CODES) {
            assertNotNull(registry.get((short) cmd),
                    String.format("cmdCode 0x%04X 未注册（合并 codec 应含 listRecord codec）", cmd));
        }
    }
}
