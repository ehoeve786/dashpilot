"use strict";
// Assembles dash-apps/web-compose/widgets/*/ into widgets/_generated.{js,css}.
//
// A widget is one folder: descriptor.json, widget.js (defines `function create(ctx)`),
// preview.svg, and optionally widget.css. Nothing else needs editing to add one.
//
// The output is a classic script, not ES modules or fetched JSON: the perf gate
// inlines <script src> into jsdom and has neither module loading nor fetch.
//
// Usage:
//   node scripts/build-compose-widgets.js          # write the generated files
//   node scripts/build-compose-widgets.js --check  # fail if they are stale (CI)

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const APP_DIR = path.join(__dirname, "..", "dash-apps", "web-compose");
const WIDGETS_DIR = path.join(APP_DIR, "widgets");
const OUT_JS = path.join(WIDGETS_DIR, "_generated.js");
const OUT_CSS = path.join(WIDGETS_DIR, "_generated.css");

const TYPE_RE = /^[a-z0-9-]+$/;
const RENDERERS = new Set(["dom", "svg", "canvas", "rive"]);
const PROP_TYPES = new Set(["string", "number", "bool", "color", "enum"]);
const SIGNAL_KINDS = new Set(["number", "bool", "enum", "string"]);
const DESCRIPTOR_KEYS = new Set(["type", "version", "renderer", "name", "defaultSize", "minSize", "maxSize", "binds", "props"]);

// Load the real signal catalog so bind defaults are checked against it.
function loadSignals() {
  const window = {};
  const context = vm.createContext({ window, Intl, Map, Set, Math, Number, String, Date, Array, Object });
  for (const file of ["i18n.js", "signals.js"]) {
    vm.runInContext(fs.readFileSync(path.join(APP_DIR, "core", file), "utf8"), context, { filename: file });
  }
  return new Map(window.DashCompose.signals.all.map((s) => [s.id, s]));
}

function checkSize(errors, where, size) {
  if (size === undefined) return;
  if (!size || !Number.isInteger(size.w) || !Number.isInteger(size.h) || size.w < 1 || size.h < 1) {
    errors.push(`${where}: must be { w, h } with positive integers`);
  }
}

function validate(folder, d, signals) {
  const errors = [];
  const at = (field) => `${folder}/descriptor.json ${field}`;

  for (const key of Object.keys(d)) if (!DESCRIPTOR_KEYS.has(key)) errors.push(at(`unknown property "${key}"`));
  if (d.type !== folder) errors.push(at(`type "${d.type}" must match the folder name`));
  if (typeof d.type !== "string" || !TYPE_RE.test(d.type)) errors.push(at(`type must match ${TYPE_RE}`));
  if (!Number.isInteger(d.version) || d.version < 1) errors.push(at("version must be a positive integer"));
  if (!RENDERERS.has(d.renderer)) errors.push(at(`renderer must be one of ${[...RENDERERS].join(", ")}`));
  if (!d.name || typeof d.name.en !== "string") errors.push(at("name.en is required"));
  checkSize(errors, at("defaultSize"), d.defaultSize);
  checkSize(errors, at("minSize"), d.minSize);
  checkSize(errors, at("maxSize"), d.maxSize);
  if (!d.defaultSize) errors.push(at("defaultSize is required"));

  const bindKeys = new Set();
  for (const b of d.binds || []) {
    if (typeof b.key !== "string" || bindKeys.has(b.key)) errors.push(at(`binds: missing or duplicate key "${b.key}"`));
    bindKeys.add(b.key);
    if (!Array.isArray(b.kinds) || b.kinds.some((k) => !SIGNAL_KINDS.has(k))) {
      errors.push(at(`binds.${b.key}.kinds must list ${[...SIGNAL_KINDS].join(" / ")}`));
    }
    if (b.default !== null && b.default !== undefined) {
      const s = signals.get(b.default);
      if (!s) errors.push(at(`binds.${b.key}.default "${b.default}" is not a known signal`));
      else if (Array.isArray(b.kinds) && !b.kinds.includes(s.kind)) {
        errors.push(at(`binds.${b.key}.default "${b.default}" is a ${s.kind}, not one of ${b.kinds.join(", ")}`));
      }
    }
  }

  const propKeys = new Set();
  for (const p of d.props || []) {
    if (typeof p.key !== "string" || propKeys.has(p.key)) errors.push(at(`props: missing or duplicate key "${p.key}"`));
    propKeys.add(p.key);
    if (!PROP_TYPES.has(p.type)) errors.push(at(`props.${p.key}.type must be one of ${[...PROP_TYPES].join(", ")}`));
    if (!("default" in p)) errors.push(at(`props.${p.key} needs a default (adding a prop must never break old layouts)`));
    if (!p.name || typeof p.name.en !== "string") errors.push(at(`props.${p.key}.name.en is required`));
    if (p.type === "enum") {
      if (!Array.isArray(p.options) || p.options.length === 0) errors.push(at(`props.${p.key}.options is required for enum`));
      else if (!p.options.includes(p.default)) errors.push(at(`props.${p.key}.default must be one of its options`));
    }
  }
  return errors;
}

