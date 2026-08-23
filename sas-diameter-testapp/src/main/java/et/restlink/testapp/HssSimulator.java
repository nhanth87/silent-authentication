/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Subscriber registry of the simulated HSS / 3GPP AAA. Keyed by IMSI and by
 * MSISDN (both point at the same {@link SubscriberState}), because the SAS
 * sends the Username AVP as IMSI when the resolver knows one, else MSISDN.
 */
public final class HssSimulator {

    /** Demo subscriber seeded by the SAS in-memory resolver defaults. */
    public static final String DEMO_IMSI = "655010000000001";
    public static final String DEMO_MSISDN = "+251911111111";

    private final MessageLog messageLog;
    private final Map<String, SubscriberState> byImsi = new LinkedHashMap<>();
    private final Map<String, SubscriberState> byMsisdn = new LinkedHashMap<>();

    public HssSimulator(MessageLog messageLog) {
        this.messageLog = messageLog;
        addDemo();
    }

    private void addDemo() {
        SubscriberState demo = new SubscriberState(DEMO_IMSI, DEMO_MSISDN);
        byImsi.put(DEMO_IMSI, demo);
        byMsisdn.put(DEMO_MSISDN, demo);
    }

    /**
     * Resolve a Diameter Username AVP to subscriber state. Accepts IMSI or
     * MSISDN with or without the leading '+'.
     */
    public Optional<SubscriberState> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String key = username.trim();
        String normalized = key.startsWith("+") ? key : "+" + key;
        SubscriberState state = byImsi.get(key);
        if (state != null) {
            return Optional.of(state);
        }
        state = byMsisdn.get(key);
        if (state != null) {
            return Optional.of(state);
        }
        return Optional.ofNullable(byMsisdn.get(normalized));
    }

    /** Register or replace a subscriber (control API convenience). */
    public synchronized SubscriberState upsert(String imsi, String msisdn) {
        SubscriberState existing = byImsi.get(imsi);
        if (existing != null && existing.msisdn().equals(msisdn)) {
            return existing;
        }
        SubscriberState fresh = new SubscriberState(imsi, msisdn);
        byImsi.put(imsi, fresh);
        byMsisdn.put(msisdn, fresh);
        return fresh;
    }

    public Collection<SubscriberState> subscribers() {
        return byImsi.values();
    }

    public MessageLog log() {
        return messageLog;
    }

    /** Clear the ring buffer and restore every subscriber's default state. */
    public synchronized void reset() {
        messageLog.clear();
        byImsi.values().forEach(SubscriberState::resetDefaults);
    }

    /** True when the Username matches no provisioned subscriber. */
    public boolean isKnownUser(String username) {
        return find(username).isPresent();
    }
}
