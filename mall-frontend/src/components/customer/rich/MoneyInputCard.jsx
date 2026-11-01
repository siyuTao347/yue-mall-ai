import React, { useState } from 'react';

export const MoneyInputCard = ({ card, onAction }) => {
  if (!card) return null;

  const data = card.data || {};
  const inputRequestNo = data.inputRequestNo || card.inputRequestNo || '';
  const caseNo = data.caseNo || card.caseNo || '';
  const prompt = data.prompt || card.prompt || '请提供补充凭证或说明以协助资金核查：';
  const fields = data.fields || card.fields || [{ key: 'statement', label: '补充说明', type: 'text', required: true }];
  const expiresAt = data.expiresAt || card.expiresAt || '';
  const initialStatus = data.status || card.status || 'REQUIRED';

  const [formValues, setFormValues] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(initialStatus === 'SUBMITTED');
  const [error, setError] = useState('');

  const isExpired = expiresAt && new Date(expiresAt).getTime() < Date.now();

  const handleInputChange = (key, value) => {
    setFormValues(prev => ({ ...prev, [key]: value }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (submitting || submitted || isExpired) return;

    for (const f of fields) {
      if (f.required && (!formValues[f.key] || !formValues[f.key].trim())) {
        setError(`请填写${f.label || f.key}`);
        return;
      }
    }

    setError('');
    setSubmitting(true);
    try {
      if (onAction) {
        await onAction({
          cardId: card.cardId,
          actionId: card.actionId || 'PROVIDE_MONEY_INPUT',
          type: 'SUBMIT_MONEY_INPUT',
          revision: card.revision || 1,
          input: {
            inputRequestNo,
            caseNo,
            answers: formValues
          }
        });
      }
      setSubmitted(true);
    } catch (err) {
      setError(err.message || '提交失败，请重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-yellow-200 rounded-lg shadow-sm p-4 my-2 max-w-md w-full text-sm">
      <div className="flex items-center justify-between border-b pb-2 mb-2">
        <div className="flex items-center space-x-2">
          <span className="font-semibold text-gray-800">调查材料补充</span>
          {submitted ? (
            <span className="px-2 py-0.5 text-xs font-semibold rounded bg-green-100 text-green-800">已提交</span>
          ) : isExpired ? (
            <span className="px-2 py-0.5 text-xs font-semibold rounded bg-red-100 text-red-800">已过期</span>
          ) : (
            <span className="px-2 py-0.5 text-xs font-semibold rounded bg-yellow-100 text-yellow-800">待补充</span>
          )}
        </div>
        <span className="text-xs text-gray-400 font-mono">Rev.{card.revision || 1}</span>
      </div>

      <div className="text-gray-700 text-xs mb-3 leading-relaxed">
        {prompt}
      </div>

      {isExpired && (
        <div className="text-xs text-red-600 bg-red-50 p-2 rounded mb-3">
          此补充材料要求已超过截止时间，如需核查请联系人工客服。
        </div>
      )}

      {error && (
        <div className="text-xs text-red-600 bg-red-50 p-2 rounded mb-3">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-3">
        {fields.map(f => (
          <div key={f.key} className="space-y-1">
            <label className="text-xs font-medium text-gray-600 flex justify-between">
              <span>{f.label || f.key}{f.required && <span className="text-red-500 ml-0.5">*</span>}</span>
            </label>
            {f.type === 'textarea' ? (
              <textarea
                disabled={submitted || isExpired || submitting}
                value={formValues[f.key] || ''}
                onChange={e => handleInputChange(f.key, e.target.value)}
                placeholder={f.placeholder || `请输入${f.label || f.key}`}
                rows={3}
                className="w-full text-xs border border-gray-300 rounded px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              />
            ) : (
              <input
                type="text"
                disabled={submitted || isExpired || submitting}
                value={formValues[f.key] || ''}
                onChange={e => handleInputChange(f.key, e.target.value)}
                placeholder={f.placeholder || `请输入${f.label || f.key}`}
                className="w-full text-xs border border-gray-300 rounded px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              />
            )}
          </div>
        ))}

        {!submitted && !isExpired && (
          <div className="flex justify-end pt-2">
            <button
              type="submit"
              disabled={submitting}
              className="px-4 py-1.5 text-xs font-medium text-white bg-blue-600 hover:bg-blue-700 disabled:opacity-50 rounded transition-colors"
            >
              {submitting ? '提交中…' : '提交补充材料'}
            </button>
          </div>
        )}
      </form>

      {expiresAt && !isExpired && !submitted && (
        <div className="text-right text-[11px] text-gray-400 mt-2">
          截止时间: {new Date(expiresAt).toLocaleString()}
        </div>
      )}
    </div>
  );
};

