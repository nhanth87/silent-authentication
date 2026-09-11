/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import et.restlink.sas.api.VerifyResource;
import et.restlink.sas.api.dto.VerifyRequestDto;
import et.restlink.sas.otpsms.OtpSmsResource;
import et.restlink.sas.otpsms.SendCodeRequest;
import et.restlink.sas.otpsms.ValidateCodeRequest;
import et.restlink.sas.simswap.SimSwapCheckRequest;
import et.restlink.sas.simswap.SimSwapDateRequest;
import et.restlink.sas.simswap.SimSwapResource;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamaraConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MSISDN = "+251911111111";

    @Test
    void pathsRecognizeCamaraSurfaces() {
        assertTrue(CamaraPaths.isCamaraPath("/camara"));
        assertTrue(CamaraPaths.isCamaraPath("/camara/"));
        assertTrue(CamaraPaths.isCamaraPath("camara/number-verification/v2/verify"));
        assertTrue(CamaraPaths.isCamaraPath("/number-verification/v2/verify/"));
        assertTrue(CamaraPaths.isCamaraPath("/sim-swap/v2/check"));
        assertTrue(CamaraPaths.isCamaraPath("/one-time-password-sms/v1/send-code"));

        assertFalse(CamaraPaths.isCamaraPath("/camarafoo"));
        assertFalse(CamaraPaths.isCamaraPath("/verify"));
        assertFalse(CamaraPaths.isCamaraPath("/session-tuple"));
        assertFalse(CamaraPaths.isCamaraPath(null));
    }

    @Test
    void pathsStripConfigurableApiRoot() {
        assertEquals("/", CamaraPaths.withoutApiRoot("/camara"));
        assertEquals("/number-verification/v2/verify",
                CamaraPaths.withoutApiRoot("/camara/number-verification/v2/verify"));
        assertEquals("/sim-swap/v2/check",
                CamaraPaths.withoutApiRoot("sim-swap/v2/check/"));
    }

    @Test
    void correlatorPatternMatchesCamaraSchema() {
        assertTrue(CamaraCorrelatorRequestFilter.isValid(null));
        assertTrue(CamaraCorrelatorRequestFilter.isValid(""));
        assertTrue(CamaraCorrelatorRequestFilter.isValid(
                "b4333c46-49c0-4f62-80d7-f0ef930f1c46"));
        assertTrue(CamaraCorrelatorRequestFilter.isValid("abcDEF019-_:;./<>{}"));
        assertTrue(CamaraCorrelatorRequestFilter.isValid("a".repeat(256)));

        assertFalse(CamaraCorrelatorRequestFilter.isValid("bad value"));
        assertFalse(CamaraCorrelatorRequestFilter.isValid("bad,value"));
        assertFalse(CamaraCorrelatorRequestFilter.isValid("a".repeat(257)));
    }

    @Test
    void requestFilterRejectsInvalidCorrelatorOnCamaraPaths() {
        AtomicReference<Response> aborted = new AtomicReference<>();
        ContainerRequestContext context = requestContext(
                "/camara/number-verification/v2/verify", "bad value", aborted);

        new CamaraCorrelatorRequestFilter().filter(context);

        Response response = aborted.get();
        assertNotNull(response);
        assertEquals(400, response.getStatus());
        CamaraErrorInfo error = (CamaraErrorInfo) response.getEntity();
        assertEquals(400, error.status());
        assertEquals("INVALID_ARGUMENT", error.code());
        assertFalse(error.message().contains("bad value"));
    }

    @Test
    void requestFilterAllowsValidCorrelatorAndNonCamaraPaths() {
        AtomicReference<Response> validAbort = new AtomicReference<>();
        new CamaraCorrelatorRequestFilter().filter(requestContext(
                "/camara/number-verification/v2/verify",
                "b4333c46-49c0-4f62-80d7-f0ef930f1c46", validAbort));
        assertNull(validAbort.get());

        AtomicReference<Response> legacyAbort = new AtomicReference<>();
        new CamaraCorrelatorRequestFilter().filter(requestContext(
                "/admin/status", "bad value", legacyAbort));
        assertNull(legacyAbort.get());
    }

    @Test
    void responseFilterEchoesOnlyValidCorrelator() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle(CamaraHeaders.CORRELATOR, "");
        new CamaraCorrelatorResponseFilter().filter(
                requestContext("/camara/sim-swap/v2/check", "corr-1", new AtomicReference<>()),
                responseContext(headers));
        assertEquals("corr-1", headers.getFirst(CamaraHeaders.CORRELATOR));

        MultivaluedMap<String, Object> blankHeaders = new MultivaluedHashMap<>();
        blankHeaders.putSingle(CamaraHeaders.CORRELATOR, "");
        new CamaraCorrelatorResponseFilter().filter(
                requestContext("/sim-swap/v2/check", "", new AtomicReference<>()),
                responseContext(blankHeaders));
        assertFalse(blankHeaders.containsKey(CamaraHeaders.CORRELATOR));

        MultivaluedMap<String, Object> untouched = new MultivaluedHashMap<>();
        untouched.putSingle(CamaraHeaders.CORRELATOR, "legacy");
        new CamaraCorrelatorResponseFilter().filter(
                requestContext("/verify", "corr-2", new AtomicReference<>()),
                responseContext(untouched));
        assertEquals("legacy", untouched.getFirst(CamaraHeaders.CORRELATOR));
    }

    @Test
    void conformanceExceptionMapperReturnsCamaraErrorInfo() {
        Response response = new CamaraConformanceExceptionMapper().toResponse(
                CamaraConformanceException.invalidArgument("strict body"));
        assertEquals(400, response.getStatus());
        CamaraErrorInfo error = (CamaraErrorInfo) response.getEntity();
        assertEquals(400, error.status());
        assertEquals("INVALID_ARGUMENT", error.code());
        assertEquals("strict body", error.message());
    }

    @Test
    void verifyRequestRejectsUnknownProperties() {
        assertUnknownPropertyRejected(VerifyRequestDto.class,
                "{\"phoneNumber\":\"" + MSISDN + "\",\"unknown\":\"x\"}");
    }

    @Test
    void simSwapRequestsRejectUnknownProperties() {
        assertUnknownPropertyRejected(SimSwapCheckRequest.class,
                "{\"phoneNumber\":\"" + MSISDN + "\",\"unknown\":1}");
        assertUnknownPropertyRejected(SimSwapDateRequest.class,
                "{\"phoneNumber\":\"" + MSISDN + "\",\"unknown\":1}");
    }

    @Test
    void otpRequestsRejectUnknownProperties() {
        assertUnknownPropertyRejected(SendCodeRequest.class,
                "{\"phoneNumber\":\"" + MSISDN + "\",\"message\":\"{{code}}\",\"unknown\":1}");
        assertUnknownPropertyRejected(ValidateCodeRequest.class,
                "{\"authenticationId\":\"id\",\"code\":\"123456\",\"unknown\":1}");
    }

    @Test
    void camaraAliasRoutesAreAnnotated() throws Exception {
        assertEquals("/camara/number-verification/v2",
                pathOf(CamaraNumberVerificationResource.class));
        assertEquals("/verify", pathOf(CamaraNumberVerificationResource.class
                .getMethod("verify", VerifyRequestDto.class, String.class, String.class,
                        String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class)));
        assertEquals("/device-phone-number", pathOf(CamaraNumberVerificationResource.class
                .getMethod("devicePhoneNumber", String.class, String.class, String.class,
                        String.class, String.class, String.class)));

        assertEquals("/camara/sim-swap/v2", pathOf(CamaraSimSwapResource.class));
        assertEquals("/check", pathOf(CamaraSimSwapResource.class.getMethod("check",
                SimSwapCheckRequest.class, String.class, String.class)));
        assertEquals("/retrieve-date", pathOf(CamaraSimSwapResource.class.getMethod("retrieveDate",
                SimSwapDateRequest.class, String.class, String.class)));

        assertEquals("/camara/one-time-password-sms/v1", pathOf(CamaraOtpSmsResource.class));
        assertEquals("/send-code", pathOf(CamaraOtpSmsResource.class.getMethod("sendCode",
                SendCodeRequest.class, String.class, String.class)));
        assertEquals("/validate-code", pathOf(CamaraOtpSmsResource.class.getMethod("validateCode",
                ValidateCodeRequest.class, String.class, String.class)));

        assertEquals("/camara/health", pathOf(CamaraHealthResource.class));
    }

    @Test
    void numberVerificationAliasDelegates() {
        AtomicReference<String> called = new AtomicReference<>();
        VerifyResource delegate = new VerifyResource() {
            @Override
            public Response verifyV2(VerifyRequestDto body, String xCorrelator, String authorization,
                                     String amr, String srcIpHeader, String srcPortHeader,
                                     String accessTechHeader, String operatorTokenHeader,
                                     String riskClassHeader, String assuranceDetailHeader) {
                called.set("verify:" + body.phoneNumber() + ":" + xCorrelator);
                return Response.ok(Map.of("devicePhoneNumberVerified", true)).build();
            }

            @Override
            public Response devicePhoneNumber(String xCorrelator, String authorization, String amr,
                                              String srcIpHeader, String srcPortHeader,
                                              String accessTechHeader) {
                called.set("share:" + xCorrelator);
                return Response.ok(Map.of("devicePhoneNumber", MSISDN)).build();
            }
        };

        CamaraNumberVerificationResource alias = new CamaraNumberVerificationResource(delegate);
        Response verify = alias.verify(new VerifyRequestDto(MSISDN, null), "corr-verify",
                "Bearer token", "mobile", null, null, null, null, null, null);
        assertEquals(200, verify.getStatus());
        assertEquals("verify:" + MSISDN + ":corr-verify", called.get());

        Response share = alias.devicePhoneNumber("corr-share", "Bearer token", "mobile",
                null, null, null);
        assertEquals(200, share.getStatus());
        assertEquals("share:corr-share", called.get());
    }

    @Test
    void simSwapAliasDelegates() {
        AtomicReference<String> called = new AtomicReference<>();
        SimSwapResource delegate = new SimSwapResource() {
            @Override
            public Response check(SimSwapCheckRequest body, String xCorrelator, String authorization) {
                called.set("check:" + body.phoneNumber() + ":" + xCorrelator);
                return Response.ok(Map.of("swapped", false)).build();
            }

            @Override
            public Response retrieveDate(SimSwapDateRequest body, String xCorrelator,
                                         String authorization) {
                called.set("retrieve:" + body.phoneNumber() + ":" + xCorrelator);
                return Response.ok(Map.of("latestSimChange", "2026-01-01T00:00:00Z")).build();
            }
        };

        CamaraSimSwapResource alias = new CamaraSimSwapResource(delegate);
        assertEquals(200, alias.check(new SimSwapCheckRequest(MSISDN, null), "corr-1",
                "Bearer token").getStatus());
        assertEquals("check:" + MSISDN + ":corr-1", called.get());

        assertEquals(200, alias.retrieveDate(new SimSwapDateRequest(MSISDN), "corr-2",
                "Bearer token").getStatus());
        assertEquals("retrieve:" + MSISDN + ":corr-2", called.get());
    }

    @Test
    void otpSmsAliasDelegates() {
        AtomicReference<String> called = new AtomicReference<>();
        OtpSmsResource delegate = new OtpSmsResource() {
            @Override
            public Response sendCode(SendCodeRequest body, String xCorrelator, String authorization) {
                called.set("send:" + body.phoneNumber() + ":" + xCorrelator);
                return Response.ok(Map.of("authenticationId", "auth-id")).build();
            }

            @Override
            public Response validateCode(ValidateCodeRequest body, String xCorrelator,
                                         String authorization) {
                called.set("validate:" + body.authenticationId() + ":" + xCorrelator);
                return Response.noContent().build();
            }
        };

        CamaraOtpSmsResource alias = new CamaraOtpSmsResource(delegate);
        assertEquals(200, alias.sendCode(new SendCodeRequest(MSISDN, "{{code}}"), "corr-1",
                "Bearer token").getStatus());
        assertEquals("send:" + MSISDN + ":corr-1", called.get());

        assertEquals(204, alias.validateCode(new ValidateCodeRequest("auth-id", "123456"),
                "corr-2", "Bearer token").getStatus());
        assertEquals("validate:auth-id:corr-2", called.get());
    }

    @Test
    void healthAliasReportsUp() {
        Response response = new CamaraHealthResource().health();
        assertEquals(200, response.getStatus());
        assertEquals("UP", ((CamaraHealthResource.CamaraHealthStatus) response.getEntity()).status());
    }

    private void assertUnknownPropertyRejected(Class<?> type, String json) {
        UnrecognizedPropertyException exception = assertThrows(UnrecognizedPropertyException.class,
                () -> MAPPER.readValue(json, type));
        Response response = new CamaraUnrecognizedPropertyExceptionMapper().toResponse(exception);
        assertEquals(400, response.getStatus());
        CamaraErrorInfo error = (CamaraErrorInfo) response.getEntity();
        assertEquals(400, error.status());
        assertEquals("INVALID_ARGUMENT", error.code());
        assertTrue(error.message().startsWith("unknown request property:"));
    }

    private static String pathOf(Class<?> type) {
        Path annotation = type.getAnnotation(Path.class);
        assertNotNull(annotation);
        return annotation.value();
    }

    private static String pathOf(Method method) {
        Path annotation = method.getAnnotation(Path.class);
        assertNotNull(annotation);
        return annotation.value();
    }

    private static ContainerRequestContext requestContext(String path,
                                                          String correlator,
                                                          AtomicReference<Response> aborted) {
        UriInfo uriInfo = proxy(UriInfo.class, (proxy, method, args) -> {
            if ("getPath".equals(method.getName())) {
                return path;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return proxy(ContainerRequestContext.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUriInfo":
                    return uriInfo;
                case "getHeaderString":
                    return CamaraHeaders.CORRELATOR.equals(args[0]) ? correlator : null;
                case "abortWith":
                    aborted.set((Response) args[0]);
                    return null;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    private static ContainerResponseContext responseContext(MultivaluedMap<String, Object> headers) {
        return proxy(ContainerResponseContext.class, (proxy, method, args) -> {
            if ("getHeaders".equals(method.getName())) {
                return headers;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString":
                            return type.getSimpleName() + "Proxy";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return handler.invoke(proxy, method, args);
                    }
                });
    }
}
