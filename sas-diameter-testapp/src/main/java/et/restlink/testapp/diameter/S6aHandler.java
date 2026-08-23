/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp.diameter;

import java.time.Instant;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.app.s6a.AvpFactory;
import com.mobius.software.telco.protocols.diameter.app.s6a.MessageFactory;
import com.mobius.software.telco.protocols.diameter.app.s6a.ServerListener;
import com.mobius.software.telco.protocols.diameter.commands.s6a.AuthenticationInformationAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.AuthenticationInformationRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.InsertSubscriberDataAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.InsertSubscriberDataRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.primitives.gx.RATTypeEnum;
import com.mobius.software.telco.protocols.diameter.primitives.s6a.EUTRANVector;
import com.mobius.software.telco.protocols.diameter.primitives.s6a.SubscriberStatusEnum;
import com.mobius.software.telco.protocols.diameter.primitives.s6a.SubscriptionData;

import io.netty.buffer.Unpooled;

import et.restlink.testapp.HssSimulator;
import et.restlink.testapp.MessageLog;
import et.restlink.testapp.SubscriberState;

/**
 * S6a server side (TS 29.272): ULR to ULA (316), AIR to AIA (318),
 * IDR to IDA (319).
 *
 * <p>Answer policy (the SAS maps every branch below to pass or fail-closed).
 * Error values ride the base Result-Code — corsac marks Experimental-Result
 * disallowed on its answer implementations:</p>
 * <ul>
 *   <li>unknown Username: Result-Code 5001 (DIAMETER_ERROR_USER_UNKNOWN,
 *       TS 29.272 §7.2.6);</li>
 *   <li>detached UE: Result-Code 5421
 *       (DIAMETER_ERROR_UNKNOWN_EPS_SUBSCRIPTION);</li>
 *   <li>barred: success ULA carrying Subscription-Data with Subscriber-Status
 *       OPERATOR_DETERMINED_BARRING (TS 29.272 §7.3.30);</li>
 *   <li>authVectorsAvailable == 0: success AIA with no Authentication-Info —
 *       the SAS fails closed on the empty vector set; otherwise exactly
 *       min(requested, available) fabricated E-UTRAN vectors.</li>
 * </ul>
 */
final class S6aHandler implements ServerListener {

    private static final Logger LOG = LogManager.getLogger(S6aHandler.class);

    private final HssSimulator hss;
    private final MessageFactory messages;
    private final AvpFactory avps;

    S6aHandler(HssSimulator hss, MessageFactory messages, AvpFactory avps) {
        this.hss = hss;
        this.messages = messages;
        this.avps = avps;
    }

