import { useEffect, useMemo, useState } from 'react';
import { embeddingStatusLabel, embeddingStatusTone } from './ragLabels';

const flatten = nodes => {
  const list = [];
  (nodes || []).forEach(parent => {
    list.push(parent);
    (parent.children || []).forEach(child => list.push(child));
  });
  return list;
};

/** 父子分片树：左侧父片 / 子片导航，右侧分片详情与向量状态。 */
export const RagChunkTree = ({
  mode = 'PERSISTED', meta = {}, nodes = [], phase = 'IDLE', error = '',
  busyChunkId, onReEmbedChunk, onReload
}) => {
  const flat = useMemo(() => flatten(nodes), [nodes]);
  const [selectedChunkNo, setSelectedChunkNo] = useState(null);

  // 切换文档时定位到第一个父片；轮询刷新时保留当前选中分片
  useEffect(() => {
    setSelectedChunkNo(prev => {
      if (prev && flat.some(node => node.chunkNo === prev)) return prev;
      return flat.length > 0 ? flat[0].chunkNo : null;
    });
  }, [flat]);

  const selected = flat.find(node => node.chunkNo === selectedChunkNo) || null;
  const preview = mode === 'PREVIEW';
  const parents = nodes || [];

  return (
    <div className="rag-chunks">
      <div className="rag-chunks-head">
        <div>
          <strong>{meta.docNo || '-'}</strong>
          <span className="rag-muted">{meta.title || ''}</span>
        </div>
        <div className="rag-chunks-meta">
          <span>版本 v{meta.version ?? '-'}</span>
          <span>字符 {meta.charCount ?? '-'}</span>
          <span>父片 {meta.parentCount ?? parents.length}</span>
          <span>子片 {meta.childCount ?? flat.filter(node => node.chunkLevel === 2).length}</span>
          {preview ? <span className="rag-tag rag-tag-info">切片预览（未落库）</span> : null}
          {onReload ? (
            <button type="button" className="rag-button small" onClick={onReload} disabled={phase === 'LOADING'}>
              {phase === 'LOADING' ? '加载中…' : '刷新分片'}
            </button>
          ) : null}
        </div>
      </div>

      {error ? <div className="rag-inline-error">{error}</div> : null}

      <div className="rag-chunks-body">
        <div className="rag-chunks-tree">
          {phase === 'LOADING' && parents.length === 0 ? <div className="rag-table-state">加载中…</div> : null}
          {phase !== 'LOADING' && parents.length === 0 ? (
            <div className="rag-table-state">暂无分片，请先保存文档完成切片</div>
          ) : null}
          {parents.map(parent => (
            <div key={parent.chunkNo} className="rag-chunk-group">
              <button type="button"
                className={`rag-chunk-node parent ${selectedChunkNo === parent.chunkNo ? 'active' : ''}`}
                onClick={() => setSelectedChunkNo(parent.chunkNo)}>
                <span className="rag-chunk-no mono">{parent.chunkNo}</span>
                <span className="rag-chunk-path" title={parent.titlePath || ''}>{parent.titlePath || '(无小节标题)'}</span>
                <span className="rag-chunk-count">{(parent.children || []).length} 子片</span>
              </button>
              {(parent.children || []).map(child => (
                <button key={child.chunkNo} type="button"
                  className={`rag-chunk-node child ${selectedChunkNo === child.chunkNo ? 'active' : ''}`}
                  onClick={() => setSelectedChunkNo(child.chunkNo)}>
                  <span className="rag-chunk-no mono">{child.chunkNo}</span>
                  <span className={`rag-tag rag-tag-${embeddingStatusTone(child.embeddingStatus)}`}>
                    {embeddingStatusLabel(child.embeddingStatus)}
                  </span>
                  <span className="rag-chunk-count">{child.charCount ?? 0} 字</span>
                </button>
              ))}
            </div>
          ))}
        </div>

        <div className="rag-chunk-detail">
          {!selected ? (
            <div className="rag-table-state">选择左侧分片查看内容</div>
          ) : (
            <>
              <div className="rag-chunk-detail-head">
                <div>
                  <span className="rag-tag rag-tag-default">{selected.chunkLevel === 2 ? '子片' : '父片'}</span>
                  <strong className="mono">{selected.chunkNo}</strong>
                  {selected.splitForced ? <span className="rag-tag rag-tag-warning">强制切分</span> : null}
                </div>
                {!preview && selected.chunkLevel === 2 && selected.chunkId ? (
                  <button type="button" className="rag-button small"
                    disabled={busyChunkId === selected.chunkId || onReEmbedChunk === undefined}
                    onClick={() => onReEmbedChunk(selected)}>
                    {busyChunkId === selected.chunkId ? '处理中…' : '重新向量化'}
                  </button>
                ) : null}
              </div>
              <dl className="rag-chunk-meta">
                <div><dt>标题路径</dt><dd>{selected.titlePath || '-'}</dd></div>
                <div><dt>字符区间</dt><dd className="mono">{selected.charStart ?? '-'} ~ {selected.charEnd ?? '-'}</dd></div>
                <div><dt>向量状态</dt><dd>{embeddingStatusLabel(selected.embeddingStatus)}</dd></div>
                <div><dt>模型 / 维度</dt><dd className="mono">{selected.embeddingModel || '-'} / {selected.embeddingDim ?? '-'}</dd></div>
              </dl>
              <pre className="rag-chunk-content">{selected.content || '（该分片未返回正文，父片正文可点击子片查看）'}</pre>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
