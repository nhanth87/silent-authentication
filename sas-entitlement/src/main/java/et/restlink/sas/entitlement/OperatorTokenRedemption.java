/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import com.nimbusds.jose.jwk.ECKey;

import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.token.OperatorToken;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redemption of this ECS's own operator tokens (layered ADR D3, R10). The ECS issued the
 * token, so it alone can decrypt and verify it; the {@code jti} goes into the single-use
 * ledger, and the answer is recorded under {@code (jti, request id)} for the dedup window
 * so that a network retry of the same redemption gets the same answer instead of a 401.
 *
 * <p>Every refusal looks the same to the caller ({@link Optional#empty()}), whatever the
 * reason; the reason is logged without the token or the number.</p>
 */
@ApplicationScoped
public class OperatorTokenRedemption {

    private static final Logger LOG = LogManager.getLogger(OperatorTokenRedemption.class);

    /** Contract dedup window ({@code ExposureApi.DEDUP_WINDOW_MS}). */
    static final long DEDUP_WINDOW_MS = 60_000L;
    static final long CLOCK_SKEW_SECONDS = 60L;

    /** What one successful redemption proves. {@code msisdn} is in internal digit form. */
    public record Redeemed(String msisdn, String eapMethod, long issuedAtEpochSec, String jti) {

        @Override
        public String toString() {
            return "Redeemed[jti=" + jti + ", eap=" + eapMethod + "]";
        }
    }

    private record Recorded(Redeemed redeemed, long atMs) {
    }

    @Inject
    EntitlementConfig config;

    @Inject
    EcsTokenKeys keys;

    @Inject
    EntitlementTokenService tokens;

    @Inject
    Instance<OperatorTokenLedger> configuredLedgers;

    private volatile OperatorTokenLedger ledger;
    private volatile Clock clock = Clock.systemUTC();
    private final Map<String, Recorded> answers = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (configuredLedgers != null && configuredLedgers.isResolvable()) {
            ledger = configuredLedgers.get();
        } else {
            ledger = new InMemoryOperatorTokenLedger();
            LOG.warn("[ECS] operator-token ledger is in-memory (one instance, lost on restart) "
                    + "— lab only; prod needs the durable ledger");
        }
    }

    /** Test seam. */
    void use(OperatorTokenLedger ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    public boolean durableLedger() {
        return ledger != null && ledger.durable();
    }

    /**
     * Redeem once.
     *
     * @param token     the token without its {@code operatortoken:} prefix
     * @param requestId the caller's {@code X-Request-Id}
     * @return the verification result, or empty for any refusal
     */
    public Optional<Redeemed> redeem(String token, String requestId) {
        if (!config.redeemAtEcs()) {
            LOG.warn("[ECS] redeem refused: sas.ecs.token.redeem-at-ecs is off");
            return Optional.empty();
        }
        if (token == null || token.isBlank() || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        OperatorToken.Claims claims;
        try {
            claims = OperatorToken.verify(token, policy(), clock);
        } catch (OperatorToken.RejectedException | IllegalStateException e) {
            LOG.warn("[ECS] operator token refused: {}", e.getMessage());
            return Optional.empty();
        }
        long nowMs = clock.millis();
        evict(nowMs);
        String key = claims.jwtId() + '|' + requestId;
        Recorded earlier = answers.get(key);
        if (earlier != null) {
            LOG.info("[ECS] operator token redemption replayed for the same request (jti={})",
                    claims.jwtId());
            return Optional.of(earlier.redeemed());
        }
        if (!OperatorToken.EAP_AKA.equals(claims.eapMethod())) {
            // Only the mechanism actually implemented is ever attested (ADR R5).
            LOG.warn("[ECS] operator token refused: eap_method {} is not offered", claims.eapMethod());
            return Optional.empty();
        }
        String msisdn = PhoneNumberNormalizer.toDigits(claims.phoneNumber()).orElse(null);
        if (msisdn == null) {
            LOG.warn("[ECS] operator token refused: phone_number is not a number");
            return Optional.empty();
        }
        if (!ledger.consume(claims.issuer(), claims.jwtId(), claims.expiresAt())) {
            LOG.warn("[ECS] operator token refused: already redeemed (jti={})", claims.jwtId());
            return Optional.empty();
        }
        Redeemed redeemed = new Redeemed(msisdn, claims.eapMethod(), claims.issuedAt(), claims.jwtId());
        answers.put(key, new Recorded(redeemed, nowMs));
        if (tokens != null) {
            tokens.forgetIssued(token);
        }
        LOG.info("[ECS] operator token redeemed (jti={})", claims.jwtId());
        return Optional.of(redeemed);
    }

    private OperatorToken.Policy policy() {
        String issuer = config.ecsIssuer();
        ECKey signing = keys.signingKey().orElseThrow(
                () -> new IllegalStateException("no ECS signing key"));
        List<ECKey> decryption = keys.ownDecryptionKey().map(List::of).orElse(List.of());
        return new OperatorToken.Policy(Map.of(issuer, List.of(signing.toPublicJWK())), issuer,
                decryption, !config.allowUnencrypted(), CLOCK_SKEW_SECONDS);
    }

    private void evict(long nowMs) {
        answers.values().removeIf(r -> nowMs - r.atMs() > DEDUP_WINDOW_MS);
    }
}
