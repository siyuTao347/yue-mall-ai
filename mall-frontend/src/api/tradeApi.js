import { API_BASE, request } from './client';

const unwrap = (response) => {
  if (response && response.code === 200) {
    return response.data;
  }
  throw new Error((response && response.msg) || '接口返回异常');
};

const get = async (url) => unwrap(await request(url));
const post = async (url, body = {}) => unwrap(await request(url, {
  method: 'POST',
  body: JSON.stringify(body)
}));

const query = (params = {}) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, value);
    }
  });
  const text = search.toString();
  return text ? `?${text}` : '';
};

const getPaged = async (url, params = {}) => get(`${url}${query(params)}`);

export const tradeApi = {
  merchant: {
    me: () => get(`${API_BASE.USER}/api/merchant/me`),
    apply: (payload) => post(`${API_BASE.USER}/api/merchant/apply`, payload),
    deposit: (merchantId) => get(`${API_BASE.USER}/api/merchant/${merchantId}/deposit`),
    payDeposit: (merchantId, amount, depositNo) => post(`${API_BASE.USER}/api/merchant/${merchantId}/deposit`, {
      depositNo,
      amount
    }),
    list: (params = {}) => getPaged(`${API_BASE.USER}/api/merchant/admin/list`, params),
    audit: (merchantId, action, reason = '') => post(
      `${API_BASE.USER}/api/merchant/admin/${merchantId}/audit`,
      { action, reason }
    )
  },

  item: {
    listMine: (params = {}) => getPaged(`${API_BASE.ITEM}/api/asset/item/list`, params),
    create: (payload) => post(`${API_BASE.ITEM}/api/asset/item`, payload),
    submit: (itemId) => post(`${API_BASE.ITEM}/api/asset/item/${itemId}/submit`, {}),
    importCards: (itemId, secrets) => post(`${API_BASE.ITEM}/api/asset/item/${itemId}/cards`, { secrets }),
    pendingAudit: (params = {}) => getPaged(`${API_BASE.ITEM}/api/asset/admin/item/pending`, params),
    audit: (itemId, action, reason = '') => post(
      `${API_BASE.ITEM}/api/asset/admin/item/${itemId}/audit`,
      { action, reason }
    )
  },

  order: {
    list: (params = {}) => getPaged(`${API_BASE.ORDER}/api/trade/orders`, params),
    detail: (orderNo) => get(`${API_BASE.ORDER}/api/trade/orders/${orderNo}`),
    quote: (itemId, quantity = 1, sessionId = null) => post(`${API_BASE.ORDER}/api/trade/quotes`, { itemId, quantity, sessionId }),
    create: (itemId, quantity = 1) => post(`${API_BASE.ORDER}/api/trade/orders`, { itemId, quantity }),
    cancel: (orderNo) => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/cancel`, {}),
    deliver: (orderNo, content) => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/deliver`, { content }),
    secrets: (orderNo) => get(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/secrets`),
    delivery: (orderNo) => get(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/delivery`),
    reveal: (orderNo) => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/reveal`, {}),
    confirm: (orderNo) => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/confirm`, {}),
    review: (orderNo, score, content = '') => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/review`, {
      score,
      content
    }),
    addEvidence: (orderNo, content) => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/evidence`, {
      evidenceType: 'TEXT',
      content
    })
  },

  payment: {
    start: (paymentNo) => post(`${API_BASE.ORDER}/api/payment/${paymentNo}/start`, {}),
    pay: (paymentNo) => post(`${API_BASE.ORDER}/api/payment/${paymentNo}/mock-pay`, { result: 'SUCCESS' }),
    methods: (paymentNo) => get(`${API_BASE.ORDER}/api/payment/${paymentNo}/methods`),
    createAttempt: (paymentNo, paymentMethod, requestKey) => post(`${API_BASE.ORDER}/api/payment/${paymentNo}/attempts`, {
      paymentMethod,
      requestKey
    }),
    getAttempt: (attemptNo) => get(`${API_BASE.ORDER}/api/payment/attempts/${attemptNo}`),
    mockConfirm: (attemptNo, result = 'SUCCESS') => post(`${API_BASE.ORDER}/api/payment/attempts/${attemptNo}/mock-confirm`, { result }),
    closeAttempt: (attemptNo, requestKey) => post(`${API_BASE.ORDER}/api/payment/attempts/${attemptNo}/close`, { requestKey })
  },

  reconciliation: {
    listRuns: (params = {}) => getPaged(`${API_BASE.ORDER}/api/trade/admin/reconciliation/runs`, params),
    triggerRun: (channel, businessDate) => post(`${API_BASE.ORDER}/api/trade/admin/reconciliation/runs`, { channel, businessDate }),
    runDetail: (runNo) => get(`${API_BASE.ORDER}/api/trade/admin/reconciliation/runs/${runNo}`),
    listDiffs: (params = {}) => getPaged(`${API_BASE.ORDER}/api/trade/admin/reconciliation/diffs`, params),
    diffAction: (diffNo, action, reason = '', requestKey = '') => post(
      `${API_BASE.ORDER}/api/trade/admin/reconciliation/diffs/${diffNo}/actions`,
      { action, reason, requestKey }
    ),
    fileDetail: (fileId) => get(`${API_BASE.ORDER}/api/trade/admin/reconciliation/files/${fileId}`),
    listSettlements: (params = {}) => getPaged(`${API_BASE.ORDER}/api/trade/admin/channel-settlements`, params),
    listFaultPlans: () => get(`${API_BASE.ORDER}/api/trade/admin/mock/fault-plans`),
    createFaultPlan: (plan) => post(`${API_BASE.ORDER}/api/trade/admin/mock/fault-plans`, plan)
  },

  account: {
    me: () => get(`${API_BASE.USER}/api/account/me`),
    flows: (params = {}) => getPaged(`${API_BASE.USER}/api/account/flows`, params)
  },

  withdraw: {
    apply: (amount, mockAccount, clientToken) => post(`${API_BASE.USER}/api/withdraw/apply`, {
      amount,
      mockAccount,
      clientToken
    }),
    list: (params = {}) => getPaged(`${API_BASE.USER}/api/withdraw/list`, params),
    pending: (params = {}) => getPaged(`${API_BASE.USER}/api/withdraw/admin/pending`, params),
    audit: (withdrawNo, approved, reason = '') => post(`${API_BASE.USER}/api/withdraw/admin/audit`, {
      withdrawNo,
      approved,
      reason
    })
  },

  dispute: {
    open: (orderNo, disputeType, reason, refundAmount) => post(`${API_BASE.ORDER}/api/trade/disputes`, {
      orderNo,
      disputeType,
      reason,
      refundAmount
    }),
    pending: (params = {}) => getPaged(`${API_BASE.ORDER}/api/trade/admin/disputes/pending`, params),
    evidence: (disputeNo) => get(`${API_BASE.ORDER}/api/trade/admin/disputes/${disputeNo}/evidence`),
    arbitrate: (disputeNo, result, reason) => post(
      `${API_BASE.ORDER}/api/trade/admin/disputes/${disputeNo}/arbitrate`,
      { result, reason }
    )
  }
};
