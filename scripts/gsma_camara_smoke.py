#!/usr/bin/env python3
# Silent Auth SAS — Restlink (Ethiopia).
# CAMARA/GSMA lab smoke helper. Python 3. R&D only — never production.
#
# Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
from __future__ import annotations

import argparse
import base64
import csv
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

DEFAULT_REPO = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_LOCAL_BASE = "http://localhost:8085"
DEFAULT_LOCAL_TOKEN = "lab"
DEFAULT_LOCAL_NUMBER = "+251911111111"
DEFAULT_LOCAL_OTP_LOG = DEFAULT_REPO / "dist/logs/sas.log"
DEFAULT_LOCAL_CDR = DEFAULT_REPO / "dist/logs/sas.cdr"
DEFAULT_GSMA_CLIENT_ID = ""
DEFAULT_OTP_MESSAGE = "{{code}} is your Restlink code"
DEFAULT_SIMSWAP_SCOPE = "sim-swap:check sim-swap:retrieve-date"
DEFAULT_OTP_SCOPE = "one-time-password-sms:send-validate"
DEFAULT_NV_SCOPE = "number-verification:verify"
DEFAULT_DISCOVERY_SCOPE = "number-verification:device-phone-number:read"
DEFAULT_TESTS = {
    "local": "simswap,otp,cdr",
    "gsma": "token,simswap,nv",
    "compare": "simswap,nv",
}
ALL_TESTS = {
    "local": "nv,nv-discovery,simswap,otp,cdr",
    "gsma": "token,simswap,otp,nv,nv-discovery",
    "compare": "token,simswap,otp,nv,nv-discovery,cdr",
}
PHONE_RE = re.compile(r"^\+?\d{10,15}$")
LAB_SMS_RE = re.compile(r"(?:LAB-SMS|MOCK-SMS).*?text=\"(\d{4,8})")
CDR_FIELDS = [
    "time",
    "correlationId",
    "msisdn",
    "operation",
    "status",
    "detail",
    "user",
    "connector",
    "tenantId",
]
PHONE_CLAIMS = {"phone_number", "phoneNumber", "msisdn", "mobile_number"}
COMPARE_DIRECT_FIELDS = ("status", "swapped", "devicePhoneNumberVerified", "code")
COMPARE_PRESENCE_FIELDS = ("latestSimChange", "devicePhoneNumber", "authenticationId")
LOCAL_ONLY_FIELDS = ("decision", "assurance", "fallbackReason", "reqId")


@dataclass
class Config:
    name: str
    api_root: str | None
    token_url: str | None
    client_id: str | None
    client_secret: str | None
    access_token: str | None
    tokens: dict[str, str | None]
    scopes: dict[str, str]
    test_number: str | None
    otp_message: str
    simswap_maxage: int | None
    purpose: str | None
    token_auth: str
    token_mode: str
    timeout: float
    dry_run: bool
    allow_send: bool
    assurance_detail: bool
    src_ip: str
    src_port: str
    access_tech: str
    otp_log: pathlib.Path | None
    cdr_file: pathlib.Path | None
    otp_code: str | None = None


def first_env(*names: str, default=None):
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return default


def first_int_env(*names: str, default=None):
    value = first_env(*names)
    if value is None:
        return default
    return int(value)


def first_float_env(*names: str, default=None):
    value = first_env(*names)
    if value is None:
        return default
    return float(value)


def load_env_file(path: str | os.PathLike[str] | None, override: bool = False) -> pathlib.Path | None:
    if not path:
        return None
    file_path = pathlib.Path(str(path)).expanduser()
    if not file_path.exists():
        raise ValueError(f"env file not found: {file_path}")
    for raw_line in file_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if override or not os.getenv(key):
            os.environ[key] = value
    return file_path


def first_path(*values) -> pathlib.Path | None:
    for value in values:
        if value:
            path = pathlib.Path(str(value)).expanduser()
            return path
    return None


def join_url(root: str | None, path: str) -> str:
    if path.startswith("http://") or path.startswith("https://"):
        return path
    if not root:
        return "<API_ROOT>" + path
    return root.rstrip("/") + path


def mask_phone(value: str) -> str:
    if len(value) <= 6:
        return "*" * len(value)
    stars = max(2, len(value) - 6)
    return value[:4] + "*" * stars + value[-2:]


def mask_authentication_id(value: str) -> str:
    if len(value) <= 8:
        return "<masked>"
    return value[:8] + "..."


def is_sensitive_key(key: str) -> bool:
    lowered = key.lower()
    return any(
        token in lowered
        for token in ("token", "secret", "authorization", "password", "credential")
    )


def mask_value(value, *, mask_code=False, mask_message=False):
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            lowered = str(key).lower()
            if is_sensitive_key(lowered):
                out[key] = "<redacted>"
            elif lowered == "code" and mask_code:
                out[key] = "<masked>"
            elif lowered == "message" and mask_message:
                out[key] = "<masked-message>"
            elif lowered == "authenticationid":
                out[key] = mask_authentication_id(str(item)) if item else item
            elif lowered in ("phonenumber", "devicephonenumber", "msisdn"):
                out[key] = mask_phone(str(item)) if item else item
            else:
                out[key] = mask_value(item, mask_code=mask_code, mask_message=mask_message)
        return out
    if isinstance(value, list):
        return [mask_value(item, mask_code=mask_code, mask_message=mask_message) for item in value]
    if isinstance(value, str) and PHONE_RE.match(value):
        return mask_phone(value)
    return value


