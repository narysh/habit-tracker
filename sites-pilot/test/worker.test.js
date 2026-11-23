import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
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
const publicTools = [
    "get_tasks", "get_notes", "get_habits", "get_habit_history", "get_projects", "search_records", "get_week_review",
    "create_task", "create_habit", "create_note", "create_project", "propose_week_plan",
    "update_item", "complete_task", "mark_habit", "delete_item", "get_attachment",
];
const draftPage = await readFile(fileURLToPath(new URL("../../web/task.js", import.meta.url)), "utf8");

function renderDraft(fragment) {
    const elements = new Map(["status", "task-title", "page-title", "open"].map(id =>
        [id, { textContent: "", hidden: id === "open", href: "" }],
    ));
    const document = { getElementById(id) {
        if (!elements.has(id)) elements.set(id, { textContent: "", hidden: id === "open", href: "" });
        return elements.get(id);
    } };
    vm.runInNewContext(draftPage, { location: { hash: `#${fragment}` }, URLSearchParams, document });
    return elements;
}
const catalog = { tools: [...publicTools, "internal_admin"].map(name => ({ name, inputSchema: { type: "object" }, annotations: { readOnlyHint: name.startsWith("get_") } })) };

function request(method, params, options = {}) {
    const headers = { "Content-Type": "application/json", ...options.headers };
    if (options.user !== null) headers["oai-authenticated-user-id"] = options.user ?? owner;
    return worker.fetch(new Request(options.url ?? origin + "/mcp", {
        method: "POST",
        headers,
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
    }), options.env ?? env);
}

function stubUpstream(t, { list = catalog, call = requestBody => ({ content: [{ type: "text", text: `handled ${requestBody.params.name}` }] }), callResponse, callStatus } = {}) {
    return t.mock.method(globalThis, "fetch", (url, init) => {
        assert.equal(url, "https://habit-tracker-plugin-pilot.onrender.com/mcp");
        assert.equal(init.redirect, "error");
        assert.equal(init.headers.Authorization, "Bearer " + env.HABIT_TRACKER_MCP_ACCESS_TOKEN);
        assert.equal(init.headers["MCP-Protocol-Version"], "2025-11-25");
        const requestBody = JSON.parse(init.body);
        const result = requestBody.method === "tools/list" ? list : call(requestBody);
        if (requestBody.method === "tools/list") return Response.json({ jsonrpc: "2.0", id: requestBody.id, result });
        if (callResponse) return callResponse(requestBody);
        return Response.json({ jsonrpc: "2.0", id: requestBody.id, result }, callStatus ? { status: callStatus } : undefined);
    });
}

test("discovery mirrors the scoped backend catalog and filters tools outside the public allowlist", async t => {
    stubUpstream(t);
    const discovery = await (await request("server/discover")).json();
    assert.deepEqual(discovery.result.supportedVersions, ["2026-07-28"]);
    const catalogResponse = await (await request("tools/list")).json();
    assert.deepEqual(catalogResponse.result.tools.map(tool => tool.name), publicTools);
    assert.ok(catalogResponse.result.tools.every(tool => tool.name !== "internal_admin"));
});

test("untrusted requests, missing owner binding and tools outside the allowlist cannot reach Habit Tracker", async t => {
    const send = t.mock.method(globalThis, "fetch", () => { throw new Error("Unexpected network request"); });
    const args = { name: "create_task", arguments: { operation_id: operationId, title: "Проверка связи" } };
    assert.equal((await request("tools/call", args, { user: null })).status, 401);
    assert.equal((await request("tools/call", args, { user: "different-site-account" })).status, 403);
    assert.equal((await request("tools/call", args, { url: "https://other.example/mcp" })).status, 403);
    assert.equal((await request("tools/call", args, { headers: { Origin: "https://other.example" } })).status, 403);
    assert.equal((await request("tools/call", args, { headers: { "Mcp-Name": "create_note" } })).status, 400);
    assert.equal((await request("tools/call", args, { headers: { "MCP-Protocol-Version": "2025-11-25" } })).status, 400);
    assert.equal((await request("tools/call", args, { url: origin + "/mcp?user=other" })).status, 404);
    const notConfigured = await (await request("tools/call", args, {
        env: { ...env, HABIT_TRACKER_SITE_OWNER_ID: undefined },
    })).json();
    assert.equal(notConfigured.result.isError, false);
    assert.match(notConfigured.result.content[0].text, /ещё не настроено/);
    const disallowed = await (await request("tools/call", { name: "internal_admin", arguments: {} })).json();
    assert.equal(disallowed.error.code, -32602);
    assert.equal(send.mock.callCount(), 0);
});

