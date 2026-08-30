#!/usr/bin/env python3
"""Generate technical SVG diagrams for Digicom-ET Silent Auth v2 deck."""

from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "assets" / "v2"
OUT.mkdir(parents=True, exist_ok=True)

# Ethiopia palette
GREEN = "#078930"
YELLOW = "#FCDD09"
RED = "#DA121A"
DARK = "#1A1A2E"
CREAM = "#FFF8E7"
TEAL = "#0D7377"
GOLD = "#C9A227"
WHITE = "#FFFFFF"
GRAY = "#6B7280"
LIGHT = "#F3F4F6"

FONT = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
FONT_SANS = "Arial, Helvetica, sans-serif"


def svg_wrap(w, h, body, bg=CREAM):
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
  <rect width="{w}" height="{h}" fill="{bg}" rx="8"/>
  {body}
</svg>
'''


def esc(text):
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def marker_defs(prefix, color=GOLD):
    return f'''
  <defs>
    <marker id="{prefix}-arr" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
      <path d="M0,0 L7,3 L0,6 Z" fill="{color}"/>
    </marker>
    <marker id="{prefix}-arr-rev" markerWidth="8" markerHeight="8" refX="0" refY="3" orient="auto">
      <path d="M7,0 L0,3 L7,6 Z" fill="{color}"/>
    </marker>
  </defs>
'''


def box(x, y, w, h, title, lines=(), fill=WHITE, stroke=TEAL, title_color=DARK, mono=True):
    ff = FONT if mono else FONT_SANS
    body = f'''
  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="6" fill="{fill}" stroke="{stroke}" stroke-width="2"/>
  <text x="{x + w/2}" y="{y + 22}" text-anchor="middle" font-family="{FONT_SANS}" font-size="13"
        font-weight="bold" fill="{title_color}">{esc(title)}</text>
  <line x1="{x + 8}" y1="{y + 30}" x2="{x + w - 8}" y2="{y + 30}" stroke="{stroke}" stroke-width="1" opacity="0.4"/>
'''
    for i, line in enumerate(lines):
        body += f'''
  <text x="{x + 12}" y="{y + 48 + i * 16}" font-family="{ff}" font-size="11" fill="{DARK}">{esc(line)}</text>
'''
    return body


def note_box(x, y, w, h, text, stroke=RED, fill=None):
    fill = fill or f"{RED}18"
    return f'''
  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{fill}" stroke="{stroke}" stroke-width="1.5" stroke-dasharray="4,3"/>
  <text x="{x + 10}" y="{y + 18}" font-family="{FONT}" font-size="10" fill="{DARK}">{esc(text)}</text>
'''


def arrow_h(x1, y, x2, label="", color=GOLD, marker="arr", dashed=False, num=""):
    dash = ' stroke-dasharray="5,4"' if dashed else ""
    mid = (x1 + x2) / 2
    lbl = ""
    if label:
        lbl = f'<text x="{mid}" y="{y - 8}" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{DARK}">{esc(label)}</text>'
    num_lbl = ""
    if num:
        num_lbl = f'''
  <circle cx="{mid}" cy="{y}" r="9" fill="{TEAL}" stroke="{WHITE}" stroke-width="1"/>
  <text x="{mid}" y="{y + 4}" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" fill="{WHITE}" font-weight="bold">{esc(num)}</text>
'''
    return f'''
  <line x1="{x1}" y1="{y}" x2="{x2 - 6}" y2="{y}" stroke="{color}" stroke-width="2"{dash}
        marker-end="url(#{marker}-arr)"/>
  {lbl}
  {num_lbl}
'''


def arrow_v(x, y1, y2, label="", color=GOLD, marker="arr", dashed=False, lx=0):
    dash = ' stroke-dasharray="5,4"' if dashed else ""
    mid = (y1 + y2) / 2
    lbl = ""
    if label:
        lbl = f'<text x="{x + lx}" y="{mid}" font-family="{FONT}" font-size="10" fill="{DARK}">{esc(label)}</text>'
    return f'''
  <line x1="{x}" y1="{y1}" x2="{x}" y2="{y2 - 6}" stroke="{color}" stroke-width="2"{dash}
        marker-end="url(#{marker}-arr)"/>
  {lbl}
