/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.config;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * P2 transport selection — switches each verifier between the in-memory pilot
 * backend and the real signalling transport.
 *
 * <ul>
 *   <li>{@code sas.transport.map} — {@code memory} (default) or {@code jss7}.</li>
 *   <li>{@code sas.transport.s6a} — {@code memory} (default) or {@code corsac}.</li>
 *   <li>{@code sas.transport.swx} — {@code memory} (default) or {@code corsac}.</li>
 * </ul>
 *
 * <p>Fail-closed: an unknown value falls back to the in-memory backend so a
 * misconfiguration can never silently open a signalling path.</p>
 */
@ApplicationScoped
public class SasTransportConfig {

    @ConfigProperty(name = "sas.transport.map", defaultValue = "memory")
    String mapTransport;

    @ConfigProperty(name = "sas.transport.s6a", defaultValue = "memory")
    String s6aTransport;

    @ConfigProperty(name = "sas.transport.swx", defaultValue = "memory")
    String swxTransport;

    /** Resolver source: memory (default), cgnat, radius, or sd (PCRF Gx probe). */
    @ConfigProperty(name = "sas.transport.resolver", defaultValue = "memory")
    String resolverTransport;

    /** PCRF Sd/Gx specific peer port override (falls back to diameter peer port). */
    @ConfigProperty(name = "sas.transport.sd.peer-port", defaultValue = "3868")
    int sdPeerPort;

    /** PCRF Sd/Gx transport: SCTP when true (default), TCP otherwise. */
    @ConfigProperty(name = "sas.transport.sd.sctp", defaultValue = "true")
    boolean sdSctp;

    /** PCRF Sd/Gx per-probe timeout in ms. */
    @ConfigProperty(name = "sas.transport.sd.timeout-ms", defaultValue = "500")
    long sdTimeoutMs;

    /** PCRF Sd/Gx binding cache staleness window in ms. */
    @ConfigProperty(name = "sas.transport.sd.stale-after-ms", defaultValue = "60000")
    long sdStaleAfterMs;

    /** CGNAT log path (used when resolver transport = cgnat). */
    @ConfigProperty(name = "sas.transport.resolver.cgnat-log")
    java.util.Optional<String> cgnatLogPath;

    /** RADIUS accounting listener UDP port (used when resolver transport = radius). */
    @ConfigProperty(name = "sas.transport.resolver.radius.port", defaultValue = "1813")
    int radiusPort;

    /** RADIUS shared secret (empty disables authenticator verification — lab only). */
    @ConfigProperty(name = "sas.transport.resolver.radius.secret")
    java.util.Optional<String> radiusSecret;

    /** RADIUS binding staleness window in ms. */
    @ConfigProperty(name = "sas.transport.resolver.radius.stale-after-ms", defaultValue = "60000")
    long radiusStaleAfterMs;

    /** CGNAT log tail refresh interval in ms. */
    @ConfigProperty(name = "sas.transport.resolver.cgnat-log.refresh-ms", defaultValue = "2000")
    long cgnatRefreshMs;

    /** CGNAT point-in-time staleness window in ms. */
    @ConfigProperty(name = "sas.transport.resolver.cgnat-log.stale-ms", defaultValue = "60000")
    long cgnatStaleMs;

    /** Path to the jSS7 stack JSON (used when map transport = jss7). */
    @ConfigProperty(name = "sas.transport.jss7.config")
    java.util.Optional<String> jss7ConfigPath;

    /** Diameter peer host for S6a/SWx (used when transport = corsac). */
    @ConfigProperty(name = "sas.transport.diameter.peer-host", defaultValue = "127.0.0.1")
    String diameterPeerHost;

    /** Diameter peer port for S6a/SWx. */
    @ConfigProperty(name = "sas.transport.diameter.peer-port", defaultValue = "3868")
    int diameterPeerPort;

    /**
     * SWx-specific peer port override — lets the SWx stack dial a separate
     * AAA/HSS simulator instance (one inbound SCTP association per listen
     * port on the lab HSS). Falls back to the common peer port.
     */
    @ConfigProperty(name = "sas.transport.diameter.swx.peer-port", defaultValue = "3868")
    int diameterSwxPeerPort;

    /** Local Diameter origin host. */
    @ConfigProperty(name = "sas.transport.diameter.origin-host",
            defaultValue = "sas.restlink.et")
    String diameterOriginHost;

    /** Local Diameter realm. */
    @ConfigProperty(name = "sas.transport.diameter.realm", defaultValue = "restlink.et")
    String diameterRealm;

    /** Destination (HSS/AAA) Diameter host. */
    @ConfigProperty(name = "sas.transport.diameter.destination-host",
            defaultValue = "hss.restlink.et")
    String diameterDestinationHost;

    /** Destination (HSS/AAA) Diameter realm. */
    @ConfigProperty(name = "sas.transport.diameter.destination-realm",
            defaultValue = "restlink.et")
    String diameterDestinationRealm;

    /** HLR/HSS Global Title for MAP PSI/SAI (used when map transport = jss7). */
    @ConfigProperty(name = "sas.transport.jss7.hlr-gt", defaultValue = "251911000000")
    String jss7HlrGt;

    /** Local SAS Global Title (SCCP calling party) for MAP dialogs. */
    @ConfigProperty(name = "sas.transport.jss7.local-gt", defaultValue = "251911999999")
    String jss7LocalGt;

    public boolean useJss7Map() {
        return "jss7".equalsIgnoreCase(mapTransport);
    }

    public boolean useCorsacS6a() {
        return "corsac".equalsIgnoreCase(s6aTransport);
    }

    public boolean useCorsacSwx() {
        return "corsac".equalsIgnoreCase(swxTransport);
    }

    public boolean useCgnatResolver() {
        return "cgnat".equalsIgnoreCase(resolverTransport);
    }

    public boolean useRadiusResolver() {
        return "radius".equalsIgnoreCase(resolverTransport);
    }

    public boolean useSdResolver() {
        return "sd".equalsIgnoreCase(resolverTransport);
    }

    public int sdPeerPort() {
        return sdPeerPort;
    }

    public boolean sdSctp() {
        return sdSctp;
    }

    public long sdTimeoutMs() {
        return sdTimeoutMs;
    }

    public long sdStaleAfterMs() {
        return sdStaleAfterMs;
    }

    public int radiusPort() {
        return radiusPort;
    }

    public String radiusSecret() {
        return radiusSecret.orElse("");
    }

    public long radiusStaleAfterMs() {
        return radiusStaleAfterMs;
    }

    public long cgnatRefreshMs() {
        return cgnatRefreshMs;
    }

    public long cgnatStaleMs() {
        return cgnatStaleMs;
    }

    public String cgnatLogPath() {
        return cgnatLogPath.orElse("");
    }

    public String jss7ConfigPath() {
        return jss7ConfigPath.orElse("");
    }

    public String diameterPeerHost() {
        return diameterPeerHost;
    }

    public int diameterPeerPort() {
        return diameterPeerPort;
    }

    public int diameterSwxPeerPort() {
        return diameterSwxPeerPort;
    }

    public String diameterOriginHost() {
        return diameterOriginHost;
    }

    public String diameterRealm() {
        return diameterRealm;
    }

    public String diameterDestinationHost() {
        return diameterDestinationHost;
    }

    public String diameterDestinationRealm() {
        return diameterDestinationRealm;
    }

    public String jss7HlrGt() {
        return jss7HlrGt;
    }

    public String jss7LocalGt() {
        return jss7LocalGt;
    }
}
