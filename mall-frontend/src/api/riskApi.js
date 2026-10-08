import { API_BASE, RequestError, request } from './client';

const unwrap = response => {
  if (response && response.code === 200) {
    return response.data;
  }
  throw new RequestError((response && response.msg) || '接口返回异常', {
    code: (response && response.errorCode) || 'RISK_BUSINESS_ERROR',
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
  return `${API_BASE.RISK}${path}${text ? `?${text}` : ''}`;
};

const get = async (url, options = {}) => unwrap(await request(url, options));
const post = async (url, body, options = {}) => unwrap(await request(url, {
  ...options,
  method: 'POST',
  body: JSON.stringify(body || {}),
  timeout: options.timeout || 15000
}));

export const riskApi = {
  dictionaries: ({ signal } = {}) => get(buildUrl('/api/admin/risk/dictionaries'), { signal }),
  cases: (query = {}, { signal } = {}) => get(buildUrl('/api/admin/risk/cases', query), { signal }),
  detail: (caseNo, { signal } = {}) => get(
    buildUrl(`/api/admin/risk/cases/${encodeURIComponent(caseNo)}`),
    { signal }
  ),
  assign: (caseNo, payload) => post(`/api/admin/risk/cases/${encodeURIComponent(caseNo)}/assign`, payload),
  resolve: (caseNo, payload) => post(`/api/admin/risk/cases/${encodeURIComponent(caseNo)}/resolve`, payload),
  close: (caseNo, payload) => post(`/api/admin/risk/cases/${encodeURIComponent(caseNo)}/close`, payload),
  reopen: (caseNo, payload) => post(`/api/admin/risk/cases/${encodeURIComponent(caseNo)}/reopen`, payload),
  resendCommand: (caseNo, commandNo, payload) => post(
    `/api/admin/risk/cases/${encodeURIComponent(caseNo)}/commands/${encodeURIComponent(commandNo)}/resend`,
    payload
  ),
  manualComplete: (caseNo, commandNo, payload) => post(
    `/api/admin/risk/cases/${encodeURIComponent(caseNo)}/commands/${encodeURIComponent(commandNo)}/manual-complete`,
    payload
  )
};
