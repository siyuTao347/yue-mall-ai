import { useCallback, useEffect, useRef, useState } from 'react';
import { riskApi } from '../../api/riskApi';
import { RiskStatusTag } from './RiskStatusTag';
import { RiskEvidencePanel, RiskCommandTimeline } from './RiskEvidencePanel';
import { RiskOperationDialog } from './RiskOperationDialog';
import { commandPolicyOf, toneOf } from './RiskDictionary';

const formatTime = value => (value ? String(value).replace('T', ' ').slice(0, 19) : '-');

const POLLING_STATUSES = ['PENDING_SEND', 'SENT'];

export const RiskCaseDetail = ({
  caseNo, dictionary, requestedOperation, onOperationConsumed, onChanged, onNotify
}) => {
  const [phase, setPhase] = useState('IDLE');
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState('');
  const [traceId, setTraceId] = useState(null);
  const [dialogOperation, setDialogOperation] = useState(null);
  const [dialogError, setDialogError] = useState('');
  const [busyKey, setBusyKey] = useState('');
  const controllerRef = useRef(null);
  const pollStartRef = useRef(0);
  const pollFailuresRef = useRef(0);
  const policy = commandPolicyOf(dictionary);

  const load = useCallback(({ silent = false } = {}) => {
    if (!caseNo) return Promise.resolve(null);
    if (controllerRef.current) {
      controllerRef.current.abort();
    }
    const controller = new AbortController();
    controllerRef.current = controller;
    setPhase(current => (silent && current === 'SUCCESS' ? 'REFRESHING' : 'LOADING'));
    return riskApi.detail(caseNo, { signal: controller.signal })
      .then(data => {
        setDetail(data);
        setError('');
        setTraceId(null);
        setPhase('SUCCESS');
        return data;
      })
      .catch(e => {
        if (e && e.code === 'ABORTED') return null;
        setError((e && e.message) || '案件详情加载失败');
        setTraceId(e && e.traceId);
        setPhase('ERROR');
        return null;
      });
  }, [caseNo]);

  useEffect(() => {
    setDetail(null);
    setDialogOperation(null);
    setPhase('IDLE');
    pollStartRef.current = 0;
    pollFailuresRef.current = 0;
    load();
    return () => {
      if (controllerRef.current) {
        controllerRef.current.abort();
      }
    };
  }, [load]);

  useEffect(() => {
    if (requestedOperation && detail && detail.riskCase
        && detail.riskCase.caseNo === caseNo) {
      setDialogOperation(requestedOperation);
      setDialogError('');
      onOperationConsumed?.();
    }
  }, [requestedOperation, detail, caseNo, onOperationConsumed]);

  const commandStatus = detail && detail.command ? detail.command.status : null;

  useEffect(() => {
    if (!caseNo || dialogOperation || !POLLING_STATUSES.includes(commandStatus)) {
      return undefined;
    }
    if (!pollStartRef.current) {
      pollStartRef.current = Date.now();
    }
    const interval = Math.max(2, policy.pollIntervalSeconds || 5) * 1000;
    const maxDuration = Math.max(30, policy.pollMaxDurationSeconds || 300) * 1000;
    const timer = setInterval(() => {
      if (document.hidden) return;
      if (Date.now() - pollStartRef.current > maxDuration) {
        clearInterval(timer);
        return;
      }
      riskApi.detail(caseNo)
        .then(data => {
          pollFailuresRef.current = 0;
          setDetail(data);
          setError('');
        })
        .catch(() => {
          pollFailuresRef.current += 1;
          if (pollFailuresRef.current >= 2) {
            clearInterval(timer);
            setError('命令状态轮询失败，请手动刷新');
          }
        });
    }, interval);
    return () => clearInterval(timer);
  }, [caseNo, commandStatus, dialogOperation, policy.pollIntervalSeconds, policy.pollMaxDurationSeconds]);

  const submitOperation = useCallback(payload => {
    const commandNo = detail && detail.command ? detail.command.commandNo : null;
    const commandScoped = ['resend', 'manualComplete'].includes(dialogOperation) && commandNo;
    const key = `${dialogOperation}-${caseNo}${commandScoped ? `-${commandNo}` : ''}`;
    setBusyKey(key);
    setDialogError('');
    const action = operationRequest(dialogOperation, caseNo, detail, payload);
    return action
      .then(result => {
        onNotify?.('success', '操作成功', '案件状态已更新');
        return result;
      })
      .then(() => {
        setDialogOperation(null);
        return load({ silent: true });
      })
      .then(() => onChanged?.())
      .catch(e => {
        const message = (e && e.message) || '操作失败';
        if (e && e.code === 'RISK_CASE_STATE_CHANGED') {
          setDialogError('案件状态已变化，已为你刷新最新数据，请确认后重试');
          load({ silent: true }).then(() => onChanged?.());
        } else {
          setDialogError(message);
        }
      })
      .finally(() => setBusyKey(''));
  }, [caseNo, detail, dialogOperation, load, onChanged, onNotify]);

  if (!caseNo) {
    return (
      <aside className="risk-detail risk-detail-placeholder">
        <p>选择左侧案件查看决策、证据与命令状态</p>
      </aside>
    );
  }

  if (phase === 'LOADING' && !detail) {
    return (
      <aside className="risk-detail">
        <div className="risk-skeleton risk-skeleton-block" />
        <div className="risk-skeleton risk-skeleton-block" />
        <div className="risk-skeleton risk-skeleton-block" />
      </aside>
    );
  }

  if (phase === 'ERROR' && !detail) {
    return (
      <aside className="risk-detail risk-state risk-state-error">
        <p>{error}</p>
        {traceId ? <p className="risk-trace">traceId: {traceId}</p> : null}
        <button type="button" className="risk-button" onClick={() => load()}>重试</button>
      </aside>
    );
  }

  if (!detail) {
    return <aside className="risk-detail risk-detail-placeholder"><p>暂无案件详情</p></aside>;
  }

  const riskCase = detail.riskCase;
  const command = detail.command;
  const canAssign = riskCase.status === 'OPEN';
  const canResolve = riskCase.status === 'PROCESSING';
  const canResend = command && ['FAILED', 'COMMAND_FAILED', 'DEAD_LETTER'].includes(command.status);
  const canClose = riskCase.status === 'RESOLVED' && riskCase.commandStatus === 'SUCCESS';
  const canReopen = riskCase.status === 'CLOSED';
  const canManualComplete = policy.manualCompleteEnabled && command
    && ['FAILED', 'COMMAND_FAILED', 'DEAD_LETTER'].includes(command.status);

  return (
    <aside className="risk-detail">
      <header className="risk-detail-header">
        <div>
          <h3>{riskCase.caseNo}</h3>
          <p className="risk-muted">{riskCase.sceneText || riskCase.scene} · {riskCase.bizNo || '-'}</p>
        </div>
        <div className="risk-detail-header-actions">
          <button type="button" className="risk-button small"
            onClick={() => copyText(riskCase.caseNo, onNotify)}>复制案件号</button>
          <button type="button" className="risk-button small" onClick={() => load({ silent: true })}>
            刷新
          </button>
        </div>
      </header>

      {phase === 'REFRESHING' ? <p className="risk-muted">刷新中...</p> : null}
      {error ? (
        <div className="risk-inline-error">
          <span>{error}</span>
          {traceId ? <span className="risk-trace">traceId: {traceId}</span> : null}
          <button type="button" className="risk-button small" onClick={() => load({ silent: true })}>重试</button>
        </div>
      ) : null}

      <section className="risk-detail-section">
        <h4>案件概要</h4>
        <dl className="risk-kv">
          <div><dt>状态</dt><dd>
            <RiskStatusTag text={riskCase.statusText} tone={toneOf(dictionary, 'caseStatus', riskCase.status)} />
          </dd></div>
          <div><dt>风险等级</dt><dd>
            <RiskStatusTag text={`${riskCase.riskLevelText || ''} ${riskCase.riskScore ?? ''}`.trim()}
              tone={toneOf(dictionary, 'riskLevel', riskCase.riskLevel)} />
          </dd></div>
          <div><dt>场景</dt><dd>{riskCase.sceneText || riskCase.scene}</dd></div>
          <div><dt>业务号</dt><dd>{riskCase.bizNo || '-'}</dd></div>
          <div><dt>创建时间</dt><dd>{formatTime(riskCase.createdTime)}</dd></div>
          <div><dt>更新时间</dt><dd>{formatTime(riskCase.updatedTime)}</dd></div>
        </dl>
      </section>

      <section className="risk-detail-section">
        <h4>处理信息</h4>
        <dl className="risk-kv">
          <div><dt>处理人</dt><dd>{riskCase.assignedTo || '未认领'}</dd></div>
          <div><dt>处理命令</dt><dd>{riskCase.resolvedActionText || riskCase.resolvedAction || '-'}</dd></div>
          <div><dt>处理原因</dt><dd>{riskCase.resolveReason || '-'}</dd></div>
          <div><dt>处理时间</dt><dd>{formatTime(riskCase.resolvedTime)}</dd></div>
        </dl>
      </section>

      <section className="risk-detail-section">
        <h4>命令状态</h4>
        {command ? (
          <dl className="risk-kv">
            <div><dt>命令编号</dt><dd className="risk-break">{command.commandNo || '-'}</dd></div>
            <div><dt>命令</dt><dd>{command.commandText || command.command || '-'}</dd></div>
            <div><dt>状态</dt><dd>
              <RiskStatusTag text={command.statusText} tone={command.tone || 'default'} />
            </dd></div>
            <div><dt>重试次数</dt><dd>{command.retryCount ?? 0}</dd></div>
            <div><dt>失败原因</dt><dd>{command.lastError || '-'}</dd></div>
            <div><dt>发送时间</dt><dd>{formatTime(command.sentTime)}</dd></div>
            <div><dt>完成时间</dt><dd>{formatTime(command.finishedTime)}</dd></div>
          </dl>
        ) : <p className="risk-muted">暂无命令</p>}
      </section>

      <section className="risk-detail-section">
        <h4>风控证据</h4>
        <RiskEvidencePanel detail={detail} dictionary={dictionary} />
      </section>

      <section className="risk-detail-section">
        <h4>操作历史</h4>
        <RiskCommandTimeline notes={detail.notes} dictionary={dictionary} />
      </section>

      <footer className="risk-detail-actions">
        {canAssign ? (
          <button type="button" className="risk-button primary"
            onClick={() => { setDialogError(''); setDialogOperation('assign'); }}>认领</button>
        ) : null}
        {canResolve ? (
          <button type="button" className="risk-button primary"
            onClick={() => { setDialogError(''); setDialogOperation('resolve'); }}>处置</button>
        ) : null}
        {canResend ? (
          <button type="button" className="risk-button danger"
            onClick={() => { setDialogError(''); setDialogOperation('resend'); }}>重发命令</button>
        ) : null}
        {canManualComplete ? (
          <button type="button" className="risk-button"
            onClick={() => { setDialogError(''); setDialogOperation('manualComplete'); }}>人工处理完成</button>
        ) : null}
        {canClose ? (
          <button type="button" className="risk-button primary"
            onClick={() => { setDialogError(''); setDialogOperation('close'); }}>关闭案件</button>
        ) : null}
        {canReopen ? (
          <button type="button" className="risk-button danger"
            onClick={() => { setDialogError(''); setDialogOperation('reopen'); }}>重开案件</button>
        ) : null}
      </footer>

      <RiskOperationDialog
        open={Boolean(dialogOperation)}
        operation={dialogOperation}
        detail={detail}
        dictionary={dictionary}
        busy={Boolean(busyKey)}
        error={dialogError}
        onCancel={() => setDialogOperation(null)}
        onSubmit={submitOperation}
      />
    </aside>
  );
};

const copyText = (text, notify) => {
  if (!text) return;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text)
      .then(() => notify?.('success', '已复制', text))
      .catch(() => notify?.('error', '复制失败', '请手动选择文本复制'));
    return;
  }
  notify?.('error', '复制失败', '当前浏览器不支持剪贴板');
};

const operationRequest = (operation, caseNo, detail, payload) => {
  switch (operation) {
    case 'assign':
      return riskApi.assign(caseNo, payload);
    case 'resolve':
      return riskApi.resolve(caseNo, payload);
    case 'close':
      return riskApi.close(caseNo, payload);
    case 'reopen':
      return riskApi.reopen(caseNo, payload);
    case 'resend':
      return riskApi.resendCommand(caseNo, detail.command.commandNo, payload);
    case 'manualComplete':
      return riskApi.manualComplete(caseNo, detail.command.commandNo, payload);
    default:
      return Promise.reject(new Error('不支持的操作'));
  }
};
