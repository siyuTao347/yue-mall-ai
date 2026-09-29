import React, { useState, useEffect, useCallback } from 'react';
import { AppProvider, useApp } from './context/AppContext';
import { itemApi } from './api/itemApi';
import { Navbar } from './components/layout/Navbar';
import { Footer } from './components/layout/Footer';
import { HeroCarousel } from './components/home/HeroCarousel';
import { LiveTicker } from './components/home/LiveTicker';
import { SessionTabs } from './components/seckill/SessionTabs';
import { CountdownTimer } from './components/seckill/CountdownTimer';
import { SeckillGrid } from './components/seckill/SeckillGrid';
import { CatalogGrid } from './components/catalog/CatalogGrid';
import { PointsDrawer } from './components/drawer/PointsDrawer';
import { SeckillModal } from './components/modal/SeckillModal';
import { AuthModal } from './components/auth/AuthModal';
import { ToastViewport } from './components/ui/UiFeedback';
import { TradeWorkbench } from './components/trade/TradeWorkbench';

const MainContent = () => {
  const { overview, setOverview } = useApp();
  const [activeSessionId, setActiveSessionId] = useState(null);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [isOverviewLoading, setIsOverviewLoading] = useState(true);

  const fetchOverview = useCallback(async () => {
    setIsOverviewLoading(true);
    try {
      const data = await itemApi.getHomeOverview();
      setOverview(data);
      // 优先展示进行中的场次，其次展示即将开始的场次；过期后刷新不再保留旧选择。
      const sessions = data.sessions || [];
      const active = sessions.find(s => s.status === 1) ||
                     sessions.find(s => s.status === 0);
      setActiveSessionId(active ? active.sessionId : null);
    } catch (e) {
      console.warn('获取首页数据异常', e);
    } finally {
      setIsOverviewLoading(false);
    }
  }, [setOverview]);

  useEffect(() => {
    fetchOverview();
  }, [fetchOverview]);

  const sessions = overview.sessions || [];
  const activeSession = sessions.find(s => s.sessionId === activeSessionId);
  const hasAvailableSession = sessions.some(s => s.status !== 2);

  const handleSearch = useCallback((keyword) => {
    setSearchKeyword(keyword);
    if (keyword) {
      document.getElementById('catalogSection')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, []);

  return (
    <div className="app-shell" id="top">
      <Navbar searchKeyword={searchKeyword} onSearch={handleSearch} />
      <LiveTicker tickers={overview.tickers} />
      <HeroCarousel banners={overview.banners} />
      <TradeWorkbench items={overview.catalogItems || overview.hotItems || []} />

      <main className="layout-container page-section" id="seckillSection">
        <div className="section-header seckill-header">
          <div>
            <h2 className="section-title">限时秒杀</h2>
            <p className="section-desc">每场限量，先到先得</p>
          </div>

          <CountdownTimer
            session={activeSession}
            onExpire={fetchOverview}
            isLoading={isOverviewLoading}
          />
        </div>

        {!isOverviewLoading && hasAvailableSession ? (
          <>
            <SessionTabs
              sessions={sessions}
              activeSessionId={activeSessionId}
              onSelectSession={setActiveSessionId}
            />
            <SeckillGrid items={activeSession?.items} session={activeSession} />
          </>
        ) : (
          <div className="empty-state">暂无秒杀活动</div>
        )}
      </main>

      <CatalogGrid
        items={overview.catalogItems || overview.hotItems || []}
        searchKeyword={searchKeyword}
      />
      <Footer />

      <PointsDrawer />
      <SeckillModal />
      <AuthModal />
      <ToastViewport />
    </div>
  );
};

export default function App() {
  return (
    <AppProvider>
      <MainContent />
    </AppProvider>
  );
}
