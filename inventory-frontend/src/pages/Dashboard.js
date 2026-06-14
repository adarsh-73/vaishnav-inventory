import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiRequest, formatInvoiceCategory, getInvoiceCategoryTotals, getInvoiceProfit, isWashingEntry } from "../utils/api";
import { calculateReport, getCurrentMonthKey } from "../utils/reporting";

function Dashboard() {
  const navigate = useNavigate();
  const [products, setProducts] = useState([]);
  const [dailyBook, setDailyBook] = useState([]);
  const [invoices, setInvoices] = useState([]);
  const [activeBreakdown, setActiveBreakdown] = useState("");

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
    { label: "Old Accessories Earning", note: "Purane saman ki direct earning", amount: totals.oldAccessoriesProfit, type: "plus" },
    { label: "Gross Profit", note: "Washing + accessories profit", amount: grossProfit, type: "total" },
    { label: "Paid Daily Expense", note: "Sirf paid kharch minus hoga, udhar alag rahega", amount: totals.expense, type: "minus" },
    { label: "Net Profit", note: "Gross profit - expense", amount: netProfit, type: "final" }
  ];
  const goToUdhar = () => navigate("/old-bills?udhar=1");
  const recentInvoices = report.invoices.slice().reverse().slice(0, 6);
  const recentEntries = report.dailyBook.slice().reverse().slice(0, 6);
  const breakdownConfig = getBreakdownConfig(activeBreakdown);
  const breakdownRows = useMemo(() => getDashboardBreakdownRows(activeBreakdown, report, products), [activeBreakdown, products, report]);
  const breakdownDailyTotals = useMemo(() => getDailyTotals(breakdownRows), [breakdownRows]);
  const openBreakdown = (key) => setActiveBreakdown((current) => current === key ? "" : key);

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
        <MetricCard label="Washing / Labour Profit" value={formatMoney(washingProfit)} accent="#0f766e" onClick={() => openBreakdown("washing")} />
        <MetricCard label="Accessories Sale" value={formatMoney(totals.accessories)} accent="#0f2963" onClick={() => openBreakdown("accessoriesSale")} />
        <MetricCard label="Accessories Profit" value={formatMoney(totals.accessoriesProfit)} accent="#15803d" onClick={() => openBreakdown("accessoriesProfit")} />
        <MetricCard label="Old Accessories Earning" value={formatMoney(totals.oldAccessoriesProfit)} accent="#7c2d12" onClick={() => openBreakdown("oldAccessories")} />
        <MetricCard label="Gross Profit" value={formatMoney(grossProfit)} accent="#166534" onClick={() => openBreakdown("grossProfit")} />
        <MetricCard label="Net Profit" value={formatMoney(netProfit)} accent="#0f5132" onClick={() => openBreakdown("netProfit")} />
        <MetricCard label="This Month Billing" value={formatMoney(invoiceTotal)} accent="#9c742a" onClick={() => openBreakdown("billing")} />
        <MetricCard label="Stock Value" value={formatMoney(stockValue)} accent="#334155" onClick={() => openBreakdown("stockValue")} />
        <MetricCard label="Total Products" value={products.length} accent="#475569" onClick={() => navigate("/products")} />
        <MetricCard label="Low Stock" value={lowStockProducts.length} accent="#c2410c" onClick={() => goToLowStock()} />
        <MetricCard label="Udhar Pending" value={formatMoney(totals.udhar)} accent="#b91c1c" onClick={goToUdhar} />
        <MetricCard label="Paid Daily Expense" value={formatMoney(totals.expense)} accent="#7f1d1d" onClick={() => openBreakdown("expense")} />
      </div>

      {activeBreakdown && (
        <section style={widePanelStyle}>
          <div style={panelHeader}>
            <h2 style={panelTitle}>{breakdownConfig.title}</h2>
            <button type="button" onClick={() => setActiveBreakdown("")} style={refreshBtn}>Close</button>
          </div>
          <div style={breakdownGrid}>
            <div style={miniPanel}>
              <h3 style={miniTitle}>{breakdownConfig.totalTitle}</h3>
              <table style={tableStyle}>
                <thead>
                  <tr>
                    <th style={thStyle}>Date</th>
                    <th style={thStyle}>Entries</th>
                    <th style={thStyle}>Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {breakdownDailyTotals.map((row) => (
                    <tr key={row.date}>
                      <td style={tdStyle}>{row.date}</td>
                      <td style={tdStyle}>{row.count}</td>
                      <td style={amountTd}>{formatMoney(row.amount)}</td>
                    </tr>
                  ))}
                  {breakdownDailyTotals.length === 0 && <tr><td style={emptyTd} colSpan="3">No entries</td></tr>}
                </tbody>
              </table>
            </div>
            <div style={miniPanel}>
              <h3 style={miniTitle}>Entry Wise Detail</h3>
              <div style={tableWrap}>
                <table style={detailTableStyle}>
                  <thead>
                    <tr>
                      <th style={thStyle}>Date</th>
                      <th style={thStyle}>Source</th>
                      <th style={thStyle}>Customer / Party</th>
                      <th style={thStyle}>Note</th>
                      <th style={thStyle}>Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {breakdownRows.map((row) => (
                      <tr
                        key={row.id}
                        onClick={() => row.invoiceId ? navigate(`/billing?invoiceId=${row.invoiceId}`) : row.productId ? navigate(`/products?productId=${row.productId}&q=${encodeURIComponent(row.party || "")}`) : navigate(row.target || "/daily-book")}
                        style={clickableRow}
                      >
                        <td style={tdStyle}>{row.date}</td>
                        <td style={tdStyle}>{row.source}</td>
                        <td style={tdStyle}>{row.party}</td>
                        <td style={tdStyle}>{row.note}</td>
                        <td style={amountTd}>{formatMoney(row.amount)}</td>
                      </tr>
                    ))}
                    {breakdownRows.length === 0 && <tr><td style={emptyTd} colSpan="5">No entries</td></tr>}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </section>
      )}

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

