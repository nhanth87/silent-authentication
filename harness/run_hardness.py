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
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - pyyaml is expected in this tree
    print("pyyaml is required: python3 -m pip install pyyaml", file=sys.stderr)
    sys.exit(2)

HERE = Path(__file__).resolve().parent
GATES_FILE = HERE / "gates.yaml"
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

# Canonical contract — SAS design facts under test. Mirrors
# docs/design/silent-auth-standard-flow.md + docs/design/3gpp-spec-coverage.md and is the
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
        "fresh_lte": "TS 29.328/29.329 Sh UDR/SNR (read-only)",
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


def check_preflight(expect, contract):
    """P-H1…P-H7 production gate: run harness/preflight_prod.py scenarios.

    `baseline: true`  → a fully provisioned prod deployment must pass all checks.
    `scenario(s): …`  → each named misconfiguration must be caught by the PRO ids
                        listed for it in preflight_prod.MUTATIONS.
    Proves the gate bites without needing a JVM, an SS7 stack or secrets.
    """
    try:
        import preflight_prod
    except Exception as exc:  # pragma: no cover - broken module must fail the gate
        return False, f"preflight_prod.py unusable: {exc}"

    scenarios: list[str | None] = []
    if expect.get("baseline"):
        scenarios.append(None)
    single = expect.get("scenario")
    if single:
        scenarios.append(single)
    scenarios.extend(expect.get("scenarios") or [])
    if not scenarios:
        return False, "preflight checker expects baseline/scenario(s)"

    details = []
    for scenario in scenarios:
        ok, detail = preflight_prod.verify(scenario)
        details.append(detail)
        if not ok:
            return False, "; ".join(details)
    return True, "; ".join(details)


def check_access_tech_parity(expect, contract):
    """H22 — one bearer vocabulary across SAS + every UE SDK.

    A device may only declare the bearer names the SAS can parse. If an SDK
    invents a spelling ("5G", "CELLULAR"), the /session-tuple gate classifies it
    INVALID and silent auth fails closed with nothing but a 400 to explain it —
    the kind of drift that is invisible until a pilot breaks. So every source
    must *declare* each shared name (enum constant, `case x = "NAME"` raw value,
    or `NAME: 'NAME'` map entry) and the SDKs must keep an UNKNOWN declaration —
    a bearer that cannot be read is never guessed.
    """
    root = HERE.parent
    problems = []

    def declared(text, token):
        # Declaration sites only. A bare substring match would let a file pass on
        # `AccessTech.GS_2G3G` references alone even after its declaration was
        # renamed, which is exactly the drift this gate must catch.
        if re.search(r'(?m)^[ \t]*' + re.escape(token) + r'\b', text):
            return True
        return re.search(r'["\']' + re.escape(token) + r'["\']', text) is not None

    def check_file(rel, tokens, why):
        path = root / rel
        if not path.exists():
            problems.append(f"{rel}: missing")
            return
        text = path.read_text(encoding="utf-8")
        absent = [tok for tok in tokens if not declared(text, tok)]
        if absent:
            problems.append(f"{rel}: no declared {'/'.join(absent)} ({why})")

    for rel in expect.get("sources", []):
        check_file(rel, list(expect.get("cellular", [])) + list(expect.get("non_cellular", [])),
                   "bearer names")
    for rel in expect.get("unknown_sources", []):
        check_file(rel, ["UNKNOWN"], "must not guess a bearer")

    checked = len(expect.get("sources", [])) + len(expect.get("unknown_sources", []))
    ok = not problems
    return ok, (f"accessTech declared vocabulary aligned across {checked} sources" if ok
                else "; ".join(problems))



