#!/usr/bin/env python3
"""Minimal technical SVG diagrams for Digicom-ET Silent Auth Technical v2 deck."""

from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "assets" / "v2"
OUT.mkdir(parents=True, exist_ok=True)

GREEN = "#078930"
YELLOW = "#FCDD09"
RED = "#DA121A"
DARK = "#1A1A2E"
CREAM = "#FFF8E7"
TEAL = "#0D7377"
WHITE = "#FFFFFF"
GRAY = "#6B7280"
LIGHT = "#E8F5E9"


def wrap(w, h, body, title=""):
    t = f'<text x="{w/2}" y="28" text-anchor="middle" font-family="Calibri,sans-serif" font-size="14" font-weight="bold" fill="{DARK}">{title}</text>' if title else ""
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
  <rect width="{w}" height="{h}" fill="{CREAM}" rx="8"/>
  <rect x="0" y="0" width="{w}" height="6" fill="{GREEN}"/>
  <rect x="0" y="6" width="{w}" height="4" fill="{YELLOW}"/>
  <rect x="0" y="10" width="{w}" height="4" fill="{RED}"/>
  {t}
  {body}
</svg>'''


def actor(x, y, label, w=90, h=36):
    return f'''
  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{WHITE}" stroke="{TEAL}" stroke-width="1.5"/>
  <text x="{x+w/2}" y="{y+h/2+5}" text-anchor="middle" font-family="Calibri,sans-serif" font-size="11" fill="{DARK}">{label}</text>'''


def arrow(x1, y1, x2, y2, label="", dashed=False):
    dash = 'stroke-dasharray="4,3"' if dashed else ""
    mid_x, mid_y = (x1 + x2) / 2, (y1 + y2) / 2
    lbl = f'<text x="{mid_x}" y="{mid_y-4}" text-anchor="middle" font-family="Calibri,sans-serif" font-size="9" fill="{GRAY}">{label}</text>' if label else ""
    return f'''
  <line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{DARK}" stroke-width="1.2" marker-end="url(#arr)" {dash}/>
  {lbl}'''


def markers():
    return '''
  <defs>
    <marker id="arr" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#1A1A2E"/>
    </marker>
  </defs>'''


def e2e_sequence():
    body = markers() + """
  """ + actor(20, 50, "App") + actor(130, 50, "Bank BE") + actor(240, 50, "SAS")
    body += actor(350, 50, "Resolver") + actor(460, 50, "Verifier") + actor(560, 50, "HLR/HSS")
    y = 110
    steps = [
        (1, "POST /login", 20, 130),
        (2, "POST /verify {IP,port,ts}", 130, 240),
        (3, "resolve(IP,port,ts)", 240, 350),
        (4, "MSISDN+IMSI", 350, 240, True),
        (5, "verify(MSISDN)", 240, 460),
        (6, "ATI/PSI or IDR/AIR", 460, 560),
        (7, "subscriberState", 560, 460, True),
        (8, "{match:true}", 460, 240, True),
        (9, "Login OK", 240, 130, True),
        (10, "Authenticated", 130, 20, True),
    ]
    for item in steps:
        n, lbl = item[0], item[1]
        x1, x2 = item[2], item[3]
        dashed = item[4] if len(item) > 4 else False
        ax1, ax2 = x1 + 45, x2 + 45
        body += arrow(ax1, y, ax2, y, f"{n}. {lbl}", dashed)
        y += 28
    return wrap(640, 420, body, "E2E Message Flow (numbered steps)")


def ati_flow():
    body = markers() + actor(30, 60, "SAS Verifier", 100) + actor(200, 60, "MAP TCAP", 90)
    body += actor(360, 60, "HLR", 80) + actor(480, 60, "VLR/SGSN", 90)
    body += f'''
  <text x="30" y="130" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">TC-BEGIN → ATI Request</text>
  <text x="30" y="148" font-family="Calibri,sans-serif" font-size="10" fill="{GRAY}">subscriberIdentity (MSISDN/IMSI)</text>
  <text x="30" y="166" font-family="Calibri,sans-serif" font-size="10" fill="{GRAY}">requestedInfo: locationInfo, subscriberState</text>
  {arrow(130, 190, 245, 190, "MAP ATI")}
  {arrow(405, 210, 525, 210, "interrogate")}
  {arrow(525, 230, 405, 230, "response", True)}
  {arrow(245, 250, 130, 250, "ATI Response", True)}
  <rect x="30" y="280" width="580" height="44" rx="4" fill="{LIGHT}" stroke="{GREEN}"/>
  <text x="40" y="300" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">FS.11 Cat.1 — ATI blocked on interconnect</text>
  <text x="40" y="316" font-family="Calibri,sans-serif" font-size="10" fill="{TEAL}">Deploy Digicom SAS inside operator network</text>'''
    return wrap(640, 360, body, "ATI Deep Dive — TC Dialog")


def psi_sai():
    body = f'''
  <rect x="20" y="50" width="280" height="260" rx="6" fill="{WHITE}" stroke="{GREEN}" stroke-width="2"/>
  <text x="160" y="75" text-anchor="middle" font-family="Calibri,sans-serif" font-size="13" font-weight="bold" fill="{GREEN}">PSI (Cat 2.1)</text>
  <text x="35" y="100" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">ProvideSubscriberInfo</text>
  <text x="35" y="120" font-family="Calibri,sans-serif" font-size="10" fill="{GRAY}">subscriber state + location</text>
  <text x="35" y="140" font-family="Calibri,sans-serif" font-size="10" fill="{GRAY}">intra-network only</text>
  <text x="35" y="170" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">Use: reachable check</text>
  <text x="35" y="190" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">VLR/SGSN address</text>
  <rect x="340" y="50" width="280" height="260" rx="6" fill="{WHITE}" stroke="{TEAL}" stroke-width="2"/>
  <text x="480" y="75" text-anchor="middle" font-family="Calibri,sans-serif" font-size="13" font-weight="bold" fill="{TEAL}">SAI (Cat 3.2)</text>
  <text x="355" y="100" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">SendAuthenticationInfo</text>
  <text x="355" y="120" font-family="Calibri,sans-serif" font-size="10" fill="{GRAY}">auth vectors / Ki freshness</text>
  <text x="355" y="140" font-family="Calibri,sans-serif" font-size="10" fill="{GRAY}">SIM-swap signal</text>
  <text x="355" y="170" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">Compare lastUpdateLocation</text>
  <text x="355" y="190" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">Fresh swap → FALLBACK</text>
  {arrow(300, 180, 340, 180, "complement")}'''
    return wrap(640, 340, body, "PSI vs SAI")


def diameter_s6a():
    body = markers() + actor(40, 60, "SAS Verifier", 100) + actor(220, 60, "MME/SGW", 90)
    body += actor(380, 60, "HSS (S6a)", 100)
    body += f'''
  <text x="40" y="130" font-family="Calibri,sans-serif" font-size="11" font-weight="bold" fill="{DARK}">IDR/IDA — Insert Subscriber Data Request/Answer</text>
  {arrow(140, 150, 265, 150, "IDR")}
  {arrow(265, 170, 430, 170, "forward")}
  {arrow(430, 190, 265, 190, "IDA", True)}
  {arrow(265, 210, 140, 210, "subscriber data", True)}
  <text x="40" y="240" font-family="Calibri,sans-serif" font-size="11" font-weight="bold" fill="{DARK}">AIR/AIA — Authentication Info Request/Answer</text>
  {arrow(140, 260, 430, 260, "AIR")}
  {arrow(430, 280, 140, 280, "AIA (vectors)", True)}
  <rect x="40" y="300" width="500" height="30" rx="4" fill="{LIGHT}"/>
  <text x="50" y="320" font-family="Calibri,sans-serif" font-size="10" fill="{TEAL}">GSMA FS.19 — 4G/5G verifier path</text>'''
    return wrap(580, 360, body, "Diameter S6a Path")


def fsm():
    states = [
        ("RESOLVING", 80, 80, GREEN),
        ("VERIFYING", 280, 80, TEAL),
        ("SCORING", 480, 80, TEAL),
        ("APPROVED", 580, 200, GREEN),
        ("FALLBACK", 180, 220, RED),
    ]
    body = markers()
    for name, x, y, color in states:
        body += f'''
  <rect x="{x}" y="{y}" width="110" height="40" rx="20" fill="{WHITE}" stroke="{color}" stroke-width="2"/>
  <text x="{x+55}" y="{y+25}" text-anchor="middle" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">{name}</text>'''
    body += arrow(190, 100, 280, 100, "binding OK")
    body += arrow(390, 100, 480, 100, "HSS OK")
    body += arrow(535, 120, 635, 190, "score ≥ threshold")
    body += arrow(135, 120, 235, 210, "no binding", True)
    body += arrow(335, 120, 235, 210, "timeout", True)
    body += arrow(535, 120, 235, 210, "low score", True)
    body += f'''
  <text x="320" y="300" text-anchor="middle" font-family="Calibri,sans-serif" font-size="11" font-weight="bold" fill="{RED}">Fail-closed — no partial approvals</text>'''
    return wrap(720, 330, body, "SAS Request FSM")


def timeout():
    rows = [
        ("Resolver lookup", "300 ms", "FALLBACK"),
        ("MAP dialog (PSI/ATI)", "2 s", "abort dialog → FALLBACK"),
        ("Diameter S6a (IDR/AIR)", "2 s", "FALLBACK"),
        ("Total SAS budget", "3 s", "bank shows normal login"),
    ]
    body = f'''
  <rect x="30" y="50" width="520" height="28" fill="{GREEN}"/>
  <text x="50" y="69" font-family="Calibri,sans-serif" font-size="11" font-weight="bold" fill="{WHITE}">Stage</text>
  <text x="220" y="69" font-family="Calibri,sans-serif" font-size="11" font-weight="bold" fill="{WHITE}">Budget</text>
  <text x="380" y="69" font-family="Calibri,sans-serif" font-size="11" font-weight="bold" fill="{WHITE}">On expiry</text>'''
    y = 78
    for i, (stage, budget, action) in enumerate(rows):
        bg = WHITE if i % 2 == 0 else LIGHT
        body += f'''
  <rect x="30" y="{y}" width="520" height="36" fill="{bg}"/>
  <text x="50" y="{y+22}" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">{stage}</text>
  <text x="220" y="{y+22}" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">{budget}</text>
  <text x="380" y="{y+22}" font-family="Calibri,sans-serif" font-size="10" fill="{TEAL}">{action}</text>'''
        y += 36
    body += f'''
  <rect x="30" y="{y+10}" width="520" height="50" rx="4" fill="{CREAM}" stroke="{RED}"/>
  <text x="40" y="{y+30}" font-family="Calibri,sans-serif" font-size="10" fill="{DARK}">SAS = dialog anchor — bounded TC timer on every MAP dialog</text>
  <text x="40" y="{y+48}" font-family="Calibri,sans-serif" font-size="10" fill="{RED}">timeout ⇒ abort() — no dialog leak</text>'''
    return wrap(580, 320, body, "Timeout & Dialog Leak Strategy")


def main():
    files = {
        "v2_e2e_sequence.svg": e2e_sequence(),
        "v2_ati_flow.svg": ati_flow(),
        "v2_psi_sai.svg": psi_sai(),
        "v2_diameter_s6a.svg": diameter_s6a(),
        "v2_fsm.svg": fsm(),
        "v2_timeout.svg": timeout(),
    }
    for name, content in files.items():
        path = OUT / name
        path.write_text(content, encoding="utf-8")
        print(f"  {path.name}")
    print(f"Generated {len(files)} SVGs → {OUT}")


if __name__ == "__main__":
    main()
