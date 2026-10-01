/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import et.restlink.sas.subscriber.dto.IpAddr;
import et.restlink.sas.subscriber.dto.LocationInfo;
import et.restlink.sas.subscriber.dto.MonitoringEventReport;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.PlmnId;
import et.restlink.sas.subscriber.dto.ProblemDetails;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.SubscriberVerificationResult;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layered ADR §9.1 test A, contract half: the Java contract and
 * {@code docs/design/openapi/sas-subscriber-exposure-v1.yaml} cannot drift apart.
 */
class ExposureContractTest {

    private static final Path OPENAPI =
            Path.of("..", "docs", "design", "openapi", "sas-subscriber-exposure-v1.yaml");
    private static final ObjectMapper JSON = ExposureJson.mapper();

    private static JsonNode openapi;
    private static JsonSchemaFactory factory;
    private static SchemaValidatorsConfig config;

    @BeforeAll
    static void load() throws Exception {
        openapi = new ObjectMapper(new YAMLFactory()).readTree(OPENAPI.toFile());
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    }

    /** Validate {@code instance} against {@code components.schemas.<name>}. */
    private static Set<ValidationMessage> validate(String schemaName, JsonNode instance) {
        ObjectNode root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/" + schemaName);
        root.set("components", openapi.get("components"));
        JsonSchema schema = factory.getSchema(root, config);
        return schema.validate(instance);
    }

    private static JsonNode json(Object dto) {
        return JSON.valueToTree(dto);
    }

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    // ---- every DTO, as the code serializes it, satisfies the contract -------

    static final UeIdReq UE_ID_REQ = new UeIdReq("sas-camara", new IpAddr("10.20.30.40", null),
            55555, "2026-10-01T06:00:00.000Z", null);
    static final MonitoringEventSubscription MON_REQ = new MonitoringEventSubscription(
            "251911111111", ExposureApi.UE_REACHABILITY, List.of(ExposureApi.LOCATION_REPORTING),
            ExposureApi.LAST_KNOWN_LOCATION, 1, true);

    @Test
    void everyDtoSerializesIntoItsSchema() {
        Map<String, Object> samples = Map.ofEntries(
                Map.entry("UeIdReq", UE_ID_REQ),
                Map.entry("UeIdInfo", new UeIdInfo("msisdn-251911111111",
                        "2026-10-01T05:59:58.120Z", "GX")),
                Map.entry("MonitoringEventSubscription", MON_REQ),
                Map.entry("MonitoringEventReports", new MonitoringEventReports(List.of(
                        new MonitoringEventReport(ExposureApi.UE_REACHABILITY, "251911111111",
                                "2026-10-01T06:00:00.410Z", true, null, null),
                        new MonitoringEventReport(ExposureApi.LOCATION_REPORTING, "251911111111",
                                "2026-10-01T06:00:00.410Z", null, new PlmnId("636", "01"),
                                new LocationInfo(4))), "MAP_PSI")),
                Map.entry("SimChangeReq", new SimChangeReq("sas-camara", "251911111111")),
                Map.entry("SimChangeInfo", new SimChangeInfo("251911111111", "2026-09-01T10:00:00Z",
                        "2026-10-01T06:00:00.390Z", "HLR_SIM_DB")),
                Map.entry("OperatorTokenRedeemReq", new OperatorTokenRedeemReq("sas-camara",
                        "eyJlcGsiOnsia3R5IjoiRUMiLCJjcnYi", "bank-a-backend")),
                Map.entry("SubscriberVerificationResult", new SubscriberVerificationResult(
                        "251911111111", ExposureApi.AUTH_METHOD_EAP_AKA, "2026-10-01T05:59:41Z",
                        true, "3f0c2b0e-8d1a-4b52-9a49-0f7e4a1c2d11")),
                Map.entry("ProblemDetails", ExposureCause.NO_BINDING.problem("no subscriber holds it")));
        samples.forEach((name, dto) ->
                assertEquals(Set.of(), validate(name, json(dto)), name + " drifted from the contract"));
    }

    @Test
    void everyProblemOfEveryCauseIsAValidProblemDetails() {
        for (ExposureCause cause : ExposureCause.values()) {
            assertEquals(Set.of(), validate("ProblemDetails", json(cause.problem("x", "param"))),
                    cause.name());
        }
    }

    // ---- closed sets agree with the document ---------------------------------

