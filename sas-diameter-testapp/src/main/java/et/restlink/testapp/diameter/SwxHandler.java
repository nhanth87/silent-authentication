/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp.diameter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.app.swx.AvpFactory;
import com.mobius.software.telco.protocols.diameter.app.swx.MessageFactory;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.PushProfileAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.PushProfileRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.SwxRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.ServerAssignmentAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.ServerAssignmentRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.impl.primitives.creditcontrol.SubscriptionIdImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.swx.Non3GPPUserDataImpl;
import com.mobius.software.telco.protocols.diameter.primitives.cxdx.SIPAuthDataItem;
import com.mobius.software.telco.protocols.diameter.primitives.creditcontrol.SubscriptionIdTypeEnum;
import com.mobius.software.telco.protocols.diameter.primitives.swx.Non3GPPUserData;

import io.netty.buffer.Unpooled;

import et.restlink.testapp.HssSimulator;
import et.restlink.testapp.MessageLog;
import et.restlink.testapp.SubscriberState;

/**
 * SWx server side (TS 29.273): MAR to MAA (§6.2.2), SAR to SAA (§6.3.2),
 * PPR to PPA (§6.6.2).
 *
 * <p>Answer policy mirrors {@link S6aHandler} (errors ride the base
 * Result-Code; corsac forbids Experimental-Result on these answers): unknown
 * user 5001, detached UE 5421 (DIAMETER_ERROR_USER_NO_NON_3GPP_SUBSCRIPTION
 * shares the 5421 value), zero vectors = success MAA with no
 * SIP-Auth-Data-Item (SAS fails closed on the empty set), otherwise fabricated
 * EAP-AKA items honouring the same vector-count state as S6a. A non-empty MAA
 * also stamps the subscriber's lastEapAuthSuccess timestamp.</p>
 */
final class SwxHandler implements com.mobius.software.telco.protocols.diameter.app.swx.ServerListener {

    private static final Logger LOG = LogManager.getLogger(SwxHandler.class);

    static final String EAP_AKA_SCHEME = "EAP-AKA";
    static final String AAA_SERVER_NAME = "aaa.restlink.et";

    private final HssSimulator hss;
    private final MessageFactory messages;
    private final AvpFactory avps;

    SwxHandler(HssSimulator hss, MessageFactory messages, AvpFactory avps) {
        this.hss = hss;
        this.messages = messages;
        this.avps = avps;
    }

