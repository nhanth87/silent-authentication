#!/usr/bin/env python3
"""Generate Ethiopia-themed SVG illustrations for Digicom-ET Silent Auth deck."""

import math
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "assets"
OUT.mkdir(parents=True, exist_ok=True)

# Ethiopian flag palette
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


def svg_wrap(w, h, body, bg=CREAM):
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
  <rect width="{w}" height="{h}" fill="{bg}" rx="12"/>
  {body}
</svg>
'''


def flag_stripe(x, y, w, h):
    sh = h / 3
    return f'''
  <rect x="{x}" y="{y}" width="{w}" height="{sh}" fill="{GREEN}"/>
  <rect x="{x}" y="{y + sh}" width="{w}" height="{sh}" fill="{YELLOW}"/>
  <rect x="{x}" y="{y + 2*sh}" width="{w}" height="{sh}" fill="{RED}"/>
'''


def icon_phone(cx, cy, scale=1.0, color=DARK):
    w, h = 36 * scale, 60 * scale
    x, y = cx - w / 2, cy - h / 2
    return f'''
  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{6*scale}" fill="{color}" stroke="{GOLD}" stroke-width="2"/>
  <rect x="{x + 4*scale}" y="{y + 8*scale}" width="{w - 8*scale}" height="{h - 20*scale}" rx="2" fill="{CREAM}"/>
  <circle cx="{cx}" cy="{y + h - 6*scale}" r="{3*scale}" fill="{GOLD}"/>
'''


def icon_bank(cx, cy, scale=1.0):
    w, h = 70 * scale, 50 * scale
    x, y = cx - w / 2, cy - h / 2
    cols = "".join(
        f'<rect x="{x + 12*scale + i*14*scale}" y="{y + 18*scale}" width="{8*scale}" height="{22*scale}" fill="{CREAM}"/>'
        for i in range(4)
    )
    return f'''
  <polygon points="{cx},{y} {x},{y + 14*scale} {x + w},{y + 14*scale}" fill="{GREEN}"/>
  <rect x="{x}" y="{y + 14*scale}" width="{w}" height="{h - 14*scale}" fill="{DARK}"/>
  {cols}
  <rect x="{x - 4*scale}" y="{y + h}" width="{w + 8*scale}" height="{4*scale}" fill="{GOLD}"/>
'''


def icon_tower(cx, cy, scale=1.0):
    return f'''
  <rect x="{cx - 6*scale}" y="{cy - 10*scale}" width="{12*scale}" height="{40*scale}" fill="{DARK}"/>
  <circle cx="{cx}" cy="{cy - 18*scale}" r="{8*scale}" fill="{TEAL}" stroke="{GOLD}" stroke-width="2"/>
  <path d="M {cx - 20*scale},{cy - 10*scale} Q {cx},{cy - 35*scale} {cx + 20*scale},{cy - 10*scale}"
        fill="none" stroke="{GREEN}" stroke-width="2" opacity="0.7"/>
  <path d="M {cx - 28*scale},{cy} Q {cx},{cy - 45*scale} {cx + 28*scale},{cy}"
        fill="none" stroke="{YELLOW}" stroke-width="2" opacity="0.5"/>
'''


def icon_lock(cx, cy, scale=1.0, locked=True):
    color = GREEN if locked else RED
    return f'''
  <rect x="{cx - 14*scale}" y="{cy - 4*scale}" width="{28*scale}" height="{24*scale}" rx="3" fill="{color}"/>
  <path d="M {cx - 8*scale},{cy - 4*scale} V {cy - 16*scale} A {8*scale} {8*scale} 0 0 1 {cx + 8*scale} {cy - 16*scale} V {cy - 4*scale}"
        fill="none" stroke="{DARK}" stroke-width="{3*scale}"/>
  <circle cx="{cx}" cy="{cy + 6*scale}" r="{4*scale}" fill="{CREAM}"/>
'''


def icon_sms(cx, cy, scale=1.0, crossed=False):
    body = f'''
  <rect x="{cx - 28*scale}" y="{cy - 16*scale}" width="{56*scale}" height="{32*scale}" rx="6" fill="{TEAL}"/>
  <polygon points="{cx - 8*scale},{cy + 16*scale} {cx},{cy + 28*scale} {cx + 8*scale},{cy + 16*scale}" fill="{TEAL}"/>
  <text x="{cx}" y="{cy + 4*scale}" text-anchor="middle" font-family="Arial,sans-serif"
        font-size="{12*scale}" fill="{WHITE}" font-weight="bold">SMS OTP</text>
'''
    if crossed:
        body += f'''
  <line x1="{cx - 32*scale}" y1="{cy - 22*scale}" x2="{cx + 32*scale}" y2="{cy + 28*scale}"
        stroke="{RED}" stroke-width="{4*scale}" stroke-linecap="round"/>
'''
    return body


def arrow(x1, y1, x2, y2, color=GOLD, label="", dashed=False):
    mid_x, mid_y = (x1 + x2) / 2, (y1 + y2) / 2 - 12
    lbl = ""
    if label:
        lbl = f'<text x="{mid_x}" y="{mid_y}" text-anchor="middle" font-family="Arial,sans-serif" font-size="11" fill="{DARK}">{label}</text>'
    dash = ' stroke-dasharray="6,4"' if dashed else ""
    return f'''
  <defs>
    <marker id="arr-{int(x1)}-{int(y1)}" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L6,3 L0,6 Z" fill="{color}"/>
    </marker>
  </defs>
  <line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="2.5"{dash}
        marker-end="url(#arr-{int(x1)}-{int(y1)})"/>
  {lbl}
'''


def ethiopia_star(cx, cy, r, fill=YELLOW, stroke=RED, sw=1.5):
    """Five-point star like the Ethiopian flag emblem."""
    pts = []
    for i in range(10):
        ang = -90 + i * 36
        rad = r if i % 2 == 0 else r * 0.38
        pts.append(f"{cx + rad * math.cos(math.radians(ang))},{cy + rad * math.sin(math.radians(ang))}")
    return f'<polygon points="{" ".join(pts)}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>'


def addis_skyline(x, y, w, h):
    """Minimal Addis Ababa skyline silhouette."""
    return f'''
  <g opacity="0.18">
    <rect x="{x}" y="{y + h * 0.55}" width="{w * 0.08}" height="{h * 0.45}" fill="{DARK}"/>
    <rect x="{x + w * 0.1}" y="{y + h * 0.35}" width="{w * 0.06}" height="{h * 0.65}" fill="{DARK}"/>
    <rect x="{x + w * 0.18}" y="{y + h * 0.2}" width="{w * 0.1}" height="{h * 0.8}" fill="{TEAL}"/>
    <polygon points="{x + w * 0.32},{y + h} {x + w * 0.36},{y + h * 0.15} {x + w * 0.4},{y + h}" fill="{GREEN}"/>
    <rect x="{x + w * 0.42}" y="{y + h * 0.4}" width="{w * 0.07}" height="{h * 0.6}" fill="{DARK}"/>
    <rect x="{x + w * 0.52}" y="{y + h * 0.25}" width="{w * 0.09}" height="{h * 0.75}" fill="{DARK}"/>
    <rect x="{x + w * 0.64}" y="{y + h * 0.5}" width="{w * 0.08}" height="{h * 0.5}" fill="{DARK}"/>
    <rect x="{x + w * 0.76}" y="{y + h * 0.3}" width="{w * 0.06}" height="{h * 0.7}" fill="{TEAL}"/>
    <rect x="{x + w * 0.86}" y="{y + h * 0.55}" width="{w * 0.1}" height="{h * 0.45}" fill="{DARK}"/>
  </g>
