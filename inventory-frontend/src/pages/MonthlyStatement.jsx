import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiRequest } from "../utils/api";
import { calculateReport, getCurrentMonthKey, getMonthLabel, getStatementRows } from "../utils/reporting";

function MonthlyStatement() {
  const navigate = useNavigate();
  const [products, setProducts] = useState([]);
  const [dailyBook, setDailyBook] = useState([]);
  const [invoices, setInvoices] = useState([]);
  const [monthKey, setMonthKey] = useState(getCurrentMonthKey());
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [productsData, dailyBookData, invoicesData] = await Promise.all([
        apiRequest("/products/options"),
        apiRequest(`/daily-book/month?month=${monthKey}`),
        apiRequest(`/invoices/month?month=${monthKey}&limit=500`)
      ]);
      setProducts(Array.isArray(productsData) ? productsData : []);
      setDailyBook(Array.isArray(dailyBookData) ? dailyBookData : []);
      setInvoices(Array.isArray(invoicesData) ? invoicesData : []);
    } catch (error) {
      alert(`Statement load error: ${error.message}`);
    } finally {
      setLoading(false);
    }
  }, [monthKey]);

  useEffect(() => {
    load();
  }, [load]);

  const report = useMemo(() => calculateReport({ invoices, dailyBook, monthKey }), [invoices, dailyBook, monthKey]);
  const rows = useMemo(() => getStatementRows({ invoices, dailyBook, monthKey }), [invoices, dailyBook, monthKey]);
  const stockValue = products.reduce((sum, product) => sum + Number(product.quantity || 0) * Number(product.sellPrice || 0), 0);
  const goToUdhar = (row) => {
    const params = new URLSearchParams({ udhar: "1" });
    const searchText = row?.party && row.party !== "-" ? row.party : row?.note;
    if (searchText) {
      params.set("q", searchText);
      params.set("searchBy", "all");
    }
    navigate(`/old-bills?${params.toString()}`);
  };

  return (
    <div style={pageStyle}>
      <section style={headerStyle}>
        <div>
          <div style={eyebrowStyle}>Monthly Statement</div>
          <h1 style={titleStyle}>{getMonthLabel(monthKey)}</h1>
          <div style={subtitleStyle}>Billing, profit, udhar aur daily book ka month-wise hisaab.</div>
        </div>
        <div style={controlBox}>
          <label style={labelStyle}>Select Month</label>
          <input type="month" value={monthKey} onChange={(event) => setMonthKey(event.target.value)} style={monthInput} />
          <button type="button" onClick={load} style={refreshBtn}>{loading ? "Loading..." : "Refresh"}</button>
        </div>
      </section>

      <div style={cardGrid}>
        <MetricCard label="Billing" value={formatMoney(report.invoiceTotal)} accent="#9c742a" />
        <MetricCard label="Washing / Labour Profit" value={formatMoney(report.washingProfit)} accent="#0f766e" />
        <MetricCard label="Accessories Sale" value={formatMoney(report.totals.accessories)} accent="#0f2963" />
        <MetricCard label="Accessories Profit" value={formatMoney(report.totals.accessoriesProfit)} accent="#15803d" />
        <MetricCard label="Gross Profit" value={formatMoney(report.grossProfit)} accent="#166534" />
        <MetricCard label="Labour / Shop Expense" value={formatMoney(report.totals.expense)} accent="#b91c1c" />
        <MetricCard label="Parts / Stock Purchase" value={formatMoney(report.totals.stockPurchaseExpense)} accent="#6d4c1d" />
        <MetricCard label="Net Profit" value={formatMoney(report.netProfit)} accent="#0f5132" />
        <MetricCard label="Udhar Pending" value={formatMoney(report.totals.udhar)} accent="#dc2626" onClick={() => goToUdhar()} />
        <MetricCard label="Current Stock Value" value={formatMoney(stockValue)} accent="#334155" />
      </div>

      <section style={panelStyle}>
        <div style={panelHeader}>
          <h2 style={panelTitle}>Statement Entries</h2>
          <span style={mutedText}>{rows.length} entries</span>
        </div>
        <div style={tableWrap}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Date</th>
                <th style={thStyle}>Type</th>
                <th style={thStyle}>Party</th>
                <th style={thStyle}>Note</th>
                <th style={thStyle}>Income</th>
                <th style={thStyle}>Expense</th>
                <th style={thStyle}>Udhar</th>
                <th style={thStyle}>Profit</th>
                <th style={thStyle}>Balance</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} onClick={row.udhar ? () => goToUdhar(row) : undefined} style={row.udhar ? clickableRow : undefined}>
                  <td style={tdStyle}>{formatDate(row.date)}</td>
                  <td style={tdStyle}>{row.type}</td>
                  <td style={tdStyle}>{row.party}</td>
                  <td style={tdStyle}>{row.note}</td>
                  <td style={amountTd}>{row.income ? formatMoney(row.income) : "-"}</td>
                  <td style={expenseTd}>{row.expense ? formatMoney(row.expense) : "-"}</td>
                  <td style={udharTd}>{row.udhar ? formatMoney(row.udhar) : "-"}</td>
                  <td style={amountTd}>{row.profit ? formatMoney(row.profit) : "-"}</td>
                  <td style={strongAmountTd}>{formatMoney(row.balance)}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td style={emptyTd} colSpan="9">Is month me abhi koi entry nahi hai.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function MetricCard({ label, value, accent, onClick }) {
  return (
    <div
      onClick={onClick}
      role={onClick ? "button" : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={(event) => {
        if (onClick && (event.key === "Enter" || event.key === " ")) onClick();
      }}
      style={{ ...cardStyle, borderTopColor: accent, ...(onClick ? clickableCard : {}) }}
    >
      <span style={cardLabel}>{label}</span>
      <strong style={{ ...cardValue, color: accent }}>{value}</strong>
      {onClick && <span style={cardHint}>Click to view details</span>}
    </div>
  );
}

function formatMoney(value) {
  return `Rs. ${Number(value || 0).toLocaleString("en-IN")}`;
}

function formatDate(value) {
  return String(value || "").slice(0, 10) || "-";
}

const pageStyle = { padding: "28px", background: "#eef2f6", minHeight: "100vh", color: "#0f172a" };
const headerStyle = { background: "#ffffff", border: "1px solid #e2e8f0", borderRadius: "8px", padding: "22px", marginBottom: "18px", display: "flex", justifyContent: "space-between", gap: "18px", alignItems: "center", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)" };
const eyebrowStyle = { color: "#9c742a", fontSize: "12px", fontWeight: "900", textTransform: "uppercase" };
const titleStyle = { margin: "6px 0", color: "#0f2963", fontSize: "30px" };
const subtitleStyle = { color: "#64748b", fontSize: "14px" };
const controlBox = { display: "flex", alignItems: "end", gap: "10px", flexWrap: "wrap" };
const labelStyle = { display: "flex", flexDirection: "column", gap: "6px", color: "#475569", fontSize: "12px", fontWeight: "800", textTransform: "uppercase" };
const monthInput = { padding: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", fontWeight: "800", color: "#0f172a" };
const refreshBtn = { border: "1px solid #0f2963", background: "#0f2963", color: "#ffffff", borderRadius: "6px", padding: "11px 14px", fontWeight: "900", cursor: "pointer" };
const cardGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(205px, 1fr))", gap: "16px", marginBottom: "18px" };
const cardStyle = { background: "#ffffff", padding: "17px", borderRadius: "8px", borderTop: "4px solid #0f2963", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", minHeight: "88px", display: "flex", flexDirection: "column", justifyContent: "space-between" };
const clickableCard = { cursor: "pointer", outline: "none" };
const cardLabel = { color: "#64748b", fontSize: "12px", fontWeight: "900", textTransform: "uppercase" };
const cardValue = { fontSize: "24px", fontWeight: "900", marginTop: "10px" };
const cardHint = { color: "#64748b", fontSize: "12px", fontWeight: "800", marginTop: "8px" };
const panelStyle = { background: "#ffffff", borderRadius: "8px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", overflow: "hidden", border: "1px solid #e2e8f0" };
const panelHeader = { padding: "15px 18px", borderBottom: "1px solid #e2e8f0", display: "flex", justifyContent: "space-between", alignItems: "center" };
const panelTitle = { margin: 0, fontSize: "17px", color: "#0f2963" };
const mutedText = { color: "#64748b", fontSize: "13px", fontWeight: "800" };
const tableWrap = { overflowX: "auto" };
const tableStyle = { width: "100%", borderCollapse: "collapse", minWidth: "980px" };
const thStyle = { textAlign: "left", padding: "11px 14px", background: "#f8fafc", color: "#475569", fontSize: "12px", textTransform: "uppercase", borderBottom: "1px solid #e2e8f0" };
const tdStyle = { padding: "12px 14px", borderBottom: "1px solid #edf2f7", color: "#334155", fontSize: "13px", verticalAlign: "top" };
const clickableRow = { cursor: "pointer", background: "#fff7ed" };
const amountTd = { ...tdStyle, color: "#0f2963", fontWeight: "900", whiteSpace: "nowrap" };
const expenseTd = { ...amountTd, color: "#b91c1c" };
const udharTd = { ...amountTd, color: "#dc2626" };
const strongAmountTd = { ...amountTd, color: "#0f5132" };
const emptyTd = { ...tdStyle, textAlign: "center", color: "#94a3b8" };

export default MonthlyStatement;
