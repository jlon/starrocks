#!/usr/bin/env python3
"""Simulate Shield permission check flow (same as fe-plugin-shield)."""
import hashlib
import json
import sys
import urllib.request
from typing import Optional


def md5_sign(params: dict, app_key: str) -> str:
    params = {k: v for k, v in params.items() if k != "signature"}
    parts = "&".join(f"{k}={params[k]}" for k in sorted(params))
    return hashlib.md5(f"{parts}:{app_key}".encode()).hexdigest()


def post(domain: str, path: str, params: dict) -> dict:
    url = domain.rstrip("/") + path
    body = json.dumps(params).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.loads(resp.read().decode())


def extract_app_group(rpd: str) -> Optional[str]:
    prefix = "hive://"
    marker = ":group@"
    if not rpd.startswith(prefix) or marker not in rpd:
        return None
    group_part = rpd[len(prefix):rpd.index(marker)]
    if group_part == "group":
        return None
    return group_part


def parse_rpd(rpd: str, area_filter: str = "china1/"):
    if area_filter not in rpd:
        return None
    idx = rpd.find(area_filter)
    rest = rpd[idx + len(area_filter):]
    if rest.startswith("hive/"):
        rest = rest[len("hive/"):]
    if ".db/" in rest:
        db, table = rest.split(".db/", 1)
        table = table.split("?", 1)[0]
        return db, table
    if rest.endswith(".db") or ".db?" in rest:
        db = rest.split(".db", 1)[0]
        return db, None
    return None


def load_permissions(domain: str, app_key: str, username: str, psa_id: str):
    base = {
        "operator": username,
        "sysID": "starrocks",
        "reqID": 1,
    }
    user_params = dict(base)
    user_params["user"] = username
    user_params["signature"] = md5_sign(user_params, app_key)
    groups = post(domain, "/oauthority/api/getUserAppGroup", user_params).get("data", [])
    allowed_groups = {g["groupID"] for g in groups if g.get("psaId") == psa_id}

    perm_params = dict(base)
    perm_params["user"] = username
    perm_params["areaCode"] = "china1"
    perm_params["resType"] = "hive"
    perm_params["authority"] = "select,create,admin"
    perm_params["signature"] = md5_sign(perm_params, app_key)
    perms = post(domain, "/oauthority/api/getResourcesByUser", perm_params).get("data", [])

    tables = set()
    dbs = set()
    for p in perms:
        rpd = p.get("rpd", "")
        app_group = extract_app_group(rpd)
        if app_group is not None and app_group not in allowed_groups:
            continue
        parsed = parse_rpd(rpd)
        if not parsed:
            continue
        db, table = parsed
        dbs.add(db)
        if table:
            tables.add((db, table))
    return dbs, tables


def main():
    domain = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18999"
    app_key = "test-app-key"
    cases = [
        ("80372263", "37422", "ad_model", "ad_table", True),
        ("80372263", "37422", "other_db", "t", False),
        ("deny_user", "99999", "ad_model", "ad_table", False),
    ]
    print(f"Shield permission simulation against {domain}")
    ok = True
    for username, psa_id, db, table, expected in cases:
        sr_user = f"{username}_{psa_id}"
        dbs, tables = load_permissions(domain, app_key, username, psa_id)
        allowed = db in dbs or (db, table) in tables
        status = "PASS" if allowed == expected else "FAIL"
        if status == "FAIL":
            ok = False
        print(f"  [{status}] user={sr_user} db={db} table={table} expected={expected} actual={allowed}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