'''


def icon_clock(cx, cy, scale=1.0):
    return f'''
  <circle cx="{cx}" cy="{cy}" r="{14*scale}" fill="{WHITE}" stroke="{RED}" stroke-width="2"/>
  <line x1="{cx}" y1="{cy}" x2="{cx}" y2="{cy - 8*scale}" stroke="{RED}" stroke-width="2" stroke-linecap="round"/>
  <line x1="{cx}" y1="{cy}" x2="{cx + 6*scale}" y2="{cy + 2*scale}" stroke="{RED}" stroke-width="2" stroke-linecap="round"/>
  <text x="{cx}" y="{cy + 24*scale}" text-anchor="middle" font-size="{9*scale}" fill="{RED}" font-family="Arial,sans-serif">3–30s</text>
'''


def icon_attacker(cx, cy, scale=1.0):
    return f'''
  <circle cx="{cx}" cy="{cy - 12*scale}" r="{10*scale}" fill="{DARK}"/>
  <ellipse cx="{cx}" cy="{cy + 8*scale}" rx="{14*scale}" ry="{10*scale}" fill="{RED}" opacity="0.85"/>
  <rect x="{cx - 18*scale}" y="{cy - 2*scale}" width="{36*scale}" height="{3*scale}" fill="{RED}"/>
  <text x="{cx}" y="{cy + 28*scale}" text-anchor="middle" font-size="{9*scale}" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">Attacker</text>
'''


def icon_threat_ss7(cx, cy, scale=1.0):
    return f'''
  <circle cx="{cx}" cy="{cy}" r="{20*scale}" fill="{LIGHT}" stroke="{RED}" stroke-width="2"/>
  <text x="{cx}" y="{cy + 4*scale}" text-anchor="middle" font-size="{10*scale}" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">SS7</text>
  <path d="M {cx - 14*scale},{cy + 14*scale} L {cx},{cy + 22*scale} L {cx + 14*scale},{cy + 14*scale}"
        fill="none" stroke="{RED}" stroke-width="1.5"/>
'''


def icon_threat_sim(cx, cy, scale=1.0):
    return f'''
  <rect x="{cx - 12*scale}" y="{cy - 16*scale}" width="{24*scale}" height="{32*scale}" rx="3" fill="{TEAL}"/>
  <rect x="{cx - 8*scale}" y="{cy - 12*scale}" width="{16*scale}" height="{10*scale}" rx="1" fill="{GOLD}"/>
  <path d="M {cx - 16*scale},{cy} L {cx + 16*scale},{cy - 8*scale}" stroke="{RED}" stroke-width="2" marker-end="url(#arr-sim)"/>
  <defs><marker id="arr-sim" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="{RED}"/></marker></defs>
'''


def icon_threat_phish(cx, cy, scale=1.0):
    return f'''
  <path d="M {cx - 16*scale},{cy + 10*scale} Q {cx},{cy - 18*scale} {cx + 16*scale},{cy + 10*scale}"
        fill="none" stroke="{RED}" stroke-width="2"/>
  <circle cx="{cx}" cy="{cy + 12*scale}" r="{6*scale}" fill="{GOLD}" stroke="{RED}" stroke-width="1.5"/>
  <line x1="{cx}" y1="{cy + 6*scale}" x2="{cx}" y2="{cy - 8*scale}" stroke="{RED}" stroke-width="1.5"/>
'''


def icon_threat_fail(cx, cy, scale=1.0):
    return f'''
  <rect x="{cx - 14*scale}" y="{cy - 10*scale}" width="{28*scale}" height="{20*scale}" rx="2" fill="{TEAL}" opacity="0.6"/>
  <line x1="{cx - 8*scale}" y1="{cy - 4*scale}" x2="{cx + 8*scale}" y2="{cy + 4*scale}" stroke="{RED}" stroke-width="3"/>
  <line x1="{cx + 8*scale}" y1="{cy - 4*scale}" x2="{cx - 8*scale}" y2="{cy + 4*scale}" stroke="{RED}" stroke-width="3"/>
'''


# ---- Illustrations ----

def make_problem_sms():
    body = flag_stripe(0, 0, 720, 18)
    body += addis_skyline(20, 60, 680, 50)
    body += f'''
  <text x="360" y="55" text-anchor="middle" font-family="Arial,sans-serif" font-size="20" font-weight="bold" fill="{DARK}">
    Today's pain: SMS OTP for banking login
  </text>
  <text x="360" y="74" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="{GRAY}">
    Ethiopian bank customers wait, worry, and abandon
  </text>
'''
    # User with phone showing bank app + loading
    body += f'''
  <g transform="translate(70,115)">
    <rect width="120" height="130" rx="10" fill="{WHITE}" stroke="{GREEN}" stroke-width="2"/>
    <circle cx="60" cy="45" r="22" fill="{CREAM}" stroke="{DARK}" stroke-width="1.5"/>
    <ellipse cx="60" cy="82" rx="28" ry="18" fill="{GREEN}"/>
    <text x="60" y="115" text-anchor="middle" font-size="12" font-weight="bold" fill="{DARK}" font-family="Arial,sans-serif">Citizen</text>
  </g>
'''
    body += icon_phone(130, 175, 0.9)
    body += f'''
  <rect x="108" y="148" width="44" height="36" rx="2" fill="{CREAM}" stroke="{GOLD}" stroke-width="1"/>
  <text x="130" y="162" text-anchor="middle" font-size="7" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">CBE</text>
  <text x="130" y="172" text-anchor="middle" font-size="6" fill="{GRAY}" font-family="Arial,sans-serif">Login…</text>
  <circle cx="130" cy="180" r="4" fill="none" stroke="{RED}" stroke-width="1.5" stroke-dasharray="2,2"/>
  <text x="130" y="218" text-anchor="middle" font-size="9" fill="{RED}" font-family="Arial,sans-serif">😤 waiting</text>