function formatDate(value) {
  return String(value || "").slice(0, 10) || "-";
}

function getBreakdownConfig(type) {
  const configs = {
    washing: { title: "Washing / Labour Breakdown", totalTitle: "Daily Total" },
    accessoriesSale: { title: "Accessories Sale Breakdown", totalTitle: "Daily Sale" },
    accessoriesProfit: { title: "Accessories Profit Breakdown", totalTitle: "Daily Profit" },
    oldAccessories: { title: "Old Accessories Earning Breakdown", totalTitle: "Daily Old Accessories" },
    grossProfit: { title: "Gross Profit Breakdown", totalTitle: "Daily Gross Profit" },
    netProfit: { title: "Net Profit Breakdown", totalTitle: "Daily Net Profit" },
    billing: { title: "This Month Billing Breakdown", totalTitle: "Daily Billing" },
    stockValue: { title: "Stock Value Breakdown", totalTitle: "Stock By Date" },
    expense: { title: "Paid Daily Expense Breakdown", totalTitle: "Daily Expense" }
  };
  return configs[type] || { title: "Dashboard Breakdown", totalTitle: "Daily Total" };
}

function getDashboardBreakdownRows(type, report, products) {
  if (!type) return [];
  if (type === "washing") return getWashingBreakdownRows(report);
  if (type === "accessoriesSale") return getAccessoriesRows(report, "sale");
  if (type === "accessoriesProfit") return getAccessoriesRows(report, "profit");
  if (type === "oldAccessories") return getOldAccessoriesRows(report);
  if (type === "grossProfit") return [...getWashingBreakdownRows(report), ...getAccessoriesRows(report, "profit"), ...getOldAccessoriesRows(report)].sort(sortRowsByDateDesc);
  if (type === "netProfit") {
    return [
      ...getWashingBreakdownRows(report),
      ...getAccessoriesRows(report, "profit"),
      ...getOldAccessoriesRows(report),
      ...getExpenseRows(report).map((row) => ({ ...row, amount: -Math.abs(row.amount), source: "Expense" }))
    ].sort(sortRowsByDateDesc);
  }
  if (type === "billing") return getBillingRows(report);
  if (type === "stockValue") return getStockRows(products, true);
  if (type === "expense") return getExpenseRows(report);
  return [];
}

function getOldAccessoriesRows(report) {
  return (report.invoices || [])
    .map((invoice) => {
      const split = getInvoiceCategoryTotals(invoice);
      const amount = Number(split.oldAccessoriesProfit || 0);
      if (amount <= 0) return null;
      const items = (invoice.invoiceItems || invoice.items || [])
        .filter((item) => getInvoiceCategoryTotals({ invoiceItems: [item] }).oldAccessories > 0)
        .map((item) => item.description || item.productInvoiceitem?.productName || item.productName)
        .filter(Boolean)
        .join(", ");
      return {
        id: `old-accessories-${invoice.id}`,
        invoiceId: invoice.id,
        date: formatDate(invoice.invoiceDate || invoice.createdDate),
        source: `Bill ${invoice.invoiceNumber || invoice.id}`,
        party: invoice.customer?.customerName || "Walk-in",
        note: items || "Old Accessories",
        amount
      };
    })
    .filter(Boolean)
    .sort(sortRowsByDateDesc);
}

