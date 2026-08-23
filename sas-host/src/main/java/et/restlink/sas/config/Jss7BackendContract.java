/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.config;

/**
 * Documentation-only contract for de-risking the integration of
 * {@code et.restlink.sas.ras.mapverifier.Jss7MapVerifierBackend} into the
 * admin-managed SS7 pipeline.
 *
 * <p>This class intentionally contains <strong>no logic</strong>. It exists so
 * the lead knows the exact surface area {@code SasBootstrap} must call and the
 * extension point {@link Ss7AdminSupport} expects on the backend.</p>
 *
 * <h2>Constructor</h2>
 * <pre>{@code
 *   new Jss7MapVerifierBackend(
 *       java.nio.file.Path configPath,   // jSS7 stack JSON (ss7-sas.json)
 *       String hlrGt,                    // HLR global title (SCCP called party)
 *       String localGt)                  // local SAS global title (SCCP calling party)
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@code public synchronized void start()} — build the jSS7 stack via
 *       {@code Ss7StackBuilder.build(configPath)}, register the
 *       MAP service + dialog listeners, set {@code started = true}. Swallows
 *       startup failures and stays fail-closed ({@code started} remains false).</li>
 *   <li>{@code public synchronized void stop()} — complete/clear pending PSI/SAI
 *       futures, stop and null out the stack.</li>
 *   <li>{@code public boolean isStarted()} — live-state probe for the admin
 *       status endpoint.</li>
 * </ul>
 *
 * <h2>Verification</h2>
 * <pre>{@code
 *   public java.util.concurrent.CompletableFuture<et.restlink.sas.model.VerificationEvidence>
 *       verify(String msisdn, String imsi,
 *              et.restlink.sas.model.AccessTech accessTech, long nowMs)
 * }</pre>
 * <p>Drives PSI ({@code provideSubscriberInfo}) + SAI
 * ({@code sendAuthenticationInfo}) dialogs against the operator's OWN HLR/HSS
 * only. <strong>Never ATI</strong> (FS.11 Category 1).</p>
 *
 * <h2>Recommended extension for on-the-fly reload</h2>
 * <pre>{@code
 *   public synchronized void reload(java.nio.file.Path configPath,
 *                                   String hlrGt, String localGt) {
 *       stop();
 *       // update final fields via a mutable holder / rebuild instance
 *       start();
 *   }
 * }</pre>
 * <p>Because the current fields are {@code final}, the cleanest reload is to
 * reconstruct the backend with the new path/GTs and swap it in, then re-route
 * the RA backend reference. {@code Ss7AdminSupport.apply()} / {@code start()}
 * / {@code stop()} are the hooks where this swap belongs.</p>
 */
public final class Jss7BackendContract {

    /**
     * One-line reminder of the lead's wiring contract.
     */
    public static final String NOTE =
            "Wire Jss7MapVerifierBackend(Path config, String hlrGt, String localGt); "
            + "call start()/stop(); never ATI on interconnect; reload = stop() then "
            + "rebuild with a new Path/GTs and start().";

    private Jss7BackendContract() {
        // Documentation only — never instantiated.
    }
}