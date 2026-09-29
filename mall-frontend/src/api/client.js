const gatewayBaseUrl = import.meta.env.VITE_API_BASE_URL || '';

export const API_BASE = {
  ITEM: gatewayBaseUrl,
  ORDER: gatewayBaseUrl,
  USER: gatewayBaseUrl
};

export async function request(url, options = {}) {
  const token = localStorage.getItem('valor_token');
  const headers = {
    'Content-Type': 'application/json',
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    ...(options.headers || {})
  };

  try {
    const res = await fetch(url, {
      ...options,
      headers
    });
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      const msg = (data && data.msg) || `HTTP ${res.status}: ${res.statusText}`;
      throw new Error(msg);
    }
    return data;
  } catch (err) {
    console.warn(`Request failed: ${url}`, err);
    throw err;
  }
}
