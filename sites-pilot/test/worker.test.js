import assert from "node:assert/strict";
import { test } from "node:test";
import worker from "../worker/index.js";

const origin = "https://habit-sample.chatgpt.site";
const owner = "owner-scoped-by-sites";
const env = {
    HABIT_TRACKER_SITE_ORIGIN: origin,
    HABIT_TRACKER_SITE_OWNER_ID: owner,
    HABIT_TRACKER_MCP_ACCESS_TOKEN: "x".repeat(43),
};
const operationId = "0fa32a3e-8e6d-4b12-9fde-8b87edf84e41";
const task = { operation_id: operationId, title: "Проверка связи", due_date: "2026-12-01", due_time: "18:00" };

function request(method, params, options = {}) {
    const headers = { "Content-Type": "application/json", ...options.headers };
    if (options.user !== null) headers["oai-authenticated-user-id"] = options.user ?? owner;
    return worker.fetch(new Request(options.url ?? origin + "/mcp", {
        method: "POST",
        headers,
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
    }), options.env ?? env);
}

test("discovery uses the stateless MCP protocol and only advertises creation", async () => {
    const discovery = await (await request("server/discover")).json();
    assert.deepEqual(discovery.result.supportedVersions, ["2026-07-28"]);
    const catalog = await (await request("tools/list")).json();
    assert.deepEqual(catalog.result.tools.map(tool => tool.name), ["create_task", "create_note"]);
    assert.ok(catalog.result.tools.every(tool => !tool.annotations.readOnlyHint && tool.annotations.idempotentHint));
});

test("untrusted requests, missing owner binding and disallowed tools cannot reach Habit Tracker", async t => {
    const send = t.mock.method(globalThis, "fetch", () => { throw new Error("Unexpected network request"); });
    const args = { name: "create_task", arguments: task };
    assert.equal((await request("tools/call", args, { user: null })).status, 401);
    assert.equal((await request("tools/call", args, { user: "different-site-account" })).status, 403);
    assert.equal((await request("tools/call", args, { url: "https://other.example/mcp" })).status, 403);
    assert.equal((await request("tools/call", args, { headers: { Origin: "https://other.example" } })).status, 403);
    assert.equal((await request("tools/call", args, { headers: { "Mcp-Name": "create_note" } })).status, 400);
    assert.equal((await request("tools/call", args, { headers: { "MCP-Protocol-Version": "2025-11-25" } })).status, 400);
    assert.equal((await request("tools/call", args, { url: origin + "/mcp?user=other" })).status, 404);
    assert.equal((await request("tools/call", args, { url: origin + "/api/v1/mutations" })).status, 404);
    const notConfigured = await (await request("tools/call", args, {
        env: { ...env, HABIT_TRACKER_SITE_OWNER_ID: undefined },
    })).json();
    assert.equal(notConfigured.result.isError, false);
    assert.match(notConfigured.result.content[0].text, /ещё не настроено/);
    const notAllowed = await (await request("tools/call", { ...args, name: "delete_item" })).json();
    assert.equal(notAllowed.error.code, -32602);
    assert.equal(send.mock.callCount(), 0);
});

test("unexpected arguments and incomplete time data are rejected", async t => {
    const send = t.mock.method(globalThis, "fetch", () => { throw new Error("Unexpected network request"); });
    const invalidArguments = [
        { ...task, token: "should-not-be-forwarded" },
        { ...task, operation_id: "not-a-uuid" },
        { ...task, due_date: undefined, due_time: "18:00" },
        { ...task, due_time: "23:59:00" },
        { ...task, title: " " },
    ];
    for (const args of invalidArguments) {
        const response = await request("tools/call", { name: "create_task", arguments: args });
        assert.equal((await response.json()).result.isError, true);
    }
    assert.equal(send.mock.callCount(), 0);
});

