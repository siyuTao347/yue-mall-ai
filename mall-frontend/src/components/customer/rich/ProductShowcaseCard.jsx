import React from 'react';

export const ProductShowcaseCard = ({ card, onAction }) => {
  const data = card?.data || {};
  const items = data.items || [];

  if (!items.length) {
    return <div className="rich-card-fallback">{card?.fallbackText || '商品列表'}</div>;
  }

  return (
    <div className="rich-card rich-card-product-showcase">
      <div className="rich-card-header">
        <span className="rich-card-tag">推荐商品</span>
        {data.title && <span className="rich-card-title">{data.title}</span>}
      </div>
      <div className="product-showcase-grid">
        {items.slice(0, 6).map((item, idx) => (
          <div key={item.itemId || idx} className="product-showcase-item">
            {item.imageUrl ? (
              <img src={item.imageUrl} alt={item.name} className="product-thumb" />
            ) : (
              <div className="product-thumb-placeholder">📦</div>
            )}
            <div className="product-info">
              <div className="product-name" title={item.name}>{item.name}</div>
              <div className="product-meta">
                <span className="product-price">
                  {item.price?.currency === 'CNY' || !item.price?.currency ? '¥' : item.price.currency}
                  {item.price?.amount !== undefined ? item.price.amount : item.price || '0.00'}
                </span>
                {item.stock !== undefined && (
                  <span className="product-stock">库存: {item.stock}</span>
                )}
              </div>
            </div>
            <div className="product-actions">
              <button
                type="button"
                className="btn-card-action"
                onClick={() => onAction?.({
                  actionId: `ASK_PRODUCT_${item.itemId}`,
                  type: 'ASK_PRODUCT',
                  payload: { itemId: item.itemId, name: item.name },
                  label: `咨询 ${item.name}`
                })}
              >
                咨询此商品
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
