import { API_BASE, request, RequestError } from './client';
const call = async (path, method = 'GET', body, signal) => {
  const response = await request(`${API_BASE.AGENT}${path}`, {
    method, signal, timeout: 95000,
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  if (response?.code !== 200) throw new RequestError(response?.msg || '客服暂时不可用', { code: response?.errorCode, status: response?.code });
  return response.data;
};
export const customerServiceApi = {
  create: signal => call('/api/agent/sessions', 'POST', {}, signal),
  session: (session, signal) => call(`/api/agent/sessions/${encodeURIComponent(session)}`, 'GET', undefined, signal),
  send: (session, content, clientMessageId, signal) => call(`/api/agent/sessions/${encodeURIComponent(session)}/messages`, 'POST', { content, clientMessageId }, signal),
  sendStream: async (session, content, clientMessageId, onEvent, signal) => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(new DOMException('请求超时', 'TimeoutError')), 95000);
    const onAbort = () => controller.abort(signal?.reason);
    if (signal?.aborted) controller.abort(signal.reason);
    else signal?.addEventListener('abort', onAbort, { once: true });
    const token = localStorage.getItem('valor_token');
    try {
      const response = await fetch(`${API_BASE.AGENT}/api/agent/sessions/${encodeURIComponent(session)}/messages/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          'X-Request-Id': globalThis.crypto?.randomUUID?.() || `req-${Date.now()}`,
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify({ content, clientMessageId }),
        signal: controller.signal
      });
      if (!response.ok) {
        let body;
        try { body = await response.json(); } catch { body = null; }
        throw new RequestError(body?.msg || `HTTP ${response.status}: ${response.statusText}`, {
          code: body?.errorCode || `HTTP_${response.status}`,
          status: response.status,
          retryable: response.status >= 500 || response.status === 429
        });
      }
      if (!response.body) throw new RequestError('浏览器不支持客服流式响应', { code: 'STREAM_UNAVAILABLE' });
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let result = null;
      const dispatch = block => {
        let event = 'message';
        const data = [];
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
        }
        if (!data.length) return;
        let payload;
        try { payload = JSON.parse(data.join('\n')); } catch { return; }
        onEvent?.({ event, data: payload });
        if (event === 'run.completed' || event === 'run.failed') result = payload;
      };
      while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done }).replace(/\r\n/g, '\n');
        let boundary;
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
          dispatch(buffer.slice(0, boundary));
          buffer = buffer.slice(boundary + 2);
        }
        if (done) break;
      }
      if (buffer.trim()) dispatch(buffer);
      if (!result) throw new RequestError('客服流式响应意外结束', { code: 'STREAM_INCOMPLETE', retryable: true });
      return result;
    } catch (error) {
      if (error instanceof RequestError) throw error;
      if (controller.signal.aborted) {
        if (signal?.aborted) throw new RequestError('请求已取消', { code: 'ABORTED' });
        throw new RequestError('请求超时，请稍后重试', { code: 'TIMEOUT', retryable: true });
      }
      throw new RequestError('网络异常，请检查网络后重试', { code: 'NETWORK_ERROR', retryable: true });
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener('abort', onAbort);
    }
  },
  close: session => call(`/api/agent/sessions/${encodeURIComponent(session)}/close`, 'POST', {}),
  feedback: (run, rating) => call(`/api/agent/runs/${encodeURIComponent(run)}/feedback`, 'POST', { rating, comment: '' })
};
const admin = '/api/admin/agent';
export const customerAdminApi = {
  list: section => call(`${admin}/${section}`),
  detail: (section, id) => call(`${admin}/${section}/${encodeURIComponent(id)}`),
  process: id => call(`${admin}/handoffs/${encodeURIComponent(id)}/process`, 'POST', {}),
  resolve: (id, resolution) => call(`${admin}/handoffs/${encodeURIComponent(id)}/resolve`, 'POST', { resolution }),
  build: evaluations => call(`${admin}/intent-releases`, 'POST', { evaluations }),
  activate: id => call(`${admin}/intent-releases/${encodeURIComponent(id)}/activate`, 'POST', {}),
  revoke: id => call(`${admin}/intent-releases/${encodeURIComponent(id)}/revoke`, 'POST', {})
};
