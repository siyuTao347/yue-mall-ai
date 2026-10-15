import React, { useState, useEffect, useCallback } from 'react';
import { tradeApi } from '../../api/tradeApi';
import { useApp } from '../../context/AppContext';
import { getStatusText, getStatusBadgeClass, formatMoney, formatTime } from '../../utils/orderStatus';
import './orders.css';

export const UserOrderDetailPage = ({ orderNo, navigate }) => {
  const { user } = useApp();
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [actionLoading, setActionLoading] = useState(false);

  // Modals / Inputs
  const [cardSecret, setCardSecret] = useState(null);
  const [revealAuditNotice, setRevealAuditNotice] = useState(false);
  const [disputeModal, setDisputeModal] = useState({ open: false, type: 'NOT_AS_DESCRIBED', reason: '', amount: '' });
  const [reviewModal, setReviewModal] = useState({ open: false, score: 5, content: '' });

  const fetchDetail = useCallback(async () => {
    if (!orderNo) return;
    setLoading(true);
    setError(null);
    try {
      const res = await tradeApi.order.detail(orderNo);
      setDetail(res);
      if (res?.cardSecret) {
        setCardSecret(res.cardSecret);
      }
    } catch (err) {
      setError(err.message || '获取订单详情失败');
    } finally {
      setLoading(false);
    }
  }, [orderNo]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  const order = detail?.order;
  const delivery = detail?.deliveryRecord;
  const dispute = detail?.dispute;
  const timeline = detail?.timeline || [];

  const allowedActions = order?.allowedActions || [];
  const canPay = allowedActions.includes('PAY') || order?.displayStatus === 'WAIT_PAY';
  const canCancel = allowedActions.includes('CANCEL') || order?.displayStatus === 'WAIT_PAY';
  const canConfirm = allowedActions.includes('CONFIRM') || order?.displayStatus === 'DELIVERED';
  const canDispute = allowedActions.includes('DISPUTE') && !order?.isDisputing;
  const canReview = allowedActions.includes('REVIEW');

  // Handle Pay
  const handlePay = async () => {
    if (!order?.paymentNo) {
      alert('订单未关联有效支付单，请刷新重试');
      return;
    }
    const confirmed = window.confirm(`确认支付订单 ${order.orderNo}，支付金额 ${formatMoney(order.orderAmount)}？`);
    if (!confirmed) return;

    try {
      setActionLoading(true);
      await tradeApi.payment.start(order.paymentNo);
      await tradeApi.payment.pay(order.paymentNo);
      alert('支付成功！');
      fetchDetail();
    } catch (err) {
      alert(`支付失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Cancel
  const handleCancel = async () => {
    const confirmed = window.confirm(`确认取消订单 ${order.orderNo} 吗？`);
    if (!confirmed) return;

    try {
      setActionLoading(true);
      await tradeApi.order.cancel(order.orderNo);
      alert('订单已取消');
      fetchDetail();
    } catch (err) {
      alert(`取消失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Confirm Receipt
  const handleConfirmReceipt = async () => {
    const confirmed = window.confirm(`确认收货订单 ${order.orderNo}？确认后资金将进入结算期。`);
    if (!confirmed) return;

    try {
      setActionLoading(true);
      await tradeApi.order.confirm(order.orderNo);
      alert('已确认收货！');
      fetchDetail();
    } catch (err) {
      alert(`确认收货失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Reveal Card
  const handleRevealCard = async () => {
    try {
      setActionLoading(true);
      const res = await tradeApi.order.reveal(order.orderNo);
      setCardSecret(res?.deliveryContent || '暂无卡密文本');
      setRevealAuditNotice(true);
    } catch (err) {
      alert(`查看卡密失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Dispute Submit
  const handleSubmitDispute = async (e) => {
    e.preventDefault();
    if (!disputeModal.reason.trim()) {
      alert('请填写售后理由');
      return;
    }

    try {
      setActionLoading(true);
      await tradeApi.dispute.open(
        order.orderNo,
        disputeModal.type,
        disputeModal.reason,
        disputeModal.amount ? Number(disputeModal.amount) : undefined
      );
      alert('售后申请已提交');
      setDisputeModal({ open: false, type: 'NOT_AS_DESCRIBED', reason: '', amount: '' });
      fetchDetail();
    } catch (err) {
      alert(`提交售后失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Review Submit
  const handleSubmitReview = async (e) => {
    e.preventDefault();
    try {
      setActionLoading(true);
      await tradeApi.order.review(order.orderNo, disputeModal.score || 5, disputeModal.content || '');
      alert('评价成功！');
      setReviewModal({ open: false, score: 5, content: '' });
      fetchDetail();
    } catch (err) {
      alert(`评价失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="order-detail-container">
        <div className="order-breadcrumb" onClick={() => navigate('/orders')}>
          &larr; 返回订单列表
        </div>
        <div className="orders-empty" style={{ minHeight: '300px' }}>
          <div className="orders-empty-icon">⏳</div>
          <div className="orders-empty-title">正在加载订单详情...</div>
        </div>
      </div>
    );
  }

  if (error || !order) {
    return (
      <div className="order-detail-container">
        <div className="order-breadcrumb" onClick={() => navigate('/orders')}>
          &larr; 返回订单列表
        </div>
        <div className="orders-empty">
          <div className="orders-empty-icon">⚠️</div>
          <div className="orders-empty-title">{error || '未找到该订单或无权访问'}</div>
          <button type="button" className="orders-filter-btn primary" onClick={() => navigate('/orders')}>
            返回订单中心
          </button>
        </div>
      </div>
    );
  }

  const badgeClass = getStatusBadgeClass(order.displayStatus, order.disputeStatus);
  const statusLabel = getStatusText(order.displayStatus, order.disputeStatus);
  const isAutoCard = order.item?.deliveryMode === 'AUTO_CARD' || delivery?.deliveryMode === 'AUTO_CARD';

  return (
    <div className="order-detail-container">
      {/* Breadcrumb */}
      <div className="order-breadcrumb" onClick={() => navigate('/orders')}>
        &larr; 返回订单列表
      </div>

      {/* Banner */}
      <div className="order-detail-banner">
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '8px' }}>
            <span className={`order-badge ${badgeClass}`} style={{ fontSize: '14px', padding: '6px 14px' }}>
              {statusLabel}
            </span>
            <span style={{ fontSize: '14px', color: 'var(--text-sub)' }}>
              订单号: <strong style={{ color: 'var(--text-main)', fontFamily: 'monospace' }}>{order.orderNo}</strong>
            </span>
          </div>
          <div className="order-detail-status-sub">
            {order.statusDescription || '订单状态更新正常'}
          </div>
          {order.payDeadline && order.displayStatus === 'WAIT_PAY' && (
            <div style={{ marginTop: '8px', fontSize: '13px', color: 'var(--color-primary)', fontWeight: 600 }}>
              ⏰ 支付截止时间: {formatTime(order.payDeadline)}
            </div>
          )}
        </div>

        {/* Header Action Buttons */}
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          {canPay && (
            <button
              type="button"
              className="order-action-btn primary"
              disabled={actionLoading}
              onClick={handlePay}
            >
              立即支付
            </button>
          )}
          {canConfirm && (
            <button
              type="button"
              className="order-action-btn primary"
              disabled={actionLoading}
              onClick={handleConfirmReceipt}
            >
              确认收货
            </button>
          )}
          {canCancel && (
            <button
              type="button"
              className="order-action-btn danger"
              disabled={actionLoading}
              onClick={handleCancel}
            >
              取消订单
            </button>
          )}
          {canDispute && (
            <button
              type="button"
              className="order-action-btn secondary"
              disabled={actionLoading}
              onClick={() => setDisputeModal({ open: true, type: 'NOT_AS_DESCRIBED', reason: '', amount: order.orderAmount })}
            >
              申请售后
            </button>
          )}
        </div>
      </div>

      {/* Card Secret Delivery Box */}
      {isAutoCard && ['PAID', 'DELIVERED', 'CONFIRMED', 'SETTLING', 'SETTLED'].includes(order.orderStatus) && (
        <div className="order-detail-section" style={{ borderColor: 'rgba(46, 213, 115, 0.4)' }}>
          <h2 className="order-detail-section-title">🔑 虚拟卡密自动交付</h2>
          {cardSecret ? (
            <div className="order-secret-box">
              <div style={{ fontSize: '13px', color: 'var(--text-sub)' }}>
                {revealAuditNotice ? '（披露操作已产生安全审计日志）' : '交付内容如下：'}
              </div>
              <div className="order-secret-text">{cardSecret}</div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
                <button
                  type="button"
                  className="order-action-btn primary"
                  onClick={() => {
                    navigator.clipboard?.writeText(cardSecret);
                    alert('卡密已复制到剪切板！');
                  }}
                >
                  📋 复制卡密
                </button>
              </div>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', alignItems: 'flex-start' }}>
              <div style={{ fontSize: '14px', color: 'var(--text-sub)' }}>
                卡密资产已成功分配到当前订单。为保障资产安全，查看卡密操作需要显式确认并计入系统安全审计日志。
              </div>
              <button
                type="button"
                className="order-action-btn primary"
                disabled={actionLoading}
                onClick={handleRevealCard}
              >
                {actionLoading ? '获取中...' : '👁️ 查看卡密'}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Item & Financial Breakdown */}
      <div className="order-detail-section">
        <h2 className="order-detail-section-title">📦 商品与金额信息</h2>
        <div className="order-card-body" style={{ padding: 0 }}>
          <div className="order-item-img">
            {order.item?.imageUrl ? (
              <img src={order.item.imageUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            ) : (
              '🎁'
            )}
          </div>
          <div className="order-item-info">
            <span className="order-item-name">{order.item?.name || `商品 #${order.itemId}`}</span>
            <div className="order-item-meta">
              <span className="order-delivery-mode">
                {isAutoCard ? '⚡ 自动发货' : '🚚 人工交付'}
              </span>
              <span>单价: {formatMoney(order.unitPrice)}</span>
              <span>数量: &times; {order.quantity || 1}</span>
            </div>
          </div>
          <div className="order-amount-group">
            <span className="order-amount-label">实付款</span>
            <span className="order-amount-val">{formatMoney(order.orderAmount)}</span>
          </div>
        </div>

        <div className="order-detail-grid" style={{ marginTop: '16px', borderTop: '1px solid var(--border)', paddingTop: '16px' }}>
          <div className="order-detail-field">
            <span className="order-detail-field-label">商品总额</span>
            <span className="order-detail-field-value">{formatMoney(order.orderAmount)}</span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">已支付金额</span>
            <span className="order-detail-field-value">{formatMoney(order.paidAmount || 0)}</span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">退款金额</span>
            <span className="order-detail-field-value" style={{ color: order.refundedAmount > 0 ? '#eb3b5a' : 'inherit' }}>
              {formatMoney(order.refundedAmount || 0)}
            </span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">交易币种</span>
            <span className="order-detail-field-value">{order.currency || 'CNY'}</span>
          </div>
        </div>
      </div>

      {/* Order Base Details & Counterparty */}
      <div className="order-detail-section">
        <h2 className="order-detail-section-title">ℹ️ 订单详细信息</h2>
        <div className="order-detail-grid">
          <div className="order-detail-field">
            <span className="order-detail-field-label">交易视角</span>
            <span className="order-detail-field-value">
              {order.viewRole === 'BUYER' ? '买家视角 (买入)' : '卖家视角 (卖出)'}
            </span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">交易对手方</span>
            <span className="order-detail-field-value">
              {order.counterparty?.displayName || (order.viewRole === 'BUYER' ? `卖家 #${order.sellerId}` : `买家 #${order.buyerId}`)}
            </span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">支付流水号</span>
            <span className="order-detail-field-value">{order.paymentNo || '-'}</span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">订单来源</span>
            <span className="order-detail-field-value">{order.source || 'NORMAL'}</span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">下单时间</span>
            <span className="order-detail-field-value">{formatTime(order.createdTime)}</span>
          </div>
          <div className="order-detail-field">
            <span className="order-detail-field-label">最后更新时间</span>
            <span className="order-detail-field-value">{formatTime(order.updatedTime)}</span>
          </div>
        </div>
      </div>

      {/* Dispute Section (if exists) */}
      {dispute && (
        <div className="order-detail-section" style={{ borderColor: 'rgba(255, 171, 0, 0.4)' }}>
          <h2 className="order-detail-section-title" style={{ color: '#ffab00' }}>
            ⚖️ 售后纠纷处理进度
          </h2>
          <div className="order-detail-grid">
            <div className="order-detail-field">
              <span className="order-detail-field-label">售后单号</span>
              <span className="order-detail-field-value">{dispute.disputeNo}</span>
            </div>
            <div className="order-detail-field">
              <span className="order-detail-field-label">售后状态</span>
              <span className="order-detail-field-value">{dispute.disputeStatus}</span>
            </div>
            <div className="order-detail-field">
              <span className="order-detail-field-label">售后类型</span>
              <span className="order-detail-field-value">{dispute.disputeType}</span>
            </div>
            <div className="order-detail-field">
              <span className="order-detail-field-label">申请退款金额</span>
              <span className="order-detail-field-value">{formatMoney(dispute.refundAmount)}</span>
            </div>
            <div className="order-detail-field" style={{ gridColumn: '1 / -1' }}>
              <span className="order-detail-field-label">申请原因说明</span>
              <span className="order-detail-field-value">{dispute.buyerReason}</span>
            </div>
            {dispute.arbitrationResult && (
              <div className="order-detail-field" style={{ gridColumn: '1 / -1' }}>
                <span className="order-detail-field-label">仲裁处理结果</span>
                <span className="order-detail-field-value" style={{ color: 'var(--color-primary)' }}>
                  {dispute.arbitrationResult}
                </span>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Timeline Section */}
      <div className="order-detail-section">
        <h2 className="order-detail-section-title">🕒 订单流转时间线</h2>
        {timeline.length === 0 ? (
          <div style={{ color: 'var(--text-sub)', fontSize: '14px', padding: '12px 0' }}>
            暂无详细日志记录
          </div>
        ) : (
          <div className="order-timeline">
            {timeline.map((log, index) => (
              <div key={log.id || index} className="order-timeline-node">
                <div className="order-timeline-title">
                  {log.action}
                  {log.toStatus && ` → ${log.toStatus}`}
                </div>
                <div className="order-timeline-time">{formatTime(log.createdAt)}</div>
                {log.remark && <div className="order-timeline-desc">{log.remark}</div>}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Modals */}
      {disputeModal.open && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: '20px' }}>
          <form onSubmit={handleSubmitDispute} style={{ background: 'var(--bg-white)', maxWidth: '520px', width: '100%', borderRadius: '12px', padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px', boxShadow: '0 12px 36px rgba(0,0,0,0.3)' }}>
            <h3 style={{ margin: 0, color: 'var(--text-main)', fontSize: '18px' }}>申请售后 / 争议仲裁</h3>
            <p style={{ margin: 0, color: 'var(--text-sub)', fontSize: '13px' }}>
              订单号: {order.orderNo}
            </p>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
              <label style={{ fontSize: '13px', color: 'var(--text-sub)' }}>售后原因类型</label>
              <select
                className="orders-select"
                value={disputeModal.type}
                onChange={(e) => setDisputeModal({ ...disputeModal, type: e.target.value })}
              >
                <option value="NOT_AS_DESCRIBED">商品与描述不符 / 无法使用</option>
                <option value="NOT_RECEIVED">未收到卡密或商品</option>
                <option value="CARD_INVALID">卡密已被使用或失效</option>
                <option value="OTHER">其他原因</option>
              </select>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
              <label style={{ fontSize: '13px', color: 'var(--text-sub)' }}>详细说明与证据</label>
              <textarea
                className="orders-search-input"
                rows={4}
                required
                placeholder="请详细描述问题及诉求..."
                value={disputeModal.reason}
                onChange={(e) => setDisputeModal({ ...disputeModal, reason: e.target.value })}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
              <label style={{ fontSize: '13px', color: 'var(--text-sub)' }}>申请退款金额 (元)</label>
              <input
                type="number"
                step="0.01"
                className="orders-search-input"
                value={disputeModal.amount}
                onChange={(e) => setDisputeModal({ ...disputeModal, amount: e.target.value })}
              />
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '10px' }}>
              <button type="button" className="order-action-btn secondary" onClick={() => setDisputeModal({ open: false, type: 'NOT_AS_DESCRIBED', reason: '', amount: '' })}>
                取消
              </button>
              <button type="submit" className="order-action-btn primary" disabled={actionLoading}>
                {actionLoading ? '提交中...' : '提交申请'}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};
