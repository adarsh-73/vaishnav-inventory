import React, { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../utils/api";

const MARKETS = {
  BTC: { name: "Bitcoin", price: 0, atr15: 0, atr1h: 0, atr4h: 0, seed: 71 },
  ETH: { name: "Ethereum", price: 0, atr15: 0, atr1h: 0, atr4h: 0, seed: 47 },
  SOL: { name: "Solana", price: 0, atr15: 0, atr1h: 0, atr4h: 0, seed: 59 }
};

const AI_ENGINES = ["ChatGPT", "Gemini", "DeepSeek", "Claude", "Local ML", "Risk AI"];
const TIMEFRAMES = ["15m", "1h", "4h"];
const INDICATORS = [
  "SMA 10", "SMA 20", "SMA 50", "SMA 100", "SMA 200", "EMA 9", "EMA 21", "EMA 50", "EMA 100", "EMA 200",
  "Ichimoku Cloud", "Parabolic SAR", "Supertrend", "Trend Slope",
  "RSI 14", "Stochastic RSI", "MACD", "MACD Histogram", "ADX", "DMI Plus", "DMI Minus", "CCI", "Williams %R", "ROC",
  "Momentum 10", "Ultimate Oscillator", "Awesome Oscillator", "TRIX", "TSI", "Fisher Transform", "Connors RSI", "MFI",
  "ATR", "Bollinger Bands", "Keltner Channel", "Donchian Channel", "Standard Deviation", "Historical Volatility", "NATR", "Volatility Stop",
  "OBV", "VWAP", "Anchored VWAP", "Volume SMA", "Volume Spike", "Volume Delta", "CVD", "Chaikin Money Flow", "Accumulation Distribution", "Ease of Movement",
  "Force Index", "Money Flow Index", "Negative Volume Index", "Positive Volume Index", "Volume Profile POC", "Value Area High", "Value Area Low", "Order Book Imbalance", "Bid Ask Spread", "Depth Wall",
  "Open Interest", "Open Interest Change", "Funding Rate", "Premium Index", "Basis", "Long Short Ratio", "Top Trader Ratio", "Liquidation Heatmap", "Liquidation Cluster", "Mark Price Deviation",
  "Support Resistance", "Pivot Points", "Fibonacci Retracement", "Fibonacci Extension", "Market Structure", "Higher High Lower Low", "Breakout Strength", "Retest Quality", "Liquidity Sweep", "Fair Value Gap",
  "Order Block", "Supply Demand Zone", "Candle Pattern", "Engulfing Pattern", "Pin Bar", "Doji", "Inside Bar", "Range Compression", "Session High Low", "Previous Day High Low",
  "Correlation BTC ETH", "Correlation BTC SOL", "Dollar Index Proxy", "Crypto Fear Greed", "News Sentiment", "Social Volume", "AI Hallucination Check", "Signal Agreement Score", "Risk Reward Ratio", "Slippage Estimate"
];

function CryptoTrading() {
  const [selectedSymbol, setSelectedSymbol] = useState("BTC");
  const [capital, setCapital] = useState(1000);
  const [riskPercent, setRiskPercent] = useState(1);
  const [maxLeverage, setMaxLeverage] = useState(3);
  const [confidenceGate, setConfidenceGate] = useState(78);
  const [dailyLossLimit, setDailyLossLimit] = useState(3);
  const [paperEnabled, setPaperEnabled] = useState(true);
  const [autoTrade, setAutoTrade] = useState(false);
  const [cashEnabled, setCashEnabled] = useState(false);
  const [apiKey, setApiKey] = useState("");
  const [apiSecret, setApiSecret] = useState("");
  const [serverDashboard, setServerDashboard] = useState(null);
  const [loading, setLoading] = useState(false);
  const [notificationEnabled, setNotificationEnabled] = useState(
    typeof Notification !== "undefined" && Notification.permission === "granted"
  );
  const lastNotificationKey = useRef("");

  const localDashboard = useMemo(
    () => buildDashboard({ capital, riskPercent, maxLeverage, confidenceGate, dailyLossLimit }),
    [capital, riskPercent, maxLeverage, confidenceGate, dailyLossLimit]
  );
  const dashboard = useMemo(
    () => serverDashboard ? normalizeServerDashboard(serverDashboard, localDashboard, capital) : localDashboard,
    [serverDashboard, localDashboard, capital]
  );
  const selectedPlan = dashboard.plans[selectedSymbol];
  const portfolio = dashboard.portfolio;
  const binanceStatus = dashboard.binance || {};
  const liveAlerts = buildLiveAlerts(dashboard);

  const loadDashboard = async () => {
    setLoading(true);
    try {
      setServerDashboard(await apiRequest("/crypto/dashboard"));
    } catch (error) {
      console.warn("Crypto backend dashboard fallback:", error.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDashboard();
    const timer = setInterval(loadDashboard, 30000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!notificationEnabled || typeof Notification === "undefined" || Notification.permission !== "granted") return;
    const majorAlert = liveAlerts.find((alert) => alert.type === "Whale" || alert.type === "Risk");
    if (!majorAlert) return;
    const key = `${majorAlert.type}-${majorAlert.title}-${majorAlert.detail}`;
    if (lastNotificationKey.current === key) return;
    lastNotificationKey.current = key;
    new Notification(`Crypto ${majorAlert.type} Alert`, {
      body: `${majorAlert.title}. ${majorAlert.detail}`,
      tag: key
    });
  }, [liveAlerts, notificationEnabled]);

  const enableNotifications = async () => {
    if (typeof Notification === "undefined") return alert("Is browser me notification support nahi hai.");
    const permission = await Notification.requestPermission();
    setNotificationEnabled(permission === "granted");
    if (permission !== "granted") alert("Notification allow nahi hua. Browser permission me allow karna hoga.");
  };

  const runPaperScan = async () => {
    if (!paperEnabled) return alert("Paper Trade OFF hai.");
    try {
      const created = await apiRequest("/crypto/paper-scan", { method: "POST" });
      await loadDashboard();
      alert(created?.length ? "Paper trade database me save ho gaya." : "No trade: risk engine ne block kiya ya already running trade hai.");
    } catch (error) {
      alert(`Paper scan failed: ${error.message}`);
    }
  };

  const closeRunningTrades = async () => {
    try {
      await apiRequest("/crypto/close-running", { method: "POST" });
      await loadDashboard();
      alert("Running paper trades close/update ho gaye.");
    } catch (error) {
      alert(`Close failed: ${error.message}`);
    }
  };

  const cleanupBadDataTrades = async () => {
    try {
      const result = await apiRequest("/crypto/cleanup-bad-data", { method: "POST" });
      await loadDashboard();
      alert(`${result?.cancelled || 0} bad/fallback trades clean ho gaye.`);
    } catch (error) {
      alert(`Cleanup failed: ${error.message}`);
    }
  };

  const connectBinance = async () => {
    if (!apiKey.trim() || !apiSecret.trim()) return alert("API key aur API secret dono bharna hoga.");
    try {
      await apiRequest("/crypto/binance/connect", {
        method: "POST",
        body: JSON.stringify({ apiKey, apiSecret, testnet: true })
      });
      setApiKey("");
      setApiSecret("");
      await loadDashboard();
      alert("Binance testnet credentials encrypted backend vault me save ho gaye.");
    } catch (error) {
      alert(`Binance connect failed: ${error.message}`);
    }
  };

  const disconnectBinance = async () => {
    try {
      await apiRequest("/crypto/binance/disconnect", { method: "POST" });
      await loadDashboard();
      alert("Binance disconnect ho gaya.");
    } catch (error) {
      alert(`Disconnect failed: ${error.message}`);
    }
  };

  return (
    <div style={pageStyle}>
      <section style={heroStyle}>
        <div>
          <div style={eyebrowStyle}>Crypto Auto Trading Command Center</div>
          <h1 style={titleStyle}>BTC / ETH / SOL AI Paper Trader</h1>
          <div style={subtitleStyle}>1 month paper monitoring, then Binance testnet/live only after risk checks pass.</div>
        </div>
        <div style={heroStats}>
          <span style={heroLabel}>Paper 30D PnL</span>
          <strong style={portfolio.monthPnl >= 0 ? heroProfit : heroLoss}>{formatUsd(portfolio.monthPnl)}</strong>
          <small style={heroSmall}>{portfolio.winRate}% win rate | {portfolio.trades} trades</small>
        </div>
      </section>

      <section style={controlPanel}>
        <Field label="Capital USDT" value={capital} onChange={setCapital} />
        <Field label="Risk / Trade %" value={riskPercent} onChange={setRiskPercent} />
        <Field label="Max Leverage" value={maxLeverage} onChange={setMaxLeverage} />
        <Field label="AI Gate %" value={confidenceGate} onChange={setConfidenceGate} />
        <Field label="Daily Loss Limit %" value={dailyLossLimit} onChange={setDailyLossLimit} />
        <Toggle label="Paper Trade" checked={paperEnabled} onClick={() => setPaperEnabled(!paperEnabled)} good />
        <Toggle label="Cash Mode" checked={cashEnabled} onClick={() => setCashEnabled(!cashEnabled)} danger />
        {cashEnabled && <Toggle label="Auto Trade" checked={autoTrade} onClick={() => setAutoTrade(!autoTrade)} danger />}
        <button type="button" onClick={loadDashboard} style={secondaryBtn}>{loading ? "Loading..." : "Refresh Signals"}</button>
        <button type="button" onClick={runPaperScan} style={primaryBtn}>Run Paper Scan</button>
        <button type="button" onClick={closeRunningTrades} style={dangerBtn}>Close Running Paper</button>
        <button type="button" onClick={cleanupBadDataTrades} style={secondaryBtn}>Clean Bad Trades</button>
      </section>

      <section style={noticeStyle}>
        <strong>{cashEnabled ? "Cash Enabled:" : "Cash Disabled:"}</strong> {cashEnabled ? "Binance connect/testnet panel active hai, lekin live real-money order backend vault aur final approval ke bina locked rahega." : "AI paper trades hi record honge. Is mode me tum dekh sakte ho AI ne din me kitne trade liye aur profit/loss kya raha."}
      </section>

      <section style={alertStrip}>
        <div style={alertHeader}>
          <strong>Live Market Alerts</strong>
          <div style={alertActions}>
            <span>{liveAlerts.length} active</span>
            <button type="button" onClick={enableNotifications} style={notificationBtn}>
              {notificationEnabled ? "Notifications ON" : "Enable Notifications"}
            </button>
          </div>
        </div>
        <div style={alertGrid}>
          {liveAlerts.map((alert) => (
            <div key={alert.id} style={{ ...alertCard, borderLeftColor: alert.color }}>
              <span style={{ ...alertType, color: alert.color }}>{alert.type}</span>
              <strong>{alert.title}</strong>
              <small>{alert.detail}</small>
            </div>
          ))}
        </div>
      </section>

      <section style={intelligenceGrid}>
        <Info label="Whale Watch" value={dashboard.intelligence?.whaleTracker || "Waiting"} />
        <Info label="News Risk" value={dashboard.intelligence?.newsRisk || "Waiting"} />
        <Info label="Macro Risk" value={dashboard.intelligence?.macro?.macroRisk || dashboard.intelligence?.macro?.sp500 || "Waiting"} />
        <Info label="Fake News Guard" value={dashboard.intelligence?.fakeNewsFilter || "Backend data ka wait"} />
        <Info label="Trade Rule" value={dashboard.intelligence?.rule || "No real-money auto trade until all filters pass"} />
      </section>

      <section style={strategyPanel}>
        <div style={panelHeaderCompact}>
          <h2 style={panelTitle}>AI Trade Checklist</h2>
          <span style={pillStyle}>A to Z decision flow</span>
        </div>
        <div style={strategyGrid}>
          <Info label="Price" value="CoinMarketCap live BTC/ETH/SOL" />
          <Info label="Indicators" value="MA 50/100/200, EMA, RSI, MACD, BB, ATR, VWAP, S/R" />
          <Info label="Futures" value="Funding, open interest, long/short ratio, volume spike" />
          <Info label="Whale Proxy" value="Large volume + Binance flow now, real whale API keys next" />
          <Info label="Macro" value="S&P500/SPY, dollar/UUP, CPI/Fed/geopolitical risk filter" />
          <Info label="Entry Rule" value="75%+ score, 2+ timeframes, RR 1:2+, no high risk" />
        </div>
      </section>

      <section style={smartMoneyPanel}>
        <div style={panelHeader}>
          <h2 style={panelTitle}>Smart Money Command Feed</h2>
          <span style={dashboard.intelligence?.externalRisk === "HIGH" ? dangerPill : safePill}>
            Score {dashboard.intelligence?.smartMoneyScore ?? 0}% | {dashboard.intelligence?.externalRisk || "WAITING"}
          </span>
        </div>
        <div style={feedGrid}>
          <FeedList title="Whale / Exchange Flow" status={dashboard.intelligence?.whaleFeed} items={dashboard.intelligence?.topWhales} />
          <FeedList title="Verified News" status={dashboard.intelligence?.newsFeed} items={dashboard.intelligence?.verifiedNews} />
          <FeedList title="ETF Rotation Proxy" status={dashboard.intelligence?.etfFeed} items={dashboard.intelligence?.etfFlows} />
          <FeedList title="Free Whale Watchlist" status="MANUAL_FREE_SOURCES" items={dashboard.intelligence?.freeWhaleSources} />
        </div>
      </section>

      <div style={coinGrid}>
        {Object.values(dashboard.plans).map((plan) => (
          <button
            key={plan.symbol}
            type="button"
            onClick={() => setSelectedSymbol(plan.symbol)}
            style={selectedSymbol === plan.symbol ? selectedCoinCard(plan) : coinCard(plan)}
          >
            <div style={coinTopRow}>
              <strong style={coinName}>{plan.symbol}USDT</strong>
              <span style={plan.allowed ? readyPill : blockedPill}>{plan.allowed ? "READY" : "WAIT"}</span>
            </div>
            <div style={signalTextStyle(plan.direction)}>{plan.direction}</div>
            <div style={probabilityBar}>
              <span style={{ ...probabilityFill, width: `${plan.longChance || 0}%`, background: "#16a34a" }} />
              <span style={{ ...probabilityFill, width: `${plan.shortChance || 0}%`, background: "#dc2626" }} />
              <span style={{ ...probabilityFill, width: `${plan.noTradeChance || 0}%`, background: "#ca8a04" }} />
            </div>
            <div style={coinMeta}>Long {formatPercentPlain(plan.longChance)} | Short {formatPercentPlain(plan.shortChance)} | No Trade {formatPercentPlain(plan.noTradeChance)}</div>
            <div style={coinMeta}>Price {formatPrice(plan.entry)} | Source {plan.priceSource || "CMC"}</div>
            <div style={coinMeta}>CMC 1h {formatPercent(plan.percentChange1h)} | 24h {formatPercent(plan.percentChange24h)} | 7d {formatPercent(plan.percentChange7d)}</div>
            <div style={coinMeta}>Entry {formatPrice(plan.entry)} | SL {formatPrice(plan.stopLoss)} | Book {formatPrice(plan.takeProfit)}</div>
            {plan.marketWarning && <div style={warningText}>{plan.marketWarning}</div>}
          </button>
        ))}
      </div>

      <div style={metricGrid}>
        <Metric label="Best Coin Now" value={`${portfolio.best.symbol} ${portfolio.best.direction}`} accent={portfolio.best.direction === "LONG" ? "#0f766e" : "#b91c1c"} />
        <Metric label="Avg AI Confidence" value={`${portfolio.avgConfidence}%`} accent="#0f2963" />
        <Metric label="Paper Equity" value={formatUsd(portfolio.equity)} accent="#166534" />
        <Metric label="Max Drawdown" value={`${portfolio.maxDrawdown}%`} accent="#b91c1c" />
        <Metric label="DB Paper Trades" value={portfolio.trades} accent="#0f2963" />
        <Metric label="Bad Data Cleaned" value={portfolio.cancelledBadDataTrades} accent="#92400e" />
        <Metric label="Today Trades" value={`${portfolio.todayTrades}/${portfolio.maxDailyTrades}`} accent="#0f2963" />
        <Metric label="Today Profit" value={formatUsd(portfolio.todayPnl)} accent={portfolio.todayPnl >= 0 ? "#15803d" : "#b91c1c"} />
        <Metric label="Week Profit" value={formatUsd(portfolio.weekPnl)} accent={portfolio.weekPnl >= 0 ? "#15803d" : "#b91c1c"} />
        <Metric label="Month Profit" value={formatUsd(portfolio.monthPnl)} accent={portfolio.monthPnl >= 0 ? "#15803d" : "#b91c1c"} />
        <Metric label="Profitable Trades" value={`${portfolio.profitableTrades}/${portfolio.trades}`} accent="#166534" />
        <Metric label="Binance Vault" value={binanceStatus.connected ? "Connected" : "Not Connected"} accent={binanceStatus.connected ? "#15803d" : "#92400e"} />
        <Metric label="Auto Status" value={cashEnabled && autoTrade && binanceStatus.connected ? "Testnet Armed" : paperEnabled ? "Paper Only" : "Manual"} accent={cashEnabled && autoTrade && binanceStatus.connected ? "#b91c1c" : "#92400e"} />
      </div>

      <section style={panelStyle}>
        <div style={panelHeader}>
          <h2 style={panelTitle}>Live Paper Trades</h2>
          <span style={dashboard.liveTrades?.length ? safePill : warnPill}>{dashboard.liveTrades?.length ? `${dashboard.liveTrades.length} running` : "No running trade"}</span>
        </div>
        <div style={tableWrap}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Coin</th>
                <th style={thStyle}>Side</th>
                <th style={thStyle}>Entry</th>
                <th style={thStyle}>Current</th>
                <th style={thStyle}>Live PnL</th>
                <th style={thStyle}>SL</th>
                <th style={thStyle}>Book</th>
                <th style={thStyle}>Target Left</th>
                <th style={thStyle}>AI Score</th>
                <th style={thStyle}>Why Trade</th>
              </tr>
            </thead>
            <tbody>
              {dashboard.liveTrades?.map((trade) => (
                <tr key={`live-${trade.id}`}>
                  <td style={tdStyle}>{String(trade.symbol || "").replace("USDT", "")}</td>
                  <td style={trade.side === "LONG" ? longTd : shortTd}>{trade.side}</td>
                  <td style={tdStyle}>{formatPrice(trade.entryPrice)}</td>
                  <td style={tdStyle}>{formatPrice(trade.currentPrice)}</td>
                  <td style={Number(trade.unrealizedPnl || 0) >= 0 ? profitTd : lossTd}>{formatUsd(trade.unrealizedPnl)} ({formatPercent(trade.unrealizedPnlPercent)})</td>
                  <td style={tdStyle}>{formatPrice(trade.stopLoss)} ({formatPercentPlain(trade.stopDistancePercent)})</td>
                  <td style={tdStyle}>{formatPrice(trade.takeProfit)}</td>
                  <td style={tdStyle}>{formatPercentPlain(trade.targetDistancePercent)}</td>
                  <td style={tdStyle}>{Math.round(Number(trade.finalScore || trade.confidence || 0))}%</td>
                  <td style={reasonTd}>{trade.aiConsensus || trade.technicalSummary || "AI consensus running"}</td>
                </tr>
              ))}
              {(!dashboard.liveTrades || dashboard.liveTrades.length === 0) && (
                <tr><td style={emptyTd} colSpan="10">Abhi koi running paper trade nahi hai. Trade tabhi dikhega jab AI paper scan entry lega.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <div style={mainGrid}>
        <section style={panelStyle}>
          <div style={panelHeader}>
            <h2 style={panelTitle}>{selectedPlan.symbol} Trade Plan</h2>
            <span style={selectedPlan.allowed ? safePill : warnPill}>{selectedPlan.allowed ? "AI can take paper trade" : "No trade"}</span>
          </div>
          <div style={orderGrid}>
            <Info label="Side" value={selectedPlan.direction} strong />
            <Info label="Long Chance" value={formatPercentPlain(selectedPlan.longChance)} good />
            <Info label="Short Chance" value={formatPercentPlain(selectedPlan.shortChance)} danger />
            <Info label="No Trade" value={formatPercentPlain(selectedPlan.noTradeChance)} />
            <Info label="Entry" value={formatPrice(selectedPlan.entry)} />
            <Info label="Stop Loss" value={formatPrice(selectedPlan.stopLoss)} danger />
            <Info label="Book Profit" value={formatPrice(selectedPlan.takeProfit)} good />
            <Info label="Close / Trail" value={formatPrice(selectedPlan.trailingStop)} />
            <Info label="Confidence" value={`${selectedPlan.confidence}%`} />
            <Info label="Price Source" value={selectedPlan.priceSource || "CMC"} />
            <Info label="CMC 24h" value={formatPercent(selectedPlan.percentChange24h)} />
            <Info label="RSI / MA" value={`${selectedPlan.indicators?.rsi14 || "-"} / ${selectedPlan.indicators?.maTrend || "-"}`} />
            <Info label="MACD / BB" value={`${selectedPlan.indicators?.macdSignal || "-"} / ${selectedPlan.indicators?.bollingerPosition || "-"}`} />
            <Info label="Updated" value={formatUpdated(selectedPlan.lastUpdated)} />
            <Info label="Position Size" value={`${selectedPlan.positionSize.toFixed(4)} ${selectedPlan.symbol}`} />
            <Info label="Risk Amount" value={formatUsd(selectedPlan.riskAmount)} danger />
            <Info label="Signal Grade" value={selectedPlan.signalGrade || "WAIT"} strong />
            <Info label="Category Align" value={selectedPlan.categoryAligned ? "YES" : "NO"} good={selectedPlan.categoryAligned} danger={!selectedPlan.categoryAligned} />
            <Info label="Whale 30%" value={`${scoreValue(selectedPlan.categoryScores?.whaleScore)} ${selectedPlan.categoryScores?.whaleDirection || ""}`} />
            <Info label="On-chain 25%" value={`${scoreValue(selectedPlan.categoryScores?.onchainScore)} ${selectedPlan.categoryScores?.onchainDirection || ""}`} />
            <Info label="Derivatives 25%" value={`${scoreValue(selectedPlan.categoryScores?.derivativesScore)} ${selectedPlan.categoryScores?.derivativesDirection || ""}`} />
            <Info label="Technical 15%" value={`${scoreValue(selectedPlan.categoryScores?.technicalScore)} ${selectedPlan.categoryScores?.technicalDirection || ""}`} />
            <Info label="Sentiment 5%" value={`${scoreValue(selectedPlan.categoryScores?.sentimentScore)} ${selectedPlan.categoryScores?.sentimentDirection || ""}`} />
          </div>
          <div style={formulaNote}>{selectedPlan.categoryScores?.formula || "Whale 30% + On-chain 25% + Derivatives 25% + Technical 15% + News/Sentiment 5%"}</div>
          <div style={ruleList}>
            {selectedPlan.rules.map((rule) => (
              <div key={rule.name} style={rule.pass ? ruleOk : ruleBad}>
                <strong>{rule.pass ? "PASS" : "BLOCK"}</strong>
                <span>{rule.name}</span>
              </div>
            ))}
          </div>
        </section>

        <section style={panelStyle}>
          <div style={panelHeader}>
            <h2 style={panelTitle}>Binance Vault</h2>
            <span style={cashEnabled && binanceStatus.connected ? safePill : warnPill}>{cashEnabled && binanceStatus.connected ? "Testnet connected" : "Paper safe mode"}</span>
          </div>
          <div style={connectBox}>
            <div style={vaultStatusGrid}>
              <Info label="Cash Mode" value={cashEnabled ? "ON" : "OFF"} good={cashEnabled} />
              <Info label="Vault" value={binanceStatus.connected ? "Connected" : "Not Connected"} good={binanceStatus.connected} />
              <Info label="Live Money" value="LOCKED" danger />
              <Info label="Auto Trade" value={cashEnabled && autoTrade ? "TESTNET ARMED" : "OFF"} good={cashEnabled && autoTrade} />
            </div>
            {cashEnabled ? (
              <>
                <label style={fieldLabel}>API Key
                  <input type="password" placeholder="Binance testnet API key" value={apiKey} onChange={(event) => setApiKey(event.target.value)} style={inputFull} />
                </label>
                <label style={fieldLabel}>API Secret
                  <input type="password" placeholder="Binance testnet API secret" value={apiSecret} onChange={(event) => setApiSecret(event.target.value)} style={inputFull} />
                </label>
                <div style={buttonRow}>
                  <button type="button" onClick={connectBinance} style={primaryBtn}>Connect Testnet Vault</button>
                  <button type="button" onClick={disconnectBinance} style={dangerBtn}>Disconnect</button>
                </div>
                <div style={connectNote}>
                  Status: {binanceStatus.connected ? `Connected (${binanceStatus.keyPreview || "masked"})` : "Not connected"} | Vault: {binanceStatus.vaultMode || "unknown"} | Live orders: LOCKED
                </div>
              </>
            ) : (
              <div style={connectNote}>
                Cash Disabled hai. Abhi safe paper monitoring chalega.
                <button type="button" onClick={() => setCashEnabled(true)} style={{ ...secondaryBtn, marginLeft: "10px" }}>Enable Binance Connect</button>
              </div>
            )}
            <div style={connectNote}>Daily cap: max {portfolio.maxDailyTrades} paper trades/day. Real cash mode me bhi same cap, kill switch, SL/TP aur manual final unlock required hoga.</div>
          </div>
        </section>

        <section style={panelStyle}>
          <div style={panelHeader}>
            <h2 style={panelTitle}>Paper Trade Journal</h2>
            <span style={pillStyle}>Actual AI paper trades only</span>
          </div>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Coin</th>
                <th style={thStyle}>Side</th>
                <th style={thStyle}>Entry</th>
                <th style={thStyle}>Stop Loss</th>
                <th style={thStyle}>Book</th>
                <th style={thStyle}>Close</th>
                <th style={thStyle}>PnL</th>
                <th style={thStyle}>Status</th>
              </tr>
            </thead>
            <tbody>
              {dashboard.recentTrades?.map((trade) => (
                <tr key={`db-${trade.id}`}>
                  <td style={tdStyle}>{String(trade.symbol || "").replace("USDT", "")}</td>
                  <td style={trade.side === "LONG" ? longTd : shortTd}>{trade.side}</td>
                  <td style={tdStyle}>{formatPrice(trade.entryPrice)}</td>
                  <td style={tdStyle}>{formatPrice(trade.stopLoss)}</td>
                  <td style={tdStyle}>{formatPrice(trade.takeProfit)}</td>
                  <td style={tdStyle}>{trade.exitPrice ? formatPrice(trade.exitPrice) : "Running"}</td>
                  <td style={Number(trade.pnl || 0) >= 0 ? profitTd : lossTd}>{formatUsd(trade.pnl || 0)}</td>
                  <td style={tdStyle}>{trade.closeReason || trade.status}</td>
                </tr>
              ))}
              {(!dashboard.recentTrades || dashboard.recentTrades.length === 0) && (
                <tr><td style={emptyTd} colSpan="8">Abhi koi AI paper trade record nahi hai.</td></tr>
              )}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  );
}

function Field({ label, value, onChange }) {
  return (
    <label style={fieldLabel}>{label}
      <input type="number" value={value} onChange={(event) => onChange(Number(event.target.value || 0))} style={inputStyle} />
    </label>
  );
}

function Toggle({ label, checked, onClick, danger, good }) {
  const style = checked ? (danger ? dangerToggle : good ? goodToggle : activeToggle) : toggleBtn;
  return <button type="button" onClick={onClick} style={style}>{label}: {checked ? "ON" : "OFF"}</button>;
}

function Metric({ label, value, accent }) {
  return (
    <div style={{ ...metricCard, borderTopColor: accent }}>
      <span style={metricLabel}>{label}</span>
      <strong style={{ ...metricValue, color: accent }}>{value}</strong>
    </div>
  );
}

function Info({ label, value, good, danger, strong }) {
  const color = danger ? "#b91c1c" : good ? "#15803d" : strong ? "#0f2963" : "#334155";
  return (
    <div style={infoCard}>
      <span>{label}</span>
      <strong style={{ color }}>{value}</strong>
    </div>
  );
}

function FeedList({ title, status, items }) {
  const rows = Array.isArray(items) ? items.slice(0, 5) : [];
  return (
    <div style={feedCard}>
      <div style={feedHeader}>
        <strong>{title}</strong>
        <span style={feedStatus}>{status || "WAITING"}</span>
      </div>
      <div style={feedRows}>
        {rows.map((item, index) => (
          <div key={`${title}-${index}`} style={feedRow}>
            <span style={feedAsset}>{item.asset || item.source || item.status || "Feed"}</span>
            <strong style={feedSignal(item.sentiment || item.signal || item.severity)}>{item.sentiment || item.signal || item.severity || item.direction || "INFO"}</strong>
            <small>{item.title || item.flow || item.message || item.rule || "-"}</small>
          </div>
        ))}
        {rows.length === 0 && <div style={feedEmpty}>API data ka wait hai.</div>}
      </div>
    </div>
  );
}

function buildLiveAlerts(dashboard) {
  const alerts = [];
  const intelligence = dashboard.intelligence || {};
  if (intelligence.externalRisk === "HIGH" || intelligence.newsRisk === "HIGH") {
    alerts.push({ id: "risk", type: "Risk", title: "High news/smart-money risk", detail: "AI no-trade chance badhayega.", color: "#b91c1c" });
  }
  (intelligence.topWhales || []).slice(0, 3).forEach((item, index) => {
    if (item.severity === "HIGH" || Number(item.largeTrades || 0) >= 5) {
      alerts.push({
        id: `whale-${index}`,
        type: "Whale",
        title: `${item.asset || "Coin"} large flow`,
        detail: `${item.flow || "Large trades detected"} | ${item.direction || "BALANCED"}`,
        color: "#7c3aed"
      });
    }
  });
  if ((dashboard.liveTrades || []).length > 0) {
    alerts.push({ id: "running", type: "Trade", title: `${dashboard.liveTrades.length} running paper trade`, detail: "Live PnL table me update ho raha hai.", color: "#0f766e" });
  }
  if (alerts.length === 0) {
    alerts.push({ id: "calm", type: "Status", title: "No major alert", detail: "System normal monitoring mode me hai.", color: "#0f2963" });
  }
  return alerts;
}

function buildDashboard(settings) {
  const plans = Object.fromEntries(
    Object.keys(MARKETS).map((symbol) => [symbol, buildPlan(symbol, settings)])
  );
  const list = Object.values(plans);
  const best = list.slice().sort((a, b) => Number(b.allowed) - Number(a.allowed) || b.confidence - a.confidence)[0];
  const monthPnl = 0;
  const trades = 0;
  const weightedWins = 0;

  return {
    plans,
    liveTrades: [],
    recentTrades: [],
    binance: { connected: false, vaultMode: "LOCAL_SIMULATION", liveTradingLocked: true },
    intelligence: {
      whaleTracker: "Waiting for backend",
      newsRisk: "Waiting",
      fakeNewsFilter: "Backend/CoinMarketCap data ka wait",
      rule: "No fake local trade",
      smartMoneyScore: 0,
      externalRisk: "WAITING",
      whaleFeed: "WAITING",
      newsFeed: "WAITING",
      etfFeed: "WAITING",
      topWhales: [],
      freeWhaleSources: [],
      verifiedNews: [],
      etfFlows: []
    },
    portfolio: {
      best,
      monthPnl,
      trades,
      profitableTrades: Math.round(trades * (trades ? Math.round(weightedWins / trades) : 0) / 100),
      lossTrades: trades - Math.round(trades * (trades ? Math.round(weightedWins / trades) : 0) / 100),
      runningTrades: 0,
      todayTrades: Math.min(5, Math.ceil(trades / 10)),
      todayProfitableTrades: Math.min(5, Math.ceil(trades / 14)),
      todayPnl: monthPnl / 20,
      weekPnl: monthPnl / 4,
      maxDailyTrades: 5,
      todaySlotsLeft: Math.max(0, 5 - Math.min(5, Math.ceil(trades / 10))),
      equity: Number(settings.capital || 0) + monthPnl,
      winRate: trades ? Math.round(weightedWins / trades) : 0,
      maxDrawdown: Math.max(...list.map((plan) => plan.paper.drawdown)),
      avgConfidence: Math.round(list.reduce((sum, plan) => sum + plan.confidence, 0) / list.length)
    }
  };
}

function normalizeServerDashboard(serverDashboard, fallback, capital) {
  const plans = { ...fallback.plans };
  (serverDashboard.symbols || []).forEach((signal) => {
    const symbol = String(signal.symbol || "").replace("USDT", "");
    const fallbackPlan = fallback.plans[symbol] || fallback.plans.BTC;
    const finalSignal = signal.finalSignal || "NO_TRADE";
    const aiVotes = (signal.aiVotes || []).map((vote) => ({
      name: vote.ai,
      signal: vote.signal,
      confidence: vote.confidence,
      exit: vote.reason || vote.entryQuality || "-"
    }));
    const longVotes = aiVotes.filter((vote) => vote.signal === "LONG").length;
    const shortVotes = aiVotes.length - longVotes;
    const timeframeRows = {};
    (signal.timeframes || []).forEach((row) => {
      timeframeRows[row.timeframe] = {
        signal: row.signal,
        ma50: row.ma50,
        ma100: row.ma100,
        ma200: row.ma200,
        rsi: row.rsi,
        score: Math.round(Number(signal.finalScore || fallbackPlan.confidence))
      };
    });
    plans[symbol] = {
      ...fallbackPlan,
      symbol,
      direction: finalSignal || fallbackPlan.direction,
      confidence: Number(signal.confidence || fallbackPlan.confidence),
      allowed: Boolean(signal.allowed),
      entry: Number(signal.entry || fallbackPlan.entry),
      priceSource: signal.priceSource || fallbackPlan.priceSource || "FALLBACK",
      marketWarning: signal.marketWarning || "",
      longChance: Number(signal.aiDecision?.longChance || 0),
      shortChance: Number(signal.aiDecision?.shortChance || 0),
      noTradeChance: Number(signal.aiDecision?.noTradeChance || 100),
      signalGrade: signal.signalGrade || "WAIT",
      categoryAligned: Boolean(signal.categoryAligned),
      categoryScores: signal.categoryScores || fallbackPlan.categoryScores,
      aiReason: signal.aiDecision?.reason || "",
      aiSource: signal.aiDecision?.source || "",
      indicators: signal.technicalIndicators || {},
      percentChange1h: Number(signal.percentChange1h || 0),
      percentChange24h: Number(signal.percentChange24h || 0),
      percentChange7d: Number(signal.percentChange7d || 0),
      lastUpdated: signal.lastUpdated || "",
      stopLoss: Number(signal.stopLoss || fallbackPlan.stopLoss),
      takeProfit: Number(signal.takeProfit || fallbackPlan.takeProfit),
      trailingStop: Number(signal.trailingStop || fallbackPlan.trailingStop),
      riskReward: Number(signal.riskReward || fallbackPlan.riskReward || 2),
      positionSize: Number(signal.positionSize || fallbackPlan.positionSize),
      longVotes,
      shortVotes,
      aiVotes: aiVotes.length ? aiVotes : fallbackPlan.aiVotes,
      timeframes: { ...fallbackPlan.timeframes, ...timeframeRows },
      indicatorBreadth: {
        bullish: Number(signal.indicatorSummary?.bullish || fallbackPlan.indicatorBreadth.bullish),
        bearish: Number(signal.indicatorSummary?.bearish || fallbackPlan.indicatorBreadth.bearish),
        score: Number(signal.finalScore || fallbackPlan.indicatorBreadth.score)
      },
      rules: [
        { name: "Backend consensus + risk engine", pass: Boolean(signal.allowed) },
        { name: signal.blockReason || "No block reason", pass: !signal.blockReason },
        { name: "Real money disabled, paper-only mode", pass: true },
        { name: "100 indicator clean summary available", pass: true }
      ],
      paper: fallbackPlan.paper
    };
  });

  const report = serverDashboard.report || {};
  const best = Object.values(plans).sort((a, b) => Number(b.allowed) - Number(a.allowed) || b.confidence - a.confidence)[0] || fallback.portfolio.best;
  return {
    plans,
    liveTrades: serverDashboard.liveTrades || [],
    recentTrades: serverDashboard.recentTrades || [],
    openTrades: serverDashboard.openTrades || [],
    safetyRules: serverDashboard.safetyRules || [],
    binance: serverDashboard.binance || {},
    intelligence: serverDashboard.intelligence || fallback.intelligence,
    portfolio: {
      best,
      monthPnl: Number(report.monthPnl ?? report.virtualPnl ?? 0),
      trades: Number(report.totalTrades || 0),
      profitableTrades: Number(report.profitableTrades || 0),
      cancelledBadDataTrades: Number(report.cancelledBadDataTrades || 0),
      lossTrades: Number(report.lossTrades || 0),
      runningTrades: Number(report.runningTrades || 0),
      todayTrades: Number(report.todayTrades || 0),
      todayProfitableTrades: Number(report.todayProfitableTrades || 0),
      todayPnl: Number(report.todayPnl || 0),
      weekPnl: Number(report.weekPnl || 0),
      maxDailyTrades: Number(report.maxDailyTrades || serverDashboard.maxDailyTrades || 5),
      todaySlotsLeft: Number(report.todaySlotsLeft || serverDashboard.todayTradeSlotsLeft || 0),
      equity: Number(capital || 0) + Number(report.virtualPnl || 0),
      winRate: Number(report.winRate || 0),
      maxDrawdown: Math.abs(Number(report.maxLoss || 0)),
      avgConfidence: Math.round(Object.values(plans).reduce((sum, plan) => sum + plan.confidence, 0) / Object.values(plans).length)
    }
  };
}

function buildPlan(symbol, settings) {
  const market = MARKETS[symbol];
  const timeframes = Object.fromEntries(TIMEFRAMES.map((tf) => [tf, buildWaitingTimeframe(market)]));
  const indicatorBreadth = { bullish: 0, bearish: INDICATORS.length, score: 0, signals: [] };
  const aiVotes = AI_ENGINES.map((name) => ({
    name,
    signal: "NO_TRADE",
    confidence: 0,
    exit: "Backend/CoinMarketCap data ka wait"
  }));
  const direction = "NO_TRADE";
  const confidence = 0;
  const entry = market.price;
  const stopDistance = 0;
  const stopLoss = entry - stopDistance;
  const takeProfit = entry + stopDistance * 2;
  const trailingStop = entry;
  const riskAmount = Number(settings.capital || 0) * Number(settings.riskPercent || 0) / 100;
  const positionUsdt = entry > 0 ? Math.min(Number(settings.capital || 0) * Number(settings.maxLeverage || 1), riskAmount / (stopDistance / entry || 1)) : 0;
  const positionSize = Math.max(0, positionUsdt / entry);
  const rules = [
    { name: "Backend dashboard load hona chahiye", pass: false },
    { name: "CoinMarketCap live price required", pass: false },
    { name: "Fake local signal disabled", pass: true }
  ];
  const allowed = false;
  const paper = buildPaperStats(symbol, confidence, direction, allowed, settings.capital);

  return {
    symbol,
    direction,
    confidence,
    longVotes: 0,
    shortVotes: 0,
    aiVotes,
    indicatorBreadth,
    timeframes,
    entry,
    priceSource: "WAITING_FOR_BACKEND",
    marketWarning: "Backend/CoinMarketCap live data load nahi hua. Fake trade signal disabled hai.",
    longChance: 0,
    shortChance: 0,
    noTradeChance: 100,
    aiReason: "Waiting for backend AI payload",
    aiSource: "WAITING",
    signalGrade: "WAIT",
    categoryAligned: false,
    categoryScores: {
      formula: "Whale 30% + On-chain 25% + Derivatives 25% + Technical 15% + News/Sentiment 5%",
      whaleScore: 0,
      onchainScore: 0,
      derivativesScore: 0,
      technicalScore: 0,
      sentimentScore: 0,
      whaleDirection: "WAIT",
      onchainDirection: "WAIT",
      derivativesDirection: "WAIT",
      technicalDirection: "WAIT",
      sentimentDirection: "WAIT"
    },
    indicators: {},
    percentChange1h: 0,
    percentChange24h: 0,
    percentChange7d: 0,
    lastUpdated: "",
    stopLoss,
    takeProfit,
    trailingStop,
    riskAmount,
    positionSize,
    rules,
    allowed,
    paper
  };
}

function buildWaitingTimeframe(market) {
  return {
    signal: "NO_TRADE",
    ma50: market.price,
    ma100: market.price,
    ma200: market.price,
    rsi: 50,
    score: 0
  };
}

function buildPaperStats(symbol, confidence, direction, allowed, capital) {
  const base = MARKETS[symbol].seed + confidence + (direction === "LONG" ? 9 : -7);
  const trades = 22 + (base % 17);
  const winRate = allowed ? 54 + (base % 18) : 42 + (base % 9);
  const pnlPercent = allowed ? (winRate - 50) / 5 : -1.2;
  const pnl = Number(capital || 0) * pnlPercent / 100;
  const drawdown = allowed ? 3 + (base % 6) : 8 + (base % 5);
  return { trades, winRate, pnl, drawdown };
}

function formatPrice(value) {
  return `$${Number(value || 0).toLocaleString("en-US", { maximumFractionDigits: 2 })}`;
}

function formatUsd(value) {
  return `${Number(value || 0) >= 0 ? "+" : "-"}$${Math.abs(Number(value || 0)).toLocaleString("en-US", { maximumFractionDigits: 2 })}`;
}

function formatPercent(value) {
  const number = Number(value || 0);
  return `${number >= 0 ? "+" : ""}${number.toFixed(2)}%`;
}

function formatPercentPlain(value) {
  return `${Number(value || 0).toFixed(0)}%`;
}

function scoreValue(value) {
  return `${Math.round(Number(value || 0))}%`;
}

function formatUpdated(value) {
  if (!value) return "Waiting for CMC";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString("en-IN", { dateStyle: "short", timeStyle: "short" });
}

function signalTextStyle(signal) {
  if (signal === "LONG") return longSignalText;
  if (signal === "SHORT") return shortSignalText;
  return noTradeSignalText;
}

function feedSignal(value) {
  const text = String(value || "").toUpperCase();
  const color = text.includes("HIGH") || text.includes("RISK_OFF") || text.includes("OUTFLOW") ? "#b91c1c"
    : text.includes("INFLOW") || text.includes("RISK_ON") || text.includes("NORMAL") ? "#166534"
      : "#92400e";
  return { color };
}

const pageStyle = { padding: "28px", background: "#eef2f6", minHeight: "100vh", color: "#0f172a" };
const heroStyle = { background: "linear-gradient(135deg, #071635 0%, #0f2963 52%, #0b3b36 100%)", color: "#ffffff", borderRadius: "8px", padding: "24px", display: "flex", justifyContent: "space-between", gap: "18px", alignItems: "center", marginBottom: "18px", boxShadow: "0 14px 34px rgba(15, 41, 99, 0.22)" };
const eyebrowStyle = { color: "#f9d989", fontSize: "12px", fontWeight: "900", textTransform: "uppercase" };
const titleStyle = { margin: "6px 0", fontSize: "30px", color: "#ffffff" };
const subtitleStyle = { color: "#cbd5e1", fontSize: "14px" };
const heroStats = { minWidth: "205px", background: "rgba(255,255,255,0.1)", border: "1px solid rgba(249,217,137,0.45)", borderRadius: "8px", padding: "14px", textAlign: "right" };
const heroLabel = { display: "block", color: "#cbd5e1", fontSize: "12px", fontWeight: "800", marginBottom: "8px" };
const heroProfit = { display: "block", color: "#bbf7d0", fontSize: "28px", fontWeight: "900" };
const heroLoss = { display: "block", color: "#fecaca", fontSize: "28px", fontWeight: "900" };
const heroSmall = { color: "#cbd5e1", fontWeight: "700" };
const controlPanel = { background: "#ffffff", border: "1px solid #e2e8f0", borderRadius: "8px", padding: "16px", display: "flex", flexWrap: "wrap", gap: "12px", marginBottom: "12px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)" };
const fieldLabel = { display: "flex", flexDirection: "column", gap: "6px", color: "#475569", fontSize: "12px", fontWeight: "900", textTransform: "uppercase" };
const inputStyle = { padding: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "125px", color: "#0f172a", fontWeight: "800" };
const inputFull = { ...inputStyle, width: "100%", boxSizing: "border-box" };
const toggleBtn = { alignSelf: "end", padding: "10px 13px", border: "1px solid #cbd5e1", background: "#ffffff", borderRadius: "6px", cursor: "pointer", fontWeight: "900" };
const activeToggle = { ...toggleBtn, background: "#e0f2fe", color: "#075985", borderColor: "#7dd3fc" };
const goodToggle = { ...toggleBtn, background: "#dcfce7", color: "#166534", borderColor: "#86efac" };
const dangerToggle = { ...toggleBtn, background: "#fee2e2", color: "#991b1b", borderColor: "#fca5a5" };
const noticeStyle = { background: "#fffbeb", border: "1px solid #fcd34d", color: "#92400e", padding: "12px 14px", borderRadius: "8px", marginBottom: "18px", fontSize: "13px" };
const alertStrip = { background: "#ffffff", border: "1px solid #e2e8f0", borderRadius: "8px", marginBottom: "18px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", overflow: "hidden" };
const alertHeader = { padding: "12px 14px", borderBottom: "1px solid #e2e8f0", display: "flex", justifyContent: "space-between", color: "#0f2963" };
const alertActions = { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", justifyContent: "flex-end" };
const alertGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", gap: "10px", padding: "12px" };
const alertCard = { border: "1px solid #e2e8f0", borderLeft: "4px solid #0f2963", borderRadius: "8px", padding: "11px", display: "grid", gap: "4px", background: "#f8fafc" };
const alertType = { fontSize: "11px", fontWeight: "900", textTransform: "uppercase" };
const notificationBtn = { border: "1px solid #0f2963", background: "#ffffff", color: "#0f2963", borderRadius: "6px", padding: "6px 9px", fontWeight: "900", cursor: "pointer" };
const intelligenceGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", gap: "12px", marginBottom: "18px" };
const strategyPanel = { background: "#ffffff", border: "1px solid #e2e8f0", borderRadius: "8px", marginBottom: "18px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", overflow: "hidden" };
const panelHeaderCompact = { padding: "13px 16px", borderBottom: "1px solid #e2e8f0", display: "flex", justifyContent: "space-between", gap: "10px", alignItems: "center" };
const strategyGrid = { padding: "14px", display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(210px, 1fr))", gap: "10px" };
const coinGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: "14px", marginBottom: "18px" };
const probabilityBar = { display: "flex", height: "8px", overflow: "hidden", borderRadius: "999px", background: "#e5e7eb", margin: "8px 0 6px" };
const probabilityFill = { display: "block", height: "100%" };
const coinCardBase = { textAlign: "left", borderRadius: "8px", padding: "16px", cursor: "pointer", border: "1px solid #e2e8f0", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)" };
const coinCard = (plan) => ({ ...coinCardBase, background: "#ffffff", borderTop: `4px solid ${plan.direction === "LONG" ? "#0f766e" : plan.direction === "SHORT" ? "#b91c1c" : "#92400e"}` });
const selectedCoinCard = (plan) => ({ ...coinCard(plan), outline: "3px solid #f9d989", background: "#f8fafc" });
const coinTopRow = { display: "flex", justifyContent: "space-between", gap: "10px", alignItems: "center" };
const coinName = { color: "#0f172a", fontSize: "18px" };
const readyPill = { background: "#dcfce7", color: "#166534", borderRadius: "999px", padding: "5px 8px", fontSize: "11px", fontWeight: "900" };
const blockedPill = { background: "#fef3c7", color: "#92400e", borderRadius: "999px", padding: "5px 8px", fontSize: "11px", fontWeight: "900" };
const longSignalText = { color: "#0f766e", fontSize: "30px", fontWeight: "900", margin: "10px 0" };
const shortSignalText = { color: "#b91c1c", fontSize: "30px", fontWeight: "900", margin: "10px 0" };
const noTradeSignalText = { color: "#92400e", fontSize: "30px", fontWeight: "900", margin: "10px 0" };
const coinMeta = { color: "#64748b", fontSize: "13px", fontWeight: "700", marginTop: "4px" };
const warningText = { color: "#92400e", background: "#fffbeb", border: "1px solid #fde68a", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", fontWeight: "800", marginTop: "8px" };
const metricGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: "14px", marginBottom: "18px" };
const metricCard = { background: "#ffffff", padding: "16px", borderRadius: "8px", borderTop: "4px solid #0f2963", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", minHeight: "82px", display: "flex", flexDirection: "column", justifyContent: "space-between" };
const metricLabel = { color: "#64748b", fontSize: "12px", fontWeight: "900", textTransform: "uppercase" };
const metricValue = { fontSize: "23px", fontWeight: "900", marginTop: "10px" };
const mainGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(455px, 1fr))", gap: "18px" };
const panelStyle = { background: "#ffffff", borderRadius: "8px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", overflow: "hidden", border: "1px solid #e2e8f0" };
const panelHeader = { padding: "15px 18px", borderBottom: "1px solid #e2e8f0", display: "flex", justifyContent: "space-between", gap: "10px", alignItems: "center" };
const panelTitle = { margin: 0, fontSize: "17px", color: "#0f2963" };
const pillStyle = { background: "#eef2ff", color: "#0f2963", borderRadius: "999px", padding: "6px 10px", fontSize: "12px", fontWeight: "900" };
const safePill = { ...pillStyle, background: "#dcfce7", color: "#166534" };
const warnPill = { ...pillStyle, background: "#fef3c7", color: "#92400e" };
const dangerPill = { ...pillStyle, background: "#fee2e2", color: "#991b1b" };
const smartMoneyPanel = { background: "#ffffff", borderRadius: "8px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", overflow: "hidden", border: "1px solid #e2e8f0", marginBottom: "18px" };
const feedGrid = { padding: "16px", display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: "14px" };
const feedCard = { border: "1px solid #e2e8f0", borderRadius: "8px", background: "#f8fafc", overflow: "hidden" };
const feedHeader = { display: "flex", justifyContent: "space-between", gap: "10px", padding: "12px", borderBottom: "1px solid #e2e8f0", color: "#0f2963", alignItems: "center" };
const feedStatus = { fontSize: "11px", fontWeight: "900", color: "#64748b", textAlign: "right" };
const feedRows = { display: "grid" };
const feedRow = { display: "grid", gap: "5px", padding: "11px 12px", borderBottom: "1px solid #e2e8f0", color: "#334155" };
const feedAsset = { fontSize: "12px", color: "#64748b", fontWeight: "900", textTransform: "uppercase" };
const feedEmpty = { padding: "14px", color: "#94a3b8", fontWeight: "800", textAlign: "center" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const tableWrap = { width: "100%", overflowX: "auto" };
const thStyle = { textAlign: "left", padding: "11px 14px", background: "#f8fafc", color: "#475569", fontSize: "12px", textTransform: "uppercase", borderBottom: "1px solid #e2e8f0" };
const tdStyle = { padding: "12px 14px", borderBottom: "1px solid #edf2f7", color: "#334155", fontSize: "13px", verticalAlign: "top" };
const reasonTd = { ...tdStyle, minWidth: "260px", maxWidth: "420px", lineHeight: 1.35 };
const emptyTd = { ...tdStyle, textAlign: "center", color: "#94a3b8" };
const longTd = { ...tdStyle, color: "#0f766e", fontWeight: "900" };
const shortTd = { ...tdStyle, color: "#b91c1c", fontWeight: "900" };
const profitTd = { ...tdStyle, color: "#15803d", fontWeight: "900" };
const lossTd = { ...tdStyle, color: "#b91c1c", fontWeight: "900" };
const orderGrid = { padding: "16px", display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "10px" };
const infoCard = { border: "1px solid #e2e8f0", background: "#f8fafc", borderRadius: "8px", padding: "10px", display: "grid", gap: "5px", color: "#64748b", fontSize: "12px", fontWeight: "900", textTransform: "uppercase" };
const formulaNote = { margin: "0 16px 14px", padding: "10px", border: "1px solid #dbeafe", background: "#eff6ff", color: "#0f2963", borderRadius: "8px", fontSize: "13px", fontWeight: "900" };
const ruleList = { padding: "0 16px 16px", display: "grid", gap: "9px" };
const ruleOk = { border: "1px solid #bbf7d0", background: "#f0fdf4", color: "#166534", borderRadius: "8px", padding: "10px", display: "flex", gap: "10px" };
const ruleBad = { border: "1px solid #fed7aa", background: "#fff7ed", color: "#9a3412", borderRadius: "8px", padding: "10px", display: "flex", gap: "10px" };
const connectBox = { padding: "16px", display: "grid", gap: "12px" };
const vaultStatusGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(135px, 1fr))", gap: "10px" };
const buttonRow = { display: "flex", gap: "10px", flexWrap: "wrap" };
const primaryBtn = { padding: "11px 14px", background: "#0f2963", color: "#ffffff", border: "none", borderRadius: "6px", fontWeight: "900", cursor: "pointer" };
const secondaryBtn = { padding: "11px 14px", background: "#ffffff", color: "#0f2963", border: "1px solid #0f2963", borderRadius: "6px", fontWeight: "900", cursor: "pointer" };
const dangerBtn = { ...primaryBtn, background: "#b91c1c" };
const connectNote = { background: "#f8fafc", border: "1px solid #e2e8f0", borderRadius: "8px", padding: "11px", color: "#475569", fontSize: "13px", fontWeight: "700" };
export default CryptoTrading;
