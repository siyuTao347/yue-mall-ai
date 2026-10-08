// 管理端「新增文档」的本地文件解析：Markdown front-matter → 知识库文档请求体。
// 规则与 scripts/rag/import_rag_docs.mjs 保持一致，保证页面导入与脚本导入结果一致。

const DOC_TYPE_MAP = {
  product_intro: 'PRODUCT',
  product: 'PRODUCT',
  rule: 'RULE',
  policy: 'POLICY',
  after_sale: 'AFTER_SALE',
  aftersale: 'AFTER_SALE',
  risk: 'RISK',
  faq: 'FAQ'
};

export const RAG_DOC_TYPES = ['PRODUCT', 'RULE', 'POLICY', 'AFTER_SALE', 'RISK', 'FAQ'];

const unquote = value => String(value || '').trim().replace(/^["']|["']$/g, '');

/** 解析极简 YAML front-matter：支持 key: value 与 tags: [a, b]。 */
export const parseFrontMatter = raw => {
  const text = String(raw || '').replace(/^\uFEFF/, '');
  if (!text.startsWith('---\n') && !text.startsWith('---\r\n')) {
    return { meta: {}, body: text };
  }
  const end = text.indexOf('\n---', 4);
  if (end < 0) {
    return { meta: {}, body: text };
  }
  const meta = {};
  text.slice(4, end).split('\n').forEach(line => {
    const match = /^([A-Za-z0-9_]+):\s*(.*)$/.exec(line.trim());
    if (!match) return;
    const [, key, rawValue] = match;
    const value = rawValue.trim();
    if (value.startsWith('[') && value.endsWith(']')) {
      meta[key] = value.slice(1, -1).split(',').map(item => unquote(item)).filter(Boolean);
    } else {
      meta[key] = unquote(value);
    }
  });
  const bodyStart = text.indexOf('\n', end + 1);
  return { meta, body: bodyStart < 0 ? '' : text.slice(bodyStart + 1) };
};

const resolveDocType = meta => {
  const raw = String(meta.doc_type || meta.docType || '').toLowerCase();
  if (DOC_TYPE_MAP[raw]) return DOC_TYPE_MAP[raw];
  const upper = String(meta.doc_type || '').toUpperCase();
  return RAG_DOC_TYPES.includes(upper) ? upper : 'PRODUCT';
};

/** 审核通过 + 已上架 + 非高风险才对外可见，其余作为内部判例语料。 */
const resolveVisibility = meta => {
  if (!meta.audit_status && !meta.shelf_status && !meta.risk_level) return 'PUBLIC';
  const risk = String(meta.risk_level || '').toUpperCase();
  const visible = String(meta.audit_status || '').toUpperCase() === 'APPROVED'
    && String(meta.shelf_status || '').toUpperCase() === 'ON_SHELF'
    && risk !== 'CRITICAL' && risk !== 'MANUAL_REVIEW';
  return visible ? 'PUBLIC' : 'INTERNAL';
};

/** 把 front-matter 元数据压成一行正文前缀，保证商家/类别/标签仍参与关键词抽取。 */
const buildMetadataLine = meta => {
  const fields = [
    ['类别', meta.category],
    ['资产类型', meta.asset_type],
    ['交付方式', meta.delivery_mode],
    ['商家', meta.merchant],
    ['价格(元)', meta.price_cny],
    ['库存', meta.stock],
    ['审核状态', meta.audit_status],
    ['上架状态', meta.shelf_status],
    ['风险等级', meta.risk_level]
  ].filter(([, value]) => value !== undefined && value !== '')
    .map(([label, value]) => `${label}=${value}`);
  const tags = Array.isArray(meta.tags) ? meta.tags : [];
  const suffix = tags.length > 0 ? `；标签=${tags.join('、')}` : '';
  if (fields.length === 0 && !suffix) return '';
  return `> 商品元数据：${fields.join('；')}${suffix}\n\n`;
};

const titleFromBody = body => {
  const match = /^#\s+(.+)$/m.exec(body || '');
  return match ? match[1].trim() : '';
};

const docNoFromFileName = fileName => {
  const base = String(fileName || '').replace(/\.[^.]+$/, '');
  const prefix = base.split('_')[0].trim();
  return /^[A-Za-z0-9-]{2,64}$/.test(prefix) ? prefix.toUpperCase() : '';
};

/**
 * 解析单个 Markdown 文件为文档请求体。
 *
 * @returns {{payload: object, warnings: string[]}}
 */
export const parseMarkdownDocument = (raw, fileName = '') => {
  const { meta, body } = parseFrontMatter(raw);
  const warnings = [];
  const docNo = unquote(meta.doc_id || meta.doc_no || meta.docNo).toUpperCase() || docNoFromFileName(fileName);
  if (!docNo) warnings.push('未能识别文档编号，请手工填写');

  const docType = resolveDocType(meta);
  const rawTitle = unquote(meta.title) || titleFromBody(body) || String(fileName || '').replace(/\.[^.]+$/, '');
  // 与 scripts/rag/import_rag_docs.mjs 的命名保持一致
  const title = rawTitle && docType === 'PRODUCT' && !rawTitle.includes('介绍')
    ? `${rawTitle} · 商品介绍`
    : rawTitle;
  if (!title) warnings.push('未能识别标题，请手工填写');

  const content = (buildMetadataLine(meta) + String(body || '').trim()).trim();
  if (!content) warnings.push('文件内容为空');

  const hasFrontMatter = Object.keys(meta).length > 0;
  const payload = {
    docNo,
    docType,
    title: title.length > 128 ? title.slice(0, 128) : title,
    content,
    sourceType: hasFrontMatter && meta.doc_id ? 'BUILT_IN' : 'IMPORT',
    sourcePath: fileName || null,
    visibility: resolveVisibility(meta),
    status: 'ENABLED',
    autoIndex: true
  };
  return { payload, warnings };
};

export const isImportableFile = file => {
  const name = String(file?.name || '').toLowerCase();
  return /\.(md|markdown|txt)$/.test(name) || file?.type === 'text/markdown' || file?.type === 'text/plain';
};

export const readDocumentFile = async file => {
  const raw = await file.text();
  const { payload, warnings } = parseMarkdownDocument(raw, file.name);
  return { fileName: file.name, payload, warnings };
};
