import React from 'react';

export const OrderTimelineCard = ({ card, onAction }) => {
  const data = card?.data || {};
  const items = data.items || [];
  const orderNo = data.orderNo;

  if (!items.length) {
    return <div className="rich-card-fallback">{card?.fallbackText || `订单 ${orderNo || ''}`}</div>;
  }

  const formatTime = (ts) => {
    if (!ts) return '';
    try {
      const d = new Date(ts);
      return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    } catch {
      return String(ts);
    }
  };

  return (
    <div className="rich-card rich-card-timeline">
      <div className="rich-card-header">
        <span className="rich-card-tag">订单状态追踪</span>
        {orderNo && <span className="rich-card-order-no">订单号: {orderNo}</span>}
      </div>
      <div className="timeline-stepper">
        {items.map((it, idx) => {
          const isLatest = idx === items.length - 1;
          const isCompleted = it.state === 'COMPLETED' || it.state === 'SUCCESS';
          return (
            <div
              key={it.eventKey || idx}
              className={`timeline-step ${isLatest ? 'is-latest' : ''} ${isCompleted ? 'is-completed' : ''}`}
            >
              <div className="timeline-node">
                <span className="timeline-dot" />
                {idx < items.length - 1 && <span className="timeline-line" />}
              </div>
              <div className="timeline-body">
                <div className="timeline-title-row">
                  <span className="timeline-label">{it.label || it.state}</span>
                  <span className="timeline-time">{formatTime(it.occurredAt || it.createdTime)}</span>
                </div>
                {it.description && <div className="timeline-desc">{it.description}</div>}
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
              {act.label || '查看订单'}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};