'''


def title(text, w=800, y=28):
    return f'''
  <text x="{w/2}" y="{y}" text-anchor="middle" font-family="{FONT_SANS}" font-size="15"
        font-weight="bold" fill="{DARK}">{esc(text)}</text>
'''


def chip(cx, y, w, lines, color=GOLD):
    """Small self-label chip centered at cx (used for in-box steps)."""
    h = 14 * len(lines) + 12
    body = f'''
  <rect x="{cx - w/2}" y="{y}" width="{w}" height="{h}" rx="4" fill="{color}" opacity="0.12" stroke="{color}" stroke-width="1"/>
'''
    for i, line in enumerate(lines):
        body += f'''
  <text x="{cx}" y="{y + 15 + i * 14}" text-anchor="middle" font-family="{FONT}" font-size="9.5" fill="{DARK}">{esc(line)}</text>
'''
    return body


def make_two_stage():
    w, h = 800, 360
    body = marker_defs("ts") + title("Two-stage architecture — MAP cannot resolve IP→MSISDN", w)
    body += f'''
  <text x="400" y="52" text-anchor="middle" font-family="{FONT}" font-size="11" fill="{GRAY}">
    IP:port:ts ──[Resolver]──► MSISDN/IMSI ──[Verifier]──► assurance
  </text>
'''
    body += box(60, 90, 280, 130, "Resolver", [
        "Input:  srcIP, srcPort, ts",
        "Source: PGW / GGSN / CGNAT",
        "Output: MSISDN, IMSI, bearerAge",
    ], stroke=GREEN, title_color=GREEN)

    body += box(460, 90, 280, 130, "Verifier", [
        "Input:  MSISDN / IMSI",
        "MAP:    ATI / PSI / SAI",
        "Diam:   IDR / AIR (S6a)",
    ], stroke=TEAL, title_color=TEAL)

    body += arrow_h(340, 155, 460, "MSISDN + IMSI", GREEN, "ts")

    body += f'''
  <rect x="120" y="240" width="560" height="44" rx="6" fill="{RED}14" stroke="{RED}" stroke-width="1.5"/>
  <text x="400" y="260" text-anchor="middle" font-family="{FONT}" font-size="11" fill="{RED}" font-weight="bold">
    MAP / Diameter cannot map IP → MSISDN
  </text>
  <text x="400" y="276" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{DARK}">
    PGW session binding required · Verifier only checks subscriber state
  </text>
'''
    (OUT / "v2_two_stage.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_e2e_sequence():
    w, h = 800, 400
    body = marker_defs("e2e") + title("End-to-end verify flow", w)

    lanes = [
        (70, "App"),
        (190, "Bank BE"),
        (310, "Digicom SAS"),
        (430, "PGW Resolver"),
        (550, "MAP Verifier"),
        (670, "HLR/HSS"),
    ]
    for x, name in lanes:
        body += f'''
  <line x1="{x}" y1="60" x2="{x}" y2="340" stroke="{GRAY}" stroke-width="1" stroke-dasharray="3,4" opacity="0.5"/>
  <rect x="{x - 42}" y="64" width="84" height="24" rx="4" fill="{DARK}" opacity="0.08" stroke="{DARK}" stroke-width="1"/>
  <text x="{x}" y="80" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" font-weight="bold" fill="{DARK}">{esc(name)}</text>
'''
    # Forward messages
    msgs = [
        (70, 190, 110, "POST /verify", "1"),
        (190, 310, 130, "POST /verify", "2"),
        (310, 430, 150, "resolve()", "3"),
        (310, 550, 170, "verify()", "4"),
        (550, 670, 190, "ATI / PSI", "5"),
    ]
    for x1, x2, y, lbl, num in msgs:
        body += arrow_h(x1, y, x2, lbl, TEAL, "e2e", num=num)

    # Return path
    body += f'''
  <line x1="670" y1="220" x2="74" y2="220" stroke="{GREEN}" stroke-width="2" stroke-dasharray="6,4"
        marker-end="url(#e2e-arr-rev)"/>
  <text x="372" y="212" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GREEN}">subscriberState → score → Approve</text>
  <circle cx="372" cy="220" r="9" fill="{GREEN}" stroke="{WHITE}" stroke-width="1"/>
  <text x="372" y="224" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" fill="{WHITE}" font-weight="bold">6</text>
