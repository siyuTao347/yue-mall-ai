import { INDEX_STATUS_OPTIONS, indexStatusLabel } from './ragLabels';

const CARDS = [
  { key: 'documentTotal', label: '知识文档', hint: total => `启用 ${total.enabled} 篇` },
  { key: 'parentChunkTotal', label: '父片', hint: () => '按小节切分' },
  { key: 'childChunkTotal', label: '子片', hint: () => '向量召回单位' },
  { key: 'embeddedTotal', label: '已向量化', hint: total => `失败 ${total.failed}` },
  { key: 'vectorCount', label: '向量条数', hint: () => 'pgvector 实际存储' }
];

/** 概览卡片：文档 / 分片 / 向量规模与索引状态分布。 */
export const RagOverviewCards = ({ overview, loading }) => {
  const data = overview || {};
  const totals = {
    enabled: data.documentEnabled ?? 0,
    failed: data.embeddedFailed ?? 0
  };
  const distribution = data.indexStatusDistribution || {};
  const distributionText = INDEX_STATUS_OPTIONS
    .map(option => `${indexStatusLabel(option.value)} ${distribution[option.value] ?? 0}`)
    .join(' · ');

  return (
    <div className="rag-overview">
      {CARDS.map(card => (
        <div key={card.key} className={`rag-card ${card.key === 'embeddedTotal' && totals.failed > 0 ? 'warn' : ''}`}>
          <span className="rag-card-label">{card.label}</span>
          <strong className="rag-card-value">{loading ? '—' : (data[card.key] ?? 0)}</strong>
          <span className="rag-card-hint">{loading ? '加载中' : card.hint(totals)}</span>
        </div>
      ))}
      <div className="rag-card wide">
        <span className="rag-card-label">
          向量库
          <span className={`rag-badge ${data.vectorAvailable ? 'success' : 'danger'}`}>
            {loading ? '检测中' : data.vectorAvailable ? '可用' : '不可用'}
          </span>
        </span>
        <strong className="rag-card-value small">{data.vectorVersion || 'pgvector'}</strong>
        <span className="rag-card-hint">{loading ? '加载中' : `索引状态分布：${distributionText}`}</span>
      </div>
    </div>
  );
};
