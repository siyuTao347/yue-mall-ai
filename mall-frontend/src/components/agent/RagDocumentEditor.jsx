import { useEffect, useRef, useState } from 'react';
import { knowledgeApi } from '../../api/agentApi';
import { isImportableFile, readDocumentFile } from '../../utils/markdownDoc';
import { DOC_TYPE_OPTIONS, VISIBILITY_OPTIONS } from './ragLabels';

const SOURCE_TYPES = [
  { value: 'MANUAL', label: '手工录入' },
  { value: 'BUILT_IN', label: '内置语料' },
  { value: 'IMPORT', label: '批量导入' }
];

const STATUS_LABELS = {
  READY: '待导入',
  RUNNING: '导入中',
  SUCCESS: '已导入',
  FAILED: '失败'
};

const STATUS_TONES = {
  READY: 'default',
  RUNNING: 'info',
  SUCCESS: 'success',
  FAILED: 'danger'
};

const emptyForm = () => ({
  docNo: '',
  docType: 'PRODUCT',
  title: '',
  content: '',
  sourceType: 'MANUAL',
  sourcePath: '',
  visibility: 'PUBLIC',
  status: 'ENABLED',
  autoIndex: true
});

const formOf = document => (document
  ? {
    docNo: document.docNo || '',
    docType: document.docType || 'PRODUCT',
    title: document.title || '',
    content: '',
    sourceType: document.sourceType || 'MANUAL',
    sourcePath: document.sourcePath || '',
    visibility: document.visibility || 'PUBLIC',
    status: document.status || 'DISABLED',
    autoIndex: true
  }
  : emptyForm());

/**
 * 文档新增 / 编辑抽屉。
 * <p>支持直接导入本地 Markdown：解析 front-matter 自动填充编号、标题、类型、可见范围；
 * 新建模式可多选文件批量提交（逐篇调用新增接口，保证切片与向量入库逻辑只有一份）。</p>
 */
