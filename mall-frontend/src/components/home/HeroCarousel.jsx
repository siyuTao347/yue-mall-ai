import React, { useEffect, useState } from 'react';
import './HeroCarousel.css';

export const HeroCarousel = ({ banners = [] }) => {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isPaused, setIsPaused] = useState(false);
  const total = banners.length;

  useEffect(() => {
    if (total <= 1 || isPaused) return undefined;
    const timer = setInterval(() => {
      setCurrentIndex(prev => (prev + 1) % total);
    }, 5000);
    return () => clearInterval(timer);
  }, [total, isPaused]);

  if (total === 0) return null;

  const safeIndex = currentIndex % total;

  return (
    <section className="hero-section">
      <div
        className="hero-carousel"
        onMouseEnter={() => setIsPaused(true)}
        onMouseLeave={() => setIsPaused(false)}
      >
        <div
          className="hero-slides"
          style={{ transform: `translateX(-${safeIndex * 100}%)` }}
        >
          {banners.map(banner => (
            <div
              key={banner.id}
              className="hero-slide"
              style={{ backgroundImage: `url('${banner.imageUrl}')` }}
            >
              <div className="hero-copy">
                <div className="hero-content">
                  <span className="hero-badge">{banner.badgeText || '今日推荐'}</span>
                  <h2 className="hero-title">{banner.title}</h2>
                  <p className="hero-desc">{banner.desc || '精选热门饰品，限量场次陆续开抢。'}</p>
                  <div className="hero-actions">
                    <a href="#seckillSection" className="btn btn-primary">去抢购</a>
                    <a href="#catalogSection" className="btn btn-outline">逛商城</a>
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>

        {total > 1 && (
          <>
            <button
              type="button"
              className="hero-arrow prev"
              onClick={() => setCurrentIndex(prev => (prev - 1 + total) % total)}
              aria-label="上一张"
            >
              ‹
            </button>
            <button
              type="button"
              className="hero-arrow next"
              onClick={() => setCurrentIndex(prev => (prev + 1) % total)}
              aria-label="下一张"
            >
              ›
            </button>
            <div className="hero-dots">
              {banners.map((_, index) => (
                <button
                  key={index}
                  type="button"
                  className={`hero-dot ${index === safeIndex ? 'active' : ''}`}
                  onClick={() => setCurrentIndex(index)}
                  aria-label={`切换到第${index + 1}张`}
                />
              ))}
            </div>
          </>
        )}
      </div>
    </section>
  );
};
