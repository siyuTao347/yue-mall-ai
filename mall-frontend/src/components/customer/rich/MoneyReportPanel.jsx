import React from 'react';

export const MoneyReportPanel = ({ report, onClose }) => {
  if (!report) return null;

  return (
    <div className="bg-white border border-gray-200 rounded-lg shadow-md p-4 max-w-lg w-full text-sm">
      <div className="flex items-center justify-between border-b pb-2 mb-3">
        <span className="font-semibold text-gray-800">资金调查报告详情</span>
        {onClose && (
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-xs">
            ✕ 关闭
          </button>
        )}
      </div>

      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2 text-xs">
          <div>
            <span className="text-gray-500">报告编号: </span>
            <span className="font-mono text-gray-700">{report.reportNo || '-'}</span>
          </div>
          <div>
            <span className="text-gray-500">案件编号: </span>
            <span className="font-mono text-gray-700">{report.caseNo || '-'}</span>
          </div>
          <div>
            <span className="text-gray-500">调查结果: </span>
            <span className="font-semibold text-blue-700">{report.outcome || '-'}</span>
          </div>
          <div>
            <span className="text-gray-500">版本: </span>
            <span className="font-mono text-gray-700">v{report.revision || 1}</span>
          </div>
        </div>

        <div className="bg-gray-50 p-2.5 rounded border border-gray-100">
          <div className="text-xs font-medium text-gray-600 mb-1">结论摘要</div>
          <div className="text-xs text-gray-800 leading-relaxed">{report.summary || '无详细摘要'}</div>
        </div>

        {report.findings && report.findings.length > 0 && (
          <div>
            <div className="text-xs font-medium text-gray-600 mb-1.5">调查发现 (Findings):</div>
            <div className="space-y-1">
              {report.findings.map((f, i) => (
                <div key={i} className="text-xs bg-blue-50/50 p-2 rounded border border-blue-100/50">
                  <span className="font-medium text-blue-800">[{f.ruleId || f.findingType || '发现'}] </span>
                  <span className="text-gray-700">{f.description || JSON.stringify(f)}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {report.recommendedAction && (
          <div className="bg-amber-50 p-2.5 rounded border border-amber-100">
            <div className="text-xs font-medium text-amber-800 mb-0.5">建议处理动作</div>
            <div className="text-xs text-amber-900">{report.recommendedAction}</div>
          </div>
        )}

        {report.publishedAt && (
          <div className="text-right text-[11px] text-gray-400">
            出具时间: {new Date(report.publishedAt).toLocaleString()}
          </div>
        )}
      </div>
    </div>
  );
};

