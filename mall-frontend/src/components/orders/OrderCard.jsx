import React from 'react';
import { getStatusBadgeClass, getStatusText, formatMoney, formatTime } from '../../utils/orderStatus';

export const OrderCard = ({ order, onNavigateDetail, onPay, onCancel, onConfirm, onReveal, onDispute }) => {
  if (!order) return null;

  const item = order.item || {};
  const counterparty = order.counterparty || {};
  const allowedActions = order.allowedActions || [];

  const handleCopyOrderNo = (e) => {
    e.stopPropagation();
    navigator.clipboard?.writeText(order.orderNo);
    alert(`订单号 ${order.orderNo} 已复制到剪贴板`);
  };

  return (
    <div className="order-card" onClick={() => onNavigateDetail(order.orderNo)} style={{ cursor: 'pointer' }}>
      <div className="order-card-header">
        <div className="order-no-group">
          <span>订单号: {order.orderNo}</span>
          <button
            type="button"
            className="orders-refresh-btn"
            style={{ padding: '2px 8px', fontSize: '11px' }}
            onClick={handleCopyOrderNo}
            title="复制订单号"
          >
            复制
          </button>
          {order.source && order.source !== 'NORMAL' && (
            <span className="order-source-tag">
              {order.source === 'SECKILL' ? '秒杀' : '历史订单'}
            </span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <span className="order-time">{formatTime(order.createdTime)}</span>
          <span className={`order-badge ${getStatusBadgeClass(order.orderStatus, order.disputeStatus)}`}>
            {getStatusText(order.orderStatus, order.disputeStatus)}
          </span>
        </div>
      </div>

      <div className="order-card-body">
        <div className="order-item-img">
          {item.imageUrl ? (
            <img src={item.imageUrl} alt={item.name} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          ) : (
            '🎮'
          )}
        </div>

        <div className="order-item-info">
          <div className="order-item-name">{item.name || '饰品商品'}</div>
          <div className="order-item-meta">
            <span className="order-delivery-mode">
              {item.deliveryMode === 'AUTO_CARD' ? '⚡ 自动发卡' : '📦 人工发货'}
            </span>
            <span>{counterparty.displayName}</span>
            <span>单价: {formatMoney(order.unitPrice)}</span>
          </div>
        </div>

        <div className="order-amount-group">
          <span className="order-amount-label">实付总计</span>
          <span className="order-amount-val">{formatMoney(order.orderAmount)}</span>
          <span className="order-qty-sub">共 {order.quantity || 1} 件</span>
        </div>
      </div>

      <div className="order-card-footer" onClick={(e) => e.stopPropagation()}>
        <div className="order-deadline-hint">
          {order.statusDescription || ''}
        </div>

        <div className="order-actions-group">
          {allowedActions.includes('PAY') && (
            <button
              type="button"
              className="order-action-btn primary"
              onClick={() => onPay(order)}
            >
              立即支付
            </button>
          )}

          {allowedActions.includes('VIEW_CARD') && (
            <button
              type="button"
              className="order-action-btn secondary"
              onClick={() => onReveal(order)}
            >
              查看卡密
            </button>
          )}

          {allowedActions.includes('CONFIRM_RECEIPT') && (
            <button
              type="button"
              className="order-action-btn primary"
              onClick={() => onConfirm(order)}
            >
              确认收货
            </button>
          )}

          {allowedActions.includes('CANCEL') && (
            <button
              type="button"
              className="order-action-btn secondary"
              onClick={() => onCancel(order)}
            >
              取消订单
            </button>
          )}

          {allowedActions.includes('OPEN_DISPUTE') && (
            <button
              type="button"
              className="order-action-btn danger"
              onClick={() => onDispute(order)}
            >
              申请售后
            </button>
          )}

          <button
            type="button"
            className="order-action-btn secondary"
            onClick={() => onNavigateDetail(order.orderNo)}
          >
            详情
          </button>
        </div>
      </div>
    </div>
  );
};