'''
    # Network path with tower
    body += icon_tower(280, 175, 0.85)
    body += f'<text x="280" y="218" text-anchor="middle" font-size="9" fill="{TEAL}" font-family="Arial,sans-serif">Ethio Telecom</text>'
    body += arrow(195, 165, 250, 165, RED, "send OTP")
    body += icon_clock(340, 145, 0.85)

    # SMS bubble in flight
    body += icon_sms(400, 165, 1.1)
    body += f'''
  <path d="M 455,165 Q 490,130 530,155" fill="none" stroke="{RED}" stroke-width="2" stroke-dasharray="5,3"/>
  <text x="492" y="138" text-anchor="middle" font-size="9" fill="{RED}" font-family="Arial,sans-serif">SS7 path</text>
'''
    body += icon_attacker(530, 130, 0.75)

    body += arrow(455, 175, 580, 175, GOLD, "type code")
    body += icon_bank(630, 175, 1.0)
    body += f'<text x="630" y="218" text-anchor="middle" font-size="9" fill="{DARK}" font-family="Arial,sans-serif">Bank server</text>'

    # Pain cards with mini icons
    cards = [
        (40, "Slow / fails", "3–30s delivery", "⏱", RED),
        (250, "SS7 / SIM-swap", "OTP can be stolen", "⚠", RED),
        (460, "Friction", "Users abandon login", "✗", RED),
    ]
    for x, title, sub, sym, color in cards:
        body += f'''
  <g transform="translate({x},285)">
    <rect width="210" height="68" rx="10" fill="{WHITE}" stroke="{color}" stroke-width="2"/>
    <rect width="210" height="68" rx="10" fill="{color}" opacity="0.08"/>
    <circle cx="28" cy="28" r="16" fill="{color}" opacity="0.2"/>
    <text x="28" y="33" text-anchor="middle" font-size="14" fill="{color}" font-family="Arial,sans-serif">{sym}</text>
    <text x="55" y="28" font-size="12" fill="{color}" font-family="Arial,sans-serif" font-weight="bold">{title}</text>
    <text x="55" y="48" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">{sub}</text>
  </g>
'''
    (OUT / "01_problem_sms.svg").write_text(svg_wrap(720, 380, body), encoding="utf-8")


def make_persona():
    body = flag_stripe(0, 0, 720, 18)
    body += addis_skyline(0, 55, 300, 45)
    body += f'''
  <text x="360" y="55" text-anchor="middle" font-family="Arial,sans-serif" font-size="20" font-weight="bold" fill="{DARK}">
    Government digital services — citizen login
  </text>
  <text x="360" y="74" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="{GRAY}">
    e-Gov portals · tax · civil registry · social payments
  </text>
'''
    # Government building + citizen
    body += f'''
  <circle cx="175" cy="195" r="78" fill="{YELLOW}" stroke="{GREEN}" stroke-width="4" opacity="0.25"/>
  <rect x="130" y="150" width="90" height="70" rx="4" fill="{DARK}"/>
  <polygon points="175,120 120,155 230,155" fill="{GREEN}"/>
  <rect x="160" y="175" width="30" height="45" fill="{CREAM}"/>
  <rect x="140" y="175" width="12" height="20" fill="{YELLOW}"/>
  <rect x="198" y="175" width="12" height="20" fill="{YELLOW}"/>
  {ethiopia_star(175, 268, 10)}
  <text x="175" y="295" text-anchor="middle" font-size="15" font-weight="bold" fill="{DARK}" font-family="Arial,sans-serif">Government</text>
  <text x="175" y="314" text-anchor="middle" font-size="11" fill="{GRAY}" font-family="Arial,sans-serif">Citizen · Ethio Telecom SIM</text>
'''
    body += icon_phone(240, 230, 0.65)
    body += f'''
  <rect x="222" y="210" width="36" height="22" rx="2" fill="{CREAM}"/>
  <text x="240" y="220" text-anchor="middle" font-size="5" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">e-Gov</text>
  <text x="240" y="228" text-anchor="middle" font-size="5" fill="{TEAL}" font-family="Arial,sans-serif">Silent login</text>
'''
    body += f'''
  <g transform="translate(310,95)">
    <rect width="380" height="240" rx="12" fill="{WHITE}" stroke="{GOLD}" stroke-width="2"/>
    <rect x="0" y="0" width="380" height="36" rx="12" fill="{GREEN}"/>
    <rect x="0" y="18" width="380" height="18" fill="{GREEN}"/>
    <text x="20" y="24" font-size="15" font-weight="bold" fill="{WHITE}" font-family="Arial,sans-serif">What government needs</text>
'''
    wants = [
        ("Citizen opens e-Gov app on mobile data", GREEN),
        ("Prove MSISDN ownership without SMS OTP", GREEN),
        ("Reduce fraud on benefits &amp; tax portals", GREEN),
        ("Keep Ethio Telecom SMS revenue intact", TEAL),
    ]
    for i, (txt, color) in enumerate(wants):
        body += f'''
    <circle cx="32" cy="{58 + i*38}" r="10" fill="{color}" opacity="0.15"/>
    <text x="32" y="{62 + i*38}" text-anchor="middle" font-size="12" fill="{color}" font-family="Arial,sans-serif" font-weight="bold">✓</text>
    <text x="50" y="{62 + i*38}" font-size="12" fill="{DARK}" font-family="Arial,sans-serif">{txt}</text>
'''
    body += f'''
    <rect x="16" y="195" width="348" height="34" rx="8" fill="{TEAL}" opacity="0.12" stroke="{TEAL}" stroke-width="1"/>
    <text x="190" y="216" text-anchor="middle" font-size="13" fill="{TEAL}" font-family="Arial,sans-serif" font-weight="bold">→ Digicom Silent Auth for Government</text>
  </g>
  <g transform="translate(310,345)">
    <rect width="115" height="24" rx="6" fill="{RED}" opacity="0.12"/>
    <text x="58" y="16" text-anchor="middle" font-size="10" fill="{RED}" font-family="Arial,sans-serif">Today: SMS OTP</text>
  </g>
  <g transform="translate(435,345)">
    <rect width="115" height="24" rx="6" fill="{GREEN}" opacity="0.15"/>
    <text x="58" y="16" text-anchor="middle" font-size="10" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">Goal: Silent Auth</text>
  </g>
  <g transform="translate(560,345)">
    <rect width="115" height="24" rx="6" fill="{YELLOW}" opacity="0.3"/>
    <text x="58" y="16" text-anchor="middle" font-size="10" fill="{DARK}" font-family="Arial,sans-serif">Fallback SMS OK</text>
  </g>
