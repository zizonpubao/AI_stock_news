import { useState, useEffect } from 'react';
import Header from './components/Header';
import StockCard from './components/StockCard';
import NewsPanel from './components/NewsPanel';
import {
  fetchTop10, fetchStockNews,
  fetchArchiveDates, fetchArchiveStocks, fetchArchiveStockNews,
} from './api/stockApi';

function SkeletonCard() {
  return (
    <div className="rounded-xl border border-[var(--border)] p-4" style={{ background: 'var(--card)' }}>
      <div className="flex justify-between mb-3">
        <div className="flex gap-2">
          <div className="skeleton w-6 h-6 rounded" />
          <div>
            <div className="skeleton h-3 w-20 rounded mb-1" />
            <div className="skeleton h-2 w-12 rounded" />
          </div>
        </div>
        <div className="skeleton h-4 w-14 rounded" />
      </div>
      <div className="flex justify-between">
        <div className="skeleton h-3 w-16 rounded" />
        <div className="skeleton h-2 w-20 rounded" />
      </div>
    </div>
  );
}

export default function App() {
  const [stocks, setStocks] = useState([]);
  const [selected, setSelected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [mobileView, setMobileView] = useState('list'); // 'list' | 'detail'
  const [archiveDates, setArchiveDates] = useState([]);
  const [selectedDate, setSelectedDate] = useState('today'); // 'today' | 'YYYY-MM-DD'

  const isToday = selectedDate === 'today';
  // 아카이브 모드일 땐 아카이브 뉴스 API 사용
  const newsFetcher = isToday ? fetchStockNews : fetchArchiveStockNews;

  // 아카이브 가능한 날짜 목록 (최초 1회)
  useEffect(() => {
    fetchArchiveDates().then(setArchiveDates).catch(() => {});
  }, []);

  // 선택 날짜에 따라 종목 로드 (오늘=실시간 / 과거=아카이브)
  useEffect(() => {
    setLoading(true);
    setError(null);
    const loader = isToday ? fetchTop10() : fetchArchiveStocks(selectedDate);
    loader
      .then(data => {
        setStocks(data);
        setSelected(data.length > 0 ? data[0] : null);
      })
      .catch(() => setError(isToday
        ? '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해주세요.'
        : '해당 날짜의 아카이브를 불러오지 못했습니다.'))
      .finally(() => setLoading(false));
  }, [selectedDate, isToday]);

  const handleSelect = (stock) => {
    setSelected(stock);
    setMobileView('detail');
  };

  return (
    <div className="relative min-h-screen">
      <Header />

      <main className="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 py-6">
        {error && (
          <div className="mb-6 rounded-xl border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-400 font-mono">
            ⚠️ {error}
          </div>
        )}

        {/* 모바일 탭 네비게이션 */}
        <div className="flex md:hidden mb-4 rounded-lg overflow-hidden border border-[var(--border)]">
          <button
            onClick={() => setMobileView('list')}
            className={`flex-1 py-2.5 text-sm font-mono transition-colors ${mobileView === 'list' ? 'bg-[var(--accent)] text-[var(--bg)]' : 'text-slate-400'}`}
          >
            급상승 TOP 10
          </button>
          <button
            onClick={() => setMobileView('detail')}
            className={`flex-1 py-2.5 text-sm font-mono transition-colors ${mobileView === 'detail' ? 'bg-[var(--accent)] text-[var(--bg)]' : 'text-slate-400'}`}
          >
            뉴스 · AI 분석
          </button>
        </div>

        {/* 날짜 선택기 */}
        <div className="mb-4 flex items-center gap-2 flex-wrap">
          <span className="text-xs font-mono text-slate-400">📅 날짜</span>
          <select
            value={selectedDate}
            onChange={e => setSelectedDate(e.target.value)}
            className="bg-[var(--card)] border border-[var(--border)] rounded-lg px-3 py-1.5 text-sm font-mono text-slate-200 focus:border-[var(--accent)] outline-none"
          >
            <option value="today">오늘 (실시간)</option>
            {archiveDates.map(d => <option key={d} value={d}>{d}</option>)}
          </select>
          {!isToday && (
            <span className="text-[11px] font-mono text-[var(--yellow)]">
              📁 {selectedDate} 종가 기준 아카이브
            </span>
          )}
          {isToday && archiveDates.length === 0 && (
            <span className="text-[11px] font-mono text-slate-600">아직 저장된 아카이브가 없어요 (매 거래일 16시 자동 저장)</span>
          )}
        </div>

        <div className="flex gap-5">
          {/* 좌측: 종목 리스트 */}
          <div className={`w-full md:w-80 flex-shrink-0 ${mobileView === 'detail' ? 'hidden md:block' : ''}`}>
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <div className={`w-1.5 h-1.5 rounded-full ${isToday ? 'bg-[var(--green)] animate-pulse' : 'bg-[var(--yellow)]'}`} />
                <span className="text-xs font-mono text-slate-400 uppercase tracking-wider">
                  {isToday ? '실시간 검색 TOP 10' : '검색 TOP 10 · 아카이브'}
                </span>
              </div>
              {stocks.length > 0 && (
                <span className="text-[10px] font-mono text-slate-600">
                  {isToday
                    ? `${new Date(stocks[0]?.updatedAt)?.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })} 기준`
                    : `${selectedDate} 종가`}
                </span>
              )}
            </div>

            <div className="space-y-2">
              {loading
                ? [...Array(10)].map((_, i) => <SkeletonCard key={i} />)
                : stocks.map((stock, i) => (
                    <div key={stock.id} className="fade-up" style={{ animationDelay: `${i * 50}ms`, opacity: 0 }}>
                      <StockCard
                        stock={stock}
                        rank={i + 1}
                        selected={selected?.id === stock.id}
                        onClick={() => handleSelect(stock)}
                      />
                    </div>
                  ))
              }
            </div>
          </div>

          {/* 우측: 뉴스 + AI 분석 */}
          <div
            className={`flex-1 min-h-[600px] rounded-2xl border border-[var(--border)] p-5 overflow-hidden
              ${mobileView === 'list' ? 'hidden md:block' : ''}`}
            style={{ background: 'var(--card)' }}
          >
            <NewsPanel stock={selected} fetchNews={newsFetcher} />
          </div>
        </div>
      </main>

      {/* 푸터 */}
      <footer className="relative z-10 border-t border-[var(--border)] mt-8 py-4">
        <p className="text-center text-[11px] font-mono text-slate-600">
          본 서비스는 AI 자동 생성 정보를 제공하며, 투자 권유가 아닙니다. 투자 손실에 대한 책임은 본인에게 있습니다.
        </p>
      </footer>
    </div>
  );
}
