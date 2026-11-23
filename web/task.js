const fragment = location.hash.slice(1);
const params = new URLSearchParams(fragment);
const kind = params.get("kind") ?? "task";
const allowed = kind === "note"
  ? new Set(["kind", "title", "text"])
  : new Set(["title", "description", "date", "time"]);
const fields = new Set();
let valid = fragment.length <= 7900;
for (const [key] of params) {
  if (!allowed.has(key) || fields.has(key)) valid = false;
  fields.add(key);
}

const title = params.get("title")?.trim();
const date = params.get("date");
const time = params.get("time");
const description = params.get("description") ?? "";
const noteText = params.get("text") ?? "";
if (kind === "note" && valid && title && title.length <= 300 && noteText.length <= 3000) {
  document.getElementById("page-title").textContent = "Черновик заметки";
  document.getElementById("task-title").textContent = title;
  document.getElementById("status").textContent = "Проверь и сохрани заметку в приложении.";
  const link = document.getElementById("open");
  link.href = "habittracker://import/note?" + new URLSearchParams({ title, text: noteText }).toString();
  link.textContent = "Открыть в Habit Tracker";
  link.hidden = false;
} else if (kind === "task" && valid && title && title.length <= 300 && description.length <= 1000 &&
    (!date || /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(date)) &&
    (!time || (date && /^[0-9]{2}:[0-9]{2}$/.test(time)))) {
  document.getElementById("task-title").textContent = title;
  document.getElementById("status").textContent = "Проверь и сохрани задачу в приложении.";
  const link = document.getElementById("open");
  link.href = "habittracker://import/task?" + params.toString();
  link.textContent = "Открыть в Habit Tracker";
  link.hidden = false;
} else if (fragment) {
  document.getElementById("status").textContent = "В ссылке нет корректного черновика. Попроси ChatGPT сформировать её заново.";
}
