import { useEffect, useMemo, useState } from 'react';
import { COMMAND_TEXTS, commandPolicyOf, commandsOf } from './RiskDictionary';

const COMMAND_FIELDS = {
  APPROVE: [
    { key: 'amount', label: '处理金额', type: 'number' },
    { key: 'scope', label: '影响范围' },
    { key: 'remark', label: '备注' }
  ],
  REJECT: [
    { key: 'reason', label: '驳回说明' },
    { key: 'remark', label: '备注' }
  ],
  FREEZE: [
    { key: 'targetType', label: '冻结对象类型', required: true },
    { key: 'scope', label: '冻结范围' },
    { key: 'freezeDays', label: '冻结天数', type: 'number' },
    { key: 'remark', label: '备注' }
  ],
  LIMIT: [
    { key: 'scope', label: '限制范围', required: true },
    { key: 'limitAmount', label: '限制金额', type: 'number' },
    { key: 'remark', label: '备注' }
  ],
  DELAY_SETTLE: [
    { key: 'orderNo', label: '关联订单号' },
    { key: 'delayHours', label: '延迟小时数', type: 'number' },
    { key: 'remark', label: '备注' }
  ]
};

const CONSEQUENCE = {
  APPROVE: '通过后交易或申请将继续推进，风险由本次决策承担。',
  REJECT: '驳回后业务不可回退，请确认影响范围。',
  FREEZE: '冻结会限制资金或资产可用性，需明确恢复条件。',
  LIMIT: '限制会缩小该主体的交易或额度范围。',
  DELAY_SETTLE: '延迟结算会推迟资金到账，影响商家资金周转。'
};

const MIN_REASON = 10;

const TITLES = {
  assign: '认领案件',
  resolve: '处置案件',
  close: '关闭案件',
  reopen: '重开案件',
  resend: '重发命令',
  manualComplete: '人工处理完成'
};

