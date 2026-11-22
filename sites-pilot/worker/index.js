const upstreamUrl = "https://habit-tracker-plugin-pilot.onrender.com/mcp";
const version = "2026-07-28";
const serverInfo = { name: "habit-tracker-sites-pilot", version: "0.1.0" };
const operationId = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const datePattern = "^\\d{4}-\\d{2}-\\d{2}$";
const timePattern = "^(?:[01]\\d|2[0-3]):[0-5]\\d$";
const commonProperties = {
    operation_id: { type: "string", format: "uuid", description: "Один UUID на одну запись. При повторе используй тот же UUID." },
    title: { type: "string", minLength: 1, maxLength: 300 },
};
const annotations = { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false };

const tools = [
    {
        name: "create_task",
        description: "Создать задачу в тестовом Habit Tracker по явной просьбе пользователя. Указывай срок, только если пользователь его назвал. Не подтверждай сохранение до успеха инструмента.",
        annotations,
        inputSchema: {
            type: "object",
            properties: {
                ...commonProperties,
                description: { type: "string", maxLength: 4000 },
                due_date: { type: "string", pattern: datePattern },
                due_time: { type: "string", pattern: timePattern },
                zone_id: { type: "string", maxLength: 100, description: "Часовой пояс IANA, если известен." },
            },
            required: ["operation_id", "title"],
            additionalProperties: false,
        },
    },
    {
        name: "create_note",
        description: "Сохранить текстовую заметку в тестовом Habit Tracker по явной просьбе пользователя. Сохраняй текст заметки, не добавляй вложения. Не подтверждай сохранение до успеха инструмента.",
        annotations,
        inputSchema: {
            type: "object",
            properties: {
                ...commonProperties,
                text: { type: "string", maxLength: 16000 },
            },
            required: ["operation_id", "title", "text"],
            additionalProperties: false,
        },
    },
];

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

function normalizeArguments(tool, args) {
    if (!isObject(args)) return null;
    const { properties, required } = tool.inputSchema;
    // Some MCP clients serialize omitted optional arguments as JSON null. Treat
    // those exactly like omitted fields; required nulls still fail validation.
    return Object.fromEntries(Object.entries(args).filter(([key, value]) =>
        value !== null || required.includes(key) || !Object.hasOwn(properties, key)));
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

function validArguments(tool, args) {
    const { properties, required } = tool.inputSchema;
    if (!isObject(args) || !required.every(key => Object.hasOwn(args, key))) return false;
    if (!Object.entries(args).every(([key, value]) => {
        if (!Object.hasOwn(properties, key) || typeof value !== "string") return false;
        const rules = properties[key];
        return (rules.maxLength === undefined || value.length <= rules.maxLength)
            && (rules.pattern === undefined || new RegExp(rules.pattern).test(value));
    })) return false;
    return operationId.test(args.operation_id) && args.title.trim().length > 0
        && (!Object.hasOwn(args, "due_time") || Object.hasOwn(args, "due_date"));
}

async function upstreamReply(response, id) {
    const contentType = response.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase();
    if (contentType === "application/json") {
        return JSON.parse(await readText(response.body, 196608));
    }
    if (contentType !== "text/event-stream") throw new Error();
    let pending = "";
    let data = [];
    for await (const chunk of decodeBody(response.body, 196608)) {
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

async function createRecord(tool, args, token) {
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
            jsonrpc: "2.0", id, method: "tools/call",
            params: { name: tool.name, arguments: args },
        }),
        signal: AbortSignal.timeout(65000),
    });
    if (!response.ok) {
        void response.body?.cancel().catch(() => {});
        return response.status === 401 || response.status === 403 ? "authorization" : "unknown";
    }
    const message = await upstreamReply(response, id);
    if (message.jsonrpc !== "2.0" || message.id !== id || !isObject(message.result)) return "unknown";
    if (message.result.isError) return "rejected";
    const block = message.result.content?.[0];
    if (block?.type !== "text" || typeof block.text !== "string") return "unknown";
    return JSON.parse(block.text).status === "APPLIED" ? "saved" : "rejected";
}

const outcomes = {
    saved: "Запись сохранена в тестовом Habit Tracker.",
    authorization: "Тестовое подключение истекло или было отозвано. Нужен новый OAuth-доступ.",
    rejected: "Habit Tracker отклонил запись. Проверь поля и срок задачи.",
    unknown: "Сохранение не подтверждено. Если повторишь запрос, используй тот же operation_id.",
    unconfigured: "Тестовое подключение Habit Tracker ещё не настроено.",
};

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
        if (rpc.method === "tools/list") return complete(rpc.id, { tools });
        if (rpc.method !== "tools/call") return error(rpc.id, -32601, "Method not found");
        const tool = tools.find(candidate => candidate.name === rpc.params?.name);
        if (!tool) return error(rpc.id, -32602, "Unknown tool");
        const args = normalizeArguments(tool, rpc.params?.arguments);
        if (!validArguments(tool, args)) {
            return complete(rpc.id, {
                content: [{ type: "text", text: "Проверь обязательные поля и формат аргументов." }],
                isError: true,
            });
        }

        let outcome = "unconfigured";
        const token = env.HABIT_TRACKER_MCP_ACCESS_TOKEN;
        if (user === env.HABIT_TRACKER_SITE_OWNER_ID && typeof token === "string"
            && /^[A-Za-z0-9_-]{32,256}$/.test(token)) {
            try {
                outcome = await createRecord(tool, args, token);
            } catch {
                outcome = "unknown";
            }
        }
        return complete(rpc.id, {
            content: [{ type: "text", text: outcomes[outcome] }],
            isError: outcome !== "saved",
        });
    },
};
