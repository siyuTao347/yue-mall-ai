import React from 'react';

export const HandoffStatusCard = ({ card, onAction }) => {
  const data = card?.data || {};
  const { handoffNo, mode, queueState, staffName, estimatedWaitSeconds } = data;

  const isQueued = mode === 'QUEUED' || queueState === 'WAITING' || queueState === 'PENDING';
  const isHuman = mode === 'HUMAN';

  return (
    <div className={`rich-card rich-card-handoff ${isHuman ? 'is-human-assigned' : 'is-queued'}`}>
      <div className="rich-card-header">
        <span className={`rich-card-tag ${isHuman ? 'tag-success' : 'tag-warning'}`}>
          {isHuman ? '人工服务中' : '排队转接中'}
        </span>
        {handoffNo && <span className="rich-card-id">服务单号: {handoffNo}</span>}
      </div>

      <div className="handoff-card-body">
        {isHuman ? (
          <div className="handoff-staff-info">
            <span className="staff-avatar">👩‍💼</span>
            <div className="staff-meta">
              <strong className="staff-name">{staffName || '在线客服专员'}</strong>
              <small className="staff-tip">已进入会话为您提供一对一服务</small>
            </div>
          </div>
        ) : (
          <div className="handoff-queue-info">
            <div className="queue-spinner" aria-hidden="true" />
            <div className="queue-meta">
              <strong className="queue-title">正在为您匹配最合适的客服专员...</strong>
              {estimatedWaitSeconds ? (
                <small className="queue-tip">预计等待时间约 {Math.ceil(estimatedWaitSeconds / 60)} 分钟</small>
              ) : (
                <small className="queue-tip">当前客服繁忙，请稍候，我们正在快速处理</small>
              )}
            </div>
          </div>
        )}
      </div>

      {isQueued && (
        <div className="rich-card-footer">
          <button
            type="button"
            className="btn-card-action btn-secondary"
            onClick={() => onAction?.({
              actionId: 'CANCEL_HANDOFF',
              type: 'CANCEL_HANDOFF',
              payload: { handoffNo },
              label: '取消排队'
            })}
          >
            取消排队并返回智能客服
          </button>
        </div>
      )}
    </div>
  );
};
