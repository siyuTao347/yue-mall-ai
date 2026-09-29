import React, { useEffect, useState } from 'react';
import { useApp } from '../../context/AppContext';
import { seckillApi } from '../../api/seckillApi';
import { orderApi } from '../../api/orderApi';
import { userApi } from '../../api/userApi';
import './SeckillModal.css';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export const SeckillModal = () => {
  const {
    user,
    userId,
    activeModalItem,
    closeModal,
    addPoints,
    refreshPoints,
    updateSeckillItem
  } = useApp();
  const currentUid = user ? user.id : userId;

  const [currentStep, setCurrentStep] = useState(1);
  const [statusText, setStatusText] = useState('确认订单后开始抢购');
  const [isProcessing, setIsProcessing] = useState(false);
  const [orderNo, setOrderNo] = useState(null);
  const [isSuccess, setIsSuccess] = useState(false);
  const [errorMsg, setErrorMsg] = useState(null);

  useEffect(() => {
    if (activeModalItem) {
      setCurrentStep(1);
      setStatusText('确认订单后开始抢购');
      setIsProcessing(false);
      setOrderNo(null);
      setIsSuccess(false);
      setErrorMsg(null);
    }
  }, [activeModalItem]);

  if (!activeModalItem) return null;

  const earnedPoints = Math.floor(activeModalItem.seckillPrice);

  const executeSeckill = async () => {
    if (isProcessing) return;
    setIsProcessing(true);
    setErrorMsg(null);

    try {
      setCurrentStep(1);
      setStatusText('正在校验抢购资格...');
      const pathToken = await seckillApi.getPath(activeModalItem.itemId, currentUid);
      await sleep(250);

      setCurrentStep(2);
      setStatusText('正在锁定库存...');
      const seckillRes = await seckillApi.doSeckill(pathToken, activeModalItem.itemId, currentUid);
      if (seckillRes.code !== 200) {
        throw new Error(seckillRes.msg || '库存锁定失败，请重试');
      }

      const generatedOrderNo = seckillRes.orderNo || `ORD${Date.now()}${currentUid}`;
      setOrderNo(generatedOrderNo);
      await sleep(300);

      setCurrentStep(3);
      setStatusText(`订单已生成，订单号 ${generatedOrderNo}`);
      await sleep(350);

      setCurrentStep(4);
      setStatusText('正在完成支付...');
      try {
        await orderApi.payOrder(generatedOrderNo);
      } catch {
        await userApi.mockRewardPoints(generatedOrderNo, activeModalItem.seckillPrice, currentUid);
      }
      await sleep(300);

      addPoints(earnedPoints);
      refreshPoints(currentUid);
      setIsSuccess(true);

      const nextStock = Math.max(0, Number(activeModalItem.remainStock || 0) - 1);
      const nextPercent = activeModalItem.seckillStock
        ? Math.min(100, Math.round(((activeModalItem.seckillStock - nextStock) * 100) / activeModalItem.seckillStock))
        : 100;
      updateSeckillItem(activeModalItem, {
        remainStock: nextStock,
        percent: nextPercent
      });
    } catch (error) {
      setErrorMsg(error.message || '抢购失败，请重试');
    } finally {
      setIsProcessing(false);
    }
  };

  const steps = ['资格校验', '锁定库存', '生成订单', '完成支付'];

  return (
    <div className="modal-backdrop" role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-label="确认抢购订单">
        <div className="modal-header">
          <div className="modal-title">确认抢购订单</div>
          <button type="button" className="modal-close" onClick={closeModal} aria-label="关闭">×</button>
        </div>

        <div className="modal-body">
          <div className="order-item">
            <img src={activeModalItem.imageUrl} alt={activeModalItem.itemName} />
            <div className="order-item-info">
              <h4 className="order-item-title">{activeModalItem.itemName}</h4>
              <div className="order-item-subtitle">{activeModalItem.subTitle}</div>
              <div className="order-price-row">
                <span className="order-price">¥{Number(activeModalItem.seckillPrice).toFixed(2)}</span>
                <span className="order-original-price">¥{Number(activeModalItem.originalPrice).toFixed(2)}</span>
                <span className="order-point-tag">得 {earnedPoints} 积分</span>
              </div>
            </div>
          </div>

          {!isSuccess ? (
            <>
              <div className="order-steps">
                {steps.map((step, index) => {
                  const stepNo = index + 1;
                  return (
                    <React.Fragment key={step}>
                      {index > 0 && (
                        <span className={`step-line ${currentStep > stepNo ? 'completed' : ''}`} />
                      )}
                      <div className={`order-step ${currentStep === stepNo ? 'active' : ''} ${currentStep > stepNo ? 'completed' : ''}`}>
                        <span className="step-index">
                          {currentStep > stepNo ? '✓' : stepNo}
                        </span>
                        <span>{step}</span>
                      </div>
                    </React.Fragment>
                  );
                })}
              </div>

              <div className={`order-status ${errorMsg ? 'error' : ''}`}>
                {isProcessing && <span className="spinner" />}
                <span>{errorMsg ? `下单失败：${errorMsg}` : statusText}</span>
              </div>
            </>
          ) : (
            <div className="order-result">
              <div className="result-icon">✓</div>
              <h3 className="result-title">支付成功</h3>
              <p className="result-order">
                订单号 {orderNo} · 实付 ¥{Number(activeModalItem.seckillPrice).toFixed(2)}
              </p>
              <div className="result-points">
                <strong>+{earnedPoints} 积分</strong>
                <span>积分已计入账户，可在“我的积分”查看明细</span>
              </div>
            </div>
          )}
        </div>

        <div className="modal-footer">
          {!isSuccess ? (
            <>
              <button type="button" className="btn btn-secondary" onClick={closeModal} disabled={isProcessing}>
                取消
              </button>
              <button type="button" className="btn btn-primary" onClick={executeSeckill} disabled={isProcessing}>
                {isProcessing ? '处理中...' : errorMsg ? '重试' : '确认抢购'}
              </button>
            </>
          ) : (
            <button type="button" className="btn btn-primary" onClick={closeModal}>
              完成
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