'''
    body += f'''
  <rect x="40" y="300" width="720" height="52" rx="6" fill="{TEAL}12" stroke="{TEAL}" stroke-width="1"/>
  <text x="400" y="322" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{DARK}">
    App → Bank BE → Digicom SAS → PGW Resolver → MAP Verifier → HLR → Approve
  </text>
  <text x="400" y="340" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    Labels: POST /verify · resolve() · ATI/PSI · subscriberState
  </text>
'''
    (OUT / "v2_e2e_sequence.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_ati_flow():
    w, h = 800, 380
    body = marker_defs("ati") + title("MAP AnyTimeInterrogation (ATI) — 2G/3G", w)

    body += box(80, 100, 220, 100, "Digicom Verifier", [
        "MAP dialog anchor",
        "TCAP TC-BEGIN",
    ], stroke=TEAL, title_color=TEAL)

    body += box(500, 100, 220, 100, "HLR", [
        "Intra-network only",
        "subscriber DB",
    ], stroke=DARK, title_color=DARK)

    body += arrow_h(300, 150, 500, "TC-BEGIN / ATI", TEAL, "ati")
    body += arrow_h(500, 190, 300, "ATI-Res (subscriberState, locationInfo)", GREEN, "ati")

    body += note_box(140, 240, 520, 52,
                     "FS.11 Cat 1 = interconnect BLOCKED → intra-network HLR only")

    body += f'''
  <text x="400" y="330" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    AnyTimeInterrogationRequest → subscriberState, locationInfo (VLR/SGSN)
  </text>
  <text x="400" y="348" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    No cross-operator ATI · deployment invariant
  </text>
'''
    (OUT / "v2_ati_flow.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_psi_sai():
    w, h = 800, 420
    body = marker_defs("ps") + title("Verifier signalling — PSI vs SAI", w)

    body += box(50, 70, 330, 200, "PSI — ProvideSubscriberInfo", [
        "FS.11 Category 2.1",
        "subscriberState + locationInfo",
        "Primary reachability check",
        "Intra-network HLR query",
        "",
        "When Digicom uses PSI:",
        "• Default 2G/3G verify path",
        "• Check attached / reachable",
        "• VLR/SGSN location plausibility",
    ], stroke=GREEN, title_color=GREEN)

    body += box(420, 70, 330, 200, "SAI — SendAuthenticationInfo", [
        "FS.11 Category 3.2",
        "Auth vectors / key freshness",
        "SIM-swap detection signal",
        "Compare lastUpdate age",
        "",
        "When Digicom uses SAI:",
        "• High-risk transactions",
        "• Fresh IMSI change detected",
        "• Supplement PSI for swap age",
    ], stroke=TEAL, title_color=TEAL)

    body += f'''
  <rect x="50" y="290" width="700" height="70" rx="6" fill="{YELLOW}30" stroke="{GOLD}" stroke-width="1.5"/>
  <text x="400" y="312" text-anchor="middle" font-family="{FONT_SANS}" font-size="12" font-weight="bold" fill="{DARK}">
    Policy: PSI first → SAI if swap-risk or high assurance needed
  </text>
  <text x="400" y="332" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{DARK}">
    Fresh swap (minutes/hours) → downgrade assurance → FALLBACK (SMS)
  </text>
  <text x="400" y="350" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    jSS7: ProvideSubscriberInfoRequestImpl · SendAuthenticationInfoRequestImpl
  </text>
