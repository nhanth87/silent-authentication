"""Mutation self-test for harness gate H25 (entitlement_split).

Injects one realistic violation at a time into the working tree, runs the
checker, restores the file. Every scenario is a regression the two-binary split
exists to prevent: one binary reaching into the other, a shared library taking a
side, a banned MAP/Diameter operation on the entitlement path, or AKA key material
handed to a logger.

    python3 harness/mut_entitlement_split.py        # exit 0 = all caught
    python3 harness/run_hardness.py --mutations     # H24 + H25 self-tests
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ECS = "sas-ecs-host/src/main/java/et/restlink/sas"
CAM = "sas-host/src/main/java/et/restlink/sas"
TR = "sas-operator-adapter/src/main/java/et/restlink/sas"
PRELUDE = (
    'import sys, yaml\n'
    'sys.path.insert(0, "harness")\n'
    'import run_hardness as rh\n'
    'g = next(x for x in yaml.safe_load(open("harness/gates.yaml"))["gates"] if x["id"] == "H25")\n'
    'ok, detail = rh.CHECKERS["entitlement_split"](g["expect"], rh.CONTRACT)\n'
    'print(("PASS " if ok else "CAUGHT ") + detail)\n')


def add_import(stmt):
    return lambda t: t.replace('import ', stmt + '\nimport ', 1)


def before(anchor, line):
    return lambda t: t.replace(anchor, line + '\n' + anchor, 1) if anchor in t else t


SCENARIOS = [
    ("entitlement server imports the CAMARA OAuth AS", f"{ECS}/bootstrap/EcsBootstrap.java",
     add_import("import et.restlink.sas.oauth.AccessTokenService;")),
    ("CAMARA server imports the entitlement library", f"{CAM}/bootstrap/SasBootstrap.java",
     add_import("import et.restlink.sas.entitlement.EntitlementConfig;")),
    ("CAMARA binary depends on the entitlement library", "sas-host/pom.xml",
     before("  </dependencies>",
            "    <dependency><groupId>et.restlink</groupId><artifactId>sas-entitlement</artifactId></dependency>")),
    ("entitlement binary embeds the CAMARA library", "sas-ecs-host/pom.xml",
     before("  </dependencies>",
            "    <dependency><groupId>et.restlink</groupId><artifactId>sas-api</artifactId></dependency>")),
    ("shared token library takes a side", "sas-token/src/main/java/et/restlink/sas/token/OperatorToken.java",
     add_import("import et.restlink.sas.oauth.OAuthServerConfig;")),
    ("ATI on the auth-vector path", f"{TR}/ras/authvector/AuthVectorResourceAdaptor.java",
     add_import("import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation."
                "AnyTimeInterrogationRequest;")),
    ("SRI-SM on the binding path", f"{TR}/ras/binding/backend/MapSendImsiBinding.java",
     add_import("import org.restcomm.protocols.ss7.map.api.service.sms.SendRoutingInfoForSMRequest;")),
    ("AKA key logged in the entitlement SBB", f"{ECS}/sbbs/EntitlementSbb.java",
     before("        boolean akaPrime = ",
            '        LOG.info("[ts43] leak {}", vector.ck());')),
    ("CAMARA seam re-implements the entitlement engine", f"{CAM}/bootstrap/SasBootstrap.java",
     lambda t: t.replace("implements SasVerifyEngine {",
                         "implements SasVerifyEngine, java.io.Serializable {", 1)),
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
    print(f"== H25: {caught} caught, {missed} missed, baseline_ok={baseline_ok} ==")
    return 0 if (missed == 0 and baseline_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
