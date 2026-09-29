import React, { useState, useEffect } from 'react';
import './LiveTicker.css';

export const LiveTicker = ({ tickers = [] }) => {
  const [currentIndex, setCurrentIndex] = useState(0);

  useEffect(() => {
    if (!tickers || tickers.length === 0) return;
    const timer = setInterval(() => {
      setCurrentIndex(prev => (prev + 1) % tickers.length);
    }, 3500);
    return () => clearInterval(timer);
  }, [tickers]);

  return (
    <section className="ticker-bar">
      <div className="ticker-container">
        <div className="ticker-tag">
          <span className="ticker-dot" />
          <span>商城快讯</span>
        </div>
        <div className="ticker-viewport">
          <div 
            className="ticker-track" 
            style={{ transform: `translateY(-${currentIndex * 24}px)` }}
          >
            {tickers.map((text, idx) => (
              <div key={idx} className="ticker-item">{text}</div>
            ))}
          </div>
        </div>
        <div className="ticker-tip">消费 1 元得 1 积分</div>
      </div>
    </section>
  );
};
