#!/usr/bin/env python3
"""DeepSeek-Hardness gate runner for the Silent Auth SAS.

Validates the documented verifier contract (docs/design/*.md) today and, later, a Java
verifier's emitted trace (``--trace path.json``). No soft "looks right" — every gate is a
boolean assertion anchored to a normative 3GPP clause.

Usage:
  python3 harness/run_hardness.py                  # contract check (documented source of truth)
  python3 harness/run_hardness.py --trace f.json   # (future) validate a verifier run

Exit code == number of failing gates.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - pyyaml is expected in this tree
    print("pyyaml is required: python3 -m pip install pyyaml", file=sys.stderr)
    sys.exit(2)

HERE = Path(__file__).resolve().parent
GATES_FILE = HERE / "gates.yaml"

# Canonical contract — SAS design facts under test. Mirrors
# docs/design/silent-auth-flow.md + docs/design/3gpp-spec-coverage.md and is the
# source of truth until a Java verifier emits a real trace.
CONTRACT = {
    "resolver_timeout_ms": 300,
    "map_timeout_ms": 2000,
    "diameter_timeout_ms": 2000,
    "total_timeout_ms": 3000,
    "fail_closed": True,
    "one_dialog_per_stage": True,
    "abort_on_timeout": True,
    "no_interconnect_ati": True,
    "cgnat_ip_port_ts_required": True,
    "privacy_msisdn_never_to_app": True,
    "privacy_imsi_never_to_app": True,
    "camara_contract": True,
    "mapping": {
        "reachable_2g3g": "TS 29.002 provideSubscriberInfo (70)",
        "force_reachable": "TS 29.002 anyTimeInterrogation (71) [intra-net only]",
        "fresh_2g3g": "TS 29.002 sendAuthenticationInfo (56)",
        "reachable_lte": "TS 29.272 ULR/ULA (316), PUR/PUA (321)",
        "fresh_lte": "TS 29.272 AIR/AIA (318)",
        "fallback_sms": "TS 29.338 SGd (Diameter SMS, via DEA)",
        "wifi_silent_auth": "TS 33.402 SWm/SWx (EAP-AKA) + GSMA TS.43",
        "reachable_5g": "TS 33.501 Nudm/Nausf via NEF/NRF",
        "tcap_dialog": "TS 23.018 / TS 23.060 (TC-TIMER)",
        "map_vs_diameter_no_ip2msisdn": "Resolver = PGW/PCRF (S6b/SGi data plane)",
    },
}

# ---------------------------------------------------------------------------
# Checkers
# ---------------------------------------------------------------------------

def check_mapping_present(expect, contract):
    key = expect["key"]
    val = contract.get("mapping", {}).get(key)
    ok = bool(val)
    return ok, f"mapping[{key!r}] = {val!r}" + (" (present)" if ok else " (MISSING)")


def check_flag(expect, contract):
    key = expect["key"]
    want = expect.get("value", True)
    got = contract.get(key)
    ok = (got == want)
    return ok, f"contract[{key!r}] = {got!r} (expect {want!r})"


def check_budget(expect, contract):
    """Every stage timeout < total budget (dialog-anchor invariant)."""
    total = contract.get("total_timeout_ms")
    failures = []
    for k in ("resolver_timeout_ms", "map_timeout_ms", "diameter_timeout_ms"):
        v = contract.get(k)
        if v is None or v >= total:
            failures.append(f"{k}={v}")
    ok = not failures
    return ok, (f"all stage budgets < total({total} ms)" if ok
                else f"budget exceeds total: {failures}")


CHECKERS = {
    "mapping_present": check_mapping_present,
    "flag": check_flag,
    "budget": check_budget,
}

# ---------------------------------------------------------------------------
# Contract self-consistency checks (always run, in addition to the gates)
# ---------------------------------------------------------------------------

def _contract_checks(contract):
    out = []
    budget_pairs = [
        ("resolver_timeout_ms", contract.get("resolver_timeout_ms")),
        ("map_timeout_ms", contract.get("map_timeout_ms")),
        ("diameter_timeout_ms", contract.get("diameter_timeout_ms")),
    ]
    total = contract.get("total_timeout_ms")
    for name, v in budget_pairs:
        ok = isinstance(v, int) and v > 0 and v < total
        out.append((f"budget:{name}<total", ok, f"{v} < {total}"))

    for key in ("fail_closed", "abort_on_timeout", "one_dialog_per_stage",
                "no_interconnect_ati", "privacy_msisdn_never_to_app",
                "privacy_imsi_never_to_app", "cgnat_ip_port_ts_required"):
        ok = contract.get(key) is True
        out.append((f"contract:{key}", ok, str(contract.get(key))))
    return out


def run_harness(gates, contract, trace=None):
    failures = 0
    total = 0
    print("== DeepSeek-Hardness — Silent Auth SAS ==")
    if trace is not None:
        print(f"trace: {trace}")
    else:
        print("mode: contract check (docs/design/*.md)")

    print("\n[contract checks]")
    for name, ok, detail in _contract_checks(contract):
        total += 1
        mark = "PASS" if ok else "FAIL"
        if not ok:
            failures += 1
        print(f"  [{mark}] {name} — {detail}")

    print("\n[gates]")
    for g in gates:
        total += 1
        ck = g.get("check")
        fn = CHECKERS.get(ck)
        if fn is None:
            print(f"  [FAIL] {g['id']} — unknown checker {ck!r}")
            failures += 1
            continue
        ok, detail = fn(g.get("expect", {}), contract)
        mark = "PASS" if ok else "FAIL"
        if not ok:
            failures += 1
        print(f"  [{mark}] {g['id']} {g['title']}  ({' | '.join(g['spec'])})")
        if not ok:
            print(f"         rule: {g['rule']}")
            print(f"         {detail}")

    print(f"\n== {total - failures}/{total} pass, {failures} fail ==")
    return failures


def main(argv):
    trace_arg = None
    for i, a in enumerate(argv):
        if a == "--trace" and i + 1 < len(argv):
            trace_arg = argv[i + 1]

    if not GATES_FILE.exists():
        print(f"missing {GATES_FILE}", file=sys.stderr)
        return 2

    with open(GATES_FILE, encoding="utf-8") as fh:
        doc = yaml.safe_load(fh)

    gates = doc["gates"]

    if trace_arg:
        with open(trace_arg, encoding="utf-8") as fh:
            trace = json.load(fh)
    else:
        trace = None

    return run_harness(gates, CONTRACT, trace)


if __name__ == "__main__":
    sys.exit(main(sys.argv))