def b64url_decode(segment: str) -> bytes:
    padding = "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode((segment + padding).encode())


def jwt_claims(token: str | None) -> dict:
    if not token:
        return {}
    parts = token.split(".")
    if len(parts) < 2:
        return {}
    try:
        payload = json.loads(b64url_decode(parts[1]).decode("utf-8", errors="replace"))
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def token_scopes(claims: dict) -> set[str]:
    for key in ("scope", "scp", "scopes"):
        value = claims.get(key)
        if isinstance(value, str):
            return set(value.split())
        if isinstance(value, list):
            return {str(item) for item in value}
    return set()


def has_phone_claim(claims: dict) -> bool:
    return any(claims.get(key) for key in PHONE_CLAIMS)


def http_call(method, url, headers=None, json_body=None, form_body=None, timeout=20.0):
    data = None
    all_headers = dict(headers or {})
    if json_body is not None:
        data = json.dumps(json_body).encode()
        all_headers.setdefault("Content-Type", "application/json")
    elif form_body is not None:
        data = urllib.parse.urlencode(form_body).encode()
        all_headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
    request = urllib.request.Request(url, data=data, method=method)
    for key, value in all_headers.items():
        request.add_header(key, value)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            text = response.read().decode("utf-8", errors="replace")
            error = None
    except urllib.error.HTTPError as exc:
        status = exc.code
        text = exc.read().decode("utf-8", errors="replace")
        error = None
    except Exception as exc:
        status = None
        text = ""
        error = f"{type(exc).__name__}: {exc}"
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    parsed = None
    if text:
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            parsed = text[:2000]
    return status, parsed, text, elapsed_ms, error


def skipped(test, cfg, reason, request_body=None):
    return {
        "test": test,
        "target": cfg.name,
        "state": "SKIPPED",
        "reason": reason,
        "requestBody": mask_value(request_body, mask_code=True, mask_message=True) if request_body is not None else None,
    }


def dry_result(test, cfg, method, url, request_body=None, reason="dry-run"):
    return {
        "test": test,
        "target": cfg.name,
        "state": "DRY_RUN",
        "method": method,
        "url": url,
        "reason": reason,
        "requestBody": mask_value(request_body, mask_code=True, mask_message=True) if request_body is not None else None,
    }


def perform(
    cfg,
    test,
    method,
    url,
    headers=None,
    json_body=None,
    form_body=None,
    display_body=None,
    timeout=None,
):
    timeout = timeout or cfg.timeout
    shown = display_body if display_body is not None else json_body
    if shown is None and form_body is not None:
        shown = form_body
    if cfg.dry_run:
        return dry_result(test, cfg, method, url, shown)
    status, parsed, text, elapsed_ms, error = http_call(
        method,
        url,
        headers=headers,
        json_body=json_body,
        form_body=form_body,
        timeout=timeout,
    )
    result = {
        "test": test,
        "target": cfg.name,
        "method": method,
        "url": url,
        "status": status,
        "durationMs": elapsed_ms,
        "requestBody": mask_value(shown, mask_code=True, mask_message=True) if shown is not None else None,
        "responseBody": mask_value(parsed if parsed is not None else text),
        "_rawBody": parsed,
    }
    if error:
        result["state"] = "ERROR"
        result["reason"] = error
    else:
        result["state"] = "HTTP"
    return result


class TokenError(RuntimeError):
    pass


def resolve_bound(cfg, api, source, claims):
    if cfg.token_mode == "3-legged":
        return True
    if cfg.token_mode == "2-legged":
        return False
    if has_phone_claim(claims):
        return True
    if api == "nv" and cfg.name == "gsma" and source in ("explicit", "generic"):
        return True
    return False


def acquire_token(cfg, api, scope, explicit, allow_client_credentials=True):
    token = explicit or cfg.access_token
    source = "explicit" if explicit else ("generic" if cfg.access_token else None)
    if token:
        claims = jwt_claims(token)
        return {
            "token": token,
            "source": source,
            "scope": scope,
            "claims": claims,
            "scopes": token_scopes(claims),
            "bound": resolve_bound(cfg, api, source, claims),
        }
    if cfg.dry_run:
        return {
            "token": "DRY_RUN_TOKEN",
            "source": "dry-run",
            "scope": scope,
            "claims": {},
            "scopes": set(scope.split()) if scope else set(),
            "bound": cfg.token_mode == "3-legged",
        }
    if not allow_client_credentials:
        return {
            "token": None,
            "source": "client-credentials-not-allowed",
            "scope": scope,
            "claims": {},
            "scopes": set(),
            "bound": False,
        }
    if not (cfg.token_url and cfg.client_id and cfg.client_secret):
        return {
            "token": None,
            "source": "missing-token-url-or-client-credentials",
            "scope": scope,
            "claims": {},
            "scopes": set(),
            "bound": False,
        }
    cache = getattr(cfg, "_token_cache", None)
    if cache is None:
        cache = {}
        setattr(cfg, "_token_cache", cache)
    key = (scope, cfg.token_auth)
    if key in cache:
        token = cache[key]
        claims = jwt_claims(token)
        return {
            "token": token,
            "source": "client_credentials",
            "scope": scope,
            "claims": claims,
            "scopes": token_scopes(claims),
            "bound": resolve_bound(cfg, api, "client_credentials", claims),
        }
    record_form = {"grant_type": "client_credentials"}
    if scope:
        record_form["scope"] = scope
    actual_form = dict(record_form)
    headers = {"Accept": "application/json"}
    if cfg.token_auth == "basic":
        raw = f"{cfg.client_id}:{cfg.client_secret}".encode()
        headers["Authorization"] = "Basic " + base64.b64encode(raw).decode()
    else:
        actual_form["client_id"] = cfg.client_id
        actual_form["client_secret"] = cfg.client_secret
    status, parsed, text, elapsed_ms, error = http_call(
        "POST",
        cfg.token_url,
        headers=headers,
        form_body=actual_form,
        timeout=cfg.timeout,
    )
    if error:
        raise TokenError(error)
    if status and status >= 400:
        raise TokenError(f"token endpoint returned HTTP {status}: {text[:500]}")
    if not isinstance(parsed, dict) or not parsed.get("access_token"):
        raise TokenError(f"token endpoint did not return access_token: {text[:500]}")
    token = parsed["access_token"]
    cache[key] = token
    claims = jwt_claims(token)
    return {
        "token": token,
        "source": "client_credentials",
        "scope": scope,
        "claims": claims,
        "scopes": token_scopes(claims),
        "bound": resolve_bound(cfg, api, "client_credentials", claims),
        "tokenStatus": status,
        "durationMs": elapsed_ms,
    }


