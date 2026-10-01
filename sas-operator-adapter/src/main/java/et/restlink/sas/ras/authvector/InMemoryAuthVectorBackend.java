/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lab-only {@link AuthVectorBackend}: serves deterministic vectors with no network at
 * all, so the entitlement FSM can be exercised before an operator HSS exists.
 *
 * <p><strong>Never legal in production.</strong> {@code PRO-30} refuses
 * {@code sas.transport.authvector=memory} in the prod profile; the values are
 * fabricated, so an EAP server driven by them would "authenticate" anything.</p>
 */
public final class InMemoryAuthVectorBackend implements AuthVectorBackend {

    private static final Logger LOG = LogManager.getLogger(InMemoryAuthVectorBackend.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final long latencyMs;

    public InMemoryAuthVectorBackend() {
        this(0);
    }

    public InMemoryAuthVectorBackend(long latencyMs) {
        this.latencyMs = Math.max(0, latencyMs);
    }

    @Override
    public CompletableFuture<AuthVector> fetch(String imsi, String scheme) {
        return serve(imsi, scheme, false);
    }

    @Override
    public CompletableFuture<AuthVector> resync(String imsi, String scheme,
                                                byte[] rand, byte[] auts) {
        if (auts == null || auts.length != 16) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("AT_AUTS must be 16 octets"));
        }
        return serve(imsi, scheme, true);
    }

    @Override
    public void stop() {
        // nothing to release
    }

    @Override
    public String name() {
        return "memory";
    }

    private CompletableFuture<AuthVector> serve(String imsi, String scheme, boolean resync) {
        if (imsi == null || imsi.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("IMSI is required"));
        }
        if (!AuthVector.EAP_AKA.equals(scheme) && !AuthVector.EAP_AKA_PRIME.equals(scheme)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unsupported AKA scheme: " + scheme));
        }
        byte[] rand = new byte[16];
        byte[] autn = new byte[16];
        byte[] ck = new byte[16];
        byte[] ik = new byte[16];
        RANDOM.nextBytes(rand);
        RANDOM.nextBytes(autn);
        RANDOM.nextBytes(ck);
        RANDOM.nextBytes(ik);
        // A lab XRES the synthetic UE can be told to answer.
        byte[] xres = new byte[8];
        RANDOM.nextBytes(xres);
        AuthVector vector = new AuthVector(rand, autn, xres, ck, ik, scheme);
        LOG.info("Lab auth vector minted imsi={} scheme={} resync={} xres={}",
                maskImsi(imsi), scheme, resync, HexFormat.of().formatHex(xres));
        CompletableFuture<AuthVector> out = new CompletableFuture<>();
        if (latencyMs > 0) {
            CompletableFuture.delayedExecutor(latencyMs, TimeUnit.MILLISECONDS)
                    .execute(() -> out.complete(vector));
        } else {
            out.complete(vector);
        }
        return out;
    }

    private static String maskImsi(String imsi) {
        if (imsi == null || imsi.length() < 6) {
            return "***";
        }
        return imsi.substring(0, 3) + "****" + imsi.substring(imsi.length() - 2);
    }
}
