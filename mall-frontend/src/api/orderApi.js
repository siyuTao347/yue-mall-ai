import { API_BASE, request } from './client';

export const orderApi = {
  // 支付订单，成功后由后端同步积分
  async payOrder(orderNo) {
    return await request(`${API_BASE.ORDER}/api/order/pay?orderNo=${orderNo}`, {
      method: 'POST'
    });
  },

  // 查询订单详情
  async getOrderDetail(orderNo) {
    return await request(`${API_BASE.ORDER}/api/order/detail?orderNo=${orderNo}`);
  },

  // 普通商品下单 (过渡/测试使用，需严格鉴权与正整数校验)
  async createTccOrder(itemId, count = 1, mockException = false) {
    const token = localStorage.getItem('valor_token');
    const headers = {};
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    const res = await fetch(`${API_BASE.ORDER}/api/order/createTcc?itemId=${itemId}&count=${count}&mockException=${mockException}`, {
      method: 'POST',
      headers
    });
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(errText || '下单失败');
    }
    const text = await res.text();
    const match = text.match(/订单号:\s*([A-Za-z0-9_]+)/);
    if (!match) {
      throw new Error(text || '未能获取有效订单号');
    }
    return match[1];
  }
};
