import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useApp } from '../../context/AppContext';
import { knowledgeApi } from '../../api/agentApi';
import { showToast } from '../../utils/feedback';
import { RagOverviewCards } from './RagOverviewCards';
import { RagDocumentFilters } from './RagDocumentFilters';
import { RagDocumentList } from './RagDocumentList';
import { RagDocumentEditor } from './RagDocumentEditor';
import { RagChunkTree } from './RagChunkTree';
import { RagRetrievalTester } from './RagRetrievalTester';
import { RagIngestTaskPanel } from './RagIngestTaskPanel';
import { RagConfirmDialog } from './RagConfirmDialog';
import { RAG_EMPTY_FILTERS, RAG_PAGE_SIZE, isIndexingStatus, isTaskActive } from './ragLabels';
import './RagKnowledgeWorkbench.css';

const PANELS = [
  { key: 'chunks', label: '分片预览' },
  { key: 'retrieval', label: '检索测试' },
  { key: 'tasks', label: '向量化任务' }
];

const POLL_INTERVAL_MS = 3000;

/**
 * 知识库管理页面：概览 → 筛选/列表 → 分片预览 / 检索测试 / 入库任务。
 * <p>安全边界在后端与网关，前端仅做入口收敛（仅 ADMIN 渲染本组件）。</p>
 */
export const RagKnowledgeWorkbench = () => {
  const { user } = useApp();
  if (user?.role !== 'ADMIN') {
    return (
      <div className="rag-workbench">
        <div className="rag-denied">知识库管理仅对管理员开放，请使用管理员账号登录。</div>
      </div>
    );
  }
  return <RagConsole />;
};

