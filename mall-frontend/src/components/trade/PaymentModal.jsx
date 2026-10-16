import React, { useState, useEffect } from 'react';
import { tradeApi } from '../../api/tradeApi';
import './PaymentModal.css';

const CHANNELS = [
  {
    code: 'UNIONPAY_BANK',
    name: '中国银联 / 网银快捷',
    icon: '💳',
    color: '#004b87',
    badge: '费率 0.50%',
    settleNote: '银行工作日 D+1 清算',
    desc: '直连全国各商业银行，支持借记卡/贷记卡代扣与B2C网银'
  },
  {
    code: 'WECHAT_PAY',
    name: '微信支付',
    icon: '💬',
    color: '#07c160',
    badge: '费率 0.60%',
    settleNote: '自然日 D+1 清算',
    desc: '亿级用户移动支付生态，支持 Native 扫码与移动收银'
  },
  {
    code: 'ALIPAY',
    name: '支付宝',
    icon: '🔵',
    color: '#1677ff',
    badge: '费率 0.60%',
    settleNote: '自然日 D+1 清算',
    desc: '蚂蚁金服数字支付解决方案，支持扫码付与快捷收银台'
  },
  {
    code: 'ECNY',
    name: '数字人民币 (e-CNY)',
    icon: '🇨🇳',
    color: '#c8161d',
    badge: '0 手续费',
    settleNote: '央行实时清算',
    desc: '中国人民银行法定数字货币，零手续费、实时入账'
  }
];