'''
    (OUT / "v2_psi_sai.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_diameter_s6a():
    w, h = 800, 380
    body = marker_defs("s6a") + title("4G/5G path — Diameter S6a (DEA/HSS)", w)

    body += box(80, 110, 200, 90, "Digicom Verifier", [
        "jDiameter client",
        "S6a interface",
    ], stroke=TEAL, title_color=TEAL)

    body += box(520, 110, 200, 90, "DEA / HSS", [
        "4G/5G subscriber",
        "authentication DB",
    ], stroke=DARK, title_color=DARK)

    body += arrow_h(280, 140, 520, "IDR (Insert-Subscriber-Data-Request)", TEAL, "s6a")
    body += arrow_h(520, 170, 280, "IDA (Insert-Subscriber-Data-Answer)", GREEN, "s6a")

    body += arrow_h(280, 200, 520, "AIR (Authentication-Information-Request)", GOLD, "s6a")
    body += arrow_h(520, 230, 280, "AIA (Authentication-Information-Answer)", GREEN, "s6a")

    body += f'''
  <rect x="100" y="270" width="600" height="60" rx="6" fill="{TEAL}10" stroke="{TEAL}" stroke-width="1"/>
  <text x="400" y="292" text-anchor="middle" font-family="{FONT}" font-size="11" fill="{DARK}">
    IDR/IDA — inspect subscriber data · reachable, location, profile
  </text>
  <text x="400" y="312" text-anchor="middle" font-family="{FONT}" font-size="11" fill="{DARK}">
    AIR/AIA — auth vectors · SIM-swap freshness (mirror MAP SAI)
  </text>
'''
    (OUT / "v2_diameter_s6a.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_fsm():
    w, h = 800, 400
    body = marker_defs("fsm") + title("SAS per-request state machine", w)

    states = [
        (100, 120, "RESOLVING", TEAL, "IP→MSISDN lookup"),
        (280, 120, "VERIFYING", GREEN, "MAP/Diameter dialog"),
        (460, 120, "SCORING", GOLD, "Policy weights"),
        (640, 120, "APPROVED", GREEN, "assurance ≥ threshold"),
    ]
    for x, y, name, color, sub in states:
        body += f'''
  <rect x="{x}" y="{y}" width="120" height="56" rx="8" fill="{color}" opacity="0.15" stroke="{color}" stroke-width="2"/>
  <text x="{x + 60}" y="{y + 24}" text-anchor="middle" font-family="{FONT}" font-size="11"
        font-weight="bold" fill="{color}">{esc(name)}</text>
  <text x="{x + 60}" y="{y + 42}" text-anchor="middle" font-family="{FONT}" font-size="9" fill="{DARK}">{esc(sub)}</text>
'''
    for i in range(3):
        x1 = states[i][0] + 120
        x2 = states[i + 1][0]
        body += arrow_h(x1, 148, x2, color=GOLD, marker="fsm")

    # FALLBACK box
    body += f'''
  <rect x="280" y="240" width="240" height="56" rx="8" fill="{RED}18" stroke="{RED}" stroke-width="2"/>
  <text x="400" y="264" text-anchor="middle" font-family="{FONT}" font-size="12" font-weight="bold" fill="{RED}">FALLBACK</text>
  <text x="400" y="282" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{DARK}">SMS OTP / TOTP / Passkey</text>
'''
    # Fail transitions
    fail_from = [
        (160, 176, "no binding"),
        (340, 176, "timeout"),
        (520, 176, "low score"),
    ]
    for x, y1, lbl in fail_from:
        body += arrow_v(x, y1, 240, lbl, RED, "fsm", dashed=True, lx=8)

    body += f'''
  <text x="400" y="340" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    Fail-closed: any stage without evidence → FALLBACK · no partial approvals
  </text>
  <text x="400" y="358" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    One MAP/Diameter dialog max per stage · reqId idempotency
  </text>
'''
    (OUT / "v2_fsm.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_timeout():
    w, h = 800, 380
    body = marker_defs("to") + title("Timeout budget — SAS is dialog anchor", w)

    # Timeline bar
    body += f'''
  <line x1="60" y1="140" x2="740" y2="140" stroke="{DARK}" stroke-width="2"/>
  <line x1="60" y1="130" x2="60" y2="150" stroke="{DARK}" stroke-width="2"/>
  <line x1="740" y1="130" x2="740" y2="150" stroke="{DARK}" stroke-width="2"/>
  <text x="60" y="165" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">0 ms</text>
  <text x="740" y="165" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">3000 ms</text>
