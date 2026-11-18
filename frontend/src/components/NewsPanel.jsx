import { useState, useEffect, useRef, useCallback } from 'react';
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

function NewsItem({ article, index, highlighted }) {
  return (
    <a
      id={`news-ref-${index + 1}`}
      href={article.link}
      target="_blank"
      rel="noopener noreferrer"
      className={`block rounded-xl p-4 border hover:border-slate-500 transition-all duration-200 group slide-in ${
        highlighted ? 'border-[rgba(0,212,255,0.8)] bg-[rgba(0,212,255,0.08)]' : 'border-[var(--border)]'
      }`}
      style={{ animationDelay: `${index * 80}ms`, opacity: 0 }}
    >
      <div className="flex items-start gap-3">
        <span className="font-display text-xl text-slate-600 leading-none mt-0.5 group-hover:text-[var(--accent)] transition-colors">
          [{index + 1}]
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

// 분석 시각 표시: 오늘이면 HH:mm, 아니면 MM-DD HH:mm. 해석할 수 없으면 null
function formatAnalyzedAt(value) {
  if (!value) return null;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  const pad = n => String(n).padStart(2, '0');
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const now = new Date();
  const isToday = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  return isToday ? time : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${time}`;
}

// 뉴스 논조 배지: 주가 등락(빨강/파랑)과 헷갈리지 않도록 색 없이 테두리와 글자로만 구분
const SENTIMENT_LABEL = {
  POSITIVE: { mark: '＋', text: '긍정' },
  NEUTRAL: { mark: '＝', text: '중립' },
  NEGATIVE: { mark: '－', text: '부정' },
};

// 문장 안의 [n] 을 번호 칩으로 변환. 해당 기사가 없는 번호는 일반 텍스트로 둔다
function renderWithRefs(line, newsCount, onRefClick) {
  return line.split(/(\[\d+\])/).map((part, i) => {
    const m = /^\[(\d+)\]$/.exec(part);
    if (m) {
      const n = Number(m[1]);
      if (n >= 1 && n <= newsCount) {
        return (
          <button
            key={i}
            type="button"
            onClick={() => onRefClick(n)}
            title={`관련 뉴스 ${n}번으로 이동`}
            className="inline-flex items-center justify-center mx-0.5 px-1 min-w-[1.25rem] h-4 rounded border border-slate-500 text-[10px] font-mono text-slate-300 hover:border-[var(--accent)] hover:text-[var(--accent)] align-baseline transition-colors"
          >
            {n}
          </button>
        );
      }
    }
    return part;
  });
}

function hasValidRef(text, newsCount) {
  const re = /\[(\d+)\]/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const n = Number(m[1]);
    if (n >= 1 && n <= newsCount) return true;
  }
  return false;
}

function AiAnalysis({ text, model, analyzedAt, sentiment, newsCount, onRefClick }) {
  if (!text) return null;

  // 섹션별 파싱 (📈 💡 ⚠️ 기준)
  const lines = text.split('\n').filter(l => l.trim());
  const meta = [model, formatAnalyzedAt(analyzedAt)].filter(Boolean).join(' · ');
  const sentimentInfo = SENTIMENT_LABEL[sentiment];
  const showRefNote = hasValidRef(text, newsCount);

  return (
    <div className="rounded-xl border border-[var(--accent)] overflow-hidden"
      style={{ background: 'linear-gradient(135deg, rgba(0,212,255,0.05) 0%, rgba(17,24,39,0.8) 100%)' }}>
      <div className="flex items-center gap-2 px-4 py-3 border-b border-[rgba(0,212,255,0.2)] flex-wrap">
        <div className="w-5 h-5 rounded flex items-center justify-center bg-[var(--accent)]">
          <svg width="10" height="10" viewBox="0 0 20 20" fill="var(--bg)">
            <path d="M10 2a8 8 0 100 16A8 8 0 0010 2zm1 11H9v-4h2v4zm0-6H9V5h2v2z"/>
          </svg>
        </div>
        <span className="text-xs font-mono text-[var(--accent)] tracking-wider">AI 분석 리포트</span>
        {sentimentInfo && (
          <span
            className="text-[10px] font-mono text-slate-300 border border-slate-500 rounded px-1.5 py-0.5"
            title="수집된 뉴스 전반의 분위기를 AI 가 요약한 것이며, 매수·매도 신호가 아닙니다"
          >
            뉴스 논조 {sentimentInfo.mark} {sentimentInfo.text}
          </span>
        )}
        {meta && <span className="ml-auto text-[10px] font-mono text-slate-500">{meta}</span>}
      </div>
      <div className="px-4 py-3 space-y-1">
        {showRefNote && (
          <p className="text-[10px] text-slate-500 leading-relaxed mb-2">
            주요 이슈의 번호는 아래 관련 뉴스 번호입니다. 관점·리스크는 AI 의 해석입니다.
          </p>
        )}
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
              {isHeader || isDisclaimer ? line : renderWithRefs(line, newsCount, onRefClick)}
            </p>
          );
        })}
      </div>
    </div>
  );
}

export default function NewsPanel({ stock, fetchNews = fetchStockNews }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [highlight, setHighlight] = useState(null);
  const highlightTimer = useRef(null);

  const handleRefClick = useCallback((n) => {
    const el = document.getElementById(`news-ref-${n}`);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setHighlight(n);
    clearTimeout(highlightTimer.current);
    highlightTimer.current = setTimeout(() => setHighlight(null), 1800);
  }, []);

  useEffect(() => () => clearTimeout(highlightTimer.current), []);

  useEffect(() => {
    if (!stock) return;
    setLoading(true);
    setError(null);
    setData(null);
    setHighlight(null);

    fetchNews(stock.id)
      .then(setData)
      .catch(() => setError('뉴스를 불러오지 못했습니다.'))
      .finally(() => setLoading(false));
  }, [stock?.id, fetchNews]);

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
                {({ PRE: '🌅 프리장', AFTER: '🌙 애프터마켓' })[stock.marketSession] && (
                  <span className="font-mono text-[11px] px-1.5 py-0.5 rounded bg-[var(--border)] text-amber-300 whitespace-nowrap">
                    {({ PRE: '🌅 프리장', AFTER: '🌙 애프터마켓' })[stock.marketSession]}
                  </span>
                )}
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
          <AiAnalysis
            text={data.stock.aiAnalysis}
            model={data.stock.analysisModel}
            analyzedAt={data.stock.analyzedAt}
            sentiment={data.stock.analysisSentiment}
            newsCount={data.news?.length ?? 0}
            onRefClick={handleRefClick}
          />
        </div>
      )}

      {/* 분석 없음: 사유에 따라 구분해 그대로 알린다 */}
      {!data?.stock?.aiAnalysis && data?.stock?.analysisStatus === 'FAILED' && (
        data.stock.analysisFailReason === 'NO_NEWS' ? (
          <div className="mb-5 fade-up rounded-xl border border-[var(--border)] bg-[rgba(148,163,184,0.06)] px-4 py-3"
            style={{ animationDelay: '100ms' }}>
            <p className="text-xs font-mono text-slate-300">근거 뉴스 없음</p>
            <p className="text-[11px] text-slate-400 mt-1 leading-relaxed">
              근거가 될 최근 뉴스가 없어 AI 분석을 생성하지 않았습니다. 근거 없이 추측한 분석은 제공하지 않습니다.
            </p>
          </div>
        ) : (
          <div className="mb-5 fade-up rounded-xl border border-[rgba(255,215,0,0.4)] bg-[rgba(255,215,0,0.05)] px-4 py-3"
            style={{ animationDelay: '100ms' }}>
            {data.stock.analysisFailReason === 'INVALID_RESPONSE' ? (
              <>
                <p className="text-xs font-mono text-[var(--yellow)]">⚠️ 근거가 확인되는 분석을 얻지 못했습니다</p>
                <p className="text-[11px] text-slate-400 mt-1 leading-relaxed">
                  AI 응답에서 근거가 확인되는 분석을 얻지 못했습니다. 다음 정기 갱신 때 다시 시도합니다.
                  {data?.stock?.newsStatus !== 'FAILED' && ' 아래 뉴스는 정상 수집된 내용입니다.'}
                </p>
              </>
            ) : (
              <>
                <p className="text-xs font-mono text-[var(--yellow)]">⚠️ AI 분석을 생성하지 못했습니다</p>
                <p className="text-[11px] text-slate-400 mt-1 leading-relaxed">
                  외부 AI 서비스 응답에 실패해 이번 갱신에서는 분석이 없습니다. 다음 정기 갱신 때 다시 시도합니다.
                  {data?.stock?.newsStatus !== 'FAILED' && ' 아래 뉴스는 정상 수집된 내용입니다.'}
                </p>
              </>
            )}
          </div>
        )
      )}

      {/* 뉴스 목록 */}
      <div className="flex-1 overflow-y-auto space-y-2 pr-1">
        <div className="flex items-center gap-2 mb-3 fade-up" style={{ animationDelay: '150ms' }}>
          <span className="text-xs font-mono text-slate-500 uppercase tracking-wider">관련 뉴스</span>
          {data?.news && <span className="text-[10px] font-mono text-slate-600">({data.news.length}건)</span>}
        </div>

        {loading && <SkeletonNews />}
        {error && <p className="text-sm text-red-400 text-center py-8">{error}</p>}
        {data?.news?.length === 0 && data?.stock?.newsStatus === 'FAILED' && (
          <div className="rounded-xl border border-[rgba(255,215,0,0.4)] bg-[rgba(255,215,0,0.05)] px-4 py-3">
            <p className="text-xs font-mono text-[var(--yellow)]">⚠️ 뉴스를 수집하지 못했습니다</p>
            <p className="text-[11px] text-slate-400 mt-1 leading-relaxed">
              뉴스 검색 서비스 호출에 실패해 이번 갱신에서는 뉴스가 없습니다. 다음 정기 갱신 때 다시 시도합니다.
            </p>
          </div>
        )}
        {data?.news?.length === 0 && data?.stock?.newsStatus === 'PENDING' && (
          <p className="text-sm text-slate-500 text-center py-8">뉴스 수집 대기 중입니다.</p>
        )}
        {data?.news?.length === 0 && data?.stock?.newsStatus !== 'FAILED' && data?.stock?.newsStatus !== 'PENDING' && (
          <p className="text-sm text-slate-500 text-center py-8">48시간 이내 뉴스가 없습니다.</p>
        )}
        {data?.news?.map((article, i) => (
          <NewsItem key={article.id} article={article} index={i} highlighted={highlight === i + 1} />
        ))}
      </div>
    </div>
  );
}