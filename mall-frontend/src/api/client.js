export const API_BASE = {
  ITEM: 'http://localhost:8082',
  ORDER: 'http://localhost:8083',
  USER: 'http://localhost:8081'
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
