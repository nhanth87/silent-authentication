/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.diameter;

import et.restlink.sas.config.SasTransportConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-realm + multi-application Diameter configuration (JSON) used by the
 * S6a (ULR/ULA + Sh UDR) and SWx (MAR/MAA) verifiers.
 *
 * <p>Keep this POJO Jackson-friendly: plain public fields are the source of
 * truth for {@code diameter.json} round-trips. {@link #validate()} is
 * fail-closed and is called before anything is applied to a live stack.</p>
 */
public class DiameterConfig {

    public static final String DEFAULT_TYPE_AUTH = "auth";
    public static final String DEFAULT_TYPE_ACCT = "acct";
    public static final String DEFAULT_TYPE_VENDOR = "vendor";
    public static final long DEFAULT_VENDOR_3GPP = 10_415L;

    /**
     * Visited-PLMN digits when {@link #visitedPlmn} is unset: MCC 636
     * (Ethiopia) + MNC 01 (Ethio Telecom) — the home PLMN of restlink.et.
     */
    public static final String DEFAULT_VISITED_PLMN = "63601";

    public String localHost;
    public String localRealm;
    public String destinationHost;
    public String destinationRealm;
    public String peerHost;
    public int peerPort = 3868;
    public boolean isServer;
    public boolean isSctp = true;

    /** Visited-PLMN digits (5–6 numeric, MCC+MNC) sent as Visited-PLMN-Id AVP. */
    public String visitedPlmn;

    /** Legacy TS 29.272 IDR/IDA probe stage (removed from the verify path; kept for config back-compat only). */
    public Boolean s6aIdrProbeEnabled;

    /** TS 29.273 SAR/SAA server-name registration after MAR. Default on. */
    public Boolean swxSarEnabled;

    /** TS 29.273 PPR/PPA probe stage (HSS-initiated per spec — lab only). Default off. */
    public Boolean swxPprProbeEnabled;

    public List<Application> applications = new ArrayList<>();
    public List<Realm> realms = new ArrayList<>();

    public static class Application {
        public long id;
        public String name;
        public String type = DEFAULT_TYPE_AUTH;
        public long vendorId = DEFAULT_VENDOR_3GPP;
    }

    public static class Realm {
        public String originRealm;
        public String destinationRealm;
        public List<Long> applicationIds = new ArrayList<>();
    }

    /**
     * Working single-realm config mirroring the current
     * {@link SasTransportConfig} diameter defaults.
     */
    public static DiameterConfig defaults() {
        DiameterConfig cfg = new DiameterConfig();
        cfg.localHost = "sas.restlink.et";
        cfg.localRealm = "restlink.et";
        cfg.destinationHost = "hss.restlink.et";
        cfg.destinationRealm = "restlink.et";
        cfg.peerHost = "127.0.0.1";
        cfg.peerPort = 3868;
        cfg.isServer = false;
        cfg.isSctp = true;

        cfg.applications = List.of(
                app(16_777_251L, "S6a", DEFAULT_TYPE_AUTH, DEFAULT_VENDOR_3GPP),
                app(16_777_265L, "SWx", DEFAULT_TYPE_AUTH, DEFAULT_VENDOR_3GPP));

        cfg.realms = List.of(realm(cfg.localRealm, cfg.destinationRealm,
                List.of(16_777_251L, 16_777_265L)));
        return cfg;
    }

    /**
     * Bridge from the legacy {@code sas.transport.diameter.*} MP-config values
     * so existing {@code Corsac*VerifierBackend(SasTransportConfig)} ctors keep
     * working unchanged.
     */
    public static DiameterConfig fromTransportConfig(SasTransportConfig t) {
        DiameterConfig cfg = new DiameterConfig();
        cfg.localHost = t.diameterOriginHost();
        cfg.localRealm = t.diameterRealm();
        cfg.destinationHost = t.diameterDestinationHost();
        cfg.destinationRealm = t.diameterDestinationRealm();
        cfg.peerHost = t.diameterPeerHost();
        cfg.peerPort = t.diameterPeerPort();
        cfg.isServer = false;
        cfg.isSctp = true;
        cfg.applications = List.of(
                app(16_777_251L, "S6a", DEFAULT_TYPE_AUTH, DEFAULT_VENDOR_3GPP),
                app(16_777_265L, "SWx", DEFAULT_TYPE_AUTH, DEFAULT_VENDOR_3GPP));
        cfg.realms = List.of(realm(cfg.localRealm, cfg.destinationRealm,
                List.of(16_777_251L, 16_777_265L)));
        return cfg;
    }

    private static Application app(long id, String name, String type, long vendorId) {
        Application a = new Application();
        a.id = id;
        a.name = name;
        a.type = type;
        a.vendorId = vendorId;
        return a;
    }

    private static Realm realm(String originRealm, String destinationRealm,
                               List<Long> applicationIds) {
        Realm r = new Realm();
        r.originRealm = originRealm;
        r.destinationRealm = destinationRealm;
        r.applicationIds = applicationIds == null ? new ArrayList<>() : new ArrayList<>(applicationIds);
        return r;
    }

    /** Effective origin host for a link (destination host falls back to local host). */
    public String effectiveDestinationHost() {
        return isBlank(destinationHost) ? localHost : destinationHost;
    }

    /** Effective realm for a link. */
    public String effectiveDestinationRealm() {
        return isBlank(destinationRealm) ? localRealm : destinationRealm;
    }

    /** Effective peer host for a link. */
    public String effectivePeerHost() {
        return isBlank(peerHost) ? "127.0.0.1" : peerHost;
    }

    /** Visited-PLMN digits for the Verifier probes (never blank). */
    public String effectiveVisitedPlmn() {
        return isBlank(visitedPlmn) ? DEFAULT_VISITED_PLMN : visitedPlmn.trim();
    }

    public boolean idrProbeOrDefault() {
        return s6aIdrProbeEnabled != null && s6aIdrProbeEnabled;
    }

    public boolean sarOrDefault() {
        return swxSarEnabled == null || swxSarEnabled;
    }

    public boolean pprProbeOrDefault() {
        return swxPprProbeEnabled != null && swxPprProbeEnabled;
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (isBlank(localHost)) {
            errors.add("localHost is required");
        }
        if (isBlank(localRealm)) {
            errors.add("localRealm is required");
        }
        if (isBlank(destinationRealm)) {
            errors.add("destinationRealm is required");
        }
        if (!isBlank(visitedPlmn) && !visitedPlmn.trim().matches("\\d{5,6}")) {
            errors.add("visitedPlmn must be 5-6 numeric digits (MCC+MNC)");
        }
        if (applications == null || applications.isEmpty()) {
            errors.add("at least one application is required");
        } else {
            int i = 0;
            for (Application app : applications) {
                if (app == null) {
                    errors.add("applications[" + i + "] is null");
                } else {
                    if (app.id <= 0) {
                        errors.add("applications[" + i + "].id must be positive");
                    }
                    if (isBlank(app.name)) {
                        errors.add("applications[" + i + "].name is required");
                    }
                }
                i++;
            }
        }
        if (realms != null) {
            int i = 0;
            for (Realm realm : realms) {
                if (realm == null) {
                    errors.add("realms[" + i + "] is null");
                } else {
                    if (isBlank(realm.destinationRealm)) {
                        errors.add("realms[" + i + "].destinationRealm is required");
                    }
                    if (realm.applicationIds == null || realm.applicationIds.isEmpty()) {
                        errors.add("realms[" + i + "].applicationIds must not be empty");
                    }
                }
                i++;
            }
        }
        return errors;
    }

    /** @throws IllegalArgumentException when a required field is missing/invalid. */
    public void validate() {
        List<String> errors = validationErrors();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    @Override
    public String toString() {
        int appCount = applications == null ? 0 : applications.size();
        int realmCount = realms == null ? 0 : realms.size();
        return "DiameterConfig{localHost='" + localHost + "', localRealm='" + localRealm
                + "', destinationRealm='" + destinationRealm + "', applications=" + appCount
                + ", realms=" + realmCount + "}";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}