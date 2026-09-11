#!/usr/bin/env python3
# Silent Auth SAS — Restlink (Ethiopia).
# Mock GSMA Open Gateway for CAMARA lab smoke tests. Python 3. R&D only — never production.
#
# Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
from __future__ import annotations

import base64
import json
import os
import re
import secrets
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.getenv("MOCK_HOST", "127.0.0.1")
PORT = int(os.getenv("MOCK_PORT", "18099"))
TEST_NUMBER = os.getenv("MOCK_TEST_NUMBER", "+251911111111")
UNKNOWN_NUMBER = os.getenv("MOCK_UNKNOWN_NUMBER", "+251999999999")
LATEST_SIM_CHANGE = os.getenv("MOCK_LATEST_SIM_CHANGE", "2026-08-30T18:45:17.227Z")
OTP_ATTEMPTS: dict[str, str] = {}
LOCK = threading.Lock()


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def make_token(scope: str, three_legged: bool = False) -> str:
    header = {"alg": "none", "typ": "JWT"}
    payload = {"scope": scope, "token_use": "access"}
    if three_legged:
        payload["phone_number"] = TEST_NUMBER
    return (
        b64url(json.dumps(header, separators=(",", ":")).encode())
        + "."
        + b64url(json.dumps(payload, separators=(",", ":")).encode())
        + ".mock-signature"
    )


def b64url_decode(segment: str) -> bytes:
    padding = "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode(segment + padding)


