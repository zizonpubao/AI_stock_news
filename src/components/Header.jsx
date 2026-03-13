import { useState, useEffect } from 'react';
import { fetchHealth } from '../api/stockApi';

export default function Header() {
  const [health, setHealth] = useState(null);
  const [now, setNow] = useState(new Date());

  useEffect(() => {
    fetchHealth().then(setHealth).catch(() => {});
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);


  const timeStr = now.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const dateStr = now.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' });

  return (
    <header className="relative z-10 border-b border-[var(--border)]">
      <div className="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between">
        {/* 로고 */}
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-lg bg-[var(--accent)] flex items-center justify-center">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <polyline points="1,12 5,7 9,10 15,3" stroke="#0a0e1a" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </div>
          <div>
            <h1 className="font-display text-2xl text-white tracking-widest leading-none">STOCKPULSE</h1>
            <p className="text-[11px] text-[var(--accent)] font-mono tracking-[0.2em] opacity-70">실시간 검색 인기 종목 AI 분석</p>
          </div>
        </div>

        {/* 중앙: 시간 + 업데이트 안내 */}
        <div className="hidden md:flex flex-col items-center">
          <span className="font-mono text-2xl text-white tracking-widest">{timeStr}</span>
          <span className="text-xs text-slate-400 mt-1">{dateStr}</span>
          <span className="text-[10px] font-mono mt-1 px-2 py-0.5 rounded-full border border-[var(--border)] text-slate-500">
            🕐 매시 정각 자동 업데이트 (평일 9~16시)
          </span>
        </div>

        {/* 우측: 서버 상태 */}
        <div className="flex items-center gap-3">
          {health && (
            <div className="hidden sm:flex items-center gap-3 text-xs font-mono text-slate-400">
              <span className="flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-[var(--green)] animate-pulse" />
                종목 {health.stockCount}
              </span>
              <span>|</span>
              <span>뉴스 {health.newsCount}</span>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}