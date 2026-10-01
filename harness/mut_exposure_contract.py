"""Mutation self-test for harness gate H28 (exposure_contract).

Injects one realistic drift at a time between the subscriber exposure contract
(docs/design/openapi/sas-subscriber-exposure-v1.yaml) and its Java halves, runs the
checker, restores the file. Each scenario is a way the CAMARA gateway and the
entitlement server could stop understanding each other while every unit test of one
side stays green.

    python3 harness/mut_exposure_contract.py        # exit 0 = all caught
    python3 harness/run_hardness.py --mutations     # H24 + H25 + H28 self-tests
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OPENAPI = "docs/design/openapi/sas-subscriber-exposure-v1.yaml"
API = "sas-subscriber-api/src/main/java/et/restlink/sas/subscriber"
RES = "sas-subscriber-exposure/src/main/java/et/restlink/sas/exposure/SubscriberExposureResource.java"
CLIENT = "sas-host/src/main/java/et/restlink/sas/ras/exposure/ExposureClient.java"
PRELUDE = (
    'import sys, yaml\n'
    'sys.path.insert(0, "harness")\n'
    'import run_hardness as rh\n'
    'g = next(x for x in yaml.safe_load(open("harness/gates.yaml"))["gates"] if x["id"] == "H28")\n'
    'ok, detail = rh.CHECKERS["exposure_contract"](g["expect"], rh.CONTRACT)\n'
    'print(("PASS " if ok else "CAUGHT ") + detail)\n')


def swap(old, new):
    return lambda t: t.replace(old, new, 1) if old in t else t


SCENARIOS = [
    ("contract renames an operation path", OPENAPI,
     swap("  /sim-change/retrieve:", "  /sim-change/fetch:")),
    ("Java contract moves a path", f"{API}/ExposureApi.java",
     swap('REDEEM_PATH = "/operator-token/redeem"', 'REDEEM_PATH = "/operator-token/consume"')),
    ("contract adds a scope Java does not know", OPENAPI,
     swap("            exposure:ue-id: RetrieveUeId",
          "            exposure:ue-id: RetrieveUeId\n            exposure:roaming: RoamingStatus")),
    ("ECS resource serves a literal path", RES,
     swap("@Path(ExposureApi.REDEEM_PATH)", '@Path("/operator-token/redeem")')),
    ("gateway client stops calling an operation", CLIENT,
     swap("post(ExposureApi.SIM_CHANGE_PATH,", 'post("/sim-change/retrieve",')),
    ("cause code only in Java", f"{API}/ExposureCause.java",
     swap("    INVALID_ARGUMENT(400),", "    INVALID_ARGUMENT(400),\n    TEAPOT(418),")),
    ("cause code only in the contract", OPENAPI,
     swap("            - BUDGET_EXHAUSTED", "            - BUDGET_EXHAUSTED\n            - RATE_LIMITED")),
    ("SubscriberService loses an operation", f"{API}/SubscriberService.java",
     swap("    CompletableFuture<ExposureOutcome<SimChangeInfo>> retrieveSimChange(",
          "    Object retrieveSimChange(")),
]


def run():
    p = subprocess.run([sys.executable, "-c", PRELUDE], cwd=ROOT,
                       capture_output=True, text=True)
    lines = (p.stdout or p.stderr).strip().splitlines()
    return lines[0] if lines else "(no output)"


def main():
    base = run()
    baseline_ok = base.startswith("PASS")
    caught = missed = 0
    for i, (name, rel, mutate) in enumerate(SCENARIOS, 1):
        path = ROOT / rel
        orig = path.read_text(encoding="utf-8")
        mutated = mutate(orig)
        if mutated == orig:
            print(f"[M{i}] SETUP-FAIL {name} (anchor not found in {rel})")
            missed += 1
            continue
        path.write_text(mutated, encoding="utf-8")
        try:
            out = run()
        finally:
            path.write_text(orig, encoding="utf-8")
        hit = out.startswith("CAUGHT")
        caught += hit
        missed += not hit
        print(f"[M{i}] {'caught' if hit else 'MISSED'} — {name} :: {out[:160]}")
    print(f"== H28: {caught} caught, {missed} missed, baseline_ok={baseline_ok} ==")
    return 0 if (missed == 0 and baseline_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
