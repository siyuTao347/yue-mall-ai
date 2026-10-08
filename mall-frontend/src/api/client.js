const gatewayBaseUrl = import.meta.env.VITE_API_BASE_URL || '';

export const API_BASE = {
  ITEM: gatewayBaseUrl,
  ORDER: gatewayBaseUrl,
  USER: gatewayBaseUrl,
  RISK: gatewayBaseUrl,
  AGENT: gatewayBaseUrl
};

const DEFAULT_TIMEOUT = 10000;

export class RequestError extends Error {
  constructor(message, { code = 'REQUEST_FAILED', traceId = null, retryable = false, status = 0 } = {}) {
    super(message);
    this.name = 'RequestError';
    this.code = code;
    this.traceId = traceId;
    this.retryable = retryable;
    this.status = status;
  }
}

const createRequestId = () => {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
};

const parseBody = async response => {
  const text = await response.text().catch(() => '');
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
};

const normalizeError = error => {
  if (error instanceof RequestError) return error;
  if (error && error.name === 'TimeoutError') {
    return new RequestError('请求超时，请稍后重试', { code: 'TIMEOUT', retryable: true });
  }
  if (error && error.name === 'AbortError') {
    return new RequestError('请求已取消', { code: 'ABORTED', retryable: false });
  }
  return new RequestError('网络异常，请检查网络后重试', { code: 'NETWORK_ERROR', retryable: true });
};

export async function request(url, options = {}) {
  const { timeout = DEFAULT_TIMEOUT, signal, requestId, ...fetchOptions } = options;
  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(new DOMException('请求超时', 'TimeoutError')),
    timeout
  );
  const onAbort = () => controller.abort(signal ? signal.reason : undefined);
  if (signal) {
    if (signal.aborted) {
      controller.abort(signal.reason);
    } else {
      signal.addEventListener('abort', onAbort, { once: true });
    }
  }

  const token = localStorage.getItem('valor_token');
  const headers = {
    'Content-Type': 'application/json',
    'X-Request-Id': requestId || createRequestId(),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(fetchOptions.headers || {})
  };

  try {
    const res = await fetch(url, { ...fetchOptions, headers, signal: controller.signal });
    const data = await parseBody(res);
    if (!res.ok) {
      const code = (data && data.errorCode) || `HTTP_${res.status}`;
      const message = (data && data.msg) || `HTTP ${res.status}: ${res.statusText}`;
      const traceId = (data && data.traceId) || res.headers.get('X-Request-Id');
      throw new RequestError(message, {
        code,
        traceId,
        retryable: res.status >= 500 || res.status === 429,
        status: res.status
      });
    }
    return data;
  } catch (error) {
    const normalized = normalizeError(error);
    if (normalized.code !== 'ABORTED') {
      console.warn(`[request] ${normalized.code} ${url} ${normalized.message}`);
    }
    throw normalized;
  } finally {
    clearTimeout(timer);
    if (signal) {
      signal.removeEventListener('abort', onAbort);
    }
  }
}
