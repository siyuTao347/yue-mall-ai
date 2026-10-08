import React, { useEffect, useRef, useState } from 'react';
import { useApp } from '../../context/AppContext';
import { showToast } from '../../utils/feedback';
import './Navbar.css';

const ADMIN_NAV_ITEMS = [
  { key: 'operations', label: '运营审核' },
  { key: 'risk', label: '风控案件' },
  { key: 'rag', label: '知识库管理' }
];

export const Navbar = ({
  searchKeyword = '',
  onSearch = () => {},
  mode = 'store',
  activeKey = '',
  onNavigate = () => {}
}) => {
  const { user, userPoints, openDrawer, openAuthModal, logout } = useApp();
  const isAdminMode = mode === 'admin';
  const [showDropdown, setShowDropdown] = useState(false);
  const [keywordInput, setKeywordInput] = useState(searchKeyword);
  const menuRef = useRef(null);

  useEffect(() => {
    setKeywordInput(searchKeyword);
  }, [searchKeyword]);

  const submitSearch = (event) => {
    event.preventDefault();
    const keyword = keywordInput.trim();
    if (!keyword) {
      showToast('info', '请输入关键词', '可以先输入饰品名称、专场或品类再搜索');
      return;
    }
    onSearch(keyword);
  };

  useEffect(() => {
    if (!showDropdown) return undefined;

    const close = (event) => {
      if (!menuRef.current?.contains(event.target)) {
        setShowDropdown(false);
      }
    };

    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [showDropdown]);

  return (
    <header className={`navbar ${isAdminMode ? 'admin-mode' : ''}`}>
      <div className="nav-container">
        <a className="brand" href="#top" aria-label={isAdminMode ? '悦购运营管理后台' : '悦购商城首页'}>
          <span className="brand-logo">悦</span>
          <span className="brand-title">
            <span className="brand-name">悦购商城</span>
            <span className="brand-subtitle">{isAdminMode ? '运营管理后台' : '虚拟饰品精选'}</span>
          </span>
        </a>

        <form className={isAdminMode ? 'search-box admin-hidden' : 'search-box'} onSubmit={submitSearch} role="search" hidden={isAdminMode}>
          <input
            type="search"
            value={keywordInput}
            onChange={event => setKeywordInput(event.target.value)}
            placeholder="搜索饰品、专场或品类"
            aria-label="搜索饰品"
          />
          {keywordInput && (
            <button
              type="button"
              className="search-clear"
              onClick={() => {
                setKeywordInput('');
                onSearch('');
              }}
              aria-label="清空搜索"
            >
              ×
            </button>
          )}
          <button type="submit" className="search-submit">
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <path d="M9 3a6 6 0 1 0 3.7 10.7l3.3 3.3 1.4-1.4-3.3-3.3A6 6 0 0 0 9 3Zm0 2a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z" />
            </svg>
            <span>搜索</span>
          </button>
        </form>

        <nav className="nav-links" aria-label={isAdminMode ? '管理后台导航' : '页面导航'}>
          {isAdminMode ? ADMIN_NAV_ITEMS.map(item => (
            <button key={item.key} type="button"
              className={`nav-link nav-button ${activeKey === item.key ? 'active' : ''}`}
              onClick={() => onNavigate(item.key)}>
              {item.label}
            </button>
          )) : (
            <>
              <a href="#tradeSection" className="nav-link active">担保交易</a>
              <a href="#seckillSection" className="nav-link">限时秒杀</a>
              <a href="#catalogSection" className="nav-link">饰品商城</a>
            </>
          )}
        </nav>

        <div className="nav-actions">
          {!isAdminMode ? (
            <button type="button" className="points-entry" onClick={openDrawer}>
              <span className="points-label">我的积分</span>
              <span className="points-value">{userPoints.toLocaleString()}</span>
            </button>
          ) : null}

          {user ? (
            <div className="user-menu" ref={menuRef}>
              <button
                type="button"
                className="user-button"
                onClick={() => setShowDropdown(prev => !prev)}
              >
                <img
                  className="user-avatar"
                  src={user.avatarUrl || 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"%3E%3Crect width="32" height="32" fill="%23dfe3e8"/%3E%3Ccircle cx="16" cy="12" r="5" fill="%23fff"/%3E%3Cpath d="M6 28c0-5.5 4.5-10 10-10s10 4.5 10 10" fill="%23fff"/%3E%3C/svg%3E'}
                  alt=""
                />
                <span className="user-name">{user.nickname || user.username}</span>
                <span className="user-caret">▼</span>
              </button>

              {showDropdown && (
                <div className="user-dropdown">
                  <div className="dropdown-profile">
                    <div className="dropdown-name">{user.nickname || user.username}</div>
                    <div className="dropdown-mail">{user.email || user.username}</div>
                    <div className="dropdown-id">{isAdminMode ? `管理员账号：${user.id}` : `会员号：${user.id}`}</div>
                  </div>
                  {!isAdminMode ? (
                    <button
                      type="button"
                      className="dropdown-action"
                      onClick={() => {
                        setShowDropdown(false);
                        openDrawer();
                      }}
                    >
                      积分明细
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="dropdown-action danger"
                    onClick={() => {
                      setShowDropdown(false);
                      logout();
                    }}
                  >
                    退出登录
                  </button>
                </div>
              )}
            </div>
          ) : (
            <button type="button" className="login-entry" onClick={openAuthModal}>
              登录 / 注册
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
