#!/usr/bin/env python3
"""Minimal Shield (神盾) API mock for local integration testing."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


# StarRocks user format: {username}_{psaId}, e.g. 80372263_37422
MOCK_GROUPS = {
    "80372263": [{"psaId": "37422", "groupID": "group-demo-001"}],
    "deny_user": [{"psaId": "99999", "groupID": "group-deny-001"}],
}

MOCK_USER_PERMISSIONS = {
    "80372263": [
        {
            "rpd": "hive://group-demo-001:group@china1/hive/ad_model.db/ad_table?option=select",
            "authority": "select",
            "expireTime": None,
        },
        {
            "rpd": "hive://group-demo-001:group@china1/hive/ad_model.db?option=select",
            "authority": "select",
            "expireTime": None,
        },
        {
            "rpd": "hive://other-psa-group:group@china1/hive/other_db.db/t?option=select",
            "authority": "select",
            "expireTime": None,
        },
    ],
    "deny_user": [],
}

MOCK_GROUP_PERMISSIONS = {
    "group-demo-001": [
        {
            "rpd": "hive://group-demo-001:group@china1/hive/ad_model.db?option=select",
            "authority": "select",
            "expireTime": None,
        },
    ],
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[shield-mock] {self.path} {fmt % args}")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            payload = json.loads(body) if body else {}
        except json.JSONDecodeError:
            payload = {}

        if self.path.endswith("/oauthority/api/getUserAppGroup"):
            user = payload.get("user", "")
            data = MOCK_GROUPS.get(user, [])
            resp = {"success": True, "desc": "ok", "data": data}
        elif self.path.endswith("/oauthority/api/getResourcesByUser"):
            user = payload.get("user", "")
            data = MOCK_USER_PERMISSIONS.get(user, [])
            resp = {"success": True, "desc": "ok", "data": data}
        elif self.path.endswith("/oauthority/api/getResourcesByGroupID"):
            group_id = payload.get("groupID", "")
            data = MOCK_GROUP_PERMISSIONS.get(group_id, [])
            resp = {"success": True, "desc": "ok", "data": data}
        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"not found")
            return

        content = json.dumps(resp).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)


if __name__ == "__main__":
    port = 18999
    server = HTTPServer(("0.0.0.0", port), Handler)
    print(f"Shield mock API listening on http://0.0.0.0:{port}")
    server.serve_forever()
