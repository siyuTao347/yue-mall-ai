import { API_BASE, RequestError, request } from './client';

const unwrap = response => {
  if (response && response.code === 200) {
    return response.data;
  }
  throw new RequestError((response && response.msg) || '接口返回异常', {
    code: (response && response.errorCode) || 'AGENT_BUSINESS_ERROR',
    traceId: response && response.traceId,
    retryable: false,
    status: response && response.code
  });
};

const buildUrl = (path, params = {}) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, value);
    }
  });
  const text = search.toString();
  return `${API_BASE.AGENT}${path}${text ? `?${text}` : ''}`;
};

const get = async (url, options = {}) => unwrap(await request(url, options));
const send = async (method, url, body, options = {}) => unwrap(await request(url, {
  ...options,
  method,
  body: JSON.stringify(body || {})
}));
const post = (url, body, options = {}) => send('POST', url, body, options);
const put = (url, body, options = {}) => send('PUT', url, body, options);
const del = (url, options = {}) => send('DELETE', url, null, options);

const BASE = '/api/admin/agent/knowledge';

/** 管理端知识库接口（设计文档 10.3）：仅管理员可调用，网关与服务端双重校验。 */
export const knowledgeApi = {
  overview: ({ signal } = {}) => get(buildUrl(`${BASE}/stats/overview`), { signal }),
  documents: (query = {}, { signal } = {}) => get(buildUrl(`${BASE}/documents`, query), { signal }),
  document: (id, { signal } = {}) => get(buildUrl(`${BASE}/documents/${id}`), { signal }),
  documentContent: (id, { signal } = {}) => get(buildUrl(`${BASE}/documents/${id}/content`), { signal }),
  versions: (docNo, { signal } = {}) => get(buildUrl(`${BASE}/documents/${encodeURIComponent(docNo)}/versions`), { signal }),
  createDocument: body => post(`${BASE}/documents`, body, { timeout: 20000 }),
  updateDocument: (id, body) => put(`${BASE}/documents/${id}`, body, { timeout: 20000 }),
  previewSplit: id => post(`${BASE}/documents/${id}/split`, {}, { timeout: 20000 }),
  chunks: (id, { signal } = {}) => get(buildUrl(`${BASE}/documents/${id}/chunks`), { signal }),
  reindex: (id, reembed = false) => post(buildUrl(`${BASE}/documents/${id}/index`, { reembed }), {}),
  reEmbed: id => post(`${BASE}/documents/${id}/re-embed`, {}),
  reEmbedChunk: chunkId => post(`${BASE}/chunks/${chunkId}/re-embed`, {}),
  retrievalTest: body => post(`${BASE}/retrieval-test`, body, { timeout: 20000 }),
  tasks: (query = {}, { signal } = {}) => get(buildUrl(`${BASE}/tasks`, query), { signal }),
  task: (taskNo, { signal } = {}) => get(buildUrl(`${BASE}/tasks/${encodeURIComponent(taskNo)}`), { signal }),
  retryTask: taskNo => post(`${BASE}/tasks/${encodeURIComponent(taskNo)}/retry`, {}),
  enable: id => post(`${BASE}/documents/${id}/enable`, {}),
  disable: id => post(`${BASE}/documents/${id}/disable`, {}),
  remove: id => del(`${BASE}/documents/${id}`)
};
