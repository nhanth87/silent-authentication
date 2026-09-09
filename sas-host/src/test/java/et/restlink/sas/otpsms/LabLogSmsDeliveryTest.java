/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lab delivery seam: {@code sas.otp.sms-delivery=log} logs and "delivers";
 * anything else — including a real adapter name that is not wired yet — fails
 * closed so {@code /send-code} never issues an {@code authenticationId} for an
 * SMS that did not leave the SAS.
 */
class LabLogSmsDeliveryTest {

    private static final String MSISDN = "+251911111111";
    private static final String TEXT = "123456 is your Restlink code";

    @Test
    void logMode_acceptsAndStatesThatNothingWasSent() {
        LabLogSmsDelivery delivery = new LabLogSmsDelivery();
        delivery.config = configWithDelivery("log");

        SmsDeliveryPort.DeliveryResult result = delivery.deliver(MSISDN, TEXT);

        assertEquals(SmsDeliveryPort.Outcome.DELIVERED, result.outcome());
        assertTrue(result.delivered());
        assertTrue(result.detail().contains("lab"), result.detail());
    }

    @Test
    void logMode_isCaseInsensitive() {
        LabLogSmsDelivery delivery = new LabLogSmsDelivery();
        delivery.config = configWithDelivery("LOG");
        assertTrue(delivery.deliver(MSISDN, TEXT).delivered());
    }

    @Test
    void unwiredAdapterName_failsClosed() {
        LabLogSmsDelivery delivery = new LabLogSmsDelivery();
        delivery.config = configWithDelivery("sgd");

        SmsDeliveryPort.DeliveryResult result = delivery.deliver(MSISDN, TEXT);

        assertEquals(SmsDeliveryPort.Outcome.UNAVAILABLE, result.outcome());
        assertFalse(result.delivered());
        assertTrue(result.detail().contains("sgd"), result.detail());
    }

    @Test
    void unsetOrMissingConfig_failsClosed() {
        LabLogSmsDelivery unset = new LabLogSmsDelivery();
        unset.config = configWithDelivery(null);
        assertEquals(SmsDeliveryPort.Outcome.UNAVAILABLE, unset.deliver(MSISDN, TEXT).outcome());

        LabLogSmsDelivery noConfig = new LabLogSmsDelivery();
        assertEquals(SmsDeliveryPort.Outcome.UNAVAILABLE, noConfig.deliver(MSISDN, TEXT).outcome());
    }

    private static OtpConfig configWithDelivery(String deliveryMode) {
        OtpConfig config = new OtpConfig();
        try {
            Field field = OtpConfig.class.getDeclaredField("smsDeliveryRaw");
            field.setAccessible(true);
            field.set(config, Optional.ofNullable(deliveryMode));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return config;
    }
}
