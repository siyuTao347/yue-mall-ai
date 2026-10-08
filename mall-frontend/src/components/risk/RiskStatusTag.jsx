export const RiskStatusTag = ({ text, tone = 'default' }) => (
  <span className={`risk-tag risk-tag-${tone}`}>{text || '-'}</span>
);
