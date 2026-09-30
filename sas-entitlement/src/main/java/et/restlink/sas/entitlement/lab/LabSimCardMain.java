/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.lab;

import java.util.Base64;

/**
 * CLI wrapper around {@link LabSimAkaCard}: the "SIM" half of the lab demo, with no
 * HTTP in it.
 *
 * <pre>
 *   java -cp ... et.restlink.sas.entitlement.lab.LabSimCardMain &lt;imsi&gt; &lt;challenge-b64&gt;
 * </pre>
 *
 * <p>stdout is exactly one line — the base64 {@code EAP-Response/AKA-Challenge} — so a
 * shell driver can paste it straight into {@code POST /ts43/respond}. The demo script
 * ({@code scripts/ts43-lab-demo.sh}) owns the transport and this class owns the
 * cryptography; the split is not cosmetic. Gate H24 forbids raw HTTP clients in the
 * runtime modules, and a device-side card has no business making one either.</p>
 */
public final class LabSimCardMain {

    private LabSimCardMain() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: LabSimCardMain <imsi> <challenge-b64>");
            System.exit(2);
            return;
        }
        String imsi = args[0].trim();
        String challenge = args[1].trim();
        try {
            LabSimAkaCard card = new LabSimAkaCard(imsi);
            String response = card.respond(challenge);
            // One line, no decoration: the driver reads stdout verbatim.
            System.out.println(response);
        } catch (RuntimeException e) {
            System.err.println("card refused to answer: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Exposed so a shell driver (or a test) can decode a challenge without a card. */
    public static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64.trim());
    }
}