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
