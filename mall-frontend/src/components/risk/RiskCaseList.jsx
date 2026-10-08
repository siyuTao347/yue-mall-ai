import { Pagination } from '../ui/Pagination';
import { RiskStatusTag } from './RiskStatusTag';
import { labelOf, toneOf } from './RiskDictionary';

const formatTime = value => (value ? String(value).replace('T', ' ').slice(0, 19) : '-');

const summarizeTarget = item => {
  if (item.bizNo) {
    return `${item.bizType || '业务'} ${item.bizNo}`;
  }
  if (item.subjectId) {
    return `${item.subjectType || '主体'} ${item.subjectId}`;
  }
  return '-';
};

const actionsOf = item => {
  const actions = [{ key: 'detail', label: '详情' }];
  if (item.status === 'OPEN') {
    actions.push({ key: 'assign', label: '认领', tone: 'primary' });
  }
  if (item.status === 'PROCESSING') {
    actions.push({ key: 'resolve', label: '处置', tone: 'primary' });
  }
  if (['FAILED', 'COMMAND_FAILED', 'DEAD_LETTER'].includes(item.commandStatus)) {
    actions.push({ key: 'resend', label: '重发', tone: 'danger' });
  }
  if (item.status === 'RESOLVED' && item.commandStatus === 'SUCCESS') {
    actions.push({ key: 'close', label: '关闭', tone: 'primary' });
  }
  if (item.status === 'CLOSED') {
    actions.push({ key: 'reopen', label: '重开', tone: 'danger' });
  }
  return actions;
};

export const RiskCaseList = ({
  records, phase, error, traceId, page, pageSize, total, hasMore,
  dictionary, selectedCaseNo, busyKey, onSelect, onQuickAction, onPageChange, onRetry
}) => {
  const loading = phase === 'LOADING';
  const refreshing = phase === 'REFRESHING';

  if (phase === 'ERROR' && records.length === 0) {
    return (
      <div className="risk-state risk-state-error">
        <p>{error || '案件列表加载失败'}</p>
        {traceId ? <p className="risk-trace">traceId: {traceId}</p> : null}
        <button type="button" className="risk-button" onClick={onRetry}>重试</button>
      </div>
    );
  }

  if (phase === 'EMPTY' && records.length === 0) {
    return (
      <div className="risk-state risk-state-empty">
        <p>当前筛选条件下暂无案件</p>
        <button type="button" className="risk-button" onClick={onRetry}>重置筛选</button>
      </div>
    );
  }

  return (
    <div className={`risk-list ${refreshing ? 'is-refreshing' : ''}`}>
      <div className="risk-table-wrap">
        <table className="risk-table">
          <thead>
            <tr>
              <th>案件号</th>
              <th>风险</th>
              <th>场景</th>
              <th>业务对象</th>
              <th>状态</th>
              <th>命令</th>
              <th>处理人</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              Array.from({ length: 5 }).map((_, index) => (
                <tr key={`skeleton-${index}`} className="risk-skeleton-row">
                  {Array.from({ length: 9 }).map((__, cell) => (
                    <td key={cell}><span className="risk-skeleton" /></td>
                  ))}
                </tr>
              ))
            ) : records.map(item => (
              <tr key={item.caseNo}
                className={item.caseNo === selectedCaseNo ? 'is-selected' : ''}
                onClick={() => onSelect(item.caseNo)}>
                <td>
                  <button type="button" className="risk-case-no"
                    onClick={event => {
                      event.stopPropagation();
                      onSelect(item.caseNo);
                    }}>{item.caseNo}</button>
                </td>
                <td>
                  <RiskStatusTag text={`${item.riskLevelText || ''} ${item.riskScore ?? ''}`.trim()}
                    tone={toneOf(dictionary, 'riskLevel', item.riskLevel)} />
                </td>
                <td>{item.sceneText || item.scene}</td>
                <td className="risk-target">{summarizeTarget(item)}</td>
                <td>
                  <RiskStatusTag text={item.statusText || labelOf(dictionary, 'caseStatus', item.status)}
                    tone={toneOf(dictionary, 'caseStatus', item.status)} />
                </td>
                <td>
                  {item.commandStatus && item.commandStatus !== 'NONE' ? (
                    <RiskStatusTag text={item.commandStatusText
                      || labelOf(dictionary, 'commandStatus', item.commandStatus)}
                      tone={item.commandTone || toneOf(dictionary, 'commandStatus', item.commandStatus)} />
                  ) : <span className="risk-muted">-</span>}
                </td>
                <td>{item.assignedTo || <span className="risk-muted">未认领</span>}</td>
                <td className="risk-time">{formatTime(item.updatedTime)}</td>
                <td>
                  <div className="risk-row-actions" onClick={event => event.stopPropagation()}>
                    {actionsOf(item).map(action => (
                      <button key={action.key} type="button"
                        className={`risk-button small ${action.tone || ''}`}
                        disabled={busyKey === `${action.key}-${item.caseNo}`}
                        onClick={() => (action.key === 'detail'
                          ? onSelect(item.caseNo)
                          : onQuickAction(action.key, item))}>
                        {action.label}
                      </button>
                    ))}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {error && records.length > 0 ? (
        <div className="risk-inline-error">
          <span>{error}</span>
          {traceId ? <span className="risk-trace">traceId: {traceId}</span> : null}
          <button type="button" className="risk-button small" onClick={onRetry}>重试</button>
        </div>
      ) : null}
      <Pagination page={page} pageSize={pageSize} total={total} hasMore={hasMore}
        onPageChange={onPageChange} />
    </div>
  );
};
