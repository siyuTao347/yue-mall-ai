import { useCallback, useEffect, useState } from 'react';
import { useApp } from '../context/AppContext';

export const useServerTime = () => {
  const { serverTimeOffset } = useApp();
  const [timeStr, setTimeStr] = useState('--:--:--');

  useEffect(() => {
    const update = () => {
      const now = new Date(Date.now() + serverTimeOffset);
      const hh = String(now.getHours()).padStart(2, '0');
      const mm = String(now.getMinutes()).padStart(2, '0');
      const ss = String(now.getSeconds()).padStart(2, '0');
      setTimeStr(`${hh}:${mm}:${ss}`);
    };

    update();
    const timer = setInterval(update, 1000);
    return () => clearInterval(timer);
  }, [serverTimeOffset]);

  const getStandardNow = useCallback(
    () => Date.now() + serverTimeOffset,
    [serverTimeOffset]
  );

  return { timeStr, getStandardNow };
};