const RagConsole = () => {
  const [filters, setFilters] = useState(RAG_EMPTY_FILTERS);
  const [records, setRecords] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [listPhase, setListPhase] = useState('IDLE');
  const [listError, setListError] = useState('');

  const [overview, setOverview] = useState(null);
  const [overviewLoading, setOverviewLoading] = useState(true);

  const [panel, setPanel] = useState('chunks');
  const [selectedDoc, setSelectedDoc] = useState(null);
  const [chunkMode, setChunkMode] = useState('PERSISTED');
  const [chunkNodes, setChunkNodes] = useState([]);
  const [chunkMeta, setChunkMeta] = useState({});
  const [chunkPhase, setChunkPhase] = useState('IDLE');
  const [chunkError, setChunkError] = useState('');
  const [busyChunkId, setBusyChunkId] = useState(null);

  const [tasks, setTasks] = useState([]);
  const [taskPage, setTaskPage] = useState(1);
  const [taskTotal, setTaskTotal] = useState(0);
  const [taskHasMore, setTaskHasMore] = useState(false);
  const [taskPhase, setTaskPhase] = useState('IDLE');
  const [busyTaskNo, setBusyTaskNo] = useState(null);

  const [editor, setEditor] = useState({ open: false, mode: 'create', document: null });
  const [submitting, setSubmitting] = useState(false);
  const [confirmState, setConfirmState] = useState(null);
  const [busyKey, setBusyKey] = useState('');

  const listControllerRef = useRef(null);
  const chunkControllerRef = useRef(null);
  const taskControllerRef = useRef(null);

  const notifyError = useCallback((error, fallback) => {
    const message = (error && error.message) || fallback;
    showToast('error', '操作失败', error && error.traceId ? `${message}（traceId ${error.traceId}）` : message);
  }, []);

  const notifySuccess = useCallback((title, message) => {
    showToast('success', title, message);
  }, []);

  const loadOverview = useCallback(() => {
    setOverviewLoading(true);
    return knowledgeApi.overview()
      .then(data => setOverview(data))
      .catch(error => console.warn('知识库概览加载失败', error))
      .finally(() => setOverviewLoading(false));
  }, []);

  const loadDocuments = useCallback((pageToLoad, { silent = false } = {}) => {
    if (listControllerRef.current) listControllerRef.current.abort();
    const controller = new AbortController();
    listControllerRef.current = controller;
    setListPhase(silent ? 'REFRESHING' : 'LOADING');
    return knowledgeApi.documents({ ...filters, page: pageToLoad, pageSize: RAG_PAGE_SIZE }, { signal: controller.signal })
      .then(data => {
        const nextRecords = (data && data.records) || [];
        setRecords(nextRecords);
        setTotal((data && data.total) || 0);
        setHasMore(Boolean(data && data.hasMore));
        setPage((data && data.page) || pageToLoad);
        setListError('');
        setListPhase(nextRecords.length === 0 ? 'EMPTY' : 'SUCCESS');
        return data;
      })
      .catch(error => {
        if (error && error.code === 'ABORTED') return null;
        setListError((error && error.message) || '文档列表加载失败');
        setListPhase('ERROR');
        return null;
      });
  }, [filters]);

  const loadChunks = useCallback((documentId, { silent = false } = {}) => {
    if (!documentId) return Promise.resolve(null);
    if (chunkControllerRef.current) chunkControllerRef.current.abort();
    const controller = new AbortController();
    chunkControllerRef.current = controller;
    if (!silent) setChunkPhase('LOADING');
    return knowledgeApi.chunks(documentId, { signal: controller.signal })
      .then(data => {
        setChunkNodes(data || []);
        setChunkError('');
        setChunkPhase('SUCCESS');
        return data;
      })
      .catch(error => {
        if (error && error.code === 'ABORTED') return null;
        setChunkError((error && error.message) || '分片加载失败');
        setChunkPhase('ERROR');
        return null;
      });
  }, []);

  const loadTasks = useCallback((pageToLoad, { silent = false } = {}) => {
    if (taskControllerRef.current) taskControllerRef.current.abort();
    const controller = new AbortController();
    taskControllerRef.current = controller;
    if (!silent) setTaskPhase('LOADING');
    return knowledgeApi.tasks({ page: pageToLoad, pageSize: RAG_PAGE_SIZE }, { signal: controller.signal })
      .then(data => {
        setTasks((data && data.records) || []);
        setTaskTotal((data && data.total) || 0);
        setTaskHasMore(Boolean(data && data.hasMore));
        setTaskPage((data && data.page) || pageToLoad);
        setTaskPhase('SUCCESS');
        return data;
      })
      .catch(error => {
        if (error && error.code === 'ABORTED') return null;
        setTaskPhase('ERROR');
        return null;
      });
  }, []);

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    loadDocuments(1);
  }, [loadDocuments]);

  useEffect(() => () => {
    [listControllerRef, chunkControllerRef, taskControllerRef].forEach(ref => {
      if (ref.current) ref.current.abort();
    });
  }, []);

  // 有文档入库中或有未结束任务时轮询刷新，保证进度可见
  const shouldPoll = useMemo(
    () => records.some(record => isIndexingStatus(record.indexStatus)) || tasks.some(task => isTaskActive(task.status)),
    [records, tasks]
  );

  useEffect(() => {
    if (!shouldPoll) return undefined;
    const timer = setInterval(() => {
      loadDocuments(page, { silent: true });
      if (panel === 'tasks') loadTasks(taskPage, { silent: true });
      if (panel === 'chunks' && chunkMode === 'PERSISTED' && selectedDoc) loadChunks(selectedDoc.id, { silent: true });
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [shouldPoll, page, panel, taskPage, chunkMode, selectedDoc, loadDocuments, loadTasks, loadChunks]);

  const selectDocument = useCallback(record => {
    setSelectedDoc(record);
    setPanel('chunks');
    setChunkMode('PERSISTED');
    setChunkMeta({
      docNo: record.docNo,
      title: record.title,
      version: record.version,
      charCount: record.charCount,
      parentCount: record.parentChunkCount,
      childCount: record.childChunkCount
    });
    setChunkNodes([]);
    loadChunks(record.id);
  }, [loadChunks]);

  const previewDocument = useCallback(record => {
    setSelectedDoc(record);
    setPanel('chunks');
    setChunkMode('PREVIEW');
    setChunkPhase('LOADING');
    setChunkError('');
    setChunkNodes([]);
    knowledgeApi.previewSplit(record.id)
      .then(data => {
        setChunkNodes((data && data.parents) || []);
        setChunkMeta({
          docNo: data?.docNo,
          title: data?.title,
          version: data?.version,
          charCount: data?.charCount,
          parentCount: data?.parentCount,
          childCount: data?.childCount
        });
        setChunkPhase('SUCCESS');
      })
      .catch(error => {
        setChunkError((error && error.message) || '切片预览失败');
        setChunkPhase('ERROR');
      });
  }, []);

  const afterIndexChange = useCallback(() => {
    loadDocuments(page, { silent: true });
    loadOverview();
    loadTasks(taskPage, { silent: true });
    if (selectedDoc && panel === 'chunks' && chunkMode === 'PERSISTED') loadChunks(selectedDoc.id, { silent: true });
  }, [page, taskPage, selectedDoc, panel, chunkMode, loadDocuments, loadOverview, loadTasks, loadChunks]);

  const runDocumentAction = useCallback(async (record, key, action, successTitle) => {
    setBusyKey(`${record.id}:${key}`);
    try {
      const result = await action();
      notifySuccess(successTitle, typeof result === 'string' ? result : '操作已完成');
      afterIndexChange();
    } catch (error) {
      notifyError(error, '操作失败');
    } finally {
      setBusyKey('');
    }
  }, [afterIndexChange, notifyError, notifySuccess]);

  const requestIndex = useCallback((record, reembed = false) => {
    setConfirmState({
      title: reembed ? '全量重新向量化' : '重建索引',
      message: reembed
        ? '将忽略现有向量状态，对全部子片重新调用 Embedding 并覆盖写入 pgvector。'
        : '将对该文档当前版本的待处理子片执行向量化入库，已完成的分片自动跳过。',
      details: [
        { label: '文档编号', value: record.docNo },
        { label: '版本', value: `v${record.version}` },
        { label: '父/子分片', value: `${record.parentChunkCount ?? 0} / ${record.childChunkCount ?? 0}` }
      ],
      confirmText: '开始处理',
      onConfirm: () => runDocumentAction(
        record,
        reembed ? 'reembed' : 'index',
        () => knowledgeApi[reembed ? 'reEmbed' : 'reindex'](record.id),
        reembed ? '已提交全量重算' : '已提交向量入库'
      )
    });
  }, [runDocumentAction]);

  const toggleStatus = useCallback(record => {
    const enabling = record.status !== 'ENABLED';
    if (!enabling) {
      runDocumentAction(record, 'status', async () => {
        await knowledgeApi.disable(record.id);
        return `${record.docNo} 已停用，检索将不再命中该文档`;
      }, '已停用');
      return;
    }
    setConfirmState({
      title: '启用文档',
      message: '启用后该版本参与检索，同编号的其他版本会被自动停用。请确认向量已入库完成。',
      details: [
        { label: '文档编号', value: record.docNo },
        { label: '索引状态', value: record.indexStatus },
        { label: '可见范围', value: record.visibility }
      ],
      confirmText: '确认启用',
      onConfirm: () => runDocumentAction(record, 'status', async () => {
        await knowledgeApi.enable(record.id);
        return `${record.docNo} 已启用`;
      }, '已启用')
    });
  }, [runDocumentAction]);

  const removeDocument = useCallback(record => {
    setConfirmState({
      title: '物理删除知识文档',
      message: '物理删除不可恢复：将永久删除该版本的文档正文、父子分片、入库任务记录，'
        + '并同步清理 pgvector 中该版本的向量。同编号的其他版本不受影响。',
      details: [
        { label: '文档编号', value: record.docNo },
        { label: '标题', value: record.title },
        { label: '版本', value: `v${record.version}` },
        { label: '分片规模', value: `父片 ${record.parentChunkCount ?? 0} / 子片 ${record.childChunkCount ?? 0}` }
      ],
      confirmText: '确认永久删除',
      tone: 'danger',
      onConfirm: () => runDocumentAction(record, 'delete', async () => {
        await knowledgeApi.remove(record.id);
        setSelectedDoc(prev => (prev && prev.id === record.id ? null : prev));
        return `${record.docNo} v${record.version} 已物理删除`;
      }, '已删除')
    });
  }, [runDocumentAction]);

  const submitEditor = useCallback(async payload => {
    setSubmitting(true);
    try {
      const isEdit = editor.mode === 'edit';
      const result = isEdit
        ? await knowledgeApi.updateDocument(editor.document.id, payload)
        : await knowledgeApi.createDocument(payload);
      const message = result && result.taskNo
        ? `v${result.version} 已切片，向量入库任务 ${result.taskNo} 已提交（失败可在任务面板重试）`
        : `内容未变化，当前版本 v${result?.version ?? '-'}`;
      notifySuccess(isEdit ? '文档已更新' : '文档已创建', message);
      setEditor({ open: false, mode: 'create', document: null });
      afterIndexChange();
    } catch (error) {
      notifyError(error, '保存失败');
    } finally {
      setSubmitting(false);
    }
  }, [editor, notifySuccess, notifyError, afterIndexChange]);

  /** 批量导入：逐篇调用新增接口，保证切片与向量入库逻辑只有一份（顺序执行，避免打满入库线程池）。 */
  const importDocuments = useCallback(async (payloads, onProgress) => {
    const results = [];
    for (let index = 0; index < payloads.length; index += 1) {
      const payload = payloads[index];
      onProgress?.(index, 'RUNNING');
      try {
        const result = await knowledgeApi.createDocument(payload);
        results.push({ ok: true, payload, result });
        onProgress?.(index, 'SUCCESS', result?.taskNo ? `任务 ${result.taskNo}` : `版本未变化 v${result?.version ?? '-'}`);
      } catch (error) {
        results.push({ ok: false, payload, error });
        onProgress?.(index, 'FAILED', (error && error.message) || '导入失败');
      }
    }
    const failed = results.filter(item => !item.ok);
    if (failed.length === 0) {
      notifySuccess('批量导入完成', `${results.length} 篇文档已切片，向量入库任务已提交`);
    } else if (failed.length === results.length) {
      notifyError(failed[0].error, `批量导入失败：${failed.length}/${results.length} 篇`);
    } else {
      notifyError(new Error(`${failed.length}/${results.length} 篇导入失败，成功的文档可在列表继续处理`), '部分导入失败');
    }
    afterIndexChange();
    return results;
  }, [notifySuccess, notifyError, afterIndexChange]);

  const reEmbedChunk = useCallback(async node => {
    setBusyChunkId(node.chunkId);
    try {
      await knowledgeApi.reEmbedChunk(node.chunkId);
      notifySuccess('分片已重新向量化', `${node.chunkNo} 向量已更新`);
      if (selectedDoc) loadChunks(selectedDoc.id, { silent: true });
    } catch (error) {
      notifyError(error, '分片向量化失败');
    } finally {
      setBusyChunkId(null);
    }
  }, [notifySuccess, notifyError, selectedDoc, loadChunks]);

  const retryTask = useCallback(async task => {
    setBusyTaskNo(task.taskNo);
    try {
      const created = await knowledgeApi.retryTask(task.taskNo);
      notifySuccess('已提交重试', `新任务 ${created?.taskNo || '-'} 已排队`);
      afterIndexChange();
    } catch (error) {
      notifyError(error, '重试失败');
    } finally {
      setBusyTaskNo(null);
    }
  }, [notifySuccess, notifyError, afterIndexChange]);

  const closeConfirm = useCallback(() => {
    if (busyKey) return;
    setConfirmState(null);
  }, [busyKey]);

  const confirmAction = useCallback(async () => {
    const action = confirmState && confirmState.onConfirm;
    setConfirmState(null);
    if (action) await action();
  }, [confirmState]);

  return (
    <div className="rag-workbench">
      <div className="rag-workbench-head">
        <div>
          <h3 className="rag-title">知识库管理</h3>
          <p className="rag-subtitle">RAG 语料治理：文档版本、父子分片、向量入库与检索测试（仅管理员可见）</p>
        </div>
        <button type="button" className="rag-button primary" onClick={() => setEditor({ open: true, mode: 'create', document: null })}>
          新增文档
        </button>
      </div>

      <RagOverviewCards overview={overview} loading={overviewLoading} />

      <RagDocumentFilters
        value={filters}
        disabled={listPhase === 'LOADING'}
        onChange={setFilters}
        onReset={() => setFilters(RAG_EMPTY_FILTERS)}
        onRefresh={() => { loadDocuments(page, { silent: true }); loadOverview(); }}
        onCreate={() => setEditor({ open: true, mode: 'create', document: null })}
      />

      {listError ? (
        <div className="rag-inline-error">
          <span>{listError}</span>
          <button type="button" className="rag-button small" onClick={() => loadDocuments(page)}>重试</button>
        </div>
      ) : null}

      <RagDocumentList
        records={records}
        total={total}
        page={page}
        pageSize={RAG_PAGE_SIZE}
        hasMore={hasMore}
        loading={listPhase === 'LOADING'}
        selectedId={selectedDoc ? selectedDoc.id : null}
        busyKey={busyKey}
        onPageChange={nextPage => loadDocuments(nextPage, { silent: true })}
        onSelect={selectDocument}
        onPreview={previewDocument}
        onEdit={record => setEditor({ open: true, mode: 'edit', document: record })}
        onIndex={record => requestIndex(record, false)}
        onReEmbed={record => requestIndex(record, true)}
        onToggleStatus={toggleStatus}
        onDelete={removeDocument}
      />

      <div className="rag-panels">
        <div className="rag-panel-tabs" role="tablist">
          {PANELS.map(item => (
            <button key={item.key} type="button" role="tab" aria-selected={panel === item.key}
              className={`rag-panel-tab ${panel === item.key ? 'active' : ''}`}
              onClick={() => {
                setPanel(item.key);
                if (item.key === 'tasks' && taskPhase === 'IDLE') loadTasks(1);
              }}>{item.label}</button>
          ))}
        </div>

        {panel === 'chunks' ? (
          selectedDoc ? (
            <RagChunkTree
              mode={chunkMode}
              meta={chunkMeta}
              nodes={chunkNodes}
              phase={chunkPhase}
              error={chunkError}
              busyChunkId={busyChunkId}
              onReEmbedChunk={chunkMode === 'PERSISTED' ? reEmbedChunk : undefined}
              onReload={chunkMode === 'PERSISTED' ? () => loadChunks(selectedDoc.id) : undefined}
            />
          ) : (
            <div className="rag-empty">请在上方列表点击「查看」或「切片预览」选择一份文档</div>
          )
        ) : null}

        {panel === 'retrieval' ? <RagRetrievalTester /> : null}

        {panel === 'tasks' ? (
          <RagIngestTaskPanel
            tasks={tasks}
            phase={taskPhase}
            page={taskPage}
            pageSize={RAG_PAGE_SIZE}
            total={taskTotal}
            hasMore={taskHasMore}
            busyTaskNo={busyTaskNo}
            onPageChange={nextPage => loadTasks(nextPage, { silent: true })}
            onRetry={retryTask}
            onRefresh={() => loadTasks(taskPage, { silent: true })}
          />
        ) : null}
      </div>

      {editor.open ? (
        <RagDocumentEditor
          key={`${editor.mode}-${editor.document ? editor.document.id : 'new'}`}
          mode={editor.mode}
          document={editor.document}
          submitting={submitting}
          onClose={() => setEditor({ open: false, mode: 'create', document: null })}
          onSubmit={submitEditor}
          onBatchImport={importDocuments}
        />
      ) : null}

      <RagConfirmDialog
        open={Boolean(confirmState)}
        title={confirmState ? confirmState.title : ''}
        message={confirmState ? confirmState.message : ''}
        details={confirmState ? confirmState.details : []}
        confirmText={confirmState ? confirmState.confirmText : '确认'}
        tone={confirmState && confirmState.tone ? confirmState.tone : 'primary'}
        busy={Boolean(busyKey)}
        onCancel={closeConfirm}
        onConfirm={confirmAction}
      />
    </div>
  );
};
