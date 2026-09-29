import React, { useState, useEffect, useCallback } from 'react';
import { useApp } from '../../context/AppContext';
import { userApi } from '../../api/userApi';
import './PointsDrawer.css';

export const PointsDrawer = () => {
  const { user, userId, userPoints, historyPoints, isDrawerOpen, closeDrawer } = useApp();
  const currentUid = user ? user.id : userId;
  const [records, setRecords] = useState([]);
  const [loading, setLoading] = useState(false);

  const fetchRecords = useCallback(async () => {
    setLoading(true);
    try {
      const data = await userApi.getPointRecords(currentUid, 20);
      setRecords(data);
    } catch (e) {
      console.warn('获取积分流水异常', e);
    } finally {
      setLoading(false);
    }
  }, [currentUid]);

  useEffect(() => {
    if (isDrawerOpen) {
      fetchRecords();
    }
  }, [isDrawerOpen, fetchRecords]);

  return (
    <>
      <div
        className={`drawer-backdrop ${isDrawerOpen ? 'active' : ''}`}
        onClick={closeDrawer}
      />
      <aside className={`points-drawer ${isDrawerOpen ? 'active' : ''}`}>
        <div className="drawer-header">
          <div className="drawer-title">我的积分</div>
          <button type="button" className="drawer-close" onClick={closeDrawer} aria-label="关闭">×</button>
        </div>

        <div className="drawer-body">
          <div className="points-summary">
            <div className="summary-row">
              <div className="summary-cell">
                <span className="summary-label">可用积分</span>
                <span className="summary-value gold">{userPoints.toLocaleString()}</span>
              </div>
              <div className="summary-divider" />
              <div className="summary-cell">
                <span className="summary-label">累计获得</span>
                <span className="summary-value">{historyPoints.toLocaleString()}</span>
              </div>
            </div>
            <div className="summary-tip">消费 1 元得 1 积分，支付成功后自动入账。</div>
          </div>

          <div className="records-header">
            <span className="records-title">积分明细</span>
            <button type="button" className="refresh-button" onClick={fetchRecords} disabled={loading}>
              {loading ? '刷新中...' : '刷新'}
            </button>
          </div>

          <div className="records-list">
            {records.map((r, idx) => {
              const isPlus = r.changePoints >= 0;
              return (
                <div key={r.id || idx} className="record-item">
                  <div className="record-info">
                    <span className="record-title">{r.remark || '消费返积分'}</span>
                    <span className="record-time">
                      {r.createTime ? r.createTime.replace('T', ' ') : '刚刚'} · {r.orderNo}
                    </span>
                  </div>
                  <div className={`record-delta ${isPlus ? 'plus' : 'minus'}`}>
                    {isPlus ? '+' : ''}{r.changePoints}
                  </div>
                </div>
              );
            })}
            {!loading && records.length === 0 && (
              <div className="empty-records">暂无积分明细</div>
            )}
          </div>
        </div>
      </aside>
    </>
  );
};
