import axios from 'axios';

const api = axios.create({
  baseURL: process.env.REACT_APP_API_URL || 'http://localhost:8080/api',
  timeout: 15000,
});

export const fetchTop10 = () => api.get('/stocks/top10').then(r => r.data);
export const fetchStockNews = (id) => api.get(`/stocks/${id}/news`).then(r => r.data);
export const refreshStocks = () => api.post('/stocks/refresh').then(r => r.data);
export const fetchHealth = () => api.get('/health').then(r => r.data);
export const fetchIndices = () => api.get('/market/indices').then(r => r.data);

// 거래대금(원 숫자) → "11.4조" / "3,270억" 형식
export const formatTradingValue = (won) => {
  if (won == null) return '-';
  const n = Number(won);
  if (!Number.isFinite(n) || n <= 0) return '-';
  if (n >= 1e12) return `${(n / 1e12).toFixed(1)}조`;
  if (n >= 1e8) return `${Math.round(n / 1e8).toLocaleString()}억`;
  return `${Math.round(n / 1e4).toLocaleString()}만`;
};