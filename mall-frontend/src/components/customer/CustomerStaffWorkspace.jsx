import React, { useState, useEffect, useRef, useCallback } from 'react';
import { customerStaffApi as staffApi, customerServiceApi as api } from '../../api/customerServiceApi';
import { realtimeClient } from '../../api/customerRealtimeClient';
import { RichMessageRenderer } from './rich/RichMessageRenderer';

export const CustomerStaffWorkspace = () => {
  const [profile, setProfile] = useState(null);
  const [status, setStatus] = useState('ONLINE');
  const [queue, setQueue] = useState([]);
  const [selectedHandoff, setSelectedHandoff] = useState(null);
  const [sessionMessages, setSessionMessages] = useState([]);
  const [staffInput, setStaffInput] = useState('');
  const [internalNote, setInternalNote] = useState('');
  const [showNoteModal, setShowNoteModal] = useState(false);
  const [showTransferModal, setShowTransferModal] = useState(false);
  const [transferTarget, setTransferTarget] = useState({ skillGroupId: 1, reason: '' });
  const [copilotSuggestions, setCopilotSuggestions] = useState([]);
  const [copilotSummary, setCopilotSummary] = useState(null);
  const [sopTask, setSopTask] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const chatEndRef = useRef(null);

  // Load staff profile & presence
  const loadProfile = useCallback(async () => {
    try {
      const data = await staffApi.me();
      setProfile(data);
      if (data.status) setStatus(data.status);
    } catch (err) {
      setError(err.message || '获取客服信息失败');
    }
  }, []);

  // Load handoff queue
  const loadQueue = useCallback(async () => {
    try {
      const data = await staffApi.listHandoffs('OPEN');
      setQueue(data || []);
    } catch (err) {
      console.warn('Failed to load queue:', err);
    }
  }, []);

  useEffect(() => {
    loadProfile();
    loadQueue();
    const interval = setInterval(loadQueue, 5000);
    return () => clearInterval(interval);
  }, [loadProfile, loadQueue]);

  // Load session messages when selecting a handoff
  const loadSession = useCallback(async (handoff) => {
    if (!handoff) return;
    setSelectedHandoff(handoff);
    setCopilotSuggestions([]);
    setCopilotSummary(null);
    setBusy(true);

    try {
      const sessionNo = handoff.session_no || handoff.sessionNo;
      const snap = await api.snapshot(sessionNo);
      const list = (snap.messages || []).map(m => ({
        id: m.message_no || m.messageNo || `m-${Date.now()}`,
        role: m.role,
        content: m.content,
        cards: m.cards || (m.richCard ? [m.richCard] : []),
        citations: m.citations || [],
        createdTime: m.created_time || m.createdTime
      }));
      setSessionMessages(list);

      // Subscribe to real-time events for this session
      realtimeClient.subscribeSession(sessionNo);
    } catch (err) {
      setError(err.message || '加载会话内容失败');
    } finally {
      setBusy(false);
    }
  }, []);

  // Listen to realtime events for active session
  useEffect(() => {
    const unsub = realtimeClient.on('*', (eventType, payload) => {
      if (!selectedHandoff) return;
      const currentSessionNo = selectedHandoff.session_no || selectedHandoff.sessionNo;
      if (payload?.sessionNo && payload.sessionNo !== currentSessionNo) return;

      if (eventType === 'message.created') {
        const msg = payload.message || payload;
        setSessionMessages(prev => [
          ...prev,
          {
            id: msg.messageNo || msg.message_no || `m-${Date.now()}`,
            role: msg.role,
            content: msg.content || '',
            cards: msg.cards || (msg.richCard ? [msg.richCard] : []),
            createdTime: msg.createdTime || Date.now()
          }
        ]);
      }
    });

    return () => unsub();
  }, [selectedHandoff]);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [sessionMessages]);

  // Status toggle
  const handleStatusChange = async (newStatus) => {
    try {
      await staffApi.updateStatus(newStatus);
      setStatus(newStatus);
    } catch (err) {
      setError(err.message || '更新状态失败');
    }
  };

  // Claim handoff
  const handleClaim = async (handoff) => {
    setBusy(true);
    setError('');
    try {
      const handoffNo = handoff.handoff_no || handoff.handoffNo;
      await staffApi.claimHandoff(handoffNo);
      await loadQueue();
      await loadSession({ ...handoff, status: 'PROCESSING' });
    } catch (err) {
      setError(err.message || '认领失败，可能已被其他客服认领');
    } finally {
      setBusy(false);
    }
  };

  // Send staff reply
  const handleSendStaffMessage = async (e) => {
    e?.preventDefault();
    if (!staffInput.trim() || !selectedHandoff || busy) return;
    const sessionNo = selectedHandoff.session_no || selectedHandoff.sessionNo;
    const content = staffInput.trim();
    setStaffInput('');

    try {
      await staffApi.sendMessage(sessionNo, content, `staff-${Date.now()}`);
    } catch (err) {
      setError(err.message || '发送消息失败');
    }
  };

  // Transfer handoff
  const handleTransfer = async () => {
    if (!selectedHandoff || !transferTarget.reason) return;
    const handoffNo = selectedHandoff.handoff_no || selectedHandoff.handoffNo;
    setBusy(true);
    try {
      await staffApi.transferHandoff(handoffNo, null, transferTarget.skillGroupId, transferTarget.reason);
      setShowTransferModal(false);
      setSelectedHandoff(null);
      setSessionMessages([]);
      await loadQueue();
    } catch (err) {
      setError(err.message || '转派失败');
    } finally {
      setBusy(false);
    }
  };

  // Resolve handoff
  const handleResolve = async (disposition = 'RETURN_TO_BOT') => {
    if (!selectedHandoff) return;
    const handoffNo = selectedHandoff.handoff_no || selectedHandoff.handoffNo;
    setBusy(true);
    try {
      await staffApi.resolveHandoff(handoffNo, disposition, '人工处理完成');
      setSelectedHandoff(null);
      setSessionMessages([]);
      await loadQueue();
    } catch (err) {
      setError(err.message || '完成工单失败');
    } finally {
      setBusy(false);
    }
  };

  // Trigger Copilot Reply Suggestions
  const fetchCopilotSuggestions = async () => {
    if (!selectedHandoff) return;
    const sessionNo = selectedHandoff.session_no || selectedHandoff.sessionNo;
    try {
      const res = await staffApi.copilotSuggestions(sessionNo);
      setCopilotSuggestions(res?.suggestions || []);
    } catch (err) {
      setError(err.message || '获取建议失败');
    }
  };

  // Trigger Copilot Summary
  const fetchCopilotSummary = async () => {
    if (!selectedHandoff) return;
    const sessionNo = selectedHandoff.session_no || selectedHandoff.sessionNo;
    try {
      const res = await staffApi.copilotSummary(sessionNo);
      setCopilotSummary(res);
    } catch (err) {
      setError(err.message || '生成总结失败');
    }
  };

  return (
    <div className="staff-workspace-container">
      {/* Top Bar: Profile & Presence */}
      <header className="staff-top-bar">
        <div className="staff-profile-info">
          <span className="staff-avatar-badge">👩‍💼</span>
          <div>
            <strong>{profile?.nickname || profile?.staffCode || '客服专员'}</strong>
            <small>工号: {profile?.staffCode || 'CS001'} | 技能组: {profile?.skillGroups?.map(g => g.groupName).join(', ') || '通用客服'}</small>
          </div>
        </div>

        <div className="staff-presence-controls">
          <span className="status-label">当前状态:</span>
          {['ONLINE', 'BUSY', 'OFFLINE'].map(st => (
            <button
              key={st}
              type="button"
              className={`btn-status ${status === st ? 'is-current' : ''} status-${st.toLowerCase()}`}
              onClick={() => handleStatusChange(st)}
            >
              {st === 'ONLINE' ? '● 在线接单' : st === 'BUSY' ? '⏳ 忙碌' : '○ 离线'}
            </button>
          ))}
        </div>
      </header>

      {error && (
        <div className="staff-error-banner" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => setError('')}>✕</button>
        </div>
      )}

      {/* Main 3-Column Layout */}
      <div className="staff-main-grid">
        {/* Column 1: Queue */}
        <aside className="staff-queue-col">
          <div className="queue-header">
            <h4>待接单队列 ({queue.length})</h4>
            <button type="button" className="btn-refresh" onClick={loadQueue}>🔄</button>
          </div>

          <div className="queue-list">
            {queue.map(item => {
              const hNo = item.handoff_no || item.handoffNo;
              const isSelected = selectedHandoff && (selectedHandoff.handoff_no || selectedHandoff.handoffNo) === hNo;
              const risk = item.risk_level || item.riskLevel || 'NORMAL';

              return (
                <div
                  key={hNo}
                  className={`queue-card ${isSelected ? 'is-selected' : ''} risk-${risk.toLowerCase()}`}
                  onClick={() => loadSession(item)}
                >
                  <div className="queue-card-top">
                    <span className={`risk-badge risk-${risk.toLowerCase()}`}>
                      {risk === 'URGENT' ? '紧急' : risk === 'HIGH' ? '高风险' : '常规'}
                    </span>
                    <span className="queue-time">用户 #{item.user_id || item.userId}</span>
                  </div>
                  <div className="queue-reason" title={item.reason}>{item.reason || '用户申请人工服务'}</div>
                  <div className="queue-card-actions">
                    <button
                      type="button"
                      className="btn-claim"
                      disabled={busy}
                      onClick={(e) => {
                        e.stopPropagation();
                        handleClaim(item);
                      }}
                    >
                      认领接单
                    </button>
                  </div>
                </div>
              );
            })}

            {!queue.length && (
              <div className="queue-empty">暂无排队工单，静候用户咨询</div>
            )}
          </div>
        </aside>

        {/* Column 2: Active Chat */}
        <main className="staff-chat-col">
          {selectedHandoff ? (
            <>
              <div className="chat-header">
                <div>
                  <strong>工单: {selectedHandoff.handoff_no || selectedHandoff.handoffNo}</strong>
                  <span className="chat-subtitle">会话: {selectedHandoff.session_no || selectedHandoff.sessionNo}</span>
                </div>
                <div className="chat-controls">
                  <button type="button" className="btn-secondary" onClick={() => setShowTransferModal(true)}>
                    ↪ 转派
                  </button>
                  <button type="button" className="btn-warning" onClick={() => handleResolve('RETURN_TO_BOT')}>
                    🤖 归还智能助理
                  </button>
                  <button type="button" className="btn-success" onClick={() => handleResolve('CLOSE')}>
                    ✓ 办结服务
                  </button>
                </div>
              </div>

              <div className="chat-flow">
                {sessionMessages.map(m => (
                  <div
                    key={m.id}
                    className={`staff-chat-bubble ${m.role === 'STAFF' ? 'is-staff' : m.role === 'USER' ? 'is-user' : 'is-bot'}`}
                  >
                    <div className="bubble-sender">
                      {m.role === 'STAFF' ? '👩‍💼 我 (客服专员)' : m.role === 'USER' ? '👤 客户' : '🤖 智能助理'}
                    </div>
                    <div className="bubble-text">{m.content}</div>
                    {m.cards && m.cards.length > 0 && (
                      <RichMessageRenderer cards={m.cards} />
                    )}
                  </div>
                ))}
                <div ref={chatEndRef} />
              </div>

              <form onSubmit={handleSendStaffMessage} className="staff-input-bar">
                <textarea
                  value={staffInput}
                  onChange={e => setStaffInput(e.target.value)}
                  onKeyDown={e => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      handleSendStaffMessage();
                    }
                  }}
                  placeholder="在此输入回复客户的内容（按 Enter 发送）..."
                  rows={3}
                />
                <button type="submit" disabled={!staffInput.trim() || busy} className="btn-send-staff">
                  发送回复
                </button>
              </form>
            </>
          ) : (
            <div className="no-chat-selected">
              <span className="empty-icon">🎧</span>
              <p>请在左侧队列中选择或认领工单开始接待</p>
            </div>
          )}
        </main>

        {/* Column 3: Copilot & SOP */}
        <aside className="staff-copilot-col">
          <div className="copilot-section-header">
            <h4>💡 AI Copilot 智能助手</h4>
          </div>

          <div className="copilot-action-buttons">
            <button
              type="button"
              className="btn-copilot"
              disabled={!selectedHandoff}
              onClick={fetchCopilotSuggestions}
            >
              ✨ 智能回复推荐
            </button>
            <button
              type="button"
              className="btn-copilot"
              disabled={!selectedHandoff}
              onClick={fetchCopilotSummary}
            >
              📝 生成会话小结
            </button>
          </div>

          {/* Suggestions List */}
          {copilotSuggestions.length > 0 && (
            <div className="copilot-card">
              <h5>推荐话术（点击填入输入框）:</h5>
              <div className="suggestions-list">
                {copilotSuggestions.map((sug, i) => (
                  <div
                    key={i}
                    className="suggestion-item"
                    onClick={() => setStaffInput(sug.text || sug)}
                  >
                    <p>{sug.text || sug}</p>
                    <span className="use-badge">一键填入 ↵</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Summary Card */}
          {copilotSummary && (
            <div className="copilot-card summary-card">
              <h5>会话实时小结:</h5>
              <div className="summary-content">
                <div><strong>核心诉求:</strong> {copilotSummary.primaryIntent || '售后支持'}</div>
                <div><strong>争议焦点:</strong> {copilotSummary.disputePoints || '无显著争议'}</div>
                <div><strong>处理建议:</strong> {copilotSummary.recommendation || '建议核实物流状态后提供退换货'}</div>
              </div>
            </div>
          )}
        </aside>
      </div>

      {/* Transfer Modal */}
      {showTransferModal && (
        <div className="modal-backdrop">
          <div className="modal-card">
            <h3>工单转派</h3>
            <label>目标技能组:</label>
            <select
              value={transferTarget.skillGroupId}
              onChange={e => setTransferTarget(p => ({ ...p, skillGroupId: Number(e.target.value) }))}
            >
              <option value={1}>常规服务组 (DEFAULT_SUPPORT)</option>
              <option value={2}>资深服务组 (SENIOR_SUPPORT)</option>
              <option value={3}>售后退款专组 (REFUND_SPECIALIST)</option>
              <option value={4}>技术保障组 (TECH_SUPPORT)</option>
            </select>
            <label>转派原因 (必填):</label>
            <textarea
              value={transferTarget.reason}
              onChange={e => setTransferTarget(p => ({ ...p, reason: e.target.value }))}
              placeholder="请填写转派原因，例如：涉及特殊退款权限..."
              rows={3}
            />
            <div className="modal-actions">
              <button type="button" onClick={() => setShowTransferModal(false)}>取消</button>
              <button type="button" className="btn-primary" disabled={!transferTarget.reason.trim()} onClick={handleTransfer}>
                确认转派
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
