import React, { useEffect, useRef, useState } from 'react';
import { useCustomerRealtime } from '../../context/CustomerRealtimeContext';
import { RichMessageRenderer } from './rich/RichMessageRenderer';

export const CustomerServiceDialog = ({ user, position, onClose }) => {
  const {
    session,
    serviceMode,
    handoffInfo,
    messages,
    busy,
    error,
    setError,
    sendMessage,
    requestHandoff,
    executeCardAction,
    startNewSession
  } = useCustomerRealtime();

  const [inputContent, setInputContent] = useState('');
  const inputRef = useRef(null);
  const dialogRef = useRef(null);
  const endRef = useRef(null);

  // Focus and keyboard accessibility
  useEffect(() => {
    if (inputRef.current && !inputRef.current.disabled) {
      inputRef.current.focus();
    }
    const handleKey = (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    };
    const node = dialogRef.current;
    node?.addEventListener('keydown', handleKey);
    return () => node?.removeEventListener('keydown', handleKey);
  }, [onClose]);

  // Scroll to bottom on new message or status change
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, busy]);

  const handleSend = async (e) => {
    e?.preventDefault();
    if (!user || busy || !inputContent.trim()) return;
    const text = inputContent.trim();
    setInputContent('');
    try {
      await sendMessage(text);
    } catch {
      // error handled in context
    }
  };

  const isHuman = serviceMode === 'HUMAN';
  const isQueued = serviceMode === 'QUEUED';

  // Responsive position bounds
  const left = position.x > window.innerWidth / 2
    ? Math.max(12, position.x - 420)
    : Math.min(window.innerWidth - 420, position.x + 68);
  const top = Math.max(12, Math.min(position.y, window.innerHeight - 620));

  return (
    <section
      ref={dialogRef}
      id="customer-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="customer-title"
      className="customer-dialog"
      style={{ left: Math.max(12, left), top }}
    >
      <header className={`customer-header mode-${serviceMode.toLowerCase()}`}>
        <div className="customer-header-title">
          <strong id="customer-title">平台客服中心</strong>
          <div className="customer-mode-badge">
            {isHuman ? (
              <span className="badge-human">● 人工客服 ({handoffInfo?.staffName || '客服专员'})</span>
            ) : isQueued ? (
              <span className="badge-queued">⏳ 排队中 (请稍候)</span>
            ) : (
              <span className="badge-bot">🤖 智能助理</span>
            )}
          </div>
        </div>
        <button type="button" className="customer-close-btn" onClick={onClose} aria-label="关闭客服">
          ×
        </button>
      </header>

      <div className="customer-messages" role="log" aria-live="polite" aria-busy={busy}>
        {!messages.length && (
          <div className="customer-welcome">
            <p>👋 您好！我是商城智能客服助手，很高兴为您服务。</p>
            <p className="welcome-hint">您可以询问：订单发货、退款售后、物流查询或平台优惠规则。</p>
          </div>
        )}

        {messages.map((m) => {
          const isUser = m.role === 'USER';
          const isSystem = m.role === 'SYSTEM';

          return (
            <article
              key={m.id}
              className={`customer-message ${isUser ? 'customer-user' : isSystem ? 'customer-system' : 'customer-agent'}`}
            >
              {isSystem ? (
                <div className="system-notice-bubble">{m.content}</div>
              ) : (
                <div className="message-bubble">
                  {m.content && <p className="message-text">{m.content}</p>}

                  {m.streaming && m.stage && (
                    <small className="message-stage-indicator" role="status">
                      {m.stage}
                    </small>
                  )}

                  {/* Rich Cards */}
                  {m.cards && m.cards.length > 0 && (
                    <RichMessageRenderer cards={m.cards} onCardAction={executeCardAction} />
                  )}

                  {/* Citations */}
                  {m.citations && m.citations.length > 0 && (
                    <div className="citations-list">
                      {m.citations.map((c, i) => (
                        <details key={c.chunkId || i} className="citation-item">
                          <summary className="citation-summary">
                            📄 参考知识: {c.title || c.titlePath || '服务知识库'}
                          </summary>
                          <p className="citation-content">{c.content}</p>
                        </details>
                      ))}
                    </div>
                  )}

                  {/* Suggested actions */}
                  {m.suggestedActions && m.suggestedActions.length > 0 && (
                    <div className="suggested-actions-row">
                      {m.suggestedActions.map((action, i) => (
                        <button
                          key={i}
                          type="button"
                          className="btn-suggested-action"
                          onClick={() => {
                            if (action.type === 'VIEW_TRADE' || action.bizNo) {
                              onClose();
                              document.getElementById('tradeSection')?.scrollIntoView({ behavior: 'smooth' });
                            } else {
                              sendMessage(action.label || action.bizNo);
                            }
                          }}
                        >
                          {action.label || `查看业务 · ${action.bizNo}`}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </article>
          );
        })}

        {busy && (
          <div className="customer-typing-indicator" role="status">
            <span className="dot" /><span className="dot" /><span className="dot" />
            <small>客服正在回复中...</small>
          </div>
        )}
        <div ref={endRef} />
      </div>

      {error && (
        <div className="customer-error" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => setError('')}>✕</button>
        </div>
      )}

      {/* Action shortcuts */}
      <div className="customer-shortcuts">
        {!isHuman && !isQueued && (
          <button
            type="button"
            className="btn-shortcut"
            disabled={busy || !user}
            onClick={() => requestHandoff('用户主动要求人工接管')}
          >
            👨‍💼 转人工
          </button>
        )}
        <button
          type="button"
          className="btn-shortcut"
          disabled={busy}
          onClick={startNewSession}
        >
          🔄 新会话
        </button>
      </div>

      {/* Input bar - NOT disabled during human handoff! */}
      <form onSubmit={handleSend} className="customer-input-area">
        <label className="customer-sr-only" htmlFor="customer-input">
          输入消息
        </label>
        <textarea
          ref={inputRef}
          id="customer-input"
          maxLength={2000}
          disabled={!user || busy}
          value={inputContent}
          onChange={(e) => setInputContent(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder={
            !user
              ? '请先在页面顶部登录账号'
              : isHuman
              ? '人工客服已接入，请在此输入沟通内容（按 Enter 发送）...'
              : isQueued
              ? '正在排队，您可提前输入咨询问题（按 Enter 发送）...'
              : '输入您的问题（按 Enter 发送，Shift+Enter 换行）...'
          }
        />
        <button
          type="submit"
          className="customer-send-btn"
          disabled={!user || busy || !inputContent.trim()}
        >
          发送
        </button>
      </form>
    </section>
  );
};