'''
    (OUT / "02_persona.svg").write_text(svg_wrap(720, 380, body), encoding="utf-8")


def make_silent_flow():
    body = flag_stripe(0, 0, 860, 18)
    body += f'''
  <defs>
    <marker id="sf-arr" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L6,3 L0,6 Z" fill="{GOLD}"/>
    </marker>
  </defs>
  <text x="430" y="48" text-anchor="middle" font-family="Arial,sans-serif" font-size="19" font-weight="bold" fill="{DARK}">
    Silent Auth — App → Bank → Digicom → Network → Approve
  </text>
  <text x="430" y="72" text-anchor="middle" font-family="Arial,sans-serif" font-size="13" fill="{TEAL}">
    No SMS · No OTP typing · Live cellular proof in &lt; 3 seconds
  </text>
'''
    steps = [
        (70, GREEN, "1. App", "Collect\nIP:port", "phone"),
        (230, TEAL, "2. Bank", "Call\nDigicom API", "bank"),
        (390, TEAL, "3. Digicom", "Resolve +\nverify MSISDN", "lock"),
        (550, DARK, "4. Network", "MAP /\nDiameter", "tower"),
        (710, GREEN, "5. Approve", "Login\ngranted", "check"),
    ]
    for i, (x, color, title, sub, kind) in enumerate(steps):
        body += f'''
  <rect x="{x - 52}" y="108" width="104" height="118" rx="12" fill="{WHITE}" stroke="{color}" stroke-width="2.5"/>
  <circle cx="{x}" cy="138" r="26" fill="{color}" stroke="{GOLD}" stroke-width="2"/>
'''
        if kind == "phone":
            body += icon_phone(x, 138, 0.55, WHITE)
        elif kind == "bank":
            body += icon_bank(x, 140, 0.55)
        elif kind == "lock":
            body += icon_lock(x, 138, 0.75)
        elif kind == "tower":
            body += icon_tower(x, 142, 0.55)
        elif kind == "check":
            body += f'''
  <path d="M {x - 10} 138 L {x - 2} 146 L {x + 12} 130" fill="none" stroke="{WHITE}" stroke-width="3" stroke-linecap="round"/>
'''
        for j, line in enumerate(sub.split("\n")):
            body += f'<text x="{x}" y="{178 + j * 14}" text-anchor="middle" font-size="10" fill="{DARK}" font-family="Arial,sans-serif">{line}</text>'
        body += f'<text x="{x}" y="214" text-anchor="middle" font-size="11" fill="{color}" font-family="Arial,sans-serif" font-weight="bold">{title}</text>'
        if i < len(steps) - 1:
            nx = steps[i + 1][0]
            body += f'''
  <line x1="{x + 56}" y1="165" x2="{nx - 56}" y2="165" stroke="{GOLD}" stroke-width="3" marker-end="url(#sf-arr)"/>
'''
    body += f'''
  <g transform="translate(680, 88)">
    <rect width="150" height="52" rx="8" fill="{RED}" opacity="0.12" stroke="{RED}" stroke-width="1.5" stroke-dasharray="4 3"/>
    <text x="75" y="22" text-anchor="middle" font-size="11" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">NOT in this flow</text>
    <text x="75" y="40" text-anchor="middle" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">SMS OTP</text>
  </g>
  <line x1="680" y1="114" x2="620" y2="165" stroke="{RED}" stroke-width="2" stroke-dasharray="5 4" opacity="0.5"/>
  <text x="650" y="148" font-size="10" fill="{RED}" font-family="Arial,sans-serif" transform="rotate(-25 650 148)">bypassed</text>

  <rect x="50" y="248" width="760" height="88" rx="12" fill="{GREEN}" opacity="0.1" stroke="{GREEN}" stroke-width="1.5"/>
  <text x="430" y="278" text-anchor="middle" font-size="14" font-weight="bold" fill="{DARK}" font-family="Arial,sans-serif">
    Proof = live cellular session bound to the SIM's MSISDN
  </text>
  <text x="430" y="302" text-anchor="middle" font-size="12" fill="{TEAL}" font-family="Arial,sans-serif">
    Resistant to SS7 SMS intercept · Nothing for the user to type or steal
  </text>
  <text x="430" y="322" text-anchor="middle" font-size="11" fill="{GRAY}" font-family="Arial,sans-serif">
    Bank never talks to the operator directly — Digicom orchestrates verification
  </text>
'''
    (OUT / "03_silent_flow.svg").write_text(svg_wrap(860, 380, body), encoding="utf-8")


def make_architecture():
    body = flag_stripe(0, 0, 840, 18)
    body += f'''
  <defs>
    <marker id="arch-down" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L6,3 L0,6 Z" fill="{GOLD}"/>
    </marker>
    <marker id="arch-right" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L6,3 L0,6 Z" fill="{GREEN}"/>
    </marker>
  </defs>
  <text x="420" y="46" text-anchor="middle" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="{DARK}">
    Digicom-ET — adapter layer above Ethio Telecom
  </text>
  <text x="420" y="68" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="{TEAL}">
    Banks call Digicom API · Operator core stays unchanged
  </text>

  <!-- Layer 1: Banks -->
  <rect x="40" y="88" width="760" height="72" rx="10" fill="{GREEN}" opacity="0.12" stroke="{GREEN}" stroke-width="2"/>
  <text x="60" y="112" font-size="13" font-weight="bold" fill="{GREEN}" font-family="Arial,sans-serif">BANK LAYER</text>
  <rect x="180" y="98" width="130" height="52" rx="8" fill="{GREEN}"/>
  <text x="245" y="120" text-anchor="middle" font-size="12" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">CBE</text>
  <text x="245" y="138" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">Bank App</text>
  <rect x="330" y="98" width="130" height="52" rx="8" fill="{GREEN}"/>
  <text x="395" y="120" text-anchor="middle" font-size="12" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">Awash</text>
  <text x="395" y="138" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">Bank App</text>
  <rect x="480" y="98" width="130" height="52" rx="8" fill="{GREEN}"/>
  <text x="545" y="120" text-anchor="middle" font-size="12" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">Dashen</text>
  <text x="545" y="138" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">Bank App</text>
  <text x="680" y="128" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">← customers</text>

  <!-- API call arrow down -->
  <line x1="420" y1="160" x2="420" y2="188" stroke="{GREEN}" stroke-width="3" marker-end="url(#arch-right)"/>
  <text x="520" y="178" font-size="11" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">REST API · per-verify billing</text>

  <!-- Layer 2: Digicom adapter (above telco) -->
  <rect x="40" y="188" width="760" height="88" rx="10" fill="{TEAL}" opacity="0.15" stroke="{TEAL}" stroke-width="3"/>
  <text x="60" y="212" font-size="13" font-weight="bold" fill="{TEAL}" font-family="Arial,sans-serif">DIGICOM-ET ADAPTER (VAS)</text>
  <rect x="120" y="218" width="600" height="48" rx="8" fill="{TEAL}"/>
  <text x="420" y="238" text-anchor="middle" font-size="14" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">Silent Auth Service — Resolver · Verifier · Fallback orchestrator</text>
  <text x="420" y="256" text-anchor="middle" font-size="11" fill="{CREAM}" font-family="Arial,sans-serif">Sits above operator signalling — does not replace HLR/HSS/PGW</text>

  <!-- signalling down -->
  <line x1="300" y1="276" x2="300" y2="304" stroke="{GOLD}" stroke-width="2.5" marker-end="url(#arch-down)"/>
  <line x1="420" y1="276" x2="420" y2="304" stroke="{GOLD}" stroke-width="2.5" marker-end="url(#arch-down)"/>
  <line x1="540" y1="276" x2="540" y2="304" stroke="{GOLD}" stroke-width="2.5" marker-end="url(#arch-down)"/>
  <text x="620" y="294" font-size="10" fill="{GOLD}" font-family="Arial,sans-serif">MAP / Diameter / PGW</text>

  <!-- Layer 3: Ethio Telecom -->
  <rect x="40" y="304" width="760" height="72" rx="10" fill="{DARK}" opacity="0.9"/>
  <text x="60" y="328" font-size="13" font-weight="bold" fill="{YELLOW}" font-family="Arial,sans-serif">ETHIO TELECOM CORE (unchanged)</text>
  <text x="420" y="352" text-anchor="middle" font-size="12" fill="{CREAM}" font-family="Arial,sans-serif">HLR · HSS · PGW · SMSC · SS7 / Diameter interconnect</text>

  <!-- money note -->
  <rect x="60" y="392" width="720" height="58" rx="10" fill="{WHITE}" stroke="{GREEN}" stroke-width="2"/>
  <text x="420" y="416" text-anchor="middle" font-size="14" font-weight="bold" fill="{GREEN}" font-family="Arial,sans-serif">
    Digicom does NOT take telecom revenue — SMS &amp; interconnect stay with Ethio Telecom
  </text>
  <text x="420" y="436" text-anchor="middle" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">
    Digicom bills banks for auth API · Operator keeps existing SMS revenue on fallback
  </text>
