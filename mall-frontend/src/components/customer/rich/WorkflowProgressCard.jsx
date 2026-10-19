import React from 'react';

export const WorkflowProgressCard = ({ card, onAction }) => {
  const data = card?.data || {};
  const { workflowNo, title, steps = [], currentState } = data;

  return (
    <div className="rich-card rich-card-workflow">
      <div className="rich-card-header">
        <span className="rich-card-tag">任务进度</span>
        <span className="rich-card-title">{title || '流程办理进度'}</span>
      </div>

      <div className="workflow-stepper">
        {steps.map((st, idx) => {
          const isCurrent = st.key === currentState || st.state === 'ACTIVE';
          const isDone = st.state === 'COMPLETED' || st.completed;
          return (
            <div key={st.key || idx} className={`workflow-step ${isDone ? 'is-done' : ''} ${isCurrent ? 'is-current' : ''}`}>
              <div className="workflow-step-badge">
                {isDone ? '✓' : idx + 1}
              </div>
              <div className="workflow-step-info">
                <span className="workflow-step-label">{st.label || st.name}</span>
                {st.description && <small className="workflow-step-desc">{st.description}</small>}
              </div>
            </div>
          );
        })}
      </div>

      {card?.actions?.length > 0 && (
        <div className="rich-card-footer">
          {card.actions.map(act => (
            <button
              key={act.actionId}
              type="button"
              className="btn-card-action btn-primary"
              onClick={() => onAction?.(act)}
            >
              {act.label || '继续当前任务'}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};
