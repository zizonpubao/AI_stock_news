import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080/api',
  timeout: 15000,
});

export const fetchTop10 = () => api.get('/stocks/top10').then(r => r.data);
export const fetchStockNews = (id) => api.get(`/stocks/${id}/news`).then(r => r.data);
export const refreshStocks = () => api.post('/stocks/refresh').then(r => r.data);
export const fetchHealth = () => api.get('/health').then(r => r.data);
