import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { useApp } from './AppContext';
import { realtimeClient } from '../api/customerRealtimeClient';
import { customerServiceApi as api } from '../api/customerServiceApi';

const CustomerRealtimeContext = createContext(null);

export const useCustomerRealtime = () => {
  const context = useContext(CustomerRealtimeContext);
  if (!context) {
    throw new Error('useCustomerRealtime must be used within a CustomerRealtimeProvider');
  }
  return context;
};

export const CustomerRealtimeProvider = ({ children }) => {
  const { user } = useApp();
  const [session, setSession] = useState(null);
  const [serviceMode, setServiceMode] = useState('BOT'); // 'BOT' | 'QUEUED' | 'HUMAN' | 'CLOSED'
  const [controlEpoch, setControlEpoch] = useState(1);
  const [handoffInfo, setHandoffInfo] = useState(null);
  const [messages, setMessages] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const activeSessionRef = useRef(session);
  activeSessionRef.current = session;
  const isDialogOpenRef = useRef(isDialogOpen);
  isDialogOpenRef.current = isDialogOpen;

  const storageKey = user ? `mall.customerService.session.${user.id}` : null;

  // Initialize or restore session
  const restoreSession = useCallback(async () => {
    if (!user || !storageKey) return;
    let saved = null;
    try {
      saved = sessionStorage.getItem(storageKey);
    } catch {
      // storage unavailable
    }

    if (saved) {
      try {
        setBusy(true);
        const snap = await api.snapshot(saved);
        if (snap.status === 'CLOSED' || snap.status === 'EXPIRED') {
          sessionStorage.removeItem(storageKey);
          setSession(null);
          setMessages([]);
          return;
        }

        setSession(saved);
        setServiceMode(snap.serviceMode || 'BOT');
        setControlEpoch(snap.controlEpoch || 1);
        if (snap.handoff) setHandoffInfo(snap.handoff);

        const list = (snap.messages || []).map(m => ({
          id: m.message_no || m.messageNo,
          role: m.role,
          content: m.content,
          cards: m.cards || (m.result?.cards) || (m.richCard ? [m.richCard] : []),
          citations: m.citations || m.result?.citations || [],
          suggestedActions: m.suggestedActions || m.result?.suggestedActions || [],
          createdTime: m.created_time || m.createdTime,
          clientMessageId: m.client_message_id || m.clientMessageId
        }));
        setMessages(list);

        // Subscribe via realtime client
        realtimeClient.subscribeSession(saved);
      } catch (err) {
        console.warn('Failed to restore customer session snapshot:', err);
      } finally {
        setBusy(false);
      }
    }
  }, [user, storageKey]);

  useEffect(() => {
    restoreSession();
  }, [restoreSession]);

  // Connect realtime client
  useEffect(() => {
    if (!user) {
      realtimeClient.disconnect();
      return;
    }

    realtimeClient.setRole(user.role === 'ADMIN' ? 'STAFF' : 'USER');
    const unsubStatus = realtimeClient.onStatusChange(status => {
      setWsConnected(status);
    });

    realtimeClient.connect();

    return () => {
      unsubStatus();
    };
  }, [user]);

  // Handle incoming realtime events
  useEffect(() => {
    const unsubEvent = realtimeClient.on('*', (eventType, payload) => {
      const currentSessionNo = activeSessionRef.current;
      if (payload?.sessionNo && payload.sessionNo !== currentSessionNo) {
        return;
      }

      switch (eventType) {
        case 'message.created': {
          const msg = payload.message || payload;
          const msgNo = msg.messageNo || msg.message_no || msg.id;
          const clientMsgId = msg.clientMessageId || msg.client_message_id;

          setMessages(prev => {
            // Check if matches optimistic message
            const existingIdx = prev.findIndex(m =>
              (clientMsgId && m.clientMessageId === clientMsgId) ||
              (msgNo && m.id === msgNo)
            );

            const formatted = {
              id: msgNo || clientMsgId || `msg-${Date.now()}`,
              role: msg.role,
              content: msg.content || '',
              cards: msg.cards || (msg.richCard ? [msg.richCard] : []),
              citations: msg.citations || [],
              suggestedActions: msg.suggestedActions || [],
              createdTime: msg.createdTime || Date.now(),
              clientMessageId: clientMsgId,
              streaming: false
            };

            if (existingIdx >= 0) {
              const updated = [...prev];
              updated[existingIdx] = formatted;
              return updated;
            }
            return [...prev, formatted];
          });

          if (!isDialogOpenRef.current && msg.role !== 'USER') {
            setUnreadCount(c => c + 1);
          }
          break;
        }

        case 'stage.progress': {
          setMessages(prev => {
            const last = prev[prev.length - 1];
            if (last && last.role === 'AGENT' && last.streaming) {
              const updated = [...prev];
              updated[prev.length - 1] = {
                ...last,
                stage: payload.label || '正在处理…'
              };
              return updated;
            }
            return prev;
          });
          break;
        }

        case 'answer.delta': {
          setMessages(prev => {
            const last = prev[prev.length - 1];
            if (last && last.role === 'AGENT') {
              const updated = [...prev];
              updated[prev.length - 1] = {
                ...last,
                content: (last.content || '') + (payload.delta || ''),
                stage: ''
              };
              return updated;
            }
            return prev;
          });
          break;
        }

        case 'card.updated': {
          const updatedCard = payload.card || payload;
          setMessages(prev => prev.map(m => {
            if (!m.cards || !m.cards.length) return m;
            return {
              ...m,
              cards: m.cards.map(c => c.cardId === updatedCard.cardId ? { ...c, ...updatedCard } : c)
            };
          }));
          break;
        }

        case 'service_mode.changed': {
          if (payload.serviceMode) {
            setServiceMode(payload.serviceMode);
          }
          if (payload.controlEpoch) {
            setControlEpoch(payload.controlEpoch);
          }
          if (payload.handoff) {
            setHandoffInfo(payload.handoff);
          }
          break;
        }

        case 'handoff.status': {
          if (payload.status === 'ASSIGNED' || payload.status === 'PROCESSING') {
            setServiceMode('HUMAN');
          } else if (payload.status === 'OPEN') {
            setServiceMode('QUEUED');
          } else if (payload.status === 'RESOLVED' || payload.status === 'CANCELLED') {
            setServiceMode('BOT');
          }
          setHandoffInfo(payload);
          break;
        }

        default:
          break;
      }
    });

    return () => {
      unsubEvent();
    };
  }, []);

  const openDialog = useCallback(() => {
    setIsDialogOpen(true);
    setUnreadCount(0);
    if (activeSessionRef.current) {
      api.markRead(activeSessionRef.current, messages.length).catch(() => {});
    }
  }, [messages.length]);

  const closeDialog = useCallback(() => {
    setIsDialogOpen(false);
  }, []);

  const ensureSession = async () => {
    if (session) return session;
    const res = await api.create();
    const newSessionNo = res.sessionNo;
    setSession(newSessionNo);
    setServiceMode('BOT');
    setControlEpoch(1);
    try {
      if (storageKey) sessionStorage.setItem(storageKey, newSessionNo);
    } catch {
      // storage
    }
    realtimeClient.subscribeSession(newSessionNo);
    return newSessionNo;
  };

  const sendMessage = async (text) => {
    if (!text || !text.trim() || busy) return;
    const content = text.trim();
    const clientMessageId = `c-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    setError('');

    try {
      const currentSessionNo = await ensureSession();

      // Add optimistic user message
      const optimisticMsg = {
        id: clientMessageId,
        role: 'USER',
        content,
        clientMessageId,
        createdTime: Date.now()
      };

      setMessages(prev => [...prev, optimisticMsg]);

      // If in BOT mode, prepare streaming placeholder for bot
      if (serviceMode === 'BOT') {
        setBusy(true);
        const assistantId = `${clientMessageId}-assistant`;
        setMessages(prev => [...prev, {
          id: assistantId,
          role: 'AGENT',
          content: '',
          stage: '正在分析问题…',
          streaming: true,
          cards: [],
          citations: []
        }]);

        // Send via SSE stream for smooth typing
        try {
          const result = await api.sendStream(currentSessionNo, content, clientMessageId, (event) => {
            const p = event.data || {};
            if (event.event === 'stage.progress') {
              setMessages(m => m.map(item => item.id === assistantId ? { ...item, stage: p.label || '正在处理…' } : item));
            } else if (event.event === 'answer.delta') {
              setMessages(m => m.map(item => item.id === assistantId ? { ...item, content: item.content + (p.delta || ''), stage: '' } : item));
            } else if (event.event === 'run.completed' || event.event === 'run.failed') {
              setMessages(m => m.map(item => item.id === assistantId ? {
                ...item,
                content: p.answer || item.content,
                cards: p.cards || (p.richCard ? [p.richCard] : item.cards),
                citations: p.citations || [],
                suggestedActions: p.suggestedActions || [],
                stage: '',
                streaming: false
              } : item));
              if (p.handoffRequired) {
                setServiceMode('QUEUED');
              }
            }
          });

          if (result && result.errorCode) {
            setError(result.answer || '处理请求时发生异常');
          }
        } catch (streamErr) {
          // If stream fails, fallback to WS / HTTP message send
          console.warn('Stream failed, sending via fallback:', streamErr);
          await api.send(currentSessionNo, content, clientMessageId);
        } finally {
          setBusy(false);
        }
      } else {
        // In QUEUED or HUMAN mode: human chat
        const sentOverWs = realtimeClient.sendMessage(currentSessionNo, content, clientMessageId);
        if (!sentOverWs) {
          await api.send(currentSessionNo, content, clientMessageId);
        }
      }
    } catch (err) {
      setError(err.message || '发送失败，请稍后重试');
      throw err;
    }
  };

  const requestHandoff = async (reason = 'USER_REQUESTED') => {
    if (!session) return;
    setError('');
    try {
      const res = await api.requestHandoff(session, reason);
      setServiceMode('QUEUED');
      setHandoffInfo(res);
      // Insert system notification
      setMessages(prev => [...prev, {
        id: `sys-${Date.now()}`,
        role: 'SYSTEM',
        content: '已为您转接人工客服，正在排队中，请稍候...',
        cards: [{
          cardId: `card-handoff-${Date.now()}`,
          type: 'handoff_status',
          cardVersion: 1,
          revision: 1,
          data: {
            handoffNo: res.handoffNo,
            mode: 'QUEUED',
            queueState: 'WAITING',
            estimatedWaitSeconds: 120
          }
        }],
        createdTime: Date.now()
      }]);
    } catch (err) {
      setError(err.message || '申请转人工失败');
    }
  };

  const executeCardAction = async (action) => {
    if (!session || !action) return;
    try {
      const payload = {
        actionId: action.actionId,
        cardId: action.payload?.cardId || 'CARD_ACTION',
        cardVersion: 1,
        revision: 1,
        payload: action.payload || {},
        clientMessageId: `act-${Date.now()}`
      };
      await api.cardAction(session, payload);
    } catch (err) {
      setError(err.message || '操作失败');
    }
  };

  const startNewSession = async () => {
    if (busy) return;
    try {
      if (session) {
        await api.close(session).catch(() => {});
        realtimeClient.unsubscribeSession(session);
      }
      if (storageKey) {
        sessionStorage.removeItem(storageKey);
      }
      setSession(null);
      setServiceMode('BOT');
      setHandoffInfo(null);
      setMessages([]);
      setError('');
    } catch (err) {
      setError(err.message || '重置会话失败');
    }
  };

  const value = {
    session,
    serviceMode,
    controlEpoch,
    handoffInfo,
    messages,
    unreadCount,
    isDialogOpen,
    wsConnected,
    busy,
    error,
    setError,
    openDialog,
    closeDialog,
    sendMessage,
    requestHandoff,
    executeCardAction,
    startNewSession
  };

  return (
    <CustomerRealtimeContext.Provider value={value}>
      {children}
    </CustomerRealtimeContext.Provider>
  );
};
