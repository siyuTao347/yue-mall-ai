import React, { useState, useEffect, useCallback } from 'react';
import { tradeApi } from '../../api/tradeApi';
import { useApp } from '../../context/AppContext';
import { OrderFilters } from '../../components/orders/OrderFilters';
import { OrderCard } from '../../components/orders/OrderCard';
import { Pagination } from '../../components/ui/Pagination';
import './orders.css';

export const UserOrdersPage = ({ navigate, initialParams = {} }) => {
  const { user } = useApp();

  const [viewRole, setViewRole] = useState(initialParams.view || 'BUYER');
  const [activeTab, setActiveTab] = useState(initialParams.tab || 'ALL');
  const [orderNoQuery, setOrderNoQuery] = useState(initialParams.orderNo || '');
  const [timeRange, setTimeRange] = useState(initialParams.timeRange || 'ALL');
  const [page, setPage] = useState(Number(initialParams.page) || 1);

  const [orders, setOrders] = useState([]);
  const [pageInfo, setPageInfo] = useState({ total: 0, pageSize: 20, hasMore: false });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Modals state
  const [revealModal, setRevealModal] = useState({ open: false, orderNo: '', content: '' });
  const [disputeModal, setDisputeModal] = useState({ open: false, order: null, type: 'NOT_AS_DESCRIBED', reason: '', amount: '' });
  const [actionLoading, setActionLoading] = useState(false);

  const fetchOrders = useCallback(async (targetPage = page) => {
    if (!user) return;
    setLoading(true);
    setError(null);

    const params = {
      page: targetPage,
      pageSize: 20,
      view: viewRole,
      statusGroup: activeTab !== 'ALL' ? activeTab : undefined,
      orderNo: orderNoQuery || undefined
    };

    if (timeRange && timeRange !== 'ALL') {
      const now = new Date();
      let days = 30;
      if (timeRange === '7D') days = 7;
      if (timeRange === '90D') days = 90;
      const past = new Date(now.getTime() - days * 24 * 60 * 60 * 1000);
      params.fromTime = past.toISOString();
      params.toTime = now.toISOString();
    }

    try {
      const res = await tradeApi.order.list(params);
      setOrders(res?.records || []);
      setPageInfo({
        total: res?.total || 0,
        pageSize: res?.pageSize || 20,
        hasMore: res?.hasMore || false
      });
      setPage(targetPage);
    } catch (err) {
      setError(err.message || '加载订单列表失败');
      setOrders([]);
    } finally {
      setLoading(false);
    }
  }, [user, viewRole, activeTab, orderNoQuery, timeRange, page]);

  useEffect(() => {
    fetchOrders(1);
  }, [viewRole, activeTab, orderNoQuery, timeRange]);

  const handleTabChange = (newTab) => {
    setActiveTab(newTab);
    navigate('/orders', { view: viewRole, tab: newTab, page: 1, orderNo: orderNoQuery });
  };

  const handleViewChange = (newView) => {
    setViewRole(newView);
    setActiveTab('ALL');
    setOrderNoQuery('');
    navigate('/orders', { view: newView, tab: 'ALL', page: 1 });
  };

  const handleSearch = ({ orderNo, timeRange }) => {
    setOrderNoQuery(orderNo);
    setTimeRange(timeRange);
    navigate('/orders', { view: viewRole, tab: activeTab, page: 1, orderNo, timeRange });
  };

  const handleReset = () => {
    setOrderNoQuery('');
    setTimeRange('ALL');
    setActiveTab('ALL');
    navigate('/orders', { view: viewRole, tab: 'ALL', page: 1 });
  };

  // Actions
  const handlePay = async (order) => {
    if (!order.paymentNo) {
      alert('订单未关联有效支付单，请刷新重试');
      return;
    }
    const confirmed = window.confirm(`确认支付订单 ${order.orderNo}，支付金额 ¥${order.orderAmount}？`);
    if (!confirmed) return;

    try {
      setActionLoading(true);
      await tradeApi.payment.start(order.paymentNo);
      await tradeApi.payment.pay(order.paymentNo);
      alert('支付成功！');
      fetchOrders(page);
    } catch (err) {
      alert(`支付失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  const handleCancel = async (order) => {
    const confirmed = window.confirm(`确认取消待支付订单 ${order.orderNo} 吗？`);
    if (!confirmed) return;

    try {
      setActionLoading(true);
      await tradeApi.order.cancel(order.orderNo);
      alert('订单已成功取消');
      fetchOrders(page);
    } catch (err) {
      alert(`取消失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  const handleConfirmReceipt = async (order) => {
    const confirmed = window.confirm(`确认收货订单 ${order.orderNo}？确认后资金将进入结算期。`);
    if (!confirmed) return;

    try {
      setActionLoading(true);
      await tradeApi.order.confirm(order.orderNo);
      alert('已确认收货！');
      fetchOrders(page);
    } catch (err) {
      alert(`确认失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  const handleRevealCard = async (order) => {
    try {
      setActionLoading(true);
      const res = await tradeApi.order.reveal(order.orderNo);
      setRevealModal({
        open: true,
        orderNo: order.orderNo,
        content: res?.deliveryContent || '暂无卡密文本'
      });
    } catch (err) {
      alert(`获取卡密失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  const handleOpenDispute = (order) => {
    setDisputeModal({
      open: true,
      order,
      type: 'NOT_AS_DESCRIBED',
      reason: '',
      amount: order.orderAmount || ''
    });
  };

  const handleSubmitDispute = async (e) => {
    e.preventDefault();
    if (!disputeModal.order) return;
    if (!disputeModal.reason.trim()) {
      alert('请填写售后理由');
      return;
    }

    try {
      setActionLoading(true);
      await tradeApi.dispute.open(
        disputeModal.order.orderNo,
        disputeModal.type,
        disputeModal.reason,
        disputeModal.amount ? Number(disputeModal.amount) : undefined
      );
      alert('售后申请已提交');
      setDisputeModal({ open: false, order: null, type: 'NOT_AS_DESCRIBED', reason: '', amount: '' });
      fetchOrders(page);
    } catch (err) {
      alert(`提交售后失败: ${err.message || '未知错误'}`);
    } finally {
      setActionLoading(false);
    }
  };

  if (!user) {
    return (
      <div className="orders-page-container">
        <div className="orders-empty">
          <div className="orders-empty-icon">🔒</div>
          <div className="orders-empty-title">请先登录账号</div>
          <div className="orders-empty-desc">登录后即可查看买入与卖出的全部订单记录及交付卡密</div>
          <button type="button" className="orders-filter-btn primary" onClick={() => navigate('/home')}>
            返回首页登录
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="orders-page-container">
      {/* Header */}
      <div className="orders-header">
        <div className="orders-title-group">
          <h1 className="orders-title">我的订单</h1>
          <div className="orders-view-toggle">
            <button
              type="button"
              className={`orders-view-btn ${viewRole === 'BUYER' ? 'active' : ''}`}
              onClick={() => handleViewChange('BUYER')}
            >
              我买到的
            </button>
            <button
              type="button"
              className={`orders-view-btn ${viewRole === 'SELLER' ? 'active' : ''}`}
              onClick={() => handleViewChange('SELLER')}
            >
              我卖出的
            </button>
          </div>
        </div>

        <button type="button" className="orders-refresh-btn" onClick={() => fetchOrders(page)} disabled={loading}>
          {loading ? '刷新中...' : '🔄 刷新'}
        </button>
      </div>

      {/* Filters & Tabs */}
      <OrderFilters
        activeTab={activeTab}
        onTabChange={handleTabChange}
        onSearch={handleSearch}
        onReset={handleReset}
      />

      {/* Error Notice */}
      {error && (
        <div style={{ padding: '12px 16px', borderRadius: '8px', background: 'rgba(235, 59, 90, 0.1)', color: '#eb3b5a', border: '1px solid rgba(235, 59, 90, 0.3)' }}>
          {error}
        </div>
      )}

      {/* Order List */}
      {loading ? (
        <div className="orders-list">
          {[1, 2, 3].map((i) => (
            <div key={i} className="order-card" style={{ opacity: 0.6 }}>
              <div style={{ height: '20px', background: 'var(--bg-hover)', borderRadius: '4px', width: '40%' }}></div>
              <div style={{ height: '80px', background: 'var(--bg-hover)', borderRadius: '6px', margin: '12px 0' }}></div>
              <div style={{ height: '30px', background: 'var(--bg-hover)', borderRadius: '4px', width: '20%' }}></div>
            </div>
          ))}
        </div>
      ) : orders.length === 0 ? (
        <div className="orders-empty">
          <div className="orders-empty-icon">📦</div>
          <div className="orders-empty-title">暂无相关订单</div>
          <div className="orders-empty-desc">
            {orderNoQuery ? '未找到符合条件的订单号，请尝试清除搜索条件' : '您当前分类下没有订单记录'}
          </div>
          {(orderNoQuery || activeTab !== 'ALL') && (
            <button type="button" className="orders-filter-btn secondary" onClick={handleReset}>
              清除所有筛选
            </button>
          )}
        </div>
      ) : (
        <div className="orders-list">
          {orders.map((order) => (
            <OrderCard
              key={order.orderNo}
              order={order}
              onNavigateDetail={(orderNo) => navigate(`/orders/${orderNo}`)}
              onPay={handlePay}
              onCancel={handleCancel}
              onConfirm={handleConfirmReceipt}
              onReveal={handleRevealCard}
              onDispute={handleOpenDispute}
            />
          ))}
        </div>
      )}

      {/* Pagination */}
      <Pagination
        page={page}
        pageSize={pageInfo.pageSize}
        total={pageInfo.total}
        hasMore={pageInfo.hasMore}
        onPageChange={(nextPage) => {
          navigate('/orders', { view: viewRole, tab: activeTab, page: nextPage, orderNo: orderNoQuery });
          fetchOrders(nextPage);
        }}
      />

      {/* Reveal Card Secret Modal */}
      {revealModal.open && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: '20px' }}>
          <div style={{ background: 'var(--bg-white)', maxWidth: '500px', width: '100%', borderRadius: '12px', padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px', boxShadow: '0 12px 36px rgba(0,0,0,0.3)' }}>
            <h3 style={{ margin: 0, color: 'var(--text-main)', fontSize: '18px' }}>卡密信息已披露</h3>
            <p style={{ margin: 0, color: 'var(--text-sub)', fontSize: '13px' }}>
              订单号: {revealModal.orderNo} (披露操作已被系统日志审计)
            </p>
            <div className="order-secret-box">
              <div className="order-secret-text">{revealModal.content}</div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
              <button
                type="button"
                className="order-action-btn primary"
                onClick={() => {
                  navigator.clipboard?.writeText(revealModal.content);
                  alert('卡密已复制到剪贴板！');
                }}
              >
                复制卡密
              </button>
              <button
                type="button"
                className="order-action-btn secondary"
                onClick={() => setRevealModal({ open: false, orderNo: '', content: '' })}
              >
                关闭
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Dispute Modal */}
      {disputeModal.open && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: '20px' }}>
          <form onSubmit={handleSubmitDispute} style={{ background: 'var(--bg-white)', maxWidth: '520px', width: '100%', borderRadius: '12px', padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px', boxShadow: '0 12px 36px rgba(0,0,0,0.3)' }}>
            <h3 style={{ margin: 0, color: 'var(--text-main)', fontSize: '18px' }}>申请订单售后 / 仲裁</h3>
            <p style={{ margin: 0, color: 'var(--text-sub)', fontSize: '13px' }}>
              订单号: {disputeModal.order?.orderNo}
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
              <button type="button" className="order-action-btn secondary" onClick={() => setDisputeModal({ open: false, order: null, type: 'NOT_AS_DESCRIBED', reason: '', amount: '' })}>
                取消
              </button>
              <button type="submit" className="order-action-btn primary" disabled={actionLoading}>
                {actionLoading ? '提交中...' : '提交售后'}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};
