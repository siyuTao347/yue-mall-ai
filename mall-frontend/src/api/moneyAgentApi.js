import { API_BASE, request, RequestError } from './client';

const call = async (path, method = 'GET', body, signal) => {
  const response = await request(`${API_BASE.AGENT}${path}`, {
    method,
    signal,
    timeout: 30000,
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  if (response?.code !== 200) {
    throw new RequestError(response?.msg || '资金服务请求失败', {
      code: response?.errorCode,
      status: response?.code
    });
  }
  return response.data;
};

export const moneyAgentApi = {
  // User/Client APIs
  submitInvestigation: (body, signal) => call('/api/agent/money/investigations', 'POST', body, signal),
  getDispatch: (dispatchNo, signal) => call(`/api/agent/money/dispatches/${encodeURIComponent(dispatchNo)}`, 'GET', undefined, signal),
  listCases: (signal) => call('/api/agent/money/cases', 'GET', undefined, signal),
  getCase: (caseNo, signal) => call(`/api/agent/money/cases/${encodeURIComponent(caseNo)}`, 'GET', undefined, signal),
  submitInput: (caseNo, inputNo, body, signal) =>
    call(`/api/agent/money/cases/${encodeURIComponent(caseNo)}/inputs/${encodeURIComponent(inputNo)}`, 'POST', body, signal),
  cancelCase: (caseNo, body = {}, signal) =>
    call(`/api/agent/money/cases/${encodeURIComponent(caseNo)}/cancel`, 'POST', body, signal),

  // Staff APIs
  listStaffSessionCases: (sessionNo, signal) =>
    call(`/api/staff/agent/sessions/${encodeURIComponent(sessionNo)}/money-cases`, 'GET', undefined, signal),

  // Admin/Finance Workbench APIs
  listAdminCases: (params = {}, signal) => {
    const query = new URLSearchParams(params).toString();
    return call(`/api/admin/money-agent/cases${query ? `?${query}` : ''}`, 'GET', undefined, signal);
  },
  getAdminCaseDetail: (caseNo, signal) =>
    call(`/api/admin/money-agent/cases/${encodeURIComponent(caseNo)}`, 'GET', undefined, signal)
};

