import { useState, useEffect } from 'react';
import { fetchStockNews } from '../api/stockApi';

// HTML 엔티티 디코딩 (&quot; &amp; 등)
function decodeHtml(text) {
  if (!text) return '';
  return text
    .replace(/&quot;/g, '"')
    .replace(/&#039;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)));
}

function SkeletonNews() {
  return (
    <div className="space-y-4">
      {[...Array(3)].map((_, i) => (
        <div key={i} className="rounded-xl p-4 border border-[var(--border)]">
          <div className="skeleton h-3 w-3/4 rounded mb-2" />
          <div className="skeleton h-3 w-full rounded mb-1" />
          <div className="skeleton h-3 w-2/3 rounded" />
        </div>
      ))}
    </div>
  );
}

function NewsItem({ article, index }) {
  return (
    <a
      href={article.link}
      target="_blank"
      rel="noopener noreferrer"
      className="block rounded-xl p-4 border border-[var(--border)] hover:border-slate-500 transition-all duration-200 group slide-in"
      style={{ animationDelay: `${index * 80}ms`, opacity: 0 }}
    >
      <div className="flex items-start gap-3">
        <span className="font-display text-xl text-slate-600 leading-none mt-0.5 group-hover:text-[var(--accent)] transition-colors">
          {String(index + 1).padStart(2, '0')}
        </span>
        <div className="flex-1 min-w-0">
          <p className="font-body font-medium text-sm text-slate-200 group-hover:text-white leading-snug transition-colors line-clamp-2">
            {decodeHtml(article.title)}
          </p>
          {article.description && (
            <p className="text-xs text-slate-500 mt-1.5 leading-relaxed line-clamp-2">
              {decodeHtml(article.description)}
            </p>
          )}
          <div className="flex items-center gap-2 mt-2">
            <span className="text-[10px] font-mono text-slate-600">{article.pubDate?.slice(0, 16)}</span>
            <span className="text-[10px] font-mono text-[var(--accent)] opacity-0 group-hover:opacity-100 transition-opacity">
              읽기 →
            </span>
          </div>
        </div>
      </div>
    </a>
  );
}

function AiAnalysis({ text }) {
  if (!text) return null;

  // 섹션별 파싱 (📈 💡 ⚠️ 기준)
  const lines = text.split('\n').filter(l => l.trim());

  return (
    <div className="rounded-xl border border-[var(--accent)] overflow-hidden"
      style={{ background: 'linear-gradient(135deg, rgba(0,212,255,0.05) 0%, rgba(17,24,39,0.8) 100%)' }}>
      <div className="flex items-center gap-2 px-4 py-3 border-b border-[rgba(0,212,255,0.2)]">
        <div className="w-5 h-5 rounded flex items-center justify-center bg-[var(--accent)]">
          <svg width="10" height="10" viewBox="0 0 20 20" fill="var(--bg)">
            <path d="M10 2a8 8 0 100 16A8 8 0 0010 2zm1 11H9v-4h2v4zm0-6H9V5h2v2z"/>
          </svg>
        </div>
        <span className="text-xs font-mono text-[var(--accent)] tracking-wider">AI 분석 리포트</span>
        <span className="ml-auto text-[10px] font-mono text-slate-500">Gemini 2.5 Flash</span>
      </div>
      <div className="px-4 py-3 space-y-1">
        {lines.map((line, i) => {
          const isHeader = line.startsWith('📈') || line.startsWith('💡') || line.startsWith('⚠️');
          const isDisclaimer = line.startsWith('*');
          const isBullet = line.startsWith('-');

          return (
            <p
              key={i}
              className={
                isHeader
                  ? 'text-sm font-semibold text-white mt-3 first:mt-0'
                  : isDisclaimer
                  ? 'text-[10px] font-mono text-slate-600 mt-3 pt-2 border-t border-[var(--border)]'
                  : isBullet
                  ? 'text-xs text-slate-300 pl-3 leading-relaxed'
                  : 'text-xs text-slate-400 leading-relaxed'
              }
            >
              {line}
            </p>
          );
        })}
      </div>
    </div>
  );
}

export default function NewsPanel({ stock }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!stock) return;
    setLoading(true);
    setError(null);
    setData(null);

    fetchStockNews(stock.id)
      .then(setData)
      .catch(() => setError('뉴스를 불러오지 못했습니다.'))
      .finally(() => setLoading(false));
  }, [stock?.id]);

  if (!stock) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center py-20">
        <div className="w-16 h-16 rounded-2xl border border-[var(--border)] flex items-center justify-center mb-4">
          <svg width="28" height="28" fill="none" viewBox="0 0 24 24" stroke="var(--accent)" strokeWidth={1.5} opacity="0.4">
            <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 3v11.25A2.25 2.25 0 006 16.5h2.25M3.75 3h-1.5m1.5 0h16.5m0 0h1.5m-1.5 0v11.25A2.25 2.25 0 0118 16.5h-2.25m-7.5 0h7.5m-7.5 0l-1 3m8.5-3l1 3m0 0l.5 1.5m-.5-1.5h-9.5m0 0l-.5 1.5" />
          </svg>
        </div>
        <p className="text-slate-500 text-sm">종목을 선택하면<br />뉴스와 AI 분석을 볼 수 있어요</p>
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col">
      {/* 패널 헤더 */}
      {(() => {
        const isDown = stock.changeRate?.startsWith('-');
        // 한국식: 하락=파랑, 상승=빨강
        const priceColor = isDown ? 'var(--blue)' : 'var(--red)';
        const arrow = isDown ? '▼' : '▲';
        // changePrice에서 숫자만 추출 후 부호 붙이기
        const changePriceNum = stock.changePrice?.replace(/[^0-9,]/g, '') || '';
        const changePriceDisplay = changePriceNum ? (isDown ? `-${changePriceNum}` : `+${changePriceNum}`) : '';
        // changeRate에서 숫자만 추출 후 부호 붙이기
        const changeRateNum = stock.changeRate?.replace(/[^0-9.]/g, '') || '';
        const changeRateDisplay = changeRateNum ? (isDown ? `-${changeRateNum}%` : `+${changeRateNum}%`) : '';
        return (
          <div className="flex items-center justify-between mb-5 fade-up">
            <div>
              <div className="flex items-center gap-3">
                <h2 className="font-display text-2xl text-white tracking-wider">{stock.name}</h2>
                <a
                  href={`https://finance.naver.com/item/main.naver?code=${stock.code}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={e => e.stopPropagation()}
                  className="flex items-center gap-1 px-4 py-1.5 rounded border border-[var(--border)] text-[12px] font-mono text-slate-400 hover:border-[var(--accent)] hover:text-[var(--accent)] transition-all duration-200"
                >
                  <svg width="10" height="10" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                  </svg>
                  종목 링크
                </a>
              </div>
              <div className="flex items-center gap-3 mt-1">
                {/* 현재가 */}
                <span className="font-mono text-sm text-white font-semibold">
                  {stock.currentPrice ? `₩${stock.currentPrice}` : '-'}
                </span>
                {/* 화살표 + 전일대비 금액 + 등락률 */}
                <span className="font-mono text-sm font-semibold" style={{ color: priceColor }}>
                  {arrow} {changePriceDisplay}
                </span>
                <span className="font-mono text-xs px-1.5 py-0.5 rounded" style={{ color: priceColor, background: isDown ? 'rgba(59,130,246,0.12)' : 'rgba(255,68,102,0.12)' }}>
                  {changeRateDisplay}
                </span>
              </div>
            </div>
            <span className="font-mono text-xs text-slate-500 border border-[var(--border)] px-2 py-1 rounded">
              {stock.code}
            </span>
          </div>
        );
      })()}

      {/* AI 분석 */}
      {data?.stock?.aiAnalysis && (
        <div className="mb-5 fade-up" style={{ animationDelay: '100ms' }}>
          <AiAnalysis text={data.stock.aiAnalysis} />
        </div>
      )}

      {/* 뉴스 목록 */}
      <div className="flex-1 overflow-y-auto space-y-2 pr-1">
        <div className="flex items-center gap-2 mb-3 fade-up" style={{ animationDelay: '150ms' }}>
          <span className="text-xs font-mono text-slate-500 uppercase tracking-wider">관련 뉴스</span>
          {data?.news && <span className="text-[10px] font-mono text-slate-600">({data.news.length}건)</span>}
        </div>

        {loading && <SkeletonNews />}
        {error && <p className="text-sm text-red-400 text-center py-8">{error}</p>}
        {data?.news?.length === 0 && (
          <p className="text-sm text-slate-500 text-center py-8">48시간 이내 뉴스가 없습니다.</p>
        )}
        {data?.news?.map((article, i) => (
          <NewsItem key={article.id} article={article} index={i} />
        ))}
      </div>
    </div>
  );
}