'''
    (OUT / "04_architecture.svg").write_text(svg_wrap(840, 470, body), encoding="utf-8")


def make_fallback():
    body = flag_stripe(0, 0, 820, 18)
    body += f'''
  <defs>
    <marker id="fb-yes" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L6,3 L0,6 Z" fill="{GREEN}"/>
    </marker>
    <marker id="fb-no" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L6,3 L0,6 Z" fill="{RED}"/>
    </marker>
  </defs>
  <text x="410" y="48" text-anchor="middle" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="{DARK}">
    Fallback policy — Silent Auth first, SMS OTP only if needed
  </text>
  <text x="410" y="70" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="{TEAL}">
    ~95%+ succeed silently · SMS is the exception, not the default
  </text>

  <!-- Step 1: Always try silent first -->
  <rect x="290" y="88" width="240" height="56" rx="10" fill="{TEAL}" stroke="{GOLD}" stroke-width="2"/>
  <text x="410" y="112" text-anchor="middle" font-size="14" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">1. Try Silent Auth</text>
  <text x="410" y="132" text-anchor="middle" font-size="11" fill="{CREAM}" font-family="Arial,sans-serif">Digicom verifies via cellular session</text>
  <line x1="410" y1="144" x2="410" y2="168" stroke="{GOLD}" stroke-width="2.5"/>

  <!-- Decision diamond -->
  <polygon points="410,168 490,210 410,252 330,210" fill="{YELLOW}" stroke="{GOLD}" stroke-width="2"/>
  <text x="410" y="206" text-anchor="middle" font-size="12" fill="{DARK}" font-family="Arial,sans-serif" font-weight="bold">Cellular</text>
  <text x="410" y="222" text-anchor="middle" font-size="12" fill="{DARK}" font-family="Arial,sans-serif" font-weight="bold">path OK?</text>

  <!-- YES branch -->
  <line x1="330" y1="210" x2="140" y2="210" stroke="{GREEN}" stroke-width="3" marker-end="url(#fb-yes)"/>
  <text x="230" y="200" text-anchor="middle" font-size="12" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">YES</text>
  <rect x="30" y="178" width="110" height="64" rx="10" fill="{GREEN}"/>
  <text x="85" y="204" text-anchor="middle" font-size="12" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">Approve</text>
  <text x="85" y="222" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">No SMS</text>
  <text x="85" y="236" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">&lt; 3 sec</text>

  <!-- NO branch -->
  <line x1="490" y1="210" x2="680" y2="210" stroke="{RED}" stroke-width="3" marker-end="url(#fb-no)"/>
  <text x="580" y="200" text-anchor="middle" font-size="12" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">NO</text>
  <rect x="680" y="178" width="120" height="64" rx="10" fill="{RED}"/>
  <text x="740" y="204" text-anchor="middle" font-size="12" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">SMS OTP</text>
  <text x="740" y="222" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">Fallback only</text>
  <text x="740" y="236" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">via operator</text>

  <!-- Failure reasons -->
  <rect x="520" y="268" width="260" height="100" rx="10" fill="{WHITE}" stroke="{RED}" stroke-width="1.5" stroke-dasharray="5 3"/>
  <text x="650" y="290" text-anchor="middle" font-size="12" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">When Silent Auth fails</text>
  <text x="540" y="312" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">• User on Wi-Fi only (no cellular data path)</text>
  <text x="540" y="332" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">• MVNO / roaming edge cases</text>
  <text x="540" y="352" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">• Network timeout or resolver miss</text>

  <!-- Success path highlight -->
  <rect x="30" y="268" width="260" height="100" rx="10" fill="{GREEN}" opacity="0.08" stroke="{GREEN}" stroke-width="1.5"/>
  <text x="160" y="290" text-anchor="middle" font-size="12" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">Happy path (majority)</text>
  <text x="50" y="312" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">• Cellular session proves SIM ownership</text>
  <text x="50" y="332" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">• Zero user friction — no code to type</text>
  <text x="50" y="352" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">• Resistant to SMS intercept attacks</text>

  <rect x="80" y="388" width="660" height="44" rx="8" fill="{LIGHT}" stroke="{TEAL}" stroke-width="1"/>
  <text x="410" y="408" text-anchor="middle" font-size="12" fill="{DARK}" font-family="Arial,sans-serif">
    Fallback SMS still routes through Ethio Telecom — Digicom orchestrates policy, not billing
  </text>
  <text x="410" y="424" text-anchor="middle" font-size="11" fill="{TEAL}" font-family="Arial,sans-serif">
    Operator keeps SMS revenue · Digicom keeps auth API revenue
  </text>