def base_headers(cfg, plan, correlator):
    headers = {
        "Accept": "application/json",
        "x-correlator": correlator,
    }
    if plan and plan.get("token"):
        headers["Authorization"] = "Bearer " + plan["token"]
    return headers


def local_verify_headers(cfg):
    headers = {
        "X-Sas-Amr": "mobile",
        "X-Sas-Src-Ip": cfg.src_ip,
        "X-Sas-Src-Port": str(cfg.src_port),
        "X-Sas-Access-Tech": cfg.access_tech,
    }
    if cfg.assurance_detail:
        headers["X-Sas-Assurance-Detail"] = "true"
    return headers


def simswap_body(cfg, plan, endpoint):
    body = {}
    if not plan.get("bound"):
        body["phoneNumber"] = cfg.test_number
    if endpoint == "/sim-swap/v2/check" and cfg.simswap_maxage is not None:
        body["maxAge"] = cfg.simswap_maxage
    if cfg.purpose:
        body["purpose"] = cfg.purpose
    return body


def read_new_otp_code(path: pathlib.Path | None, offset: int, timeout: float = 5.0):
    if not path:
        return None, offset
    deadline = time.time() + timeout
    while time.time() < deadline:
        if path.exists():
            with path.open("r", encoding="utf-8", errors="ignore") as handle:
                handle.seek(offset)
                text = handle.read()
                new_offset = handle.tell()
            matches = LAB_SMS_RE.findall(text)
            if matches:
                return matches[-1], new_offset
        time.sleep(0.2)
    return None, offset


def log_offset(path: pathlib.Path | None) -> int:
    if path and path.exists():
        return path.stat().st_size
    return 0


def validate_otp_message(message: str):
    if "{{code}}" not in message:
        raise ValueError("OTP message must contain {{code}}")
    if len(message) > 160:
        raise ValueError(f"OTP message too long: {len(message)} > 160")


def run_token(cfg, results):
    scope = cfg.scopes["simswap"]
    explicit = cfg.tokens.get("simswap")
    if explicit:
        claims = jwt_claims(explicit)
        results.append(
            {
                "test": "token",
                "target": cfg.name,
                "state": "OK",
                "reason": "explicit access token supplied; no token endpoint call",
                "scope": scope,
                "source": "explicit",
                "tokenScopes": sorted(token_scopes(claims)),
                "bound": resolve_bound(cfg, "token", "explicit", claims),
                "responseBody": "<access-token-redacted>",
            }
        )
        return
    if cfg.dry_run:
        results.append(
            dry_result(
                "token",
                cfg,
                "POST",
                cfg.token_url or "<TOKEN_URL>",
                {"grant_type": "client_credentials", "scope": scope},
            )
        )
        return
    try:
        plan = acquire_token(cfg, "token", scope, None)
    except TokenError as exc:
        results.append(
            {
                "test": "token",
                "target": cfg.name,
                "state": "ERROR",
                "reason": str(exc),
            }
        )
        return
    if not plan.get("token"):
        results.append(skipped("token", cfg, plan.get("source", "missing-token")))
        return
    results.append(
        {
            "test": "token",
            "target": cfg.name,
            "state": "OK",
            "status": plan.get("tokenStatus", 200),
            "durationMs": plan.get("durationMs"),
            "source": plan.get("source"),
            "scope": scope,
            "tokenScopes": sorted(plan.get("scopes", [])),
            "bound": plan.get("bound"),
            "responseBody": "<access-token-redacted>",
        }
    )


def run_simswap(cfg, results, correlators):
    endpoints = [
        ("simswap-check", "/sim-swap/v2/check"),
        ("simswap-retrieve-date", "/sim-swap/v2/retrieve-date"),
    ]
    scope = cfg.scopes["simswap"]
    try:
        plan = acquire_token(cfg, "simswap", scope, cfg.tokens.get("simswap"))
    except TokenError as exc:
        for test, _ in endpoints:
            results.append({"test": test, "target": cfg.name, "state": "ERROR", "reason": str(exc)})
        return
    if not plan.get("token"):
        for test, _ in endpoints:
            results.append(skipped(test, cfg, plan.get("source", "missing-token")))
        return
    for test, endpoint in endpoints:
        body = simswap_body(cfg, plan, endpoint)
        if not plan.get("bound") and not cfg.test_number:
            results.append(skipped(test, cfg, "missing test number for 2-legged request", body))
            continue
        correlator = f"{cfg.name[:4]}-{test}-{int(time.time() * 1000)}"
        url = join_url(cfg.api_root, endpoint)
        headers = base_headers(cfg, plan, correlator)
        result = perform(cfg, test, "POST", url, headers=headers, json_body=body)
        correlators.append(correlator)
        result["correlator"] = correlator
        result["tokenSource"] = plan.get("source")
        result["tokenBound"] = plan.get("bound")
        results.append(result)


