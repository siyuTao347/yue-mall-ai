import { useCallback, useEffect, useState } from 'react';
import { useApp } from '../../context/AppContext';
import { tradeApi } from '../../api/tradeApi';
import { showToast } from '../../utils/feedback';
import '../trade/TradeWorkbench.css';
import './AdminConsole.css';

const pageRecords = result => (result?.records ? result.records : result || []);
const money = value => `¥${Number(value || 0).toFixed(2)}`;

/** 管理员运营审核：商家/商品准入、售后仲裁、提现审批。 */
export const AdminOperations = () => {
  const { user } = useApp();
  const [merchants, setMerchants] = useState([]);
  const [pendingItems, setPendingItems] = useState([]);
  const [pendingDisputes, setPendingDisputes] = useState([]);
  const [pendingWithdrawals, setPendingWithdrawals] = useState([]);
  const [selectedDispute, setSelectedDispute] = useState(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    if (!user || user.role !== 'ADMIN') {
      setMerchants([]);
      setPendingItems([]);
      setPendingDisputes([]);
      setPendingWithdrawals([]);
      return;
    }
    setLoading(true);
    try {
      const [merchantList, itemList, disputeList, withdrawalList] = await Promise.all([
        tradeApi.merchant.list().catch(() => []),
        tradeApi.item.pendingAudit().catch(() => []),
        tradeApi.dispute.pending().catch(() => []),
        tradeApi.withdraw.pending().catch(() => [])
      ]);
      setMerchants(pageRecords(merchantList));
      setPendingItems(pageRecords(itemList));
      setPendingDisputes(pageRecords(disputeList));
      setPendingWithdrawals(pageRecords(withdrawalList));
      setError('');
    } catch (e) {
      setError(e.message || '运营审核数据加载失败');
    } finally {
      setLoading(false);
    }
  }, [user]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const runAction = useCallback(async (key, action, successMessage) => {
    setBusy(key);
    setError('');
    try {
      const result = await action();
      showToast('success', '操作成功', successMessage);
      await refresh();
      return result;
    } catch (e) {
      setError(e.message || '操作失败');
      showToast('error', '操作失败', e.message || '请稍后重试');
      return false;
    } finally {
      setBusy('');
    }
  }, [refresh]);

  const auditMerchant = (id, action) => runAction(
    `merchant-audit-${id}`,
    () => tradeApi.merchant.audit(id, action),
    '商家审核完成'
  );

  const auditItem = (id, action) => runAction(
    `item-audit-${id}`,
    () => tradeApi.item.audit(id, action),
    '商品审核完成'
  );

  const auditWithdraw = (withdrawNo, approved) => runAction(
    `withdraw-audit-${withdrawNo}`,
    () => tradeApi.withdraw.audit(withdrawNo, approved),
    '提现审核完成'
  );

  const viewDispute = async dispute => {
    const detail = await runAction(
      `dispute-view-${dispute.disputeNo}`,
      () => tradeApi.dispute.evidence(dispute.disputeNo),
      '证据已加载'
    );
    if (detail) setSelectedDispute(detail);
  };

  const arbitrate = (dispute, result) => runAction(
    `arbitrate-${dispute.disputeNo}`,
    () => tradeApi.dispute.arbitrate(dispute.disputeNo, result, '管理员根据平台证据完成仲裁'),
    '仲裁完成'
  );

  const pendingMerchants = merchants.filter(item => item.status === 'SUBMITTED');
  const evidenceList = Array.isArray(selectedDispute?.evidence) ? selectedDispute.evidence : [];

  return (
    <div className="admin-operations">
      {error ? <div className="admin-inline-error">{error}</div> : null}
      {loading ? <div className="admin-loading">正在加载运营审核数据…</div> : null}

      <div className="trade-grid">
        <section className="trade-panel">
          <div className="admin-panel-head">
            <h3 className="trade-panel-title">商家与商品审核</h3>
            <button type="button" className="admin-button" onClick={refresh} disabled={loading}>刷新</button>
          </div>
          <p className="trade-panel-desc">管理员准入商家，商品审核通过后进入买家列表</p>
          <div className="trade-list">
            {pendingMerchants.map(item => (
              <div className="trade-row" key={`m-${item.id}`}>
                <div className="trade-row-main">
                  <span className="trade-row-title">{item.merchantName}</span>
                  <span className="trade-status pending">待审核</span>
                </div>
                <div className="trade-actions">
                  <button type="button" className="trade-action primary"
                    disabled={busy === `merchant-audit-${item.id}`} onClick={() => auditMerchant(item.id, 'APPROVE')}>
                    通过
                  </button>
                  <button type="button" className="trade-action"
                    disabled={busy === `merchant-audit-${item.id}`} onClick={() => auditMerchant(item.id, 'REJECT')}>
                    拒绝
                  </button>
                </div>
              </div>
            ))}
            {pendingItems.map(item => (
              <div className="trade-row" key={`i-${item.id}`}>
                <div className="trade-row-main">
                  <span className="trade-row-title">{item.itemName}</span>
                  <span className="trade-status pending">待审核</span>
                </div>
                <div className="trade-actions">
                  <button type="button" className="trade-action primary"
                    disabled={busy === `item-audit-${item.id}`} onClick={() => auditItem(item.id, 'APPROVE')}>
                    上架
                  </button>
                  <button type="button" className="trade-action"
                    disabled={busy === `item-audit-${item.id}`} onClick={() => auditItem(item.id, 'REJECT')}>
                    驳回
                  </button>
                </div>
              </div>
            ))}
            {!loading && pendingMerchants.length === 0 && pendingItems.length === 0 ? (
              <div className="trade-empty">暂无待审核申请</div>
            ) : null}
          </div>
        </section>

        <section className="trade-panel">
          <div className="admin-panel-head">
            <h3 className="trade-panel-title">售后与提现处理</h3>
            <span className="admin-panel-count">
              仲裁 {pendingDisputes.length} · 提现 {pendingWithdrawals.length}
            </span>
          </div>
          <p className="trade-panel-desc">阶段一仲裁支持全额退款或全额放款</p>
          <div className="trade-list">
            {pendingDisputes.map(dispute => (
              <div className="trade-row" key={`d-${dispute.disputeNo}`}>
                <div className="trade-row-main">
                  <span className="trade-row-title">{dispute.disputeNo}</span>
                  <span className="trade-status pending">仲裁中</span>
                </div>
                <div className="trade-row-meta">
                  订单 {dispute.orderNo} · 申请退款 {money(dispute.proposedRefundAmount)}
                </div>
                <div className="trade-actions">
                  <button type="button" className="trade-action"
                    disabled={busy === `dispute-view-${dispute.disputeNo}`}
                    onClick={() => viewDispute(dispute)}>查看证据</button>
                  <button type="button" className="trade-action danger"
                    disabled={busy === `arbitrate-${dispute.disputeNo}`}
                    onClick={() => arbitrate(dispute, 'REFUND_ALL')}>全额退款</button>
                  <button type="button" className="trade-action primary"
                    disabled={busy === `arbitrate-${dispute.disputeNo}`}
                    onClick={() => arbitrate(dispute, 'RELEASE_ALL')}>全额放款</button>
                </div>
              </div>
            ))}
            {pendingWithdrawals.map(item => (
              <div className="trade-row" key={`w-${item.withdrawNo || item.id}`}>
                <div className="trade-row-main">
                  <span className="trade-row-title">{item.withdrawNo}</span>
                  <span className="trade-status pending">待审核</span>
                </div>
                <div className="trade-actions">
                  <button type="button" className="trade-action primary"
                    disabled={busy === `withdraw-audit-${item.withdrawNo}`}
                    onClick={() => auditWithdraw(item.withdrawNo, true)}>通过打款</button>
                  <button type="button" className="trade-action"
                    disabled={busy === `withdraw-audit-${item.withdrawNo}`}
                    onClick={() => auditWithdraw(item.withdrawNo, false)}>拒绝</button>
                </div>
              </div>
            ))}
            {!loading && pendingDisputes.length === 0 && pendingWithdrawals.length === 0 ? (
              <div className="trade-empty">暂无待处理事项</div>
            ) : null}
          </div>
        </section>
      </div>

      {selectedDispute ? (
        <section className="trade-panel admin-evidence-panel">
          <div className="admin-panel-head">
            <h3 className="trade-panel-title">仲裁证据：{selectedDispute.dispute?.disputeNo}</h3>
            <button type="button" className="admin-button" onClick={() => setSelectedDispute(null)}>收起</button>
          </div>
          <div className="admin-evidence-list">
            {evidenceList.length === 0 ? (
              <div className="trade-empty">该售后暂无文本证据</div>
            ) : evidenceList.map((evidence, index) => (
              <article className="admin-evidence" key={evidence.id || `${evidence.evidenceType || 'TEXT'}-${index}`}>
                <header>
                  <strong>{evidence.evidenceType || 'TEXT'}</strong>
                  <span>{evidence.createdTime || evidence.createdAt || '-'}</span>
                </header>
                <p>{evidence.content || evidence.description || JSON.stringify(evidence)}</p>
              </article>
            ))}
          </div>
        </section>
      ) : null}
    </div>
  );
};
