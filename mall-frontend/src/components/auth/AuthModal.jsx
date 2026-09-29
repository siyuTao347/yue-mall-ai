import React, { useState, useEffect, useRef } from 'react';
import { useApp } from '../../context/AppContext';
import { userApi } from '../../api/userApi';
import './AuthModal.css';

export const AuthModal = () => {
  const { isAuthModalOpen, closeAuthModal, login } = useApp();

  const [authMode, setAuthMode] = useState('code_login');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');

  const [cooldown, setCooldown] = useState(0);
  const [loading, setLoading] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [successMsg, setSuccessMsg] = useState('');

  const timerRef = useRef(null);

  useEffect(() => {
    if (cooldown > 0) {
      timerRef.current = setTimeout(() => {
        setCooldown(prev => prev - 1);
      }, 1000);
    }
    return () => clearTimeout(timerRef.current);
  }, [cooldown]);

  useEffect(() => {
    if (!isAuthModalOpen) {
      setErrorMsg('');
      setSuccessMsg('');
    }
  }, [isAuthModalOpen]);

  if (!isAuthModalOpen) return null;

  const isValidEmail = (val) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(val);

  const handleSendCode = async () => {
    if (!email || !isValidEmail(email)) {
      setErrorMsg('请输入合法的邮箱地址 (例如: 123456@qq.com)');
      return;
    }
    setErrorMsg('');
    setSendingCode(true);

    try {
      const res = await userApi.sendEmailCode(email);
      if (res.code === 200) {
        setCooldown(60);
        setSuccessMsg(res.msg || '验证码已发送，请查收邮件');
      } else {
        setErrorMsg(res.msg || '验证码发送失败');
      }
    } catch (e) {
      setErrorMsg(e.message || '网络异常，请稍后重试');
    } finally {
      setSendingCode(false);
    }
  };

  const handleCodeLogin = async (e) => {
    e.preventDefault();
    if (!email || !isValidEmail(email)) {
      setErrorMsg('请输入有效的邮箱地址');
      return;
    }
    if (!code || code.length < 4) {
      setErrorMsg('请输入正确的 6 位验证码');
      return;
    }

    setLoading(true);
    setErrorMsg('');
    try {
      const res = await userApi.login({
        usernameOrEmail: email,
        code,
        loginType: 'CODE'
      });
      if (res.code === 200 && res.data) {
        setSuccessMsg('登录成功，正在进入商城');
        setTimeout(() => login(res.data), 400);
      } else {
        setErrorMsg(res.msg || '验证码不正确');
      }
    } catch (e) {
      setErrorMsg(e.message || '登录异常');
    } finally {
      setLoading(false);
    }
  };

  const handlePasswordLogin = async (e) => {
    e.preventDefault();
    if (!username) {
      setErrorMsg('请输入用户名或注册邮箱');
      return;
    }
    if (!password) {
      setErrorMsg('请输入登录密码');
      return;
    }

    setLoading(true);
    setErrorMsg('');
    try {
      const res = await userApi.login({
        usernameOrEmail: username,
        password,
        loginType: 'PASSWORD'
      });
      if (res.code === 200 && res.data) {
        setSuccessMsg('登录成功，正在进入商城');
        setTimeout(() => login(res.data), 400);
      } else {
        setErrorMsg(res.msg || '账号或密码不正确');
      }
    } catch (e) {
      setErrorMsg(e.message || '登录异常');
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async (e) => {
    e.preventDefault();
    if (!email || !isValidEmail(email)) {
      setErrorMsg('请输入合法的邮箱地址');
      return;
    }
    if (!code || code.length < 4) {
      setErrorMsg('请输入邮箱中收到的 6 位验证码');
      return;
    }
    if (!username || username.length < 3) {
      setErrorMsg('用户名至少需要 3 位字符');
      return;
    }
    if (!password || password.length < 6) {
      setErrorMsg('密码长度不能少于 6 位字符');
      return;
    }

    setLoading(true);
    setErrorMsg('');
    try {
      const res = await userApi.register({
        email,
        code,
        username,
        password,
        nickname: nickname.trim() || username
      });
      if (res.code === 200 && res.data) {
        setSuccessMsg('注册成功，已赠送 100 积分');
        setTimeout(() => login(res.data), 600);
      } else {
        setErrorMsg(res.msg || '注册失败，请稍后重试');
      }
    } catch (e) {
      setErrorMsg(e.message || '注册异常');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-backdrop" onClick={closeAuthModal}>
      <div className="auth-modal" onClick={e => e.stopPropagation()}>
        <button type="button" className="auth-close" onClick={closeAuthModal} aria-label="关闭">×</button>
        <div className="auth-header">
          <h2 className="auth-title">欢迎来到悦购商城</h2>
          <p className="auth-subtitle">登录后可参与秒杀、查看积分和订单</p>
        </div>

        <div className="auth-tabs">
          <button 
            type="button"
            className={`auth-tab ${authMode === 'code_login' ? 'active' : ''}`}
            onClick={() => { setAuthMode('code_login'); setErrorMsg(''); setSuccessMsg(''); }}
          >
            邮箱快捷登录
          </button>
          <button 
            type="button"
            className={`auth-tab ${authMode === 'pwd_login' ? 'active' : ''}`}
            onClick={() => { setAuthMode('pwd_login'); setErrorMsg(''); setSuccessMsg(''); }}
          >
            密码登录
          </button>
          <button 
            type="button"
            className={`auth-tab ${authMode === 'register' ? 'active' : ''}`}
            onClick={() => { setAuthMode('register'); setErrorMsg(''); setSuccessMsg(''); }}
          >
            注册账号
          </button>
        </div>

        {errorMsg && (
          <div className="auth-alert error">
            <span>{errorMsg}</span>
          </div>
        )}
        {successMsg && (
          <div className="auth-alert success">
            <span>{successMsg}</span>
          </div>
        )}

        {authMode === 'code_login' && (
          <form onSubmit={handleCodeLogin} className="auth-form">
            <div className="form-field">
              <label>电子邮箱</label>
              <input
                type="email"
                placeholder="请输入邮箱"
                value={email}
                onChange={e => setEmail(e.target.value)}
                required
              />
            </div>

            <div className="form-field">
              <label>6 位验证码</label>
              <div className="code-row">
                <input 
                  type="text" 
                  maxLength={6}
                  placeholder="输入邮件验证码" 
                  value={code}
                  onChange={e => setCode(e.target.value.trim())}
                  required
                />
                <button 
                  type="button" 
                  className="send-code"
                  onClick={handleSendCode}
                  disabled={cooldown > 0 || sendingCode}
                >
                  {sendingCode ? '发送中...' : cooldown > 0 ? `${cooldown}s 后重试` : '获取验证码'}
                </button>
              </div>
            </div>

            <button type="submit" className="auth-submit" disabled={loading}>
              {loading ? '登录中...' : '登录'}
            </button>

            <div className="auth-form-footer">
              <span>还没有账号？</span>
              <a href="#register" onClick={(e) => { e.preventDefault(); setAuthMode('register'); }}>
                立即注册
              </a>
            </div>
          </form>
        )}

        {authMode === 'pwd_login' && (
          <form onSubmit={handlePasswordLogin} className="auth-form">
            <div className="form-field">
              <label>用户名或注册邮箱</label>
              <input
                type="text"
                placeholder="请输入账号或邮箱"
                value={username}
                onChange={e => setUsername(e.target.value)}
                required
              />
            </div>

            <div className="form-field">
              <label>登录密码</label>
              <input
                type="password"
                placeholder="请输入密码"
                value={password}
                onChange={e => setPassword(e.target.value)}
                required
              />
            </div>

            <button type="submit" className="auth-submit" disabled={loading}>
              {loading ? '登录中...' : '登录'}
            </button>

            <div className="auth-form-footer">
              <span>忘记密码？</span>
              <a href="#code" onClick={(e) => { e.preventDefault(); setAuthMode('code_login'); }}>
                用邮箱验证码登录
              </a>
            </div>
          </form>
        )}

        {authMode === 'register' && (
          <form onSubmit={handleRegister} className="auth-form">
            <div className="form-field">
              <label>注册邮箱 <span className="required">*</span></label>
              <input
                type="email"
                placeholder="请输入邮箱"
                value={email}
                onChange={e => setEmail(e.target.value)}
                required
              />
            </div>

            <div className="form-field">
              <label>邮箱验证码 <span className="required">*</span></label>
              <div className="code-row">
                <input 
                  type="text" 
                  maxLength={6}
                  placeholder="6 位验证码" 
                  value={code}
                  onChange={e => setCode(e.target.value.trim())}
                  required
                />
                <button 
                  type="button" 
                  className="send-code"
                  onClick={handleSendCode}
                  disabled={cooldown > 0 || sendingCode}
                >
                  {sendingCode ? '发送中...' : cooldown > 0 ? `${cooldown}s 后重试` : '获取验证码'}
                </button>
              </div>
            </div>

            <div className="form-row">
              <div className="form-field">
                <label>用户名 <span className="required">*</span></label>
                <input 
                  type="text" 
                  placeholder="3-20 位字母/数字" 
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  required
                />
              </div>

              <div className="form-field">
                <label>昵称</label>
                <input 
                  type="text" 
                  placeholder="请输入昵称"
                  value={nickname}
                  onChange={e => setNickname(e.target.value)}
                />
              </div>
            </div>

            <div className="form-field">
              <label>设置密码 <span className="required">*</span></label>
              <input 
                type="password" 
                placeholder="不少于 6 位" 
                value={password}
                onChange={e => setPassword(e.target.value)}
                required
              />
            </div>

            <div className="register-tip">
              注册成功即赠 <strong>100 积分</strong>，消费 1 元可得 1 积分。
            </div>

            <button type="submit" className="auth-submit" disabled={loading}>
              {loading ? '注册中...' : '注册并登录'}
            </button>

            <div className="auth-form-footer">
              <span>已有账号？</span>
              <a href="#login" onClick={(e) => { e.preventDefault(); setAuthMode('code_login'); }}>
                直接登录
              </a>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};
