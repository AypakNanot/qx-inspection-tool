package com.optel.dc.ext.qx.service;

import com.optel.qx.cci.payload.QxGeneratedCodec;
import com.optel.qx.cci.payload.QxMessageRegistry;
import com.optel.qx.cci.payload.QxPayloadCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Qx 协议服务基类，提供 codec 自动扫描注册。
 */
public abstract class AbstractQxService {

    private static final Logger SCAN_LOG = LoggerFactory.getLogger(AbstractQxService.class);

    protected final QxMessageRegistry codecRegistry;

    /** 扫描巡检工具生成的 codec 所在包 */
    private static final String MODULE_BASE = "com.optel.qxinspection";

    protected AbstractQxService() {
        this.codecRegistry = scanGeneratedCodecs();
    }

    private static QxMessageRegistry scanGeneratedCodecs() {
        return scanFor(MODULE_BASE, QxGeneratedCodec.class);
    }

    @SuppressWarnings("unchecked")
    private static QxMessageRegistry scanFor(String basePackage, Class<?> targetInterface) {
        QxMessageRegistry reg = new QxMessageRegistry();
        List<String> registered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> skippedTemplates = new ArrayList<>();
        List<String> skippedRecords = new ArrayList<>();
        Map<Integer, String> cmdIndex = new TreeMap<>();
        Map<String, String[]> seen = new TreeMap<>();

        String pattern = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(pattern);
            SimpleMetadataReaderFactory factory = new SimpleMetadataReaderFactory();

            for (Resource resource : resources) {
                MetadataReader reader = factory.getMetadataReader(resource);

                if (reader.getClassMetadata().isInterface()
                        || reader.getClassMetadata().isAbstract()) {
                    continue;
                }

                if (!matchesInterface(reader, targetInterface)) {
                    continue;
                }

                String fullClassName = reader.getClassMetadata().getClassName();
                try {
                    Class<?> clazz = Class.forName(fullClassName);
                    QxPayloadCodec<?> codec =
                            (QxPayloadCodec<?>) clazz.getDeclaredConstructor().newInstance();

                    int cmd = codec.cmdCode() & 0xFFFF;
                    String dir = codec.direction();
                    String simpleName = codec.getClass().getSimpleName();
                    String ns = codec.namespace();
                    String collisionKey = String.format("0x%04X:%s", cmd, dir);

                    if (cmd == 0) {
                        skippedTemplates.add(String.format("  %s (0x0000:%s)", simpleName, dir));
                        continue;
                    }

                    if (codec.isListRecord()) {
                        skippedRecords.add(String.format(
                                "  %s (listRecord, %s)", simpleName, collisionKey));
                        continue;
                    }

                    String[] prev = seen.get(collisionKey);
                    if (prev != null) {
                        String prevName = prev[0];
                        String prevNs = prev[1];
                        String msg = String.format(
                                "cmdCode %s conflict: %s (ns=%s) vs %s (ns=%s)",
                                collisionKey, prevName, prevNs, simpleName, ns);
                        SCAN_LOG.error("=== cmdCode+direction conflict === {}", msg);
                        throw new IllegalStateException("Startup abort: " + msg);
                    }

                    reg.register(codec);
                    seen.put(collisionKey, new String[]{simpleName, ns});
                    registered.add(String.format("  %-45s cmdCode=0x%04X", simpleName, cmd));

                    String idxPrev = cmdIndex.get(cmd);
                    cmdIndex.put(cmd, (idxPrev != null ? idxPrev + ", " : "")
                            + simpleName + "(" + dir + ")");

                } catch (NoSuchMethodException e) {
                    skipped.add(fullClassName.substring(fullClassName.lastIndexOf('.') + 1));
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    SCAN_LOG.warn("Failed to instantiate codec: {}", fullClassName, e);
                    skipped.add(fullClassName.substring(fullClassName.lastIndexOf('.') + 1));
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            SCAN_LOG.error("Failed to scan package: {}", basePackage, e);
        }

        SCAN_LOG.info("=== codec registration: {} registered, {} template skipped, {} record skipped, {} manual-required ===",
                registered.size(), skippedTemplates.size(), skippedRecords.size(), skipped.size());
        registered.forEach(SCAN_LOG::info);

        if (!skippedTemplates.isEmpty()) {
            SCAN_LOG.info("--- skipped 0x0000 template codecs ---");
            skippedTemplates.forEach(SCAN_LOG::info);
        }
        if (!skippedRecords.isEmpty()) {
            SCAN_LOG.info("--- skipped listOf record codecs ---");
            skippedRecords.forEach(SCAN_LOG::info);
        }
        if (!skipped.isEmpty()) {
            SCAN_LOG.warn("--- codecs requiring manual registration ---");
            skipped.forEach(name -> SCAN_LOG.warn("  - {}", name));
        }

        SCAN_LOG.info("=== cmdIndex ({} unique cmdCodes) ===", cmdIndex.size());
        for (Map.Entry<Integer, String> entry : cmdIndex.entrySet()) {
            SCAN_LOG.info(String.format("  0x%04X -> %s", entry.getKey(), entry.getValue()));
        }
        return reg;
    }

    private static boolean matchesInterface(MetadataReader reader, Class<?> target) {
        for (String iface : reader.getClassMetadata().getInterfaceNames()) {
            if (target.getName().equals(iface)) {
                return true;
            }
        }
        return false;
    }
}
