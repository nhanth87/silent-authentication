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

    /** Resolver source: memory (default), cgnat, or radius. */
    @ConfigProperty(name = "sas.transport.resolver", defaultValue = "memory")
    String resolverTransport;

    /** CGNAT log path (used when resolver transport = cgnat). */
    @ConfigProperty(name = "sas.transport.resolver.cgnat-log", defaultValue = "")
    String cgnatLogPath;

    /** Path to the jSS7 stack JSON (used when map transport = jss7). */
    @ConfigProperty(name = "sas.transport.jss7.config", defaultValue = "")
    String jss7ConfigPath;

    /** Diameter peer host for S6a/SWx (used when transport = corsac). */
    @ConfigProperty(name = "sas.transport.diameter.peer-host", defaultValue = "127.0.0.1")
    String diameterPeerHost;

    /** Diameter peer port for S6a/SWx. */
    @ConfigProperty(name = "sas.transport.diameter.peer-port", defaultValue = "3868")
    int diameterPeerPort;

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

    public String cgnatLogPath() {
        return cgnatLogPath;
    }

    public String jss7ConfigPath() {
        return jss7ConfigPath;
    }

    public String diameterPeerHost() {
        return diameterPeerHost;
    }

    public int diameterPeerPort() {
        return diameterPeerPort;
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
