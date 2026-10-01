#!/usr/bin/env python3
# Silent Auth SAS — Restlink (Ethiopia).
# Independent EAP-AKA / EAP-AKA' peer + Milenage oracle. Python 3. Lab/test only.
#
# Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
"""Clean-room oracle for the Java EAP-AKA stack.

Written from the specifications only (3GPP TS 35.206 / TS 35.208, TS 33.102,
TS 33.402 Annex A, RFC 3748, RFC 4187, RFC 9048) and deliberately sharing no
code with the Java implementation, so a bug cannot hide by being made twice.

Used three ways:
  * ``selftest``        — Milenage against TS 35.208 test sets, RFC 9048 CK'/IK'.
  * ``peer``            — a USIM + EAP peer that answers a live TS.43 ECS over
                          HTTP (see ``run_ts43``), used by the E2E prove script.
  * ``decode``          — strict RFC 4187 decoder for a base64 EAP packet
                          (fails loudly on anything non-conformant).

Never ship: this answers an AKA challenge from a key in memory.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# ---------------------------------------------------------------------------
# Milenage (TS 35.206)
# ---------------------------------------------------------------------------


def _aes(key: bytes, block: bytes) -> bytes:
    enc = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    return enc.update(block) + enc.finalize()


def _xor(a: bytes, b: bytes) -> bytes:
    return bytes(x ^ y for x, y in zip(a, b))


def _rot(x: bytes, bits: int) -> bytes:
    n = int.from_bytes(x, "big")
    bits %= 128
    n = ((n << bits) | (n >> (128 - bits))) & ((1 << 128) - 1)
    return n.to_bytes(16, "big")


def _c(i: int) -> bytes:
    return bytes(15) + bytes([i])


def opc_from_op(k: bytes, op: bytes) -> bytes:
    return _xor(_aes(k, op), op)


def milenage(k: bytes, opc: bytes, rand: bytes, sqn: bytes, amf: bytes) -> dict:
    temp = _aes(k, _xor(rand, opc))
    in1 = sqn + amf + sqn + amf
    out1 = _xor(_aes(k, _xor(temp, _rot(_xor(in1, opc), 64))), opc)
    out2 = _xor(_aes(k, _xor(_rot(_xor(temp, opc), 0), _c(1))), opc)
    out3 = _xor(_aes(k, _xor(_rot(_xor(temp, opc), 32), _c(2))), opc)
    out4 = _xor(_aes(k, _xor(_rot(_xor(temp, opc), 64), _c(4))), opc)
    out5 = _xor(_aes(k, _xor(_rot(_xor(temp, opc), 96), _c(8))), opc)
    return {
        "mac_a": out1[:8], "mac_s": out1[8:],
        "res": out2[8:], "ak": out2[:6],
        "ck": out3, "ik": out4, "ak_star": out5[:6],
    }


# TS 35.208 conformance test sets (4.3). Values transcribed from the spec.
TS35208 = [
    {  # Test Set 1
        "k": "465b5ce8b199b49faa5f0a2ee238a6bc", "rand": "23553cbe9637a89d218ae64dae47bf35",
        "sqn": "ff9bb4d0b607", "amf": "b9b9", "op": "cdc202d5123e20f62b6d676ac72cb318",
        "opc": "cd63cb71954a9f4e48a5994e37a02baf", "mac_a": "4a9ffac354dfafb3",
        "mac_s": "01cfaf9ec4e871e9", "res": "a54211d5e3ba50bf", "ck": "b40ba9a3c58b2a05bbf0d987b21bf8cb",
        "ik": "f769bcd751044604127672711c6d3441", "ak": "aa689c648370", "ak_star": "451e8beca43b",
    },
]


def selftest() -> int:
    failures = 0
    for i, ts in enumerate(TS35208, 1):
        h = {k: bytes.fromhex(v) for k, v in ts.items()}
        if opc_from_op(h["k"], h["op"]) != h["opc"]:
            print(f"TS 35.208 set {i}: OPc mismatch"); failures += 1
        out = milenage(h["k"], h["opc"], h["rand"], h["sqn"], h["amf"])
        for name in ("mac_a", "mac_s", "res", "ck", "ik", "ak", "ak_star"):
            if out[name] != h[name]:
                print(f"TS 35.208 set {i}: {name} {out[name].hex()} != {h[name].hex()}"); failures += 1
    # RFC 9048 Appendix D case 3: CK'/IK' for "WLAN".
    ckp, ikp = derive_ck_ik_prime(bytes.fromhex("c0" * 16), bytes.fromhex("b0" * 16),
                                  b"WLAN", bytes.fromhex("a0" * 6))
    if ckp.hex() != "cd4c8e5c68f57dd1d7d7dfd0c538e577" or ikp.hex() != "3ece6b705dbbf7dfc459a11280c65524":
        print("RFC 9048 case 3 CK'/IK' mismatch"); failures += 1
    print("oracle selftest:", "OK" if failures == 0 else f"{failures} FAIL")
    return failures


# ---------------------------------------------------------------------------
# Key derivation (RFC 4187 §7 / Appendix A, RFC 9048 §3.3, TS 33.402 A.2)
# ---------------------------------------------------------------------------


def _fips186_2_prf(xkey: bytes, out_len: int) -> bytes:
    """RFC 4187 Appendix A: FIPS 186-2 change notice 1 PRF, b=160, no XSEED."""

    def g(t: bytes, c: bytes) -> bytes:
        # SHA-1 compression function on the 512-bit zero-padded block, no final padding.
        h = [int.from_bytes(t[i:i + 4], "big") for i in range(0, 20, 4)]
        block = c + bytes(64 - len(c))
        w = [int.from_bytes(block[i:i + 4], "big") for i in range(0, 64, 4)]
        for i in range(16, 80):
            v = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]
            w.append(((v << 1) | (v >> 31)) & 0xFFFFFFFF)
        a, b, cc, d, e = h
        for i in range(80):
            if i < 20:
                f, kk = (b & cc) | (~b & d), 0x5A827999
            elif i < 40:
                f, kk = b ^ cc ^ d, 0x6ED9EBA1
            elif i < 60:
                f, kk = (b & cc) | (b & d) | (cc & d), 0x8F1BBCDC
            else:
                f, kk = b ^ cc ^ d, 0xCA62C1D6
            tmp = (((a << 5) | (a >> 27)) + (f & 0xFFFFFFFF) + e + kk + w[i]) & 0xFFFFFFFF
            e, d, cc, b, a = d, cc, ((b << 30) | (b >> 2)) & 0xFFFFFFFF, a, tmp
        return b"".join(((x + y) & 0xFFFFFFFF).to_bytes(4, "big") for x, y in zip(h, [a, b, cc, d, e]))

    t = bytes.fromhex("67452301EFCDAB8998BADCFE10325476C3D2E1F0")
    xk = int.from_bytes(xkey, "big")
    out = b""
    while len(out) < out_len:
        for _ in range(2):
            w = g(t, (xk % (1 << 160)).to_bytes(20, "big"))
            out += w
            xk = (1 + xk + int.from_bytes(w, "big")) % (1 << 160)
    return out[:out_len]


def aka_keys(identity: bytes, ik: bytes, ck: bytes) -> dict:
    mk = hashlib.sha1(identity + ik + ck).digest()
    k = _fips186_2_prf(mk, 160)
    return {"k_encr": k[:16], "k_aut": k[16:32], "msk": k[32:96], "emsk": k[96:160]}


def _prf_prime(key: bytes, seed: bytes, n: int) -> bytes:
    out, t, i = b"", b"", 1
    while len(out) < n:
        t = hmac.new(key, t + seed + bytes([i]), hashlib.sha256).digest()
        out += t
        i += 1
    return out[:n]


def derive_ck_ik_prime(ck: bytes, ik: bytes, network_name: bytes, sqn_xor_ak: bytes):
    s = b"\x20" + network_name + len(network_name).to_bytes(2, "big") + sqn_xor_ak + b"\x00\x06"
    out = hmac.new(ck + ik, s, hashlib.sha256).digest()
    return out[:16], out[16:]


def aka_prime_keys(identity: bytes, ik_p: bytes, ck_p: bytes) -> dict:
    k = _prf_prime(ik_p + ck_p, b"EAP-AKA'" + identity, 208)
    return {"k_encr": k[:16], "k_aut": k[16:48], "k_re": k[48:80], "msk": k[80:144], "emsk": k[144:208]}


# ---------------------------------------------------------------------------
# RFC 4187 §8 codec (strict)
# ---------------------------------------------------------------------------

AKA, AKA_PRIME = 23, 50
SUB_CHALLENGE, SUB_AUTH_REJECT, SUB_SYNC_FAILURE, SUB_IDENTITY = 1, 2, 4, 5
SUB_NOTIFICATION, SUB_REAUTH, SUB_CLIENT_ERROR = 12, 13, 14
AT_RAND, AT_AUTN, AT_RES, AT_AUTS, AT_PADDING = 1, 2, 3, 4, 6
AT_PERMANENT_ID_REQ, AT_MAC, AT_NOTIFICATION, AT_ANY_ID_REQ, AT_IDENTITY = 10, 11, 12, 13, 14
AT_FULLAUTH_ID_REQ, AT_COUNTER, AT_COUNTER_TOO_SMALL, AT_NONCE_S = 17, 19, 20, 21
AT_CLIENT_ERROR_CODE, AT_KDF_INPUT, AT_KDF = 22, 23, 24
AT_IV, AT_ENCR_DATA, AT_NEXT_PSEUDONYM, AT_NEXT_REAUTH_ID = 129, 130, 132, 133
AT_CHECKCODE, AT_RESULT_IND, AT_BIDDING = 134, 135, 136
KNOWN = {AT_RAND, AT_AUTN, AT_RES, AT_AUTS, AT_PADDING, AT_PERMANENT_ID_REQ, AT_MAC,
         AT_NOTIFICATION, AT_ANY_ID_REQ, AT_IDENTITY, AT_FULLAUTH_ID_REQ, AT_COUNTER,
         AT_COUNTER_TOO_SMALL, AT_NONCE_S, AT_CLIENT_ERROR_CODE, AT_KDF_INPUT, AT_KDF}


class Nonconformant(Exception):
    pass


@dataclass
class Msg:
    code: int
    ident: int
    type: int
    subtype: int
    attrs: list = field(default_factory=list)   # list of (type, raw_value_after_length)


def decode(pkt: bytes) -> Msg:
    if len(pkt) < 4:
        raise Nonconformant("short EAP header")
    code, ident, length = pkt[0], pkt[1], struct.unpack(">H", pkt[2:4])[0]
    if length != len(pkt):
        raise Nonconformant(f"EAP Length {length} != {len(pkt)} octets")
    if code in (3, 4):
        if length != 4:
            raise Nonconformant("Success/Failure must be 4 octets")
        return Msg(code, ident, -1, -1)
    if length < 8:
        raise Nonconformant("EAP-AKA header needs Type+Subtype+Reserved")
    typ, sub = pkt[4], pkt[5]
    if typ not in (AKA, AKA_PRIME):
        raise Nonconformant(f"method type {typ}")
    attrs, i = [], 8
    while i < len(pkt):
        if i + 2 > len(pkt):
            raise Nonconformant("truncated attribute")
        at, ln = pkt[i], pkt[i + 1] * 4
        if ln == 0 or i + ln > len(pkt):
            raise Nonconformant(f"bad attribute length at {i}")
        if at < 128 and at not in KNOWN:
            raise Nonconformant(f"unknown non-skippable attribute {at}")
        attrs.append((at, pkt[i + 2:i + ln]))
        i += ln
    return Msg(code, ident, typ, sub, attrs)


def attr(m: Msg, t: int):
    for at, v in m.attrs:
        if at == t:
            return v
    return None


def _enc_attr(at: int, body: bytes) -> bytes:
    total = 2 + len(body)
    pad = (-total) % 4
    return bytes([at, (total + pad) // 4]) + body + bytes(pad)


def encode(code: int, ident: int, typ: int, sub: int, attrs: list) -> bytes:
    body = b"".join(_enc_attr(t, v) for t, v in attrs)
    length = 8 + len(body)
    return bytes([code, ident]) + struct.pack(">H", length) + bytes([typ, sub, 0, 0]) + body


def _mac_over(pkt: bytes, key: bytes, prime: bool) -> bytes:
    """Recompute AT_MAC over the packet with the MAC value zeroed (RFC 4187 §10.15)."""
    m = decode(pkt)
    i, zeroed = 8, bytearray(pkt)
    for at, v in m.attrs:
        ln = pkt[i + 1] * 4
        if at == AT_MAC:
            zeroed[i + 4:i + 20] = bytes(16)
        i += ln
    digest = hashlib.sha256 if prime else hashlib.sha1
    return hmac.new(key, bytes(zeroed), digest).digest()[:16]


# ---------------------------------------------------------------------------
# USIM + peer
# ---------------------------------------------------------------------------


@dataclass
class Usim:
    k: bytes
    opc: bytes
    sqn_ms: int = 0
    delta: int = 1 << 28

    def authenticate(self, rand: bytes, autn: bytes, require_separation: bool):
        conc, amf, mac_a = autn[:6], autn[6:8], autn[8:]
        ak = milenage(self.k, self.opc, rand, bytes(6), amf)["ak"]
        sqn = _xor(conc, ak)
        out = milenage(self.k, self.opc, rand, sqn, amf)
        if out["mac_a"] != mac_a:
            return ("reject", None)
        if require_separation and not (amf[0] & 0x80):
            return ("reject", None)       # TS 33.401 separation bit — AKA' demands it
        n = int.from_bytes(sqn, "big")
        if not (self.sqn_ms < n <= self.sqn_ms + self.delta):
            sqn_ms = self.sqn_ms.to_bytes(6, "big")
            star = milenage(self.k, self.opc, rand, sqn_ms, b"\x00\x00")
            auts = _xor(sqn_ms, star["ak_star"]) + star["mac_s"]
            return ("sync", auts)
        self.sqn_ms = n
        return ("ok", {"res": out["res"], "ck": out["ck"], "ik": out["ik"], "sqn_xor_ak": conc})


def respond(usim: Usim, identity: bytes, request: bytes) -> tuple[bytes, dict]:
    """Answer one EAP-Request/AKA(')-Challenge. Returns (response, info)."""
    m = decode(request)
    if m.code != 1 or m.subtype != SUB_CHALLENGE:
        raise Nonconformant(f"expected Request/Challenge, got code={m.code} subtype={m.subtype}")
    prime = m.type == AKA_PRIME
    rand_v, autn_v, mac_v = attr(m, AT_RAND), attr(m, AT_AUTN), attr(m, AT_MAC)
    for name, v in (("AT_RAND", rand_v), ("AT_AUTN", autn_v), ("AT_MAC", mac_v)):
        if v is None or len(v) != 18 or v[:2] != b"\x00\x00":
            raise Nonconformant(f"{name} missing or malformed (RFC 4187 §10)")
    rand, autn, mac = rand_v[2:], autn_v[2:], mac_v[2:]
    net = None
    if prime:
        kdf, kin = attr(m, AT_KDF), attr(m, AT_KDF_INPUT)
        if kdf is None or len(kdf) != 2 or struct.unpack(">H", kdf)[0] != 1:
            raise Nonconformant("AKA' challenge without AT_KDF=1")
        if kin is None or len(kin) < 2:
            raise Nonconformant("AKA' challenge without AT_KDF_INPUT")
        nl = struct.unpack(">H", kin[:2])[0]
        net = kin[2:2 + nl]
    verdict, data = usim.authenticate(rand, autn, require_separation=prime)
    if verdict == "reject":
        return encode(2, m.ident, m.type, SUB_AUTH_REJECT, []), {"verdict": "reject"}
    if verdict == "sync":
        return encode(2, m.ident, m.type, SUB_SYNC_FAILURE, [(AT_AUTS, data)]), {"verdict": "sync"}
    ck, ik = data["ck"], data["ik"]
    if prime:
        ck, ik = derive_ck_ik_prime(ck, ik, net, data["sqn_xor_ak"])
        keys = aka_prime_keys(identity, ik, ck)
    else:
        keys = aka_keys(identity, ik, ck)
    if not hmac.compare_digest(_mac_over(request, keys["k_aut"], prime), mac):
        raise Nonconformant("server AT_MAC does not verify — network not authenticated")
    res = data["res"]
    resp_attrs = [(AT_RES, struct.pack(">H", len(res) * 8) + res),
                  (AT_MAC, bytes(18))]
    # RFC 9048 §3.2: AT_KDF appears in a Response only to negotiate another KDF;
    # accepting KDF 1 means answering with AT_RES + AT_MAC alone.
    pkt = bytearray(encode(2, m.ident, m.type, SUB_CHALLENGE, resp_attrs))
    tag = _mac_over(bytes(pkt), keys["k_aut"], prime)
    # place MAC: find AT_MAC offset
    i = 8
    while i < len(pkt):
        if pkt[i] == AT_MAC:
            pkt[i + 4:i + 20] = tag
            break
        i += pkt[i + 1] * 4
    return bytes(pkt), {"verdict": "ok", "msk": keys["msk"].hex()}


# ---------------------------------------------------------------------------
# TS.43 client (drives a live ECS)
# ---------------------------------------------------------------------------

EAP_RELAY = "application/vnd.gsma.eap-relay.v1.0+json"


def run_ts43(base: str, imsi: str, mcc: str, mnc: str, app: str, usim: Usim, prime: bool,
             terminal_id: str = "354575090000000", verbose: bool = True) -> dict:
    prefix = "6" if prime else "0"
    eap_id = f"{prefix}{imsi}@nai.epc.mnc{mnc.zfill(3)}.mcc{mcc}.3gppnetwork.org"
    identity = eap_id.encode()
    q = urllib.parse.urlencode({"vers": "0", "entitlement_version": "2.0", "app": app,
                                "EAP_ID": eap_id, "terminal_id": terminal_id,
                                "terminal_vendor": "LAB", "terminal_model": "ORACLE",
                                "terminal_sw_version": "1"})
    jar = {}

    def call(method, url, body=None):
        req = urllib.request.Request(url, method=method, data=body)
        req.add_header("Accept", f"{EAP_RELAY}, application/json")
        if body is not None:
            req.add_header("Content-Type", EAP_RELAY)
        if jar:
            req.add_header("Cookie", "; ".join(f"{k}={v}" for k, v in jar.items()))
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                status, headers, data = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            status, headers, data = e.code, e.headers, e.read()
        for c in headers.get_all("Set-Cookie") or []:
            name, _, rest = c.partition("=")
            jar[name.strip()] = rest.split(";", 1)[0]
        return status, headers.get("Content-Type", ""), data

    url = base.rstrip("/") + "/?" + q
    status, ctype, data = call("GET", url)
    rounds = []
    for _ in range(4):
        if status != 200 or EAP_RELAY not in ctype:
            break
        req_pkt = base64.b64decode(json.loads(data)["eap-relay-packet"])
        resp_pkt, info = respond(usim, identity, req_pkt)
        rounds.append({"request": decode(req_pkt).subtype, "answer": info["verdict"]})
        if verbose:
            print(f"  EAP round: request subtype={decode(req_pkt).subtype} -> {info['verdict']}")
        body = json.dumps({"eap-relay-packet": base64.b64encode(resp_pkt).decode()}).encode()
        status, ctype, data = call("POST", base.rstrip("/") + "/", body)
    return {"status": status, "content_type": ctype, "body": data.decode(errors="replace"),
            "rounds": rounds}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("selftest")
    d = sub.add_parser("decode")
    d.add_argument("b64")
    p = sub.add_parser("peer")
    p.add_argument("--base", required=True)
    p.add_argument("--imsi", required=True)
    p.add_argument("--mcc", default="636")
    p.add_argument("--mnc", default="01")
    p.add_argument("--app", required=True)
    p.add_argument("--k", required=True)
    p.add_argument("--opc", required=True)
    p.add_argument("--sqn-ms", type=lambda s: int(s, 0), default=0)
    p.add_argument("--prime", action="store_true")
    a = ap.parse_args(argv)
    if a.cmd == "selftest":
        return selftest()
    if a.cmd == "decode":
        m = decode(base64.b64decode(a.b64))
        print(json.dumps({"code": m.code, "id": m.ident, "type": m.type, "subtype": m.subtype,
                          "attrs": [[t, v.hex()] for t, v in m.attrs]}))
        return 0
    usim = Usim(bytes.fromhex(a.k), bytes.fromhex(a.opc), a.sqn_ms)
    out = run_ts43(a.base, a.imsi, a.mcc, a.mnc, a.app, usim, a.prime)
    print(json.dumps(out, indent=2))
    return 0 if out["status"] == 200 and out["rounds"] and out["rounds"][-1]["answer"] == "ok" else 1


if __name__ == "__main__":
    sys.exit(main())
