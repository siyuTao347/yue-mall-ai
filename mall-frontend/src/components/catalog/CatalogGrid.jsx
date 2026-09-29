import React, { useState } from 'react';
import { useApp } from '../../context/AppContext';
import { orderApi } from '../../api/orderApi';
import { userApi } from '../../api/userApi';
import { ConfirmDialog } from '../ui/UiFeedback';
import { showToast } from '../../utils/feedback';
import './CatalogGrid.css';

export const CatalogGrid = ({ items = [], searchKeyword = '' }) => {
  const { userId, addPoints, refreshPoints, requireAuth } = useApp();
  const [selectedCategory, setSelectedCategory] = useState('全部');
  const [orderingId, setOrderingId] = useState(null);
  const [pendingItem, setPendingItem] = useState(null);

  const categories = ['全部', '步枪', '狙击枪', '匕首', '手套'];
  const keyword = searchKeyword.trim().toLowerCase();
  const filteredItems = items.filter(item => {
    const matchesCategory = selectedCategory === '全部'
      || item.category === selectedCategory
      || item.itemName.includes(selectedCategory);
    if (!keyword) return matchesCategory;
    const haystack = `${item.itemName || ''} ${item.subTitle || ''} ${item.category || ''}`.toLowerCase();
    return matchesCategory && haystack.includes(keyword);
  });

  const submitOrder = async () => {
    const item = pendingItem;
    if (!item) return;
    setPendingItem(null);
    setOrderingId(item.id);

    requireAuth(async currentUser => {
      const currentUid = currentUser?.id || userId;
      const earnedPoints = Math.floor(item.price);

      try {
        const orderNo = await orderApi.createTccOrder(item.id, 1, false);
        await orderApi.payOrder(orderNo);
        await userApi.mockRewardPoints(orderNo, item.price, currentUid);

        addPoints(earnedPoints);
        refreshPoints(currentUid);
        showToast(
          'success',
          '下单成功',
          `订单号 ${orderNo}，实付 ¥${Number(item.price).toFixed(2)}，得 ${earnedPoints} 积分`
        );
      } catch (error) {
        showToast('error', '下单失败', error.message || '请稍后重试');
      } finally {
        setOrderingId(null);
      }
    });
  };

  return (
    <section className="catalog-section" id="catalogSection">
      <div className="section-header">
        <div className="section-title-wrap">
          <h2 className="section-title">饰品商城</h2>
          <p className="section-desc">现货商品，下单后立即支付</p>
        </div>
        <div className="category-filter">
          {categories.map(category => (
            <button
              key={category}
              type="button"
              className={`category-button ${selectedCategory === category ? 'active' : ''}`}
              onClick={() => setSelectedCategory(category)}
            >
              {category}
            </button>
          ))}
        </div>
      </div>

      {filteredItems.length === 0 ? (
        <div className="empty-state">没有找到相关饰品</div>
      ) : (
        <div className="catalog-grid">
          {filteredItems.map(item => {
            const earned = Math.floor(item.price);
            const isBuying = orderingId === item.id;

            return (
              <article key={item.id} className="product-card catalog-card">
                <div className="card-image">
                  <div className="card-flags">
                    <span className="flag stock">库存 {item.stock || 99} 件</span>
                  </div>
                  <img src={item.imageUrl} alt={item.itemName} loading="lazy" />
                </div>

                <div className="card-body">
                  <h3 className="card-title">{item.itemName}</h3>
                  <div className="card-subtitle">{item.subTitle || '官方正品保障'}</div>

                  <div className="card-price-row">
                    <span className="price-symbol">¥</span>
                    <span className="price-current">{Number(item.price).toFixed(2)}</span>
                  </div>

                  <span className="point-tag">得 {earned} 积分</span>

                  <button
                    type="button"
                    className="card-action"
                    onClick={() => requireAuth(() => setPendingItem(item))}
                    disabled={isBuying}
                  >
                    {isBuying ? '提交中...' : '立即购买'}
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}

      <ConfirmDialog
        open={Boolean(pendingItem)}
        title="确认购买"
        message={pendingItem ? (
          <>
            是否购买 {pendingItem.itemName}？实付 ¥{Number(pendingItem.price).toFixed(2)}，
            支付成功后得 {Math.floor(pendingItem.price)} 积分。
          </>
        ) : null}
        confirmText="确认支付"
        onConfirm={submitOrder}
        onCancel={() => setPendingItem(null)}
      />
    </section>
  );
};
