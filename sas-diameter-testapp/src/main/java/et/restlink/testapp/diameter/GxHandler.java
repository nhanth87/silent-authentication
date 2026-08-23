/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp.diameter;

import java.net.InetAddress;
import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AvpCodes;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerCCSession;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.gx.AbortSessionAnswer;
import com.mobius.software.telco.protocols.diameter.commands.gx.AbortSessionRequest;
import com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlAnswer;
import com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlRequest;
import com.mobius.software.telco.protocols.diameter.commands.gx.ReAuthAnswer;
import com.mobius.software.telco.protocols.diameter.commands.gx.ReAuthRequest;
import com.mobius.software.telco.protocols.diameter.commands.gx.SessionTerminationAnswer;
import com.mobius.software.telco.protocols.diameter.commands.gx.SessionTerminationRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterAvpKey;
import com.mobius.software.telco.protocols.diameter.primitives.creditcontrol.SubscriptionIdTypeEnum;

import io.netty.buffer.Unpooled;

import et.restlink.testapp.BindingRegistry;
import et.restlink.testapp.HssSimulator;
import et.restlink.testapp.MessageLog;

/**
 * Gx server side (TS 29.212): CCR-I to CCA binding lookup for the SAS
 * resolver. The request's Framed-IP-Address keys into the
 * {@link BindingRegistry}; a hit answers 2001 + Subscription-Id, a miss
 * answers {@code 5030 DIAMETER_USER_UNKNOWN} (RFC 4006 §8.4, referenced by
 * TS 29.212 for CCAs) — both fail-closed on the SAS side by design.
 *
 * <p>Lab deviation: the Subscription-Id AVP rides the CCA as an unknown AVP
 * (code 443, mandatory bit clear) because corsac does not model it on the Gx
 * answer; the SAS reads it back through its optional-AVP channel.</p>
 */
final class GxHandler implements com.mobius.software.telco.protocols.diameter.app.gx.ServerListener {

    private static final Logger LOG = LogManager.getLogger(GxHandler.class);

    /** RFC 4006 §8.4 DIAMETER_USER_UNKNOWN — no binding for the framed IP. */
    static final long USER_UNKNOWN = 5030L;

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.gx.MessageFactory messages;

    GxHandler(HssSimulator hss,
            com.mobius.software.telco.protocols.diameter.app.gx.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(CreditControlRequest request,
            ServerCCSession<CreditControlAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID, AsyncCallback callback) {
        try {
            session.sendInitialAnswer(build(request), callback);
        } catch (Exception e) {
            LOG.warn("Gx handler failure on {} — fail-safe {} answer",
                    request.getClass().getSimpleName(), Answers.UNABLE_TO_DELIVER, e);
            try {
                session.sendInitialAnswer(messages.createCreditControlAnswer(request,
                        request.getHopByHopIdentifier(), request.getEndToEndIdentifier(),
                        Answers.UNABLE_TO_DELIVER), callback);
            } catch (Exception fatal) {
                LOG.error("Gx fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private CreditControlAnswer build(CreditControlRequest ccr) throws Exception {
        InetAddress framed = ccr.getFramedIPAddress();
        String ip = framed == null ? null : framed.getHostAddress();
        BindingRegistry.Binding binding = ip == null ? null : hss.bindings().find(ip);
        Answers.received(hss, "CCR", ccr, "framed-ip=" + (ip == null ? "-" : ip));

        long resultCode = binding == null ? USER_UNKNOWN : Answers.SUCCESS;
        CreditControlAnswer cca = messages.createCreditControlAnswer(ccr,
                ccr.getHopByHopIdentifier(), ccr.getEndToEndIdentifier(), resultCode);

        if (binding != null) {
            attachSubscriptionId(cca, SubscriptionIdTypeEnum.END_USER_E164, binding.msisdn());
            if (binding.imsi() != null && !binding.imsi().isBlank()) {
                attachSubscriptionId(cca, SubscriptionIdTypeEnum.END_USER_IMSI, binding.imsi());
            }
        }
        sent("CCA", ccr, Long.toString(resultCode),
                binding == null ? "no binding for " + ip
                        : "bound msisdn=" + binding.msisdn() + " imsi=" + binding.imsi());
        return cca;
    }

    /**
     * Encodes { Subscription-Id-Type (450), Subscription-Id-Data (444) } and
     * attaches it as unknown AVP 443 without the mandatory bit so the client
     * parser stores it instead of rejecting.
     */
    private static void attachSubscriptionId(CreditControlAnswer answer,
            SubscriptionIdTypeEnum type, String data) {
        byte[] value = groupedValue(
                leaf(AvpCodes.SUBSCRIPTION_ID_TYPE, intBytes((int) type.getValue())),
                leaf(AvpCodes.SUBSCRIPTION_ID_DATA, data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        answer.addOptionalAvp(new DiameterAvpKey(AvpCodes.SUBSCRIPTION_ID),
                Unpooled.wrappedBuffer(value), Boolean.FALSE);
    }

    private static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    private static byte[] leaf(long avpCode, byte[] data) {
        int length = 8 + data.length;
        byte[] out = new byte[length + ((4 - (data.length % 4)) % 4)];
        out[0] = (byte) (avpCode >>> 24);
        out[1] = (byte) (avpCode >>> 16);
        out[2] = (byte) (avpCode >>> 8);
        out[3] = (byte) avpCode;
        out[4] = 0;
        out[5] = (byte) (length >>> 16);
        out[6] = (byte) (length >>> 8);
        out[7] = (byte) length;
        System.arraycopy(data, 0, out, 8, data.length);
        return out;
    }

    private static byte[] groupedValue(byte[]... leaves) {
        int total = 0;
        for (byte[] leaf : leaves) {
            total += leaf.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] leaf : leaves) {
            System.arraycopy(leaf, 0, out, offset, leaf.length);
            offset += leaf.length;
        }
        return out;
    }

    private void sent(String command, DiameterRequest request, String result, String details) {
        Answers.log(hss).add(new MessageLog.Entry(Instant.now(), "ans", command,
                Answers.sessionId(request), result, details));
    }

    @Override
    public void onReauthAnswer(ReAuthAnswer answer,
            ServerCCSession<CreditControlAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID, AsyncCallback callback) {
        // client-side event — never initiated here
    }

    @Override
    public void onSessionTerminationRequest(SessionTerminationRequest request,
            ServerCCSession<CreditControlAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID, AsyncCallback callback) {
        LOG.warn("Gx STR unexpected in lab simulator session={}", session.getID());
    }

    @Override
    public void onAbortSessionAnswer(AbortSessionAnswer answer,
            ServerCCSession<CreditControlAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID, AsyncCallback callback) {
        // client-side event — never initiated here
    }

    @Override
    public void onTimeout(DiameterRequest request,
            com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        LOG.warn("Gx server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        // stateless — nothing to clean up
    }
}
