/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.IOException;

/**
 * One-call convenience for logging in over the cellular data bearer
 * (2G/3G/4G/5G). The app hands over nothing but a {@code Context}, an MSISDN
 * it owns and the SAS base URL; this class binds the cellular {@code Network},
 * stamps the tuple with the observed radio and posts it — then closes the
 * bearer so no socket or callback is left pinned in the bank app.
 *
 * <p><strong>4G/5G.</strong> Use {@link #silentLogin4g5g(Object, String, String,
 * String)} when the bank demands an EPS/5GS-verifiable path: the SDK then
 * accepts only an {@link AccessTech#LTE} or {@link AccessTech#NR} bearer and
 * fails closed ({@link CellularUnavailableException}) on anything else — the
 * app maps that to the OTP/passkey fallback, never a Wi-Fi retry.</p>
 */
public final class CellularCalls {

    private CellularCalls() {
        // static facade
    }

    /**
     * Silent-auth login pinned to a 4G/5G (LTE or NR) cellular bearer.
     *
     * @param context       an {@code android.content.Context}; typed {@code Object}
     *                      because this module compiles without {@code android.jar}
     * @param sasBaseUrl    SAS base URL, e.g. {@code https://sas.example.et} — no
     *                      trailing slash needed, {@code /session-tuple} is appended
     * @param claimedMsisdn the MSISDN the embedding app claims this phone owns,
     *                      or null; never read by the SDK itself
     * @param apiKey        tenant API key for {@code X-Api-Key}, or null when the
     *                      SAS does not enforce keys on this endpoint
     * @return HTTP status of {@code POST /session-tuple} — 200 seeds the binding
     * @throws CellularUnavailableException when no 4G/5G bearer is usable (mobile
     *         data off, airplane mode, Wi-Fi only): the app must fall back, fail
     *         closed, never retry over Wi-Fi
     * @throws IOException                  on transport-level failure
     */
    public static int silentLogin4g5g(Object context, String sasBaseUrl,
                                      String claimedMsisdn, String apiKey) throws IOException {
        return silentLogin(context, sasBaseUrl, claimedMsisdn, apiKey,
                CellularRequirement.CELLULAR_4G_PLUS);
    }

    /**
     * Silent-auth login pinned to any cellular bearer, downgraded with an
     * explicit {@link CellularRequirement#ANY} for the lab / TS.43 Wi-Fi path.
     */
    public static int silentLogin(Object context, String sasBaseUrl,
                                  String claimedMsisdn, String apiKey,
                                  CellularRequirement requirement) throws IOException {
        CellularRequirement need =
                requirement == null ? CellularRequirement.CELLULAR : requirement;
        try (CellularBearer bearer = CellularBearer.bind(context, need)) {
            TupleSnapshot snapshot =
                    TupleSnapshot.cellularNow(claimedMsisdn, bearer.accessTech());
            return new SessionTupleClient().post(
                    sasBaseUrl, snapshot, apiKey, bearer, need);
        }
    }
}