function build(widgetsDir = WIDGETS_DIR) {
  const signals = loadSignals();
  const folders = fs.readdirSync(widgetsDir, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !e.name.startsWith("_"))
    .map((e) => e.name)
    .sort();

  const errors = [];
  const js = [];
  const css = [];
  for (const folder of folders) {
    const dir = path.join(widgetsDir, folder);
    const file = (name) => path.join(dir, name);
    for (const required of ["descriptor.json", "widget.js", "preview.svg"]) {
      if (!fs.existsSync(file(required))) errors.push(`${folder}: missing ${required}`);
    }
    if (!fs.existsSync(file("descriptor.json")) || !fs.existsSync(file("widget.js"))) continue;

    let descriptor;
    try {
      descriptor = JSON.parse(fs.readFileSync(file("descriptor.json"), "utf8"));
    } catch (error) {
      errors.push(`${folder}/descriptor.json: ${error.message}`);
      continue;
    }
    errors.push(...validate(folder, descriptor, signals));

    const source = fs.readFileSync(file("widget.js"), "utf8").trimEnd();
    if (!/^function create\s*\(/m.test(source)) errors.push(`${folder}/widget.js: must define \`function create(ctx)\``);

    js.push(
      `  // --- ${folder}`,
      `  register(${JSON.stringify(descriptor)}, (function () {`,
      source.split("\n").map((l) => (l ? "    " + l : l)).join("\n"),
      "    return create;",
      "  })());",
    );
    if (fs.existsSync(file("widget.css"))) {
      css.push(`/* --- ${folder} */`, fs.readFileSync(file("widget.css"), "utf8").trimEnd(), "");
    }
  }

  // No "*/" in here: it would end the CSS comment early and swallow the first rule.
  const header = "GENERATED by scripts/build-compose-widgets.js from the widget folders - do not edit.";
  return {
    errors,
    count: folders.length,
    js: [`// ${header}`, "(function () {", "  const register = window.DashCompose.registerWidget;", ...js, "})();", ""].join("\n"),
    css: [`/* ${header} */`, "", ...css].join("\n"),
  };
}

function main() {
  const result = build();
  if (result.errors.length) {
    for (const e of result.errors) console.error("ERROR " + e);
    process.exit(1);
  }

  if (process.argv.includes("--check")) {
    const stale = [[OUT_JS, result.js], [OUT_CSS, result.css]]
      .filter(([file, content]) => !fs.existsSync(file) || fs.readFileSync(file, "utf8") !== content)
      .map(([file]) => path.relative(process.cwd(), file));
    if (stale.length) {
      console.error(`Stale: ${stale.join(", ")}. Run: node scripts/build-compose-widgets.js`);
      process.exit(1);
    }
    console.log(`OK: ${result.count} widgets, generated files up to date.`);
  } else {
    fs.writeFileSync(OUT_JS, result.js);
    fs.writeFileSync(OUT_CSS, result.css);
    console.log(`Built ${result.count} widgets -> widgets/_generated.js, widgets/_generated.css`);
  }
}

if (require.main === module) main();
module.exports = { build, validate, loadSignals };