def run_otp(cfg, results, correlators):
    scope = cfg.scopes["otp"]
    if cfg.name == "gsma" and not cfg.allow_send and not cfg.dry_run:
        results.append(
            skipped("otp-send", cfg, "GSMA OTP may send SMS; rerun with --allow-send")
        )
        results.append(skipped("otp-validate", cfg, "depends on otp-send"))
        return
    try:
        validate_otp_message(cfg.otp_message)
    except ValueError as exc:
        results.append({"test": "otp-send", "target": cfg.name, "state": "ERROR", "reason": str(exc)})
        results.append(skipped("otp-validate", cfg, "invalid OTP message"))
        return
    if not cfg.test_number:
        results.append(skipped("otp-send", cfg, "missing test number"))
        results.append(skipped("otp-validate", cfg, "missing test number"))
        return
    try:
        plan = acquire_token(cfg, "otp", scope, cfg.tokens.get("otp"))
    except TokenError as exc:
        results.append({"test": "otp-send", "target": cfg.name, "state": "ERROR", "reason": str(exc)})
        results.append(skipped("otp-validate", cfg, "token failure"))
        return
    if not plan.get("token"):
        results.append(skipped("otp-send", cfg, plan.get("source", "missing-token")))
        results.append(skipped("otp-validate", cfg, plan.get("source", "missing-token")))
        return

    offset = log_offset(cfg.otp_log)
    send_body = {"phoneNumber": cfg.test_number, "message": cfg.otp_message}
    correlator = f"{cfg.name[:4]}-otp-send-{int(time.time() * 1000)}"
    url = join_url(cfg.api_root, "/one-time-password-sms/v1/send-code")
    headers = base_headers(cfg, plan, correlator)
    send_result = perform(cfg, "otp-send", "POST", url, headers=headers, json_body=send_body)
    correlators.append(correlator)
    send_result["correlator"] = correlator
    send_result["tokenSource"] = plan.get("source")
    send_result["tokenBound"] = plan.get("bound")
    results.append(send_result)

    authentication_id = None
    if not cfg.dry_run and isinstance(send_result.get("_rawBody"), dict):
        authentication_id = send_result["_rawBody"].get("authenticationId")
    if cfg.dry_run:
        authentication_id = "DRY_RUN_AUTH_ID"

    code = cfg.otp_code
    if not code and cfg.dry_run:
        code = "DRY_RUN_CODE"
    if not code and cfg.otp_log:
        code, _ = read_new_otp_code(cfg.otp_log, offset)
    if not authentication_id:
        results.append(skipped("otp-validate", cfg, "no authenticationId returned"))
        return
    if not code:
        reason = "no OTP code supplied"
        if cfg.name == "local":
            reason += "; set --otp-code or --otp-log pointing at redirected SAS console output"
        else:
            reason += "; read SMS from test device or set --otp-code"
        results.append(skipped("otp-validate", cfg, reason))
        return

    validate_body = {"authenticationId": authentication_id, "code": code}
    correlator = f"{cfg.name[:4]}-otp-validate-{int(time.time() * 1000)}"
    url = join_url(cfg.api_root, "/one-time-password-sms/v1/validate-code")
    headers = base_headers(cfg, plan, correlator)
    validate_result = perform(
        cfg,
        "otp-validate",
        "POST",
        url,
        headers=headers,
        json_body=validate_body,
        display_body={"authenticationId": mask_authentication_id(str(authentication_id)), "code": "<masked>"},
    )
    correlators.append(correlator)
    validate_result["correlator"] = correlator
    validate_result["codeSource"] = "cli/env" if cfg.otp_code else "log"
    results.append(validate_result)


def run_nv(cfg, results, correlators):
    scope = cfg.scopes["nv"]
    explicit = cfg.tokens.get("nv")
    allow_client_credentials = cfg.token_mode == "2-legged" or cfg.name == "local"
    try:
        plan = acquire_token(cfg, "nv", scope, explicit, allow_client_credentials)
    except TokenError as exc:
        results.append({"test": "nv-verify", "target": cfg.name, "state": "ERROR", "reason": str(exc)})
        return
    if not plan.get("token"):
        if cfg.name == "gsma":
            reason = "NV requires a user-supplied 3-legged token; set GSMA_NV_TOKEN or complete the sandbox auth flow"
        else:
            reason = plan.get("source", "missing-token")
        results.append(skipped("nv-verify", cfg, reason))
        return
    if plan.get("bound"):
        body = {}
    else:
        if not cfg.test_number:
            results.append(skipped("nv-verify", cfg, "missing test number for 2-legged request"))
            return
        body = {"phoneNumber": cfg.test_number}
    correlator = f"{cfg.name[:4]}-nv-verify-{int(time.time() * 1000)}"
    url = join_url(cfg.api_root, "/number-verification/v2/verify")
    headers = base_headers(cfg, plan, correlator)
    if cfg.name == "local":
        headers.update(local_verify_headers(cfg))
    result = perform(cfg, "nv-verify", "POST", url, headers=headers, json_body=body)
    result["correlator"] = correlator
    result["tokenSource"] = plan.get("source")
    result["tokenBound"] = plan.get("bound")
    req_id = None
    if isinstance(result.get("_rawBody"), dict):
        req_id = result["_rawBody"].get("reqId")
    if req_id:
        correlators.append(req_id)
        result["cdrCorrelator"] = req_id
    elif cfg.name == "local" and not cfg.dry_run:
        result["cdrNote"] = "local VERIFY CDR keys on reqId; use --assurance-detail to expose it"
    results.append(result)


