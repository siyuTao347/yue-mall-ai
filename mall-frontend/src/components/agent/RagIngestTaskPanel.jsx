import { Pagination } from '../ui/Pagination';
import { formatTime, taskProgress, taskStatusLabel, taskStatusTone } from './ragLabels';

const TASK_TYPE_LABELS = { EMBED: '增量入库', REBUILD: '全量重建' };

/** 向量化任务面板：进度、失败原因与重试。 */
export const RagIngestTaskPanel = ({
  tasks = [], phase = 'IDLE', page = 1, pageSize = 10, total = 0, hasMore = false,
  busyTaskNo, onPageChange, onRetry, onRefresh
}) => (
  <div className="rag-tasks">
    <div className="rag-tasks-head">
      <h5 className="rag-panel-title">向量化任务</h5>
      <button type="button" className="rag-button small" onClick={onRefresh} disabled={phase === 'LOADING'}>
        {phase === 'LOADING' ? '加载中…' : '刷新'}
      </button>
    </div>
    <table className="rag-table">
      <thead>
        <tr>
          <th>任务编号</th><th>文档</th><th>版本</th><th>类型</th><th>状态</th>
          <th>进度</th><th>失败分片</th><th>创建时间</th><th>失败原因</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        {phase === 'LOADING' && tasks.length === 0 ? (
          <tr><td colSpan={10} className="rag-table-state">加载中…</td></tr>
        ) : null}
        {phase !== 'LOADING' && tasks.length === 0 ? (
          <tr><td colSpan={10} className="rag-table-state">暂无入库任务</td></tr>
        ) : null}
        {tasks.map(task => {
          const percent = taskProgress(task);
          const retryable = task.status === 'FAILED' || task.status === 'PARTIAL';
          return (
            <tr key={task.taskNo}>
              <td className="mono">{task.taskNo}</td>
              <td className="mono">{task.docNo}</td>
              <td>v{task.docVersion}</td>
              <td>{TASK_TYPE_LABELS[task.taskType] || task.taskType}</td>
              <td>
                <span className={`rag-tag rag-tag-${taskStatusTone(task.status)}`}>{taskStatusLabel(task.status)}</span>
              </td>
              <td>
                <div className="rag-progress" title={`${task.processedChunks ?? 0}/${task.totalChunks ?? 0}`}>
                  <div className={`rag-progress-bar tone-${taskStatusTone(task.status)}`} style={{ width: `${percent}%` }} />
                </div>
                <span className="rag-muted mono">{task.processedChunks ?? 0}/{task.totalChunks ?? 0}</span>
              </td>
              <td>{task.failedChunks ?? 0}</td>
              <td className="mono">{formatTime(task.createdTime)}</td>
              <td className="rag-cell-preview" title={task.lastError || ''}>{task.lastError || '-'}</td>
              <td>
                <button type="button" className="rag-link" disabled={!retryable || busyTaskNo === task.taskNo}
                  onClick={() => onRetry(task)}>
                  {busyTaskNo === task.taskNo ? '提交中…' : '重试'}
                </button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
    <Pagination page={page} pageSize={pageSize} total={total} hasMore={hasMore} onPageChange={onPageChange} />
  </div>
);
