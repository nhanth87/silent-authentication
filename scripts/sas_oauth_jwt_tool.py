#!/usr/bin/env python3
# Silent Auth SAS — Restlink (Ethiopia).
# CAMARA ICM OAuth lab helper for private_key_jwt assertions. Python 3. R&D only — never production.
#
# Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
import argparse
import base64
import json
import os
import subprocess
import time
import uuid


def b64url(data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def compact_json(value):
    return json.dumps(value, separators=(",", ":"), sort_keys=False)


def keygen(args):
    os.makedirs(args.out, exist_ok=True)
    key = os.path.join(args.out, "client.key")
    clients = os.path.join(args.out, "clients.json")
    subprocess.run(
        [
            "openssl", "genpkey",
            "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:2048",
            "-out", key,
        ],
        check=True,
        capture_output=True,
    )
    modulus = subprocess.run(
        ["openssl", "rsa", "-in", key, "-noout", "-modulus"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip().split("=", 1)[1]
    n = bytes.fromhex(modulus)
    e = (65537).to_bytes(3, "big")
    kid = uuid.uuid4().hex[:8]
    registry = {
        "clients": [
            {
                "client_id": args.client_id,
                "token_endpoint_auth_method": "private_key_jwt",
                "grant_types": [
                    "urn:openid:params:grant-type:ciba",
                    "client_credentials",
                    "urn:ietf:params:oauth:grant-type:jwt-bearer",
                ],
                "scopes": [
                    "number-verification:verify",
                    "number-verification:device-phone-number:read",
                    "sim-swap:check",
                    "sim-swap:retrieve-date",
                    "one-time-password-sms:send-validate",
                ],
                "purposes": [
                    "dpv:FraudPreventionAndDetection"
                ],
                "jwks": {
                    "keys": [
                        {
                            "kty": "RSA",
                            "use": "sig",
                            "alg": "RS256",
                            "kid": kid,
                            "n": b64url(n),
                            "e": b64url(e),
                        }
                    ]
                },
            }
        ]
    }
    with open(clients, "w", encoding="utf-8") as fh:
        json.dump(registry, fh, indent=2)
    os.chmod(key, 0o600)
    print(f"private_key={key}")
    print(f"clients_json={clients}")
    print(f"kid={kid}")


def sign(args):
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    if args.kid:
        header["kid"] = args.kid
    payload = {
        "iss": args.client_id,
        "sub": args.sub or args.client_id,
        "aud": args.aud,
        "iat": now,
        "exp": now + args.ttl,
        "jti": args.jti or uuid.uuid4().hex,
    }
    if args.scope:
        payload["scope"] = args.scope
    signing_input = b64url(compact_json(header)) + "." + b64url(compact_json(payload))
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", args.key],
        input=signing_input.encode("utf-8"),
        check=True,
        capture_output=True,
    ).stdout
    print(signing_input + "." + b64url(signature))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    kg = sub.add_parser("keygen")
    kg.add_argument("--client-id", required=True)
    kg.add_argument("--out", required=True)
    kg.set_defaults(func=keygen)

    sg = sub.add_parser("sign")
    sg.add_argument("--key", required=True)
    sg.add_argument("--client-id", required=True)
    sg.add_argument("--aud", required=True)
    sg.add_argument("--sub")
    sg.add_argument("--scope")
    sg.add_argument("--kid")
    sg.add_argument("--jti")
    sg.add_argument("--ttl", type=int, default=60)
    sg.set_defaults(func=sign)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
