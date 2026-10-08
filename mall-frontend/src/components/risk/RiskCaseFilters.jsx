import { useEffect, useState } from 'react';
import { optionList } from './RiskDictionary';

const EMPTY_FILTERS = {
  status: 'OPEN',
  scene: '',
  riskLevel: '',
  commandStatus: '',
  keyword: '',
  fromTime: '',
  toTime: ''
};

export const RISK_EMPTY_FILTERS = EMPTY_FILTERS;

export const RiskCaseFilters = ({ value, dictionary, disabled, onChange, onReset, onRefresh }) => {
  const [keyword, setKeyword] = useState(value.keyword || '');

  useEffect(() => {
    setKeyword(value.keyword || '');
  }, [value.keyword]);

  const update = (field, fieldValue) => onChange({ ...value, [field]: fieldValue });

  const submitKeyword = event => {
    event.preventDefault();
    onChange({ ...value, keyword: keyword.trim() });
  };

  const select = (field, options, placeholder) => (
    <label className="risk-filter-field">
      <span>{placeholder}</span>
      <select value={value[field] || ''} disabled={disabled}
        onChange={event => update(field, event.target.value)}>
        <option value="">全部</option>
        {options.map(option => (
          <option key={option.code} value={option.code}>{option.text}</option>
        ))}
      </select>
    </label>
  );

  return (
    <form className="risk-filters" onSubmit={submitKeyword}>
      {select('status', optionList(dictionary, 'caseStatus'), '案件状态')}
      {select('riskLevel', optionList(dictionary, 'riskLevel'), '风险等级')}
      {select('scene', optionList(dictionary, 'scene'), '风控场景')}
      {select('commandStatus', optionList(dictionary, 'commandStatus'), '命令状态')}
      <label className="risk-filter-field">
        <span>创建开始</span>
        <input type="datetime-local" value={value.fromTime || ''} disabled={disabled}
          onChange={event => update('fromTime', event.target.value)} />
      </label>
      <label className="risk-filter-field">
        <span>创建结束</span>
        <input type="datetime-local" value={value.toTime || ''} disabled={disabled}
          onChange={event => update('toTime', event.target.value)} />
      </label>
      <label className="risk-filter-field risk-filter-keyword">
        <span>关键字</span>
        <input type="text" maxLength={64} value={keyword} disabled={disabled}
          placeholder="案件号 / 决策号 / 业务号"
          onChange={event => setKeyword(event.target.value)} />
      </label>
      <div className="risk-filter-actions">
        <button type="submit" className="risk-button primary" disabled={disabled}>搜索</button>
        <button type="button" className="risk-button" disabled={disabled} onClick={onReset}>重置</button>
        <button type="button" className="risk-button" disabled={disabled} onClick={onRefresh}>刷新</button>
      </div>
    </form>
  );
};