'''
    (OUT / "05_fallback.svg").write_text(svg_wrap(820, 450, body), encoding="utf-8")


def make_threats():
    body = flag_stripe(0, 0, 720, 18)
    body += f'''
  <text x="360" y="50" text-anchor="middle" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="{DARK}">
    Why SMS OTP is fragile
  </text>
'''
    threats = [
        (90, "SS7 intercept", "Redirect MT-SMS\nvia SRI-SM abuse"),
        (260, "SIM swap", "Number moved to\nattacker SIM"),
        (430, "Phishing", "User types OTP\ninto fake page"),
        (600, "Delivery fail", "Late / lost SMS\n→ abandoned"),
    ]
    for x, title, sub in threats:
        body += f'''
  <rect x="{x}" y="90" width="140" height="130" rx="10" fill="{WHITE}" stroke="{RED}" stroke-width="2"/>
  <circle cx="{x + 70}" cy="125" r="18" fill="{RED}"/>
  <text x="{x + 70}" y="130" text-anchor="middle" font-size="14" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">!</text>
  <text x="{x + 70}" y="165" text-anchor="middle" font-size="12" fill="{DARK}" font-family="Arial,sans-serif" font-weight="bold">{title}</text>
'''
        for j, line in enumerate(sub.split("\n")):
            body += f'<text x="{x + 70}" y="{190 + j*16}" text-anchor="middle" font-size="10" fill="{GRAY}" font-family="Arial,sans-serif">{line}</text>'

    body += icon_sms(360, 300, 1.2, crossed=True)
    body += f'<text x="360" y="360" text-anchor="middle" font-size="13" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">Silent Auth removes the code — nothing to steal</text>'
    (OUT / "06_threats.svg").write_text(svg_wrap(720, 390, body), encoding="utf-8")


def make_money_model():
    body = flag_stripe(0, 0, 820, 18)
    body += f'''
  <defs>
    <marker id="money-flow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
      <path d="M0,0 L6,3 L0,6 Z" fill="{GOLD}"/>
    </marker>
  </defs>
  <text x="410" y="46" text-anchor="middle" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="{DARK}">
    Commercial model — everyone's pocket stays whole
  </text>
  <text x="410" y="68" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="{TEAL}">
    Separate revenue streams · No cannibalization of operator SMS income
  </text>
'''
    cols = [
        (40, GREEN, "Ethio Telecom", [
            "Keeps ALL SMS interconnect revenue",
            "Keeps data &amp; signalling revenue",
            "Digicom = partner VAS layer",
            "New auth API share (optional)",
        ], "SMS $ stays here"),
        (290, TEAL, "Digicom-ET", [
            "Bills banks per Silent Auth verify",
            "Adapter — NOT a carrier",
            "Orchestrates fallback policy",
            "Does NOT take telco pocket",
        ], "Auth API $"),
        (540, GOLD, "Banks", [
            "Pay Digicom for auth API",
            "Fewer SMS OTP sends overall",
            "Higher login success rate",
            "Lower fraud &amp; abandonment",
        ], "Pays for value"),
    ]
    for x, color, title, items, tag in cols:
        body += f'''
  <rect x="{x}" y="88" width="230" height="230" rx="12" fill="{WHITE}" stroke="{color}" stroke-width="3"/>
  <rect x="{x}" y="88" width="230" height="44" rx="12" fill="{color}"/>
  <rect x="{x}" y="112" width="230" height="20" fill="{color}"/>
  <text x="{x + 115}" y="118" text-anchor="middle" font-size="14" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">{title}</text>
  <rect x="{x + 20}" y="142" width="190" height="22" rx="6" fill="{color}" opacity="0.15"/>
  <text x="{x + 115}" y="157" text-anchor="middle" font-size="10" fill="{color}" font-family="Arial,sans-serif" font-weight="bold">{tag}</text>
'''
        for i, item in enumerate(items):
            body += f'<text x="{x + 18}" y="{188 + i * 32}" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">• {item}</text>'

    # Money flow arrows between columns
    body += f'''
  <path d="M 270 200 Q 280 170 290 200" fill="none" stroke="{GOLD}" stroke-width="2" marker-end="url(#money-flow)"/>
  <text x="280" y="168" text-anchor="middle" font-size="9" fill="{GOLD}" font-family="Arial,sans-serif">API share</text>
  <path d="M 520 200 Q 530 230 540 200" fill="none" stroke="{GOLD}" stroke-width="2.5" marker-end="url(#money-flow)"/>
  <text x="530" y="248" text-anchor="middle" font-size="10" fill="{GOLD}" font-family="Arial,sans-serif" font-weight="bold">per-verify billing</text>

  <!-- SMS revenue stays with telco -->
  <rect x="40" y="330" width="350" height="56" rx="10" fill="{GREEN}" opacity="0.12" stroke="{GREEN}" stroke-width="2"/>
  <text x="215" y="354" text-anchor="middle" font-size="13" font-weight="bold" fill="{GREEN}" font-family="Arial,sans-serif">
    SMS revenue → Ethio Telecom (unchanged)
  </text>
  <text x="215" y="374" text-anchor="middle" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">
    Fallback OTP SMS still billed through operator interconnect
  </text>

  <!-- Auth API revenue -->
  <rect x="410" y="330" width="370" height="56" rx="10" fill="{TEAL}" opacity="0.12" stroke="{TEAL}" stroke-width="2"/>
  <text x="595" y="354" text-anchor="middle" font-size="13" font-weight="bold" fill="{TEAL}" font-family="Arial,sans-serif">
    Auth API revenue → Digicom-ET (new stream)
  </text>
  <text x="595" y="374" text-anchor="middle" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">
    Banks pay for Silent Auth · Digicom does not compete with telco SMS
  </text>
