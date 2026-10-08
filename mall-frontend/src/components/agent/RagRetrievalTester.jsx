import { useState } from 'react';
import { knowledgeApi } from '../../api/agentApi';
import { DOC_TYPE_OPTIONS, embeddingStatusLabel, truncate } from './ragLabels';

const MODES = [
  { value: 'VECTOR', label: '向量检索（已支持）' },
  { value: 'KEYWORD', label: '关键词检索（后续增量）', disabled: true },
  { value: 'HYBRID', label: '混合检索 RRF（后续增量）', disabled: true }
];

/** 检索测试：验证向量入库效果（子片召回 → 父片回溯 → 上下文）。 */
export const RagRetrievalTester = () => {
  const [query, setQuery] = useState('');
  const [mode, setMode] = useState('VECTOR');
  const [docType, setDocType] = useState('');
  const [topK, setTopK] = useState(20);
  const [parentTopN, setParentTopN] = useState(5);
  const [phase, setPhase] = useState('IDLE');
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);

  const submit = async event => {
    event.preventDefault();
    if (!query.trim()) {
      setError('请输入检索问题');
      return;
    }
    setPhase('LOADING');
    setError('');
    try {
      const data = await knowledgeApi.retrievalTest({
        query: query.trim(),
        mode,
        docTypes: docType ? [docType] : [],
        topK: Number(topK) || 20,
        parentTopN: Number(parentTopN) || 5
      });
      setResult(data);
      setPhase('SUCCESS');
    } catch (e) {
      if (e && e.code === 'ABORTED') return;
      setError((e && e.message) || '检索测试失败');
      setResult(null);
      setPhase('ERROR');
    }
  };

  return (
    <div className="rag-retrieval">
      <form className="rag-filters" onSubmit={submit}>
        <label className="rag-field rag-field-grow">
          <span>检索问题</span>
          <input type="text" maxLength={200} value={query} disabled={phase === 'LOADING'}
            placeholder="例如：AK-47 红线的价格与交付方式？"
            onChange={event => setQuery(event.target.value)} />
        </label>
        <label className="rag-field">
          <span>检索模式</span>
          <select value={mode} onChange={event => setMode(event.target.value)}>
            {MODES.map(option => (
              <option key={option.value} value={option.value} disabled={option.disabled}>{option.label}</option>
            ))}
          </select>
        </label>
        <label className="rag-field">
          <span>文档类型</span>
          <select value={docType} onChange={event => setDocType(event.target.value)}>
            <option value="">全部</option>
            {DOC_TYPE_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <label className="rag-field small">
          <span>子片 TopK</span>
          <input type="number" min="1" max="100" value={topK} onChange={event => setTopK(event.target.value)} />
        </label>
        <label className="rag-field small">
          <span>父片 TopN</span>
          <input type="number" min="1" max="20" value={parentTopN} onChange={event => setParentTopN(event.target.value)} />
        </label>
        <div className="rag-filter-actions">
          <button type="submit" className="rag-button primary" disabled={phase === 'LOADING'}>
            {phase === 'LOADING' ? '检索中…' : '执行检索'}
          </button>
        </div>
      </form>

      {error ? <div className="rag-inline-error">{error}</div> : null}

      {result ? (
        <div className="rag-retrieval-result">
          <div className="rag-retrieval-summary">
            <span>请求模式 <strong>{result.requestedMode}</strong></span>
            <span>实际模式 <strong>{result.actualMode}</strong></span>
            <span>耗时 <strong>{result.latencyMs} ms</strong></span>
            <span>召回子片 <strong>{result.childHits?.length ?? 0}</strong></span>
            <span>回溯父片 <strong>{result.contexts?.length ?? 0}</strong></span>
            {result.actualMode !== 'VECTOR' ? <span className="rag-tag rag-tag-warning">已降级</span> : null}
          </div>

          <h5 className="rag-panel-title">子片召回（按相似度倒序）</h5>
          {(result.childHits || []).length === 0 ? (
            <div className="rag-table-state">没有命中任何子片，请确认文档已完成向量入库</div>
          ) : (
            <table className="rag-table">
              <thead>
                <tr><th>#</th><th>子片编号</th><th>文档</th><th>类型</th><th>相似度</th><th>内容摘要</th></tr>
              </thead>
              <tbody>
                {(result.childHits || []).map((hit, index) => (
                  <tr key={hit.chunkId}>
                    <td>{index + 1}</td>
                    <td className="mono">{hit.chunkNo}</td>
                    <td className="mono">{hit.docNo} v{hit.docVersion}</td>
                    <td>{hit.docType}</td>
                    <td className="mono">{Number(hit.score ?? 0).toFixed(4)}</td>
                    <td>
                      <span className="rag-chunk-path">{hit.titlePath || '-'}</span>
                      <div className="rag-cell-preview">{truncate(hit.content, 120)}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h5 className="rag-panel-title">组装上下文（父片）</h5>
          {(result.contexts || []).length === 0 ? (
            <div className="rag-table-state">无回溯父片</div>
          ) : (
            (result.contexts || []).map(context => (
              <div key={context.chunkId} className="rag-context">
                <div className="rag-context-head">
                  <span className="mono">{context.chunkNo}</span>
                  <span>{context.titlePath || '-'}</span>
                  <span className="rag-muted">{embeddingStatusLabel(context.embeddingStatus)} · {context.charCount ?? 0} 字</span>
                </div>
                <pre className="rag-chunk-content">{context.content}</pre>
              </div>
            ))
          )}
        </div>
      ) : null}
    </div>
  );
};