'''
    # Segments: Resolver 300ms, MAP 2s, total 3s
    segs = [
        (60, 140, GREEN, "Resolver", "300 ms", "PGW lookup · FALLBACK on miss"),
        (140, 520, TEAL, "MAP dialog", "2 s", "TC dialog timer · PSI/ATI/SAI"),
        (60, 680, GOLD, "Total SAS", "≤ 3 s", "Bank shows normal login"),
    ]
    for x, x_end, color, name, budget, note in segs:
        width = x_end - x
        body += f'''
  <rect x="{x}" y="100" width="{width}" height="28" rx="4" fill="{color}" opacity="0.25" stroke="{color}" stroke-width="1.5"/>
  <text x="{x + width/2}" y="118" text-anchor="middle" font-family="{FONT}" font-size="10" font-weight="bold" fill="{color}">{esc(name)} {esc(budget)}</text>
  <text x="{x + width/2}" y="190" text-anchor="middle" font-family="{FONT}" font-size="9" fill="{DARK}">{esc(note)}</text>
'''
    body += f'''
  <rect x="80" y="230" width="640" height="80" rx="6" fill="{RED}10" stroke="{RED}" stroke-width="1.5"/>
  <text x="400" y="254" text-anchor="middle" font-family="{FONT_SANS}" font-size="12" font-weight="bold" fill="{RED}">
    On MAP/Diameter timeout: abort dialog → FALLBACK
  </text>
  <text x="400" y="276" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{DARK}">
    SAS never lets hung HSS query stall the app · no dialog leak
  </text>
  <text x="400" y="296" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    Diameter S6a (IDR/AIR): same 2 s budget · Resolver: 300 ms hard cap
  </text>
'''
    (OUT / "v2_timeout.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_camara_nv_callflow():
    w, h = 800, 560
    body = marker_defs("nv") + title("CAMARA Number Verification — /verify code call flow", w)

    lanes = [
        (100, "Bank Backend"),
        (250, "SAS /verify"),
        (400, "PGW Resolver"),
        (550, "Verifier"),
        (700, "HLR / HSS"),
    ]
    for x, name in lanes:
        body += f'''
  <line x1="{x}" y1="60" x2="{x}" y2="520" stroke="{GRAY}" stroke-width="1" stroke-dasharray="3,4" opacity="0.5"/>
  <rect x="{x - 46}" y="64" width="92" height="24" rx="4" fill="{DARK}" opacity="0.08" stroke="{DARK}" stroke-width="1"/>
  <text x="{x}" y="80" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" font-weight="bold" fill="{DARK}">{esc(name)}</text>
'''
    body += arrow_h(100, 112, 250, "POST /verify · 3-legged token", TEAL, "nv", num="1")
    body += chip(250, 140, 150, ["validate JWT: scope · amr", "jti single-use ≤ 300 s"])
    body += arrow_h(250, 212, 400, "resolve(srcIP, srcPort, ts)", TEAL, "nv", num="2")
    body += f'''
  <line x1="400" y1="240" x2="254" y2="240" stroke="{GREEN}" stroke-width="2" stroke-dasharray="6,4"
        marker-end="url(#nv-arr-rev)"/>
  <text x="325" y="232" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GREEN}">MSISDN + IMSI + bearerAge</text>
  <circle cx="325" cy="240" r="9" fill="{GREEN}" stroke="{WHITE}" stroke-width="1"/>
  <text x="325" y="244" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" fill="{WHITE}" font-weight="bold">3</text>
'''
    body += arrow_h(250, 268, 550, "verify(MSISDN / IMSI)", TEAL, "nv", num="4")
    body += arrow_h(550, 296, 700, "IDR/AIR (S6a) · PSI (MAP)", TEAL, "nv", num="5")
    body += f'''
  <line x1="700" y1="324" x2="554" y2="324" stroke="{GREEN}" stroke-width="2" stroke-dasharray="6,4"
        marker-end="url(#nv-arr-rev)"/>
  <text x="627" y="316" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GREEN}">subscriberState + lastUpdate</text>
  <circle cx="627" cy="324" r="9" fill="{GREEN}" stroke="{WHITE}" stroke-width="1"/>
  <text x="627" y="328" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" fill="{WHITE}" font-weight="bold">6</text>