'''
    (OUT / "07_money_model.svg").write_text(svg_wrap(820, 410, body), encoding="utf-8")


def make_ethiopia_map():
    """Stylized Ethiopia landmass + Digicom hub (not geographic accuracy)."""
    body = flag_stripe(0, 0, 720, 18)
    body += f'''
  <text x="360" y="50" text-anchor="middle" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="{DARK}">
    Digicom-ET · Serving Ethiopian banks nationwide
  </text>
  <text x="360" y="68" text-anchor="middle" font-family="Arial,sans-serif" font-size="11" fill="{GRAY}">
    Adapter on Ethio Telecom · Silent Auth hub in Addis Ababa
  </text>
  <!-- stylized Ethiopia outline (Horn of Africa) -->
  <path d="M 240,95 L 310,88 L 380,92 L 450,105 L 510,140 L 530,190 L 520,240 L 490,285 L 440,310 L 370,325 L 300,318 L 250,295 L 220,250 L 210,200 L 225,150 Z"
        fill="{GREEN}" opacity="0.75" stroke="{GOLD}" stroke-width="3"/>
  <path d="M 240,95 L 310,88 L 380,92 L 450,105 L 510,140 L 530,190 L 520,240 L 490,285 L 440,310 L 370,325 L 300,318 L 250,295 L 220,250 L 210,200 L 225,150 Z"
        fill="none" stroke="{GREEN}" stroke-width="1" opacity="0.4"/>
  <!-- regional texture lines -->
  <line x1="280" y1="120" x2="340" y2="280" stroke="{CREAM}" stroke-width="1" opacity="0.3"/>
  <line x1="360" y1="100" x2="380" y2="300" stroke="{CREAM}" stroke-width="1" opacity="0.3"/>
  <line x1="440" y1="130" x2="400" y2="290" stroke="{CREAM}" stroke-width="1" opacity="0.3"/>
  <!-- Addis hub -->
  <circle cx="370" cy="210" r="28" fill="{YELLOW}" opacity="0.35"/>
  <circle cx="370" cy="210" r="14" fill="{YELLOW}" stroke="{RED}" stroke-width="3"/>
  {ethiopia_star(370, 210, 7, fill=RED, stroke=YELLOW, sw=0.5)}
  <text x="370" y="245" text-anchor="middle" font-size="12" fill="{DARK}" font-family="Arial,sans-serif" font-weight="bold">Addis Ababa</text>
  <text x="370" y="260" text-anchor="middle" font-size="10" fill="{TEAL}" font-family="Arial,sans-serif">Digicom-ET hub</text>
'''
    # Bank city pins
    cities = [
        (300, 175, "CBE", GREEN),
        (430, 200, "Awash", TEAL),
        (320, 270, "Dashen", GOLD),
        (460, 260, "BOA", DARK),
    ]
    for cx, cy, name, color in cities:
        body += f'''
  <circle cx="{cx}" cy="{cy}" r="6" fill="{color}" stroke="{WHITE}" stroke-width="2"/>
  <line x1="{cx}" y1="{cy}" x2="370" y2="210" stroke="{GOLD}" stroke-width="1.5" opacity="0.5" stroke-dasharray="3,2"/>
  <text x="{cx}" y="{cy - 10}" text-anchor="middle" font-size="9" fill="{color}" font-family="Arial,sans-serif" font-weight="bold">{name}</text>
'''
    # Ethio Telecom tower
    body += icon_tower(560, 180, 1.1)
    body += f'''
  <rect x="520" y="220" width="80" height="50" rx="8" fill="{WHITE}" stroke="{TEAL}" stroke-width="2"/>
  <text x="560" y="240" text-anchor="middle" font-size="10" fill="{TEAL}" font-family="Arial,sans-serif" font-weight="bold">Ethio Telecom</text>
  <text x="560" y="256" text-anchor="middle" font-size="9" fill="{GRAY}" font-family="Arial,sans-serif">HLR / HSS / PGW</text>
  <line x1="530" y1="200" x2="398" y2="210" stroke="{TEAL}" stroke-width="2" marker-end="url(#arr-et)"/>
  <defs><marker id="arr-et" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="{TEAL}"/></marker></defs>
  <text x="465" y="192" text-anchor="middle" font-size="9" fill="{TEAL}" font-family="Arial,sans-serif">signalling</text>
  <!-- legend -->
  <rect x="40" y="340" width="640" height="38" rx="8" fill="{WHITE}" stroke="{GREEN}" stroke-width="1.5"/>
  <circle cx="70" cy="359" r="5" fill="{YELLOW}" stroke="{RED}" stroke-width="1.5"/>
  <text x="85" y="363" font-size="10" fill="{DARK}" font-family="Arial,sans-serif">Digicom hub</text>
  <circle cx="200" cy="359" r="5" fill="{GREEN}"/>
  <text x="215" y="363" font-size="10" fill="{DARK}" font-family="Arial,sans-serif">Partner banks</text>
  <rect x="330" y="354" width="12" height="10" rx="2" fill="{TEAL}"/>
  <text x="350" y="363" font-size="10" fill="{DARK}" font-family="Arial,sans-serif">Ethio Telecom (unchanged)</text>
  <text x="560" y="363" text-anchor="middle" font-size="10" fill="{TEAL}" font-family="Arial,sans-serif" font-weight="bold">No telco SMS revenue taken</text>
'''
    (OUT / "08_ethiopia.svg").write_text(svg_wrap(720, 390, body), encoding="utf-8")


def make_before_after():
    body = flag_stripe(0, 0, 820, 18)
    body += f'''
  <text x="410" y="45" text-anchor="middle" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="{DARK}">
    Before vs After — Digicom Silent Auth
  </text>

  <!-- BEFORE panel -->
  <rect x="30" y="68" width="360" height="300" rx="14" fill="{WHITE}" stroke="{RED}" stroke-width="2.5"/>
  <rect x="30" y="68" width="360" height="36" rx="14" fill="{RED}"/>
  <rect x="30" y="88" width="360" height="16" fill="{RED}"/>
  <text x="210" y="92" text-anchor="middle" font-size="15" font-weight="bold" fill="{WHITE}" font-family="Arial,sans-serif">BEFORE — SMS OTP every login</text>

  <text x="55" y="130" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">① Open bank app</text>
  <text x="55" y="158" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">② Wait for SMS… (3–30 sec)</text>
  <text x="55" y="186" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">③ Find SMS, type 6-digit code</text>
  <text x="55" y="214" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">④ Hope OTP wasn't intercepted</text>

  <!-- SMS icon crossed -->
  <g transform="translate(210, 240)">
    {icon_sms(0, 0, 1.1, crossed=True)}
  </g>
  <text x="210" y="296" text-anchor="middle" font-size="12" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">~5–30 sec · friction · fraud risk</text>
  <text x="210" y="318" text-anchor="middle" font-size="11" fill="{GRAY}" font-family="Arial,sans-serif">SS7 intercept · SIM swap · phishing</text>
  <text x="210" y="352" text-anchor="middle" font-size="11" fill="{RED}" font-family="Arial,sans-serif">Users abandon login</text>

  <!-- Arrow divider -->
  <polygon points="410,218 430,200 430,236" fill="{GOLD}"/>
  <text x="420" y="260" text-anchor="middle" font-size="11" fill="{GOLD}" font-family="Arial,sans-serif" font-weight="bold" transform="rotate(90 420 260)">Digicom</text>

  <!-- AFTER panel -->
  <rect x="430" y="68" width="360" height="300" rx="14" fill="{WHITE}" stroke="{GREEN}" stroke-width="2.5"/>
  <rect x="430" y="68" width="360" height="36" rx="14" fill="{GREEN}"/>
  <rect x="430" y="88" width="360" height="16" fill="{GREEN}"/>
  <text x="610" y="92" text-anchor="middle" font-size="15" font-weight="bold" fill="{WHITE}" font-family="Arial,sans-serif">AFTER — Silent Auth default</text>

  <text x="455" y="130" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">① Open app on cellular</text>
  <text x="455" y="158" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">② Digicom verifies silently</text>
  <text x="455" y="186" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">③ Login approved — done</text>
  <text x="455" y="214" font-size="13" fill="{DARK}" font-family="Arial,sans-serif">④ SMS only on rare fallback</text>

  <!-- Lock + check -->
  <g transform="translate(580, 248)">
    {icon_lock(0, 0, 1.0)}
    <path d="M 18 -8 L 26 0 L 42 -16" fill="none" stroke="{GREEN}" stroke-width="3" stroke-linecap="round"/>
  </g>
  <text x="610" y="296" text-anchor="middle" font-size="12" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">&lt; 3 sec · zero typing · nothing to steal</text>
  <text x="610" y="318" text-anchor="middle" font-size="11" fill="{TEAL}" font-family="Arial,sans-serif">Live SIM proof · no OTP in the flow</text>
  <text x="610" y="352" text-anchor="middle" font-size="11" fill="{GREEN}" font-family="Arial,sans-serif">Higher conversion · lower fraud</text>