function invoiceNumberSet(report) {
  return new Set(
    (report.invoices || [])
      .map((invoice) => invoice.invoiceNumber)
      .filter(Boolean)
      .map((invoiceNumber) => String(invoiceNumber).trim().toLowerCase())
  );
}

function isInvoiceMirrorEntry(entry, invoiceNumbers) {
  const note = String(entry.note || "").toLowerCase();
  const isInvoiceEntry = note.includes("invoice ");
  return isInvoiceEntry && Array.from(invoiceNumbers).some((invoiceNumber) => note.includes(invoiceNumber));
}

function getWashingBreakdownRows(report) {
  const invoiceNumbers = invoiceNumberSet(report);
  const invoiceRows = (report.invoices || [])
    .map((invoice) => {
      const split = getInvoiceCategoryTotals(invoice);
      if (Number(split.serviceProfit || 0) <= 0) return null;
      const items = (invoice.invoiceItems || invoice.items || [])
        .map((item) => item.description || item.productInvoiceitem?.productName || item.productName)
        .filter(Boolean)
        .join(", ");
      return {
        id: `invoice-${invoice.id}`,
        invoiceId: invoice.id,
        date: formatDate(invoice.invoiceDate || invoice.createdDate),
        source: `Bill ${invoice.invoiceNumber || invoice.id}`,
        party: invoice.customer?.customerName || "Walk-in",
        note: items || formatInvoiceCategory(invoice),
        amount: Number(split.serviceProfit || 0)
      };
    })
    .filter(Boolean);

  const dailyRows = (report.dailyBook || [])
    .filter((entry) => {
      const note = String(entry.note || "").toLowerCase();
      const isInvoiceEntry = note.includes("invoice ");
      const isInvoiceMirror = isInvoiceMirrorEntry(entry, invoiceNumbers);
      return entry.entryType === "income" && entry.paymentStatus !== "udhar" && !isInvoiceMirror && !isInvoiceEntry && isWashingEntry(entry);
    })
    .map((entry) => ({
      id: `daily-${entry.id}`,
      date: formatDate(entry.entryDate || entry.createdDate),
      source: "Daily Book",
      party: entry.partyName || "-",
      note: entry.note || entry.incomeCategory || "Washing / Labour",
      amount: Number(entry.amount || 0)
    }));

  return [...invoiceRows, ...dailyRows].sort((a, b) => new Date(b.date || 0) - new Date(a.date || 0));
}

function getAccessoriesRows(report, mode) {
  const invoiceNumbers = invoiceNumberSet(report);
  const invoiceRows = (report.invoices || [])
    .map((invoice) => {
      const split = getInvoiceCategoryTotals(invoice);
      const amount = mode === "profit" ? Number(split.accessoriesProfit || 0) : Number(split.accessories || 0);
      if (amount <= 0) return null;
      const items = (invoice.invoiceItems || invoice.items || [])
        .filter((item) => getInvoiceCategoryTotals({ invoiceItems: [item] }).accessories > 0)
        .map((item) => item.description || item.productInvoiceitem?.productName || item.productName)
        .filter(Boolean)
        .join(", ");
      return {
        id: `accessories-${mode}-${invoice.id}`,
        invoiceId: invoice.id,
        date: formatDate(invoice.invoiceDate || invoice.createdDate),
        source: `Bill ${invoice.invoiceNumber || invoice.id}`,
        party: invoice.customer?.customerName || "Walk-in",
        note: items || "Accessories",
        amount
      };
    })
    .filter(Boolean);

  const dailyRows = (report.dailyBook || [])
    .filter((entry) => {
      const note = String(entry.note || "").toLowerCase();
      const isInvoiceEntry = note.includes("invoice ");
      return entry.entryType === "income" && entry.paymentStatus !== "udhar" && !isWashingEntry(entry) && !isInvoiceEntry && !isInvoiceMirrorEntry(entry, invoiceNumbers);
    })
    .map((entry) => ({
      id: `daily-accessories-${mode}-${entry.id}`,
      date: formatDate(entry.entryDate || entry.createdDate),
      source: "Daily Book",
      party: entry.partyName || "-",
      note: entry.note || entry.incomeCategory || "Accessories",
      amount: Number(entry.amount || 0)
    }));

  return [...invoiceRows, ...dailyRows].sort(sortRowsByDateDesc);
}

