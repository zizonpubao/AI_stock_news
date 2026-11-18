import { useState, useEffect } from 'react';
import { fetchRunSummary } from '../api/stockApi';

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

// 서버가 준 숫자를 그대로 문구로 옮긴다. 값이 없는 항목은 생략하고, 분모가 0인 비율은 만들지 않는다.
function buildParts(s) {
  const parts = [];

  if (isNum(s.collectSuccess) && isNum(s.runs) && s.runs > 0) {
    parts.push({
      key: 'collect',
      text: `수집 ${s.collectSuccess}/${s.runs}회 성공`,
      warn: s.collectSuccess < s.runs,
    });
  }

  if (isNum(s.analysisSuccess) && isNum(s.analysisRequested) && s.analysisRequested > 0) {
    parts.push({
      key: 'analysis',
      text: `AI 분석 ${s.analysisSuccess}/${s.analysisRequested}건 성공`,
      warn: isNum(s.analysisFailed) && s.analysisFailed > 0,
    });
  }

  if (isNum(s.analysisSkippedNoNews)) {
    parts.push({ key: 'skipped', text: `근거 뉴스 없음 ${s.analysisSkippedNoNews}건`, warn: false });
  }

  if (isNum(s.avgAnalysisElapsedMs)) {
    parts.push({
      key: 'avg',
      text: `평균 분석 ${(s.avgAnalysisElapsedMs / 1000).toFixed(1)}초`,
      warn: false,
    });
  }

  if (s.lastModel) {
    parts.push({ key: 'model', text: `마지막 모델 ${s.lastModel}`, warn: false });
  }

  return parts;
}

// 최근 24시간 운영 현황 한 줄. API 가 없거나 실패하면 아무것도 그리지 않는다.
export default function RunStatusLine({ hours = 24 }) {
  const [summary, setSummary] = useState(null);

  useEffect(() => {
    let cancelled = false;
    fetchRunSummary(hours)
      .then(data => { if (!cancelled && data && typeof data === 'object') setSummary(data); })
      .catch(() => { if (!cancelled) setSummary(null); });
    return () => { cancelled = true; };
  }, [hours]);

  if (!summary || !isNum(summary.runs)) return null;

  const wrapClass = 'mb-4 text-[11px] font-mono text-slate-500 leading-relaxed flex flex-wrap items-center gap-x-1.5';

  if (summary.runs === 0) {
    return <p className={wrapClass}>최근 {hours}시간 실행 기록이 아직 없습니다</p>;
  }

  const parts = buildParts(summary);

  return (
    <p className={wrapClass}>
      <span>최근 {hours}시간</span>
      {parts.map(p => (
        <span key={p.key} className="inline-flex items-center gap-x-1.5">
          <span aria-hidden="true">·</span>
          <span className={p.warn ? 'text-[var(--yellow)]' : undefined}>{p.text}</span>
        </span>
      ))}
    </p>
  );
}