    @Override
    public void onInitialRequest(SwxRequest request,
            ServerAuthSessionStateless<SwxAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            SwxAnswer answer = build(request);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("SWx handler failure on {} — fail-safe {} answer",
                    request.getClass().getSimpleName(), Answers.UNABLE_TO_DELIVER, e);
            try {
                session.sendInitialAnswer(unableToDeliver(request), callback);
            } catch (Exception fatal) {
                LOG.error("SWx fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private SwxAnswer build(SwxRequest request) throws Exception {
        if (request instanceof MultimediaAuthRequest mar) {
            return onMar(mar);
        }
        if (request instanceof ServerAssignmentRequest sar) {
            return onSar(sar);
        }
        if (request instanceof PushProfileRequest ppr) {
            return onPpr(ppr);
        }
        throw new IllegalStateException("unsupported SWx command " + request.getClass().getName());
    }

    private MultimediaAuthAnswer onMar(MultimediaAuthRequest mar) throws Exception {
        String username = Answers.usernameOf(mar);
        Answers.received(hss, "MAR", mar, "user=" + username + " scheme-request=EAP-AKA");

        SubscriberState state = hss.find(username).orElse(null);
        long resultCode = state == null ? Answers.ER_USER_UNKNOWN
                : !state.attached() ? Answers.ER_NO_SUBSCRIPTION : Answers.SUCCESS;
        MultimediaAuthAnswer maa = messages.createMultimediaAuthAnswer(mar,
                mar.getHopByHopIdentifier(), mar.getEndToEndIdentifier(),
                resultCode, username);

        if (resultCode != Answers.SUCCESS) {
            sent("MAA", mar, Long.toString(resultCode),
                    state == null ? "user unknown" : "user detached");
            return maa;
        }

        int count = state.authVectorsAvailable();
        if (count > 0) {
            maa.setSIPAuthDataItem(mintItems(count));
            state.markEapAuthSuccess(System.currentTimeMillis());
        }
        sent("MAA", mar, Long.toString(Answers.SUCCESS), "items=" + count);
        return maa;
    }

    /** Fabricated EAP-AKA auth items: random authenticate/authorization pairs. */
    private List<SIPAuthDataItem> mintItems(int count) throws DiameterException {
        List<SIPAuthDataItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SIPAuthDataItem item = avps.getSIPAuthDataItem();
            item.setSIPAuthenticationScheme(EAP_AKA_SCHEME);
            item.setSIPAuthenticate(Unpooled.wrappedBuffer(Answers.randomBytes(32)));
            item.setSIPAuthorization(Unpooled.wrappedBuffer(Answers.randomBytes(32)));
            item.setSIPItemNumber((long) (i + 1));
            items.add(item);
        }
        return items;
    }

    private ServerAssignmentAnswer onSar(ServerAssignmentRequest sar) throws Exception {
        String username = Answers.usernameOf(sar);
        String assignmentType = sar.getServerAssignmentType() == null
                ? "-" : sar.getServerAssignmentType().toString();
        Answers.received(hss, "SAR", sar,
                "user=" + username + " type=" + assignmentType);

        SubscriberState state = hss.find(username).orElse(null);
        long resultCode = state == null ? Answers.ER_USER_UNKNOWN
                : !state.attached() ? Answers.ER_NO_SUBSCRIPTION : Answers.SUCCESS;
        ServerAssignmentAnswer saa = messages.createServerAssignmentAnswer(sar,
                sar.getHopByHopIdentifier(), sar.getEndToEndIdentifier(),
                resultCode, username);

        if (resultCode != Answers.SUCCESS) {
            sent("SAA", sar, Long.toString(resultCode),
                    state == null ? "user unknown" : "user detached");
            return saa;
        }

        Non3GPPUserData userData = new Non3GPPUserDataImpl();
        userData.setSubscriptionId(new SubscriptionIdImpl(
                SubscriptionIdTypeEnum.END_USER_E164, state.msisdn()));
        saa.setNon3GPPUserData(userData);
        saa.set3GPPAAAServerName(AAA_SERVER_NAME);
        sent("SAA", sar, Long.toString(Answers.SUCCESS), "ack + non-3gpp-user-data");
        return saa;
    }

    private PushProfileAnswer onPpr(PushProfileRequest ppr) throws Exception {
        String username = Answers.usernameOf(ppr);
        Answers.received(hss, "PPR", ppr, "user=" + username);

        SubscriberState state = hss.find(username).orElse(null);
        long resultCode = state == null ? Answers.ER_USER_UNKNOWN : Answers.SUCCESS;
        PushProfileAnswer ppa = messages.createPushProfileAnswer(ppr,
                ppr.getHopByHopIdentifier(), ppr.getEndToEndIdentifier(), resultCode);
        sent("PPA", ppr, Long.toString(resultCode), state == null ? "user unknown" : "ack");
        return ppa;
    }

    private SwxAnswer unableToDeliver(SwxRequest request) throws Exception {
        if (request instanceof MultimediaAuthRequest mar) {
            return messages.createMultimediaAuthAnswer(mar, mar.getHopByHopIdentifier(),
                    mar.getEndToEndIdentifier(), Answers.UNABLE_TO_DELIVER,
                    Answers.usernameOf(mar));
        }
        if (request instanceof ServerAssignmentRequest sar) {
            return messages.createServerAssignmentAnswer(sar, sar.getHopByHopIdentifier(),
                    sar.getEndToEndIdentifier(), Answers.UNABLE_TO_DELIVER,
                    Answers.usernameOf(sar));
        }
        if (request instanceof PushProfileRequest ppr) {
            return messages.createPushProfileAnswer(ppr, ppr.getHopByHopIdentifier(),
                    ppr.getEndToEndIdentifier(), Answers.UNABLE_TO_DELIVER);
        }
        throw new IllegalStateException("no factory for " + request.getClass().getName());
    }

    private void sent(String command, DiameterRequest request, String result, String details) {
        Answers.log(hss).add(new MessageLog.Entry(Instant.now(), "ans", command,
                Answers.sessionId(request), result, details));
    }

    @Override
    public void onTimeout(DiameterRequest request,
            com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        LOG.warn("SWx server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        // stateless — nothing to clean up
    }
}
