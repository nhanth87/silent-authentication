# TODO: Implement CAMARA SimSwap API (spec đã tải tại camara_sim_swap/)

Mirror VerifyResource patterns. Repo root mvn; JAVA_HOME=zulu-25 /usr/bin/mvn. Prior 358 tests.

1. sas-api `et.restlink.sas.simswap`:
   - port `SimSwapQueryPort { Optional<Instant> lastSimChange(String msisdn); }`
   - `SimSwapResource @Path("/sim-swap/v2")`:
     * POST /check {phoneNumber, maxAgeHours? default 240, clamp ≤2400} → {"swapped":bool}
       swapped := lastSimChange present && (now-last) <= maxAge; unknown → 404 NOT_FOUND
       IDENTIFIER_NOT_FOUND; E164 qua RequestValidator.normalizeE164.
     * POST /retrieve-date → {"latestSimChangedAt": ISO-8601}.
     * Bearer path y hệt doVerify: single-use consumed-jti, binding compare
       (boundNumber null→403), tenant gate+quota — KHÔNG có amr rule (spec r3.3 không yêu cầu).
     * Scopes: sim-swap:check / sim-swap:retrieve-date (TokenValidator thêm constants +
       family "sim-swap"; oauth AuthorizationRequestService whitelist += 2 scopes).
2. sas-host adapter implements port (đọc lastImsiChangeEpochMs từ InMemory{Map,S6a,Swx}
   backend theo thứ tự map→s6a→swx, thêm getter nhỏ trên SasBootstrap).
3. Testapp: KHÔNG bắt buộc đổi (evidence từ seed InMemory, mặc định 10 ngày → swapped=false @240h).
4. Tests ~15 mirror VerifyResourceTest: true/false boundary, default/clamp maxAge, date format,
   404 unknown, 400 bad phone, 401, 403 scope/mismatch, quota, whitelist unit, adapter unit.
Header "(c) 2026 Tran Nhan (nhanth87)". Không mention AI.
