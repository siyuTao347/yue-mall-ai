import { API_BASE, request } from './client';

class CustomerRealtimeClient {
  constructor() {
    this.ws = null;
    this.ticket = null;
    this.role = 'USER';
    this.connected = false;
    this.connecting = false;
    this.reconnectTimer = null;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 10;
    this.heartbeatTimer = null;
    this.subscribedSessions = new Set();
    this.eventListeners = new Map(); // eventType -> Set of callbacks
    this.statusListeners = new Set();
  }

  setRole(role) {
    if (this.role !== role) {
      this.role = role || 'USER';
      if (this.connected || this.connecting) {
        this.disconnect();
        this.connect();
      }
    }
  }

  on(eventType, callback) {
    if (!this.eventListeners.has(eventType)) {
      this.eventListeners.set(eventType, new Set());
    }
    this.eventListeners.get(eventType).add(callback);
    return () => this.off(eventType, callback);
  }

  off(eventType, callback) {
    const listeners = this.eventListeners.get(eventType);
    if (listeners) {
      listeners.delete(callback);
    }
  }

  onStatusChange(callback) {
    this.statusListeners.add(callback);
    callback(this.connected);
    return () => this.statusListeners.delete(callback);
  }

  _notifyStatus(status) {
    this.connected = status;
    for (const listener of this.statusListeners) {
      try {
        listener(status);
      } catch (err) {
        console.error('[RealtimeClient] status listener error:', err);
      }
    }
  }

  _dispatchEvent(eventType, payload) {
    const listeners = this.eventListeners.get(eventType);
    if (listeners) {
      for (const listener of listeners) {
        try {
          listener(payload);
        } catch (err) {
          console.error(`[RealtimeClient] error in listener for ${eventType}:`, err);
        }
      }
    }

    // Also dispatch wildcard event
    const wildcard = this.eventListeners.get('*');
    if (wildcard) {
      for (const listener of wildcard) {
        try {
          listener(eventType, payload);
        } catch (err) {
          console.error('[RealtimeClient] error in wildcard listener:', err);
        }
      }
    }
  }

  async acquireTicket() {
    const path = this.role === 'STAFF'
      ? '/api/staff/agent/realtime/tickets'
      : '/api/agent/realtime/tickets';
    
    const res = await request(`${API_BASE.AGENT}${path}`, {
      method: 'POST',
      body: JSON.stringify({ role: this.role })
    });

    if (res?.code !== 200 || !res?.data?.ticket) {
      throw new Error(res?.msg || '获取实时连接凭据失败');
    }
    return res.data.ticket;
  }

  async connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    this.connecting = true;
    try {
      this.ticket = await this.acquireTicket();
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const host = window.location.host;
      const wsUrl = `${protocol}//${host}/api/agent/realtime/ws?ticket=${encodeURIComponent(this.ticket)}`;

      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        this.connecting = false;
        this.reconnectAttempts = 0;
        this._notifyStatus(true);
        this._startHeartbeat();

        // Resubscribe all active sessions
        for (const sessionNo of this.subscribedSessions) {
          this._sendRaw({ action: 'session.subscribe', sessionNo });
        }
      };

      this.ws.onmessage = (event) => {
        try {
          const envelope = JSON.parse(event.data);
          const type = envelope.type || envelope.action;
          const payload = envelope.payload !== undefined ? envelope.payload : envelope;

          if (type === 'heartbeat.ack' || type === 'pong') {
            return;
          }

          this._dispatchEvent(type, payload);
        } catch (err) {
          console.warn('[RealtimeClient] Failed to parse message:', event.data, err);
        }
      };

      this.ws.onerror = (err) => {
        console.warn('[RealtimeClient] WebSocket error:', err);
      };

      this.ws.onclose = () => {
        this.connecting = false;
        this._stopHeartbeat();
        this._notifyStatus(false);
        this._scheduleReconnect();
      };

    } catch (err) {
      this.connecting = false;
      this._notifyStatus(false);
      this._scheduleReconnect();
    }
  }

  _scheduleReconnect() {
    if (this.reconnectTimer) return;
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.warn('[RealtimeClient] Max reconnect attempts reached, backoff pause.');
      return;
    }

    const delay = Math.min(1000 * Math.pow(1.8, this.reconnectAttempts), 15000);
    this.reconnectAttempts++;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this._sendRaw({ action: 'heartbeat', timestamp: Date.now() });
      }
    }, 25000);
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  _sendRaw(data) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
      return true;
    }
    return false;
  }

  subscribeSession(sessionNo) {
    if (!sessionNo) return;
    this.subscribedSessions.add(sessionNo);
    if (this.connected) {
      this._sendRaw({ action: 'session.subscribe', sessionNo });
    } else {
      this.connect();
    }
  }

  unsubscribeSession(sessionNo) {
    if (!sessionNo) return;
    this.subscribedSessions.delete(sessionNo);
  }

  sendMessage(sessionNo, content, clientMessageId) {
    const payload = {
      action: 'message.send',
      sessionNo,
      content,
      clientMessageId: clientMessageId || `cm-${Date.now()}-${Math.random().toString(36).slice(2)}`
    };
    return this._sendRaw(payload);
  }

  markRead(sessionNo, lastReadMessageSeq) {
    return this._sendRaw({
      action: 'read.mark',
      sessionNo,
      lastReadMessageSeq
    });
  }

  sendTyping(sessionNo, typing = true) {
    return this._sendRaw({
      action: 'typing',
      sessionNo,
      typing
    });
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this._stopHeartbeat();
    if (this.ws) {
      this.ws.onclose = null;
      this.ws.close();
      this.ws = null;
    }
    this.connecting = false;
    this._notifyStatus(false);
  }
}

export const realtimeClient = new CustomerRealtimeClient();
