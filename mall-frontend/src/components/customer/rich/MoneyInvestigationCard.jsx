import React from 'react';

export const MoneyInvestigationCard = ({ card, onAction }) => {
  if (!card) return null;

  const data = card.data || {};
  const caseNo = data.caseNo || card.caseNo || '';
  const orderNo = data.orderNo || card.orderNo || '';
  const caseState = data.caseState || card.caseState || 'OPEN';
  const taskState = data.taskState || card.taskState || 'WORKING';
  const problemFamily = data.problemFamily || card.problemFamily || '资金异常调查';
  const summary = data.summary || card.summary || '调查处理中';
  const verifiedAt = data.updatedAt || data.verifiedAt || card.updatedAt || '';
  const actions = card.actions || data.actions || [];
  const facts = data.facts || card.facts || [];

  const getStageBadge = () => {
    if (caseState === 'CLOSED' || taskState === 'COMPLETED') {
      return <span className="px-2 py-0.5 text-xs font-semibold rounded bg-green-100 text-green-800">调查完成</span>;
    }
    if (taskState === 'INPUT_REQUIRED') {
      return <span className="px-2 py-0.5 text-xs font-semibold rounded bg-yellow-100 text-yellow-800">等待补证</span>;
    }
    if (taskState === 'NEED_REVIEW') {
      return <span className="px-2 py-0.5 text-xs font-semibold rounded bg-purple-100 text-purple-800">等待人工复核</span>;
    }
    if (caseState === 'CANCELLED' || taskState === 'CANCELLED') {
      return <span className="px-2 py-0.5 text-xs font-semibold rounded bg-gray-100 text-gray-800">已取消</span>;
    }
    return <span className="px-2 py-0.5 text-xs font-semibold rounded bg-blue-100 text-blue-800">调查进行中</span>;
  };

  return (
    <div className="bg-white border border-gray-200 rounded-lg shadow-sm p-4 my-2 max-w-md w-full text-sm">
      <div className="flex items-center justify-between border-b pb-2 mb-2">
        <div className="flex items-center space-x-2">
          <span className="font-semibold text-gray-800">资金核查进展</span>
          {getStageBadge()}
        </div>
        <span className="text-xs text-gray-400 font-mono">Rev.{card.revision || 1}</span>
      </div>

      <div className="space-y-2 text-gray-600">
        {orderNo && (
          <div className="flex justify-between">
            <span className="text-gray-500">关联订单:</span>
            <span className="font-mono text-gray-800">{orderNo}</span>
          </div>
        )}
        {caseNo && (
          <div className="flex justify-between">
            <span className="text-gray-500">案件编号:</span>
            <span className="font-mono text-xs text-gray-700">{caseNo}</span>
          </div>
        )}
        <div className="flex justify-between">
          <span className="text-gray-500">核查类型:</span>
          <span className="text-gray-800">{problemFamily}</span>
        </div>

        <div className="bg-gray-50 p-2.5 rounded border border-gray-100 my-2">
          <div className="text-xs font-medium text-gray-500 mb-1">调查核实结论</div>
          <div className="text-gray-700 text-xs leading-relaxed">{summary}</div>
        </div>

        {facts && facts.length > 0 && (
          <div className="space-y-1.5 my-2">
            <div className="text-xs font-medium text-gray-500">四层核实事实:</div>
            {facts.map((f, idx) => (
              <div key={idx} className="flex items-start text-xs bg-gray-50 px-2 py-1 rounded">
                <span className="text-blue-600 font-medium mr-1.5">• [{f.layer || '事实'}]:</span>
                <span className="text-gray-700 flex-1">{f.description || f.summary || JSON.stringify(f)}</span>
              </div>
            ))}
          </div>
        )}

        {verifiedAt && (
          <div className="text-right text-[11px] text-gray-400 mt-1">
            最后核实时间: {new Date(verifiedAt).toLocaleString()}
          </div>
        )}
      </div>

      {actions && actions.length > 0 && (
        <div className="flex flex-wrap gap-2 mt-3 pt-2 border-t border-gray-100 justify-end">
          {actions.map((act, idx) => (
            <button
              key={act.actionId || idx}
              onClick={() => onAction && onAction({
                cardId: card.cardId,
                actionId: act.actionId,
                type: act.type,
                revision: card.revision || 1,
                input: act.input || {}
              })}
              className="px-3 py-1 text-xs font-medium bg-blue-50 text-blue-700 hover:bg-blue-100 rounded transition-colors"
            >
              {act.label || '操作'}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