'''
    (OUT / "09_before_after.svg").write_text(svg_wrap(820, 390, body), encoding="utf-8")


def make_shield():
    body = flag_stripe(0, 0, 760, 18)
    body += f'''
  <text x="380" y="46" text-anchor="middle" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="{DARK}">
    Defense in depth — three security layers
  </text>
  <text x="380" y="68" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="{TEAL}">
    Digicom orchestrates the top layer for banks · Operator owns network borders
  </text>

  <!-- Outer ring: operator border -->
  <ellipse cx="380" cy="215" rx="310" ry="130" fill="{RED}" opacity="0.08" stroke="{RED}" stroke-width="2.5"/>
  <text x="380" y="108" text-anchor="middle" font-size="13" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">
    Layer 3 — SS7 · Diameter · 5G SEPP (operator border)
  </text>
  <text x="380" y="126" text-anchor="middle" font-size="10" fill="{GRAY}" font-family="Arial,sans-serif">
    Firewall at interconnect · block rogue signalling · protect home network
  </text>

  <!-- Middle ring: SMS firewall -->
  <ellipse cx="380" cy="220" rx="220" ry="95" fill="{YELLOW}" opacity="0.2" stroke="{GOLD}" stroke-width="2.5"/>
  <text x="620" y="175" font-size="12" fill="{DARK}" font-family="Arial,sans-serif" font-weight="bold">Layer 2</text>
  <text x="620" y="192" font-size="11" fill="{DARK}" font-family="Arial,sans-serif">SMS Firewall</text>
  <text x="620" y="208" font-size="10" fill="{GRAY}" font-family="Arial,sans-serif">Home routing</text>
  <text x="620" y="224" font-size="10" fill="{GRAY}" font-family="Arial,sans-serif">Anti-fraud rules</text>

  <!-- Inner ring: Silent Auth -->
  <ellipse cx="380" cy="225" rx="120" ry="62" fill="{GREEN}" opacity="0.35" stroke="{GREEN}" stroke-width="3"/>
  <text x="380" y="210" text-anchor="middle" font-size="13" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">Layer 1</text>
  <text x="380" y="228" text-anchor="middle" font-size="12" fill="{WHITE}" font-family="Arial,sans-serif" font-weight="bold">Silent Auth</text>
  <text x="380" y="246" text-anchor="middle" font-size="10" fill="{CREAM}" font-family="Arial,sans-serif">Replace OTP · no code to steal</text>

  <!-- Shield icon center -->
  <path d="M 380 248 L 350 262 V 290 Q 380 310 410 290 V 262 Z" fill="{TEAL}" stroke="{GOLD}" stroke-width="2"/>
  <path d="M 368 278 L 378 288 L 398 268" fill="none" stroke="{WHITE}" stroke-width="3" stroke-linecap="round"/>

  <!-- Layer labels on left -->
  <g transform="translate(30, 155)">
    <rect width="130" height="28" rx="6" fill="{GREEN}" opacity="0.15" stroke="{GREEN}" stroke-width="1"/>
    <text x="65" y="19" text-anchor="middle" font-size="10" fill="{GREEN}" font-family="Arial,sans-serif" font-weight="bold">Digicom for banks</text>
  </g>
  <line x1="160" y1="169" x2="260" y2="225" stroke="{GREEN}" stroke-width="1.5" stroke-dasharray="4 3"/>

  <g transform="translate(30, 210)">
    <rect width="130" height="28" rx="6" fill="{GOLD}" opacity="0.15" stroke="{GOLD}" stroke-width="1"/>
    <text x="65" y="19" text-anchor="middle" font-size="10" fill="{DARK}" font-family="Arial,sans-serif" font-weight="bold">Operator SMS stack</text>
  </g>
  <line x1="160" y1="224" x2="260" y2="225" stroke="{GOLD}" stroke-width="1.5" stroke-dasharray="4 3"/>

  <g transform="translate(30, 265)">
    <rect width="130" height="28" rx="6" fill="{RED}" opacity="0.12" stroke="{RED}" stroke-width="1"/>
    <text x="65" y="19" text-anchor="middle" font-size="10" fill="{RED}" font-family="Arial,sans-serif" font-weight="bold">Ethio Telecom core</text>
  </g>
  <line x1="160" y1="279" x2="260" y2="240" stroke="{RED}" stroke-width="1.5" stroke-dasharray="4 3"/>

  <!-- Bottom summary -->
  <rect x="60" y="355" width="640" height="50" rx="10" fill="{TEAL}" opacity="0.1" stroke="{TEAL}" stroke-width="1.5"/>
  <text x="380" y="378" text-anchor="middle" font-size="12" fill="{DARK}" font-family="Arial,sans-serif">
    Silent Auth removes the attack surface · SMS firewall protects remaining OTP · Border secures signalling
  </text>
  <text x="380" y="396" text-anchor="middle" font-size="11" fill="{TEAL}" font-family="Arial,sans-serif">
    Together: fewer fraud losses for banks, stronger network for operator, new revenue for Digicom
  </text>
'''
    (OUT / "10_shield.svg").write_text(svg_wrap(760, 420, body), encoding="utf-8")


def main():
    makers = [
        make_problem_sms,
        make_persona,
        make_silent_flow,
        make_architecture,
        make_fallback,
        make_threats,
        make_money_model,
        make_ethiopia_map,
        make_before_after,
        make_shield,
    ]
    for fn in makers:
        fn()
        print(f"  wrote {fn.__name__}")
    print(f"Done → {OUT}")


if __name__ == "__main__":
    main()
