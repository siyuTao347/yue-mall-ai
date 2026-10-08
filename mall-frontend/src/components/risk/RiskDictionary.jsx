import { useCallback, useEffect, useRef, useState } from 'react';
import { riskApi } from '../../api/riskApi';

export const FALLBACK_DICTIONARY = {
  caseStatus: [
    { code: 'OPEN', text: '待处理', tone: 'warning', sort: 1 },
    { code: 'PROCESSING', text: '处理中', tone: 'info', sort: 2 },
    { code: 'RESOLVED', text: '已处理', tone: 'success', sort: 3 },
    { code: 'CLOSED', text: '已关闭', tone: 'default', sort: 4 }
  ],
  commandStatus: [
    { code: 'NONE', text: '无命令', tone: 'default', sort: 1 },
    { code: 'PENDING_SEND', text: '待发送', tone: 'info', sort: 2 },
    { code: 'SENT', text: '已发送', tone: 'info', sort: 3 },
    { code: 'SUCCESS', text: '执行成功', tone: 'success', sort: 4 },
    { code: 'FAILED', text: '发送失败', tone: 'danger', sort: 5 },
    { code: 'COMMAND_FAILED', text: '执行失败', tone: 'danger', sort: 6 },
    { code: 'DEAD_LETTER', text: '死信待人工处理', tone: 'danger', sort: 7 }
  ],
  riskLevel: [
    { code: 'LOW', text: '低', tone: 'success', sort: 1 },
    { code: 'MEDIUM', text: '中', tone: 'warning', sort: 2 },
    { code: 'HIGH', text: '高', tone: 'danger', sort: 3 },
    { code: 'CRITICAL', text: '严重', tone: 'danger', sort: 4 }
  ],
  scene: [
    { code: 'ORDER', text: '订单', tone: 'info', sort: 1, commands: ['APPROVE', 'FREEZE', 'LIMIT', 'DELAY_SETTLE'] },
    { code: 'PAYMENT', text: '支付', tone: 'info', sort: 2, commands: ['APPROVE', 'FREEZE', 'LIMIT', 'DELAY_SETTLE'] },
    { code: 'DELIVERY', text: '交付', tone: 'info', sort: 3, commands: ['APPROVE', 'FREEZE', 'LIMIT', 'DELAY_SETTLE'] },
    { code: 'CONFIRM', text: '确认', tone: 'info', sort: 4, commands: ['APPROVE', 'FREEZE', 'LIMIT', 'DELAY_SETTLE'] },
    { code: 'DISPUTE', text: '售后', tone: 'info', sort: 5, commands: ['APPROVE', 'FREEZE', 'LIMIT', 'DELAY_SETTLE'] },
    { code: 'WITHDRAW', text: '提现', tone: 'info', sort: 6, commands: ['APPROVE', 'FREEZE', 'LIMIT', 'DELAY_SETTLE'] },
    { code: 'LISTING', text: '商品', tone: 'info', sort: 7, commands: ['APPROVE', 'REJECT', 'FREEZE'] },
    { code: 'MERCHANT', text: '商家', tone: 'info', sort: 8, commands: ['APPROVE', 'REJECT', 'FREEZE', 'LIMIT'] },
    { code: 'REGISTER', text: '注册', tone: 'info', sort: 9, commands: ['APPROVE', 'FREEZE'] },
    { code: 'LOGIN', text: '登录', tone: 'info', sort: 10, commands: ['APPROVE', 'FREEZE'] }
  ],
  noteType: [
    { code: 'CREATE', text: '自动创建案件', tone: 'info' },
    { code: 'ASSIGN', text: '认领案件', tone: 'info' },
    { code: 'PROCESS', text: '更新最新决策', tone: 'info' },
    { code: 'RESOLVE', text: '提交处理命令', tone: 'warning' },
    { code: 'CLOSE', text: '关闭案件', tone: 'success' },
    { code: 'REOPEN', text: '重开案件', tone: 'warning' },
    { code: 'COMMAND_SUCCESS', text: '命令执行成功', tone: 'success' },
    { code: 'COMMAND_FAILED', text: '命令执行失败', tone: 'danger' },
    { code: 'COMMAND_DEAD_LETTER', text: '命令进入死信', tone: 'danger' },
    { code: 'COMMAND_RESEND', text: '人工重发命令', tone: 'warning' },
    { code: 'COMMAND_MANUAL_COMPLETE', text: '人工确认处理结果', tone: 'warning' }
  ],
  commandPolicy: {
    pollIntervalSeconds: 5,
    pollMaxDurationSeconds: 300,
    manualCompleteEnabled: true,
    relationMaxNodes: 20,
    fundRelatedCommands: ['DELAY_SETTLE', 'FREEZE', 'LIMIT', 'REJECT', 'APPROVE']
  }
};

export const COMMAND_TEXTS = {
  APPROVE: '通过',
  REJECT: '驳回',
  FREEZE: '冻结',
  LIMIT: '限制',
  DELAY_SETTLE: '延迟结算'
};

export const optionList = (dictionary, group) =>
  (dictionary && dictionary[group]) || FALLBACK_DICTIONARY[group] || [];

export const labelOf = (dictionary, group, code) => {
  const hit = optionList(dictionary, group).find(item => item.code === code);
  return hit ? hit.text : code || '-';
};

export const toneOf = (dictionary, group, code) => {
  const hit = optionList(dictionary, group).find(item => item.code === code);
  return hit && hit.tone ? hit.tone : 'default';
};

export const commandsOf = (dictionary, scene, commandTexts = COMMAND_TEXTS) => {
  const hit = optionList(dictionary, 'scene').find(item => item.code === scene);
  const commands = (hit && hit.commands) || ['APPROVE', 'FREEZE'];
  return commands.map(code => ({ code, text: commandTexts[code] || code }));
};

export const useRiskDictionary = () => {
  const [dictionary, setDictionary] = useState(FALLBACK_DICTIONARY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const controllerRef = useRef(null);

  const reload = useCallback(() => {
    if (controllerRef.current) {
      controllerRef.current.abort();
    }
    const controller = new AbortController();
    controllerRef.current = controller;
    setLoading(true);
    return riskApi.dictionaries({ signal: controller.signal })
      .then(data => {
        if (data) {
          setDictionary(data);
        }
        setError('');
      })
      .catch(e => {
        if (e && e.code !== 'ABORTED') {
          setError(e.message || '字典加载失败');
        }
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    reload();
    return () => {
      if (controllerRef.current) {
        controllerRef.current.abort();
      }
    };
  }, [reload]);

  return { dictionary, loading, error, reload };
};

export const commandPolicyOf = dictionary =>
  (dictionary && dictionary.commandPolicy) || FALLBACK_DICTIONARY.commandPolicy;
