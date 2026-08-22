/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Deterministic builder for the {@code ss7-sas.json}-shaped stack document,
 * synthesised from raw properties when no persisted JSON / config file exists.
 *
 * <p>Output mirrors {@code src/main/resources/ss7-sas.json}: MAP only, a single
 * loopback SCTP link, one M3UA AS toward the home STP, SCCP global-title
 * routing for the HLR GT (SSN 6), and bounded TCAP dialog timers. It is plain
 * JSON (no comments) so {@link Ss7AdminSupport#validate(String)} accepts it
 * directly via both the Jackson syntax check and
 * {@code Ss7ConfigLoader.parse}.</p>
 */
public final class Ss7JsonBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DEFAULT_HLR_GT = "251911000000";
    private static final String DEFAULT_LOCAL_GT = "251911999999";
    private static final int HLR_SSN = 6;

    private Ss7JsonBuilder() {
    }

    /**
     * Build the stack JSON from global titles + point codes.
     *
     * @param hlrGt   destination HLR global title (SCCP called party)
     * @param localGt local SAS global title (SCCP calling party)
     * @param localPc local SCCP/MTP point code (OPC)
     * @param stpPc   home STP / HLR point code (DPC)
     */
    public static String fromProperties(String hlrGt, String localGt, int localPc, int stpPc) {
        String h = blankToNull(hlrGt) == null ? DEFAULT_HLR_GT : hlrGt.trim();
        String l = blankToNull(localGt) == null ? DEFAULT_LOCAL_GT : localGt.trim();
        int lpc = localPc <= 0 ? 1 : localPc;
        int spc = stpPc <= 0 ? 2 : stpPc;

        ObjectNode root = JSON.createObjectNode();
        root.put("stackName", "sas-ss7");

        ObjectNode protocols = root.putObject("protocols");
        protocols.put("map", true);
        protocols.put("cap", false);

        // ── SCTP transport toward the home STP ──────────────────────────────
        ObjectNode sctp = root.putObject("sctp");
        sctp.put("connectDelay", 5000);
        sctp.put("workerThreads", 4);
        ObjectNode link = sctp.putArray("links").addObject();
        link.put("name", "SAS-STP-A");
        link.put("local", "127.0.0.1:2905");
        link.put("peer", "127.0.0.1:2906");
        link.put("aspId", 1);

        // ── M3UA ────────────────────────────────────────────────────────────
        ObjectNode m3ua = root.putObject("m3ua");
        ObjectNode as = m3ua.putArray("as").addObject();
        as.put("name", "SAS-AS");
        as.put("mode", "loadshare");
        as.put("functionality", "as");
        as.put("ipsp", "client");
        as.put("routingContext", 0);
        as.putArray("links").add("SAS-STP-A");

        ObjectNode m3uaRoute = m3ua.putArray("routes").addObject();
        ObjectNode m3uaTo = m3uaRoute.putObject("to");
        m3uaTo.put("dpc", spc);
        m3uaTo.put("opc", lpc);
        m3uaRoute.put("via", "SAS-AS");

        // ── SCCP ────────────────────────────────────────────────────────────
        ObjectNode sccp = root.putObject("sccp");
        ObjectNode localPoint = sccp.putArray("localPoints").addObject();
        localPoint.put("pc", lpc);
        localPoint.put("networkIndicator", "national");
        localPoint.put("networkId", 0);
        localPoint.putArray("reachablePointCodes").add(spc);

        ArrayNode routing = sccp.putArray("routing");

        // Outbound: HLR GT -> STP at PC spc, SSN 6.
        ObjectNode outboundHlr = routing.addObject();
        outboundHlr.put("from", "local");
        outboundHlr.putObject("match").put("gt", h);
        outboundHlr.putObject("to").put("pc", spc).put("ssn", HLR_SSN);

        // Outbound fallback: any other called GT -> STP (masked).
        ObjectNode outboundAny = routing.addObject();
        outboundAny.put("from", "local");
        outboundAny.putObject("match").put("gt", "*");
        outboundAny.putObject("to").put("pc", spc).put("ssn", HLR_SSN);
        outboundAny.put("mask", "K");

        // Inbound: responses addressed to our local GT -> local PC on SSN 6.
        ObjectNode inboundLocal = routing.addObject();
        inboundLocal.put("from", "remote");
        inboundLocal.putObject("match").put("gt", l);
        inboundLocal.putObject("to").put("pc", lpc).put("ssn", HLR_SSN);

        // Inbound fallback.
        ObjectNode inboundAny = routing.addObject();
        inboundAny.put("from", "remote");
        inboundAny.putObject("match").put("gt", "*");
        inboundAny.putObject("to").put("pc", lpc).put("ssn", HLR_SSN);

        // ── TCAP — dialog hygiene (bounded idle + invoke timers) ────────────
        ObjectNode tcap = root.putObject("tcap");
        tcap.put("dialogIdleTimeout", 5000);
        tcap.put("invokeTimeout", 2500);
        tcap.put("maxDialogs", 1000);

        // ── services: SSN 6 = HLR service on this stack ─────────────────────
        ObjectNode service = root.putArray("services").addObject();
        service.put("name", "hlr");
        service.put("ssn", HLR_SSN);
        service.put("protocol", "map");

        return root.toPrettyString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}