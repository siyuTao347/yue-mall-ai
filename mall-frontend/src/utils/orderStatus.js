export const ORDER_TABS = [
  { key: 'ALL', label: '全部' },
  { key: 'WAIT_PAY', label: '待支付' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'WAIT_DELIVERY', label: '待交付' },
  { key: 'WAIT_CONFIRM', label: '待确认' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'AFTER_SALE', label: '售后/退款' },
  { key: 'CLOSED', label: '已关闭' },
];

export const STATUS_LABELS = {
  CREATE_PENDING: '创建中',
  WAIT_PAY: '待支付',
  PAY_CONFIRMING: '支付确认中',
  CANCELLING: '取消中',
  PAID: '待交付',
  DELIVERED: '待确认收货',
  CONFIRMED: '已确认收货',
  SETTLING: '待结算',
  SETTLED: '已完成',
  CANCELLED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
  CLOSED: '已关闭',
  REJECTED: '已拒绝',
  ARBITRATING: '售后仲裁中',
  RESOLVED: '售后已调解',
};

export const getStatusText = (status, disputeStatus) => {
  if (disputeStatus && disputeStatus !== 'NONE') {
    return STATUS_LABELS[disputeStatus] || `售后(${disputeStatus})`;
  }
  return STATUS_LABELS[status] || status || '未知状态';
};

export const getStatusBadgeClass = (status, disputeStatus) => {
  if (disputeStatus && disputeStatus !== 'NONE') {
    return 'badge-warning';
  }
  switch (status) {
    case 'WAIT_PAY':
      return 'badge-primary';
    case 'PAID':
    case 'DELIVERED':
      return 'badge-info';
    case 'CONFIRMED':
    case 'SETTLING':
    case 'SETTLED':
      return 'badge-success';
    case 'CANCELLED':
    case 'CLOSED':
    case 'REJECTED':
      return 'badge-muted';
    case 'REFUNDING':
    case 'REFUNDED':
      return 'badge-danger';
    case 'CREATE_PENDING':
    case 'PAY_CONFIRMING':
    case 'CANCELLING':
    default:
      return 'badge-warning';
  }
};

export const formatMoney = (val) => {
  if (val === null || val === undefined || val === '') return '¥0.00';
  const num = Number(val);
  return isNaN(num) ? `¥${val}` : `¥${num.toFixed(2)}`;
};

export const formatTime = (isoString) => {
  if (!isoString) return '-';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return d.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    });
  } catch {
    return isoString;
  }
};
