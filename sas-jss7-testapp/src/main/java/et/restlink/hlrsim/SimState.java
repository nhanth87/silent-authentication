/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Subscriber data for the lab HLR, plus the AKA vector material it hands out.
 *
 * <p>Two things this adds over the original single-flag {@code SimState}: a real
 * <b>IMSI ↔ MSISDN table</b> (so MAP {@code SendIMSI} and the entitlement binding path
 * have something to answer with), and a <b>UMTS quintuplet</b> for
 * {@code SendAuthenticationInfo} — GSM triplets cannot ground EAP-AKA, which needs
 * {@code RES}, {@code CK} and {@code IK}.</p>
 *
 * <p><strong>The vector is deterministic on purpose.</strong> A demo has to work, and a
 * demo device cannot be asked to hold the subscriber key. So instead of running Milenage
 * the simulator derives the quintuplet from a per-subscriber demo secret and the RAND it
 * is about to hand out — the SAS can then verify {@code AT_RES}, and the scripted device
 * can present the matching {@code RES} without ever seeing a key. This is a lab fiction
 * and is labelled as such everywhere: real HSSs derive RES from {@code K} inside the
 * AuC and never transmit it.</p>
 */
public final class SimState {

    /** The demo subscriber seeded so the lab works out of the box. */
    public static final String DEFAULT_IMSI = "655010000000001";
    public static final String DEFAULT_MSISDN = "+251911111111";

    private final Map<String, Subscriber> byImsi = new LinkedHashMap<>();
    private final Map<String, String> msisdnToImsi = new LinkedHashMap<>();
    private final List<String> issued = new ArrayList<>();

    public SimState() {
        add(DEFAULT_IMSI, DEFAULT_MSISDN);
    }

    /**
     * One simulated subscriber.
     *
     * @param imsi    SIM identity, digits only
     * @param msisdn  the E.164 number bound to it
     * @param attached whether the HLR considers it reachable
     * @param vectors how many quintuplets it is still willing to serve
     */
    public record Subscriber(String imsi, String msisdn, boolean attached, int vectors) {

        public boolean umtsCapable() {
            return true;
        }
    }

    public synchronized Subscriber add(String imsi, String msisdn) {
        String key = normalise(imsi);
        // Indexed by digits only: MAP carries the number as a national significant
        // number (no '+'), while the control UI and the demo use E.164 with one.
        msisdnToImsi.put(digits(msisdn), key);
        return byImsi.put(key, new Subscriber(key, msisdn.trim(), true, 1));
    }

    public synchronized Optional<Subscriber> byImsi(String imsi) {
        return Optional.ofNullable(byImsi.get(normalise(imsi)));
    }

    /** The number → IMSI direction, i.e. what MAP {@code SendIMSI} answers. */
    public synchronized Optional<Subscriber> byMsisdn(String msisdn) {
        if (msisdn == null) {
            return Optional.empty();
        }
        String key = msisdnToImsi.get(msisdn.trim());
        return key == null ? Optional.empty() : Optional.ofNullable(byImsi.get(key));
    }

    public synchronized List<Subscriber> all() {
        return List.copyOf(byImsi.values());
    }

    /** Legacy single-subscriber controls, kept so the existing control UI still works. */
    public boolean attached() {
        return all().stream().findFirst().map(Subscriber::attached).orElse(false);
    }

    public void setAttached(boolean attached) {
        setAttachedForAll(attached);
    }

    public synchronized void setAttachedForAll(boolean attached) {
        List<String> keys = List.copyOf(byImsi.keySet());
        for (String key : keys) {
            Subscriber s = byImsi.get(key);
            byImsi.put(key, new Subscriber(s.imsi(), s.msisdn(), attached, s.vectors()));
        }
    }

    public int vectors() {
        return all().stream().findFirst().map(Subscriber::vectors).orElse(0);
    }

    public void setVectors(int vectors) {
        setVectorsForAll(vectors);
    }

    public synchronized void setVectorsForAll(int vectors) {
        int clamped = Math.max(0, vectors);
        List<String> keys = List.copyOf(byImsi.keySet());
        for (String key : keys) {
            Subscriber s = byImsi.get(key);
            byImsi.put(key, new Subscriber(s.imsi(), s.msisdn(), s.attached(), clamped));
        }
    }

    /** Reset every scenario back to the demo defaults. */
    public synchronized void reset() {
        byImsi.clear();
        msisdnToImsi.clear();
        issued.clear();
        add(DEFAULT_IMSI, DEFAULT_MSISDN);
    }

    /**
     * Mint a deterministic UMTS quintuplet for a subscriber: {@code RES}, {@code CK},
     * {@code IK}, plus the {@code RAND}/{@code AUTN} the SAS will put on the wire.
     *
     * <p>Derivation: {@code SHA-256("TS43-LAB-QUINTUPLET" | imsi | RAND)} truncated to the
     * lengths the AKA needs. Deterministic per (IMSI, RAND), so the scripted device can
     * predict {@code RES} from the {@code RAND} it received in
     * {@code EAP-Request/AKA-Challenge}.</p>
     */
    public synchronized byte[][] mintQuintuplet(Subscriber subscriber, byte[] rand) {
        byte[] res = derive(subscriber.imsi(), rand, "RES", 8);
        byte[] ck = derive(subscriber.imsi(), rand, "CK", 16);
        byte[] ik = derive(subscriber.imsi(), rand, "IK", 16);
        byte[] autn = derive(subscriber.imsi(), rand, "AUTN", 16);
        issued.add(subscriber.imsi());
        return new byte[][] {rand.clone(), res, ck, ik, autn};
    }

    /** How many vectors this simulator has served, for the control UI. */
    public synchronized int issuedCount() {
        return issued.size();
    }

    public void setIssuedCount(int ignored) {
        // kept for the legacy control UI; the counter is derived from the log now
    }

    /**
     * The {@code RES} a device must present for a given {@code RAND}. The demo driver
     * calls this; a real device would compute it from K inside the SIM.
     */
    public synchronized Optional<byte[]> expectedRes(String imsi, byte[] rand) {
        return byImsi(imsi).map(s -> mintQuintuplet(s, rand)[1]);
    }

    private static byte[] derive(String imsi, byte[] rand, String label, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("TS43-LAB-QUINTUPLET".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) '|');
            digest.update(imsi.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) '|');
            digest.update(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(rand);
            return java.util.Arrays.copyOf(digest.digest(), length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** E.164 digits: strip a leading '+' and any formatting, keep the digits. */
    private static String digits(String number) {
        if (number == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String normalise(String identity) {
        if (identity == null) {
            return "";
        }
        String value = identity.trim();
        int at = value.indexOf('@');
        if (at > 0) {
            value = value.substring(0, at);
        }
        return value.startsWith("+") ? value.substring(1) : value;
    }
}
