/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding.backend;

import et.restlink.sas.ras.binding.SubscriberBinding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Export numbers go through the shared normalizer; IMSI keys are identities, not numbers. */
class SubscriberDbBindingTest {

    @Test
    void exportNumbersAreStoredInCanonicalForm(@TempDir Path dir) throws Exception {
        Path export = dir.resolve("subscribers.json");
        Files.writeString(export, """
                [{"imsi":"655010000000001","msisdn":"251911111111"},
                 {"imsi":"655010000000002@wlan.mnc001.mcc655.3gppnetwork.org","msisdn":"+251 911-111-112"},
                 {"imsi":"655010000000003","msisdn":"not-a-number"}]
                """);
        SubscriberDbBinding db = new SubscriberDbBinding(export);

        assertEquals("+251911111111", db.lookup("655010000000001").get().msisdn());
        assertEquals("+251911111112", db.lookup("655010000000002").get().msisdn(),
                "NAI realm cut from the IMSI key; separators gone from the number");
        SubscriberBinding raw = db.lookup("655010000000003").get();
        assertEquals("not-a-number", raw.msisdn(), "kept raw, so it never matches a valid claim");
        assertFalse(db.lookup("655019999999999").get().resolved());
    }
}
