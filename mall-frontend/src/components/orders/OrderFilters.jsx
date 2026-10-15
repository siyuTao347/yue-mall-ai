import React, { useState } from 'react';
import { ORDER_TABS } from '../../utils/orderStatus';

export const OrderFilters = ({ activeTab, onTabChange, onSearch, onReset }) => {
  const [orderNoInput, setOrderNoInput] = useState('');
  const [timeRange, setTimeRange] = useState('ALL');

  const handleSubmit = (e) => {
    e.preventDefault();
    onSearch({ orderNo: orderNoInput.trim(), timeRange });
  };

  const handleReset = () => {
    setOrderNoInput('');
    setTimeRange('ALL');
    onReset();
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <div className="orders-status-tabs">
        {ORDER_TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            className={`orders-status-tab ${activeTab === tab.key ? 'active' : ''}`}
            onClick={() => onTabChange(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <form className="orders-filter-bar" onSubmit={handleSubmit}>
        <input
          type="text"
          className="orders-search-input"
          placeholder="输入精确订单号 (如 TR...)"
          value={orderNoInput}
          onChange={(e) => setOrderNoInput(e.target.value)}
        />

        <select
          className="orders-select"
          value={timeRange}
          onChange={(e) => setTimeRange(e.target.value)}
        >
          <option value="ALL">全部时间</option>
          <option value="7D">近 7 天</option>
          <option value="30D">近 30 天</option>
          <option value="90D">近 90 天</option>
        </select>

        <button type="submit" className="orders-filter-btn primary">
          查询
        </button>
        <button type="button" className="orders-filter-btn secondary" onClick={handleReset}>
          重置
        </button>
      </form>
    </div>
  );
};
