#!/usr/bin/env python3
"""Production preflight for the Silent Auth SAS — deployment gate (P-H1…P-H7).

Two binaries, two gates: `--service camara` (default) reads
sas-host/src/main/resources/application.properties overlaid with
application-prod.properties (the Quarkus `prod` profile); `--service ecs` does the
same for sas-ecs-host (the TS.43 entitlement server); `--service all` runs both. It expands
${ENV_VAR} / ${ENV_VAR:default} against the real environment, and asserts the
production security invariants BEFORE the JVM starts. The application code is
already fail-closed (blank secret ⇒ reject all, unknown transport ⇒ memory), so
this gate's job is to turn those silent refusals into one readable verdict with
a non-zero exit code.

Usage:
  python3 harness/preflight_prod.py                    # check the real environment
  python3 harness/preflight_prod.py --env K=V --env K2=V2
  python3 harness/preflight_prod.py --selftest         # prove the gate bites
  python3 harness/preflight_prod.py --json             # machine-readable output

Exit code == number of failed checks (selftest: number of undetected mutations).
Static analysis + env inspection only: it never starts a JVM and never prints a
secret value — only lengths and fingerprints.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
# `camara` = sas-host (sas-camara: CAMARA APIs + OAuth AS); `ecs` = sas-ecs-host
# (sas-ecs: TS.43 entitlement server, EAP-AKA over MAP/Diameter).
SERVICES = {
    "camara": ROOT / "sas-host" / "src" / "main" / "resources",
    "ecs": ROOT / "sas-ecs-host" / "src" / "main" / "resources",
}
BASE_PROPS = SERVICES["camara"] / "application.properties"
PROD_PROPS = SERVICES["camara"] / "application-prod.properties"

EXPR = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")

# Values that mean "lab" — never acceptable for a production deployment.
LAB_TOKENS = ("change-me", "sas-dev", "demo-key", "dev-session-hmac", "test-only")
MEMORY_TRANSPORTS = ("memory", "pilot", "in-memory", "")
REAL_RESOLVERS = ("radius", "cgnat", "sd")
LOOPBACK = ("127.", "localhost", "0.0.0.0", "::1")
MIN_SECRET_LEN = 32

# Every one of these MUST be redefined by the prod profile: a key dropped from
# application-prod.properties silently reverts to its lab default, which is the
# exact regression this preflight exists to catch.
CRITICAL_KEYS = (
    "quarkus.http.insecure-requests",
    "quarkus.http.ssl.client-auth",
    "quarkus.http.ssl.certificate.key-store-file",
    "quarkus.http.ssl.certificate.key-store-password",
    "quarkus.datasource.db-kind",
    "quarkus.datasource.jdbc.url",
    "quarkus.datasource.username",
    "quarkus.datasource.password",
    "quarkus.hibernate-orm.database.generation",
    "sas.security.token-validation-enabled",
    "sas.security.hmac-secret",
    "sas.security.expected-issuer",
    "sas.security.expected-audience",
    "sas.security.enforce-api-keys",
    "sas.tenants.api-keys",
    "sas.admin.key",
    "sas.admin.session-hmac-secret",
    "sas.admin.cookie-secure",
    "sas.transport.map",
    "sas.transport.s6a",
    "sas.transport.swx",
    "sas.transport.resolver",
    "sas.transport.jss7.config",
    "sas.transport.diameter.peer-host",
    "sas.security.accept-hs256",
    "sas.oauth.signing-alg",
    "sas.oauth.signing-jwk",
    "sas.oauth.allow-ephemeral-signing-key",
    "sas.oauth.access-token-audience",
    "sas.oauth.require-client-auth",
    "sas.oauth.clients-json",
    "sas.oauth.pairwise-secret",
    "sas.oauth.authorize.trust-src-header",
    "sas.operator-token.issuers-json",
    "sas.operator-token.decryption-jwk",
    "sas.operator-token.require-encryption",
    "sas.operator-token.allow-http-jwks",
    "sas.otp.enabled",
    "sas.otp.sms-delivery",
)

# sas-ecs: every key whose lab default would be unsafe in production.
CRITICAL_KEYS_ECS = (
    "sas.exposure.enabled",
    "quarkus.http.insecure-requests",
    "quarkus.http.ssl.client-auth",
    "quarkus.http.ssl.certificate.key-store-file",
    "quarkus.http.ssl.certificate.key-store-password",
    "sas.transport.authvector",
    "sas.binding.source-order",
    "sas.entitlement.token-format",
    "sas.entitlement.require-signed",
    "sas.entitlement.issue-attestation-required",
    "sas.entitlement.issue-attestation-secret",
    "sas.ecs.issuer",
    "sas.ecs.signing-jwk",
    "sas.ecs.allow-ephemeral-signing-key",
    "sas.ecs.token.redeem-at-ecs",
    "sas.ecs.token.decryption-jwk",
    "sas.ecs.token.allow-unencrypted",
    "quarkus.datasource.db-kind",
    "quarkus.datasource.jdbc.url",
    "sas.entitlement.legacy-exchange-enabled",
    "sas.security.enforce-api-keys",
    "sas.security.api-key",
)

# Key ids of the PUBLICLY KNOWN lab keys committed in the lab application.properties.
LAB_KID_PREFIXES = ("lab-",)


def parse_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    if not path.exists():
        return props
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def expand(value: str, env: dict[str, str]) -> tuple[str, list[str]]:
    """Resolve ${VAR} / ${VAR:default}; returns (value, missing_required_vars)."""
    missing: list[str] = []

    def sub(match: re.Match) -> str:
        name, default = match.group(1), match.group(2)
        current = env.get(name, "")
        if current:
            return current
        if default is not None:
            return default
        missing.append(name)
        return ""

    return EXPR.sub(sub, value), missing


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


class Profile:
    """Base properties overlaid with the prod profile, env-expanded."""

    def __init__(self, env: dict[str, str], check_files: bool = True,
                 overrides: dict[str, str | None] | None = None, service: str = "camara"):
        self.env = env
        self.check_files = check_files
        self.service = service
        self.base_path = SERVICES[service] / "application.properties"
        self.prod_path = SERVICES[service] / "application-prod.properties"
        self.base = parse_properties(self.base_path)
        self.prod = parse_properties(self.prod_path) if self.prod_path.exists() else {}
        self.merged = {**self.base, **self.prod}
        for key, value in (overrides or {}).items():
            if value is None:
                # emulate a key dropped from application-prod.properties
                self.prod.pop(key, None)
                self.merged.pop(key, None)
            else:
                self.prod[key] = value
                self.merged[key] = value
        self.values: dict[str, str] = {}
        self.missing: dict[str, list[str]] = {}
        for key, raw in self.merged.items():
            value, miss = expand(raw, env)
            self.values[key] = value
            self.missing[key] = miss

    def get(self, key: str, default: str = "") -> str:
        return self.values.get(key, default)

    def raw(self, key: str) -> str:
        return self.merged.get(key, "")

    def defined_in_prod(self, key: str) -> bool:
        return key in self.prod

    def env_sourced(self, key: str) -> bool:
        return bool(EXPR.search(self.raw(key)))

    def missing_env(self) -> list[str]:
        out = [f"{key} <- ${{{name}}}"
               for key, names in self.missing.items() for name in names]
        return sorted(out)


class Report:
    def __init__(self) -> None:
        self.rows: list[dict] = []

    def add(self, cid: str, title: str, ok: bool, detail: str = "") -> bool:
        self.rows.append({"id": cid, "title": title, "ok": bool(ok), "detail": detail})
        return bool(ok)

    def failed_ids(self) -> set[str]:
        return {r["id"] for r in self.rows if not r["ok"]}

    def failures(self) -> list[dict]:
        return [r for r in self.rows if not r["ok"]]

    def print(self, stream=sys.stdout) -> None:
        width = max((len(r["id"]) for r in self.rows), default=6)
        for r in self.rows:
            mark = "PASS" if r["ok"] else "FAIL"
            stream.write(f"  [{mark}] {r['id'].ljust(width)}  {r['title']}\n")
            if not r["ok"] and r["detail"]:
                for line in r["detail"].splitlines():
                    stream.write(f"          {line}\n")


def is_labish(value: str) -> bool:
    low = value.strip().lower()
    return any(tok in low for tok in LAB_TOKENS)


def file_ok(path: str) -> tuple[bool, str]:
    if not path:
        return False, "empty path"
    p = Path(path).expanduser()
    if not p.exists():
        return False, f"{path} does not exist"
    if not p.is_file():
        return False, f"{path} is not a regular file"
    return True, path


def _int(p: Profile, key: str, default: int = 0) -> int:
    try:
        return int(p.get(key) or default)
    except ValueError:
        return -1


def secret(p: Profile, key: str) -> tuple[bool, str]:
    """True when `key` is env-sourced, non-placeholder and long enough."""
    if not p.defined_in_prod(key):
        return False, f"{key} is not set by the prod profile (lab default in effect)"
    if not p.env_sourced(key):
        return False, f"{key} is a literal in application-prod.properties — it must " \
                      f"come from the environment/secrets manager"
    value = p.get(key)
    if not value:
        return False, f"{key} unresolved — required environment variable missing ({p.raw(key)})"
    if len(value) < MIN_SECRET_LEN:
        return False, f"{key} is only {len(value)} chars — minimum {MIN_SECRET_LEN}"
    if is_labish(value):
        return False, f"{key} carries a lab/placeholder value ({value[:3]}...)"
    return True, f"{key} ok (len={len(value)}, fp={fingerprint(value)})"


# ---------------------------------------------------------------------------
# the checks — one per production invariant
# ---------------------------------------------------------------------------

def build_checks(p: Profile) -> Report:
    r = Report()

    # --- gate integrity -----------------------------------------------------
    r.add("PRO-01", "prod profile exists", p.prod_path.exists() and bool(p.prod),
          f"missing {p.prod_path}")
    uncovered = [k for k in CRITICAL_KEYS if not p.defined_in_prod(k)]
    r.add("PRO-02", "prod profile overrides every lab-critical key", not uncovered,
          "still on lab defaults:\n" + "\n".join(uncovered))
    miss = p.missing_env()
    r.add("PRO-03", "no unresolved required environment variable", not miss, "\n".join(miss))

    # --- P-H1 northbound auth ----------------------------------------------
    r.add("PRO-04", "bank->SAS token validation enforced",
          p.get("sas.security.token-validation-enabled") == "true",
          "sas.security.token-validation-enabled must be true; false keeps the P0 "
          "behaviour (identity = body phoneNumber) and is not a production mode")
    hs256 = p.get("sas.security.accept-hs256", "true") == "true"
    if hs256:
        ok, why = secret(p, "sas.security.hmac-secret")
    else:
        ok, why = True, "HS256 bearer tokens refused; no shared secret to provision"
    r.add("PRO-05", "no weak shared bearer-token secret", ok, why)

    issuer = p.get("sas.security.expected-issuer")
    audience = p.get("sas.security.expected-audience")
    notes = []
    if not (issuer and audience and issuer != audience):
        notes.append(f"issuer={issuer!r} audience={audience!r} (both required, must differ)")
    if not hs256:
        # ES256 tokens come from THIS Authorization Server: the resource server must pin
        # its issuer and the audience the AS stamps, or every token is rejected.
        as_issuer = p.get("sas.oauth.public-base-url").rstrip("/")
        if issuer.rstrip("/") != as_issuer:
            notes.append(f"expected-issuer {issuer!r} != sas.oauth.public-base-url {as_issuer!r}")
        if audience != p.get("sas.oauth.access-token-audience"):
            notes.append("expected-audience != sas.oauth.access-token-audience")
    r.add("PRO-06", "iss/aud pinned", not notes, "\n".join(notes))

    scopes = p.get("sas.security.required-scopes")
    r.add("PRO-07", "global scope gate empty (per-endpoint scopes do the work)", not scopes,
          f"sas.security.required-scopes={p.raw('sas.security.required-scopes')!r} is a global "
          "AND across /verify and /device-phone-number — pinning one scope 403s the other")

    r.add("PRO-08", "per-tenant API key enforcement",
          p.get("sas.security.enforce-api-keys") == "true",
          "sas.security.enforce-api-keys must be true; with false every caller collapses "
          "into the implicit 'lab' tenant and billing/tenant scoping is meaningless")

    keys_raw = p.get("sas.tenants.api-keys")
    pairs = [kv.split("=", 1) for kv in keys_raw.split(",") if kv.strip()]
    bad: list[str] = []
    if not pairs:
        bad.append("key map empty while enforcement is on (TenantRegistry rejects all)")
    seen: set[str] = set()
    for pair in pairs:
        if len(pair) != 2 or not pair[0].strip() or len(pair[1].strip()) < 16:
            bad.append(f"malformed or short entry for tenant {pair[0][:16]!r}")
            continue
        tenant, key = pair[0].strip(), pair[1].strip()
        if is_labish(key):
            bad.append(f"tenant {tenant!r}: lab/placeholder API key")
        if key in seen:
            bad.append(f"tenant {tenant!r}: API key already assigned to another tenant")
        seen.add(key)
    r.add("PRO-09", "tenant API keys unique, non-placeholder, >=16 chars", not bad, "\n".join(bad))

    skew = _int(p, "sas.security.clock-skew-seconds")
    replay = _int(p, "sas.security.replay-window-seconds")
    reqid = _int(p, "sas.security.reqid-ttl-seconds")
    r.add("PRO-10", "replay/idempotency windows bounded",
          0 < skew <= 60 and 0 < replay <= 300 and reqid >= replay,
          f"clock-skew={skew}s (want <=60) replay={replay}s (want <=300) "
          f"reqId-TTL={reqid}s (want >= replay window)")

    # --- P-H1 transport security -------------------------------------------
    r.add("PRO-11", "cleartext HTTP disabled",
          p.get("quarkus.http.insecure-requests") == "disabled",
          f"quarkus.http.insecure-requests={p.get('quarkus.http.insecure-requests')!r} "
          "(production requires 'disabled'; the base profile listens plain on :8085)")

    ks = p.get("quarkus.http.ssl.certificate.key-store-file")
    ks_pw = p.get("quarkus.http.ssl.certificate.key-store-password")
    ks_key_pw = p.get("quarkus.http.ssl.certificate.key-store-key-password")
    notes = []
    if not p.env_sourced("quarkus.http.ssl.certificate.key-store-file"):
        notes.append("keystore path must be env-supplied")
    if p.check_files:
        f_ok, f_why = file_ok(ks)
        if not f_ok:
            notes.append(f"keystore: {f_why}")
    if len(ks_pw) < 8 or len(ks_key_pw) < 8 or is_labish(ks_pw) or is_labish(ks_key_pw):
        notes.append("keystore passwords must be env-supplied and >=8 chars")
    r.add("PRO-12", "TLS keystore provisioned", not notes, "\n".join(notes))

    client_auth = p.get("quarkus.http.ssl.client-auth")
    ts = p.get("quarkus.http.ssl.certificate.trust-store-file")
    notes = []
    if client_auth not in ("required", "request"):
        notes.append(f"quarkus.http.ssl.client-auth={client_auth!r} — mTLS is off")
    if not ts:
        notes.append("no trust store: presented client certificates cannot be validated")
    elif p.check_files:
        f_ok, f_why = file_ok(ts)
        if not f_ok:
            notes.append(f"truststore: {f_why}")
    r.add("PRO-13", "bank->SAS mTLS (FS.11 spoofed-identity defence)", not notes, "\n".join(notes))

    # --- P-H2 admin surface -------------------------------------------------
    admin_key = p.get("sas.admin.key")
    r.add("PRO-14", "admin key rotated",
          p.env_sourced("sas.admin.key") and len(admin_key) >= 16 and not is_labish(admin_key),
          f"sas.admin.key must be env-supplied, >=16 chars, not the 'change-me' default "
          f"(sourced={p.env_sourced('sas.admin.key')}, len={len(admin_key)})")
    ok, why = secret(p, "sas.admin.session-hmac-secret")
    r.add("PRO-15", "admin session secret rotated", ok, why)
    cost = _int(p, "sas.admin.password.bcrypt-cost", 0)
    r.add("PRO-16", "admin cookies Secure-flagged + bcrypt cost sane",
          p.get("sas.admin.cookie-secure") == "true" and 10 <= cost <= 16,
          f"cookie-secure={p.get('sas.admin.cookie-secure')!r} bcrypt-cost={cost} (want 10..16)")

    # --- P2 / P-H4 signalling transport ------------------------------------
    transports = {
        "sas.transport.map": ("jss7",),
        "sas.transport.s6a": ("corsac",),
        "sas.transport.swx": ("corsac",),
        "sas.transport.resolver": REAL_RESOLVERS,
    }
    notes = [f"{key}={p.get(key)!r} (want {'|'.join(want)})"
             for key, want in transports.items()
             if p.get(key).lower() not in want]
    r.add("PRO-17", "no in-memory pilot backend on any stage", not notes,
          "\n".join(notes) + "\n  an unknown value falls back to memory in code — a typo "
          "must never open a fake signalling path against a live bank")

    jss7_cfg = p.get("sas.transport.jss7.config")
    notes = []
    if not p.env_sourced("sas.transport.jss7.config"):
        notes.append("jss7 stack config path must be env-supplied (no lab JSON in prod)")
    elif p.check_files:
        f_ok, f_why = file_ok(jss7_cfg)
        if not f_ok:
            notes.append(f"jss7 config: {f_why}")
        else:
            try:
                json.loads(Path(jss7_cfg).expanduser().read_text(encoding="utf-8"))
            except (ValueError, OSError) as exc:
                notes.append(f"jss7 config is not readable JSON: {exc}")
    hlr_gt, local_gt = p.get("sas.transport.jss7.hlr-gt"), p.get("sas.transport.jss7.local-gt")
    if not (hlr_gt.isdigit() and local_gt.isdigit()):
        notes.append(f"GTs must be digits (hlr-gt={hlr_gt!r} local-gt={local_gt!r})")
    if hlr_gt == local_gt:
        notes.append("HLR GT equals local GT — the SAS would interrogate itself")
    if hlr_gt == "251911000000" or local_gt == "251911999999":
        notes.append("lab GT defaults still in effect — point them at the own HLR/HSS")
    r.add("PRO-18", "jSS7 MAP stack configured (PSI/SAI against own HLR)",
          not notes, "\n".join(notes))

    origin_host = p.get("sas.transport.diameter.origin-host")
    realm = p.get("sas.transport.diameter.realm")
    dest_host = p.get("sas.transport.diameter.destination-host")
    dest_realm = p.get("sas.transport.diameter.destination-realm")
    notes = []
    for key, value in (("origin-host", origin_host), ("realm", realm),
                       ("destination-host", dest_host), ("destination-realm", dest_realm)):
        if not value:
            notes.append(f"sas.transport.diameter.{key} unresolved")
        elif is_labish(value) or value in ("sas.restlink.et", "hss.restlink.et", "restlink.et"):
            notes.append(f"sas.transport.diameter.{key}={value!r} is the lab identity")
    if origin_host and origin_host == dest_host:
        notes.append("origin-host equals destination-host")
    r.add("PRO-19", "Diameter identity is the operator's own realm", not notes, "\n".join(notes))

    peer = p.get("sas.transport.diameter.peer-host")
    sd_peer = p.get("sas.transport.sd.peer-host") or peer
    notes = []
    if not peer:
        notes.append("sas.transport.diameter.peer-host unresolved")
    else:
        if any(peer.startswith(pre) or peer == pre for pre in LOOPBACK):
            notes.append(f"S6a/SWx peer is loopback ({peer}) — the CORSAC/SimHss lab path, "
                         "not a live HSS/AAA")
        if not p.env_sourced("sas.transport.diameter.peer-host"):
            notes.append("peer host must be env-supplied")
    if p.get("sas.transport.resolver") == "sd" and any(
            sd_peer.startswith(pre) or sd_peer == pre for pre in LOOPBACK):
        notes.append(f"PCRF Sd probe peer is loopback ({sd_peer})")
    r.add("PRO-20", "Diameter peer is a real host, not loopback", not notes, "\n".join(notes))

    resolver = p.get("sas.transport.resolver").lower()
    notes = []
    if resolver not in REAL_RESOLVERS:
        notes.append(f"resolver={resolver!r} is not a production IP-binding feed "
                     f"({'|'.join(REAL_RESOLVERS)}) — nothing to authenticate")
    elif resolver == "radius":
        ok, why = secret(p, "sas.transport.resolver.radius.secret")
        if not ok:
            notes.append("RADIUS: " + why + " — an empty secret disables Message-Authenticator "
                         "verification (RFC 2869 §5.7), so bindings become spoofable")
        port = _int(p, "sas.transport.resolver.radius.port", -1)
        if not 0 < port <= 65535:
            notes.append(f"RADIUS accounting port out of range: {port}")
    elif resolver == "cgnat":
        log_path = p.get("sas.transport.resolver.cgnat-log")
        if not log_path:
            notes.append("CGNAT: sas.transport.resolver.cgnat-log unresolved")
        elif p.check_files:
            f_ok, f_why = file_ok(log_path)
            if not f_ok:
                notes.append(f"CGNAT: {f_why}")
    elif resolver == "sd":
        if p.get("sas.transport.sd.sctp", "true") != "true":
            notes.append("PCRF Sd over TCP — Gx is SCTP-only in production")
    r.add("PRO-21", f"resolver feed ({resolver or 'unset'}) is authenticated", not notes,
          "\n".join(notes))

    # --- auth server signing key (ES256) --------------------------------------
    # (PRO-22/23 — entitlement tokens and /issue attestation — are sas-ecs checks now.)
    notes = []
    if p.get("sas.oauth.signing-alg").upper() != "ES256":
        notes.append("sas.oauth.signing-alg must be ES256 (asymmetric, JWKS-verifiable)")
    if p.get("sas.oauth.allow-ephemeral-signing-key") == "true":
        notes.append("ephemeral signing key allowed — tokens die on restart; lab only")
    ok_k, why_k = jwk_secret(p, "sas.oauth.signing-jwk", "sig")
    if not ok_k:
        notes.append(why_k)
    r.add("PRO-24", "authorization-server ES256 signing key provisioned", not notes,
          "\n".join(notes) if notes else why_k)

    db_kind = p.get("quarkus.datasource.db-kind")
    jdbc = p.get("quarkus.datasource.jdbc.url")
    db_user = p.get("quarkus.datasource.username")
    db_pass = p.get("quarkus.datasource.password")
    notes = []
    if db_kind != "postgresql":
        notes.append(f"db-kind={db_kind!r} — the H2 file DB is a lab artifact (single node, "
                     "no replication, ./data on local disk)")
    if not jdbc.startswith("jdbc:postgresql://") or "h2:" in jdbc:
        notes.append(f"jdbc url not postgres: {jdbc!r}")
    if not db_user or len(db_pass) < 8 or is_labish(db_pass):
        notes.append("datasource credentials must be env-supplied (user set, password >=8 chars)")
    if p.get("quarkus.hibernate-orm.database.generation") != "none":
        notes.append("hibernate schema generation must be 'none' — schema owned by Flyway")
    if p.get("quarkus.flyway.migrate-at-start") != "true":
        notes.append("Flyway migrations must run at startup (versioned schema)")
    r.add("PRO-25", "P-H6 persistence on PostgreSQL under Flyway", not notes, "\n".join(notes))

    log_dir = p.get("sas.log.dir")
    r.add("PRO-26", "CDR + audit persistence enabled",
          p.get("sas.cdr.enabled") == "true" and p.get("sas.cdr.db.enabled") == "true"
          and bool(log_dir) and os.path.isabs(log_dir),
          f"sas.cdr.enabled={p.get('sas.cdr.enabled')!r} "
          f"sas.cdr.db.enabled={p.get('sas.cdr.db.enabled')!r} sas.log.dir={log_dir!r} "
          "(must be an absolute path outside the source tree)")

    r.add("PRO-27", "assurance detail off by default (not in the CAMARA contract)",
          p.get("sas.api.assurance-detail-enabled", "false") != "true",
          "sas.api.assurance-detail-enabled=true leaks internal evidence to bank clients; "
          "it must stay opt-in per request")
    return r


def jwk_secret(p: Profile, key: str, use: str) -> tuple[bool, str]:
    """A private P-256 JWK from the environment, never a committed lab key."""
    if not p.defined_in_prod(key):
        return False, f"{key} is not set by the prod profile"
    if not p.env_sourced(key):
        return False, f"{key} must come from the environment/secret store, not a literal"
    return jwk_value_ok(p.get(key), key, use, private=True)


def jwk_value_ok(raw: str, key: str, use: str, private: bool) -> tuple[bool, str]:
    if not raw:
        return False, f"{key} unresolved — required environment variable missing"
    try:
        jwk = json.loads(raw)
    except ValueError:
        return False, f"{key} is not JSON"
    if not isinstance(jwk, dict) or jwk.get("kty") != "EC" or jwk.get("crv") != "P-256" \
            or not jwk.get("x") or not jwk.get("y"):
        return False, f"{key} is not a P-256 EC JWK"
    if private and not jwk.get("d"):
        return False, f"{key} has no private part (d)"
    if not private and jwk.get("d"):
        return False, f"{key} carries a private part — configure the PUBLIC key only"
    if jwk.get("use") not in (None, use):
        return False, f"{key} use={jwk.get('use')!r}, expected {use!r}"
    kid = str(jwk.get("kid") or "")
    if kid.startswith(LAB_KID_PREFIXES):
        return False, f"{key} is the publicly known LAB key (kid {kid})"
    return True, f"{key} ok (P-256, kid={kid or 'thumbprint'})"


def _json_list(raw: str, field: str) -> list | None:
    try:
        doc = json.loads(raw)
    except ValueError:
        return None
    items = doc.get(field) if isinstance(doc, dict) else doc
    return items if isinstance(items, list) else None


def oauth_checks(p: Profile) -> Report:
    """PRO-31..33 — the CAMARA Authorization Server and the operator-token trust."""
    r = Report()
    notes = []
    if p.get("sas.oauth.require-client-auth") != "true":
        notes.append("sas.oauth.require-client-auth must be true (private_key_jwt, CAMARA ICM)")
    if not p.env_sourced("sas.oauth.clients-json"):
        notes.append("sas.oauth.clients-json must come from the environment")
    else:
        clients = _json_list(p.get("sas.oauth.clients-json") or "null", "clients")
        if not clients:
            notes.append("no OAuth client registered (or clients-json is not valid JSON)")
        for c in clients or []:
            cid = c.get("client_id", "?")
            if c.get("token_endpoint_auth_method", "private_key_jwt") != "private_key_jwt":
                notes.append(f"client {cid}: auth method must be private_key_jwt")
            if not (c.get("jwks") or {}).get("keys"):
                notes.append(f"client {cid}: no jwks to verify its client assertions")
            for uri in c.get("redirect_uris") or []:
                if not str(uri).startswith("https://"):
                    notes.append(f"client {cid}: redirect_uri {uri!r} is not https")
    ok, why = secret(p, "sas.oauth.pairwise-secret")
    if not ok:
        notes.append(why)
    if not p.raw("sas.oauth.authorize.trust-src-header"):
        notes.append("sas.oauth.authorize.trust-src-header must be decided explicitly")
    r.add("PRO-31", "OAuth clients authenticate (private_key_jwt); ID subjects pairwise",
          not notes, "\n".join(notes))

    notes = []
    if p.get("sas.operator-token.enabled", "true") == "true":
        raw = p.get("sas.operator-token.issuers-json")
        if not p.env_sourced("sas.operator-token.issuers-json") or not raw:
            notes.append("sas.operator-token.issuers-json must come from the environment")
        else:
            issuers = _json_list(raw, "issuers")
            if not issuers:
                notes.append("no trusted entitlement-server issuer (or invalid JSON)")
            for it in issuers or []:
                iss = str(it.get("issuer", ""))
                if not iss.startswith("https://"):
                    notes.append(f"issuer {iss!r} is not https")
                uri = it.get("jwks_uri")
                if uri and not str(uri).startswith("https://"):
                    notes.append(f"issuer {iss}: jwks_uri {uri!r} is not https")
                if not uri and not (it.get("jwks") or {}).get("keys"):
                    notes.append(f"issuer {iss}: neither jwks_uri nor inline jwks")
        ok, why = jwk_secret(p, "sas.operator-token.decryption-jwk", "enc")
        if not ok:
            notes.append(why)
        if p.get("sas.operator-token.require-encryption", "true") != "true":
            notes.append("operator tokens must be encrypted (the MSISDN crosses the device)")
        if p.get("sas.operator-token.allow-http-jwks", "false") == "true":
            notes.append("plain-HTTP jwks_uri allowed — a MITM could swap the issuer keys")
        if p.get("sas.operator-token.header-enabled", "false") == "true":
            notes.append("X-Sas-Operator-Token header path is a lab helper; keep it off")
    r.add("PRO-32", "operator tokens: trusted HTTPS issuers, encrypted, own decryption key",
          not notes, "\n".join(notes))

    r.add("PRO-33", "resource server accepts only ES256 tokens from this AS",
          p.get("sas.security.accept-hs256", "true") == "false",
          "sas.security.accept-hs256 must be false in prod: an HS256 secret lets anyone "
          "holding it mint CAMARA access tokens")
    return r


def ecs_checks(p: Profile) -> Report:
    """sas-ecs production profile: transport, signalling, operator tokens, machine auth."""
    r = Report()
    r.add("PRO-01", "prod profile exists", p.prod_path.exists() and bool(p.prod),
          f"missing {p.prod_path}")
    uncovered = [k for k in CRITICAL_KEYS_ECS if not p.defined_in_prod(k)]
    r.add("PRO-02", "prod profile overrides every lab-critical key", not uncovered,
          "still on lab defaults:\n" + "\n".join(uncovered))
    miss = p.missing_env()
    r.add("PRO-03", "no unresolved required environment variable", not miss, "\n".join(miss))

    notes = []
    if p.get("sas.security.enforce-api-keys") != "true":
        notes.append("sas.security.enforce-api-keys must be true (/entitlement/issue|exchange)")
    ok, why = secret(p, "sas.security.api-key")
    if not ok:
        notes.append(why)
    r.add("PRO-08", "machine endpoints require API keys", not notes, "\n".join(notes))

    r.add("PRO-11", "cleartext HTTP disabled",
          p.get("quarkus.http.insecure-requests") == "disabled",
          f"quarkus.http.insecure-requests={p.get('quarkus.http.insecure-requests')!r}")
    notes = []
    if not p.env_sourced("quarkus.http.ssl.certificate.key-store-file"):
        notes.append("keystore path must be env-supplied")
    elif p.check_files:
        f_ok, f_why = file_ok(p.get("quarkus.http.ssl.certificate.key-store-file"))
        if not f_ok:
            notes.append(f"keystore: {f_why}")
    if len(p.get("quarkus.http.ssl.certificate.key-store-password")) < 8:
        notes.append("keystore password must be env-supplied and >=8 chars")
    r.add("PRO-12", "TLS keystore provisioned", not notes, "\n".join(notes))
    client_auth = p.get("quarkus.http.ssl.client-auth")
    r.add("PRO-13", "TLS client-auth decided (device-facing: request; AAA-only: required)",
          client_auth in ("request", "required"),
          f"quarkus.http.ssl.client-auth={client_auth!r}")

    source = (p.get("sas.transport.authvector") or "").strip().lower()
    order = [t.strip() for t in (p.get("sas.binding.source-order") or "").split(",") if t.strip()]
    uses_map = source in ("map", "jss7") or "map-smi" in order
    notes = []
    if uses_map:
        cfg = p.get("sas.transport.jss7.config")
        if not cfg:
            notes.append("MAP in use but sas.transport.jss7.config unresolved")
        elif p.check_files:
            f_ok, f_why = file_ok(cfg)
            if not f_ok:
                notes.append(f"jss7 config: {f_why}")
        hlr, local = p.get("sas.transport.jss7.hlr-gt"), p.get("sas.transport.jss7.local-gt")
        if not (hlr.isdigit() and local.isdigit()) or hlr == local:
            notes.append(f"GTs must be distinct digits (hlr-gt={hlr!r} local-gt={local!r})")
        if hlr == "251911000000" or local in ("251911999999", "251911999998"):
            notes.append("lab GT defaults still in effect")
    r.add("PRO-18", "MAP stack configured when MAP is used (SAI / SendIMSI, own HLR)",
          not notes, "\n".join(notes) if notes else f"MAP in use: {uses_map}")

    notes = []
    if source == "corsac":
        peer = p.get("sas.transport.diameter.peer-host")
        if not peer or any(peer.startswith(pre) or peer == pre for pre in LOOPBACK):
            notes.append(f"SWx peer {peer!r} is unset or loopback")
        for key in ("origin-host", "realm", "destination-host", "destination-realm"):
            v = p.get(f"sas.transport.diameter.{key}")
            if not v or v in ("ecs.restlink.et", "hss.restlink.et", "restlink.et"):
                notes.append(f"sas.transport.diameter.{key}={v!r} is unset or the lab identity")
    r.add("PRO-19", "Diameter SWx identity/peer are the operator's own (when SWx is used)",
          not notes, "\n".join(notes) if notes else f"SWx in use: {source == 'corsac'}")

    notes = []
    if p.get("sas.entitlement.token-format") != "operator-jwe":
        notes.append("sas.entitlement.token-format must be operator-jwe (split deployment)")
    ttl = _int(p, "sas.entitlement.token-ttl-seconds", 0)
    if not 0 < ttl <= 300:
        notes.append(f"token TTL {ttl}s breaks the CAMARA single-use <=300s ceiling")
    if p.get("sas.entitlement.require-signed") != "true":
        notes.append("sas.entitlement.require-signed must stay true")
    if not p.get("sas.ecs.issuer").startswith("https://"):
        notes.append(f"sas.ecs.issuer={p.get('sas.ecs.issuer')!r} must be an https identifier")
    if p.get("sas.ecs.allow-ephemeral-signing-key") == "true":
        notes.append("ephemeral ECS signing key — lab only")
    if p.get("sas.ecs.token.allow-unencrypted") == "true":
        notes.append("unencrypted operator tokens would expose the MSISDN to the device/app")
    if p.get("sas.entitlement.legacy-exchange-enabled", "true") != "false":
        notes.append("legacy /entitlement/exchange is a second redemption point next to "
                     "/operator-token/redeem — one token could be spent twice; disable it")
    ok, why = jwk_secret(p, "sas.ecs.signing-jwk", "sig")
    if not ok:
        notes.append(why)
    # Layered ADR D3/D7: the ECS is the token's audience and only redeemer — its own
    # decryption key, and a ledger every instance shares that survives a restart.
    if (p.get("sas.ecs.token.redeem-at-ecs") or "true").strip().lower() != "true":
        notes.append("sas.ecs.token.redeem-at-ecs must be true: the ECS is the only redeemer (D3)")
    ok, why = jwk_secret(p, "sas.ecs.token.decryption-jwk", "enc")
    if not ok:
        notes.append(why)
    if (p.get("quarkus.datasource.db-kind") or "").strip().lower() != "postgresql":
        notes.append("the single-use ledger must be on PostgreSQL (D7); an H2 file is per "
                     "instance and lets a token be spent once per ECS")
    r.add("PRO-22", "operator tokens signed (ES256), encrypted to and redeemed by this ECS "
          "once (durable ledger), within TTL", not notes, "\n".join(notes))

    ok, why = secret(p, "sas.entitlement.issue-attestation-secret")
    r.add("PRO-23", "/entitlement/issue requires AAA attestation",
          p.get("sas.entitlement.issue-attestation-required") == "true" and ok,
          why if not ok else "attestation required + MAC secret provisioned")

    notes = []
    live = "map-smi" in order
    db_path = p.get("sas.binding.subscriber-db-path")
    db_export = "db" in order and bool(db_path)
    if not (live or db_export):
        notes.append("no usable IMSI->MSISDN source: add map-smi (MAP SendIMSI) or a "
                     "subscriber-db export — every EAP success would then fail closed")
    if db_export and p.check_files:
        f_ok, f_why = file_ok(db_path)
        if not f_ok:
            notes.append(f"subscriber export: {f_why}")
    r.add("PRO-34", "a real IMSI->MSISDN binding source is wired", not notes, "\n".join(notes))

    # Layered ADR M2: the subscriber exposure surface exists, but its client
    # authentication (mTLS + certificate-bound client credentials) only lands in M4 and
    # the durable ledger for ECS-side redemption in M3. Until then both stay off in prod.
    notes = []
    if (p.get("sas.exposure.enabled") or "false").strip().lower() != "false":
        notes.append("sas.exposure.enabled=true: /sas-subscriber-exposure has no client "
                     "authentication before migration step M4 — anyone reaching the port "
                     "could resolve IP->MSISDN")
    r.add("PRO-35", "subscriber exposure stays off until it is authenticated", not notes,
          "\n".join(notes) if notes else "exposure surface off until M4")
    return r


def quota_tenant_check(p: Profile) -> Report:
    """P-H2: sas.tenants.quota.<id> for an id that is not a real tenant silently
    does nothing (or worse, meters the wrong bank). Reject misbound quotas."""
    r = Report()
    tenants = set()
    for pair in p.get("sas.tenants.api-keys").split(","):
        if "=" in pair:
            tenants.add(pair.split("=", 1)[0].strip())
    prefix = "sas.tenants.quota."
    orphaned = [key for key in sorted(p.merged)
                if key.startswith(prefix) and key[len(prefix):].strip() not in tenants]
    r.add("PRO-28", "every per-tenant quota names a real tenant", not orphaned,
           "quota keys with no matching tenant: " + ", ".join(orphaned))
    return r


LAB_OTP_SENDERS = ("", "log", "lab", "memory", "pilot", "in-memory", "test-only")


def otp_surface_check(p: Profile) -> Report:
    """PRO-29 — the CAMARA OTP SMS fallback must not run on a lab sender.

    `/one-time-password-sms/v1` is the fallback branch banks land on when the
    silent path fails, so it is exactly the surface an attacker hammers (AIT /
    OTP bombing, SG.22). Two things make the current implementation lab-only:
    the sole delivery seam logs the composed SMS (with the cleartext OTP) and
    sends nothing — production delivery belongs to the operator SMSC or SGd
    (TS 29.338) behind Home Routing + a signalling firewall — and the attempt
    store is in-memory, so a restart drops live attempts. Enabled with a lab
    sender therefore fails closed here rather than at 3 a.m. in front of a bank.
    """
    r = Report()
    notes: list[str] = []
    if p.get("sas.otp.enabled") == "true":
        delivery = (p.get("sas.otp.sms-delivery") or "").strip().lower()
        if delivery in LAB_OTP_SENDERS:
            notes.append(
                f"sas.otp.enabled=true with sas.otp.sms-delivery="
                f"{delivery or 'unset'!r} — the only wired sender logs the OTP "
                f"and sends nothing")
            notes.append(
                "production OTP delivery needs an operator SMSC/SGd adapter "
                "(TS 29.338) plus a persistent attempt store; keep "
                "sas.otp.enabled=false until both exist")
    r.add("PRO-29", "OTP SMS fallback is off or on a real operator route", not notes,
          "\n".join(notes))
    return r


def auth_vector_check(p: Profile) -> Report:
    """PRO-30 — the auth-vector source must be a real operator path, never the lab.

    Under the D6/Shape S decision the entitlement service terminates EAP-AKA itself,
    so it fetches authentication vectors from the operator HSS over SWx. The lab
    backend mints *fabricated* vectors, which means an EAP server driven by them would
    "authenticate" anything — the one failure mode a silent-auth product must never
    ship. The entitlement path is opt-in today (the /ts43 surface does not exist yet),
    so `memory` is only a failure once the surface is actually enabled; until then the
    check still reports the value so the operator sees which one is wired.
    """
    r = Report()
    notes: list[str] = []
    source = (p.get("sas.transport.authvector") or "").strip().lower()
    entitlement_on = (p.get("sas.entitlement.enabled") or "true").strip().lower() == "true"
    if p.service == "ecs":
        ok = source in ("map", "jss7", "corsac")
        r.add("PRO-30", "entitlement auth vectors come from the operator, not the lab", ok,
              f"sas.transport.authvector={source or 'unset'} — legal in prod: map (MAP SAI, "
              "own HLR) | corsac (Diameter SWx MAR, own HSS); memory fabricates vectors that "
              "would 'authenticate' anyone")
        return r
    notes.append(f"sas.transport.authvector={source or 'unset'} "
                 f"(entitlement.enabled={entitlement_on})")

    if source in ("", "memory", "in-memory", "inmemory"):
        if entitlement_on:
            notes.append("the TS.43 entitlement service terminates EAP-AKA and must read real "
                         "vectors; set sas.transport.authvector=corsac and record the "
                         "operator vector-grade AuC access agreement (plan 2.1.2 R1) "
                         "before the /ts43 surface is enabled")
        else:
            notes.append("entitlement surface is off, so the fabricated lab vector source is "
                         "not reachable — acceptable while /ts43 does not exist")
        ok = not entitlement_on
    elif source == "corsac":
        swx = (p.get("sas.transport.swx") or "").strip().lower()
        if swx not in ("corsac",):
            notes.append("sas.transport.authvector=corsac reuses the SWx client, so "
                         "sas.transport.swx must be corsac too (one link, one correlator)")
        ok = swx == "corsac"
    else:
        notes.append(f"unknown auth-vector transport {source!r}; legal values are memory|corsac")
        ok = False

    r.add("PRO-30", "entitlement auth vectors come from the operator, not the lab", ok,
          "\n".join(notes))
    return r


def run(env: dict[str, str], overrides: dict[str, str | None] | None = None,
        check_files: bool = True, service: str = "camara") -> Report:
    profile = Profile(env, check_files=check_files, overrides=overrides, service=service)
    if service == "ecs":
        report = ecs_checks(profile)
        report.rows.extend(auth_vector_check(profile).rows)
        return report
    report = build_checks(profile)
    report.rows.extend(oauth_checks(profile).rows)
    report.rows.extend(quota_tenant_check(profile).rows)
    report.rows.extend(otp_surface_check(profile).rows)
    return report


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_env_args(pairs: list[str]) -> dict[str, str]:
    env = dict(os.environ)
    for pair in pairs or []:
        if "=" not in pair:
            raise SystemExit(f"--env expects KEY=VALUE, got {pair!r}")
        key, _, value = pair.partition("=")
        env[key.strip()] = value
    return env


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="SAS production preflight gate")
    ap.add_argument("--env", action="append", default=[], metavar="KEY=VALUE",
                    help="override an environment variable (repeatable)")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                    help="override a profile property (repeatable; for drills)")
    ap.add_argument("--no-file-checks", action="store_true",
                    help="skip on-disk existence checks (keystore, jss7 config, logs)")
    ap.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    ap.add_argument("--selftest", action="store_true",
                    help="run the gate against synthetic good/bad deployments")
    ap.add_argument("--service", choices=("camara", "ecs", "all"), default="camara",
                    help="which binary's prod profile to gate (default camara)")
    args = ap.parse_args(argv[1:])

    if args.selftest:
        return selftest()

    overrides = {}
    for pair in args.set or []:
        key, _, value = pair.partition("=")
        overrides[key.strip()] = value
    services = ("camara", "ecs") if args.service == "all" else (args.service,)
    report = Report()
    for svc in services:
        part = run(_parse_env_args(args.env), overrides=overrides,
                   check_files=not args.no_file_checks, service=svc)
        for row in part.rows:
            report.rows.append(dict(row, id=f"{svc}:{row['id']}") if len(services) > 1 else row)

    fails = report.failures()
    if args.json:
        json.dump({"checks": report.rows, "failed": sorted(report.failed_ids())},
                  sys.stdout, indent=2)
        sys.stdout.write("\n")
    else:
        print("== SAS production preflight (prod profile + environment) ==")
        report.print()
        print(f"\n== {len(report.rows) - len(fails)}/{len(report.rows)} pass, "
              f"{len(fails)} fail ==")
        if fails:
            print("REFUSING: this deployment is not production-safe. The app would start "
                  "but fail closed at runtime (reject-all auth / memory transport / lab DB).")
        else:
            print("OK: no lab default left in the prod profile for the checks listed above. "
                  "Real-signalling and edge-proxy items are tracked in sas-host/TODO.md.")
    return len(fails)


# ---------------------------------------------------------------------------
# selftest — proves the gate bites, without a JVM or a network
# ---------------------------------------------------------------------------

_JWK_XY = {"kty": "EC", "crv": "P-256",
           "x": "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
           "y": "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}
_D = "jpsQnnGQmL-YBIffH1136cspYG6-0iY7X1fCE9-E9LI"


def _jwk(kid: str, use: str, private: bool) -> str:
    jwk = dict(_JWK_XY, kid=kid, use=use)
    if private:
        jwk["d"] = _D
    return json.dumps(jwk)


def _good_deployment(tmp: Path) -> dict[str, str]:
    keystore = tmp / "sas-keystore.p12"
    keystore.write_bytes(b"not-a-real-keystore")
    truststore = tmp / "sas-truststore.p12"
    truststore.write_bytes(b"not-a-real-truststore")
    stack = tmp / "ss7-prod.json"
    stack.write_text(json.dumps({"Sccp": {"pointCode": "1-2-3"},
                                 "M3UA": {"asp": "sas-asp"}}), encoding="utf-8")
    cgnat = tmp / "cgnat-bindings.csv"
    cgnat.write_text("ts,msisdn,ip,port\n", encoding="utf-8")
    client_jwk = json.loads(_jwk("cobalt-2026", "sig", False))
    return {
        "SAS_TLS_KEYSTORE": str(keystore),
        "SAS_TLS_KEYSTORE_PASSWORD": "keystore-passphrase-long-enough",
        "SAS_TLS_KEYSTORE_KEY_PASSWORD": "key-passphrase-long-enough",
        "SAS_TLS_TRUSTSTORE": str(truststore),
        "SAS_TLS_TRUSTSTORE_PASSWORD": "truststore-passphrase-long",
        "SAS_SECURITY_HMAC_SECRET": "c0balt-issuer-hmac-secret-32chars-x",
        "SAS_SECURITY_ISSUER": "https://camara.et-network.net",
        "SAS_SECURITY_AUDIENCE": "https://camara.et-network.net/camara",
        "SAS_OAUTH_PUBLIC_BASE_URL": "https://camara.et-network.net",
        "SAS_OAUTH_SIGNING_JWK": _jwk("camara-sig-2026-10", "sig", True),
        "SAS_OAUTH_PAIRWISE_SECRET": "pairwise-subject-secret-32-chars-xx",
        "SAS_OAUTH_CLIENTS_JSON": json.dumps({"clients": [{
            "client_id": "cobalt", "token_endpoint_auth_method": "private_key_jwt",
            "jwks": {"keys": [client_jwk]}, "redirect_uris": ["https://cobalt.bank/cb"]}]}),
        "SAS_OPERATOR_TOKEN_ISSUERS_JSON": json.dumps({"issuers": [{
            "issuer": "https://ecs.et-network.net",
            "jwks_uri": "https://ecs.et-network.net/jwks.json"}]}),
        "SAS_OPERATOR_TOKEN_DECRYPTION_JWK": _jwk("camara-enc-2026-10", "enc", True),
        "SAS_TENANT_API_KEYS": "cobalt=ck_live_01f0e4b9a7c2d8a43b6e9157,hibret=ck_live_9a4b1e7d0f5c43a2986b715e",
        "SAS_ADMIN_KEY": "sas-admin-2f9c47be13ad86e0c5f7",
        "SAS_ADMIN_SESSION_HMAC_SECRET": "rotated-admin-session-hmac-64-chars-aaaa",
        "SAS_TRANSPORT_RESOLVER": "radius",
        "SAS_RADIUS_SECRET": "radius-shared-secret-not-default!",
        "SAS_CGNAT_LOG_PATH": str(cgnat),
        "SAS_JSS7_CONFIG": str(stack),
        "SAS_JSS7_HLR_GT": "251910000001",
        "SAS_JSS7_LOCAL_GT": "251910000099",
        "SAS_DIAMETER_PEER_HOST": "hss01.ethio-telecom.net",
        "SAS_DIAMETER_ORIGIN_HOST": "sas.et-network.net",
        "SAS_DIAMETER_REALM": "et-network.net",
        "SAS_DIAMETER_DESTINATION_HOST": "hss01.et-network.net",
        "SAS_DIAMETER_DESTINATION_REALM": "et-network.net",
        "SAS_DB_JDBC_URL": "jdbc:postgresql://db01.et-network.net:5432/sas",
        "SAS_DB_USER": "sas_app",
        "SAS_DB_PASSWORD": "db-passphrase-long-enough",
        "SAS_LOG_DIR": "/var/log/sas",
    }


def _good_ecs_deployment(tmp: Path) -> dict[str, str]:
    keystore = tmp / "ecs-keystore.p12"
    keystore.write_bytes(b"not-a-real-keystore")
    truststore = tmp / "ecs-truststore.p12"
    truststore.write_bytes(b"not-a-real-truststore")
    stack = tmp / "ss7-ecs.json"
    stack.write_text(json.dumps({"stackName": "ecs-ss7"}), encoding="utf-8")
    return {
        "ECS_TLS_KEYSTORE": str(keystore),
        "ECS_TLS_KEYSTORE_PASSWORD": "keystore-passphrase-long-enough",
        "ECS_TLS_KEYSTORE_KEY_PASSWORD": "key-passphrase-long-enough",
        "ECS_TLS_TRUSTSTORE": str(truststore),
        "ECS_TLS_TRUSTSTORE_PASSWORD": "truststore-passphrase-long",
        "ECS_TRANSPORT_AUTHVECTOR": "map",
        "ECS_BINDING_SOURCE_ORDER": "map-smi,db",
        "ECS_JSS7_CONFIG": str(stack),
        "ECS_JSS7_HLR_GT": "251910000001",
        "ECS_JSS7_LOCAL_GT": "251910000098",
        "ECS_ATTESTATION_SECRET": "aaa-attestation-shared-secret-32chr",
        "ECS_ISSUER": "https://ecs.et-network.net",
        "ECS_SIGNING_JWK": _jwk("ecs-sig-2026-10", "sig", True),
        "ECS_TOKEN_DECRYPTION_JWK": _jwk("ecs-enc-2026-10", "enc", True),
        "ECS_DB_JDBC_URL": "jdbc:postgresql://db.et-network.net:5432/ecs",
        "ECS_DB_USER": "sas_ecs",
        "ECS_DB_PASSWORD": "ecs-db-password-rotated-32-chars",
        "ECS_API_KEYS": "ecs-aaa-api-key-rotated-32-characters",
        "ECS_LOG_DIR": "/var/log/sas-ecs",
    }


GOOD = {"camara": _good_deployment, "ecs": _good_ecs_deployment}

MUTATIONS: dict[str, list[tuple[str, dict, set[str]]]] = {
    "camara": [
        ("auth off", {"sas.security.token-validation-enabled": "false"}, {"PRO-04"}),
        ("api-key enforcement off", {"sas.security.enforce-api-keys": "false"}, {"PRO-08"}),
        ("cleartext HTTP allowed", {"quarkus.http.insecure-requests": "enabled"}, {"PRO-11"}),
        ("mTLS off", {"quarkus.http.ssl.client-auth": "none"}, {"PRO-13"}),
        ("lab admin key", {"sas.admin.key": "change-me"}, {"PRO-14"}),
        ("insecure cookies", {"sas.admin.cookie-secure": "false"}, {"PRO-16"}),
        ("memory transport", {"sas.transport.resolver": "memory"}, {"PRO-17"}),
        ("loopback Diameter peer", {"sas.transport.diameter.peer-host": "127.0.0.1"}, {"PRO-20"}),
        ("missing jss7 stack file", {"sas.transport.jss7.config": "/nope/ss7.json"}, {"PRO-18"}),
        ("H2 lab database", {"quarkus.datasource.db-kind": "h2",
                             "quarkus.datasource.jdbc.url": "jdbc:h2:file:./data/sas-db"},
         {"PRO-25"}),
        ("lab keystore path", {"quarkus.http.ssl.certificate.key-store-file":
                               "src/main/resources/keystore.jks"}, {"PRO-12"}),
        ("over-pinned scope", {"sas.security.required-scopes": "number-verification:verify"},
         {"PRO-07"}),
        ("duplicate tenant key", {"sas.tenants.api-keys": "cobalt=demo-key"}, {"PRO-09"}),
        ("quota for unknown tenant", {"sas.tenants.quota.northbank": "1000"}, {"PRO-28"}),
        ("key dropped from profile", {"sas.oauth.signing-jwk": None}, {"PRO-02", "PRO-24"}),
        ("secret too short", {"SAS_OAUTH_PAIRWISE_SECRET": "abc"}, {"PRO-31"}),
        ("secret env var unset", {"SAS_OAUTH_PAIRWISE_SECRET": ""}, {"PRO-03", "PRO-31"}),
        ("assurance detail on", {"sas.api.assurance-detail-enabled": "true"}, {"PRO-27"}),
        ("relative log dir", {"SAS_LOG_DIR": "logs"}, {"PRO-26"}),
        ("lab OTP SMS sender", {"sas.otp.enabled": "true", "sas.otp.sms-delivery": "log"},
         {"PRO-29"}),
        ("HS256 bearer tokens re-enabled", {"sas.security.accept-hs256": "true"}, {"PRO-33"}),
        ("ephemeral AS signing key", {"sas.oauth.allow-ephemeral-signing-key": "true"},
         {"PRO-24"}),
        ("resource server not pinned to this AS", {"SAS_SECURITY_ISSUER": "https://idp.other"},
         {"PRO-06"}),
        ("OAuth client auth off", {"sas.oauth.require-client-auth": "false"}, {"PRO-31"}),
        ("lab operator-token decryption key",
         {"SAS_OPERATOR_TOKEN_DECRYPTION_JWK": _jwk("lab-camara-enc-x", "enc", True)},
         {"PRO-32"}),
        ("plain-HTTP issuer JWKS", {"sas.operator-token.allow-http-jwks": "true"}, {"PRO-32"}),
        ("operator tokens not encrypted", {"sas.operator-token.require-encryption": "false"},
         {"PRO-32"}),
    ],
    "ecs": [
        ("lab auth vector source", {"ECS_TRANSPORT_AUTHVECTOR": "memory"}, {"PRO-30"}),
        ("unencrypted operator tokens", {"sas.ecs.token.allow-unencrypted": "true"}, {"PRO-22"}),
        ("unsigned entitlement tokens", {"sas.entitlement.token-format": "hmac"}, {"PRO-22"}),
        ("attestation off", {"sas.entitlement.issue-attestation-required": "false"}, {"PRO-23"}),
        ("lab decryption key",
         {"ECS_TOKEN_DECRYPTION_JWK": _jwk("lab-ecs-enc-x", "enc", True)}, {"PRO-22"}),
        ("public key configured for decryption",
         {"ECS_TOKEN_DECRYPTION_JWK": _jwk("ecs-enc-2026-10", "enc", False)}, {"PRO-22"}),
        ("ledger on an H2 file", {"quarkus.datasource.db-kind": "h2"}, {"PRO-22"}),
        ("redemption handed back to a CAMARA server", {"sas.ecs.token.redeem-at-ecs": "false"},
         {"PRO-22"}),
        ("ephemeral ECS signing key", {"sas.ecs.allow-ephemeral-signing-key": "true"},
         {"PRO-22"}),
        ("no binding source", {"ECS_BINDING_SOURCE_ORDER": "db"}, {"PRO-34"}),
        ("ecs api keys off", {"sas.security.enforce-api-keys": "false"}, {"PRO-08"}),
        ("ecs cleartext HTTP", {"quarkus.http.insecure-requests": "enabled"}, {"PRO-11"}),
        ("ecs missing jss7 stack", {"ECS_JSS7_CONFIG": "/nope/ss7-ecs.json"}, {"PRO-18"}),
        ("ecs signing key dropped", {"sas.ecs.signing-jwk": None}, {"PRO-02", "PRO-22"}),
        ("legacy exchange left on", {"sas.entitlement.legacy-exchange-enabled": "true"},
         {"PRO-22"}),
        ("exposure surface on before M4", {"sas.exposure.enabled": "true"}, {"PRO-35"}),
    ],
}


def verify(scenario: str | None = None, service: str = "camara") -> tuple[bool, str]:
    """Run one named mutation (or the baseline) and report whether it is caught.

    scenario=None → a fully provisioned prod deployment must pass every check.
    otherwise     → the named mutation must make its expected PRO ids fail.
    Used by --selftest here and by the preflight gates in run_hardness.py.
    """
    with tempfile.TemporaryDirectory(prefix="sas-preflight-") as tmpdir:
        env = GOOD[service](Path(tmpdir))
        if scenario is None:
            report = run(env, service=service)
            bad = sorted(report.failed_ids())
            if bad:
                return False, (f"[{service}] baseline deployment should pass but failed: "
                               + ", ".join(bad))
            return True, f"[{service}] complete prod deployment passes all {len(report.rows)} checks"
        for name, overrides, expected in MUTATIONS[service]:
            if name != scenario:
                continue
            mutation_env = dict(env)
            overrides_only = {}
            for key, value in overrides.items():
                if key.isupper() and value is not None:
                    mutation_env[key] = value
                else:
                    overrides_only[key] = value
            report = run(mutation_env, overrides=overrides_only, service=service)
            caught = expected & report.failed_ids()
            ok = caught == expected
            return ok, (f"[{service}] {name}: expected {sorted(expected)} -> caught "
                        f"{sorted(caught) or 'NOTHING'}")
        return False, f"unknown {service} scenario {scenario!r}"


def selftest() -> int:
    undetected = 0
    total = 0
    print("== SAS production preflight — selftest ==")
    for service in ("camara", "ecs"):
        scenarios: list[str | None] = [None] + [name for name, _, _ in MUTATIONS[service]]
        for name in scenarios:
            total += 1
            ok, detail = verify(name, service)
            if not ok:
                undetected += 1
            label = f"{service}:" + ("baseline" if name is None else name)
            print(f"  [{'PASS' if ok else 'FAIL'}] {label.ljust(44)} {detail}")
    print(f"\n== {total - undetected}/{total} scenarios detected ==")
    return undetected


if __name__ == "__main__":
    sys.exit(main(sys.argv))