test("all public read and write calls are proxied with their original arguments and results", async t => {
    const seen = [];
    const send = stubUpstream(t, { call: requestBody => {
        seen.push(requestBody.params);
        return { content: [{ type: "text", text: `handled ${requestBody.params.name}` }] };
    } });
    const actions = [
        ["get_tasks", {}],
        ["create_task", { operation_id: operationId, title: "Проверка связи" }],
        ["create_habit", { operation_id: operationId, title: "Бег", mode: "WEEKLY_TARGET", target_per_week: 3 }],
        ["create_note", { operation_id: operationId, title: "Важно", text: "Взять паспорт" }],
        ["search_records", { query: "связь" }],
        ["update_item", { id: "item-id", expected_version: 1, operation_id: operationId, content: { kind: "task", title: "Исправлено" } }],
        ["complete_task", { id: "item-id", expected_version: 2, operation_id: operationId, completed: true }],
        ["mark_habit", { habit_id: "habit-id", date: "2026-11-23", expected_version: 0, operation_id: operationId, completed: true }],
        ["delete_item", { id: "item-id", expected_version: 3, operation_id: operationId }],
        ["get_attachment", { id: "attachment-id" }],
    ];
    for (const [name, args] of actions) {
        const result = await (await request("tools/call", { name, arguments: args })).json();
        assert.equal(result.result.content[0].text, `handled ${name}`);
    }
    assert.deepEqual(seen, actions.map(([name, args]) => ({ name, arguments: args })));
    assert.equal(send.mock.callCount(), actions.length * 2, "each call checks the active OAuth scope first");
});

test("an unconfigured pilot exposes no tools and does not make an upstream request", async t => {
    const send = t.mock.method(globalThis, "fetch", () => { throw new Error("Unexpected network request"); });
    const list = await (await request("tools/list", {}, { env: { ...env, HABIT_TRACKER_MCP_ACCESS_TOKEN: undefined } })).json();
    assert.deepEqual(list.result.tools, []);
    const result = await (await request("tools/call", { name: "create_task", arguments: {} }, {
        env: { ...env, HABIT_TRACKER_MCP_ACCESS_TOKEN: undefined },
    })).json();
    assert.equal(result.result.isError, false);
    assert.match(result.result.content[0].text, /ещё не настроено/);
    assert.equal(send.mock.callCount(), 0);
});

test("the bridge accepts a matching SSE result without waiting for stream close", async t => {
    const encoder = new TextEncoder();
    let cancelled = false;
    stubUpstream(t, { callResponse: requestBody => {
        const id = requestBody.id;
        const body = new ReadableStream({
            start(controller) {
                controller.enqueue(encoder.encode(': comment\r\n\r\ndata: {"jsonrpc":"2.0","id":"other"}\n\n'));
                controller.enqueue(encoder.encode("data: " + JSON.stringify({
                    jsonrpc: "2.0", id, result: { content: [{ type: "text", text: "handled get_tasks" }] },
                })));
                controller.enqueue(encoder.encode("\r\n\r\n"));
            },
            cancel() { cancelled = true; },
        });
        return new Response(body, { headers: { "Content-Type": "text/event-stream; charset=utf-8" } });
    } });
    const result = await (await request("tools/call", { name: "get_tasks", arguments: {} })).json();
    assert.equal(result.result.content[0].text, "handled get_tasks");
    assert.equal(cancelled, true);
});

test("expired or ambiguous upstream results are not reported as successful", async t => {
    const send = t.mock.method(globalThis, "fetch", (_url, init) => {
        const requestBody = JSON.parse(init.body);
        if (requestBody.method === "tools/list") return Response.json({ jsonrpc: "2.0", id: requestBody.id, result: catalog });
        return new Response("upstream detail", { status: 401 });
    });
    const expired = await (await request("tools/call", { name: "create_task", arguments: { operation_id: operationId, title: "Check" } })).json();
    assert.equal(expired.result.isError, false);
    assert.match(expired.result.content[0].text, /OAuth/);
    assert.doesNotMatch(JSON.stringify(expired), /upstream detail/);
    send.mock.mockImplementation((_url, init) => {
        const requestBody = JSON.parse(init.body);
        if (requestBody.method === "tools/list") return Response.json({ jsonrpc: "2.0", id: requestBody.id, result: catalog });
        return new Response("unavailable", { status: 502 });
    });
    const uncertain = await (await request("tools/call", { name: "create_task", arguments: { operation_id: operationId, title: "Check" } })).json();
    assert.equal(uncertain.result.isError, false);
    assert.match(uncertain.result.content[0].text, /тот же operation_id/);
    assert.equal(send.mock.callCount(), 4);
});

test("mobile task links still open an editable task draft", () => {
    const open = renderDraft("title=Pay+internet&date=2026-12-01&time=18%3A00").get("open");
    assert.equal(open.hidden, false);
    const link = new URL(open.href);
    assert.equal(link.protocol, "habittracker:");
    assert.equal(link.pathname, "/task");
    assert.equal(link.searchParams.get("title"), "Pay internet");
    assert.equal(link.searchParams.get("time"), "18:00");
});

test("mobile note links open an editable note without account identifiers", () => {
    const elements = renderDraft("kind=note&title=Project+idea&text=First+line%0ASecond+line");
    assert.equal(elements.get("page-title").textContent, "Черновик заметки");
    assert.equal(elements.get("status").textContent, "Проверь и сохрани заметку в приложении.");
    const link = new URL(elements.get("open").href);
    assert.equal(link.pathname, "/note");
    assert.equal(link.searchParams.get("title"), "Project idea");
    assert.equal(link.searchParams.get("text"), "First line\nSecond line");
    assert.equal(link.searchParams.has("account"), false);
});

test("mobile draft page rejects unexpected and repeated fields", () => {
    for (const fragment of [
        "kind=note&title=Idea&text=body&account=other",
        "kind=note&title=One&title=Two&text=body",
        "title=Task&completed=true",
    ]) assert.equal(renderDraft(fragment).get("open").hidden, true);
});
