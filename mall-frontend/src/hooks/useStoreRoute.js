import { useState, useEffect, useCallback } from 'react';

const parseHash = () => {
  const hash = window.location.hash || '';
  if (!hash || hash === '#' || hash === '#/' || hash.startsWith('#/home')) {
    return { route: 'home', params: {}, raw: hash };
  }

  // Handle /orders/:orderNo
  const orderDetailMatch = hash.match(/^#\/orders\/([A-Za-z0-9_]+)/);
  if (orderDetailMatch) {
    return {
      route: 'orderDetail',
      orderNo: orderDetailMatch[1],
      params: {},
      raw: hash
    };
  }

  // Handle /orders
  if (hash.startsWith('#/orders')) {
    const queryIndex = hash.indexOf('?');
    const params = {};
    if (queryIndex !== -1) {
      const search = new URLSearchParams(hash.substring(queryIndex + 1));
      search.forEach((value, key) => {
        params[key] = value;
      });
    }
    return {
      route: 'orders',
      params,
      raw: hash
    };
  }

  // Compatible with page sections (like #catalogSection, #tradeSection, #seckillSection)
  if (hash.startsWith('#')) {
    return { route: 'home', anchor: hash.substring(1), params: {}, raw: hash };
  }

  return { route: 'home', params: {}, raw: hash };
};

export const useStoreRoute = () => {
  const [currentRoute, setCurrentRoute] = useState(parseHash());

  useEffect(() => {
    const handleHashChange = () => {
      setCurrentRoute(parseHash());
    };
    window.addEventListener('hashchange', handleHashChange);
    return () => window.removeEventListener('hashchange', handleHashChange);
  }, []);

  const navigate = useCallback((path, queryParams = {}) => {
    let newHash = '';
    if (path.startsWith('/orders/')) {
      newHash = `#/orders/${path.replace('/orders/', '')}`;
    } else if (path === '/orders') {
      const sp = new URLSearchParams();
      Object.entries(queryParams).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') {
          sp.append(k, v);
        }
      });
      const qs = sp.toString();
      newHash = qs ? `#/orders?${qs}` : '#/orders';
    } else if (path === '/home' || path === '/') {
      newHash = '#/home';
    } else if (path.startsWith('#')) {
      newHash = path;
    } else {
      newHash = `#${path}`;
    }

    if (window.location.hash !== newHash) {
      window.location.hash = newHash;
    } else {
      setCurrentRoute(parseHash());
    }
  }, []);

  return {
    ...currentRoute,
    navigate
  };
};