def check_license_parity(expect, contract):
    """H23 — the dual license is declared wherever it binds.

    AGPL only works as the Community grant if a reader of *any* component
    actually finds it: ``LICENSE.md`` carries the terms, every component README
    restates both editions with the same SPDX expression, and the
    machine-readable metadata (Maven ``<licenses>``, npm ``license``) agrees.
    The day one of those quietly drifts to MIT/Apache "for convenience", the
    Operator-license boundary written in ``LICENSE.md`` becomes a claim the tree
    does not honour — so this is a gate, not a comment.
    """
    root = HERE.parent
    problems = []
    spdx = expect["spdx"]
    editions = list(expect["editions"])
    terms_file = expect["license_file"]

    def read(rel):
        path = root / rel
        if not path.exists():
            problems.append(f"{rel}: missing")
            return ""
        return path.read_text(encoding="utf-8")

    def license_section(md):
        # Anchored on the heading TITLE ("## License", "## License & Commercial
        # Model ..."), so an incidental use of the word elsewhere cannot open a
        # section and a renamed heading cannot quietly drop one.
        m = re.search(r"(?m)^#{1,4}[ \t]+[Ll]icen[cs]e", md)
        return md[m.start():] if m else ""

    body = read(terms_file)
    # Markdown in this tree wraps at ~80 columns, so a required clause may span a
    # line break ("remote network\ninteraction"). Compare on collapsed whitespace
    # or every re-wrap of the prose would read as a license regression.
    flat = re.sub(r"\s+", " ", body)
    for needle in expect.get("license_file_terms", []):
        if re.sub(r"\s+", " ", needle) not in flat:
            problems.append(f"{terms_file}: missing {needle!r}")

    # The Community grant is only real if the license text travels with the tree:
    # a derivative must be able to convey it, and a bare URL rots.
    for req in expect.get("bundled_texts", []):
        rel = req["path"]
        text = re.sub(r"\s+", " ", read(rel))
        if not text:
            continue
        absent = [tok for tok in req.get("must_include", [])
                  if re.sub(r"\s+", " ", tok) not in text]
        if absent:
            problems.append(f"{rel}: missing {absent}")

    for rel in [expect["root_readme"]] + list(expect["readmes"]):
        md = read(rel)
        if not md:
            continue
        section = license_section(md)
        if not section:
            problems.append(f"{rel}: no License section")
            continue
        flat_section = re.sub(r"\s+", " ", section)
        missing = [name for name in editions if name not in flat_section]
        # Edition *rows*, not bare words: "Operator" also appears inside the SPDX
        # identifier, so a substring test passed even after the Operator row was
        # deleted from the table. Require the declaration site itself.
        for row in expect.get("edition_rows", []):
            if not re.search(row, section):
                missing.append(f"edition row {row}")
        if spdx not in flat_section:
            missing.append("SPDX expression")
        if terms_file not in flat_section:
            missing.append(f"link to {terms_file}")
        if missing:
            problems.append(f"{rel}: License section lacks {'/'.join(missing)}")
        for pat in expect.get("forbid_in_license_section", []):
            if re.search(pat, flat_section):
                problems.append(f"{rel}: License section asserts a permissive grant {pat!r}")

    for rel in expect.get("poms", []):
        pom = re.sub(r"\s+", " ", read(rel))
        if not pom:
            continue
        if "<licenses>" not in pom:
            problems.append(f"{rel}: no <licenses> block")
        elif "Affero" not in pom or spdx not in pom:
            problems.append(f"{rel}: <licenses> must name AGPL and carry {spdx!r}")

    for rel in expect.get("npm", []):
        raw = read(rel)
        try:
            got = json.loads(raw).get("license") if raw else None
        except ValueError:
            got = None
        if got != spdx:
            problems.append(f"{rel}: license={got!r} (expect {spdx!r})")

    artifacts = (len(expect["readmes"]) + 1 + len(expect.get("poms", []))
                 + len(expect.get("npm", [])))
    ok = not problems
    return ok, (f"dual license stated in {artifacts} artifacts "
                f"(Community {editions[0]} OR proprietary Operator)" if ok
                else "; ".join(problems))




