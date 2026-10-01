/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.id;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The single place a phone number changes form, on both binaries (layered ADR R9).
 *
 * <p>Three forms exist, and every conversion between them goes through here:</p>
 * <ul>
 *   <li><b>CAMARA canonical</b> — E.164 with one leading {@code +}: {@code +251911111111}.</li>
 *   <li><b>Internal / TS 23.003 MSISDN</b> — the same digits without {@code +}:
 *       {@code 251911111111}. Used on the exposure API ({@code msisdn}) and on the wire
 *       (MAP ISDN-AddressString, Diameter Subscription-Id).</li>
 *   <li><b>GPSI</b> (TS 29.571) — {@code msisdn-251911111111}.</li>
 * </ul>
 *
 * <p>Accepted input, with one documented outcome each:</p>
 * <ul>
 *   <li>any of the three forms above, and {@code tel:+…} (CAMARA {@code sub});</li>
 *   <li>separators — whitespace, {@code .}, {@code -}, {@code (}, {@code )} — are removed;</li>
 *   <li>several leading {@code +} collapse to one; a missing {@code +} is supplied, because
 *       the digits are then read as the internal international form;</li>
 *   <li>refused (empty): null/blank, letters, an {@code @realm} suffix (that is a NAI, not a
 *       number), a national or {@code 00}-prefixed number (leading {@code 0}: there is no
 *       country context here to expand it), and more than 15 digits.</li>
 * </ul>
 *
 * <p>The digit rule is the CAMARA {@code phoneNumber} pattern {@code ^\+[1-9][0-9]{1,14}$}.
 * Interfaces with a stricter length (the exposure API asks for 5–15 digits) validate that
 * on top.</p>
 */
public final class PhoneNumberNormalizer {

    /** CAMARA E.164: {@code +}, first digit 1–9, 2–15 digits in all. */
    public static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{1,14}$");

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.()\\-]");
    private static final String TEL = "tel:";
    private static final String GPSI_MSISDN = "msisdn-";

    private PhoneNumberNormalizer() {
    }

    /** CAMARA canonical form ({@code +<digits>}), or empty when the input is not a number. */
    public static Optional<String> toE164(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.regionMatches(true, 0, TEL, 0, TEL.length())) {
            value = value.substring(TEL.length());
        } else if (value.regionMatches(true, 0, GPSI_MSISDN, 0, GPSI_MSISDN.length())) {
            value = value.substring(GPSI_MSISDN.length());
        }
        value = SEPARATORS.matcher(value).replaceAll("");
        int plus = 0;
        while (plus < value.length() && value.charAt(plus) == '+') {
            plus++;
        }
        value = value.substring(plus);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String e164 = "+" + value;
        return E164.matcher(e164).matches() ? Optional.of(e164) : Optional.empty();
    }

    /** Internal / TS 23.003 form (digits, no {@code +}). */
    public static Optional<String> toDigits(String raw) {
        return toE164(raw).map(e164 -> e164.substring(1));
    }

    /** GPSI form ({@code msisdn-<digits>}, TS 29.571). */
    public static Optional<String> toGpsi(String raw) {
        return toDigits(raw).map(digits -> GPSI_MSISDN + digits);
    }

    /** True when both inputs normalize to the same number; false if either is not a number. */
    public static boolean sameNumber(String a, String b) {
        Optional<String> left = toE164(a);
        return left.isPresent() && left.equals(toE164(b));
    }

    /** True when the input already is in CAMARA canonical form, with nothing to normalize. */
    public static boolean isE164(String value) {
        return value != null && E164.matcher(value).matches();
    }
}
