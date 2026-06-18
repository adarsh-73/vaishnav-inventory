import React, { useCallback, useEffect, useMemo, useState } from "react";
import { apiRequest } from "../utils/api";

const COIN_NAMES = { BTCUSDT: "Bitcoin", ETHUSDT: "Ethereum", SOLUSDT: "Solana", BNBUSDT: "BNB" };

export default function CryptoTrading() {
  const [dashboard, setDashboard] = useState(null);
  const [selectedSymbol, setSelectedSymbol] = useState("BTCUSDT");
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState("");
  const [lastLoaded, setLastLoaded] = useState(null);

  const loadDashboard = useCallback(async () => {
    try {
      const data = await apiRequest("/crypto/dashboard");
      setDashboard(data);
      setError("");
      setLastLoaded(new Date());
      const symbols = data?.symbols || [];
      if (symbols.length && !symbols.some((item) => item.symbol === selectedSymbol)) setSelectedSymbol(symbols[0].symbol);
    } catch (requestError) {
      setError(requestError.message || "Crypto backend load nahi hua");
    } finally {
      setLoading(false);
    }
  }, [selectedSymbol]);

  useEffect(() => {
    loadDashboard();
    const timer = setInterval(loadDashboard, 60_000);
    return () => clearInterval(timer);
  }, [loadDashboard]);

  const runAction = async (path, successMessage) => {
    if (actionLoading) return;
    setActionLoading(true);
    try {
      await apiRequest(path, { method: "POST" });
      await loadDashboard();
      window.alert(successMessage);
    } catch (actionError) {
      window.alert(actionError.message || "Action failed");
    } finally {
      setActionLoading(false);
    }
  };

  const signals = useMemo(() => dashboard?.symbols || [], [dashboard]);
  const selected = useMemo(
    () => signals.find((item) => item.symbol === selectedSymbol) || signals[0] || null,
    [signals, selectedSymbol]
  );
  const aiVotes = selected?.aiVotes || [];
  const liveAiVotes = aiVotes.filter((vote) => vote.status === "LIVE");
  const report = dashboard?.report || {};
  const fearGreed = dashboard?.fearGreed || {};

  return (
    <main className="crypto-clean-page">
      <style>{pageCss}</style>

      <header className="crypto-hero">
        <div>
          <div className="eyebrow">REAL DATA · PAPER TRADING</div>
          <h1>Crypto Decision Desk</h1>
          <p>Price, indicators, derivatives aur AI consensus—sirf decision ke kaam ki cheezein.</p>
        </div>
        <div className="hero-actions">
          <span className="paper-badge">REAL MONEY OFF</span>
          <button className="secondary-btn" onClick={loadDashboard} disabled={loading}>Refresh</button>
        </div>
      </header>

      {error && (
        <section className="error-banner">
          <strong>Market feed unavailable</strong>
          <span>{cleanError(error)}</span>
        </section>
      )}

      <section className="summary-grid">
        <Summary label="Market Feed" value={feedStatus(signals)} tone={signals.some((s) => s.currentPrice > 0) ? "good" : "bad"} />
        <Summary label="AI Connected" value={`${liveAiVotes.length} equal votes`} tone={liveAiVotes.length ? "good" : "neutral"} />
        <Summary label="Fear & Greed" value={fearGreed.value ? `${fearGreed.value} · ${fearGreed.classification}` : "Unavailable"} tone="neutral" />
        <Summary label="Paper Performance" value={`${report.winRate || 0}% · ${report.totalTrades || 0} trades`} tone="neutral" />
      </section>

      <section className="market-grid">
        {signals.map((signal) => (
          <button
            key={signal.symbol}
            className={`market-card ${selected?.symbol === signal.symbol ? "selected" : ""}`}
            onClick={() => setSelectedSymbol(signal.symbol)}
          >
            <div className="market-card-top">
              <div><strong>{signal.symbol.replace("USDT", "")}</strong><small>{COIN_NAMES[signal.symbol]}</small></div>
              <SignalBadge signal={signal.finalSignal} />
            </div>
            <div className="market-price">{money(signal.currentPrice || signal.entry)}</div>
            <div className="market-meta"><span>Score {number(signal.finalScore)}%</span><span>{sourceName(signal.priceSource)}</span></div>
            {signal.blockReason && <div className="card-warning">{cleanError(signal.blockReason)}</div>}
          </button>
        ))}
        {!loading && signals.length === 0 && <div className="empty-state">Backend ne koi symbol return nahi kiya.</div>}
      </section>

      {selected && (
        <>
          <section className="decision-panel">
            <div className="section-title-row">
              <div><span className="eyebrow">SELECTED MARKET</span><h2>{selected.symbol} decision</h2></div>
              <SignalBadge signal={selected.finalSignal} large />
            </div>
            <div className="decision-metrics">
              <Metric label="Live Price" value={money(selected.currentPrice)} />
              <Metric label="Final Score" value={`${number(selected.finalScore)}%`} />
              <Metric label="Risk / Reward" value={`1 : ${number(selected.riskReward, 2)}`} />
              <Metric label="Stop Loss" value={money(selected.stopLoss)} />
              <Metric label="Take Profit" value={money(selected.takeProfit)} />
              <Metric label="Position" value={number(selected.positionSize, 5)} />
            </div>
            {selected.marketWarning && <div className="inline-warning">{cleanError(selected.marketWarning)}</div>}
          </section>

          <div className="detail-grid">
            <section className="clean-panel">
              <h3>Timeframe confirmation</h3>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>TF</th><th>Signal</th><th>Score</th><th>RSI</th><th>ADX</th><th>Indicators</th></tr></thead>
                  <tbody>
                    {(selected.timeframes || []).map((row) => (
                      <tr key={row.timeframe}>
                        <td>{row.timeframe}</td><td><SignalBadge signal={row.signal} /></td><td>{number(row.score)}%</td>
                        <td>{number(row.rsi, 1)}</td><td>{number(row.adx14, 1)}</td><td>{row.indicatorCount || 0} real values</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {(selected.timeframes || []).length === 0 && <div className="empty-state">Candle data available nahi hai.</div>}
            </section>

            <section className="clean-panel">
              <h3>Equal-weight AI consensus</h3>
              <div className="ai-list">
                {aiVotes.map((vote) => (
                  <div className="ai-row" key={vote.ai}>
                    <div><strong>{vote.ai}</strong><small>{vote.model || vote.status}</small></div>
                    <SignalBadge signal={vote.signal} />
                    <span>{number(vote.confidence)}%</span>
                  </div>
                ))}
                {!aiVotes.length && <div className="empty-state">Market snapshot ke bina AI call block hai.</div>}
              </div>
              <p className="panel-note">Har live provider ka vote equal hai. Tie ya market disagreement me NO_TRADE.</p>
            </section>
          </div>

          <section className="clean-panel">
            <h3>Derivatives & liquidation</h3>
            <div className="derivatives-grid">
              <Metric label="Open Interest" value={compactMoney(selected.futuresData?.openInterestValue)} />
              <Metric label="OI Change" value={percent(selected.futuresData?.openInterestChangePercent)} />
              <Metric label="Funding" value={number(selected.futuresData?.fundingRate, 6)} />
              <Metric label="Long / Short" value={number(selected.futuresData?.longShortRatio, 3)} />
              <Metric label="Taker Buy / Sell" value={number(selected.futuresData?.takerBuySellRatio, 3)} />
              <Metric label="Large Trade Bias" value={selected.whaleData?.bias || "No data"} />
              <Metric label="Liquidations 1h" value={`${selected.liquidationData?.eventCount || 0} events`} />
              <Metric label="Futures Source" value={sourceName(selected.futuresData?.source)} />
            </div>
          </section>
        </>
      )}

      <section className="clean-panel trade-section">
        <div className="section-title-row">
          <div><span className="eyebrow">DATABASE TRACKED</span><h2>Paper trades</h2></div>
          <div className="hero-actions">
            <button className="primary-btn" disabled={actionLoading || Boolean(error)} onClick={() => runAction("/crypto/paper-scan", "Paper scan complete")}>Run paper scan</button>
            <button className="secondary-btn" disabled={actionLoading} onClick={() => runAction("/crypto/close-running", "Running trades checked")}>Close/check running</button>
          </div>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Coin</th><th>Side</th><th>Status</th><th>Entry</th><th>Exit</th><th>P&amp;L</th><th>AI</th></tr></thead>
            <tbody>
              {(dashboard?.recentTrades || []).slice(0, 10).map((trade) => (
                <tr key={trade.id}><td>{trade.symbol}</td><td>{trade.side}</td><td>{trade.status}</td><td>{money(trade.entryPrice)}</td><td>{money(trade.exitPrice)}</td><td>{money(trade.pnl)}</td><td>{trade.bestAi || "-"}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
        {(dashboard?.recentTrades || []).length === 0 && <div className="empty-state">Abhi paper trade history nahi hai.</div>}
      </section>

      <footer className="crypto-footer">
        <span>Auto refresh: 60 sec</span><span>AI cache: 10 min</span><span>Last loaded: {lastLoaded ? lastLoaded.toLocaleTimeString() : "waiting"}</span>
      </footer>
    </main>
  );
}

function Summary({ label, value, tone }) { return <div className={`summary-card ${tone}`}><span>{label}</span><strong>{value}</strong></div>; }
function Metric({ label, value }) { return <div className="metric"><span>{label}</span><strong>{value ?? "-"}</strong></div>; }
function SignalBadge({ signal, large }) {
  const normalized = signal === "LONG" || signal === "SHORT" ? signal : "NO_TRADE";
  return <span className={`signal-badge ${normalized.toLowerCase()} ${large ? "large" : ""}`}>{normalized.replace("_", " ")}</span>;
}
function feedStatus(signals) {
  const live = signals.filter((signal) => Number(signal.currentPrice || 0) > 0).length;
  return live ? `${live}/${signals.length} coins live` : "Feed blocked";
}
function cleanError(value) {
  const text = String(value || "");
  if (text.includes("restricted location") || text.includes("451")) return "Binance rejected the current server region. Official alternate feed is required.";
  if (text.length > 150) return `${text.slice(0, 150)}…`;
  return text;
}
function sourceName(value) {
  const text = String(value || "");
  if (text.includes("data-api.binance.vision")) return "Binance Data API";
  if (text.includes("binance")) return "Binance";
  if (text.includes("ERROR")) return "Unavailable";
  return text || "Waiting";
}
function number(value, digits = 0) { return Number(value || 0).toLocaleString("en-US", { maximumFractionDigits: digits }); }
function money(value) { return Number(value || 0) ? `$${number(value, 2)}` : "—"; }
function compactMoney(value) { return Number(value || 0) ? `$${Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 2 }).format(Number(value))}` : "—"; }
function percent(value) { const n = Number(value || 0); return `${n > 0 ? "+" : ""}${n.toFixed(2)}%`; }

const pageCss = `
  .crypto-clean-page{min-height:100vh;background:#f4f7fb;color:#14213d;padding:28px;font-family:Inter,system-ui,-apple-system,sans-serif}.crypto-hero{display:flex;justify-content:space-between;gap:24px;align-items:center;padding:28px;border-radius:22px;background:linear-gradient(135deg,#081d3a,#123d68);color:#fff;box-shadow:0 18px 45px rgba(15,41,99,.16)}.crypto-hero h1{font-size:34px;margin:5px 0}.crypto-hero p{margin:0;color:#cbd9e8}.eyebrow{font-size:11px;font-weight:800;letter-spacing:1.8px;color:#4fb4ff}.hero-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.paper-badge,.signal-badge{display:inline-flex;align-items:center;justify-content:center;border-radius:999px;font-size:11px;font-weight:900;padding:7px 11px;white-space:nowrap}.paper-badge{background:#173f65;color:#9bd5ff;border:1px solid #2b5a84}.primary-btn,.secondary-btn{border:0;border-radius:10px;padding:11px 15px;font-weight:800;cursor:pointer}.primary-btn{background:#1d72d8;color:white}.secondary-btn{background:white;color:#163250;border:1px solid #d5dfeb}.primary-btn:disabled,.secondary-btn:disabled{opacity:.55;cursor:not-allowed}.error-banner{margin-top:16px;padding:16px 18px;border-radius:14px;background:#fff1f2;border:1px solid #fecdd3;color:#9f1239;display:flex;gap:12px;flex-wrap:wrap}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin:18px 0}.summary-card{background:#fff;padding:18px;border-radius:16px;border:1px solid #e3eaf2;display:flex;flex-direction:column;gap:6px}.summary-card span,.metric span{font-size:12px;color:#6b7c91}.summary-card strong{font-size:18px}.summary-card.good{border-top:3px solid #16a34a}.summary-card.bad{border-top:3px solid #dc2626}.market-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.market-card{background:#fff;border:1px solid #e1e8f0;border-radius:18px;padding:18px;text-align:left;cursor:pointer;color:inherit}.market-card.selected{border:2px solid #2575d4;box-shadow:0 10px 28px rgba(37,117,212,.12)}.market-card-top{display:flex;justify-content:space-between;gap:12px}.market-card-top strong{font-size:19px}.market-card-top small,.ai-row small{display:block;color:#7b8999;margin-top:3px}.market-price{font-size:27px;font-weight:900;margin:20px 0 12px}.market-meta{display:flex;justify-content:space-between;font-size:12px;color:#66758a}.card-warning,.inline-warning{margin-top:13px;border-radius:10px;padding:10px;background:#fff5f5;color:#a61b1b;font-size:12px}.signal-badge.long{background:#dcfce7;color:#15803d}.signal-badge.short{background:#fee2e2;color:#b91c1c}.signal-badge.no_trade{background:#edf1f5;color:#526173}.signal-badge.large{font-size:14px;padding:10px 17px}.decision-panel,.clean-panel{background:#fff;border:1px solid #e1e8f0;border-radius:20px;padding:22px;margin-top:18px}.section-title-row{display:flex;justify-content:space-between;align-items:center;gap:18px}.section-title-row h2,.clean-panel h3{margin:4px 0 0}.decision-metrics,.derivatives-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px;margin-top:18px}.derivatives-grid{grid-template-columns:repeat(4,minmax(0,1fr))}.metric{background:#f7f9fc;border:1px solid #e7edf4;border-radius:13px;padding:14px;display:flex;flex-direction:column;gap:6px}.metric strong{font-size:15px;word-break:break-word}.detail-grid{display:grid;grid-template-columns:1.25fr .75fr;gap:18px}.table-wrap{overflow:auto;margin-top:14px}table{width:100%;border-collapse:collapse;min-width:650px}th,td{padding:12px 10px;text-align:left;border-bottom:1px solid #edf1f5;font-size:13px}th{font-size:11px;text-transform:uppercase;letter-spacing:.6px;color:#718096}.ai-list{margin-top:12px}.ai-row{display:grid;grid-template-columns:1fr auto 52px;align-items:center;gap:10px;padding:12px 0;border-bottom:1px solid #edf1f5}.panel-note,.crypto-footer{font-size:12px;color:#718096}.empty-state{padding:20px;color:#718096;text-align:center}.trade-section{margin-bottom:18px}.crypto-footer{display:flex;justify-content:center;gap:22px;padding:12px}.inline-warning{font-size:13px}
  @media(max-width:1050px){.summary-grid,.market-grid{grid-template-columns:repeat(2,1fr)}.decision-metrics{grid-template-columns:repeat(3,1fr)}.detail-grid{grid-template-columns:1fr}.derivatives-grid{grid-template-columns:repeat(2,1fr)}}
  @media(max-width:650px){.crypto-clean-page{padding:14px}.crypto-hero,.section-title-row{align-items:flex-start;flex-direction:column}.summary-grid,.market-grid,.decision-metrics,.derivatives-grid{grid-template-columns:1fr}.crypto-hero h1{font-size:27px}.crypto-footer{flex-direction:column;gap:5px;text-align:center}}
`;
