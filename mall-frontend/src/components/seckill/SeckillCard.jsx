import React from 'react';
import { useApp } from '../../context/AppContext';
import { showToast } from '../../utils/feedback';
import './SeckillCard.css';

export const SeckillCard = ({ item, session }) => {
  const { openModal, requireAuth } = useApp();
  const earnedPoints = Math.floor(item.seckillPrice);
  const isSessionEnded = session?.status === 2;
  const isSessionUpcoming = session?.status === 0;
  const isSoldOut = item.isSoldOut || item.remainStock <= 0;

  const handleAction = () => {
    if (isSessionUpcoming) {
      showToast('info', '已设置提醒', `${session?.sessionName || '本场活动'} 开抢前会提醒你`);
      return;
    }
    if (isSessionEnded || isSoldOut) return;
    requireAuth(() => openModal(item));
  };

  return (
    <article className="product-card">
      <div className="card-image">
        <div className="card-flags">
          <span className="flag stock">剩余 {item.remainStock} 件</span>
          <span className="flag limit">限购 {item.limitPerUser || 1} 件</span>
        </div>
        <img src={item.imageUrl} alt={item.itemName} loading="lazy" />
      </div>

      <div className="card-body">
        <h3 className="card-title">{item.itemName}</h3>
        <div className="card-subtitle">{item.subTitle || '官方正品保障'}</div>

        <div className="card-price-row">
          <span className="price-symbol">¥</span>
          <span className="price-current">{Number(item.seckillPrice).toFixed(2)}</span>
          <span className="price-original">¥{Number(item.originalPrice).toFixed(2)}</span>
        </div>

        <span className="point-tag">得 {earnedPoints} 积分</span>

        <div className="stock-panel">
          <div className="stock-labels">
            <span>已抢 <strong>{item.percent}%</strong></span>
            <span>{item.seckillStock} 件总量</span>
          </div>
          <div className="stock-track">
            <div className="stock-fill" style={{ width: `${item.percent}%` }} />
          </div>
        </div>

        {isSessionEnded ? (
          <button type="button" className="card-action" disabled>已结束</button>
        ) : isSoldOut ? (
          <button type="button" className="card-action" disabled>已抢完</button>
        ) : isSessionUpcoming ? (
          <button type="button" className="card-action upcoming" onClick={handleAction}>开抢提醒</button>
        ) : (
          <button type="button" className="card-action" onClick={handleAction}>立即抢购</button>
        )}
      </div>
    </article>
  );
};
