import { Pagination } from '../ui/Pagination';
import {
  docStatusLabel, docTypeLabel, formatTime, indexStatusLabel, indexStatusTone, isIndexingStatus
} from './ragLabels';

/** 文档列表：行内提供查看 / 预览 / 编辑 / 重建 / 启停 / 删除入口。 */
export const RagDocumentList = ({
  records = [], total = 0, page = 1, pageSize = 10, hasMore = false, loading = false,
  selectedId, busyKey, onPageChange, onSelect, onPreview, onEdit, onIndex, onReEmbed, onToggleStatus, onDelete
}) => (
  <div className="rag-list">
    <table className="rag-table">
      <thead>
        <tr>
          <th>文档编号</th>
          <th>标题</th>
          <th>类型</th>
          <th>版本</th>
          <th>分片(父/子)</th>
          <th>索引状态</th>
          <th>状态</th>
          <th>更新时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        {loading && records.length === 0 ? (
          <tr><td colSpan={9} className="rag-table-state">加载中…</td></tr>
        ) : null}
        {!loading && records.length === 0 ? (
          <tr><td colSpan={9} className="rag-table-state">暂无知识文档，点击「新增文档」导入语料</td></tr>
        ) : null}
        {records.map(record => {
          const indexing = isIndexingStatus(record.indexStatus);
          const enabled = record.status === 'ENABLED';
          const busy = key => busyKey === `${record.id}:${key}`;
          return (
            <tr key={record.id} className={selectedId === record.id ? 'selected' : ''}>
              <td className="mono">{record.docNo}</td>
              <td className="rag-cell-title" title={record.title}>{record.title}</td>
              <td>{docTypeLabel(record.docType)}</td>
              <td>v{record.version}</td>
              <td>{record.parentChunkCount ?? 0} / {record.childChunkCount ?? 0}</td>
              <td>
                <span className={`rag-tag rag-tag-${indexStatusTone(record.indexStatus)}`}>
                  {indexStatusLabel(record.indexStatus)}
                </span>
                {indexing ? <span className="rag-inline-loading">索引中…</span> : null}
              </td>
              <td>
                <span className={`rag-tag rag-tag-${enabled ? 'success' : 'default'}`}>
                  {docStatusLabel(record.status)}
                </span>
              </td>
              <td className="mono">{formatTime(record.updatedTime)}</td>
              <td className="rag-cell-actions">
                <button type="button" className="rag-link" onClick={() => onSelect(record)}>查看</button>
                <button type="button" className="rag-link" onClick={() => onPreview(record)}>切片预览</button>
                <button type="button" className="rag-link" disabled={indexing || busy('edit')} onClick={() => onEdit(record)}>编辑</button>
                <button type="button" className="rag-link" disabled={indexing || busy('index')} onClick={() => onIndex(record)}>重建索引</button>
                <button type="button" className="rag-link" disabled={indexing || busy('reembed')} onClick={() => onReEmbed(record)}>重新向量化</button>
                <button type="button" className="rag-link" disabled={indexing || busy('status')} onClick={() => onToggleStatus(record)}>
                  {enabled ? '停用' : '启用'}
                </button>
                <button type="button" className="rag-link danger" disabled={indexing || busy('delete')} onClick={() => onDelete(record)}>删除</button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
    <Pagination page={page} pageSize={pageSize} total={total} hasMore={hasMore} onPageChange={onPageChange} />
  </div>
);
