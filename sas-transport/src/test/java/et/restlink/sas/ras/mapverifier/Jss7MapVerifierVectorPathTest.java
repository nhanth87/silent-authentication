/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.ras.authvector.AuthVector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobility;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entitlement (vector) path of the MAP backend: a MAP error ends the request at once,
 * and every ending leaves no pending entry and no live dialog behind.
 */
class Jss7MapVerifierVectorPathTest {

    private static final long DIALOG_ID = 42L;

    private Jss7MapVerifierBackend backend;
    private MAPDialogMobility dialog;
    private final AtomicInteger aborts = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        dialog = proxy(MAPDialogMobility.class, (m, args) -> switch (m) {
            case "getLocalDialogId" -> DIALOG_ID;
            case "abort" -> {
                aborts.incrementAndGet();
                yield null;
            }
            default -> null;
        });
        MAPServiceMobility service = proxy(MAPServiceMobility.class,
                (m, args) -> "createNewDialog".equals(m) ? dialog : null);
        MAPParameterFactory factory = proxy(MAPParameterFactory.class, (m, args) -> null);
        MAPProvider provider = proxy(MAPProvider.class, (m, args) -> switch (m) {
            case "getMAPServiceMobility" -> service;
            case "getMAPParameterFactory" -> factory;
            default -> null;
        });
        backend = new Jss7MapVerifierBackend(Path.of("unused.json"), "251900000001", "251900000002");
        set("mapProvider", provider);
        set("started", true);
    }

    @Test
    void returnErrorEndsTheVectorRequestImmediately() {
        CompletableFuture<AuthVector> out = backend.fetch("655010000000001", AuthVector.EAP_AKA);
        long t0 = System.nanoTime();
        backend.onErrorComponent(dialog, 1L, null);
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> out.get(200, TimeUnit.MILLISECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < SasTimeouts.MAP_MS / 2,
                "must not wait for the MAP budget");
        assertTrue(pending().isEmpty(), "no pending entry after returnError");
        assertEquals(0, aborts.get(), "the HLR already ended the dialog; nothing to abort");
    }

    @Test
    void providerAbortAlsoEndsTheVectorRequest() {
        CompletableFuture<AuthVector> out = backend.fetch("655010000000001", AuthVector.EAP_AKA);
        backend.onDialogProviderAbort(dialog, null, null, null);
        assertTrue(out.isCompletedExceptionally());
        assertTrue(pending().isEmpty());
    }

    @Test
    void budgetExpiryAbortsTheDialogAndClearsTheEntry() {
        CompletableFuture<AuthVector> out = backend.fetch("655010000000001", AuthVector.EAP_AKA);
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> out.get(SasTimeouts.MAP_MS + 2_000L, TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, e.getCause());
        assertTrue(pending().isEmpty(), "timed-out entry must not leak");
        assertEquals(1, aborts.get(), "timed-out dialog is aborted (no TCAP leak)");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ?> pending() {
        try {
            Field f = Jss7MapVerifierBackend.class.getDeclaredField("vectorPending");
            f.setAccessible(true);
            return (Map<Long, ?>) f.get(backend);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void set(String name, Object value) throws ReflectiveOperationException {
        Field f = Jss7MapVerifierBackend.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(backend, value);
    }

    private interface Answer {
        Object answer(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, m, args) -> switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    case "toString" -> type.getSimpleName() + "-stub";
                    default -> answer.answer(m.getName(), args);
                });
    }
}