test("creation forwards only approved text fields and preserves the idempotency ID", async t => {
    const send = t.mock.method(globalThis, "fetch", (url, init) => {
        assert.equal(url, "https://habit-tracker-plugin-pilot.onrender.com/mcp");
        assert.equal(init.redirect, "error");
        assert.equal(init.headers.Authorization, "Bearer " + env.HABIT_TRACKER_MCP_ACCESS_TOKEN);
        assert.equal(init.headers["MCP-Protocol-Version"], "2025-11-25");
        const { id } = JSON.parse(init.body);
        return Response.json({
            jsonrpc: "2.0", id,
            result: { content: [{ type: "text", text: JSON.stringify({ status: "APPLIED", item: { title: "private" } }) }] },
        });
    });
    const note = { operation_id: operationId, title: "Важно", text: "Взять паспорт" };
    for (const [name, args] of [["create_task", task], ["create_task", task], ["create_note", note]]) {
        const result = await (await request("tools/call", { name, arguments: args })).json();
        assert.equal(result.result.isError, false);
        assert.doesNotMatch(JSON.stringify(result), /private|Взять паспорт/);
    }
    assert.deepEqual(send.mock.calls.map(call => JSON.parse(call.arguments[1].body).params), [
        { name: "create_task", arguments: task },
        { name: "create_task", arguments: task },
        { name: "create_note", arguments: note },
    ]);
});

test("null optional fields from MCP clients are treated as omitted", async t => {
    const send = t.mock.method(globalThis, "fetch", (_url, init) => {
        const { id } = JSON.parse(init.body);
        return Response.json({
            jsonrpc: "2.0", id,
            result: { content: [{ type: "text", text: JSON.stringify({ status: "APPLIED" }) }] },
        });
    });
    const args = { operation_id: operationId, title: task.title, due_date: task.due_date, description: null, due_time: null, zone_id: null };
    const result = await (await request("tools/call", { name: "create_task", arguments: args })).json();
    assert.equal(result.result.isError, false);
    assert.deepEqual(JSON.parse(send.mock.calls[0].arguments[1].body).params.arguments,
        { operation_id: operationId, title: task.title, due_date: task.due_date });
});

test("an unconfigured pilot returns a clear result instead of an argument error", async t => {
    const send = t.mock.method(globalThis, "fetch", () => { throw new Error("Unexpected network request"); });
    const result = await (await request("tools/call", { name: "create_task", arguments: task }, {
        env: { ...env, HABIT_TRACKER_MCP_ACCESS_TOKEN: undefined },
    })).json();
    assert.equal(result.result.isError, false);
    assert.match(result.result.content[0].text, /ещё не настроено/);
    assert.equal(send.mock.callCount(), 0);
});

test("the bridge accepts a matching SSE result without waiting for stream close", async t => {
    const encoder = new TextEncoder();
    let cancelled = false;
    t.mock.method(globalThis, "fetch", (_url, init) => {
        const { id } = JSON.parse(init.body);
        const body = new ReadableStream({
            start(controller) {
                controller.enqueue(encoder.encode(': comment\r\n\r\ndata: {"jsonrpc":"2.0","id":"other"}\n\n'));
                controller.enqueue(encoder.encode("data: " + JSON.stringify({
                    jsonrpc: "2.0", id,
                    result: { content: [{ type: "text", text: '{"status":"APPLIED"}' }] },
                })));
                controller.enqueue(encoder.encode("\r\n\r\n"));
            },
            cancel() { cancelled = true; },
        });
        return new Response(body, { headers: { "Content-Type": "text/event-stream; charset=utf-8" } });
    });
    const result = await (await request("tools/call", { name: "create_task", arguments: task })).json();
    assert.equal(result.result.isError, false);
    assert.equal(cancelled, true);
});

test("expired or ambiguous upstream results remain explicit and never retry automatically", async t => {
    const send = t.mock.method(globalThis, "fetch", () => new Response("upstream detail", { status: 401 }));
    const expired = await (await request("tools/call", { name: "create_task", arguments: task })).json();
    assert.equal(expired.result.isError, false);
    assert.match(expired.result.content[0].text, /OAuth/);
    assert.doesNotMatch(JSON.stringify(expired), /upstream detail/);
    send.mock.mockImplementation(() => new Response("unavailable", { status: 502 }));
    const uncertain = await (await request("tools/call", { name: "create_task", arguments: task })).json();
    assert.equal(uncertain.result.isError, false);
    assert.match(uncertain.result.content[0].text, /тот же operation_id/);
    assert.equal(send.mock.callCount(), 2);
});
