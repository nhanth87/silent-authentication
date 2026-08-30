"""Mutation self-test for harness gate H24 (slee_boundary).

Injects one realistic violation at a time into the working tree, runs the
checker, restores the file. Scenario 8 is the ratchet half: repaying debt
without deleting the allow-list entry must also fail, so the exception list can
never silently rot.

Run from anywhere (ROOT is derived from this file, not the cwd):

    python3 harness/mut_slee_boundary.py            # exit 0 = all caught
    python3 harness/run_hardness.py --mutations     # same thing, via the runner

These scenarios are the same list that `/tmp/mut24.py` carried while the gate
was being developed; persisting them in-repo means the H24 checker's mutation
coverage travels with the tree and is reproducible on any checkout.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
J = "sas-host/src/main/java/et/restlink/sas"
A = "sas-api/src/main/java/et/restlink/sas"
PRELUDE = (
    'import sys, yaml\n'
    'sys.path.insert(0, "harness")\n'
    'import run_hardness as rh\n'
    'g = next(x for x in yaml.safe_load(open("harness/gates.yaml"))["gates"] if x["id"] == "H24")\n'
    'ok, detail = rh.CHECKERS["slee_boundary"](g["expect"], rh.CONTRACT)\n'
    'print(("PASS " if ok else "CAUGHT ") + detail)\n')


def add_import(rel, stmt):
    return lambda t: t.replace('import ', stmt + '\nimport ', 1)


def prepend_line(rel, anchor, line):
    def fn(t):
        return t.replace(anchor, line + '\n' + anchor, 1) if anchor in t else t
    return fn


SCENARIOS = [
    ("transport import inside a REST resource", f'{J}/web/AdminResource.java',
     add_import(None, 'import org.restcomm.protocols.ss7.map.api.Maif;')),
    ("SLEE container reached from an SBB", f'{J}/sbbs/VerifySbb.java',
     add_import(None, 'import com.microjainslee.core.MicroSleeContainer;')),
    ("raw JSR-240 SLEE API inside an RA", f'{J}/ras/resolver/RadiusAccountingListenerBackend.java',
     add_import(None, 'import javax.slee.ActivityContextInterface;')),
    ("hand-rolled thread pool in the northbound", f'{A}/api/VerifyResource.java',
     prepend_line(None, 'public class',
                  'private final Object leak = java.util.concurrent.Executors.newFixedThreadPool(4);')),
    ("raw socket in the northbound", f'{A}/api/SessionTupleResource.java',
     prepend_line(None, 'public class',
                  'private final Object sock = new java.net.DatagramSocket(1812) != null ? 1 : 0;')),
    ("RA delegate called from the coordinator", f'{J}/coordinator/VerifyCoordinator.java',
     prepend_line(None, 'public class',
                  'private static Object peek(et.restlink.sas.bootstrap.SasBootstrap b) { return b.resolverBackend(); }')),
    ("second SLEE runtime added to a pom", 'sas-host/pom.xml',
     prepend_line(None, '<dependencies>',
                  '<dependency><groupId>javax.slee</groupId><artifactId>slee-api</artifactId><version>2.4</version></dependency>')),
    ("debt repaid, stale allow-list entry kept", f'{A}/api/SessionTupleResource.java',
     lambda t: t.replace('bootstrap.resolverBackend()', 'bootstrap.resolverBackendSealed()')),
    ("micro-jainslee pinned locally in a child pom", 'sas-api/pom.xml',
     lambda t: t.replace('<artifactId>jainslee-api</artifactId>',
                         '<artifactId>jainslee-api</artifactId>\n      <version>1.1.0</version>', 1)),
    ("wiring built outside the container", f'{J}/bootstrap/SasBootstrap.java',
     prepend_line(None, 'container.registerRa(',
                  'java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> { });')),
]


def run():
    p = subprocess.run([sys.executable, "-c", PRELUDE], cwd=ROOT,
                       capture_output=True, text=True)
    lines = (p.stdout or p.stderr).strip().splitlines()
    return lines[0] if lines else "(no output)"


def main():
    files = sorted({rel for _, rel, _ in SCENARIOS})
    originals = {f: (ROOT / f).read_text() for f in files}
    base = run()
    print(f"[baseline] {base}")
    healthy = base.startswith("PASS")
    caught = missed = 0
    try:
        for i, (name, rel, fn) in enumerate(SCENARIOS, 1):
            text = originals[rel]
            mutated = fn(text)
            if mutated == text:
                print(f"[M{i:02d}] NO-OP (anchor missing) — {name}")
                missed += 1
                continue
            (ROOT / rel).write_text(mutated)
            out = run()
            (ROOT / rel).write_text(text)
            hit = out.startswith("CAUGHT")
            caught += hit
            missed += not hit
            print(f"[M{i:02d}] {'caught' if hit else 'MISSED'} — {name} :: {out[:170]}")
    finally:
        for f, text in originals.items():
            (ROOT / f).write_text(text)
    print(f"== {caught} caught, {missed} missed, baseline_ok={healthy} ==")
    return 1 if (missed or not healthy) else 0


if __name__ == "__main__":
    sys.exit(main())