    @Override
    public void onInitialRequest(S6aRequest request,
            ServerAuthSessionStateless<S6aAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            S6aAnswer answer = build(request);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("S6a handler failure on {} — fail-safe {} answer",
                    request.getClass().getSimpleName(), Answers.UNABLE_TO_DELIVER, e);
            try {
                session.sendInitialAnswer(unableToDeliver(request), callback);
            } catch (Exception fatal) {
                LOG.error("S6a fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private S6aAnswer build(S6aRequest request) throws Exception {
        if (request instanceof UpdateLocationRequest ulr) {
            return onUlr(ulr);
        }
        if (request instanceof AuthenticationInformationRequest air) {
            return onAir(air);
        }
        if (request instanceof InsertSubscriberDataRequest idr) {
            return onIdr(idr);
        }
        throw new IllegalStateException("unsupported S6a command " + request.getClass().getName());
    }

    private UpdateLocationAnswer onUlr(UpdateLocationRequest ulr) throws Exception {
        String username = Answers.usernameOf(ulr);
        RATTypeEnum rat = ulr.getRATType();
        Answers.received(hss, "ULR", ulr, "user=" + username + " rat=" + rat);

        SubscriberState state = hss.find(username).orElse(null);
        long resultCode = state == null ? Answers.ER_USER_UNKNOWN
                : !state.attached() ? Answers.ER_NO_SUBSCRIPTION : Answers.SUCCESS;
        UpdateLocationAnswer ula = messages.createUpdateLocationAnswer(ulr,
                ulr.getHopByHopIdentifier(), ulr.getEndToEndIdentifier(), resultCode);

        if (resultCode != Answers.SUCCESS) {
            sent("ULA", ulr, Long.toString(resultCode),
                    state == null ? "user unknown" : "user detached");
            return ula;
        }
        SubscriptionData subscriptionData =
                new com.mobius.software.telco.protocols.diameter.impl.primitives.s6a.SubscriptionDataImpl();
        // E164 AVP wire form is TBCD digits — no leading '+'.
        subscriptionData.setMSISDN(state.msisdn().replaceAll("\\D", ""));
        subscriptionData.setSubscriberStatus(state.barred()
                ? SubscriberStatusEnum.OPERATOR_DETERMINED_BARRING
                : SubscriberStatusEnum.SERVICE_GRANTED);
        ula.setSubscriptionData(subscriptionData);
        sent("ULA", ulr, Long.toString(Answers.SUCCESS),
                state.barred() ? "subscriber barred" : "ok");
        return ula;
    }

    private AuthenticationInformationAnswer onAir(AuthenticationInformationRequest air)
            throws Exception {
        String username = Answers.usernameOf(air);
        int requested = requestedVectors(air);
        Answers.received(hss, "AIR", air, "user=" + username + " requested=" + requested);

        SubscriberState state = hss.find(username).orElse(null);
        long resultCode = state == null ? Answers.ER_USER_UNKNOWN
                : !state.attached() ? Answers.ER_NO_SUBSCRIPTION : Answers.SUCCESS;
        AuthenticationInformationAnswer aia = messages.createAuthenticationInformationAnswer(
                air, air.getHopByHopIdentifier(), air.getEndToEndIdentifier(), resultCode);

        if (resultCode != Answers.SUCCESS) {
            sent("AIA", air, Long.toString(resultCode),
                    state == null ? "user unknown" : "user detached");
            return aia;
        }

        int count = Math.min(requested, state.authVectorsAvailable());
        if (count > 0) {
            var authInfo = avps.getAuthenticationInfo();
            authInfo.setEUTRANVector(mintVectors(count));
            aia.setAuthenticationInfo(authInfo);
        }
        sent("AIA", air, Long.toString(Answers.SUCCESS), "vectors=" + count);
        return aia;
    }

    private InsertSubscriberDataAnswer onIdr(InsertSubscriberDataRequest idr) throws Exception {
        String username = Answers.usernameOf(idr);
        Answers.received(hss, "IDR", idr, "user=" + username);

        SubscriberState state = hss.find(username).orElse(null);
        long resultCode = state == null ? Answers.ER_USER_UNKNOWN : Answers.SUCCESS;
        InsertSubscriberDataAnswer ida = messages.createInsertSubscriberDataAnswer(idr,
                idr.getHopByHopIdentifier(), idr.getEndToEndIdentifier(), resultCode);
        sent("IDA", idr, Long.toString(resultCode), state == null ? "user unknown" : "ack");
        return ida;
    }

    /** Requested-E-UTRAN-Authentication-Info vector count, default 1 per spec. */
    private static int requestedVectors(AuthenticationInformationRequest air) {
        var requested = air.getRequestedEUTRANAuthenticationInfo();
        if (requested == null || requested.getNumberOfRequestedVectors() == null) {
            return 1;
        }
        long value = requested.getNumberOfRequestedVectors();
        return value <= 0 ? 1 : (int) Math.min(value, 10);
    }

    private List<EUTRANVector> mintVectors(int count) throws DiameterException {
        EUTRANVector[] vectors = new EUTRANVector[count];
        for (int i = 0; i < count; i++) {
            vectors[i] = avps.getEUTRANVector(
                    Unpooled.wrappedBuffer(Answers.randomBytes(16)),
                    Unpooled.wrappedBuffer(Answers.randomBytes(16)),
                    Unpooled.wrappedBuffer(Answers.randomBytes(16)),
                    Unpooled.wrappedBuffer(Answers.randomBytes(32)));
            vectors[i].setItemNumber((long) (i + 1));
        }
        return List.of(vectors);
    }

    private S6aAnswer unableToDeliver(S6aRequest request) throws Exception {
        if (request instanceof UpdateLocationRequest ulr) {
            return messages.createUpdateLocationAnswer(ulr, ulr.getHopByHopIdentifier(),
                    ulr.getEndToEndIdentifier(), Answers.UNABLE_TO_DELIVER);
        }
        if (request instanceof AuthenticationInformationRequest air) {
            return messages.createAuthenticationInformationAnswer(air,
                    air.getHopByHopIdentifier(), air.getEndToEndIdentifier(),
                    Answers.UNABLE_TO_DELIVER);
        }
        if (request instanceof InsertSubscriberDataRequest idr) {
            return messages.createInsertSubscriberDataAnswer(idr,
                    idr.getHopByHopIdentifier(), idr.getEndToEndIdentifier(),
                    Answers.UNABLE_TO_DELIVER);
        }
        throw new IllegalStateException("no factory for " + request.getClass().getName());
    }

    private void sent(String command, S6aRequest request, String result, String details) {
        Answers.log(hss).add(new MessageLog.Entry(Instant.now(), "ans", command,
                Answers.sessionId(request), result, details));
    }

    @Override
    public void onTimeout(com.mobius.software.telco.protocols.diameter.commands.DiameterRequest request,
            com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        LOG.warn("S6a server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        // stateless — nothing to clean up
    }
}