def run_nv_discovery(cfg, results, correlators):
    scope = cfg.scopes["discovery"]
    explicit = cfg.tokens.get("discovery") or cfg.tokens.get("nv")
    allow_client_credentials = cfg.token_mode == "2-legged" or cfg.name == "local"
    try:
        plan = acquire_token(cfg, "nv-discovery", scope, explicit, allow_client_credentials)
    except TokenError as exc:
        results.append({"test": "nv-discovery", "target": cfg.name, "state": "ERROR", "reason": str(exc)})
        return
    if not plan.get("token"):
        results.append(skipped("nv-discovery", cfg, plan.get("source", "missing-token")))
        return
    correlator = f"{cfg.name[:4]}-nv-discovery-{int(time.time() * 1000)}"
    url = join_url(cfg.api_root, "/number-verification/v2/device-phone-number")
    headers = base_headers(cfg, plan, correlator)
    if cfg.name == "local":
        headers.update(local_verify_headers(cfg))
    result = perform(cfg, "nv-discovery", "GET", url, headers=headers)
    result["correlator"] = correlator
    result["tokenSource"] = plan.get("source")
    result["tokenBound"] = plan.get("bound")
    req_id = None
    if isinstance(result.get("_rawBody"), dict):
        req_id = result["_rawBody"].get("reqId")
    if req_id:
        correlators.append(req_id)
        result["cdrCorrelator"] = req_id
    results.append(result)


def read_cdr_rows(path: pathlib.Path):
    rows = []
    if not path.exists():
        return rows
    with path.open(newline="", encoding="utf-8", errors="ignore") as handle:
        for row in csv.reader(handle):
            if not row or row[0] == "time":
                continue
            if len(row) >= len(CDR_FIELDS):
                rows.append(dict(zip(CDR_FIELDS, row)))
    return rows


def run_cdr(cfg, results, correlators, otp_code):
    if cfg.name != "local":
        results.append(skipped("cdr-privacy", cfg, "CDR privacy check applies to local SAS only"))
        return
    if not cfg.cdr_file:
        results.append(skipped("cdr-privacy", cfg, "missing --cdr-file"))
        return
    if cfg.dry_run:
        results.append(dry_result("cdr-privacy", cfg, "READ", str(cfg.cdr_file)))
        return
    if not correlators:
        results.append(skipped("cdr-privacy", cfg, "no API correlators were executed"))
        return

    deadline = time.time() + 5
    rows = []
    selected = []
    wanted = set(correlators)
    while time.time() < deadline:
        rows = read_cdr_rows(cfg.cdr_file)
        selected = [row for row in rows if row.get("correlationId") in wanted]
        if len(selected) >= len(wanted):
            break
        time.sleep(0.2)

    text = cfg.cdr_file.read_text(encoding="utf-8", errors="ignore") if cfg.cdr_file.exists() else ""
    violations = []
    if cfg.test_number and cfg.test_number in text:
        violations.append("raw test MSISDN found in CDR")
    if otp_code and otp_code in text:
        violations.append("OTP plaintext found in CDR")
    if cfg.otp_message and cfg.otp_message in text:
        violations.append("OTP message template found in CDR")

    missing = sorted(wanted - {row.get("correlationId") for row in selected})
    state = "OK"
    reason = f"matched {len(selected)}/{len(wanted)} CDR rows"
    if missing:
        state = "FAILED"
        reason = f"missing CDR correlators: {missing}"
    if violations:
        state = "FAILED"
        reason = "; ".join(violations)
    results.append(
        {
            "test": "cdr-privacy",
            "target": cfg.name,
            "state": state,
            "reason": reason,
            "file": str(cfg.cdr_file),
            "rows": len(selected),
            "operations": sorted({row.get("operation", "") for row in selected}),
        }
    )


def expand_tests_named(name, requested):
    if not requested:
        requested = DEFAULT_TESTS[name]
    if requested.strip().lower() == "all":
        requested = ALL_TESTS[name]
    items = [item.strip() for item in requested.split(",") if item.strip()]
    valid = set(ALL_TESTS[name].split(",")) | {"token"}
    unknown = [item for item in items if item not in valid]
    if unknown:
        raise ValueError(f"unknown tests for {name}: {unknown}; valid: {sorted(valid)}")
    return items


def expand_tests(cfg, requested):
    return expand_tests_named(cfg.name, requested)


