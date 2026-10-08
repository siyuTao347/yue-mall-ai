// 知识库管理页面的展示文案与格式化工具（常量集中在此，避免与组件混导出）。

export const RAG_PAGE_SIZE = 10;

export const RAG_EMPTY_FILTERS = {
  docType: '',
  status: '',
  indexStatus: '',
  keyword: ''
};

export const DOC_TYPE_OPTIONS = [
  { value: 'PRODUCT', label: '商品介绍' },
  { value: 'RULE', label: '交易规则' },
  { value: 'POLICY', label: '平台政策' },
  { value: 'AFTER_SALE', label: '售后口径' },
  { value: 'RISK', label: '风险判例' },
  { value: 'FAQ', label: '常见问题' }
];

export const DOC_STATUS_OPTIONS = [
  { value: 'ENABLED', label: '启用' },
  { value: 'DISABLED', label: '停用' }
];

export const INDEX_STATUS_OPTIONS = [
  { value: 'PENDING', label: '待切片' },
  { value: 'SPLIT', label: '已切片' },
  { value: 'INDEXING', label: '入库中' },
  { value: 'READY', label: '就绪' },
  { value: 'PARTIAL', label: '部分成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELED', label: '已取消' }
];

export const VISIBILITY_OPTIONS = [
  { value: 'PUBLIC', label: '公开（可对外回答）' },
  { value: 'INTERNAL', label: '内部（仅管理员检索）' }
];

export const TASK_STATUS_OPTIONS = [
  { value: 'PENDING', label: '排队中' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'PARTIAL', label: '部分成功' },
  { value: 'FAILED', label: '失败' }
];

const DOC_TYPE_LABELS = Object.fromEntries(DOC_TYPE_OPTIONS.map(item => [item.value, item.label]));
const DOC_STATUS_LABELS = Object.fromEntries(DOC_STATUS_OPTIONS.map(item => [item.value, item.label]));
const INDEX_STATUS_LABELS = Object.fromEntries(INDEX_STATUS_OPTIONS.map(item => [item.value, item.label]));
const TASK_STATUS_LABELS = Object.fromEntries(TASK_STATUS_OPTIONS.map(item => [item.value, item.label]));
const EMBEDDING_LABELS = { PENDING: '待向量化', READY: '已向量化', FAILED: '向量化失败', SKIPPED: '跳过' };

export const labelOf = (map, value, fallback = '-') => (value ? map[value] || value : fallback);
export const docTypeLabel = value => labelOf(DOC_TYPE_LABELS, value);
export const docStatusLabel = value => labelOf(DOC_STATUS_LABELS, value);
export const indexStatusLabel = value => labelOf(INDEX_STATUS_LABELS, value);
export const taskStatusLabel = value => labelOf(TASK_STATUS_LABELS, value);
export const embeddingStatusLabel = value => labelOf(EMBEDDING_LABELS, value);

/** 索引状态标签色调。 */
export const indexStatusTone = status => {
  switch (status) {
    case 'READY': return 'success';
    case 'PARTIAL': return 'warning';
    case 'FAILED': return 'danger';
    case 'INDEXING': return 'info';
    default: return 'default';
  }
};

export const taskStatusTone = status => {
  switch (status) {
    case 'SUCCESS': return 'success';
    case 'PARTIAL': return 'warning';
    case 'FAILED': return 'danger';
    case 'RUNNING': return 'info';
    case 'CANCELED': return 'default';
    default: return 'default';
  }
};

export const embeddingStatusTone = status => {
  switch (status) {
    case 'READY': return 'success';
    case 'FAILED': return 'danger';
    case 'PENDING': return 'warning';
    default: return 'default';
  }
};

/** 文档是否处于「正在向量化」状态，用于轮询与操作禁用。 */
export const isIndexingStatus = status => status === 'INDEXING';

/** 任务是否未结束。 */
export const isTaskActive = status => status === 'PENDING' || status === 'RUNNING';

export const taskProgress = task => {
  if (!task) return 0;
  const total = task.totalChunks || 0;
  if (total <= 0) return task.status === 'SUCCESS' ? 100 : 0;
  return Math.min(100, Math.round(((task.processedChunks || 0) * 100) / total));
};

export const formatTime = value => (value ? String(value).replace('T', ' ').slice(0, 19) : '-');

export const truncate = (text, max = 160) => {
  if (!text) return '';
  return text.length > max ? `${text.slice(0, max)}…` : text;
};
