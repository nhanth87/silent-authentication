#!/usr/bin/env python3
"""The verdict must not be readable by whoever happens to know the session id.

Run with the stack in the enforced shape:
  ./scripts/qr-lab.sh up live enforced
  python3 scripts/qr-tenant-probe.py
"""
import json, sys, urllib.error, urllib.parse, urllib.request

GATE="http://127.0.0.1:8085"; ECS="http://127.0.0.1:8095"
MSISDN="+251911111111"; IMSI="655010000000001"

def call(method,url,body=None,headers=None,form=None):
    h=dict(headers or {}); data=None
    if form is not None:
        data=urllib.parse.urlencode(form).encode(); h["Content-Type"]="application/x-www-form-urlencoded"
    elif body is not None:
        data=json.dumps(body).encode(); h.setdefault("Content-Type","application/json")
    req=urllib.request.Request(url,data=data,method=method,headers=h)
    try:
        with urllib.request.urlopen(req,timeout=30) as r: return r.status, r.read().decode()
    except urllib.error.HTTPError as e: return e.code, e.read().decode()
    except Exception as e: return 0, f"{type(e).__name__}"

def op_token():
    return json.loads(call("POST",f"{ECS}/entitlement/issue",
        {"msisdn":MSISDN,"imsi":IMSI,"eapMethod":"EAP-AKA"})[1])["token"]

def nv_token():
    st,t=call("POST",f"{GATE}/bc-authorize",form={"login_hint":f"operatortoken:{op_token()}",
        "scope":"dpv:FraudPreventionAndDetection number-verification:verify","client_id":"bank-lab"})
    st,t=call("POST",f"{GATE}/token",form={"grant_type":"urn:openid:params:grant-type:ciba",
        "auth_req_id":json.loads(t)["auth_req_id"],"client_id":"bank-lab"})
    return json.loads(t)["access_token"]

def brief(t):
    try: j=json.loads(t)
    except Exception: return f"<no body>"
    if isinstance(j,dict):
        if "code" in j: return f'{j["code"]} | {str(j.get("message"))[:60]}'
        if "state" in j: return f'state={j["state"]} verified={j.get("devicePhoneNumberVerified")}'
    return str(j)[:60]

ROWS=[]
def row(label,expect,method,url,**kw):
    st,t=call(method,url,**kw); ROWS.append((label,expect,st,brief(t))); return st,t

OWN={"X-Api-Key":"own-key"}; STRANGER={"X-Api-Key":"stranger-key"}

# create without the tenant key
at=nv_token()
row("create with NO api key","401","POST",f"{GATE}/qr-session/v1",body={},
    headers={"Authorization":f"Bearer {at}"})
# create with the right key -> a session owned by bank-a
at=nv_token()
st,t=row("create with the owning tenant key","200","POST",f"{GATE}/qr-session/v1",
    body={"phoneNumber":MSISDN},headers={"Authorization":f"Bearer {at}","X-Api-Key":"own-key"})
sid=json.loads(t)["sessionId"]
# claim it with the owning key
row("claim with the owning tenant key","200","POST",f"{GATE}/qr-session/v1/{sid}/claim",
    body={"accessTech":"LTE"},headers={"X-Sas-Src-Ip":"10.20.30.40",**OWN})
# now read the verdict every possible way
row("poll with NO credentials","401","GET",f"{GATE}/qr-session/v1/{sid}")
row("poll with a stranger tenant's key","403","GET",f"{GATE}/qr-session/v1/{sid}",headers=STRANGER)
row("poll with a wrong api key","401","GET",f"{GATE}/qr-session/v1/{sid}",headers={"X-Api-Key":"nope"})
row("poll with the owning tenant key","200","GET",f"{GATE}/qr-session/v1/{sid}",headers=OWN)
# a stranger must not be able to burn the session
at=nv_token()
st,t=row("create a second session (bank-a)","200","POST",f"{GATE}/qr-session/v1",
    body={"phoneNumber":MSISDN},headers={"Authorization":f"Bearer {at}","X-Api-Key":"own-key"})
sid2=json.loads(t)["sessionId"]
row("a stranger tries to claim it","403","POST",f"{GATE}/qr-session/v1/{sid2}/claim",
    body={"accessTech":"LTE"},headers={"X-Sas-Src-Ip":"10.20.30.99",**STRANGER})
row("the real phone still gets its turn","200","POST",f"{GATE}/qr-session/v1/{sid2}/claim",
    body={"accessTech":"LTE"},headers={"X-Sas-Src-Ip":"10.20.30.40",**OWN})

print(f"{'status':>6}  {'case':<42} answer")
print("-"*120)
bad=0
for label,expect,st,b in ROWS:
    flag=""
    want=int(expect) if expect.isdigit() else None
    if want is not None and st!=want: flag="  <<< UNEXPECTED"; bad+=1
    print(f"{st:>6}  {label:<42} {b}{flag}")
    print(f"{'':>8}  {'':<42} expected: {expect}")
print(f"\n{len(ROWS)} checks, {bad} unexpected")
sys.exit(1 if bad else 0)
