#!/usr/bin/env python3
"""Exercise a deployed Habit Tracker plugin on a fresh synthetic account."""

import argparse
import base64
import hashlib
from datetime import date
from html.parser import HTMLParser
import json
import os
import secrets
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ConsentPage(HTMLParser):
    def __init__(self):
        super().__init__()
        self.nonce = None
        self.code = None
        self.inside_code = False

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "input" and attributes.get("name") == "nonce":
            self.nonce = attributes.get("value")
        if tag == "code" and attributes.get("id") == "app-code":
            self.inside_code = True

    def handle_endtag(self, tag):
        if tag == "code":
            self.inside_code = False

    def handle_data(self, data):
        if self.inside_code:
            self.code = data.strip()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class Server:
    def __init__(self, origin):
        self.origin = origin
        self.http = build_opener(NoRedirect())

    def request(self, path, *, method=None, json_body=None, form=None, token=None, expect=200, accept=None):
        headers = {}
        if token:
            headers["Authorization"] = "Bearer " + token
        if accept:
            headers["Accept"] = accept
            headers["MCP-Protocol-Version"] = "2025-11-25"
        body = None
        if json_body is not None:
            body = json.dumps(json_body).encode()
            headers["Content-Type"] = "application/json"
        if form is not None:
            body = urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        request = Request(self.origin + path, data=body, headers=headers, method=method)
        try:
            response = self.http.open(request, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            require(response.code == expect, f"{request.get_method()} {path.split('?')[0]} returned HTTP {response.code}, expected {expect}")
            payload = response.read(1_048_577)
            require(len(payload) <= 1_048_576, "Unexpectedly large response")
            return response.headers, payload.decode("utf-8")

    def data(self, path, **kwargs):
        return json.loads(self.request(path, **kwargs)[1])

    def rpc(self, token, method, params=None):
        request = {"jsonrpc": "2.0", "id": 1, "method": method}
        if params is not None:
            request["params"] = params
        headers, body = self.request("/mcp", token=token, json_body=request, accept="application/json, text/event-stream")
        if headers.get_content_type() == "text/event-stream":
            body = next(line[5:].strip() for line in body.splitlines() if line.startswith("data:"))
        message = json.loads(body)
        require("result" in message and not message["result"].get("isError"), f"MCP rejected {method}")
        return message["result"]


def smoke(server, client_id, secret, callback, challenge):
    origin = server.origin
    resource = origin + "/mcp"
    require(server.data("/health").get("status") == "ok", "Health check failed")
    protected = server.data("/.well-known/oauth-protected-resource")
    metadata = server.data("/.well-known/oauth-authorization-server")
    require(protected.get("resource") == resource and protected.get("authorization_servers") == [origin], "MCP discovery declares a different public address")
    require(metadata.get("issuer") == origin and metadata.get("token_endpoint") == origin + "/oauth/token" and metadata.get("authorization_endpoint") == origin + "/oauth/authorize", "OAuth discovery declares a different issuer or endpoints")
    require("S256" in metadata.get("code_challenge_methods_supported", []) and "client_secret_post" in metadata.get("token_endpoint_auth_methods_supported", []), "OAuth discovery omits PKCE or client authentication")
    if challenge is not None:
        require(server.request("/.well-known/openai-apps-challenge")[1] == challenge, "Incorrect OpenAI challenge")
    basic_rpc = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
    server.request("/mcp", json_body=basic_rpc, expect=401)
    print("PASS HTTPS, discovery and denial of anonymous MCP", flush=True)

    sessions = []
    try:
        for _ in range(2):
            account = {"email": f"plugin-smoke-{uuid.uuid4().hex}@test.invalid", "password": secrets.token_urlsafe(32)}
            sessions.append(server.data("/api/v1/auth/register", json_body=account, expect=201)["token"])
        owner, other = sessions
        verifier = secrets.token_urlsafe(48)
        code_challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
        state = secrets.token_urlsafe(24)
        query = urlencode({"response_type": "code", "client_id": client_id, "redirect_uri": callback,
                           "state": state, "code_challenge": code_challenge, "code_challenge_method": "S256",
                           "resource": resource, "scope": "tracker:read tracker:write"})
        page = ConsentPage()
        page.feed(server.request("/oauth/authorize?" + query)[1])
        require(page.code and page.nonce, "Browser approval page is missing app pairing")
        finish = {"nonce": page.nonce}
        server.request("/oauth/app-consent", form=finish, expect=409)
        preview = server.data("/api/v1/account/chatgpt/preview", token=owner, json_body={"code": page.code})
        require(set(preview["scopes"]) == {"tracker:read", "tracker:write"}, "Unexpected permissions")
        server.request("/api/v1/account/chatgpt/approve", token=owner, json_body={"code": page.code}, expect=204)
        location = urlsplit(server.request("/oauth/app-consent", form=finish, expect=302)[0]["Location"])
        require(location._replace(query="").geturl() == callback, "Unexpected callback destination")
        returned = parse_qs(location.query)
        require(returned.get("state") == [state] and returned.get("iss") == [origin], "Unexpected callback issuer or state")
        token_params = {"client_id": client_id, "client_secret": secret, "resource": resource}
        tokens = server.data("/oauth/token", form={**token_params, "grant_type": "authorization_code",
                                                     "redirect_uri": callback, "code": returned["code"][0], "code_verifier": verifier})
        access = tokens["access_token"]
        print("PASS one-time Android approval and OAuth PKCE exchange", flush=True)

        initialization = server.rpc(access, "initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                                            "clientInfo": {"name": "habit-tracker-smoke", "version": "1"}})
        require("tools" in initialization.get("capabilities", {}), "MCP initialization did not advertise tools")
        catalog = server.rpc(access, "tools/list")
        expected_tools = {
            "get_tasks", "get_notes", "get_habits", "get_habit_history", "get_projects", "search_records", "get_week_review",
            "create_task", "create_habit", "create_note", "create_project", "propose_week_plan",
            "update_item", "complete_task", "mark_habit", "delete_item", "get_attachment",
        }
        require({tool["name"] for tool in catalog["tools"]} == expected_tools, "Unexpected tool catalog")
        task = {"operation_id": str(uuid.uuid4()), "title": "HTTPS plugin smoke test"}
        note = {"operation_id": str(uuid.uuid4()), "title": "HTTPS note smoke test", "text": "First line\nSecond line"}
        habit = {"operation_id": str(uuid.uuid4()), "title": "HTTPS walking smoke test", "mode": "WEEKLY_TARGET",
                 "target_per_week": 3, "starts_on": date.today().isoformat(), "zone_id": "UTC"}
        for _ in range(2):
            for tool_name, arguments in (("create_task", task), ("create_note", note)):
                result = server.rpc(access, "tools/call", {"name": tool_name, "arguments": arguments})
                require(json.loads(result["content"][0]["text"])["status"] == "APPLIED", f"{tool_name} did not apply")
        result = server.rpc(access, "tools/call", {"name": "create_habit", "arguments": habit})
        require(json.loads(result["content"][0]["text"])["status"] == "APPLIED", "create_habit did not apply")
        sync = {"operations": [], "cursor": 0}
        owner_items = server.data("/api/v1/sync", token=owner, json_body=sync)["changes"]
        require(len(owner_items) == 3, "Task, note or habit missing or duplicated in Android sync")
        items_by_kind = {item["content"]["kind"]: item for item in owner_items}
        require(set(items_by_kind) == {"task", "note", "habit"}, "Android sync did not return one task, note and habit")
        task_item = items_by_kind["task"]
        note_item = items_by_kind["note"]
        note_content = note_item["content"]
        habit_item = items_by_kind["habit"]
        require(task_item["content"]["title"] == task["title"], "Task title changed during sync")
        require(note_content["title"] == note["title"] and note_content["text"] == note["text"], "Note title or text changed during sync")
        require(habit_item["content"]["title"] == habit["title"], "Habit title changed during sync")
        require(not server.data("/api/v1/sync", token=other, json_body=sync)["changes"], "Task or note leaked to a different account")
        listing = server.rpc(access, "tools/call", {"name": "get_tasks", "arguments": {}})
        require([item["id"] for item in json.loads(listing["content"][0]["text"])] == [task_item["id"]], "MCP listing omitted the task or exposed the note")
        for name in ("get_notes", "get_habits"):
            result = server.rpc(access, "tools/call", {"name": name, "arguments": {}})
            require(len(json.loads(result["content"][0]["text"])) == 1, f"{name} returned an unexpected list")
        mark = {"operation_id": str(uuid.uuid4()), "habit_id": habit_item["id"], "date": date.today().isoformat(),
                "expected_version": 0, "completed": True}
        result = server.rpc(access, "tools/call", {"name": "mark_habit", "arguments": mark})
        require(json.loads(result["content"][0]["text"])["status"] == "APPLIED", "Habit mark did not apply")
        history = server.rpc(access, "tools/call", {"name": "get_habit_history", "arguments": {}})
        require(len(json.loads(history["content"][0]["text"])) == 1, "Habit history omitted the new mark")
        updated_content = dict(note_content)
        updated_content["title"] = "HTTPS note smoke test updated"
        update = {"operation_id": str(uuid.uuid4()), "id": note_item["id"], "expected_version": note_item["version"], "content": updated_content}
        result = server.rpc(access, "tools/call", {"name": "update_item", "arguments": update})
        updated_note = json.loads(result["content"][0]["text"])
        require(updated_note["status"] == "APPLIED" and updated_note["item"]["content"]["title"] == updated_content["title"], "Note update did not apply")
        completion = {"operation_id": str(uuid.uuid4()), "id": task_item["id"],
                      "expected_version": task_item["version"], "completed": True}
        result = server.rpc(access, "tools/call", {"name": "complete_task", "arguments": completion})
        completed = json.loads(result["content"][0]["text"])
        require(completed["status"] == "APPLIED" and completed["item"]["content"]["completed"], "Task completion did not apply")
        deletion = {"operation_id": str(uuid.uuid4()), "id": task_item["id"],
                    "expected_version": completed["item"]["version"]}
        result = server.rpc(access, "tools/call", {"name": "delete_item", "arguments": deletion})
        require(json.loads(result["content"][0]["text"])["status"] == "APPLIED", "Task deletion did not apply")
        updated = server.data("/api/v1/sync", token=owner, json_body=sync)["changes"]
        require(any(item["id"] == task_item["id"] and item["deleted"] for item in updated), "Task deletion missing from Android sync")
        require(any(item["id"] == note_item["id"] and item["content"]["title"] == updated_content["title"] for item in updated), "Note update missing from Android sync")
        require(any(item["content"]["kind"] == "habit_mark" for item in updated), "Habit mark missing from Android sync")
        print("PASS MCP CRUD, habit tracking, note/task reads, idempotency, Android sync and account isolation", flush=True)

        server.request("/api/v1/account/chatgpt", method="DELETE", token=owner, expect=204)
        server.request("/mcp", token=access, json_body=basic_rpc, expect=401)
        server.request("/oauth/token", form={**token_params, "grant_type": "refresh_token", "refresh_token": tokens["refresh_token"]}, expect=401)
        require(not server.data("/api/v1/account/chatgpt", token=owner)["connected"], "Account still shows connected")
        require(len(server.data("/api/v1/sync", token=owner, json_body=sync)["changes"]) == 4, "Android session no longer works")
        print("PASS revocation of MCP access while Android keeps working", flush=True)
    finally:
        for token in sessions:
            try:
                server.request("/api/v1/account/chatgpt", method="DELETE", token=token, expect=204)
                server.request("/api/v1/logout", method="POST", token=token, expect=204)
            except (OSError, RuntimeError):
                print("Synthetic session cleanup needs attention", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin", help="Trusted HTTPS origin of the isolated staging server")
    parser.add_argument("--client-id", default="habit-tracker-chatgpt")
    parser.add_argument("--callback", default="https://chatgpt.com/connector_platform_oauth_redirect")
    parser.add_argument("--challenge")
    args = parser.parse_args()
    origin = urlsplit(args.origin.rstrip("/"))
    callback = urlsplit(args.callback)
    secret = os.environ.get("HABIT_SMOKE_CLIENT_SECRET", "")
    require(origin.scheme == "https" and origin.hostname and not (origin.username or origin.password or origin.path or origin.query or origin.fragment), "Supply an HTTPS origin without a path or credentials")
    require(callback.scheme == "https" and callback.netloc == "chatgpt.com" and not (callback.query or callback.fragment), "Supply the exact ChatGPT callback")
    require(len(secret) >= 32, "Set HABIT_SMOKE_CLIENT_SECRET in the local environment")
    smoke(Server(origin.geturl()), args.client_id, secret, args.callback, args.challenge)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
