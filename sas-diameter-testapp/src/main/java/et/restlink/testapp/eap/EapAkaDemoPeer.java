/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp.eap;

import et.restlink.testapp.web.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * EAP-AKA demo peer — the POC "device + 3GPP AAA + TS.43 entitlement
 * integration" in one small, dependency-free runnable.
 *
 * <p>In production EAP-AKA terminates at the operator 3GPP AAA, which then
 * asserts the outcome to the SAS. This peer stands in for that AAA on the
 * entitlement leg of the lab loop:</p>
 *
 * <ol>
 *   <li>Simulates a successful EAP-AKA run (shape only — no SIM crypto).</li>
 *   <li>Calls {@code POST /entitlement/issue} on the SAS, optionally with the
 *       AAA attestation HMAC.</li>
 *   <li>Optionally redeems the returned token through the CAMARA {@code /verify}
 *       Wi-Fi path ({@code Bearer operatortoken:tk}) end-to-end.</li>
 * </ol>
 *
 * <p>Run against a live SAS (default {@code http://127.0.0.1:8085}):</p>
 *
 * <pre>
 * java -cp target/sas-diameter-testapp.jar et.restlink.testapp.eap.EapAkaDemoPeer \
 *      --sas-url http://127.0.0.1:8085 --api-key lab \
 *      --msisdn +251911111111 --imsi 655010000000001 \
 *      --eap-method EAP-AKA [--attestation-secret shared-secret] [--no-redeem]
 * </pre>
 */
public final class EapAkaDemoPeer {

    private static final String DEFAULT_EAP = "EAP-AKA";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String sasUrl;
    private final String apiKey;
    private final String msisdn;
    private final String imsi;
    private final String eapMethod;
    private final String attestationSecret; // blank = omit attestation headers
    private final boolean redeem;

    public EapAkaDemoPeer(String sasUrl, String apiKey, String msisdn, String imsi,
                          String eapMethod, String attestationSecret, boolean redeem) {
        this.sasUrl = sasUrl == null || sasUrl.isBlank() ? "http://127.0.0.1:8085" : sasUrl;
        this.apiKey = apiKey == null || apiKey.isBlank() ? "lab" : apiKey;
        this.msisdn = msisdn;
        this.imsi = imsi;
        this.eapMethod = canonical(eapMethod);
        this.attestationSecret = attestationSecret;
        this.redeem = redeem;
    }

    public static void main(String[] args) {
        String sasUrl = "http://127.0.0.1:8085";
        String apiKey = "lab";
        String msisdn = "+251911111111";
        String imsi = "655010000000001";
        String eapMethod = DEFAULT_EAP;
        String attestationSecret = null;
        boolean redeem = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sas-url" -> sasUrl = next(args, ++i);
                case "--api-key" -> apiKey = next(args, ++i);
                case "--msisdn" -> msisdn = next(args, ++i);
                case "--imsi" -> imsi = next(args, ++i);
                case "--eap-method" -> eapMethod = next(args, ++i);
                case "--attestation-secret" -> attestationSecret = next(args, ++i);
                case "--no-redeem" -> redeem = false;
                default -> throw new IllegalArgumentException(
                        "unknown arg " + args[i]
                        + " (expected --sas-url, --api-key, --msisdn, --imsi,"
                        + " --eap-method, --attestation-secret, --no-redeem)");
            }
        }

        new EapAkaDemoPeer(sasUrl, apiKey, msisdn, imsi, eapMethod, attestationSecret, redeem)
                .run();
    }

    public void run() {
        System.out.println("=== EAP-AKA demo peer (operator-AAA stand-in) ===");
        System.out.println("SAS      : " + sasUrl);
        System.out.println("identity : " + msisdn + " / " + imsi + " (" + eapMethod + ")");
        System.out.println();

        String res = simulatedEapAka();
        System.out.println("1. EAP-AKA succeeded (simulated) - RES=" + res);
        System.out.println();

        String token = issue();
        System.out.println("2. POST /entitlement/issue -> token=" + token);
        System.out.println();

        if (redeem) {
            boolean verified = redeem(token);
            System.out.println("3. POST /verify (operatortoken) -> devicePhoneNumberVerified=" + verified);
            if (!verified) {
                System.out.println("   NOTE: false means the SAS Wi-Fi SWx verifier failed closed "
                        + "(no seeded Wi-Fi subscriber, or no live SWx transport).");
            }
            System.out.println();
        }

        System.out.println("Manual redeem equivalent:");
        System.out.println("  curl -sS -X POST " + sasUrl + "/verify \\");
        System.out.println("    -H 'Authorization: Bearer operatortoken:" + token + "' \\");
        System.out.println("    -H 'X-Api-Key: " + apiKey + "' \\");
        System.out.println("    -H 'Content-Type: application/json' -d '{}'");
    }

    /** Shape-only EAP-AKA walk — fabricates the challenge material for display. */
    private String simulatedEapAka() {
        String rand = randHex(16);
        String autn = randHex(16);
        String res = randHex(8);
        System.out.println("    EAP-Response/Identity    NAI=" + imsi + "@restlink.et");
        System.out.println("    EAP-Request/AKA-Challenge  RAND=" + rand + " AUTN=" + autn);
        System.out.println("    EAP-Response/AKA-Challenge RES=" + res);
        return res;
    }

    private String issue() {
        String body = "{\"msisdn\":" + Json.str(msisdn)
                + ",\"imsi\":" + Json.str(imsi)
                + ",\"eapMethod\":" + Json.str(eapMethod) + "}";

        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create(sasUrl + "/entitlement/issue"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (attestationSecret != null && !attestationSecret.isBlank()) {
            long ts = System.currentTimeMillis();
            String mac = hmacSha256Hex(attestationSecret,
                    msisdn + "|" + imsi + "|" + eapMethod + "|" + ts);
            request.header("X-Sas-Attestation-Ts", Long.toString(ts));
            request.header("X-Sas-Attestation-Mac", mac);
        }

        HttpResponse<String> response = send(request.build());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "issue failed " + response.statusCode() + ": " + response.body());
        }
        Map<String, Object> parsed = Json.parseFlatObject(response.body());
        Object token = parsed.get("token");
        if (token == null) {
            throw new IllegalStateException("issue response has no token: " + response.body());
        }
        Object ttl = parsed.get("expiresInSeconds");
        if (ttl instanceof Number n) {
            System.out.println("    expiresInSeconds=" + n.longValue());
        }
        return token.toString();
    }

    private boolean redeem(String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(sasUrl + "/verify"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .header("Authorization", "Bearer operatortoken:" + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) {
            System.out.println("    redeem HTTP " + response.statusCode() + ": " + response.body());
            return false;
        }
        try {
            Map<String, Object> parsed = Json.parseFlatObject(response.body());
            Object value = parsed.get("devicePhoneNumberVerified");
            return value instanceof Boolean b && b;
        } catch (IllegalArgumentException e) {
            // Assurance detail enabled returns a nested object — infer from the raw text.
            System.out.println("    non-flat /verify response: " + response.body());
            return response.body().contains("devicePhoneNumberVerified\":true");
        }
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted", e);
        }
    }

    private static String hmacSha256Hex(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String randHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** Normalize the EAP method to the SAS canonical encoding (ASCII apostrophe). */
    private static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_EAP;
        }
        String m = raw.trim().replace('_', '-');
        String apos = m.replace("EAP-AKA-PRIME", "EAP-AKA'")
                .replace("EAP-AKAPRIME", "EAP-AKA'");
        return switch (apos) {
            case "EAP-AKA", "EAP-AKA'" -> apos;
            default -> DEFAULT_EAP;
        };
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[i - 1]);
        }
        return args[i];
    }
}