def build_config(args, name=None):
    name = name or args.target
    if name == "local":
        api_root = (
            args.local_base
            or args.api_root
            or first_env("LOCAL_BASE", "LOCAL_API_ROOT", default=DEFAULT_LOCAL_BASE)
        )
        access_token = args.access_token or first_env("LOCAL_TOKEN", "LOCAL_ACCESS_TOKEN", default=DEFAULT_LOCAL_TOKEN)
        test_number = (
            args.local_test_number
            or args.test_number
            or first_env("LOCAL_NUMBER", "LOCAL_TEST_NUMBER", default=DEFAULT_LOCAL_NUMBER)
        )
        otp_code = args.local_otp_code or args.otp_code or first_env("LOCAL_OTP_CODE", "OTP_CODE")
        otp_log = first_path(args.otp_log, first_env("LOCAL_OTP_LOG"), DEFAULT_LOCAL_OTP_LOG)
        cdr_file = first_path(args.cdr_file, first_env("LOCAL_CDR_FILE"), DEFAULT_LOCAL_CDR)
        token_url = args.token_url or first_env("LOCAL_TOKEN_URL")
        client_id = args.client_id or first_env("LOCAL_CLIENT_ID")
        client_secret = args.client_secret or first_env("LOCAL_CLIENT_SECRET")
        tokens = {
            "simswap": args.simswap_token or first_env("LOCAL_SIMSWAP_TOKEN"),
            "otp": args.otp_token or first_env("LOCAL_OTP_TOKEN"),
            "nv": args.nv_token or first_env("LOCAL_NV_TOKEN"),
            "discovery": args.discovery_token or first_env("LOCAL_DISCOVERY_TOKEN"),
        }
        scopes = {
            "simswap": first_env("LOCAL_SIMSWAP_SCOPE", default=DEFAULT_SIMSWAP_SCOPE),
            "otp": first_env("LOCAL_OTP_SCOPE", default=DEFAULT_OTP_SCOPE),
            "nv": first_env("LOCAL_NV_SCOPE", default=DEFAULT_NV_SCOPE),
            "discovery": first_env("LOCAL_DISCOVERY_SCOPE", default=DEFAULT_DISCOVERY_SCOPE),
        }
    else:
        api_root = (
            args.gsma_api_root
            or args.api_root
            or first_env("GSMA_API_ROOT", "API_ROOT")
        )
        access_token = args.access_token or first_env("GSMA_ACCESS_TOKEN", "ACCESS_TOKEN")
        test_number = (
            args.gsma_test_number
            or args.test_number
            or first_env("GSMA_TEST_NUMBER", "TEST_NUMBER")
        )
        otp_code = args.gsma_otp_code or args.otp_code or first_env("GSMA_OTP_CODE", "OTP_CODE")
        otp_log = first_path(args.otp_log, first_env("GSMA_OTP_LOG"))
        cdr_file = first_path(args.cdr_file, first_env("GSMA_CDR_FILE"))
        token_url = args.token_url or first_env("GSMA_TOKEN_URL", "TOKEN_URL")
        client_id = args.client_id or first_env("GSMA_CLIENT_ID", "CLIENT_ID", default=DEFAULT_GSMA_CLIENT_ID)
        client_secret = args.client_secret or first_env("GSMA_CLIENT_SECRET", "CLIENT_SECRET")
        tokens = {
            "simswap": args.simswap_token or first_env("GSMA_SIMSWAP_TOKEN", "SIMSWAP_TOKEN"),
            "otp": args.otp_token or first_env("GSMA_OTP_TOKEN", "OTP_TOKEN"),
            "nv": args.nv_token or first_env("GSMA_NV_TOKEN", "NV_TOKEN"),
            "discovery": args.discovery_token or first_env("GSMA_DISCOVERY_TOKEN", "DISCOVERY_TOKEN"),
        }
        scopes = {
            "simswap": first_env("GSMA_SIMSWAP_SCOPE", default=DEFAULT_SIMSWAP_SCOPE),
            "otp": first_env("GSMA_OTP_SCOPE", default=DEFAULT_OTP_SCOPE),
            "nv": first_env("GSMA_NV_SCOPE", default=DEFAULT_NV_SCOPE),
            "discovery": first_env("GSMA_DISCOVERY_SCOPE", default=DEFAULT_DISCOVERY_SCOPE),
        }

    return Config(
        name=name,
        api_root=api_root,
        token_url=token_url,
        client_id=client_id,
        client_secret=client_secret,
        access_token=access_token,
        tokens=tokens,
        scopes=scopes,
        test_number=test_number,
        otp_message=args.otp_message
        or first_env("OTP_MESSAGE", default=DEFAULT_OTP_MESSAGE),
        simswap_maxage=args.simswap_maxage
        if args.simswap_maxage is not None
        else first_int_env("SIMSWAP_MAXAGE", "GSMA_SIMSWAP_MAXAGE"),
        purpose=args.purpose or first_env("PURPOSE", "GSMA_PURPOSE"),
        token_auth=args.token_auth or first_env("TOKEN_AUTH", default="form"),
        token_mode=args.token_mode or first_env("TOKEN_MODE", default="auto"),
        timeout=args.timeout if args.timeout is not None else first_float_env("TIMEOUT", "GSMA_TIMEOUT", default=20.0),
        dry_run=args.dry_run,
        allow_send=args.allow_send,
        assurance_detail=args.assurance_detail,
        src_ip=args.src_ip or first_env("SRC_IP", default="10.20.30.40"),
        src_port=args.src_port or first_env("SRC_PORT", default="55555"),
        access_tech=args.access_tech or first_env("ACCESS_TECH", default="LTE"),
        otp_log=otp_log,
        cdr_file=cdr_file,
        otp_code=otp_code,
    )


def run_suite(cfg, tests):
    results = []
    correlators = []
    for test in tests:
        if test == "token":
            run_token(cfg, results)
        elif test == "simswap":
            run_simswap(cfg, results, correlators)
        elif test == "otp":
            run_otp(cfg, results, correlators)
        elif test == "nv":
            run_nv(cfg, results, correlators)
        elif test == "nv-discovery":
            run_nv_discovery(cfg, results, correlators)
        elif test == "cdr":
            run_cdr(cfg, results, correlators, cfg.otp_code)
    return results, correlators


