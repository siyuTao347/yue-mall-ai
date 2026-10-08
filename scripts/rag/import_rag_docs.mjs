#!/usr/bin/env node
/**
 * 商品知识库语料导入脚本（设计文档 doc/agent/RAG/rag_development_design.md 第 11.1 节）。
 *
 * 作用：把 doc/data/RAG/*.md 通过管理端 API 导入 mall-agent-service，
 *       由服务端完成「切片 → 向量化入库」，保证幂等与向量写入逻辑只有一份实现。
 *
 * 用法：
 *   ADMIN_TOKEN=<管理员JWT> node scripts/rag/import_rag_docs.mjs
 *   ADMIN_TOKEN=xxx node scripts/rag/import_rag_docs.mjs --dry-run
 *   ADMIN_TOKEN=xxx node scripts/rag/import_rag_docs.mjs --only PROD-001,PROD-002
 *   ADMIN_TOKEN=xxx node scripts/rag/import_rag_docs.mjs --base-url http://localhost:8085
 *
 * 参数：
 *   --base-url <url>   服务地址，默认取 RAG_BASE_URL 或 http://localhost:8080（网关）
 *   --token <jwt>      管理员令牌，默认取 ADMIN_TOKEN
 *   --only <docIds>    只导入指定 doc_id，逗号分隔
 *   --dry-run          只解析与打印，不发起请求
 *   --no-poll          提交后不轮询任务状态（大批量导入时更快）
 *
 * 退出码：0 全部成功；1 存在失败文档；2 参数/环境错误。
 */

