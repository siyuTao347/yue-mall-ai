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
  const lastEventSeqRef = useRef(0);

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
        lastEventSeqRef.current = snap.snapshotSeq != null ? Number(snap.snapshotSeq) : (snap.lastEventSeq != null ? Number(snap.lastEventSeq) : 0);
        if (snap.handoff) setHandoffInfo(snap.handoff);

        const rawMessages = snap.messages || snap.recentMessages || [];
        const list = rawMessages.map(m => {
          let cards = m.cards;
          let citations = m.citations;
          let suggestedActions = m.suggestedActions;
          const payload = m.payload;
          if ((!cards || cards.length === 0) && payload) {
            if (Array.isArray(payload)) cards = payload;
            else if (payload.cards) cards = payload.cards;
            else if (payload.richCard) cards = [payload.richCard];
          }
          if ((!cards || cards.length === 0) && m.richCard) cards = [m.richCard];
          if ((!citations || citations.length === 0) && payload?.citations) citations = payload.citations;
          if ((!suggestedActions || suggestedActions.length === 0) && payload?.suggestedActions) suggestedActions = payload.suggestedActions;

          return {
            id: m.message_no || m.messageNo,
            role: m.role,
            content: m.content,
            cards: cards || [],
            citations: citations || [],
            suggestedActions: suggestedActions || [],
            createdTime: m.created_time || m.createdTime,
            clientMessageId: m.client_message_id || m.clientMessageId
          };
        });
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
    const processEvent = (eventType, payload, envelope) => {
      switch (eventType) {
        case 'message.created': {
          const msg = payload.message || payload;
          const msgNo = msg.messageNo || msg.message_no || msg.id;
          const clientMsgId = msg.clientMessageId || msg.client_message_id;
          const runNo = msg.runNo || msg.run_no;

          setMessages(prev => {
            // Check if matches optimistic message or same message/run
            const existingIdx = prev.findIndex(m =>
              (clientMsgId && m.clientMessageId === clientMsgId) ||
              (msgNo && m.id === msgNo) ||
              (runNo && m.runNo === runNo)
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
              runNo: runNo,
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
          const msgNo = payload.messageNo;
          const runNo = payload.runNo;
          const clientMsgId = payload.clientMessageId;

          setMessages(prev => {
            const idx = prev.findIndex(m =>
              (msgNo && m.id === msgNo) ||
              (runNo && m.runNo === runNo) ||
              (clientMsgId && m.clientMessageId === clientMsgId)
            );
            const targetIdx = idx >= 0 ? idx : (prev.length > 0 && prev[prev.length - 1].role === 'AGENT' && prev[prev.length - 1].streaming ? prev.length - 1 : -1);
            if (targetIdx >= 0) {
              const updated = [...prev];
              updated[targetIdx] = {
                ...updated[targetIdx],
                stage: payload.label || '正在处理…'
              };
              return updated;
            }
            return prev;
          });
          break;
        }

        case 'answer.delta': {
          const msgNo = payload.messageNo;
          const runNo = payload.runNo;
          const clientMsgId = payload.clientMessageId;

          setMessages(prev => {
            const idx = prev.findIndex(m =>
              (msgNo && m.id === msgNo) ||
              (runNo && m.runNo === runNo) ||
              (clientMsgId && m.clientMessageId === clientMsgId)
            );
            const targetIdx = idx >= 0 ? idx : (prev.length > 0 && prev[prev.length - 1].role === 'AGENT' ? prev.length - 1 : -1);
            if (targetIdx >= 0) {
              const updated = [...prev];
              updated[targetIdx] = {
                ...updated[targetIdx],
                content: (updated[targetIdx].content || '') + (payload.delta || ''),
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
          const updatedCardId = updatedCard.cardId || updatedCard.id;
          if (!updatedCardId) return;

          setMessages(prev => prev.map(m => {
            if (!m.cards || !m.cards.length) return m;
            return {
              ...m,
              cards: m.cards.map(c => {
                if ((c.cardId || c.id) === updatedCardId) {
                  const existingRev = c.revision != null ? Number(c.revision) : 0;
                  const newRev = updatedCard.revision != null ? Number(updatedCard.revision) : 0;
                  if (newRev > existingRev) {
                    return { ...c, ...updatedCard };
                  }
                }
                return c;
              })
            };
          }));
          break;
        }

        case 'money.case.changed': {
          const caseNo = payload.caseNo;
          const projectionRevision = payload.projectionRevision != null ? Number(payload.projectionRevision) : 0;
          setMessages(prev => prev.map(m => {
            if (!m.cards || !m.cards.length) return m;
            return {
              ...m,
              cards: m.cards.map(c => {
                if (c.data?.caseNo === caseNo || c.caseNo === caseNo) {
                  const curRev = c.data?.projectionRevision != null ? Number(c.data.projectionRevision) : (c.revision != null ? Number(c.revision) : 0);
                  if (projectionRevision > curRev) {
                    return {
                      ...c,
                      revision: projectionRevision,
                      data: {
                        ...c.data,
                        caseState: payload.caseState || c.data?.caseState,
                        taskState: payload.taskState || c.data?.taskState,
                        projectionRevision: projectionRevision,
                        summary: payload.summary || c.data?.summary
                      }
                    };
                  }
                }
                return c;
              })
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
    };

    const unsubEvent = realtimeClient.on('*', (eventType, payload, envelope) => {
      const currentSessionNo = activeSessionRef.current;
      const targetSessionNo = envelope?.sessionNo || payload?.sessionNo;
      if (targetSessionNo && targetSessionNo !== currentSessionNo) {
        return;
      }

      const eventSeq = envelope?.eventSeq != null ? Number(envelope.eventSeq) : null;
      if (eventSeq != null && eventSeq > 0) {
        if (eventSeq <= lastEventSeqRef.current) {
          return; // Duplicate event, skip
        }
        if (lastEventSeqRef.current > 0 && eventSeq > lastEventSeqRef.current + 1 && currentSessionNo) {
          // Gap detected: sync missing events
          const startSeq = lastEventSeqRef.current;
          api.sync(currentSessionNo, startSeq).then(syncData => {
            if (syncData?.events && Array.isArray(syncData.events)) {
              for (const missingEnv of syncData.events) {
                const mSeq = missingEnv?.eventSeq != null ? Number(missingEnv.eventSeq) : 0;
                if (mSeq > lastEventSeqRef.current) {
                  lastEventSeqRef.current = mSeq;
                  processEvent(missingEnv.type || missingEnv.eventType, missingEnv.payload !== undefined ? missingEnv.payload : missingEnv, missingEnv);
                }
              }
            }
          }).catch(err => console.warn('Failed to sync missing events:', err));
        }
        lastEventSeqRef.current = Math.max(lastEventSeqRef.current, eventSeq);
      }

      processEvent(eventType, payload, envelope);
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
    lastEventSeqRef.current = 0;
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
