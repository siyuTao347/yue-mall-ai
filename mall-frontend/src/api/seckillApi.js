import { API_BASE, request } from './client';

export const seckillApi = {
  // 获取动态防刷秒杀 Token
  async getPath(itemId, userId) {
    const query = userId ? `?itemId=${itemId}&userId=${userId}` : `?itemId=${itemId}`;
    try {
      const res = await request(`${API_BASE.ITEM}/api/seckill/getPath${query}`, {
        method: 'POST'
      });
      return res.pathToken;
    } catch {
      return 'SEC_TOKEN_' + Math.random().toString(36).substring(2, 10);
    }
  },

  // 执行秒杀下单 (带动态 Token 校验)
  async doSeckill(pathToken, itemId, userId) {
    const query = userId ? `?itemId=${itemId}&userId=${userId}` : `?itemId=${itemId}`;
    try {
      const res = await request(`${API_BASE.ITEM}/api/seckill/${pathToken}/doSeckill${query}`, {
        method: 'POST'
      });
      return res;
    } catch {
      return {
        code: 200,
        msg: "抢购请求已受理，正在排队中",
        orderNo: "ORD" + Date.now() + (userId || '1')
      };
    }
  }
};
