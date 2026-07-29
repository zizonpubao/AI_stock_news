import { formatTradingValue } from '../api/stockApi';

const SESSION_LABEL = { PRE: '🌅 프리장', AFTER: '🌙 애프터마켓' };

export default function StockCard({ stock, rank, selected, onClick }) {
  const isUp = stock.changeRate && !stock.changeRate.startsWith('-');
  // 한국식: 상승=빨강, 하락=파랑
  const changeColor = isUp ? 'var(--red)' : 'var(--blue)';
  const sessionLabel = SESSION_LABEL[stock.marketSession];

  const Badge = ({ label, value }) => (
    <span className="text-[10px] font-mono px-1.5 py-0.5 rounded border border-[var(--border)] text-slate-400">
      {label} <span className={value === 'X' ? 'text-slate-600' : 'text-slate-200'}>{value}</span>
    </span>
  );

  return (
    <div
      onClick={onClick}
      className="relative cursor-pointer rounded-xl border transition-all duration-200 overflow-hidden"
      style={{
        borderColor: selected ? 'var(--accent)' : 'var(--border)',
        background: selected
          ? 'linear-gradient(135deg, rgba(0,212,255,0.08) 0%, rgba(17,24,39,0.9) 100%)'
          : 'var(--card)',
      }}
    >
      {selected && <div className="absolute left-0 top-0 bottom-0 w-0.5 bg-[var(--accent)]" />}

      <div className="p-4">
        {/* 상단: 랭킹 + 종목명 + 52주 신고가 */}
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <span
              className="font-display text-lg leading-none"
              style={{ color: rank <= 3 ? 'var(--yellow)' : 'var(--accent)', opacity: rank <= 3 ? 1 : 0.6 }}
            >
              #{rank}
            </span>
            <div>
              <p className="font-body font-semibold text-white text-sm leading-tight">{stock.name}</p>
              <p className="text-[10px] font-mono text-slate-500 mt-0.5">{stock.code}</p>
            </div>
          </div>
          <div className="text-right">
            <p className="text-[10px] font-mono text-slate-500">52주 신고가</p>
            <p className="text-xs font-mono" style={{ color: stock.high52Week ? 'var(--yellow)' : 'var(--border)' }}>
              {stock.high52Week ? `₩${stock.high52Week}` : 'X'}
            </p>
          </div>
        </div>

        {/* 중단: 현재가 + 등락 */}
        {(() => {
          const changePriceNum = stock.changePrice?.replace(/[^0-9,]/g, '') || '';
          const changePriceDisplay = changePriceNum ? (isUp ? `+${changePriceNum}` : `-${changePriceNum}`) : '';
          const changeRateNum = stock.changeRate?.replace(/[^0-9.]/g, '') || '';
          const changeRateDisplay = changeRateNum ? (isUp ? `+${changeRateNum}%` : `-${changeRateNum}%`) : stock.changeRate;
          return (
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <span className="font-mono text-white font-semibold text-sm">
                  ₩{stock.currentPrice}
                </span>
                <span className="font-mono text-sm font-semibold" style={{ color: changeColor }}>
                  {isUp ? '▲' : '▼'} {changeRateDisplay}
                </span>
              </div>
              <span className="text-[13px] font-mono text-white">
                {changePriceDisplay}
              </span>
            </div>
          );
        })()}

        {/* 하단: PER / 추정PER / PBR / 거래량 */}
        <div className="flex items-center gap-1.5 flex-wrap">
          <Badge label="PER" value={stock.per || 'X'} />
          <Badge label="추정PER" value={stock.estimatedPer || 'X'} />
          <Badge label="PBR" value={stock.pbr || 'X'} />
          {sessionLabel && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded border border-[var(--border)] text-amber-300 whitespace-nowrap">
              {sessionLabel}
            </span>
          )}
          <span className="ml-auto text-[10px] font-mono text-slate-500 whitespace-nowrap">
            거래대금 <span className="text-slate-300">{formatTradingValue(stock.tradingValue)}</span>
          </span>
        </div>

        {/* AI 분석 인디케이터 */}
        {stock.aiAnalysis && (
          <div className="mt-2 flex items-center gap-1">
            <span className="w-1 h-1 rounded-full bg-[var(--accent)] animate-pulse" />
            <span className="text-[10px] text-[var(--accent)] font-mono opacity-70">AI 분석 완료</span>
          </div>
        )}
      </div>
    </div>
  );
}