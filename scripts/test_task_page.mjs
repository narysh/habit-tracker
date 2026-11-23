import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/task.js", import.meta.url), "utf8");

function render(fragment) {
  const elements = new Map(["page-title", "task-title", "status", "open"].map((id) => [id, {
    textContent: "",
    href: "",
    hidden: id === "open",
  }]));
  vm.runInNewContext(source, {
    location: { hash: `#${fragment}` },
    URLSearchParams,
    Set,
    document: { getElementById: (id) => elements.get(id) },
  });
  return Object.fromEntries(elements);
}

const task = render("kind=task&title=Проверить+связь&date=2026-11-23");
assert.equal(task.open.hidden, false);
assert.match(task.open.href, /^habittracker:\/\/import\/task\?/);
assert.equal(task["task-title"].textContent, "Проверить связь");

const note = render("kind=note&title=Идея&text=Текст");
assert.equal(note["page-title"].textContent, "Черновик заметки");
assert.match(note.open.href, /^habittracker:\/\/import\/note\?/);

const habit = render("kind=habit&title=Бегать&days=2%2C4");
assert.equal(habit["page-title"].textContent, "Черновик привычки");
assert.match(habit.open.href, /^habittracker:\/\/import\/habit\?/);

for (const bad of [
  "kind=habit&title=Бегать&days=2%2C2",
  "kind=habit&title=Бегать&days=2&target=3",
  "kind=habit&title=Бегать&target=8",
]) {
  const result = render(bad);
  assert.equal(result.open.hidden, true, `Rejected malformed link: ${bad}`);
  assert.match(result.status.textContent, /нет корректного черновика/);
}

console.log("Task, note, habit links and invalid schedules passed.");
