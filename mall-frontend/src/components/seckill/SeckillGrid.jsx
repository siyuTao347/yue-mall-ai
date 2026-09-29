import React from 'react';
import { SeckillCard } from './SeckillCard';

export const SeckillGrid = ({ items = [], session }) => {
  if (!items || items.length === 0) {
    return (
      <div className="empty-state">
        {session?.status === 2 ? '本场活动已结束' : '本场暂无可抢购的饰品'}
      </div>
    );
  }

  return (
    <div className="seckill-grid">
      {items.map((item) => (
        <SeckillCard key={item.id || item.itemId} item={item} session={session} />
      ))}
    </div>
  );
};
