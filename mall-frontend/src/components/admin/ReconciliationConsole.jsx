import React, { useState, useEffect, useCallback } from 'react';
import { tradeApi } from '../../api/tradeApi';
import './ReconciliationConsole.css';

const TABS = [
  { key: 'runs', label: '对账批次与监控' },
  { key: 'diffs', label: '差错治理工单' },
  { key: 'settlements', label: '渠道结算与回执' },
  { key: 'faults', label: '故障演练预案' }
];

export const ReconciliationConsole = () => {
  const [activeTab, setActiveTab] = useState('runs');
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [successMsg, setSuccessMsg] = useState('');

  // Runs
  const [runs, setRuns] = useState([]);
  const [triggerChannel, setTriggerChannel] = useState('ALL');
  const [triggerDate, setTriggerDate] = useState(
    new Date(Date.now() - 86400000).toISOString().split('T')[0]
  );
  const [selectedRunDetail, setSelectedRunDetail] = useState(null);

  // Diffs
  const [diffs, setDiffs] = useState([]);
  const [diffFilter, setDiffFilter] = useState({ status: '', diffType: '' });
  const [actionModal, setActionModal] = useState({ open: false, diff: null, action: 'CLAIM', reason: '' });

  // Settlements
  const [settlements, setSettlements] = useState([]);
  const [cashReceipts, setCashReceipts] = useState([]);

  // Fault plans
  const [faultPlans, setFaultPlans] = useState([]);

  const flashMessage = (msg, isErr = false) => {
    if (isErr) {
      setErrorMsg(msg);
      setTimeout(() => setErrorMsg(''), 4000);
    } else {
      setSuccessMsg(msg);
      setTimeout(() => setSuccessMsg(''), 4000);
    }
  };

  const fetchRuns = useCallback(async () => {
    setLoading(true);
    try {
      const res = await tradeApi.reconciliation.listRuns({ page: 1, size: 50 });
      setRuns(res.list || []);
    } catch (e) {
      flashMessage(e.message || '获取对账批次失败', true);
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchDiffs = useCallback(async () => {
    setLoading(true);
    try {
      const res = await tradeApi.reconciliation.listDiffs({
        status: diffFilter.status,
        diffType: diffFilter.diffType,
        page: 1,
        size: 50
      });
      setDiffs(res.list || []);
    } catch (e) {
      flashMessage(e.message || '获取差异列表失败', true);
    } finally {
      setLoading(false);
    }
  }, [diffFilter]);

  const fetchSettlements = useCallback(async () => {
    setLoading(true);
    try {
      const res = await tradeApi.reconciliation.listSettlements({ page: 1, size: 50 });
      setSettlements(res.list || []);
      setCashReceipts(res.recentReceipts || []);
    } catch (e) {
      flashMessage(e.message || '获取结算数据失败', true);
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchFaultPlans = useCallback(async () => {
    setLoading(true);
    try {
      const res = await tradeApi.reconciliation.listFaultPlans();
      setFaultPlans(res || []);
    } catch (e) {
      flashMessage(e.message || '获取故障预案失败', true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (activeTab === 'runs') fetchRuns();
    if (activeTab === 'diffs') fetchDiffs();
    if (activeTab === 'settlements') fetchSettlements();
    if (activeTab === 'faults') fetchFaultPlans();
  }, [activeTab, fetchRuns, fetchDiffs, fetchSettlements, fetchFaultPlans]);

  const handleTriggerRun = async () => {
    setLoading(true);
    try {
      const res = await tradeApi.reconciliation.triggerRun(triggerChannel, triggerDate);
      flashMessage(`对账批次执行完成: 处理了 ${res?.length || 0} 个渠道`);
      fetchRuns();
    } catch (e) {
      flashMessage(e.message || '触发对账异常', true);
    } finally {
      setLoading(false);
    }
  };

  const handleViewRun = async (runNo) => {
    setLoading(true);
    try {
      const detail = await tradeApi.reconciliation.runDetail(runNo);
      setSelectedRunDetail(detail);
    } catch (e) {
      flashMessage(e.message || '获取批次详情失败', true);
    } finally {
      setLoading(false);
    }
  };

  const handleExecuteDiffAction = async (e) => {
    e.preventDefault();
    if (!actionModal.diff) return;
    setLoading(true);
    try {
      await tradeApi.reconciliation.diffAction(
        actionModal.diff.diffNo,
        actionModal.action,
        actionModal.reason
      );
      flashMessage(`差错操作 [${actionModal.action}] 执行成功`);
      setActionModal({ open: false, diff: null, action: 'CLAIM', reason: '' });
      fetchDiffs();
    } catch (e) {
      flashMessage(e.message || '执行差错处理失败', true);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="recon-console-root">
      <div className="recon-header-bar">
        <div>
          <h3 className="recon-title">多渠道资金与交易对账中心</h3>
          <p className="recon-sub">
            支持银联/微信/支付宝/数币全渠道 L1交易双向对账、L2费用资金核对、差错自动补单与人工治理
          </p>
        </div>
        <div className="recon-tab-group">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              className={`recon-nav-btn ${activeTab === t.key ? 'active' : ''}`}
              onClick={() => setActiveTab(t.key)}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {errorMsg && <div className="recon-toast error">⚠️ {errorMsg}</div>}
      {successMsg && <div className="recon-toast success">✓ {successMsg}</div>}

      {activeTab === 'runs' && (
        <div className="recon-panel">
          <div className="recon-toolbar">
            <div className="recon-toolbar-controls">
              <label>业务日：</label>
              <input
                type="date"
                className="recon-input"
                value={triggerDate}
                onChange={(e) => setTriggerDate(e.target.value)}
              />
              <label>渠道：</label>
              <select
                className="recon-select"
                value={triggerChannel}
                onChange={(e) => setTriggerChannel(e.target.value)}
              >
                <option value="ALL">全部渠道 (ALL)</option>
                <option value="UNIONPAY_BANK">中国银联 (UNIONPAY_BANK)</option>
                <option value="WECHAT_PAY">微信支付 (WECHAT_PAY)</option>
                <option value="ALIPAY">支付宝 (ALIPAY)</option>
                <option value="ECNY">数字人民币 (ECNY)</option>
              </select>
              <button
                type="button"
                className="recon-primary-btn"
                onClick={handleTriggerRun}
                disabled={loading}
              >
                {loading ? '对账中...' : '立即生成账单并执行对账'}
              </button>
            </div>
            <button type="button" className="recon-ghost-btn" onClick={fetchRuns}>刷新列表</button>
          </div>

          <table className="recon-table">
            <thead>
              <tr>
                <th>批次号 (Run No)</th>
                <th>任务号</th>
                <th>本地笔数</th>
                <th>渠道笔数</th>
                <th>平账匹配</th>
                <th>差错笔数</th>
                <th>状态</th>
                <th>执行时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {runs.length === 0 ? (
                <tr><td colSpan="9" className="recon-empty">暂无对账批次记录，点击上方按钮触发执行</td></tr>
              ) : (
                runs.map((r) => (
                  <tr key={r.id}>
                    <td className="code-font">{r.runNo}</td>
                    <td className="code-font">{r.taskNo}</td>
                    <td>{r.localCount || 0} 笔</td>
                    <td>{r.channelCount || 0} 笔</td>
                    <td className="success-val">{r.matchedCount || 0} 笔</td>
                    <td className={r.diffCount > 0 ? 'danger-val' : ''}>{r.diffCount || 0} 笔</td>
                    <td><span className={`recon-badge ${r.executionStatus}`}>{r.executionStatus}</span></td>
                    <td>{r.completedAt || r.startedAt || '-'}</td>
                    <td>
                      <button
                        type="button"
                        className="recon-link-btn"
                        onClick={() => handleViewRun(r.runNo)}
                      >
                        详情
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {activeTab === 'diffs' && (
        <div className="recon-panel">
          <div className="recon-toolbar">
            <div className="recon-toolbar-controls">
              <label>差异类型：</label>
              <select
                className="recon-select"
                value={diffFilter.diffType}
                onChange={(e) => setDiffFilter({ ...diffFilter, diffType: e.target.value })}
              >
                <option value="">全部类型</option>
                <option value="AMOUNT_MISMATCH">金额不一致 (AMOUNT_MISMATCH)</option>
                <option value="PLATFORM_ONLY_PAYMENT">单边账：本地有渠道无</option>
                <option value="CHANNEL_ONLY_PAYMENT">单边账：渠道有本地无</option>
              </select>
              <label>状态：</label>
              <select
                className="recon-select"
                value={diffFilter.status}
                onChange={(e) => setDiffFilter({ ...diffFilter, status: e.target.value })}
              >
                <option value="">全部状态</option>
                <option value="OPEN">待处理 (OPEN)</option>
                <option value="CLAIMED">处理中 (CLAIMED)</option>
                <option value="REPAIRED">系统已补单 (REPAIRED)</option>
                <option value="RESOLVED">人工已结案 (RESOLVED)</option>
                <option value="WAIVED">已豁免 (WAIVED)</option>
              </select>
            </div>
            <button type="button" className="recon-ghost-btn" onClick={fetchDiffs}>刷新</button>
          </div>

          <table className="recon-table">
            <thead>
              <tr>
                <th>差错工单号</th>
                <th>渠道配置</th>
                <th>差异类型</th>
                <th>本地金额</th>
                <th>渠道金额</th>
                <th>关联订单/尝试</th>
                <th>状态</th>
                <th>操作治理</th>
              </tr>
            </thead>
            <tbody>
              {diffs.length === 0 ? (
                <tr><td colSpan="8" className="recon-empty">暂无差错单据</td></tr>
              ) : (
                diffs.map((d) => (
                  <tr key={d.id}>
                    <td className="code-font">{d.diffNo}</td>
                    <td>{d.profileId}</td>
                    <td><span className="diff-type-badge">{d.diffType}</span></td>
                    <td>¥{d.localAmount || '0.00'}</td>
                    <td>¥{d.channelAmount || '0.00'}</td>
                    <td className="code-font">{d.attemptNo || d.orderNo || '-'}</td>
                    <td><span className={`recon-badge ${d.status}`}>{d.status}</span></td>
                    <td>
                      <div className="recon-action-btns">
                        <button
                          type="button"
                          className="action-btn claim"
                          onClick={() => setActionModal({ open: true, diff: d, action: 'CLAIM', reason: '' })}
                        >
                          认领
                        </button>
                        <button
                          type="button"
                          className="action-btn repair"
                          onClick={() => setActionModal({ open: true, diff: d, action: 'REPAIR', reason: '' })}
                        >
                          补单/修复
                        </button>
                        <button
                          type="button"
                          className="action-btn query"
                          onClick={() => setActionModal({ open: true, diff: d, action: 'QUERY', reason: '' })}
                        >
                          查单
                        </button>
                        <button
                          type="button"
                          className="action-btn waive"
                          onClick={() => setActionModal({ open: true, diff: d, action: 'WAIVE', reason: '' })}
                        >
                          豁免
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {activeTab === 'settlements' && (
        <div className="recon-panel">
          <div className="recon-sub-title">外部渠道结算单 (预期清算到账流水)</div>
          <table className="recon-table">
            <thead>
              <tr>
                <th>结算流水单号</th>
                <th>渠道商户配置</th>
                <th>业务日</th>
                <th>交易总额</th>
                <th>退款总额</th>
                <th>渠道手续费</th>
                <th>净结算到账额</th>
                <th>预期到账时间</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              {settlements.length === 0 ? (
                <tr><td colSpan="9" className="recon-empty">暂无渠道结算单</td></tr>
              ) : (
                settlements.map((s) => (
                  <tr key={s.id}>
                    <td className="code-font">{s.settlementNo}</td>
                    <td>{s.profileId}</td>
                    <td>{s.businessDate}</td>
                    <td>¥{s.grossAmount}</td>
                    <td>¥{s.refundAmount}</td>
                    <td className="danger-val">-¥{s.feeAmount}</td>
                    <td className="bold-val">¥{s.netAmount}</td>
                    <td>{s.expectedSettledAt}</td>
                    <td><span className={`recon-badge ${s.status}`}>{s.status}</span></td>
                  </tr>
                ))
              )}
            </tbody>
          </table>

          <div className="recon-sub-title" style={{ marginTop: '24px' }}>银行入账真实回执 (Cash Receipts)</div>
          <table className="recon-table">
            <thead>
              <tr>
                <th>银行回执号</th>
                <th>资金账户</th>
                <th>实际入账金额</th>
                <th>币种</th>
                <th>入账时间</th>
              </tr>
            </thead>
            <tbody>
              {cashReceipts.length === 0 ? (
                <tr><td colSpan="5" className="recon-empty">暂无资金入账回执</td></tr>
              ) : (
                cashReceipts.map((r) => (
                  <tr key={r.id}>
                    <td className="code-font">{r.receiptNo}</td>
                    <td>{r.accountId}</td>
                    <td className="success-val bold-val">+¥{r.receivedAmount}</td>
                    <td>{r.currency}</td>
                    <td>{r.postedAt || r.createdTime}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {activeTab === 'faults' && (
        <div className="recon-panel">
          <div className="recon-sub-title">模拟渠道故障注入与混沌演练预案</div>
          <p className="recon-desc">
            用于验证对账引擎在遇到通道故障（单边账、金额篡改、丢单、账单延迟）时的自动告警、工单生成与资金自愈能力。
          </p>
          <div className="fault-grid">
            {faultPlans.length === 0 ? (
              <div className="recon-empty">暂无预案记录</div>
            ) : (
              faultPlans.map((p) => (
                <div key={p.id} className="fault-card">
                  <div className="fault-card-header">
                    <span className="fault-scenario">{p.scenarioKey}</span>
                    <span className={`recon-badge ${p.enabled === 1 ? 'SUCCESS' : 'CLOSED'}`}>
                      {p.enabled === 1 ? '已启用' : '已禁用'}
                    </span>
                  </div>
                  <div className="fault-meta">目标尝试号: {p.targetAttemptNo || '全局随机'}</div>
                  <pre className="fault-params">{p.paramsJson}</pre>
                </div>
              ))
            )}
          </div>
        </div>
      )}

      {/* Action Dialog */}
      {actionModal.open && (
        <div className="recon-modal-backdrop" onClick={() => setActionModal({ open: false, diff: null, action: 'CLAIM', reason: '' })}>
          <div className="recon-modal-card" onClick={(e) => e.stopPropagation()}>
            <h4>执行差异处理: {actionModal.action}</h4>
            <p>工单号: <strong>{actionModal.diff?.diffNo}</strong> ({actionModal.diff?.diffType})</p>
            <form onSubmit={handleExecuteDiffAction}>
              <div className="form-group">
                <label>操作说明 / 修复原因：</label>
                <textarea
                  className="recon-textarea"
                  rows="3"
                  required
                  placeholder="请输入核实依据或处理原因..."
                  value={actionModal.reason}
                  onChange={(e) => setActionModal({ ...actionModal, reason: e.target.value })}
                />
              </div>
              <div className="modal-btn-row">
                <button type="button" className="recon-ghost-btn" onClick={() => setActionModal({ open: false, diff: null, action: 'CLAIM', reason: '' })}>
                  取消
                </button>
                <button type="submit" className="recon-primary-btn" disabled={loading}>
                  {loading ? '处理中...' : '确认执行'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Run Detail Dialog */}
      {selectedRunDetail && (
        <div className="recon-modal-backdrop" onClick={() => setSelectedRunDetail(null)}>
          <div className="recon-modal-card wide" onClick={(e) => e.stopPropagation()}>
            <h4>对账批次明细: {selectedRunDetail.run?.runNo}</h4>
            <div className="run-stats-grid">
              <div className="stat-card">
                <span>本地总笔数</span>
                <strong>{selectedRunDetail.run?.localCount || 0}</strong>
              </div>
              <div className="stat-card">
                <span>渠道总笔数</span>
                <strong>{selectedRunDetail.run?.channelCount || 0}</strong>
              </div>
              <div className="stat-card success">
                <span>完全对齐 (Matched)</span>
                <strong>{selectedRunDetail.run?.matchedCount || 0}</strong>
              </div>
              <div className="stat-card danger">
                <span>不平差异 (Diffs)</span>
                <strong>{selectedRunDetail.run?.diffCount || 0}</strong>
              </div>
            </div>

            <div className="recon-sub-title" style={{ marginTop: '16px' }}>匹配成功的双向明细样例</div>
            <table className="recon-table">
              <thead>
                <tr>
                  <th>本地尝试流水</th>
                  <th>渠道交易流水</th>
                  <th>金额</th>
                  <th>手续费</th>
                  <th>匹配结果</th>
                </tr>
              </thead>
              <tbody>
                {(!selectedRunDetail.matches || selectedRunDetail.matches.length === 0) ? (
                  <tr><td colSpan="5" className="recon-empty">无匹配明细</td></tr>
                ) : (
                  selectedRunDetail.matches.slice(0, 10).map((m) => (
                    <tr key={m.id}>
                      <td className="code-font">{m.attemptNo}</td>
                      <td className="code-font">{m.channelTradeNo}</td>
                      <td className="bold-val">¥{m.matchedAmount}</td>
                      <td>¥{m.matchedFee}</td>
                      <td><span className="recon-badge SUCCESS">{m.matchStatus}</span></td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>

            <div className="modal-btn-row">
              <button type="button" className="recon-ghost-btn" onClick={() => setSelectedRunDetail(null)}>关闭</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
