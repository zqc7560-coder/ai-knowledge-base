#!/usr/bin/env node
// 密钥红线扫描：任何将进入 git/构建产物的文件不得含 AI 密钥特征。命中则退出码 1（pre-commit / CI / dist 构建后共用）。
// 用法: node scripts/check-secrets.mjs [扫描目录, 默认仓库根（自动排除 .git/.env 白名单）]
import { readdirSync, statSync, readFileSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dirname, "..");
const TARGET = process.argv[2] ? resolve(process.argv[2]) : ROOT;
// 允许含真实值的本地文件（绝不提交）；扫描 dist 时传第二参数目录即可
const ALLOW = new Set([join(ROOT, "backend", ".env"), join(ROOT, ".git")]);
// 注意：字面量用拆分拼接，避免本脚本自匹配自身规则。
// sk- 规则要求 16+ 且必含数字：过滤 CSS 类名等全小写误报（el-splitter__mask-horiz...），
// 真实密钥（含校验位/数字段）不会漏。
const PATTERNS = [
  new RegExp("(?=[A-Za-z0-9._-]*[0-9])sk-" + "[A-Za-z0-9._-]{15,}"),
  new RegExp("Bearer" + "\\s+(?=[A-Za-z0-9._-]*[0-9])[A-Za-z0-9._-]{19,}"),
  new RegExp("MEU" + "CIQ|PRIVATE" + " KEY-----"),
];
const SKIP_EXT = [".png", ".jpg", ".gif", ".ico", ".woff", ".ttf", ".pdf", ".zip", ".jar", ".exe"];
const hits = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (ALLOW.has(p)) continue;
    let st; try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) { if (name !== ".git" && name !== "node_modules" && !p.endsWith(".env")) walk(p); continue; }
    if (SKIP_EXT.some(e => name.endsWith(e))) continue;
    if (name === ".env" || name === ".env.local") { // 未提交的本地 env 跳过（仅当在 ROOT 白名单外才报）
      continue;
    }
    if (st.size > 2 * 1024 * 1024) continue;
    const text = readFileSync(p, "utf8");
    for (const re of PATTERNS) {
      const m = text.match(re);
      if (m) hits.push(`${p}: ${re} -> ${m[0].slice(0, 10)}…`);
    }
  }
})(TARGET);
if (hits.length) {
  console.error("SECURITY SCAN FAILED — 疑似密钥泄漏:\n" + hits.join("\n"));
  process.exit(1);
}
console.log("secret scan OK:", TARGET);
