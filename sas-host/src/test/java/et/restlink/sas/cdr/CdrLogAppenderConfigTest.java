/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdrLogAppenderConfigTest {

    @Test
    void log4j2RoutesCdrRowsToDurableRollingFile() throws IOException {
        String xml;
        try (InputStream in = CdrLogAppenderConfigTest.class.getResourceAsStream("/log4j2.xml")) {
            assertNotNull(in, "log4j2.xml missing");
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("<RollingFile name=\"cdr\""), "CDR rolling file appender missing");
        assertTrue(xml.contains("${sys:sas.log.dir:-target/logs}/sas.cdr"), "active CDR path missing");
        assertTrue(xml.contains("sas-%d{yyyy-MM-dd}-%i.cdr"), "CDR rollover pattern missing");
        assertTrue(xml.contains("<Logger name=\"SAS_CDR\" level=\"INFO\" additivity=\"false\">"),
                "SAS_CDR logger missing");
        assertTrue(xml.contains("<AppenderRef ref=\"cdr\"/>"), "CDR appender not wired");
        assertTrue(xml.contains("<PatternLayout pattern=\"%m%n\"/>"), "CDR must remain raw CSV");
    }
}
