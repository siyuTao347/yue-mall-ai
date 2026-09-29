import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
import { userApi } from '../api/userApi';
import { itemApi } from '../api/itemApi';
import { DEMO_FALLBACK } from '../api/mockData';

const AppContext = createContext(null);

const INITIAL_OVERVIEW = {
  ...DEMO_FALLBACK,
  sessions: []
};

export const AppProvider = ({ children }) => {
  const [user, setUser] = useState(() => {
    try {
      const saved = localStorage.getItem('valor_user');
      return saved ? JSON.parse(saved) : null;
    } catch {
      return null;
    }
  });
  const [token, setToken] = useState(() => localStorage.getItem('valor_token') || null);
  const [isAuthModalOpen, setIsAuthModalOpen] = useState(false);
  const authPendingActionRef = useRef(null);

  const [serverTimeOffset, setServerTimeOffset] = useState(0);
  const [userPoints, setUserPoints] = useState(1580);
  const [historyPoints, setHistoryPoints] = useState(2300);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const [activeModalItem, setActiveModalItem] = useState(null);
  const [overview, setOverview] = useState(INITIAL_OVERVIEW);

// 同步服务端时间，保证秒杀倒计时准确
  const syncServerTime = useCallback(async () => {
    try {
      const { serverTime, rtt } = await itemApi.getServerTime();
      // 单程耗时补偿: rtt / 2
      const offset = (serverTime + rtt / 2) - Date.now();
      setServerTimeOffset(offset);
    } catch {
      setServerTimeOffset(0);
    }
  }, []);

// 刷新用户积分
  const refreshPoints = useCallback(async (targetUserId) => {
    try {
      const uid = targetUserId || (user ? user.id : 1);
      const data = await userApi.getPointSummary(uid);
      setUserPoints(data.totalPoints ?? 0);
      setHistoryPoints(data.historyEarnedPoints ?? 0);
    } catch (e) {
      console.warn('刷新用户积分异常', e);
    }
  }, [user]);

// 支付成功后先更新页面积分，再等待服务端数据刷新
  const addPoints = useCallback((points) => {
    setUserPoints(prev => prev + points);
    setHistoryPoints(prev => prev + points);
  }, []);

  const updateSeckillItem = useCallback((sourceItem, changes) => {
    if (!sourceItem) return;
    setOverview(prev => ({
      ...prev,
      sessions: (prev.sessions || []).map(session => ({
        ...session,
        items: (session.items || []).map(item => (
          item.id === sourceItem.id || item.itemId === sourceItem.itemId
            ? { ...item, ...changes }
            : item
        ))
      }))
    }));
  }, []);

// 登录成功后恢复用户此前想继续的操作
  const handleLoginSuccess = useCallback((authData) => {
    if (!authData || !authData.token) return;
    localStorage.setItem('valor_token', authData.token);
    const userInfo = authData.user || {
      id: authData.userId,
      username: authData.username,
      nickname: authData.nickname,
      email: authData.email,
      avatarUrl: authData.avatarUrl
    };
    localStorage.setItem('valor_user', JSON.stringify(userInfo));
    setToken(authData.token);
    setUser(userInfo);
    setIsAuthModalOpen(false);

    refreshPoints(userInfo.id);
    if (authPendingActionRef.current) {
      const pendingFn = authPendingActionRef.current;
      authPendingActionRef.current = null;
      setTimeout(() => {
        try {
          pendingFn(userInfo);
        } catch (e) {
          console.error('执行拦截队列操作失败', e);
        }
      }, 100);
    }
  }, [refreshPoints]);

// 退出登录
  const handleLogout = useCallback(() => {
    localStorage.removeItem('valor_token');
    localStorage.removeItem('valor_user');
    setToken(null);
    setUser(null);
    authPendingActionRef.current = null;
    setUserPoints(0);
    setHistoryPoints(0);
  }, []);

// 未登录时先弹出登录框，登录后继续原操作
  const requireAuth = useCallback((actionCallback) => {
    if (token && user) {
      actionCallback(user);
    } else {
      authPendingActionRef.current = actionCallback;
      setIsAuthModalOpen(true);
    }
  }, [token, user]);

  // 初始化时钟与用户信息
  useEffect(() => {
    syncServerTime();
    if (token) {
      userApi.getUserInfo().then(res => {
        if (res.code === 200 && res.data) {
          const u = res.data.user || res.data;
          setUser(u);
          localStorage.setItem('valor_user', JSON.stringify(u));
          refreshPoints(u.id);
        } else {
          handleLogout();
        }
      }).catch(() => {
        refreshPoints();
      });
    } else {
      refreshPoints(1);
    }
  }, [syncServerTime, token, refreshPoints, handleLogout]);

  const value = {
    user,
    userId: user ? user.id : 1,
    token,
    isAuthModalOpen,
    openAuthModal: () => setIsAuthModalOpen(true),
    closeAuthModal: () => {
      setIsAuthModalOpen(false);
      authPendingActionRef.current = null;
    },
    login: handleLoginSuccess,
    logout: handleLogout,
    requireAuth,
    serverTimeOffset,
    userPoints,
    historyPoints,
    isDrawerOpen,
    openDrawer: () => setIsDrawerOpen(true),
    closeDrawer: () => setIsDrawerOpen(false),
    activeModalItem,
    openModal: (item) => setActiveModalItem(item),
    closeModal: () => setActiveModalItem(null),
    overview,
    setOverview,
    updateSeckillItem,
    addPoints,
    refreshPoints
  };

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
};

export const useApp = () => {
  const ctx = useContext(AppContext);
  if (!ctx) {
    throw new Error('useApp must be used within an AppProvider');
  }
  return ctx;
};