def index_results(results):
    index = {}
    for result in results:
        test = result.get("test")
        if test:
            index[test] = result
    return index


def result_fingerprint(result):
    state = result.get("state")
    fingerprint = {
        "state": state,
        "status": result.get("status"),
        "tokenBound": result.get("tokenBound"),
    }
    if result.get("reason"):
        fingerprint["reason"] = result.get("reason")
    body = result.get("responseBody")
    if isinstance(body, dict):
        for key in COMPARE_DIRECT_FIELDS:
            if key in body:
                fingerprint[key] = body[key]
        for key in COMPARE_PRESENCE_FIELDS:
            if key in body:
                fingerprint[key] = "<present>"
        for key in LOCAL_ONLY_FIELDS:
            if key in body:
                fingerprint[key] = "<present>" if key != "decision" else body.get(key)
    return fingerprint


def compare_pair(test, gsma_result, local_result):
    if gsma_result is None or local_result is None:
        return {
            "test": test,
            "verdict": "INCOMPLETE",
            "reason": "missing result from one target",
            "gsma": result_fingerprint(gsma_result) if gsma_result else None,
            "local": result_fingerprint(local_result) if local_result else None,
        }

    gsma_fp = result_fingerprint(gsma_result)
    local_fp = result_fingerprint(local_result)
    non_comparable_states = {"SKIPPED", "DRY_RUN", "ERROR"}
    if gsma_fp.get("state") in non_comparable_states or local_fp.get("state") in non_comparable_states:
        return {
            "test": test,
            "verdict": "NOT_COMPARED",
            "reason": gsma_fp.get("reason") or local_fp.get("reason") or "one side did not execute",
            "gsma": gsma_fp,
            "local": local_fp,
        }

    diffs = []
    for key in COMPARE_DIRECT_FIELDS:
        gsma_value = gsma_fp.get(key)
        local_value = local_fp.get(key)
        if key == "status" and gsma_value != local_value:
            diffs.append(f"status: gsma={gsma_value} local={local_value}")
        elif key != "status" and (key in gsma_fp or key in local_fp) and gsma_value != local_value:
            diffs.append(f"{key}: gsma={gsma_value} local={local_value}")
    for key in COMPARE_PRESENCE_FIELDS:
        if (key in gsma_fp) != (key in local_fp):
            diffs.append(f"{key} presence differs")

    verdict = "MATCH" if not diffs else "DIFF"
    return {
        "test": test,
        "verdict": verdict,
        "diffs": diffs,
        "gsma": gsma_fp,
        "local": local_fp,
    }


def compare_results(tests, gsma_results, local_results):
    gsma_index = index_results(gsma_results)
    local_index = index_results(local_results)
    comparisons = []
    expanded = []
    for test in tests:
        if test == "simswap":
            expanded.extend(["simswap-check", "simswap-retrieve-date"])
        elif test == "otp":
            expanded.extend(["otp-send", "otp-validate"])
        elif test == "nv":
            expanded.append("nv-verify")
        elif test == "cdr":
            expanded.append("cdr-privacy")
        else:
            expanded.append(test)
    for test in expanded:
        gsma_result = gsma_index.get(test)
        local_result = local_index.get(test)
        if test == "cdr-privacy":
            comparisons.append(
                {
                    "test": test,
                    "verdict": local_result.get("state", "MISSING") if local_result else "MISSING",
                    "reason": "local-only CDR privacy check",
                    "local": result_fingerprint(local_result) if local_result else None,
                }
            )
            continue
        if test == "token":
            comparisons.append(
                {
                    "test": test,
                    "verdict": "NOT_COMPARED",
                    "reason": "token issuance is provider-specific",
                    "gsma": result_fingerprint(gsma_result) if gsma_result else None,
                    "local": result_fingerprint(local_result) if local_result else None,
                }
            )
            continue
        comparisons.append(compare_pair(test, gsma_result, local_result))
    return comparisons


def print_compare(comparisons, gsma_results, local_results, verbose=False):
    if verbose:
        print("=== GSMA RESULTS ===")
        print_human(gsma_results)
        print("\n=== LOCAL RESULTS ===")
        print_human(local_results)
        print("\n=== COMPARISON ===")
    for item in comparisons:
        verdict = item.get("verdict")
        print(f"\n[{item.get('test')}] {verdict}")
        for side in ("gsma", "local"):
            data = item.get(side)
            if data:
                compact = {
                    key: value
                    for key, value in data.items()
                    if key not in ("reason", "assurance", "reqId", "fallbackReason")
                }
                print(f"  {side}: {json.dumps(compact, ensure_ascii=False, separators=(',', ':'))}")
        if item.get("diffs"):
            for diff in item["diffs"]:
                print(f"  diff: {diff}")
        if item.get("reason"):
            print(f"  reason: {item['reason']}")

    counts = {}
    for item in comparisons:
        verdict = item.get("verdict", "UNKNOWN")
        counts[verdict] = counts.get(verdict, 0) + 1
    print("\nCOMPARE_SUMMARY " + " ".join(f"{key}={value}" for key, value in sorted(counts.items())))


def sanitize_results(results):
    for result in results:
        result.pop("_rawBody", None)