def check_slee_boundary(expect, contract):
    """H24 — micro-jainslee owns the runtime; nothing is coded around it.

    The container exists to give the SAS exactly the properties the design
    relies on: single-threaded activity state per `reqId`, bounded TC/dialog
    timers, one event path that a fail-closed timeout can abort, and RA
    transports reachable only through their command ports. Code that routes
    around it — a REST resource calling a transport delegate directly, a
    hand-rolled executor or private event bus, a second SLEE API on the
    classpath — still passes unit tests and then loses those guarantees under
    load, which is the worst place to discover it. So the boundary is asserted,
    and every exception is an explicit, ratcheted allow-list entry: repaying a
    piece of debt without deleting its entry fails the gate just as hard as
    adding new debt.
    """
    root = HERE.parent
    problems = []
    modules = list(expect.get("runtime_modules", []))
    default_exempt = list(expect.get("exempt_path_parts", []))

    imports_re = re.compile(r"^import\s+(?:static\s+)?([A-Za-z0-9_.]+);", re.M)
    sources = {}
    for mod in modules:
        src = root / mod / "src"
        if not src.exists():
            problems.append(f"{mod}/src: missing")
            continue
        for path in sorted(src.rglob("*.java")):
            rel = path.relative_to(root).as_posix()
            if "/target/" in rel or "/build/" in rel:
                continue
            sources[rel] = path.read_text(encoding="utf-8")

    def audit(label, found, allowed, exempt_used):
        """Report new violations and stale (already repaid) allow-list entries."""
        hit = {rel for rel, _ in found}
        for rel, why in sorted(found):
            if rel not in allowed:
                problems.append(f"{rel}: {label} — {why}")
        for rel in allowed:
            if rel not in hit:
                problems.append(f"{rel}: {label} allow-list entry is unused — "
                                f"debt repaid, drop the exception")

    def path_exempt(rel, rule):
        return any(part in "/" + rel for part in rule.get("exempt", default_exempt))

    for rule in expect.get("import_rules", []):
        label = rule["label"]
        prefixes = tuple(rule["imports"])
        found = []
        for rel, text in sources.items():
            if path_exempt(rel, rule):
                continue
            for imp in imports_re.findall(text):
                if imp.startswith(prefixes):
                    found.append((rel, f"imports {imp}"))
                    break
        audit(label, found, set(rule.get("only_in", [])), rule)

    for rule in expect.get("pattern_rules", []):
        label, pat = rule["label"], re.compile(rule["pattern"])
        found = []
        for rel, text in sources.items():
            if path_exempt(rel, rule):
                continue
            m = pat.search(text)
            if m:
                line = text[:m.start()].count("\n") + 1
                found.append((rel, f"{m.group(0).strip()} at line {line}"))
        audit(label, found, set(rule.get("only_in", [])), rule)

    pinned = expect.get("pinned_group")
    prop = expect.get("pin_property", "")
    dep_re = re.compile(r"<dependency>(.*?)</dependency>", re.S)
    for rel in expect.get("poms", []):
        pom = root / rel
        if not pom.exists():
            problems.append(f"{rel}: missing")
            continue
        for block in dep_re.findall(pom.read_text(encoding="utf-8")):
            gid_m = re.search(r"<groupId>([^<]+)</groupId>", block)
            aid_m = re.search(r"<artifactId>([^<]+)</artifactId>", block)
            if not gid_m:
                continue
            gid, aid = gid_m.group(1).strip(), (aid_m.group(1).strip() if aid_m else "")
            ver_m = re.search(r"<version>([^<]+)</version>", block)
            if gid == pinned:
                # One cloned micro-jainslee, pinned once in the parent: a locally
                # hardcoded version means two different SLEE runtimes on the tree.
                if ver_m and ver_m.group(1).strip() != prop:
                    problems.append(f"{rel}: {aid} pins {ver_m.group(1).strip()}, "
                                    f"must be {prop}")
            elif re.search(r"(?i)(?:^|[^a-z])slee", gid) or re.search(r"(?i)(?:^|[^a-z])slee", aid):
                problems.append(f"{rel}: second SLEE runtime dependency {gid}:{aid}")

    n_rules = len(expect.get("import_rules", [])) + len(expect.get("pattern_rules", []))
    ok = not problems
    return ok, (f"micro-jainslee boundary intact ({len(sources)} runtime sources, "
                f"{n_rules} rules, exceptions pinned)" if ok else "; ".join(problems))


CHECKERS = {
    "mapping_present": check_mapping_present,
    "flag": check_flag,
    "budget": check_budget,
    "preflight": check_preflight,
    "access_tech_parity": check_access_tech_parity,
    "license_parity": check_license_parity,
    "slee_boundary": check_slee_boundary,
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
    if "--mutations" in argv:
        # Prove the H24 checker bites: inject one violation at a time, restore
        # afterwards, fail the run when any scenario escapes or the baseline fails.
        try:
            import mut_slee_boundary
        except ImportError as exc:  # pragma: no cover - must be next to this file
            print(f"mut_slee_boundary.py unusable: {exc}", file=sys.stderr)
            return 2
        return mut_slee_boundary.main()

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