'''
    body += f'''
  <line x1="550" y1="352" x2="254" y2="352" stroke="{GREEN}" stroke-width="2" stroke-dasharray="6,4"
        marker-end="url(#nv-arr-rev)"/>
  <text x="402" y="344" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GREEN}">reachable · notSimSwapped · plausible</text>
  <circle cx="402" cy="352" r="9" fill="{GREEN}" stroke="{WHITE}" stroke-width="1"/>
  <text x="402" y="356" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" fill="{WHITE}" font-weight="bold">7</text>
'''
    body += chip(250, 376, 170, ["Policy scoring ≥ threshold", "resolved == claimed?"])
    body += f'''
  <line x1="250" y1="440" x2="104" y2="440" stroke="{GREEN}" stroke-width="2" stroke-dasharray="6,4"
        marker-end="url(#nv-arr-rev)"/>
  <text x="177" y="430" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GREEN}">200 {esc("{devicePhoneNumberVerified:true}")}</text>
  <circle cx="177" cy="440" r="9" fill="{GREEN}" stroke="{WHITE}" stroke-width="1"/>
  <text x="177" y="444" text-anchor="middle" font-family="{FONT_SANS}" font-size="10" fill="{WHITE}" font-weight="bold">8</text>
'''
    body += f'''
  <rect x="40" y="472" width="720" height="44" rx="6" fill="{RED}14" stroke="{RED}" stroke-width="1"/>
  <text x="400" y="490" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{RED}" font-weight="bold">
    Fail-closed: any timeout / no binding / score below threshold → 403, never soft-pass
  </text>
  <text x="400" y="506" text-anchor="middle" font-family="{FONT}" font-size="9" fill="{DARK}">
    MSISDN/IMSI stays on bank backend · never returned to the app · reqId dedup
  </text>
'''
    (OUT / "v2_camara_nv_callflow.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_fallback_decision():
    w, h = 800, 540
    body = marker_defs("fd") + title("Silent-auth path decision by access technology", w)

    body += box(300, 60, 200, 46, "Auth request", ["login · /verify"], stroke=DARK, title_color=DARK)

    body += box(60, 150, 300, 78, "Cellular data (5G/4G/3G)", [
        "IP-match: Resolver reads",
        "PGW/PCRF binding",
    ], stroke=GREEN, title_color=GREEN)

    body += box(440, 150, 300, 78, "Wi-Fi / browser", [
        "TS.43 EAP-AKA (SIM credential)",
        "works without cellular radio",
    ], stroke=TEAL, title_color=TEAL)

    body += arrow_v(400, 106, 150, color=DARK, marker="fd")

    body += box(600, 260, 180, 82, "No TS.43 / MNO?", [
        "TOTP / Passkey",
        "SMS OTP (home-routed)",
    ], stroke=RED, title_color=RED)
    body += arrow_v(740, 228, 260, "", RED, "fd", dashed=True)

    body += box(60, 260, 300, 82, "Verifier gates (fail-closed)", [
        "Resolver: IP+port+ts ≤ 300 ms",
        "MAP/Diameter ≤ 2 s · abort on timeout",
    ], stroke=GOLD, title_color=GOLD)

    body += box(440, 260, 136, 82, "SIM proof", [
        "TS.43 handshake",
        "SIM-bound app instance",
    ], stroke=TEAL, title_color=TEAL)

    body += f'''
  <rect x="250" y="380" width="300" height="52" rx="6" fill="{GREEN}16" stroke="{GREEN}" stroke-width="2"/>
  <text x="400" y="402" text-anchor="middle" font-family="{FONT_SANS}" font-size="12" font-weight="bold" fill="{GREEN}">APPROVED — no OTP</text>
  <text x="400" y="420" text-anchor="middle" font-family="{FONT}" font-size="9" fill="{DARK}">assurance ≥ threshold · resolved == claimed</text>
'''
    body += f'''
  <rect x="250" y="464" width="300" height="46" rx="6" fill="{RED}14" stroke="{RED}" stroke-width="2"/>
  <text x="400" y="484" text-anchor="middle" font-family="{FONT_SANS}" font-size="11" font-weight="bold" fill="{RED}">FALLBACK — step-up MFA</text>
  <text x="400" y="500" text-anchor="middle" font-family="{FONT}" font-size="9" fill="{DARK}">missing evidence never approves</text>
