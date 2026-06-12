import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiRequest, formatInvoiceCategory, getInvoiceProfit } from "../utils/api";
import { calculateReport, getCurrentMonthKey } from "../utils/reporting";

function Dashboard() {
  const navigate = useNavigate();
  const [products, setProducts] = useState([]);
  const [dailyBook, setDailyBook] = useState([]);
  const [invoices, setInvoices] = useState([]);

  const load = async () => {
    try {
      const [productsData, dailyBookData, invoicesData] = await Promise.all([
        apiRequest("/products"),
        apiRequest("/daily-book"),
        apiRequest("/invoices")
      ]);

      setProducts(Array.isArray(productsData) ? productsData : []);
      setDailyBook(Array.isArray(dailyBookData) ? dailyBookData : []);
      setInvoices(Array.isArray(invoicesData) ? invoicesData : []);
    } catch {
      setProducts([]);
      setDailyBook([]);
      setInvoices([]);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const currentMonthKey = getCurrentMonthKey();
  const report = useMemo(
    () => calculateReport({ invoices, dailyBook, monthKey: currentMonthKey }),
    [dailyBook, invoices, currentMonthKey]
  );
  const totals = report.totals;

  const lowStockProducts = products.filter((product) =>
    Number(product.quantity || 0) <= Number(product.minimumStock || 1)
  );
  const goToLowStock = (product) => {
    const params = new URLSearchParams({ lowStock: "1" });
    if (product?.id) params.set("productId", product.id);
    if (product?.productName) params.set("q", product.productName);
    navigate(`/products?${params.toString()}`);
  };
  const stockValue = products.reduce((sum, product) => sum + Number(product.quantity || 0) * Number(product.sellPrice || 0), 0);
  const invoiceTotal = report.invoiceTotal;
  const washingProfit = report.washingProfit;
  const grossProfit = report.grossProfit;
  const netProfit = report.netProfit;
  const profitBreakdown = [
    { label: "Washing / Labour Profit", note: "Washing, service aur labour income", amount: washingProfit, type: "plus" },
    { label: "Accessories Profit", note: "Selling price minus purchase price", amount: totals.accessoriesProfit, type: "plus" },
    { label: "Gross Profit", note: "Washing + accessories profit", amount: grossProfit, type: "total" },
    { label: "Paid Daily Expense", note: "Sirf paid kharch minus hoga, udhar alag rahega", amount: totals.expense, type: "minus" },
    { label: "Net Profit", note: "Gross profit - expense", amount: netProfit, type: "final" }
  ];
  const goToUdhar = () => navigate("/old-bills?udhar=1");
  const recentInvoices = report.invoices.slice().reverse().slice(0, 6);
  const recentEntries = report.dailyBook.slice().reverse().slice(0, 6);

  return (
    <div style={pageStyle}>
      <section style={heroStyle}>
        <div style={brandBlock}>
          <VaishnavLogo />
          <div>
            <div style={eyebrowStyle}>Premium Detailing Studio</div>
            <h1 style={titleStyle}>Vaishnav Car Wash And Accessories</h1>
            <div style={subtitleStyle}>Current month: {report.monthLabel}. Old month statement alag se dekh sakte hain.</div>
          </div>
        </div>
        <div style={heroMetric}>
          <span style={heroMetricLabel}>This Month Net Profit</span>
          <strong style={heroMetricValue}>{formatMoney(netProfit)}</strong>
          <button type="button" onClick={() => navigate("/monthly-statement")} style={statementBtn}>Old Month Statement</button>
        </div>
      </section>

      {lowStockProducts.length > 0 && (
        <div style={alertBox}>
          <strong>Low Stock Alert:</strong> {lowStockProducts.length} product minimum stock par hai.
          <div style={alertList}>
            {lowStockProducts.slice(0, 8).map((product) => (
              <button key={product.id} type="button" onClick={() => goToLowStock(product)} style={alertPill}>
                {product.productName} ({product.quantity ?? 0})
              </button>
            ))}
          </div>
        </div>
      )}

      <div style={cardGrid}>
        <MetricCard label="Washing / Labour Profit" value={formatMoney(washingProfit)} accent="#0f766e" />
        <MetricCard label="Accessories Sale" value={formatMoney(totals.accessories)} accent="#0f2963" />
        <MetricCard label="Accessories Profit" value={formatMoney(totals.accessoriesProfit)} accent="#15803d" />
        <MetricCard label="Gross Profit" value={formatMoney(grossProfit)} accent="#166534" />
        <MetricCard label="Net Profit" value={formatMoney(netProfit)} accent="#0f5132" />
        <MetricCard label="This Month Billing" value={formatMoney(invoiceTotal)} accent="#9c742a" />
        <MetricCard label="Stock Value" value={formatMoney(stockValue)} accent="#334155" />
        <MetricCard label="Total Products" value={products.length} accent="#475569" />
        <MetricCard label="Low Stock" value={lowStockProducts.length} accent="#c2410c" onClick={() => goToLowStock()} />
        <MetricCard label="Udhar Pending" value={formatMoney(totals.udhar)} accent="#b91c1c" onClick={goToUdhar} />
        <MetricCard label="Paid Daily Expense" value={formatMoney(totals.expense)} accent="#7f1d1d" />
      </div>

      <div style={panelGrid}>
        <section style={panelStyle}>
          <div style={panelHeader}>
            <h2 style={panelTitle}>Profit Breakdown</h2>
          </div>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Part</th>
                <th style={thStyle}>Detail</th>
                <th style={thStyle}>Amount</th>
              </tr>
            </thead>
            <tbody>
              {profitBreakdown.map((row) => (
                <tr key={row.label}>
                  <td style={row.type === "final" ? strongTd : tdStyle}>{row.label}</td>
                  <td style={tdStyle}>{row.note}</td>
                  <td style={profitAmountStyle(row.type)}>
                    {row.type === "minus" ? "- " : ""}
                    {formatMoney(row.amount)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section style={panelStyle}>
          <div style={panelHeader}>
            <h2 style={panelTitle}>This Month Bills</h2>
            <button onClick={load} style={refreshBtn}>Refresh</button>
          </div>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Bill</th>
                <th style={thStyle}>Customer</th>
                <th style={thStyle}>Category</th>
                <th style={thStyle}>Amount</th>
                <th style={thStyle}>Profit</th>
              </tr>
            </thead>
            <tbody>
              {recentInvoices.map((invoice) => (
                <tr key={invoice.id}>
                  <td style={tdStyle}>{invoice.invoiceNumber || "-"}</td>
                  <td style={tdStyle}>{invoice.customer?.customerName || "Walk-in"}</td>
                  <td style={tdStyle}>{formatInvoiceCategory(invoice)}</td>
                  <td style={amountTd}>{formatMoney(invoice.totalAmount || 0)}</td>
                  <td style={amountTd}>{formatMoney(getInvoiceProfit(invoice))}</td>
                </tr>
              ))}
              {recentInvoices.length === 0 && <tr><td style={emptyTd} colSpan="5">No bills yet</td></tr>}
            </tbody>
          </table>
        </section>

        <section style={panelStyle}>
          <div style={panelHeader}>
            <h2 style={panelTitle}>This Month Daily Book</h2>
          </div>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Type</th>
                <th style={thStyle}>Party</th>
                <th style={thStyle}>Note</th>
                <th style={thStyle}>Amount</th>
              </tr>
            </thead>
            <tbody>
              {recentEntries.map((entry) => (
                <tr key={entry.id}>
                  <td style={tdStyle}>{entry.entryType}</td>
                  <td style={tdStyle}>{entry.partyName || "-"}</td>
                  <td style={tdStyle}>{entry.note || "-"}</td>
                  <td style={amountTd}>{formatMoney(entry.amount || 0)}</td>
                </tr>
              ))}
              {recentEntries.length === 0 && <tr><td style={emptyTd} colSpan="4">No daily book entries yet</td></tr>}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  );
}

function VaishnavLogo() {
  return (
    <div style={logoShell} aria-label="Vaishnav logo">
      <div style={logoRing}>
        <div style={logoLetter}>V</div>
      </div>
      <div style={logoRibbon}>DETAILING</div>
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
      style={{ ...cardStyle, borderTopColor: accent, ...(onClick ? clickableCardStyle : {}) }}
    >
      <span style={cardLabel}>{label}</span>
      <strong style={{ ...cardValue, color: accent }}>{value}</strong>
    </div>
  );
}

function formatMoney(value) {
  return `Rs. ${Number(value || 0).toLocaleString("en-IN")}`;
}

const pageStyle = { padding: "28px", background: "#eef2f6", minHeight: "100vh", color: "#0f172a" };
const heroStyle = { background: "linear-gradient(135deg, #071635 0%, #0f2963 58%, #12233f 100%)", color: "#ffffff", borderRadius: "8px", padding: "24px", display: "flex", justifyContent: "space-between", alignItems: "center", gap: "18px", boxShadow: "0 14px 34px rgba(15, 41, 99, 0.22)", marginBottom: "22px" };
const brandBlock = { display: "flex", alignItems: "center", gap: "18px" };
const eyebrowStyle = { color: "#f9d989", fontSize: "12px", fontWeight: "800", textTransform: "uppercase", letterSpacing: "1px", marginBottom: "5px" };
const titleStyle = { margin: 0, color: "#ffffff", fontSize: "30px", fontWeight: "900" };
const subtitleStyle = { marginTop: "7px", color: "#cbd5e1", fontSize: "14px" };
const logoShell = { width: "104px", minWidth: "104px", height: "116px", position: "relative", display: "flex", alignItems: "center", justifyContent: "center" };
const logoRing = { width: "86px", height: "86px", borderRadius: "50%", background: "linear-gradient(135deg, #c49a45, #f9d989 45%, #9c742a)", padding: "5px", boxShadow: "0 10px 22px rgba(0,0,0,0.22)" };
const logoLetter = { width: "100%", height: "100%", borderRadius: "50%", background: "radial-gradient(circle at 35% 25%, #203a72, #071635 70%)", border: "2px solid rgba(249,217,137,0.65)", display: "flex", alignItems: "center", justifyContent: "center", color: "#f9d989", fontFamily: "Georgia, serif", fontSize: "48px", fontWeight: "900" };
const logoRibbon = { position: "absolute", bottom: "7px", background: "#f9d989", color: "#071635", borderRadius: "4px", padding: "4px 10px", fontSize: "10px", fontWeight: "900", letterSpacing: "1px", boxShadow: "0 6px 14px rgba(0,0,0,0.18)" };
const heroMetric = { minWidth: "190px", background: "rgba(255,255,255,0.1)", border: "1px solid rgba(249,217,137,0.45)", borderRadius: "8px", padding: "16px", textAlign: "right" };
const heroMetricLabel = { display: "block", color: "#cbd5e1", fontSize: "12px", fontWeight: "700", marginBottom: "7px" };
const heroMetricValue = { display: "block", color: "#f9d989", fontSize: "28px", fontWeight: "900" };
const statementBtn = { marginTop: "12px", border: "1px solid rgba(249,217,137,0.75)", background: "#f9d989", color: "#071635", borderRadius: "5px", padding: "8px 11px", fontWeight: "900", cursor: "pointer" };
const cardGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(210px, 1fr))", gap: "16px", marginBottom: "18px" };
const cardStyle = { background: "#ffffff", padding: "18px", borderRadius: "8px", borderTop: "4px solid #0f2963", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", minHeight: "92px", display: "flex", flexDirection: "column", justifyContent: "space-between" };
const clickableCardStyle = { cursor: "pointer", outline: "none" };
const cardLabel = { color: "#64748b", fontSize: "13px", fontWeight: "800", textTransform: "uppercase" };
const cardValue = { fontSize: "26px", fontWeight: "900", marginTop: "12px" };
const panelGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(360px, 1fr))", gap: "18px" };
const panelStyle = { background: "#ffffff", borderRadius: "8px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", overflow: "hidden", border: "1px solid #e2e8f0" };
const panelHeader = { padding: "15px 18px", borderBottom: "1px solid #e2e8f0", display: "flex", justifyContent: "space-between", alignItems: "center" };
const panelTitle = { margin: 0, fontSize: "17px", color: "#0f2963" };
const refreshBtn = { border: "1px solid #0f2963", background: "#ffffff", color: "#0f2963", borderRadius: "5px", padding: "7px 11px", fontWeight: "800", cursor: "pointer" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "11px 14px", background: "#f8fafc", color: "#475569", fontSize: "12px", textTransform: "uppercase", borderBottom: "1px solid #e2e8f0" };
const tdStyle = { padding: "12px 14px", borderBottom: "1px solid #edf2f7", color: "#334155", fontSize: "13px" };
const amountTd = { ...tdStyle, color: "#0f2963", fontWeight: "900", whiteSpace: "nowrap" };
const strongTd = { ...tdStyle, color: "#0f172a", fontWeight: "900" };
const emptyTd = { ...tdStyle, textAlign: "center", color: "#94a3b8" };
const alertBox = { background: "#fff7ed", border: "1px solid #fb923c", color: "#9a3412", padding: "14px", borderRadius: "8px", marginBottom: "18px" };
const alertList = { display: "flex", gap: "8px", flexWrap: "wrap", marginTop: "10px" };
const alertPill = { background: "#ffedd5", border: "1px solid #fdba74", padding: "6px 9px", borderRadius: "999px", fontSize: "13px", fontWeight: "bold", color: "#9a3412", cursor: "pointer" };

function profitAmountStyle(type) {
  const color = type === "minus" ? "#b91c1c" : type === "final" ? "#0f5132" : "#0f2963";
  return { ...amountTd, color, fontSize: type === "final" ? "15px" : "13px" };
}

export default Dashboard;