export const PaymentModal = ({ order, isOpen, onClose, onSuccess }) => {
  const [selectedMethod, setSelectedMethod] = useState('WECHAT_PAY');
  const [loading, setLoading] = useState(false);
  const [activeAttempt, setActiveAttempt] = useState(null);
  const [errorMsg, setErrorMsg] = useState('');
  const [successStatus, setSuccessStatus] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setActiveAttempt(null);
      setErrorMsg('');
      setSuccessStatus(false);
      setSelectedMethod('WECHAT_PAY');
    }
  }, [isOpen]);

  if (!isOpen || !order) return null;

  const handleCreateAttempt = async () => {
    setErrorMsg('');
    setLoading(true);
    try {
      const requestKey = `REQ_${Date.now()}_${Math.random().toString(36).substring(2, 8)}`;
      const attempt = await tradeApi.payment.createAttempt(order.paymentNo, selectedMethod, requestKey);
      setActiveAttempt(attempt);
    } catch (e) {
      setErrorMsg(e.message || '创建支付尝试失败，请重试');
    } finally {
      setLoading(false);
    }
  };

  const handleMockConfirm = async (result) => {
    if (!activeAttempt) return;
    setErrorMsg('');
    setLoading(true);
    try {
      const res = await tradeApi.payment.mockConfirm(activeAttempt.attemptNo, result);
      if (result === 'SUCCESS') {
        setSuccessStatus(true);
        setTimeout(() => {
          onSuccess?.();
          onClose();
        }, 1800);
      } else {
        setErrorMsg('您已主动放弃或渠道支付失败');
        setActiveAttempt(null);
      }
    } catch (e) {
      setErrorMsg(e.message || '模拟支付处理失败');
    } finally {
      setLoading(false);
    }
  };

  const handleCloseAttempt = async () => {
    if (!activeAttempt) return;
    setLoading(true);
    try {
      await tradeApi.payment.closeAttempt(activeAttempt.attemptNo);
      setActiveAttempt(null);
    } catch (e) {
      setErrorMsg(e.message || '关闭当前尝试失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="payment-modal-backdrop" onClick={onClose}>
      <div className="payment-modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="payment-modal-header">
          <div className="payment-modal-title-wrap">
            <span className="payment-modal-shield">🛡️</span>
            <div>
              <h3 className="payment-modal-title">悦购安全收银台</h3>
              <p className="payment-modal-subtitle">订单号: {order.orderNo}</p>
            </div>
          </div>
          <button className="payment-modal-close" onClick={onClose} disabled={loading}>×</button>
        </div>

        <div className="payment-modal-body">
          <div className="payment-amount-box">
            <span className="payment-amount-label">支付应收金额</span>
            <div className="payment-amount-val">
              <span className="payment-currency">¥</span>
              <span className="payment-num">{Number(order.orderAmount).toFixed(2)}</span>
            </div>
          </div>

          {errorMsg && (
            <div className="payment-alert payment-alert-error">
              <span>⚠️ {errorMsg}</span>
            </div>
          )}

          {successStatus ? (
            <div className="payment-success-banner">
              <div className="payment-success-icon">✓</div>
              <h4>支付确认成功！</h4>
              <p>资金已安全托管至平台担保账户，系统正在为您处理发货...</p>
            </div>
          ) : !activeAttempt ? (
            <>
              <div className="payment-section-title">请选择支付方式 (支持多渠道独立清算)</div>
              <div className="payment-channels-grid">
                {CHANNELS.map((ch) => {
                  const isSelected = selectedMethod === ch.code;
                  return (
                    <div
                      key={ch.code}
                      className={`payment-channel-item ${isSelected ? 'selected' : ''}`}
                      onClick={() => !loading && setSelectedMethod(ch.code)}
                    >
                      <div className="payment-channel-icon" style={{ backgroundColor: `${ch.color}15`, color: ch.color }}>
                        {ch.icon}
                      </div>
                      <div className="payment-channel-info">
                        <div className="payment-channel-name-row">
                          <span className="payment-channel-name">{ch.name}</span>
                          <span className="payment-channel-badge" style={{ color: ch.color, borderColor: `${ch.color}40`, backgroundColor: `${ch.color}10` }}>
                            {ch.badge}
                          </span>
                        </div>
                        <div className="payment-channel-desc">{ch.desc}</div>
                        <div className="payment-channel-settle">{ch.settleNote}</div>
                      </div>
                      <div className={`payment-radio-circle ${isSelected ? 'checked' : ''}`} />
                    </div>
                  );
                })}
              </div>

              <button
                type="button"
                className="payment-action-btn primary"
                onClick={handleCreateAttempt}
                disabled={loading}
              >
                {loading ? '正在调起渠道收银台...' : `确认并使用 ${CHANNELS.find(c => c.code === selectedMethod)?.name} 支付`}
              </button>
            </>
          ) : (
            <div className="payment-cashier-active">
              <div className="payment-active-badge">
                <span className="pulse-dot" /> 渠道收银台处理中 (尝试号: {activeAttempt.attemptNo})
              </div>

              <div className="mock-cashier-box">
                <div className="mock-cashier-screen">
                  <div className="mock-qr-wrap">
                    <div className="mock-qr-code">
                      <div className="mock-qr-pattern" />
                      <span className="mock-qr-center-icon">
                        {CHANNELS.find(c => c.code === activeAttempt.paymentMethod)?.icon || '💳'}
                      </span>
                    </div>
                    <p className="mock-qr-hint">模拟扫描上方二维码或点击下方通道完成支付</p>
                  </div>

                  <div className="mock-cashier-meta">
                    <div><strong>渠道标识:</strong> {activeAttempt.paymentMethod}</div>
                    <div><strong>外部流水:</strong> {activeAttempt.attemptNo}</div>
                    <div><strong>币种:</strong> {activeAttempt.currency || 'CNY'}</div>
                  </div>
                </div>

                <div className="mock-cashier-actions">
                  <button
                    type="button"
                    className="payment-action-btn success"
                    onClick={() => handleMockConfirm('SUCCESS')}
                    disabled={loading}
                  >
                    {loading ? '通信中...' : '模拟付款成功 (触发渠道网关回调)'}
                  </button>
                  <button
                    type="button"
                    className="payment-action-btn warning"
                    onClick={() => handleMockConfirm('FAIL')}
                    disabled={loading}
                  >
                    模拟买家付款失败 / 取消
                  </button>
                  <button
                    type="button"
                    className="payment-action-btn secondary"
                    onClick={handleCloseAttempt}
                    disabled={loading}
                  >
                    关闭并更换其他支付方式
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
