/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.id;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layered ADR §9.1 test D: one matrix, one documented outcome per input shape. */
class PhoneNumberNormalizerTest {

    @ParameterizedTest(name = "{0} → {1} / {2} / {3}")
    @CsvSource(delimiter = '|', value = {
            // input                 | CAMARA canonical  | internal (TS 23.003) | GPSI
            "+84901234567            | +84901234567      | 84901234567          | msisdn-84901234567",
            "84901234567             | +84901234567      | 84901234567          | msisdn-84901234567",
            "+251911111111           | +251911111111     | 251911111111         | msisdn-251911111111",
            "251911111111            | +251911111111     | 251911111111         | msisdn-251911111111",
            "+84 90-123.4567         | +84901234567      | 84901234567          | msisdn-84901234567",
            "(+251)-911.111.111      | +251911111111     | 251911111111         | msisdn-251911111111",
            "++251911111111          | +251911111111     | 251911111111         | msisdn-251911111111",
            "tel:+84901234567        | +84901234567      | 84901234567          | msisdn-84901234567",
            "TEL:+251911111111       | +251911111111     | 251911111111         | msisdn-251911111111",
            "msisdn-84901234567      | +84901234567      | 84901234567          | msisdn-84901234567",
            "+123456789012345        | +123456789012345  | 123456789012345      | msisdn-123456789012345",
    })
    void convertsEveryAcceptedShape(String input, String e164, String digits, String gpsi) {
        assertEquals(Optional.of(e164), PhoneNumberNormalizer.toE164(input));
        assertEquals(Optional.of(digits), PhoneNumberNormalizer.toDigits(input));
        assertEquals(Optional.of(gpsi), PhoneNumberNormalizer.toGpsi(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "+",
            "abc",
            "+84a01234567",          // letter
            "0901234567",            // national form: no country context to expand it
            "0084901234567",         // 00 international prefix
            "+0123456789",           // E.164 never starts with 0
            "+1234567890123456",     // 16 digits
            "84901234567@ims.mnc001.mcc452.3gppnetwork.org", // NAI, not a number
            "msisdn-",
    })
    void refusesWhatIsNotANumber(String input) {
        assertEquals(Optional.empty(), PhoneNumberNormalizer.toE164(input));
        assertEquals(Optional.empty(), PhoneNumberNormalizer.toDigits(input));
        assertEquals(Optional.empty(), PhoneNumberNormalizer.toGpsi(input));
    }

    @Test
    void roundTripsBetweenTheThreeForms() {
        String gpsi = PhoneNumberNormalizer.toGpsi("+84901234567").orElseThrow();
        String digits = PhoneNumberNormalizer.toDigits(gpsi).orElseThrow();
        assertEquals("+84901234567", PhoneNumberNormalizer.toE164(digits).orElseThrow());
    }

    @Test
    void sameNumberAcrossForms() {
        assertTrue(PhoneNumberNormalizer.sameNumber("+84 901 234 567", "msisdn-84901234567"));
        assertTrue(PhoneNumberNormalizer.sameNumber("tel:+251911111111", "251911111111"));
        assertFalse(PhoneNumberNormalizer.sameNumber("+84901234567", "+84901234568"));
        assertFalse(PhoneNumberNormalizer.sameNumber(null, null), "two non-numbers are not equal numbers");
        assertFalse(PhoneNumberNormalizer.sameNumber("abc", "abc"));
    }

    @Test
    void isE164OnlyAcceptsTheCanonicalForm() {
        assertTrue(PhoneNumberNormalizer.isE164("+84901234567"));
        assertFalse(PhoneNumberNormalizer.isE164("84901234567"));
        assertFalse(PhoneNumberNormalizer.isE164("+84 901234567"));
        assertFalse(PhoneNumberNormalizer.isE164(null));
    }

    /**
     * Source scan: no other phone-number normalizer may exist in the reactor modules.
     * The HLR simulator (sas-jss7-testapp) is a standalone build that plays the operator
     * and is deliberately out of scope.
     */
    @Test
    void noOtherNormalizerInTheReactor() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Pattern declaration = Pattern.compile(
                "\\b(String|Optional<String>)\\s+normali[sz]e(E164|Msisdn|Msisdns|Phone\\w*|Number)\\s*\\(");
        List<String> offenders = new ArrayList<>();
        for (String module : List.of("sas-common", "sas-token", "sas-operator-adapter", "sas-api",
                "sas-entitlement", "sas-host", "sas-ecs-host")) {
            Path src = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals("PhoneNumberNormalizer.java"))
                        .forEach(p -> {
                            try {
                                if (declaration.matcher(Files.readString(p)).find()) {
                                    offenders.add(root.relativize(p).toString());
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertEquals(List.of(), offenders, "use PhoneNumberNormalizer instead");
    }
}