def decode_token(token: str) -> dict:
    parts = token.split(".")
    if len(parts) < 2:
        return {}
    try:
        payload = json.loads(b64url_decode(parts[1]).decode())
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def token_scopes(claims: dict) -> set[str]:
    value = claims.get("scope") or claims.get("scp")
    if isinstance(value, str):
        return set(value.split())
    if isinstance(value, list):
        return {str(item) for item in value}
    return set()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        return

    def _send(self, status: int, body=None, headers=None):
        payload = b""
        if body is not None:
            payload = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8", errors="replace")
        if not raw:
            return {}
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}

    def _read_form(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8", errors="replace")
        return {key: values[0] for key, values in urllib.parse.parse_qs(raw).items()}

    def _auth(self, required_scope: str | None = None):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            self._send(401, {"status": 401, "code": "UNAUTHENTICATED", "message": "missing bearer token"})
            return None
        claims = decode_token(header[len("Bearer ") :])
        if not claims:
            self._send(401, {"status": 401, "code": "UNAUTHENTICATED", "message": "invalid token"})
            return None
        if required_scope and required_scope not in token_scopes(claims):
            self._send(
                403,
                {"status": 403, "code": "PERMISSION_DENIED", "message": f"missing scope {required_scope}"},
            )
            return None
        return claims

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/camara/number-verification/v2/device-phone-number":
            claims = self._auth("number-verification:device-phone-number:read")
            if claims is None:
                return
            self._send(200, {"devicePhoneNumber": TEST_NUMBER})
            return
        if path == "/health":
            self._send(200, {"status": "ok"})
            return
        self._send(404, {"status": 404, "code": "NOT_FOUND", "message": "unknown path"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/oauth/token":
            self.token()
        elif path == "/camara/sim-swap/v2/check":
            self.simswap_check()
        elif path == "/camara/sim-swap/v2/retrieve-date":
            self.simswap_retrieve_date()
        elif path == "/camara/one-time-password-sms/v1/send-code":
            self.otp_send()
        elif path == "/camara/one-time-password-sms/v1/validate-code":
            self.otp_validate()
        elif path == "/camara/number-verification/v2/verify":
            self.nv_verify()
        else:
            self._send(404, {"status": 404, "code": "NOT_FOUND", "message": "unknown path"})

    def token(self):
        form = self._read_form()
        if form.get("grant_type") != "client_credentials":
            self._send(400, {"error": "unsupported_grant_type"})
            return
        scope = form.get("scope", "")
        three_legged = "number-verification" in scope
        token = make_token(scope, three_legged=three_legged)
        self._send(200, {"access_token": token, "token_type": "Bearer", "expires_in": 300, "scope": scope})

    def simswap_check(self):
        claims = self._auth("sim-swap:check")
        if claims is None:
            return
        body = self._read_json()
        number = body.get("phoneNumber") or claims.get("phone_number")
        if not number:
            self._send(422, {"status": 422, "code": "MISSING_IDENTIFIER", "message": "phoneNumber required"})
            return
        if number == UNKNOWN_NUMBER:
            self._send(404, {"status": 404, "code": "IDENTIFIER_NOT_FOUND", "message": "no evidence"})
            return
        max_age = body.get("maxAge", 240)
        try:
            max_age = int(max_age)
        except (TypeError, ValueError):
            self._send(400, {"status": 400, "code": "OUT_OF_RANGE", "message": "maxAge invalid"})
            return
        if max_age < 1 or max_age > 2400:
            self._send(400, {"status": 400, "code": "OUT_OF_RANGE", "message": "maxAge out of range"})
            return
        self._send(200, {"swapped": max_age >= 2400})

    def simswap_retrieve_date(self):
        claims = self._auth("sim-swap:retrieve-date")
        if claims is None:
            return
        body = self._read_json()
        number = body.get("phoneNumber") or claims.get("phone_number")
        if not number:
            self._send(422, {"status": 422, "code": "MISSING_IDENTIFIER", "message": "phoneNumber required"})
            return
        if number == UNKNOWN_NUMBER:
            self._send(404, {"status": 404, "code": "IDENTIFIER_NOT_FOUND", "message": "no evidence"})
            return
        self._send(200, {"latestSimChange": LATEST_SIM_CHANGE})

    def otp_send(self):
        claims = self._auth("one-time-password-sms:send-validate")
        if claims is None:
            return
        body = self._read_json()
        number = body.get("phoneNumber")
        message = body.get("message")
        if not number or not message:
            self._send(400, {"status": 400, "code": "INVALID_ARGUMENT", "message": "phoneNumber and message required"})
            return
        bound = claims.get("phone_number")
        if bound and bound != number:
            self._send(403, {"status": 403, "code": "PERMISSION_DENIED", "message": "phoneNumber does not match token"})
            return
        if "{{code}}" not in message or len(message) > 160:
            self._send(400, {"status": 400, "code": "INVALID_ARGUMENT", "message": "message must contain {{code}} and be <=160"})
            return
        code = f"{secrets.randbelow(1000000):06d}"
        auth_id = str(secrets.token_hex(16))
        with LOCK:
            OTP_ATTEMPTS[auth_id] = code
        text = message.replace("{{code}}", code)
        print(f"[MOCK-SMS — NOT SENT] to={number} chars={len(text)} text=\"{text}\"", flush=True)
        self._send(200, {"authenticationId": auth_id})

    def otp_validate(self):
        claims = self._auth("one-time-password-sms:send-validate")
        if claims is None:
            return
        body = self._read_json()
        auth_id = body.get("authenticationId")
        code = body.get("code")
        if not auth_id or not code:
            self._send(400, {"status": 400, "code": "INVALID_ARGUMENT", "message": "authenticationId and code required"})
            return
        with LOCK:
            expected = OTP_ATTEMPTS.get(auth_id)
        if expected is None:
            self._send(404, {"status": 404, "code": "NOT_FOUND", "message": "unknown authenticationId"})
            return
        if expected != code:
            self._send(400, {"status": 400, "code": "ONE_TIME_PASSWORD_SMS.INVALID_OTP", "message": "invalid OTP"})
            return
        with LOCK:
            OTP_ATTEMPTS.pop(auth_id, None)
        self._send(204)

    def nv_verify(self):
        claims = self._auth("number-verification:verify")
        if claims is None:
            return
        body = self._read_json()
        bound = claims.get("phone_number")
        if bound:
            if body.get("phoneNumber"):
                self._send(
                    422,
                    {"status": 422, "code": "UNNECESSARY_IDENTIFIER", "message": "token is already bound"},
                )
                return
            self._send(200, {"devicePhoneNumberVerified": True})
            return
        number = body.get("phoneNumber")
        if not number:
            self._send(422, {"status": 422, "code": "MISSING_IDENTIFIER", "message": "phoneNumber required"})
            return
        self._send(200, {"devicePhoneNumberVerified": number == TEST_NUMBER})


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"MOCK_OPEN_GATEWAY_LISTENING http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