    @Test
    void causeEnumEqualsTheContractEnum() {
        Set<String> contract = new TreeSet<>();
        openapi.at("/components/schemas/ProblemDetails/properties/cause/enum")
                .forEach(n -> contract.add(n.asText()));
        Set<String> code = Arrays.stream(ExposureCause.values()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(contract, code);
    }

    @Test
    void pathsAndScopesEqualTheContract() {
        Set<String> paths = new TreeSet<>();
        openapi.get("paths").fieldNames().forEachRemaining(paths::add);
        assertEquals(new TreeSet<>(List.of(ExposureApi.UE_ID_PATH, ExposureApi.MONITORING_PATH,
                ExposureApi.SIM_CHANGE_PATH, ExposureApi.REDEEM_PATH)), paths);
        Set<String> scopes = new TreeSet<>();
        openapi.at("/components/securitySchemes/oAuth2ClientCredentials/flows/clientCredentials/scopes")
                .fieldNames().forEachRemaining(scopes::add);
        assertEquals(new TreeSet<>(List.of(ExposureApi.SCOPE_UE_ID, ExposureApi.SCOPE_MONITORING,
                ExposureApi.SCOPE_SIM_CHANGE, ExposureApi.SCOPE_OPERATOR_TOKEN)), scopes);
        assertTrue(openapi.at("/servers/0/url").asText().endsWith(ExposureApi.BASE_PATH));
    }

    // ---- negative cases named by the contract review -------------------------

    @Test
    void answerWithoutGpsiIsNotAContractAnswer() throws Exception {
        assertFalse(validate("UeIdInfo", json(
                "{\"bindingObservedAt\":\"2026-10-01T05:59:58Z\",\"evidenceSource\":\"GX\"}")).isEmpty());
        assertFalse(validate("UeIdInfo", json(
                "{\"gpsi\":\"+251911111111\",\"bindingObservedAt\":\"2026-10-01T05:59:58Z\","
                        + "\"evidenceSource\":\"GX\"}")).isEmpty(), "E.164 is not a GPSI");
    }

    @Test
    void imsiCannotHideInAnAnswer() throws Exception {
        assertFalse(validate("UeIdInfo", json("{\"gpsi\":\"msisdn-251911111111\","
                + "\"bindingObservedAt\":\"2026-10-01T05:59:58Z\",\"evidenceSource\":\"GX\","
                + "\"imsi\":\"655010000000001\"}")).isEmpty());
        assertFalse(validate("SubscriberVerificationResult", json("{\"msisdn\":\"251911111111\","
                + "\"authMethod\":\"EAP-AKA\",\"authenticatedAt\":\"2026-10-01T05:59:41Z\","
                + "\"simVerified\":true,\"tokenId\":\"t\",\"imsi\":\"655010000000001\"}")).isEmpty());
    }

    @Test
    void authMethodIsOnlyWhatIsImplemented() throws Exception {
        assertFalse(validate("SubscriberVerificationResult", json("{\"msisdn\":\"251911111111\","
                + "\"authMethod\":\"EAP-AKA'\",\"authenticatedAt\":\"2026-10-01T05:59:41Z\","
                + "\"simVerified\":true,\"tokenId\":\"t\"}")).isEmpty());
    }

    @Test
    void strictJsonRefusesUnknownFieldsAndCoercions() {
        assertEquals("INVALID_ARGUMENT", ExposureJson.read(
                "{\"afId\":\"a\",\"msisdn\":\"251911111111\",\"x\":1}", SimChangeReq.class).cause());
        assertEquals("UNSUPPORTED_ADDRESS_TYPE", ExposureJson.read(
                "{\"afId\":\"a\",\"ueMacAddr\":\"00-11-22-33-44-55\"}", UeIdReq.class).cause());
        assertEquals("INVALID_ARGUMENT", ExposureJson.read(
                "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.0.0.1\"},\"portNumber\":\"55555\","
                        + "\"observedAt\":\"2026-10-01T06:00:00Z\"}", UeIdReq.class).cause());
        assertEquals("INVALID_ARGUMENT", ExposureJson.read("not json", SimChangeReq.class).cause());
        assertEquals("INVALID_ARGUMENT", ExposureJson.read("", SimChangeReq.class).cause());
    }

    // ---- validator ⇔ schema: both accept, or both refuse ---------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"afId\":\"sas-camara\",\"msisdn\":\"251911111111\"}",
            "{\"afId\":\"sas-camara\",\"msisdn\":\"+251911111111\"}",
            "{\"afId\":\"sas-camara\",\"msisdn\":\"2519\"}",
            "{\"afId\":\"sas-camara\",\"msisdn\":\"2519111111111111\"}",
            "{\"afId\":\"\",\"msisdn\":\"251911111111\"}",
            "{\"msisdn\":\"251911111111\"}",
    })
    void simChangeValidatorAgreesWithTheSchema(String body) throws Exception {
        agree("SimChangeReq", body, SimChangeReq.class, ExposureRequestValidator::simChange);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.20.30.40\"},\"portNumber\":55555,\"observedAt\":\"2026-10-01T06:00:00Z\"}",
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv6Addr\":\"2001:db8::1\"},\"portNumber\":1,\"observedAt\":\"2026-10-01T06:00:00+03:00\"}",
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.20.30.40\"},\"observedAt\":\"2026-10-01T06:00:00Z\"}",
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.20.30.40\"},\"portNumber\":0,\"observedAt\":\"2026-10-01T06:00:00Z\"}",
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.20.30.40\"},\"portNumber\":55555}",
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.20.30.40\"},\"portNumber\":55555,\"observedAt\":\"yesterday\"}",
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.20.30.400\"},\"portNumber\":55555,\"observedAt\":\"2026-10-01T06:00:00Z\"}",
            "{\"afId\":\"a\",\"ueIpAddr\":{},\"portNumber\":55555,\"observedAt\":\"2026-10-01T06:00:00Z\"}",
            "{\"afId\":\"a\",\"ueIpAddr\":{\"ipv4Addr\":\"10.0.0.1\",\"ipv6Addr\":\"::1\"},\"portNumber\":5,\"observedAt\":\"2026-10-01T06:00:00Z\"}",
    })
    void ueIdValidatorAgreesWithTheSchema(String body) throws Exception {
        agree("UeIdReq", body, UeIdReq.class, ExposureRequestValidator::ueId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"UE_REACHABILITY\",\"maximumNumberOfReports\":1,\"immediateRep\":true}",
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"UE_REACHABILITY\",\"addnMonTypes\":[\"LOCATION_REPORTING\"],\"locationType\":\"LAST_KNOWN_LOCATION\",\"maximumNumberOfReports\":1,\"immediateRep\":true}",
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"ROAMING_STATUS\",\"maximumNumberOfReports\":1,\"immediateRep\":true}",
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"UE_REACHABILITY\",\"maximumNumberOfReports\":2,\"immediateRep\":true}",
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"UE_REACHABILITY\",\"maximumNumberOfReports\":1,\"immediateRep\":false}",
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"UE_REACHABILITY\",\"maximumNumberOfReports\":1}",
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"UE_REACHABILITY\",\"locationType\":\"CURRENT_LOCATION\",\"maximumNumberOfReports\":1,\"immediateRep\":true}",
            "{\"msisdn\":\"251911111111\",\"monitoringType\":\"UE_REACHABILITY\",\"addnMonTypes\":[\"LOCATION_REPORTING\",\"UE_REACHABILITY\"],\"maximumNumberOfReports\":1,\"immediateRep\":true}",
    })
    void monitoringValidatorAgreesWithTheSchema(String body) throws Exception {
        agree("MonitoringEventSubscription", body, MonitoringEventSubscription.class,
                req -> ExposureRequestValidator.monitoring("sas-camara", req));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"afId\":\"a\",\"operatorToken\":\"eyJlcGsiOnsia3R5IjoiRUMi\",\"clientId\":\"bank\"}",
            "{\"afId\":\"a\",\"operatorToken\":\"short\",\"clientId\":\"bank\"}",
            "{\"afId\":\"a\",\"operatorToken\":\"eyJlcGsiOnsia3R5IjoiRUMi\"}",
    })
    void redeemValidatorAgreesWithTheSchema(String body) throws Exception {
        agree("OperatorTokenRedeemReq", body, OperatorTokenRedeemReq.class,
                ExposureRequestValidator::redeem);
    }

    private static <T> void agree(String schemaName, String body, Class<T> type,
                                  Function<T, Optional<ProblemDetails>> rule) throws Exception {
        boolean schemaOk = validate(schemaName, json(body)).isEmpty();
        ExposureOutcome<T> parsed = ExposureJson.read(body, type);
        boolean codeOk = parsed.ok() && rule.apply(parsed.value()).isEmpty();
        assertEquals(schemaOk, codeOk, "schema and validator disagree on " + body);
    }

    @Test
    void headerRules() {
        assertTrue(ExposureRequestValidator.headers("req-0001", "2500").isEmpty());
        assertFalse(ExposureRequestValidator.headers("short", "2500").isEmpty());
        assertFalse(ExposureRequestValidator.headers("req 0001 x", "2500").isEmpty());
        assertFalse(ExposureRequestValidator.headers("req-0001", "0").isEmpty());
        assertFalse(ExposureRequestValidator.headers("req-0001", "3001").isEmpty());
        assertFalse(ExposureRequestValidator.headers("req-0001", null).isEmpty());
        assertFalse(ExposureRequestValidator.headers("req-0001", "fast").isEmpty());
    }
}