import { readFile, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const DOC_DIR = path.join(ROOT, 'doc/data/RAG');
const DOC_TYPE = 'PRODUCT';
const SOURCE_TYPE = 'BUILT_IN';
const POLL_INTERVAL_MS = 2000;
const POLL_TIMEOUT_MS = 5 * 60 * 1000;

function parseArgs(argv) {
  const args = { baseUrl: process.env.RAG_BASE_URL || 'http://localhost:8080', token: process.env.ADMIN_TOKEN || '', only: null, dryRun: false, poll: true };
  for (let i = 0; i < argv.length; i += 1) {
    switch (argv[i]) {
      case '--base-url': args.baseUrl = argv[++i]; break;
      case '--token': args.token = argv[++i]; break;
      case '--only': args.only = new Set(argv[++i].split(',').map((v) => v.trim())); break;
      case '--dry-run': args.dryRun = true; break;
      case '--no-poll': args.poll = false; break;
      default: throw new Error(`未知参数: ${argv[i]}`);
    }
  }
  return args;
}

/** 极简 YAML front-matter 解析：只支持本语料用到的 key: value 与 tags: [a, b] 形态。 */
function parseFrontMatter(raw) {
  if (!raw.startsWith('---\n')) {
    return { meta: {}, body: raw };
  }
  const end = raw.indexOf('\n---', 4);
  if (end < 0) {
    return { meta: {}, body: raw };
  }
  const meta = {};
  for (const line of raw.slice(4, end).split('\n')) {
    const match = /^([A-Za-z0-9_]+):\s*(.*)$/.exec(line.trim());
    if (!match) continue;
    const [, key, rawValue] = match;
    let value = rawValue.trim();
    if (value.startsWith('[') && value.endsWith(']')) {
      value = value.slice(1, -1).split(',').map((v) => v.trim().replace(/^"|"$/g, '')).filter(Boolean);
    } else {
      value = value.replace(/^"|"$/g, '');
    }
    meta[key] = value;
  }
  return { meta, body: raw.slice(raw.indexOf('\n', end + 1) + 1) };
}

/** 审核通过 + 已上架 + 非高风险，才对外可见；其余作为内部判例语料。 */
function resolveVisibility(meta) {
  const risk = String(meta.risk_level || '').toUpperCase();
  const visible = String(meta.audit_status || '').toUpperCase() === 'APPROVED'
    && String(meta.shelf_status || '').toUpperCase() === 'ON_SHELF'
    && risk !== 'CRITICAL' && risk !== 'MANUAL_REVIEW';
  return visible ? 'PUBLIC' : 'INTERNAL';
}

/** 把 front-matter 元数据压缩成一行正文前置说明，保证关键词（商家/类别/标签）可被抽取。 */
function buildMetadataLine(meta) {
  const fields = [
    ['类别', meta.category],
    ['资产类型', meta.asset_type],
    ['交付方式', meta.delivery_mode],
    ['商家', meta.merchant],
    ['价格(元)', meta.price_cny],
    ['库存', meta.stock],
    ['审核状态', meta.audit_status],
    ['上架状态', meta.shelf_status],
    ['风险等级', meta.risk_level],
  ].filter(([, value]) => value !== undefined && value !== '')
    .map(([label, value]) => `${label}=${value}`);
  const tags = Array.isArray(meta.tags) ? meta.tags : [];
  const suffix = tags.length > 0 ? `；标签=${tags.join('、')}` : '';
  return fields.length === 0 && !suffix ? '' : `> 商品元数据：${fields.join('；')}${suffix}\n\n`;
}

async function loadDocuments(only) {
  const entries = (await readdir(DOC_DIR)).filter((name) => name.endsWith('.md') && name !== 'README.md').sort();
  const documents = [];
  for (const name of entries) {
    const raw = await readFile(path.join(DOC_DIR, name), 'utf8');
    const { meta, body } = parseFrontMatter(raw);
    const docNo = meta.doc_id || path.basename(name, '.md').split('_')[0];
    if (only && !only.has(docNo)) continue;
    documents.push({
      docNo,
      file: name,
      docType: DOC_TYPE,
      title: `${meta.title || docNo} · 商品介绍`,
      content: buildMetadataLine(meta) + body.trim(),
      sourceType: SOURCE_TYPE,
      sourcePath: `doc/data/RAG/${name}`,
      visibility: resolveVisibility(meta),
      status: 'ENABLED',
      autoIndex: true,
    });
  }
  return documents;
}

async function callApi(baseUrl, token, method, urlPath, payload) {
  const response = await fetch(`${baseUrl}${urlPath}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const text = await response.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 保留原文用于报错 */ }
  if (!response.ok || (json && json.code !== undefined && json.code !== 0)) {
    throw new Error(`${method} ${urlPath} 失败: HTTP ${response.status} ${json?.msg || text.slice(0, 200)}`);
  }
  return json?.data;
}

async function pollTask(baseUrl, token, taskNo) {
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  for (;;) {
    const task = await callApi(baseUrl, token, 'GET', `/api/admin/agent/knowledge/tasks/${taskNo}`);
    if (['SUCCESS', 'PARTIAL', 'FAILED'].includes(task.status)) return task;
    if (Date.now() > deadline) return { ...task, status: 'TIMEOUT' };
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!existsSync(DOC_DIR)) throw new Error(`语料目录不存在: ${DOC_DIR}`);
  if (!args.dryRun && !args.token) throw new Error('缺少管理员令牌：请设置 ADMIN_TOKEN 或使用 --token');

  const documents = await loadDocuments(args.only);
  if (documents.length === 0) throw new Error('没有匹配的语料文件');
  console.log(`[RAG] 待导入文档 ${documents.length} 篇，目标服务 ${args.baseUrl}${args.dryRun ? '（dry-run）' : ''}`);

  const report = [];
  for (const doc of documents) {
    if (args.dryRun) {
      console.log(`  - ${doc.docNo} ${doc.title} [${doc.visibility}] chars=${doc.content.length}`);
      report.push({ docNo: doc.docNo, ok: true, note: 'dry-run' });
      continue;
    }
    try {
      const result = await callApi(args.baseUrl, args.token, 'POST', '/api/admin/agent/knowledge/documents', doc);
      let task = null;
      if (args.poll && result?.taskNo) {
        task = await pollTask(args.baseUrl, args.token, result.taskNo);
      }
      const ok = !task || ['SUCCESS', 'PARTIAL'].includes(task.status);
      console.log(`  ${ok ? 'OK  ' : 'FAIL'} ${doc.docNo} version=${result?.version} indexStatus=${result?.indexStatus} task=${result?.taskNo || '-'} ${task ? `(${task.status} ${task.processedChunks}/${task.totalChunks})` : ''}`);
      report.push({ docNo: doc.docNo, ok, taskStatus: task?.status, taskNo: result?.taskNo });
    } catch (error) {
      console.error(`  FAIL ${doc.docNo} ${error.message}`);
      report.push({ docNo: doc.docNo, ok: false, error: error.message });
    }
  }

  const failed = report.filter((item) => !item.ok);
  console.log(`[RAG] 导入完成：成功 ${report.length - failed.length} / 共 ${report.length}`);
  if (failed.length > 0) {
    console.log(`[RAG] 失败清单：${failed.map((item) => `${item.docNo}(${item.error || item.taskStatus})`).join(', ')}`);
    console.log('[RAG] 失败项可通过管理端 POST /tasks/{taskNo}/retry 或重新执行本脚本收敛（docNo 幂等）');
  }
  process.exit(failed.length > 0 ? 1 : 0);
}

main().catch((error) => {
  console.error(`[RAG] 导入终止: ${error.message}`);
  process.exit(2);
});
