import React, { useState } from 'react';

export const DynamicFormCard = ({ card, onAction }) => {
  const data = card?.data || {};
  const fields = data.fields || [];
  const [formData, setFormData] = useState(() => {
    const init = {};
    for (const f of fields) {
      init[f.name] = f.defaultValue !== undefined ? f.defaultValue : '';
    }
    return init;
  });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(data.submitted || false);

  const handleChange = (name, value) => {
    setFormData(prev => ({ ...prev, [name]: value }));
    if (errors[name]) {
      setErrors(prev => {
        const next = { ...prev };
        delete next[name];
        return next;
      });
    }
  };

  const validate = () => {
    const errs = {};
    for (const f of fields) {
      if (f.required && (formData[f.name] === undefined || formData[f.name] === null || String(formData[f.name]).trim() === '')) {
        errs[f.name] = `${f.label || f.name} 是必填项`;
      }
    }
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    if (submitting || submitted) return;
    if (!validate()) return;

    setSubmitting(true);
    const action = card?.actions?.find(a => a.type === 'SUBMIT_FORM') || card?.actions?.[0] || {
      actionId: 'SUBMIT_FORM',
      type: 'SUBMIT_FORM',
      label: '提交表单'
    };

    onAction?.({
      ...action,
      payload: {
        workflowNo: data.workflowNo,
        taskNo: data.taskNo,
        formInstanceId: data.formInstanceId,
        taskVersion: data.taskVersion,
        values: formData
      }
    });

    setSubmitted(true);
    setSubmitting(false);
  };

  return (
    <div className="rich-card rich-card-form">
      <div className="rich-card-header">
        <span className="rich-card-tag">资料补充 / 表单</span>
        <span className="rich-card-title">{data.title || '请完善以下信息'}</span>
      </div>

      <form onSubmit={handleSubmit} className="dynamic-form-body">
        {fields.map(field => {
          const fieldId = `field_${data.formInstanceId || 'form'}_${field.name}`;
          return (
            <div key={field.name} className={`form-field-group ${errors[field.name] ? 'has-error' : ''}`}>
              <label htmlFor={fieldId} className="form-field-label">
                {field.label || field.name}
                {field.required && <span className="required-star">*</span>}
              </label>

              {field.widget === 'select' ? (
                <select
                  id={fieldId}
                  value={formData[field.name] || ''}
                  disabled={submitted}
                  onChange={e => handleChange(field.name, e.target.value)}
                  className="form-control"
                >
                  <option value="">请选择...</option>
                  {(field.options || []).map(opt => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label || opt.value}
                    </option>
                  ))}
                </select>
              ) : field.widget === 'textarea' ? (
                <textarea
                  id={fieldId}
                  value={formData[field.name] || ''}
                  disabled={submitted}
                  maxLength={field.maxLength || 1000}
                  rows={3}
                  onChange={e => handleChange(field.name, e.target.value)}
                  placeholder={field.placeholder || ''}
                  className="form-control"
                />
              ) : (
                <input
                  id={fieldId}
                  type={field.widget === 'number' ? 'number' : 'text'}
                  value={formData[field.name] || ''}
                  disabled={submitted}
                  maxLength={field.maxLength || 200}
                  onChange={e => handleChange(field.name, e.target.value)}
                  placeholder={field.placeholder || ''}
                  className="form-control"
                />
              )}

              {errors[field.name] && (
                <span className="form-field-error" role="alert">
                  {errors[field.name]}
                </span>
              )}
            </div>
          );
        })}

        {!submitted && (
          <div className="form-submit-row">
            <button
              type="submit"
              disabled={submitting}
              className="btn-card-action btn-primary"
            >
              {submitting ? '提交中...' : (card?.actions?.[0]?.label || '提交')}
            </button>
          </div>
        )}

        {submitted && (
          <div className="form-submitted-banner">
            ✓ 表单已提交，请等待处理
          </div>
        )}
      </form>
    </div>
  );
};
