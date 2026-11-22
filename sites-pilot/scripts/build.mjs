import { copyFile, mkdir, readFile } from "node:fs/promises";

const sourceManifest = new URL("../.openai/hosting.json", import.meta.url);
const manifest = JSON.parse(await readFile(sourceManifest, "utf8"));
if (manifest.capabilities?.includes("mcp") !== true || manifest.static) {
  throw new Error("The pilot requires a Worker and the Sites MCP capability");
}

await mkdir(new URL("../dist/server/", import.meta.url), { recursive: true });
await mkdir(new URL("../dist/.openai/", import.meta.url), { recursive: true });
await copyFile(new URL("../worker/index.js", import.meta.url), new URL("../dist/server/index.js", import.meta.url));
await copyFile(sourceManifest, new URL("../dist/.openai/hosting.json", import.meta.url));