'''
    body += arrow_v(210, 228, 260, "", GREEN, "fd")
    body += arrow_v(560, 228, 260, "", TEAL, "fd")
    body += f'''
  <polyline points="690,342 690,402 548,402 548,481" fill="none" stroke="{RED}" stroke-width="2"
            stroke-dasharray="5,4" marker-end="url(#fd-arr)"/>
  <text x="650" y="394" text-anchor="middle" font-family="{FONT}" font-size="9" fill="{RED}">no TS.43 SIM method</text>
'''
    body += f'''
  <line x1="360" y1="301" x2="434" y2="301" stroke="{GRAY}" stroke-width="1.5" stroke-dasharray="5,4" marker-end="url(#fd-arr)"/>
  <line x1="400" y1="301" x2="400" y2="372" stroke="{GRAY}" stroke-width="2" marker-end="url(#fd-arr)"/>
  <text x="414" y="340" text-anchor="start" font-family="{FONT}" font-size="10" fill="{DARK}">score ≥ threshold?</text>
  <line x1="400" y1="432" x2="400" y2="458" stroke="{RED}" stroke-width="2" stroke-dasharray="5,4" marker-end="url(#fd-arr)"/>
  <text x="414" y="450" text-anchor="start" font-family="{FONT}" font-size="10" fill="{RED}">low score</text>
'''
    body += f'''
  <text x="400" y="533" text-anchor="middle" font-family="{FONT}" font-size="9" fill="{GRAY}">
    TS.43 shrinks the Wi-Fi fallback surface · Strategy B still firewalls residual SMS OTP
  </text>
'''
    (OUT / "v2_fallback_decision.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def make_adapter():
    w, h = 800, 360
    body = marker_defs("ad") + title("Deployment — Digicom adapter layer", w)

    body += box(40, 100, 180, 80, "Banks", [
        "CBE · Awash · Dashen",
        "POST /verify API",
    ], stroke=GREEN, title_color=GREEN)

    body += box(310, 85, 180, 110, "Digicom SAS", [
        "Resolver",
        "Verifier",
        "Policy / Fallback",
    ], stroke=TEAL, title_color=TEAL, fill=f"{TEAL}12")

    body += box(580, 100, 180, 80, "Ethio Telecom", [
        "HLR / HSS / PGW",
        "MAP / Diameter",
    ], stroke=DARK, title_color=DARK)

    body += arrow_h(220, 140, 310, "REST /verify", GREEN, "ad")
    body += arrow_h(490, 140, 580, "MAP · Diameter · PGW", TEAL, "ad")

    body += f'''
  <rect x="60" y="230" width="680" height="56" rx="6" fill="{GREEN}10" stroke="{GREEN}" stroke-width="1.5"/>
  <text x="400" y="254" text-anchor="middle" font-family="{FONT_SANS}" font-size="12" font-weight="bold" fill="{GREEN}">
    Digicom does not own SMS revenue
  </text>
  <text x="400" y="274" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{DARK}">
    SMS &amp; interconnect stay with Ethio Telecom · Digicom bills banks for auth API
  </text>
  <text x="400" y="320" text-anchor="middle" font-family="{FONT}" font-size="10" fill="{GRAY}">
    Banks | Digicom SAS (adapter) | Ethio Telecom HLR/HSS/PGW
  </text>
'''
    (OUT / "v2_adapter.svg").write_text(svg_wrap(w, h, body), encoding="utf-8")


def main():
    makers = [
        ("v2_two_stage.svg", make_two_stage),
        ("v2_e2e_sequence.svg", make_e2e_sequence),
        ("v2_ati_flow.svg", make_ati_flow),
        ("v2_psi_sai.svg", make_psi_sai),
        ("v2_diameter_s6a.svg", make_diameter_s6a),
        ("v2_fsm.svg", make_fsm),
        ("v2_camara_nv_callflow.svg", make_camara_nv_callflow),
        ("v2_fallback_decision.svg", make_fallback_decision),
        ("v2_timeout.svg", make_timeout),
        ("v2_adapter.svg", make_adapter),
    ]
    for name, fn in makers:
        fn()
        print(f"  wrote {OUT / name}")
    print(f"Done → {OUT}")


if __name__ == "__main__":
    main()
