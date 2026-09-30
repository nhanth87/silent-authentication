/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding;

/**
 * A resolved IMSI → MSISDN binding, plus the source that produced it.
 *
 * <p>Under the D6 / Shape S decision the EAP exchange proves SIM possession against an
 * IMSI, but the bank claims a <em>number</em>. Something has to say which number belongs
 * to that IMSI, and the answer is what this record carries. Because the answer decides
 * who is being logged in, {@link #SOURCE_UNRESOLVED} must never be treated as a match.</p>
 *
 * @param imsi   the identity the EAP exchange proved
 * @param msisdn the E.164 number bound to it, or {@code null} when unresolved
 * @param source which backend answered — recorded for audit and for the fail-closed
 *               diagnostic, never for trust decisions
 */
public record SubscriberBinding(String imsi, String msisdn, String source) {

    /** The only value {@code source} may take when no number was found. */
    public static final String SOURCE_UNRESOLVED = "unresolved";

    public static SubscriberBinding resolved(String imsi, String msisdn, String source) {
        if (msisdn == null || msisdn.isBlank()) {
            throw new IllegalArgumentException("a resolved binding needs an MSISDN");
        }
        return new SubscriberBinding(imsi, msisdn, source);
    }

    public static SubscriberBinding unresolved(String imsi, String source) {
        return new SubscriberBinding(imsi, null, source == null ? SOURCE_UNRESOLVED : source);
    }

    /** True only when a number was actually found. The caller must still verify it. */
    public boolean resolved() {
        return msisdn != null && !msisdn.isBlank();
    }

    @Override
    public String toString() {
        return "SubscriberBinding[imsi=<" + (imsi == null ? 0 : imsi.length())
                + " digits>, resolved=" + resolved() + ", source=" + source + "]";
    }
}
