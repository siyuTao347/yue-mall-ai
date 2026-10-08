import { useState } from 'react';
import { RiskStatusTag } from './RiskStatusTag';
import { labelOf, toneOf } from './RiskDictionary';

const money = value => (value === null || value === undefined ? '-' : `¥${Number(value).toFixed(2)}`);
const formatTime = value => (value ? String(value).replace('T', ' ').slice(0, 19) : '-');

const actualValuesText = values => {
  if (!values || typeof values !== 'object') return '-';
  return Object.entries(values).map(([key, value]) => `${key}=${value}`).join('，') || '-';
};

const thresholdText = values => {
  if (!values || typeof values !== 'object') return '-';
  return values.threshold ?? values.limit ?? '-';
};

export const RiskEvidencePanel = ({ detail }) => {
  const [rawOpen, setRawOpen] = useState(false);
  if (!detail) return null;
  const { decision, event, relations, evidenceCompleteness: completeness } = detail;

  return (
    <div className="risk-evidence">
      <section className="risk-evidence-group">
        <h4>命中规则</h4>
        {decision && decision.hitRules && decision.hitRules.length > 0 ? (
          <table className="risk-mini-table">
            <thead>
              <tr><th>规则编码</th><th>规则名</th><th>命中值</th><th>阈值</th><th>风险分</th></tr>
            </thead>
            <tbody>
              {decision.hitRules.map((rule, index) => (
                <tr key={`${rule.ruleCode || 'rule'}-${index}`}>
                  <td>{rule.ruleCode || '-'}</td>
                  <td>{rule.ruleName || '-'}</td>
                  <td className="risk-ellipsis" title={actualValuesText(rule.actualValues)}>
                    {actualValuesText(rule.actualValues)}
                  </td>
                  <td>{thresholdText(rule.actualValues)}</td>
                  <td>{rule.riskScore ?? '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p className="risk-muted">暂无该项证据</p>}
        {decision && decision.missingMetrics && decision.missingMetrics.length > 0 ? (
          <p className="risk-warn">缺失指标：{decision.missingMetrics.join('、')}</p>
        ) : null}
      </section>

      <section className="risk-evidence-group">
        <h4>事件上下文</h4>
        {event ? (
          <>
            <dl className="risk-kv">
              <div><dt>事件号</dt><dd>{event.eventNo || '-'}</dd></div>
              <div><dt>场景</dt><dd>{event.sceneText || event.scene || '-'}</dd></div>
              <div><dt>事件类型</dt><dd>{event.eventType || '-'}</dd></div>
              <div><dt>业务号</dt><dd>{event.bizNo || '-'}</dd></div>
              <div><dt>金额</dt><dd>{money(event.amount)}</dd></div>
              <div><dt>发生时间</dt><dd>{formatTime(event.occurredTime)}</dd></div>
              <div><dt>IP</dt><dd>{event.ipHashMasked || '-'}</dd></div>
              <div><dt>设备</dt><dd>{event.deviceHashMasked || '-'}</dd></div>
            </dl>
            {event.context && Object.keys(event.context).length > 0 ? (
              <ul className="risk-context-list">
                {Object.entries(event.context).map(([key, value]) => (
                  <li key={key}><span>{key}</span><b>{String(value)}</b></li>
                ))}
              </ul>
            ) : <p className="risk-muted">暂无上下文明细</p>}
          </>
        ) : <p className="risk-muted">暂无该项证据</p>}
      </section>

      <section className="risk-evidence-group">
        <h4>身份关系</h4>
        {relations && relations.length > 0 ? (
          <>
            <ul className="risk-relation-list">
              {relations.map(node => (
                <li key={`${node.nodeType}-${node.nodeId}-${node.relationType}`}>
                  <span className="risk-relation-type">{node.relationType || node.nodeType}</span>
                  <span className="risk-relation-node">{node.nodeType} {node.nodeId}</span>
                  {node.nodeHash ? <span className="risk-muted">{node.nodeHash}</span> : null}
                  <span className="risk-muted">权重 {node.weight ?? '-'}</span>
                </li>
              ))}
            </ul>
            <p className="risk-muted">为保证查询性能，仅展示主要关联。</p>
          </>
        ) : <p className="risk-muted">暂无该项证据</p>}
      </section>

      <section className="risk-evidence-group">
        <button type="button" className="risk-collapse" onClick={() => setRawOpen(open => !open)}>
          {rawOpen ? '收起原始证据' : '展开原始证据'}
        </button>
        {rawOpen ? (
          <div className="risk-raw">
            <p>证据完整度：
              {completeness ? Object.entries(completeness)
                .map(([key, value]) => `${key}:${value ? '有' : '无'}`).join('，') : '-'}
            </p>
            <p>决策：{decision ? `${decision.actionText || decision.action} / ${decision.riskLevelText}` : '无'}</p>
            <pre>{JSON.stringify(event ? event.context : {}, null, 2)}</pre>
          </div>
        ) : null}
      </section>
    </div>
  );
};

export const RiskCommandTimeline = ({ notes, dictionary }) => (
  <ol className="risk-timeline">
    {(notes || []).map(note => (
      <li key={note.id}>
        <div className="risk-timeline-head">
          <RiskStatusTag text={note.noteTypeText || labelOf(dictionary, 'noteType', note.noteType)}
            tone={note.tone || toneOf(dictionary, 'noteType', note.noteType)} />
          <span className="risk-timeline-time">{formatTime(note.createdTime)}</span>
        </div>
        <div className="risk-timeline-body">
          <p>{note.content}</p>
          <p className="risk-muted">
            {note.operatorName || '系统'}
            {note.beforeStatus && note.afterStatus && note.beforeStatus !== note.afterStatus
              ? ` · ${note.beforeStatus} → ${note.afterStatus}` : ''}
          </p>
          {note.evidence && Object.keys(note.evidence).length > 0 ? (
            <p className="risk-timeline-evidence">
              {Object.entries(note.evidence).map(([key, value]) => (
                <span key={key}>{key}: {String(value)}</span>
              ))}
            </p>
          ) : null}
        </div>
      </li>
    ))}
    {(!notes || notes.length === 0) ? <li className="risk-muted">暂无操作历史</li> : null}
  </ol>
);
