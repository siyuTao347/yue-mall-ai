import { useEffect, useState } from 'react';
import { DOC_STATUS_OPTIONS, DOC_TYPE_OPTIONS, INDEX_STATUS_OPTIONS } from './ragLabels';

const renderOptions = options => options.map(option => (
  <option key={option.value} value={option.value}>{option.label}</option>
));

/** 文档筛选栏：类型 / 状态 / 索引状态 / 关键词。 */
export const RagDocumentFilters = ({ value, disabled, onChange, onReset, onRefresh, onCreate }) => {
  const [keyword, setKeyword] = useState(value.keyword || '');

  // 重置筛选时同步输入框（外部值变化才覆盖本地输入）
  useEffect(() => {
    setKeyword(value.keyword || '');
  }, [value.keyword]);

  const update = (field, fieldValue) => onChange({ ...value, [field]: fieldValue });

  const submit = event => {
    event.preventDefault();
    onChange({ ...value, keyword: keyword.trim() });
  };

  return (
    <form className="rag-filters" onSubmit={submit}>
      <label className="rag-field">
        <span>文档类型</span>
        <select value={value.docType} disabled={disabled} onChange={event => update('docType', event.target.value)}>
          <option value="">全部</option>
          {renderOptions(DOC_TYPE_OPTIONS)}
        </select>
      </label>
      <label className="rag-field">
        <span>启用状态</span>
        <select value={value.status} disabled={disabled} onChange={event => update('status', event.target.value)}>
          <option value="">全部</option>
          {renderOptions(DOC_STATUS_OPTIONS)}
        </select>
      </label>
      <label className="rag-field">
        <span>索引状态</span>
        <select value={value.indexStatus} disabled={disabled} onChange={event => update('indexStatus', event.target.value)}>
          <option value="">全部</option>
          {renderOptions(INDEX_STATUS_OPTIONS)}
        </select>
      </label>
      <label className="rag-field rag-field-keyword">
        <span>关键词</span>
        <input type="text" maxLength={64} value={keyword} disabled={disabled}
          placeholder="文档编号 / 标题" onChange={event => setKeyword(event.target.value)} />
      </label>
      <div className="rag-filter-actions">
        <button type="submit" className="rag-button primary" disabled={disabled}>查询</button>
        <button type="button" className="rag-button" disabled={disabled} onClick={onReset}>重置</button>
        <button type="button" className="rag-button" disabled={disabled} onClick={onRefresh}>刷新</button>
        <button type="button" className="rag-button" disabled={disabled} onClick={onCreate}>新增文档</button>
      </div>
    </form>
  );
};
