#!/usr/bin/env python3
"""The QR hand-off request contract, recorded against the packaged dists.

Every row is a request shape a real client can send. The point is not that the
statuses look tidy — it is that nothing is left unstated: each shape either has
a defined answer, or is listed here as a known framework boundary.

Run with the lab stack up:
  ./scripts/qr-lab.sh up live
  python3 scripts/qr-request-matrix.py
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

GATE = "http://127.0.0.1:8085"
ECS = "http://127.0.0.1:8095"
MSISDN = "+251911111111"
IMSI = "655010000000001"


def call(method, url, body=None, headers=None, form=None):
    hdrs = dict(headers or {})
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


def operator_token():
    st, txt = call("POST", f"{ECS}/entitlement/issue",
                   {"msisdn": MSISDN, "imsi": IMSI, "eapMethod": "EAP-AKA"})
    if st != 200:
        sys.exit(f"cannot issue an operator token: {st} {txt[:200]}")
    return json.loads(txt)["token"]


def nv_token(scope="dpv:FraudPreventionAndDetection number-verification:verify"):
    op = operator_token()
    st, txt = call("POST", f"{GATE}/bc-authorize",
                   form={"login_hint": f"operatortoken:{op}", "scope": scope,
                         "client_id": "bank-lab"})
    if st != 200:
        return None, f"bc-authorize {st} {txt[:120]}"
    auth_req = json.loads(txt).get("auth_req_id")
    st, txt = call("POST", f"{GATE}/token",
                   form={"grant_type": "urn:openid:params:grant-type:ciba",
                         "auth_req_id": auth_req, "client_id": "bank-lab"})
    if st != 200:
        return None, f"token {st} {txt[:120]}"
    return json.loads(txt)["access_token"], None


def open_with_operator_token(phone=None, token=None):
    tok = token or operator_token()
    body = {} if phone is None else {"phoneNumber": phone}
    st, txt = call("POST", f"{GATE}/qr-session/v1", body,
                   {"Authorization": f"Bearer operatortoken:{tok}"})
    if st != 200:
        return None, (st, txt)
    return json.loads(txt)["sessionId"], None


def open_with_bearer(phone=None, scope=None):
    at, err = nv_token() if scope is None else nv_token(scope)
    if not at:
        return None, (0, err)
    body = {} if phone is None else {"phoneNumber": phone}
    st, txt = call("POST", f"{GATE}/qr-session/v1", body,
                   {"Authorization": f"Bearer {at}"})
    if st != 200:
        return None, (st, txt)
    return json.loads(txt)["sessionId"], None


ROWS = []


def row(group, label, expect, got_status, got_body):
    ROWS.append((group, label, expect, got_status, brief(got_body)))


def brief(txt):
    try:
        j = json.loads(txt)
    except Exception:
        return f"<no body> ({len(txt or '')} bytes)" if not txt else (txt[:70])
    if isinstance(j, dict):
        if "code" in j:
            return f'{j["code"]} | {str(j.get("message"))[:62]}'
        if "state" in j:
            return (f'state={j["state"]} verified={j.get("devicePhoneNumberVerified")}'
                    f' reason={j.get("fallbackReason")}')
        if "sessionId" in j:
            return f'sessionId={str(j["sessionId"])[:8]}…'
    return json.dumps(j)[:70]


def check(group, label, expect, method, url, expect_status=None, **kw):
    st, txt = call(method, url, **kw)
    if expect_status is not None and st != expect_status:
        label += f"  !! expected {expect_status}"
    row(group, label, expect, st, txt)
    return st, txt


print("A. POST /qr-session/v1 — the bank opens the hand-off")
at, err = nv_token()
assert at, err
check("A", "NV bearer (the production path)", "200", "POST",
      f"{GATE}/qr-session/v1", 200, body={},
      headers={"Authorization": f"Bearer {at}"})
check("A", "the SAME NV bearer replayed", "401 single-use", "POST",
      f"{GATE}/qr-session/v1", 401, body={},
      headers={"Authorization": f"Bearer {at}"})
check("A", "NV bearer without number-verification:verify", "403", "POST",
      f"{GATE}/qr-session/v1", 403, body={},
      headers={"Authorization":
               f"Bearer {nv_token('dpv:FraudPreventionAndDetection sim-swap:check')[0]}"})
op_replay = operator_token()
check("A", "operator token (TS.43 / Wi-Fi bank session)", "200", "POST",
      f"{GATE}/qr-session/v1", 200, body={},
      headers={"Authorization": f"Bearer operatortoken:{op_replay}"})
check("A", "operator token + matching phoneNumber", "200", "POST",
      f"{GATE}/qr-session/v1", 200, body={"phoneNumber": MSISDN},
      headers={"Authorization": f"Bearer operatortoken:{operator_token()}"})
check("A", "operator token + a DIFFERENT phoneNumber", "422", "POST",
      f"{GATE}/qr-session/v1", 422, body={"phoneNumber": "+251999999999"},
      headers={"Authorization": f"Bearer operatortoken:{operator_token()}"})
check("A", "the SAME operator token replayed", "401 single-use", "POST",
      f"{GATE}/qr-session/v1", 401, body={},
      headers={"Authorization": f"Bearer operatortoken:{op_replay}"})
check("A", "no Authorization", "401", "POST", f"{GATE}/qr-session/v1", 401, body={})
check("A", "empty Authorization", "401", "POST", f"{GATE}/qr-session/v1", 401,
      body={}, headers={"Authorization": ""})
check("A", "garbage bearer", "401", "POST", f"{GATE}/qr-session/v1", 401,
      body={}, headers={"Authorization": "Bearer garbage"})
check("A", "Basic scheme", "401", "POST", f"{GATE}/qr-session/v1", 401,
      body={}, headers={"Authorization": "Basic YmFuazpiYW5rOnB3"})
check("A", "phoneNumber not E.164", "400", "POST", f"{GATE}/qr-session/v1", 400,
      body={"phoneNumber": "0911111111"},
      headers={"Authorization": f"Bearer operatortoken:{operator_token()}"})
check("A", "phoneNumber in national format", "400 (E.164 is required)", "POST",
      f"{GATE}/qr-session/v1", 400, body={"phoneNumber": "0911111111"},
      headers={"Authorization": f"Bearer operatortoken:{operator_token()}"})
check("A", "phoneNumber empty string", "200 (discovery)", "POST",
      f"{GATE}/qr-session/v1", 200, body={"phoneNumber": ""},
      headers={"Authorization": f"Bearer operatortoken:{operator_token()}"})
check("A", "no body at all", "200 (discovery)", "POST", f"{GATE}/qr-session/v1",
      200, headers={"Authorization": f"Bearer operatortoken:{operator_token()}"})
check("A", "illegal x-correlator", "400", "POST", f"{GATE}/qr-session/v1", 400,
      body={}, headers={"Authorization": f"Bearer operatortoken:{operator_token()}",
                        "x-correlator": "bad correlator with spaces"})
# raw bodies have to be put on the wire byte for byte
for label, raw, expect in (
        ("unknown request property", '{"phoneNumber":"+251911111111","bogus":1}',
         "400 with a body"),
        ("body is a JSON array", '[{"phoneNumber":"+251911111111"}]',
         "400, framework boundary: no body"),
        ("body truncated mid-value", '{"phoneNumber":', "400, framework boundary: no body"),
        ("wrong scalar type", '{"phoneNumber":{"x":1}}', "400, framework boundary: no body"),
        ("body is a bare JSON string", '"just-a-string"', "400, framework boundary: no body")):
    import urllib.request as _u
    req = _u.Request(f"{GATE}/qr-session/v1", data=raw.encode(), method="POST",
                     headers={"Authorization": f"Bearer operatortoken:{operator_token()}",
                              "Content-Type": "application/json"})
    try:
        with _u.urlopen(req, timeout=30) as r:
            st, txt = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        st, txt = e.code, e.read().decode()
    row("A", f"raw body: {label}", expect, st, txt)
check("A", "GET instead of POST", "405", "GET", f"{GATE}/qr-session/v1", 405)
check("A", "/camara-prefixed alias", "200", "POST",
      f"{GATE}/camara/qr-session/v1", 200, body={},
      headers={"Authorization": f"Bearer operatortoken:{operator_token()}"})

print("B. GET /qr-session/v1/{id} — the bank polls")
sid, _ = open_with_bearer(MSISDN)
check("B", "owning tenant polls a PENDING session", "200 PENDING", "GET",
      f"{GATE}/qr-session/v1/{sid}")
check("B", "unknown but well-formed UUID", "404", "GET",
      f"{GATE}/qr-session/v1/3f2504e0-4f89-11d3-9a0c-0305e82c3301", 404)
check("B", "id that is not a UUID", "404", "GET", f"{GATE}/qr-session/v1/nope", 404)
check("B", "path traversal in the id", "404", "GET",
      f"{GATE}/qr-session/v1/..%2F..%2Fetc", 404)
check("B", "POST instead of GET", "405", "POST", f"{GATE}/qr-session/v1/{sid}", 405,
      body={})

print("C. POST /qr-session/v1/{id}/claim — the phone claims")
s, _ = open_with_bearer(MSISDN)
check("C", "cellular LTE, full evidence", "200 (verdict)", "POST",
      f"{GATE}/qr-session/v1/{s}/claim", 200, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40", "X-Sas-Src-Port": "55555"})
check("C", "the same session claimed again", "409", "POST",
      f"{GATE}/qr-session/v1/{s}/claim", 409, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.41"})
check("C", "unknown session", "404", "POST",
      f"{GATE}/qr-session/v1/11111111-2222-3333-4444-555555555555/claim", 404,
      body={"accessTech": "LTE"})
check("C", "id that is not a UUID", "404", "POST",
      f"{GATE}/qr-session/v1/nope/claim", 404, body={"accessTech": "LTE"})
disc, _ = open_with_bearer(None)
check("C", "discovery session, phone gives no number", "422", "POST",
      f"{GATE}/qr-session/v1/{disc}/claim", 422, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
disc2, _ = open_with_bearer(None)
check("C", "discovery, phone supplies the number (fresh session)", "200", "POST",
      f"{GATE}/qr-session/v1/{disc2}/claim", 200,
      body={"msisdn": MSISDN, "accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40", "X-Sas-Src-Port": "55555"})
s2, _ = open_with_bearer(MSISDN)
check("C", "phone restates the bank's number", "200", "POST",
      f"{GATE}/qr-session/v1/{s2}/claim", 200, body={"msisdn": MSISDN, "accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
s3, _ = open_with_bearer(MSISDN)
check("C", "phone substitutes a DIFFERENT number", "422", "POST",
      f"{GATE}/qr-session/v1/{s3}/claim", 422,
      body={"msisdn": "+251933333333", "accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
s4, _ = open_with_bearer(MSISDN)
check("C", "a number that cannot be normalised", "400, not silently ignored", "POST",
      f"{GATE}/qr-session/v1/{s4}/claim", 400, body={"msisdn": "0911111111", "accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
s5, _ = open_with_bearer(MSISDN)
check("C", "Wi-Fi without an operator token", "400", "POST",
      f"{GATE}/qr-session/v1/{s5}/claim", 400, body={"accessTech": "WIFI"},
      headers={"X-Sas-Src-Ip": "192.168.1.55"})
s6, _ = open_with_bearer(MSISDN)
check("C", "FIXED access", "400", "POST",
      f"{GATE}/qr-session/v1/{s6}/claim", 400, body={"accessTech": "FIXED"},
      headers={"X-Sas-Src-Ip": "203.0.113.9"})
s7, _ = open_with_bearer(MSISDN)
check("C", "bearer name outside the contract", "400", "POST",
      f"{GATE}/qr-session/v1/{s7}/claim", 400, body={"accessTech": "HOLOGRAM"},
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
s8, _ = open_with_bearer(MSISDN)
check("C", "accessTech in lower case", "200", "POST",
      f"{GATE}/qr-session/v1/{s8}/claim", 200, body={"accessTech": "lte"},
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
s9, _ = open_with_bearer(MSISDN)
check("C", "TS.43 operator token on Wi-Fi", "200", "POST",
      f"{GATE}/qr-session/v1/{s9}/claim", 200,
      body={"accessTech": "WIFI", "operatorToken": operator_token()},
      headers={"X-Sas-Src-Ip": "192.168.1.55"})
s10, _ = open_with_bearer(MSISDN)
check("C", "an operator token that was already redeemed", "401", "POST",
      f"{GATE}/qr-session/v1/{s10}/claim", 401,
      body={"accessTech": "WIFI", "operatorToken": "tk-already-used"},
      headers={"X-Sas-Src-Ip": "192.168.1.55"})
s11, _ = open_with_bearer(MSISDN)
check("C", "claiming while presenting the operator token", "200", "POST",
      f"{GATE}/qr-session/v1/{s11}/claim", 200, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40",
               "Authorization": f"Bearer operatortoken:{operator_token()}"})
s12, _ = open_with_bearer(MSISDN)
check("C", "claiming with a garbage Authorization", "401", "POST",
      f"{GATE}/qr-session/v1/{s12}/claim", 401, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40", "Authorization": "Bearer garbage"})
s13, _ = open_with_bearer(MSISDN)
check("C", "with X-Sas-Risk-Class: HIGH_VALUE", "200", "POST",
      f"{GATE}/qr-session/v1/{s13}/claim", 200, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40", "X-Sas-Risk-Class": "HIGH_VALUE"})
s14, _ = open_with_bearer(MSISDN)
check("C", "with an unparseable risk class", "200 (ignored)", "POST",
      f"{GATE}/qr-session/v1/{s14}/claim", 200, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40", "X-Sas-Risk-Class": "INSANE"})
s15, _ = open_with_bearer(MSISDN)
check("C", "with a non-numeric source port", "200 (defaulted)", "POST",
      f"{GATE}/qr-session/v1/{s15}/claim", 200, body={"accessTech": "LTE"},
      headers={"X-Sas-Src-Ip": "10.20.30.40", "X-Sas-Src-Port": "not-a-port"})
s16, _ = open_with_bearer(MSISDN)
check("C", "no body at all", "200 (the bank stated the number)", "POST",
      f"{GATE}/qr-session/v1/{s16}/claim", 200,
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
s17, _ = open_with_bearer(MSISDN)
check("C", "empty JSON object", "200", "POST",
      f"{GATE}/qr-session/v1/{s17}/claim", 200, body={},
      headers={"X-Sas-Src-Ip": "10.20.30.40"})
s18, _ = open_with_bearer(MSISDN)
import urllib.request as _u
s18b, _ = open_with_bearer(MSISDN)
req = _u.Request(f"{GATE}/qr-session/v1/{s18b}/claim",
                 data=b'{"accessTech":"LTE","x":1}', method="POST",
                 headers={"X-Sas-Src-Ip": "10.20.30.40",
                          "Content-Type": "application/json"})
try:
    with _u.urlopen(req, timeout=30) as r:
        st_c, txt_c = r.status, r.read().decode()
except urllib.error.HTTPError as e:
    st_c, txt_c = e.code, e.read().decode()
row("C", "unknown field in the claim body", "400 with a body", st_c, txt_c)
check("C", "GET instead of POST on /claim", "405", "GET",
      f"{GATE}/qr-session/v1/{s18}/claim", 405)

print("D. five handsets racing one QR code")
race, _ = open_with_bearer(MSISDN)
with ThreadPoolExecutor(max_workers=5) as pool:
    answers = list(pool.map(
        lambda i: call("POST", f"{GATE}/qr-session/v1/{race}/claim",
                       body={"accessTech": "LTE"},
                       headers={"X-Sas-Src-Ip": f"10.20.30.{40 + i}",
                                "X-Sas-Src-Port": str(55555 + i)}),
        range(5)))
codes = sorted(a[0] for a in answers)
row("D", "5 concurrent claims on one session",
    "exactly one 200 and four 409", codes[0] if codes else 0,
    f"codes={codes}")

print()
print(f"{'grp':<4}{'status':>6}  {'case':<48} answer")
print("-" * 132)
mismatched = 0
for g, label, expect, st, text in ROWS:
    flag = ""
    if "!! expected" in label:
        flag = "  <<< MISMATCH"
        mismatched += 1
        label = label.replace("  !! expected", "")
    print(f"{g:<4}{st:>6}  {label:<48} {text}{flag}")
    print(f"{'':<10}{'':<48} expected: {expect}")
print()
print(f"{len(ROWS)} request shapes recorded, {mismatched} unexpected")