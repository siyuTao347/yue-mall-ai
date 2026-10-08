import { useCallback, useEffect, useRef, useState } from 'react';
import { riskApi } from '../../api/riskApi';
import { showToast } from '../../utils/feedback';
import { RiskCaseFilters, RISK_EMPTY_FILTERS } from './RiskCaseFilters';
import { RiskCaseList } from './RiskCaseList';
import { RiskCaseDetail } from './RiskCaseDetail';
import { useRiskDictionary } from './RiskDictionary';
import './RiskCaseWorkbench.css';

const PAGE_SIZE = 20;

export const RiskCaseWorkbench = () => {
  const { dictionary, error: dictionaryError, reload: reloadDictionary } = useRiskDictionary();
  const [filters, setFilters] = useState(RISK_EMPTY_FILTERS);
  const [page, setPage] = useState(1);
  const [records, setRecords] = useState([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [phase, setPhase] = useState('IDLE');
  const [error, setError] = useState('');
  const [traceId, setTraceId] = useState(null);
  const [selectedCaseNo, setSelectedCaseNo] = useState(null);
  const [requestedOperation, setRequestedOperation] = useState(null);
  const controllerRef = useRef(null);
  const recordsRef = useRef([]);
  const autoRetriedRef = useRef(false);

  const notify = useCallback((type, title, message) => {
    showToast(type, title, message);
  }, []);

  const load = useCallback((pageToLoad, { silent = false } = {}) => {
    if (controllerRef.current) {
      controllerRef.current.abort();
    }
    const controller = new AbortController();
    controllerRef.current = controller;
    setPhase(silent && recordsRef.current.length > 0 ? 'REFRESHING' : 'LOADING');
    return riskApi.cases({ ...filters, page: pageToLoad, pageSize: PAGE_SIZE }, { signal: controller.signal })
      .then(data => {
        const nextRecords = (data && data.records) || [];
        recordsRef.current = nextRecords;
        setRecords(nextRecords);
        setTotal((data && data.total) || 0);
        setHasMore(Boolean(data && data.hasMore));
        setPage((data && data.page) || pageToLoad);
        setError('');
        setTraceId(null);
        setPhase(nextRecords.length === 0 ? 'EMPTY' : 'SUCCESS');
        autoRetriedRef.current = false;
        return data;
      })
      .catch(e => {
        if (e && e.code === 'ABORTED') return null;
        const message = (e && e.message) || '案件列表加载失败';
        if (!autoRetriedRef.current) {
          autoRetriedRef.current = true;
          return new Promise(resolve => {
            setTimeout(() => resolve(load(pageToLoad, { silent: true })), 800);
          });
        }
        setError(message);
        setTraceId(e && e.traceId);
        setPhase('ERROR');
        return null;
      });
  }, [filters]);

  useEffect(() => {
    autoRetriedRef.current = false;
    load(1);
  }, [load]);

  useEffect(() => () => {
    if (controllerRef.current) {
      controllerRef.current.abort();
    }
  }, []);

  const onPageChange = nextPage => {
    load(nextPage, { silent: true });
  };

  const onSelect = caseNo => {
    setSelectedCaseNo(caseNo);
    setRequestedOperation(null);
  };

  const onQuickAction = useCallback((operation, record) => {
    setSelectedCaseNo(record.caseNo);
    setRequestedOperation(operation);
  }, []);

  const onRetry = () => {
    if (phase === 'EMPTY') {
      setFilters(RISK_EMPTY_FILTERS);
      return;
    }
    autoRetriedRef.current = true;
    load(page, { silent: true });
  };

  return (
    <div className="risk-workbench">
      <RiskCaseFilters
        value={filters}
        dictionary={dictionary}
        disabled={phase === 'LOADING'}
        onChange={setFilters}
        onReset={() => setFilters(RISK_EMPTY_FILTERS)}
        onRefresh={() => load(page, { silent: true })}
      />
      {dictionaryError ? (
        <div className="risk-inline-error">
          <span>字典加载失败，已使用本地兜底文案</span>
          <button type="button" className="risk-button small" onClick={reloadDictionary}>重新加载</button>
        </div>
      ) : null}
      <div className="risk-workbench-body">
        <RiskCaseList
          records={records}
          phase={phase}
          error={error}
          traceId={traceId}
          page={page}
          pageSize={PAGE_SIZE}
          total={total}
          hasMore={hasMore}
          dictionary={dictionary}
          selectedCaseNo={selectedCaseNo}
          busyKey=""
          onSelect={onSelect}
          onQuickAction={onQuickAction}
          onPageChange={onPageChange}
          onRetry={onRetry}
        />
        <RiskCaseDetail
          caseNo={selectedCaseNo}
          dictionary={dictionary}
          requestedOperation={requestedOperation}
          onOperationConsumed={() => setRequestedOperation(null)}
          onChanged={() => load(page, { silent: true })}
          onNotify={notify}
        />
      </div>
    </div>
  );
};