function getBillingRows(report) {
  return (report.invoices || [])
    .map((invoice) => ({
      id: `billing-${invoice.id}`,
      invoiceId: invoice.id,
      date: formatDate(invoice.invoiceDate || invoice.createdDate),
      source: `Bill ${invoice.invoiceNumber || invoice.id}`,
      party: invoice.customer?.customerName || "Walk-in",
      note: formatInvoiceCategory(invoice),
      amount: Number(invoice.totalAmount || 0)
    }))
    .sort(sortRowsByDateDesc);
}

function getExpenseRows(report) {
  return (report.dailyBook || [])
    .filter((entry) => entry.entryType === "expense")
    .map((entry) => ({
      id: `expense-${entry.id}`,
      date: formatDate(entry.entryDate || entry.createdDate),
      source: "Daily Book",
      party: entry.partyName || "-",
      note: entry.note || "Expense",
      amount: Number(entry.amount || 0)
    }))
    .sort(sortRowsByDateDesc);
}

function getStockRows(products, useValue) {
  return (products || [])
    .map((product) => ({
      id: `product-${useValue ? "value" : "count"}-${product.id}`,
      productId: product.id,
      date: formatDate(product.createdDate || product.updatedDate),
      source: useValue ? "Stock Value" : "Product",
      party: product.productName || "-",
      note: `Qty ${product.quantity || 0} | Buy ${formatMoney(product.purchasePrice || 0)} | Sell ${formatMoney(product.sellPrice || 0)}`,
      amount: useValue ? Number(product.quantity || 0) * Number(product.sellPrice || 0) : Number(product.quantity || 0)
    }))
    .sort(sortRowsByDateDesc);
}

function sortRowsByDateDesc(a, b) {
  return new Date(b.date || 0) - new Date(a.date || 0);
}

function getDailyTotals(rows) {
  const byDate = new Map();
  rows.forEach((row) => {
    const current = byDate.get(row.date) || { date: row.date, count: 0, amount: 0 };
    current.count += 1;
    current.amount += Number(row.amount || 0);
    byDate.set(row.date, current);
  });
  return Array.from(byDate.values()).sort((a, b) => new Date(b.date || 0) - new Date(a.date || 0));
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
const widePanelStyle = { ...panelStyle, marginBottom: "18px" };
const panelHeader = { padding: "15px 18px", borderBottom: "1px solid #e2e8f0", display: "flex", justifyContent: "space-between", alignItems: "center" };
const panelTitle = { margin: 0, fontSize: "17px", color: "#0f2963" };
const refreshBtn = { border: "1px solid #0f2963", background: "#ffffff", color: "#0f2963", borderRadius: "5px", padding: "7px 11px", fontWeight: "800", cursor: "pointer" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const tableWrap = { overflowX: "auto" };
const detailTableStyle = { ...tableStyle, minWidth: "760px" };
const thStyle = { textAlign: "left", padding: "11px 14px", background: "#f8fafc", color: "#475569", fontSize: "12px", textTransform: "uppercase", borderBottom: "1px solid #e2e8f0" };
const tdStyle = { padding: "12px 14px", borderBottom: "1px solid #edf2f7", color: "#334155", fontSize: "13px" };
const amountTd = { ...tdStyle, color: "#0f2963", fontWeight: "900", whiteSpace: "nowrap" };
const strongTd = { ...tdStyle, color: "#0f172a", fontWeight: "900" };
const emptyTd = { ...tdStyle, textAlign: "center", color: "#94a3b8" };
const alertBox = { background: "#fff7ed", border: "1px solid #fb923c", color: "#9a3412", padding: "14px", borderRadius: "8px", marginBottom: "18px" };
const alertList = { display: "flex", gap: "8px", flexWrap: "wrap", marginTop: "10px" };
const alertPill = { background: "#ffedd5", border: "1px solid #fdba74", padding: "6px 9px", borderRadius: "999px", fontSize: "13px", fontWeight: "bold", color: "#9a3412", cursor: "pointer" };
const breakdownGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: "16px", padding: "16px" };
const miniPanel = { border: "1px solid #e2e8f0", borderRadius: "8px", overflow: "hidden", background: "#ffffff" };
const miniTitle = { margin: 0, padding: "12px 14px", borderBottom: "1px solid #e2e8f0", color: "#0f2963", fontSize: "15px" };
const clickableRow = { cursor: "pointer" };

function profitAmountStyle(type) {
  const color = type === "minus" ? "#b91c1c" : type === "final" ? "#0f5132" : "#0f2963";
  return { ...amountTd, color, fontSize: type === "final" ? "15px" : "13px" };
}

export default Dashboard;
