/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import java.util.List;

/**
 * The {@code amr} values the Authorization Server writes into access and ID
 * tokens, one set per way the subscriber was authenticated. The NV resource
 * server accepts a token only when its amr contains {@code mno}: the operator,
 * not the client, authenticated the subscriber.
 *
 * <table>
 *   <caption>authentication → amr</caption>
 *   <tr><td>network-based: source IP:port:ts resolved by the operator</td><td>{@code ["mno"]}</td></tr>
 *   <tr><td>SIM-based: GSMA TS.43 operator token (EAP-AKA against the USIM, run by the operator)</td>
 *       <td>{@code ["hwk", "mno"]} — RFC 8176 {@code hwk}: proof of possession of a hardware-secured key</td></tr>
 *   <tr><td>asserted {@code tel:} subject (CIBA / JWT bearer)</td><td>none — nobody authenticated anything</td></tr>
 * </table>
 */
public final class AuthMethods {

    /** Network-based authentication by the mobile network operator. */
    public static final List<String> NETWORK = List.of("mno");

    /** SIM-based authentication: an operator token the operator minted after EAP-AKA. */
    public static final List<String> SIM = List.of("hwk", "mno");

    /** No authentication: the client asserted the number. */
    public static final List<String> NONE = List.of();

    private AuthMethods() {
    }
}
