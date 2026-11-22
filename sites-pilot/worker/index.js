const upstreamUrl = "https://habit-tracker-plugin-pilot.onrender.com/mcp";
const version = "2026-07-28";
const serverInfo = { name: "habit-tracker-sites-pilot", version: "0.1.0" };
const allowedTools = new Set([
    "get_tasks", "get_notes", "get_habits", "get_habit_history", "get_projects", "search_records", "get_week_review",
    "create_task", "create_habit", "create_note", "create_project", "propose_week_plan",
    "update_item", "complete_task", "mark_habit", "delete_item", "get_attachment",
]);

const responseHeaders = {
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
};

function json(body, status = 200) {
    return Response.json(body, { status, headers: responseHeaders });
}

function error(id, code, message, status = 200, data) {
    return json({
        jsonrpc: "2.0",
        ...(id !== undefined && { id }),
        error: { code, message, ...(data && { data }) },
    }, status);
}

function complete(id, result) {
    return json({
        jsonrpc: "2.0",
        id,
        result: {
            resultType: "complete",
            _meta: { "io.modelcontextprotocol/serverInfo": serverInfo },
            ...result,
        },
    });
}

function isObject(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

async function* decodeBody(body, limit) {
    const decoder = new TextDecoder("utf-8", { fatal: true });
    const reader = body?.getReader();
    if (!reader) return;
    let size = 0;
    try {
        for (;;) {
            const { value, done } = await reader.read();
            if (done) {
                yield decoder.decode();
                return;
            }
            size += value.byteLength;
            if (size > limit) throw new RangeError("Payload exceeds the pilot limit");
            yield decoder.decode(value, { stream: true });
        }
    } finally {
        void reader.cancel().catch(() => {});
        reader.releaseLock();
    }
}

async function readText(body, limit) {
    let text = "";
    for await (const part of decodeBody(body, limit)) text += part;
    return text;
}

async function upstreamReply(response, id, limit = 12 * 1024 * 1024) {
    const contentType = response.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase();
    if (contentType === "application/json") {
        return JSON.parse(await readText(response.body, limit));
    }
    if (contentType !== "text/event-stream") throw new Error();
    let pending = "";
    let data = [];
    for await (const chunk of decodeBody(response.body, limit)) {
        const lines = (pending + chunk).split("\n");
        pending = lines.pop();
        for (const entry of lines) {
            const line = entry.replace(/\r$/, "");
            if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
            if (line === "" && data.length) {
                const message = JSON.parse(data.join("\n"));
                if (message.id === id) return message;
                data = [];
            }
        }
    }
    throw new Error();
}

async function callUpstream(method, params, token) {
    const id = crypto.randomUUID();
    const response = await fetch(upstreamUrl, {
        method: "POST",
        redirect: "error",
        headers: {
            Authorization: "Bearer " + token,
            "Content-Type": "application/json",
            Accept: "application/json, text/event-stream",
            "MCP-Protocol-Version": "2025-11-25",
        },
        body: JSON.stringify({
            jsonrpc: "2.0", id, method, ...(params && { params }),
        }),
        signal: AbortSignal.timeout(65000),
    });
    if (!response.ok) {
        void response.body?.cancel().catch(() => {});
        return { failure: response.status === 401 || response.status === 403 ? "authorization" : "unknown" };
    }
    const message = await upstreamReply(response, id);
    if (message.jsonrpc !== "2.0" || message.id !== id || (!isObject(message.result) && !isObject(message.error))) return { failure: "unknown" };
    if (message.error) return { failure: "rejected" };
    return { result: message.result };
}

const outcomes = {
    authorization: "Тестовое подключение истекло или было отозвано. Нужен новый OAuth-доступ.",
    unknown: "Сохранение не подтверждено. Если повторишь запрос, используй тот же operation_id.",
    unconfigured: "Тестовое подключение Habit Tracker ещё не настроено.",
};

async function configuredToken(user, env) {
    const token = env.HABIT_TRACKER_MCP_ACCESS_TOKEN;
    return user === env.HABIT_TRACKER_SITE_OWNER_ID && typeof token === "string"
        && /^[A-Za-z0-9_-]{32,256}$/.test(token) ? token : undefined;
}

async function scopedTools(token) {
    const upstream = await callUpstream("tools/list", undefined, token);
    if (!upstream.result) return upstream;
    const tools = upstream.result.tools;
    if (!Array.isArray(tools)) return { failure: "unknown" };
    return { result: { ...upstream.result, tools: tools.filter(tool => allowedTools.has(tool.name)) } };
}

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        if (url.protocol !== "https:" || url.origin !== env.HABIT_TRACKER_SITE_ORIGIN) {
            return json({ error: "Unknown site origin" }, 403);
        }
        const origin = request.headers.get("Origin");
        if (origin && origin !== url.origin && origin !== "https://chatgpt.com") {
            return json({ error: "Origin rejected" }, 403);
        }
        if (url.search || !["/", "/mcp"].includes(url.pathname)) return json({ error: "Not found" }, 404);
        const user = request.headers.get("oai-authenticated-user-id");
        if (!user) return json({ error: "ChatGPT Sites sign-in required" }, 401);

        // The Site is always private. During bootstrap only the signed-in Site owner
        // can read this page to retrieve the Site-scoped identifier for configuration.
        if (env.HABIT_TRACKER_SITE_OWNER_ID && user !== env.HABIT_TRACKER_SITE_OWNER_ID) {
            return json({ error: "Pilot owner required" }, 403);
        }
        if (url.pathname === "/" && request.method === "GET") {
            return json({
                site: "Habit Tracker — личный тест",
                ...(env.HABIT_TRACKER_SITE_OWNER_ID ? { owner_configured: true } : { site_owner_id: user }),
            });
        }
        if (url.pathname !== "/mcp" || request.method !== "POST") {
            return json({ error: "Method not allowed" }, 405);
        }
        if (request.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase() !== "application/json") {
            return json({ error: "JSON required" }, 415);
        }
        let rpc;
        try {
            rpc = JSON.parse(await readText(request.body, 131072));
        } catch (failure) {
            return failure instanceof RangeError
                ? json({ error: "Request too large" }, 413)
                : error(undefined, -32700, "Parse error", 400);
        }
        if (!isObject(rpc) || rpc.jsonrpc !== "2.0" || typeof rpc.method !== "string"
            || !(typeof rpc.id === "string" || Number.isSafeInteger(rpc.id))) {
            return error(undefined, -32600, "Invalid request", 400);
        }
        const methodHeader = request.headers.get("Mcp-Method");
        const nameHeader = request.headers.get("Mcp-Name");
        const requestVersion = rpc.params?._meta?.["io.modelcontextprotocol/protocolVersion"];
        const headerVersion = request.headers.get("MCP-Protocol-Version");
        if ((methodHeader && methodHeader !== rpc.method) || (nameHeader && nameHeader !== rpc.params?.name)
            || (headerVersion && requestVersion && headerVersion !== requestVersion)) {
            return error(rpc.id, -32020, "MCP routing header mismatch", 400);
        }
        if (rpc.method === "server/discover") {
            return complete(rpc.id, { supportedVersions: [version], capabilities: { tools: {} } });
        }
        const requested = requestVersion ?? headerVersion;
        if (requested && requested !== version) {
            return error(rpc.id, -32022, "Unsupported MCP protocol version", 400,
                { supported: [version], requested });
        }
        const token = await configuredToken(user, env);
        if (rpc.method === "tools/list") {
            if (!token) return complete(rpc.id, { tools: [] });
            try {
                const upstream = await scopedTools(token);
                return upstream.result
                    ? complete(rpc.id, upstream.result)
                    : complete(rpc.id, { tools: [] });
            } catch {
                return complete(rpc.id, { tools: [] });
            }
        }
        if (rpc.method !== "tools/call") return error(rpc.id, -32601, "Method not found");
        if (typeof rpc.params?.name !== "string" || !allowedTools.has(rpc.params.name)) {
            return error(rpc.id, -32602, "Unknown tool");
        }
        if (!token) return complete(rpc.id, { content: [{ type: "text", text: outcomes.unconfigured }], isError: false });
        try {
            const catalog = await scopedTools(token);
            if (!catalog.result?.tools.some(tool => tool.name === rpc.params.name)) {
                return error(rpc.id, -32602, "Tool is not available for this OAuth permission");
            }
            const upstream = await callUpstream("tools/call", {
                name: rpc.params.name,
                arguments: isObject(rpc.params.arguments) ? rpc.params.arguments : {},
            }, token);
            if (upstream.result) return complete(rpc.id, upstream.result);
            return complete(rpc.id, {
                content: [{ type: "text", text: outcomes[upstream.failure] ?? outcomes.unknown }],
                isError: false,
            });
        } catch {
            return complete(rpc.id, {
                content: [{ type: "text", text: outcomes.unknown }],
                isError: false,
            });
        }
    },
};
