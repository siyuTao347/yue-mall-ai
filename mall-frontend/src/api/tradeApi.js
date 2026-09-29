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

export const tradeApi = {
  merchant: {
    me: () => get(`${API_BASE.USER}/api/merchant/me`),
    apply: (payload) => post(`${API_BASE.USER}/api/merchant/apply`, payload),
    deposit: (merchantId) => get(`${API_BASE.USER}/api/merchant/${merchantId}/deposit`),
    payDeposit: (merchantId, amount, depositNo) => post(`${API_BASE.USER}/api/merchant/${merchantId}/deposit`, {
      depositNo,
      amount
    }),
    list: (status = '') => get(`${API_BASE.USER}/api/merchant/admin/list?status=${encodeURIComponent(status)}`),
    audit: (merchantId, action, reason = '') => post(
      `${API_BASE.USER}/api/merchant/admin/${merchantId}/audit`,
      { action, reason }
    )
  },

  item: {
    listMine: () => get(`${API_BASE.ITEM}/api/asset/item/list`),
    create: (payload) => post(`${API_BASE.ITEM}/api/asset/item`, payload),
    submit: (itemId) => post(`${API_BASE.ITEM}/api/asset/item/${itemId}/submit`, {}),
    importCards: (itemId, secrets) => post(`${API_BASE.ITEM}/api/asset/item/${itemId}/cards`, { secrets }),
    pendingAudit: () => get(`${API_BASE.ITEM}/api/asset/admin/item/pending`),
    audit: (itemId, action, reason = '') => post(
      `${API_BASE.ITEM}/api/asset/admin/item/${itemId}/audit`,
      { action, reason }
    )
  },

  order: {
    list: () => get(`${API_BASE.ORDER}/api/trade/orders`),
    create: (itemId, quantity = 1) => post(`${API_BASE.ORDER}/api/trade/orders`, { itemId, quantity }),
    cancel: (orderNo) => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/cancel`, {}),
    deliver: (orderNo, content) => post(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/deliver`, { content }),
    secrets: (orderNo) => get(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/secrets`),
    delivery: (orderNo) => get(`${API_BASE.ORDER}/api/trade/orders/${orderNo}/delivery`),
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
    pay: (paymentNo) => post(`${API_BASE.ORDER}/api/payment/${paymentNo}/mock-pay`, { result: 'SUCCESS' })
  },

  account: {
    me: () => get(`${API_BASE.USER}/api/account/me`),
    flows: () => get(`${API_BASE.USER}/api/account/flows`)
  },

  withdraw: {
    apply: (amount, mockAccount, clientToken) => post(`${API_BASE.USER}/api/withdraw/apply`, {
      amount,
      mockAccount,
      clientToken
    }),
    list: () => get(`${API_BASE.USER}/api/withdraw/list`),
    pending: () => get(`${API_BASE.USER}/api/withdraw/admin/pending`),
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
    pending: () => get(`${API_BASE.ORDER}/api/trade/admin/disputes/pending`),
    evidence: (disputeNo) => get(`${API_BASE.ORDER}/api/trade/admin/disputes/${disputeNo}/evidence`),
    arbitrate: (disputeNo, result, reason) => post(
      `${API_BASE.ORDER}/api/trade/admin/disputes/${disputeNo}/arbitrate`,
      { result, reason }
    )
  }
};