export const RiskOperationDialog = ({
  open, operation, detail, dictionary, busy, error, onCancel, onSubmit
}) => {
  const riskCase = detail && detail.riskCase;
  const commandSnapshot = detail && detail.command;
  const policy = commandPolicyOf(dictionary);
  const commands = useMemo(
    () => commandsOf(dictionary, riskCase && riskCase.scene, COMMAND_TEXTS),
    [dictionary, riskCase]
  );
  const [command, setCommand] = useState('');
  const [reason, setReason] = useState('');
  const [params, setParams] = useState({});
  const [ack, setAck] = useState(false);
  const [confirmText, setConfirmText] = useState('');
  const [result, setResult] = useState('SUCCESS');
  const [evidence, setEvidence] = useState('');
  const [arming, setArming] = useState(true);
  const [localError, setLocalError] = useState('');
  const defaultCommand = commands.length ? commands[0].code : '';
  const caseNo = riskCase && riskCase.caseNo;

  useEffect(() => {
    if (!open) return undefined;
    setCommand(defaultCommand);
    setReason('');
    setParams({});
    setAck(false);
    setConfirmText('');
    setResult('SUCCESS');
    setEvidence('');
    setLocalError('');
    setArming(true);
    const timer = setTimeout(() => setArming(false), 1000);
    return () => clearTimeout(timer);
  }, [open, operation, caseNo, defaultCommand]);

  if (!open || !riskCase) {
    return null;
  }

  const fundRelatedCommands = policy.fundRelatedCommands || [];
  const isFundRelated = operation === 'resolve'
    && fundRelatedCommands.includes(command);
  const isCritical = riskCase.riskLevel === 'CRITICAL';
  const isHighRisk = riskCase.riskLevel === 'HIGH' || isCritical;
  const needsAck = isFundRelated || isHighRisk;
  const needsReason = operation !== 'assign';

  const submit = () => {
    if (needsReason && reason.trim().length < MIN_REASON) {
      setLocalError(`处理原因至少 ${MIN_REASON} 个字符`);
      return;
    }
    if (operation === 'resolve' && !command) {
      setLocalError('请选择处理命令');
      return;
    }
    if (needsAck && !ack) {
      setLocalError('高风险或资金相关操作需要勾选确认影响范围');
      return;
    }
    if (isCritical && confirmText.trim() !== riskCase.caseNo) {
      setLocalError('严重风险案件需要输入案件号确认');
      return;
    }
    if (operation === 'resolve') {
      const fields = COMMAND_FIELDS[command] || [];
      const missing = fields.filter(field => field.required
        && !String(params[field.key] || '').trim()).map(field => field.label);
      if (missing.length > 0) {
        setLocalError(`请填写：${missing.join('、')}`);
        return;
      }
    }
    setLocalError('');
    onSubmit(buildPayload());
  };

  const buildPayload = () => {
    const expectedStatus = riskCase.status;
    const expectedCommandStatus = (commandSnapshot && commandSnapshot.status)
      || riskCase.commandStatus || 'NONE';
    const operationVersion = riskCase.operationVersion;
    if (operation === 'assign') {
      return { expectedStatus, operationVersion };
    }
    if (operation === 'resolve') {
      return {
        expectedStatus,
        operationVersion,
        command,
        reason: reason.trim(),
        actionParams: buildParams()
      };
    }
    if (operation === 'close' || operation === 'reopen') {
      return { expectedStatus, expectedCommandStatus, operationVersion, reason: reason.trim() };
    }
    if (operation === 'resend') {
      return { expectedStatus, expectedCommandStatus, operationVersion, reason: reason.trim() };
    }
    return {
      expectedCommandStatus,
      result,
      reason: reason.trim(),
      evidence: evidence.trim() || undefined
    };
  };

  const buildParams = () => {
    const fields = COMMAND_FIELDS[command] || [];
    const result = {};
    fields.forEach(field => {
      const value = params[field.key];
      if (value === undefined || value === null || String(value).trim() === '') {
        return;
      }
      result[field.key] = field.type === 'number' ? Number(value) : String(value).trim();
    });
    return result;
  };

  const target = riskCase.bizNo || `${riskCase.subjectType || ''} ${riskCase.subjectId || ''}`;

  return (
    <div className="risk-dialog-mask" role="dialog" aria-modal="true">
      <div className="risk-dialog">
        <header className="risk-dialog-header">
          <h3>{TITLES[operation] || '案件操作'}</h3>
          <button type="button" className="risk-dialog-close" onClick={onCancel} aria-label="关闭">×</button>
        </header>
        <div className="risk-dialog-body">
          <dl className="risk-dialog-summary">
            <div><dt>案件号</dt><dd>{riskCase.caseNo}</dd></div>
            <div><dt>风险等级</dt><dd>{riskCase.riskLevelText || riskCase.riskLevel}（{riskCase.riskScore ?? '-'}）</dd></div>
            <div><dt>业务对象</dt><dd>{target}</dd></div>
            {operation === 'resolve' ? (
              <div><dt>处理命令</dt><dd>{COMMAND_TEXTS[command] || command || '-'}</dd></div>
            ) : null}
          </dl>

          {operation === 'resolve' ? (
            <div className="risk-dialog-block">
              <label className="risk-dialog-field">
                <span>处理命令</span>
                <select value={command} onChange={event => setCommand(event.target.value)}>
                  {commands.map(item => (
                    <option key={item.code} value={item.code}>{item.text}</option>
                  ))}
                </select>
              </label>
              <p className="risk-dialog-consequence">{CONSEQUENCE[command] || '请确认处理后果。'}</p>
              {(COMMAND_FIELDS[command] || []).map(field => (
                <label key={field.key} className="risk-dialog-field">
                  <span>{field.label}{field.required ? ' *' : ''}</span>
                  <input type={field.type === 'number' ? 'number' : 'text'}
                    value={params[field.key] || ''}
                    onChange={event => setParams(prev => ({ ...prev, [field.key]: event.target.value }))} />
                </label>
              ))}
            </div>
          ) : null}

          {operation === 'manualComplete' ? (
            <div className="risk-dialog-block">
              <label className="risk-dialog-field">
                <span>人工结果</span>
                <select value={result} onChange={event => setResult(event.target.value)}>
                  <option value="SUCCESS">已在业务系统完成同等处理</option>
                  <option value="FAILED">确认无法处理</option>
                </select>
              </label>
              <label className="risk-dialog-field">
                <span>处理说明</span>
                <textarea maxLength={512} value={evidence}
                  onChange={event => setEvidence(event.target.value)} />
              </label>
            </div>
          ) : null}

          {needsReason ? (
            <label className="risk-dialog-field">
              <span>处理原因 *（至少 {MIN_REASON} 字）</span>
              <textarea maxLength={255} value={reason}
                onChange={event => setReason(event.target.value)} />
            </label>
          ) : null}

          {needsAck ? (
            <label className="risk-dialog-check">
              <input type="checkbox" checked={ack}
                onChange={event => setAck(event.target.checked)} />
              <span>我已确认影响范围，理解该操作带来的业务后果{isFundRelated ? '及资金或资产可用性变化' : ''}</span>
            </label>
          ) : null}

          {isCritical ? (
            <label className="risk-dialog-field">
              <span>严重风险确认：请输入案件号 {riskCase.caseNo}</span>
              <input type="text" value={confirmText}
                onChange={event => setConfirmText(event.target.value)} />
            </label>
          ) : null}

          {localError || error ? (
            <div className="risk-dialog-error">
              <span>{localError || error}</span>
            </div>
          ) : null}
        </div>
        <footer className="risk-dialog-footer">
          <button type="button" className="risk-button" onClick={onCancel} disabled={busy}>取消</button>
          <button type="button" className="risk-button danger" onClick={submit}
            disabled={busy || arming || (needsReason && reason.trim().length < MIN_REASON)}>
            {busy ? '提交中...' : '确认提交'}
          </button>
        </footer>
      </div>
    </div>
  );
};