def print_human(results):
    for result in results:
        state = result.get("state")
        status = result.get("status")
        label = str(status) if status is not None else state
        print(f"\n[{result.get('test')}] {label} ({result.get('target')})")
        if result.get("method") and result.get("url"):
            print(f"  {result['method']} {result['url']}")
        if result.get("correlator"):
            print(f"  correlator={result['correlator']}")
        if result.get("cdrCorrelator"):
            print(f"  cdrCorrelator={result['cdrCorrelator']}")
        if result.get("cdrNote"):
            print(f"  cdrNote={result['cdrNote']}")
        if result.get("requestBody") is not None:
            print(f"  request={json.dumps(result['requestBody'], ensure_ascii=False, separators=(',', ':'))}")
        if "responseBody" in result and result.get("responseBody") is not None:
            body = result["responseBody"]
            if isinstance(body, (dict, list)):
                body = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
            print(f"  response={str(body)[:1200]}")
        if result.get("reason"):
            print(f"  reason={result['reason']}")
        if result.get("tokenScopes"):
            print(f"  tokenScopes={','.join(result['tokenScopes'])}")
        if result.get("operations"):
            print(f"  operations={','.join(result['operations'])}")

    counts = {}
    for result in results:
        counts[result.get("state", "UNKNOWN")] = counts.get(result.get("state", "UNKNOWN"), 0) + 1
    print("\nSUMMARY " + " ".join(f"{key}={value}" for key, value in sorted(counts.items())))


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="gsma_camara_smoke.py",
        description="CAMARA smoke tests for GSMA Open Gateway sandbox or local SAS.",
    )
    parser.add_argument("--target", choices=["local", "gsma", "compare"], default=None)
    parser.add_argument("--env-file", default=None)
    parser.add_argument("--override-env-file", action="store_true")
    parser.add_argument("--tests", default=None)
    parser.add_argument("--api-root", default=None)
    parser.add_argument("--gsma-api-root", default=None)
    parser.add_argument("--local-base", default=None)
    parser.add_argument("--token-url", default=None)
    parser.add_argument("--client-id", default=None)
    parser.add_argument("--client-secret", default=None)
    parser.add_argument("--access-token", default=None)
    parser.add_argument("--simswap-token", default=None)
    parser.add_argument("--otp-token", default=None)
    parser.add_argument("--nv-token", default=None)
    parser.add_argument("--discovery-token", default=None)
    parser.add_argument("--test-number", default=None)
    parser.add_argument("--gsma-test-number", default=None)
    parser.add_argument("--local-test-number", default=None)
    parser.add_argument("--simswap-maxage", type=int, default=None)
    parser.add_argument("--purpose", default=None)
    parser.add_argument("--otp-message", default=None)
    parser.add_argument("--otp-code", default=None)
    parser.add_argument("--gsma-otp-code", default=None)
    parser.add_argument("--local-otp-code", default=None)
    parser.add_argument("--otp-log", default=None)
    parser.add_argument("--cdr-file", default=None)
    parser.add_argument("--token-mode", choices=["auto", "2-legged", "3-legged"], default=None)
    parser.add_argument("--token-auth", choices=["form", "basic"], default=None)
    parser.add_argument("--allow-send", action="store_true")
    parser.add_argument("--assurance-detail", action="store_true")
    parser.add_argument("--src-ip", default=None)
    parser.add_argument("--src-port", default=None)
    parser.add_argument("--access-tech", default=None)
    parser.add_argument("--timeout", type=float, default=None)
    parser.add_argument("--report", default=None)
    parser.add_argument("--require-match", action="store_true")
    parser.add_argument("--verbose-results", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        load_env_file(args.env_file or os.getenv("ENV_FILE"), args.override_env_file)
        args.target = args.target or os.getenv("TARGET", "gsma")
        args.tests = args.tests or os.getenv("TESTS")
        if args.target == "compare":
            gsma_cfg = build_config(args, "gsma")
            local_cfg = build_config(args, "local")
            tests = expand_tests_named("compare", args.tests)
            gsma_tests = [test for test in tests if test in set(ALL_TESTS["gsma"].split(",")) | {"token"}]
            local_tests = [test for test in tests if test in set(ALL_TESTS["local"].split(",")) | {"token"}]
            gsma_results, _ = run_suite(gsma_cfg, gsma_tests)
            local_results, _ = run_suite(local_cfg, local_tests)
            sanitize_results(gsma_results)
            sanitize_results(local_results)
            comparisons = compare_results(tests, gsma_results, local_results)
            report = {
                "mode": "compare",
                "tests": tests,
                "gsma": gsma_results,
                "local": local_results,
                "comparison": comparisons,
            }
            if args.report:
                report_path = pathlib.Path(args.report).expanduser()
                report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            if args.json:
                print(json.dumps(report, indent=2, ensure_ascii=False))
            else:
                print_compare(comparisons, gsma_results, local_results, args.verbose_results)
            states = {result.get("state") for result in gsma_results + local_results}
            verdicts = {item.get("verdict") for item in comparisons}
            if "ERROR" in states or "FAILED" in states or "FAILED" in verdicts:
                return 2
            if args.require_match and "DIFF" in verdicts:
                return 4
            return 0

        cfg = build_config(args)
        tests = expand_tests(cfg, args.tests)
    except ValueError as exc:
        print(f"CONFIG_ERROR: {exc}", file=sys.stderr)
        return 3

    results, _ = run_suite(cfg, tests)
    sanitize_results(results)
    if args.report:
        report_path = pathlib.Path(args.report).expanduser()
        report_path.write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.json:
        print(json.dumps(results, indent=2, ensure_ascii=False))
    else:
        print_human(results)

    states = {result.get("state") for result in results}
    if "ERROR" in states or "FAILED" in states:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