export const RagDocumentEditor = ({ mode, document, submitting, onClose, onSubmit, onBatchImport }) => {
  const [form, setForm] = useState(() => formOf(document));
  const [loading, setLoading] = useState(mode === 'edit');
  const [loadError, setLoadError] = useState('');
  const [formError, setFormError] = useState('');
  const [queue, setQueue] = useState([]);
  const [importError, setImportError] = useState('');
  const [importing, setImporting] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef(null);

  useEffect(() => {
    if (mode !== 'edit' || !document) return undefined;
    const controller = new AbortController();
    setLoading(true);
    knowledgeApi.documentContent(document.id, { signal: controller.signal })
      .then(data => {
        setForm(prev => ({ ...prev, content: (data && data.content) || '' }));
        setLoadError('');
      })
      .catch(error => {
        if (error && error.code === 'ABORTED') return;
        setLoadError((error && error.message) || '正文加载失败');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [mode, document]);

  const update = (field, value) => setForm(prev => ({ ...prev, [field]: value }));

  const applyPayload = (payload, warnings, fileName) => {
    setForm(prev => ({ ...prev, ...payload, docNo: prev.docNo || payload.docNo || '' }));
    setFormError('');
    setImportError(warnings && warnings.length > 0 ? `${fileName}：${warnings.join('；')}` : '');
  };

  const appendFiles = async fileList => {
    const files = Array.from(fileList || []).filter(isImportableFile);
    if (files.length === 0) {
      setImportError('仅支持 .md / .markdown / .txt 文件');
      return;
    }
    const parsed = [];
    for (const file of files) {
      try {
        const item = await readDocumentFile(file);
        parsed.push({
          key: `${file.name}-${file.lastModified}-${parsed.length}`,
          fileName: item.fileName,
          payload: item.payload,
          warnings: item.warnings,
          status: 'READY',
          message: ''
        });
      } catch {
        setImportError(`${file.name} 读取失败`);
      }
    }
    if (parsed.length === 0) return;
    setQueue(prev => [...parsed, ...prev]);
    if (parsed.length === 1) {
      applyPayload(parsed[0].payload, parsed[0].warnings, parsed[0].fileName);
    } else {
      setImportError('');
    }
  };

  const onDrop = event => {
    event.preventDefault();
    setDragActive(false);
    appendFiles(event.dataTransfer?.files);
  };

  const removeQueueItem = key => setQueue(prev => prev.filter(item => item.key !== key));

  const importAll = async () => {
    const ready = queue.filter(item => item.status === 'READY' || item.status === 'FAILED');
    const invalid = ready.filter(item => !item.payload.docNo.trim() || !item.payload.content.trim());
    const valid = ready.filter(item => item.payload.docNo.trim() && item.payload.content.trim());
    if (invalid.length > 0) {
      setImportError(`有 ${invalid.length} 个文件缺少文档编号或正文，请先「载入表单」补齐后单独保存`);
    }
    if (valid.length === 0) {
      if (invalid.length === 0) setImportError('没有待导入的文件');
      return;
    }
    setImporting(true);
    setImportError('');
    const results = await onBatchImport(valid.map(item => item.payload), (index, status, message) => {
      const targetKey = valid[index].key;
      setQueue(prev => prev.map(item => (
        item.key === targetKey ? { ...item, status, message: message || '' } : item
      )));
    });
    setImporting(false);
    const failedCount = (results || []).filter(item => !item.ok).length;
    if (failedCount === 0) {
      setQueue([]);
      onClose();
    }
  };

  const submit = event => {
    event.preventDefault();
    if (!form.docNo.trim() || !form.title.trim()) {
      setFormError('文档编号与标题必填');
      return;
    }
    if (!form.content.trim()) {
      setFormError('正文不能为空，切片依赖正文内容');
      return;
    }
    setFormError('');
    onSubmit({
      docNo: form.docNo.trim(),
      docType: form.docType,
      title: form.title.trim(),
      content: form.content,
      sourceType: form.sourceType,
      sourcePath: form.sourcePath.trim() || null,
      visibility: form.visibility,
      status: form.status,
      autoIndex: form.autoIndex
    });
  };

  const readyCount = queue.filter(item => item.status === 'READY' || item.status === 'FAILED').length;

  return (
    <div className="rag-drawer-mask" role="presentation">
      <aside className="rag-drawer" role="dialog" aria-modal="true" aria-label={mode === 'edit' ? '编辑知识文档' : '新增知识文档'}>
        <header className="rag-drawer-head">
          <h4>{mode === 'edit' ? `编辑文档 ${document?.docNo || ''}` : '新增知识文档'}</h4>
          <button type="button" className="rag-button small" onClick={onClose} disabled={submitting || importing}>关闭</button>
        </header>
        <form className="rag-drawer-body" onSubmit={submit}>
          {mode === 'create' ? (
            <section className="rag-import">
              <div
                className={`rag-dropzone ${dragActive ? 'active' : ''}`}
                onDragOver={event => { event.preventDefault(); setDragActive(true); }}
                onDragLeave={() => setDragActive(false)}
                onDrop={onDrop}
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  hidden
                  accept=".md,.markdown,.txt,text/markdown,text/plain"
                  onChange={event => {
                    appendFiles(event.target.files);
                    event.target.value = '';
                  }}
                />
                <div className="rag-dropzone-main">
                  拖拽 Markdown 文件到此处，或
                  <button type="button" className="rag-button small" onClick={() => fileInputRef.current?.click()} disabled={importing}>
                    选择文件
                  </button>
                  <button type="button" className="rag-button small" onClick={() => setQueue([])} disabled={importing || queue.length === 0}>
                    清空队列
                  </button>
                </div>
                <span className="rag-dropzone-hint">
                  支持 .md / .markdown / .txt 且可多选；带 front-matter 的语料会自动识别 doc_id、title、doc_type、审核与上架状态（决定公开/内部）
                </span>
              </div>

              {queue.length > 0 ? (
                <div className="rag-import-queue">
                  <div className="rag-import-queue-head">
                    <span>待导入 {queue.length} 个文件</span>
                    <div className="rag-import-queue-actions">
                      <button type="button" className="rag-button small" disabled={importing} onClick={importAll}>
                        {importing ? '导入中…' : `批量导入（${readyCount}）`}
                      </button>
                    </div>
                  </div>
                  <ul>
                    {queue.map(item => (
                      <li key={item.key}>
                        <div className="rag-import-item-main">
                          <strong className="mono">{item.payload.docNo || '未识别编号'}</strong>
                          <span className="rag-import-item-title">{item.payload.title || '未识别标题'}</span>
                          <span className="rag-muted">
                            {item.payload.docType} · {item.payload.content.length} 字 · {item.payload.visibility} · {item.fileName}
                          </span>
                          {item.warnings.length > 0 ? (
                            <span className="rag-import-warn">{item.warnings.join('；')}</span>
                          ) : null}
                          {item.message ? <span className="rag-muted mono">{item.message}</span> : null}
                        </div>
                        <div className="rag-import-item-actions">
                          <span className={`rag-tag rag-tag-${STATUS_TONES[item.status]}`}>{STATUS_LABELS[item.status]}</span>
                          <button type="button" className="rag-link" disabled={importing}
                            onClick={() => applyPayload(item.payload, item.warnings, item.fileName)}>载入表单</button>
                          <button type="button" className="rag-link danger" disabled={importing}
                            onClick={() => removeQueueItem(item.key)}>移除</button>
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}

              {importError ? <div className="rag-inline-error">{importError}</div> : null}
            </section>
          ) : null}

          <div className="rag-form-row">
            <label className="rag-field">
              <span>文档编号 *</span>
              <input type="text" maxLength={64} value={form.docNo} readOnly={mode === 'edit'}
                placeholder="PROD-001" onChange={event => update('docNo', event.target.value)} />
            </label>
            <label className="rag-field">
              <span>文档类型 *</span>
              <select value={form.docType} onChange={event => update('docType', event.target.value)}>
                {DOC_TYPE_OPTIONS.map(option => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
          </div>
          <label className="rag-field">
            <span>标题 *</span>
            <input type="text" maxLength={128} value={form.title}
              placeholder="AK-47 | 红线 · 商品介绍" onChange={event => update('title', event.target.value)} />
          </label>
          <label className="rag-field rag-field-grow">
            <span>正文（Markdown）* {loading ? '· 加载中…' : ''}</span>
            <textarea value={form.content} spellCheck={false} onChange={event => update('content', event.target.value)}
              placeholder={'# 商品介绍\n\n## 1. 基础信息\n\n……'} />
          </label>
          <div className="rag-form-row">
            <label className="rag-field">
              <span>来源类型</span>
              <select value={form.sourceType} onChange={event => update('sourceType', event.target.value)}>
                {SOURCE_TYPES.map(option => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            <label className="rag-field">
              <span>来源路径</span>
              <input type="text" maxLength={255} value={form.sourcePath}
                placeholder="doc/data/RAG/PROD-001_AK-47-红线.md" onChange={event => update('sourcePath', event.target.value)} />
            </label>
          </div>
          <div className="rag-form-row">
            <label className="rag-field">
              <span>可见范围</span>
              <select value={form.visibility} onChange={event => update('visibility', event.target.value)}>
                {VISIBILITY_OPTIONS.map(option => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            <label className="rag-field">
              <span>保存后状态</span>
              <select value={form.status} onChange={event => update('status', event.target.value)}>
                <option value="ENABLED">启用</option>
                <option value="DISABLED">停用</option>
              </select>
            </label>
            <label className="rag-field rag-field-check">
              <span>向量入库</span>
              <label className="rag-checkbox">
                <input type="checkbox" checked={form.autoIndex} onChange={event => update('autoIndex', event.target.checked)} />
                <span>保存后立即切片并向量化</span>
              </label>
            </label>
          </div>
          {loadError ? <div className="rag-inline-error">{loadError}</div> : null}
          {formError ? <div className="rag-inline-error">{formError}</div> : null}
          <p className="rag-form-tip">
            保存会同步完成父子切片；向量化在后台执行，新版本需入库成功后才会启用（编辑已启用文档会在入库成功后自动切换版本）。
          </p>
          <div className="rag-drawer-actions">
            <button type="button" className="rag-button" onClick={onClose} disabled={submitting || importing}>取消</button>
            <button type="submit" className="rag-button primary" disabled={submitting || loading || importing}>
              {submitting ? '保存中…' : mode === 'edit' ? '保存并切片' : '保存当前表单'}
            </button>
          </div>
        </form>
      </aside>
    </div>
  );
};
