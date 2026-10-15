import { API_BASE, request } from './client';

export const userApi = {
  // 发送 QQ 邮箱验证码
  async sendEmailCode(email) {
    return await request(`${API_BASE.USER}/api/user/sendEmailCode?email=${encodeURIComponent(email)}`, {
      method: 'POST'
    });
  },

  // 邮箱验证码注册 (BCrypt 密码加密 + 100 初始积分大礼包)
  async register({ username, email, code, password, nickname }) {
    return await request(`${API_BASE.USER}/api/user/register`, {
      method: 'POST',
      body: JSON.stringify({ username, email, code, password, nickname })
    });
  },

  // 用户登录 (支持 PASSWORD 密码模式或 CODE 邮箱验证码快捷登录)
  async login({ usernameOrEmail, password, code, loginType = 'PASSWORD' }) {
    return await request(`${API_BASE.USER}/api/user/login`, {
      method: 'POST',
      body: JSON.stringify({ usernameOrEmail, password, code, loginType })
    });
  },

  // 获取当前登录用户详尽资料
  async getUserInfo() {
    return await request(`${API_BASE.USER}/api/user/info`);
  },

  // 获取用户积分概览
  async getPointSummary(userId) {
    const url = userId 
      ? `${API_BASE.USER}/api/user/point/summary?userId=${userId}`
      : `${API_BASE.USER}/api/user/point/summary`;
    try {
      const res = await request(url);
      return res.data || { totalPoints: 0, historyEarnedPoints: 0 };
    } catch {
      return { totalPoints: 0, historyEarnedPoints: 0 };
    }
  },

  // 获取积分动账流水记录
  async getPointRecords(userId, limit = 20) {
    const url = userId
      ? `${API_BASE.USER}/api/user/point/records?userId=${userId}&limit=${limit}`
      : `${API_BASE.USER}/api/user/point/records?limit=${limit}`;
    try {
      const res = await request(url);
      return res.data || [];
    } catch {
      return [];
    }
  },

  // 直接触发消费积分
  async mockRewardPoints(orderNo, payAmount, userId = 1) {
    return await request(`${API_BASE.USER}/api/user/point/mockReward?orderNo=${orderNo}&userId=${userId}&payAmount=${payAmount}`, {
      method: 'POST'
    });
  }
};
