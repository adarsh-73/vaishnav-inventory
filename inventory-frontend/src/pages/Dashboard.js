import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiRequest, formatInvoiceCategory, getInvoiceCategoryTotals, getInvoiceProfit, isWashingEntry } from "../utils/api";
import { calculateReport, getCurrentMonthKey, isStockPurchaseExpense } from "../utils/reporting";

const DASHBOARD_CACHE_KEY = "vaishnav_dashboard_cache_v1";

function readDashboardCache() {
  try {
    const cached = JSON.parse(localStorage.getItem(DASHBOARD_CACHE_KEY) || "{}");
    return {
      products: Array.isArray(cached.products) ? cached.products : [],
      dailyBook: Array.isArray(cached.dailyBook) ? cached.dailyBook : [],
      invoices: Array.isArray(cached.invoices) ? cached.invoices : []
    };
  } catch {
    return { products: [], dailyBook: [], invoices: [] };
  }
}

function Dashboard() {
  const navigate = useNavigate();
  const [initialData] = useState(readDashboardCache);
  const [products, setProducts] = useState(initialData.products);
  const [dailyBook, setDailyBook] = useState(initialData.dailyBook);
  const [invoices, setInvoices] = useState(initialData.invoices);
  const [summary, setSummary] = useState(null);
  const [activeBreakdown, setActiveBreakdown] = useState("");

  const load = useCallback(async () => {
    const fallback = readDashboardCache();
    const summaryResult = await Promise.allSettled([
      apiRequest("/dashboard/summary", { timeoutMs: 8000 })
    ]);
    if (summaryResult[0].status === "fulfilled") {
      setSummary(summaryResult[0].value);
    }

    const results = await Promise.allSettled([
      apiRequest("/products/options", { timeoutMs: 5000 }),
      apiRequest("/daily-book/current-month", { timeoutMs: 5000 }),
      apiRequest("/invoices/recent?limit=20", { timeoutMs: 5000 })
    ]);
    const nextData = {
      products: results[0].status === "fulfilled" && Array.isArray(results[0].value) ? results[0].value : fallback.products,
      dailyBook: results[1].status === "fulfilled" && Array.isArray(results[1].value) ? results[1].value : fallback.dailyBook,
      invoices: results[2].status === "fulfilled" && Array.isArray(results[2].value) ? results[2].value : fallback.invoices
    };

    setProducts(nextData.products);
    setDailyBook(nextData.dailyBook);
    setInvoices(nextData.invoices);
    if (results.some((result) => result.status === "fulfilled")) {
      try {
        localStorage.setItem(DASHBOARD_CACHE_KEY, JSON.stringify(nextData));
      } catch {
        // Storage unavailable/full: fresh data is still shown for this session.
      }
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const currentMonthKey = getCurrentMonthKey();
  const report = useMemo(
    () => calculateReport({ invoices, dailyBook, monthKey: currentMonthKey }),
    [dailyBook, invoices, currentMonthKey]
  );
  const totals = { ...report.totals, ...(summary?.totals || {}) };

  const lowStockProducts = products.filter((product) =>
    Number(product.quantity || 0) <= Number(product.minimumStock || 1)
  );
  const totalProductCount = summary?.totalProducts ?? products.length;
  const lowStockCount = summary?.lowStockCount ?? lowStockProducts.length;
  const goToLowStock = (product) => {
    const params = new URLSearchParams({ lowStock: "1" });
    if (product?.id) params.set("productId", product.id);
    if (product?.productName) params.set("q", product.productName);
    navigate(`/products?${params.toString()}`);
  };
  const stockValue = summary?.stockValue ?? products.reduce((sum, product) => sum + Number(product.quantity || 0) * Number(product.sellPrice || 0), 0);
  const invoiceTotal = summary?.invoiceTotal ?? report.invoiceTotal;
  const washingProfit = summary ? Number(totals.washing || 0) : report.washingProfit;
  const stockPurchaseExpense = Number(totals.stockPurchaseExpense || 0);
  const grossProfit = summary?.grossProfit ?? report.grossProfit;
  const netProfit = summary?.netProfit ?? report.netProfit;
  const recentInvoices = report.invoices.slice().reverse().slice(0, 6);
  const recentEntries = report.dailyBook.slice().reverse().slice(0, 6);
  const breakdownConfig = getBreakdownConfig(activeBreakdown);
  const breakdownRows = useMemo(() => getDashboardBreakdownRows(activeBreakdown, report, products), [activeBreakdown, products, report]);
  const breakdownDailyTotals = useMemo(() => getDailyTotals(breakdownRows), [breakdownRows]);
  const unsoldRows = useMemo(() => getUnsoldStockRows(products, report.dailyBook, report.invoices), [products, report.dailyBook, report.invoices]);
  const openBreakdown = (key) => {
    setActiveBreakdown((current) => current === key ? "" : key);
    window.setTimeout(() => {
      document.getElementById("dashboard-breakdown")?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 80);
  };

  return (
    <div className="dashboard-page" style={pageStyle}>
      <section className="dashboard-hero" style={heroStyle}>
        <div style={brandBlock}>
          <VaishnavLogo />
          <div>
            <div style={eyebrowStyle}>Premium Detailing Studio</div>
            <h1 style={titleStyle}>Vaishnav Car Wash And Accessories</h1>
            <div style={subtitleStyle}>Current month: {report.monthLabel}. Old month statement alag se dekh sakte hain.</div>
          </div>
        </div>
        <div className="dashboard-hero-metric" style={heroMetric}>
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

      <div className="dashboard-card-grid" style={cardGrid}>
        <MetricCard label="Washing / Labour Profit" value={formatMoney(washingProfit)} accent="#0f766e" onClick={() => openBreakdown("washing")} />
        <MetricCard label="Accessories Sale" value={formatMoney(totals.accessories)} accent="#0f2963" onClick={() => openBreakdown("accessoriesSale")} />
        <MetricCard label="Accessories Profit" value={formatMoney(totals.accessoriesProfit)} accent="#15803d" onClick={() => openBreakdown("accessoriesProfit")} />
        <MetricCard label="Old Accessories Earning" value={formatMoney(totals.oldAccessoriesProfit)} accent="#7c2d12" onClick={() => openBreakdown("oldAccessories")} />
        <MetricCard label="Gross Profit" value={formatMoney(grossProfit)} accent="#166534" onClick={() => openBreakdown("grossProfit")} />
        <MetricCard label="Net Profit" value={formatMoney(netProfit)} accent="#0f5132" onClick={() => openBreakdown("netProfit")} />
        <MetricCard label="This Month Billing" value={formatMoney(invoiceTotal)} accent="#9c742a" onClick={() => openBreakdown("billing")} />
        <MetricCard label="Stock Value" value={formatMoney(stockValue)} accent="#334155" onClick={() => openBreakdown("stockValue")} />
        <MetricCard label="Total Products" value={totalProductCount} accent="#475569" onClick={() => navigate("/products")} />
        <MetricCard label="Low Stock" value={lowStockCount} accent="#c2410c" onClick={() => goToLowStock()} />
        <MetricCard label="Udhar Pending" value={formatMoney(totals.udhar)} accent="#b91c1c" onClick={() => navigate("/old-bills?udhar=1")} />
        <MetricCard label="Labour / Shop Expense" value={formatMoney(totals.expense)} accent="#7f1d1d" onClick={() => navigate("/daily-book?type=expense")} />
        <MetricCard label="Parts / Stock Purchase" value={formatMoney(stockPurchaseExpense)} accent="#6d4c1d" onClick={() => openBreakdown("stockPurchase")} />
      </div>

      <section className="dashboard-panel dashboard-panel-wide" style={widePanelStyle}>
        <div style={panelHeader}>
          <h2 style={panelTitle}>Bacha Hua / Check Stock</h2>
          <button type="button" onClick={() => navigate("/products?lowStock=1")} style={refreshBtn}>Products</button>
        </div>
        <div style={tableWrap}>
          <table style={detailTableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Item</th>
                <th style={thStyle}>Qty / Status</th>
                <th style={thStyle}>Source</th>
                <th style={thStyle}>Note</th>
                <th style={thStyle}>Value</th>
              </tr>
            </thead>
            <tbody>
              {unsoldRows.map((row) => (
                <tr key={row.id} onClick={() => row.productId ? navigate(`/products?productId=${row.productId}&q=${encodeURIComponent(row.item || "")}`) : navigate(row.target || "/daily-book?type=expense")} style={clickableRow}>
                  <td style={tdStyle}>{row.item}</td>
                  <td style={tdStyle}>{row.status}</td>
                  <td style={tdStyle}>{row.source}</td>
                  <td style={tdStyle}>{row.note}</td>
                  <td style={amountTd}>{row.value ? formatMoney(row.value) : "-"}</td>
                </tr>
              ))}
              {unsoldRows.length === 0 && <tr><td style={emptyTd} colSpan="5">No unsold stock found</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {activeBreakdown && (
        <section id="dashboard-breakdown" className="dashboard-panel dashboard-panel-wide" style={widePanelStyle}>
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
        <section className="dashboard-panel" style={panelStyle}>
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

        <section className="dashboard-panel" style={panelStyle}>
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
      className="dashboard-metric-card"
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
    udhar: { title: "Udhar Pending Breakdown", totalTitle: "Daily Udhar" },
    expense: { title: "Paid Daily Expense Breakdown", totalTitle: "Daily Expense" },
    stockPurchase: { title: "Stock Purchase Breakdown", totalTitle: "Daily Stock Purchase" }
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
  if (type === "udhar") return getUdharRows(report);
  if (type === "expense") return getExpenseRows(report);
  if (type === "stockPurchase") return getStockPurchaseRows(report);
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
    .filter((entry) => entry.entryType === "expense" && !isStockPurchaseExpense(entry))
    .map((entry) => ({
      id: `expense-${entry.id}`,
      date: formatDate(entry.entryDate || entry.createdDate),
      source: "Daily Book",
      party: entry.partyName || "-",
      note: entry.note || "Expense",
      amount: Number(entry.amount || 0),
      target: `/daily-book?type=expense&entryId=${entry.id}`
    }))
    .sort(sortRowsByDateDesc);
}

function getStockPurchaseRows(report) {
  return (report.dailyBook || [])
    .filter((entry) => entry.entryType === "expense" && isStockPurchaseExpense(entry))
    .map((entry) => ({
      id: `stock-purchase-${entry.id}`,
      date: formatDate(entry.entryDate || entry.createdDate),
      source: "Daily Book",
      party: entry.partyName || "-",
      note: entry.note || "Stock purchase",
      amount: Number(entry.amount || 0),
      target: `/daily-book?type=expense&entryId=${entry.id}`
    }))
    .sort(sortRowsByDateDesc);
}

function getUdharRows(report) {
  const invoiceNumbers = invoiceNumberSet(report);
  const invoiceRows = (report.invoices || [])
    .map((invoice) => {
      const amount = Number(invoice.remainingAmount || 0);
      if (amount <= 0) return null;
      return {
        id: `invoice-udhar-${invoice.id}`,
        invoiceId: invoice.id,
        date: formatDate(invoice.invoiceDate || invoice.createdDate),
        source: `Bill ${invoice.invoiceNumber || invoice.id}`,
        party: invoice.customer?.customerName || "Walk-in",
        note: formatInvoiceCategory(invoice),
        amount
      };
    })
    .filter(Boolean);

  const dailyRows = (report.dailyBook || [])
    .filter((entry) => entry.paymentStatus === "udhar" && !isInvoiceMirrorEntry(entry, invoiceNumbers))
    .map((entry) => ({
      id: `daily-udhar-${entry.id}`,
      date: formatDate(entry.entryDate || entry.createdDate),
      source: "Daily Book",
      party: entry.partyName || "-",
      note: entry.note || "Udhar",
      amount: Number(entry.amount || 0),
      target: `/daily-book?type=udhar&entryId=${entry.id}`
    }));

  return [...invoiceRows, ...dailyRows].sort(sortRowsByDateDesc);
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

function getUnsoldStockRows(products, dailyBook, invoices) {
  const stockRows = (products || [])
    .filter((product) => Number(product.quantity || 0) > 0)
    .map((product) => ({
      id: `stock-${product.id}`,
      productId: product.id,
      item: product.productName || "-",
      status: `Stock qty ${product.quantity || 0}`,
      source: "Products",
      note: product.productLocation ? `Location: ${product.productLocation}` : "Tracked stock",
      value: Number(product.quantity || 0) * Number(product.purchasePrice || 0),
      date: formatDate(product.updatedDate || product.createdDate)
    }));

  const productNames = new Set(
    (products || [])
      .map((product) => String(product.productName || "").trim().toLowerCase())
      .filter(Boolean)
  );
  const soldIndex = buildSoldItemIndex(invoices);

  const noteRows = (dailyBook || [])
    .filter((entry) => entry.entryType === "expense")
    .flatMap((entry) => parseStockLinesFromExpense(entry)
      .map((line) => applySoldEstimate(line, soldIndex))
      .filter((line) => line.remainingQty === null || line.remainingQty > 0)
      .filter((line) => !productNames.has(line.item.toLowerCase()))
      .map((line, index) => ({
        id: `expense-stock-${entry.id}-${index}`,
        item: line.item,
        status: line.remainingQty === null
          ? "Expense note me hai, Products stock me add/check karo"
          : `Possible bacha qty ${line.remainingQty}`,
        source: `Daily Book #${entry.id}`,
        note: line.raw,
        value: line.value,
        date: formatDate(entry.entryDate || entry.createdDate),
        target: `/daily-book?type=expense&entryId=${entry.id}`
      })));

  return [...stockRows, ...noteRows]
    .sort((a, b) => new Date(b.date || 0) - new Date(a.date || 0))
    .slice(0, 18);
}

function parseStockLinesFromExpense(entry) {
  const note = String(entry.note || "");
  if (!note) return [];
  const keywords = /(led|indicator|parking|seat cover|seat|bulb|light|air filter|door visor|ambient|mirror|lock|mud flap)/i;
  return note
    .split(/[,|]/)
    .map((part) => part.trim())
    .filter((part) => keywords.test(part))
    .map((part) => {
      const cleaned = part
        .replace(/\b\d+\s*(rs|rupees?)\b/ig, "")
        .replace(/\b\d+\s*pcs?\b/ig, "")
        .replace(/\b\d+\s*set\b/ig, "")
        .replace(/\s+/g, " ")
        .trim();
      const qtyMatch = part.match(/\b(\d+)\s*(pcs?|piece|set)\b/i);
      const priceMatch = part.match(/\b(\d+)\s*(rs|rupees?)\b/i);
      return {
        item: cleaned || part,
        matchKey: stockMatchKey(cleaned || part),
        raw: part,
        value: priceMatch ? Number(priceMatch[1]) : 0,
        qty: qtyMatch ? Number(qtyMatch[1]) : null
      };
    });
}

function buildSoldItemIndex(invoices) {
  const sold = new Map();
  (invoices || []).forEach((invoice) => {
    (invoice.invoiceItems || invoice.items || []).forEach((item) => {
      if (getInvoiceCategoryTotals({ invoiceItems: [item] }).accessories <= 0) return;
      const name = item.description || item.productInvoiceitem?.productName || item.productName || "";
      const key = stockMatchKey(name);
      if (!key) return;
      sold.set(key, (sold.get(key) || 0) + Number(item.quantity || 0));
    });
  });
  return sold;
}

function applySoldEstimate(line, soldIndex) {
  const key = line.matchKey;
  if (!key) return { ...line, remainingQty: line.qty };
  const soldQty = soldIndex.get(key) || 0;
  if (!line.qty) {
    return soldQty > 0 ? { ...line, remainingQty: 0 } : { ...line, remainingQty: null };
  }
  return { ...line, remainingQty: Math.max(0, Number(line.qty || 0) - soldQty) };
}

function stockMatchKey(value) {
  const text = String(value || "").toLowerCase();
  if (!text) return "";
  if (text.includes("ambient")) return "ambient-light";
  if (text.includes("seat cover")) return "seat-cover";
  if (text.includes("parking")) return "parking-light";
  if (text.includes("indicator")) return text.includes("fender") ? "fender-indicator" : "indicator";
  if (text.includes("air filter")) return "air-filter";
  if (text.includes("led") || text.includes("lead")) return "led-light";
  if (text.includes("bulb")) return "bulb";
  if (text.includes("door visor")) return "door-visor";
  if (text.includes("mirror")) return "mirror";
  if (text.includes("lock")) return "lock";
  if (text.includes("mud flap")) return "mud-flap";
  if (text.includes("light")) return "light";
  return text.replace(/[^a-z0-9]+/g, " ").trim();
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

const pageStyle = { padding: "28px", background: "transparent", minHeight: "100vh", color: "#0f172a" };
const heroStyle = { background: "radial-gradient(circle at 12% 8%, rgba(249,217,137,0.22), transparent 30%), linear-gradient(135deg, #06122c 0%, #0f2963 58%, #12233f 100%)", color: "#ffffff", borderRadius: "24px", padding: "26px", display: "flex", justifyContent: "space-between", alignItems: "center", gap: "18px", boxShadow: "0 24px 60px rgba(15, 41, 99, 0.26)", marginBottom: "22px", border: "1px solid rgba(249,217,137,0.22)", position: "relative", overflow: "hidden" };
const brandBlock = { display: "flex", alignItems: "center", gap: "18px" };
const eyebrowStyle = { color: "#f9d989", fontSize: "12px", fontWeight: "800", textTransform: "uppercase", letterSpacing: "1px", marginBottom: "5px" };
const titleStyle = { margin: 0, color: "#ffffff", fontSize: "30px", fontWeight: "900" };
const subtitleStyle = { marginTop: "7px", color: "#cbd5e1", fontSize: "14px" };
const logoShell = { width: "104px", minWidth: "104px", height: "116px", position: "relative", display: "flex", alignItems: "center", justifyContent: "center" };
const logoRing = { width: "86px", height: "86px", borderRadius: "50%", background: "linear-gradient(135deg, #c49a45, #f9d989 45%, #9c742a)", padding: "5px", boxShadow: "0 10px 22px rgba(0,0,0,0.22)" };
const logoLetter = { width: "100%", height: "100%", borderRadius: "50%", background: "radial-gradient(circle at 35% 25%, #203a72, #071635 70%)", border: "2px solid rgba(249,217,137,0.65)", display: "flex", alignItems: "center", justifyContent: "center", color: "#f9d989", fontFamily: "Georgia, serif", fontSize: "48px", fontWeight: "900" };
const logoRibbon = { position: "absolute", bottom: "7px", background: "#f9d989", color: "#071635", borderRadius: "4px", padding: "4px 10px", fontSize: "10px", fontWeight: "900", letterSpacing: "1px", boxShadow: "0 6px 14px rgba(0,0,0,0.18)" };
const heroMetric = { minWidth: "220px", background: "rgba(255,255,255,0.12)", border: "1px solid rgba(249,217,137,0.45)", borderRadius: "18px", padding: "18px", textAlign: "right", boxShadow: "inset 0 1px 0 rgba(255,255,255,0.12)" };
const heroMetricLabel = { display: "block", color: "#cbd5e1", fontSize: "12px", fontWeight: "700", marginBottom: "7px" };
const heroMetricValue = { display: "block", color: "#f9d989", fontSize: "28px", fontWeight: "900" };
const statementBtn = { marginTop: "12px", border: "1px solid rgba(249,217,137,0.75)", background: "linear-gradient(135deg, #f9d989, #c49a45)", color: "#071635", borderRadius: "999px", padding: "9px 13px", fontWeight: "900", cursor: "pointer", boxShadow: "0 10px 22px rgba(196,154,69,0.22)" };
const cardGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(210px, 1fr))", gap: "16px", marginBottom: "18px" };
const cardStyle = { background: "linear-gradient(180deg, rgba(255,255,255,0.98), rgba(248,250,252,0.96))", padding: "18px", borderRadius: "18px", borderTop: "4px solid #0f2963", boxShadow: "0 16px 36px rgba(15, 23, 42, 0.08)", minHeight: "104px", display: "flex", flexDirection: "column", justifyContent: "space-between", borderLeft: "1px solid rgba(226,232,240,0.8)", borderRight: "1px solid rgba(226,232,240,0.8)", borderBottom: "1px solid rgba(226,232,240,0.8)" };
const clickableCardStyle = { cursor: "pointer", outline: "none" };
const cardLabel = { color: "#64748b", fontSize: "13px", fontWeight: "800", textTransform: "uppercase" };
const cardValue = { fontSize: "26px", fontWeight: "900", marginTop: "12px" };
const panelGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(360px, 1fr))", gap: "18px" };
const panelStyle = { background: "rgba(255,255,255,0.96)", borderRadius: "20px", boxShadow: "0 16px 38px rgba(15, 23, 42, 0.08)", overflow: "hidden", border: "1px solid rgba(226,232,240,0.88)" };
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
const emptyTd = { ...tdStyle, textAlign: "center", color: "#94a3b8" };
const alertBox = { background: "#fff7ed", border: "1px solid #fb923c", color: "#9a3412", padding: "14px", borderRadius: "8px", marginBottom: "18px" };
const alertList = { display: "flex", gap: "8px", flexWrap: "wrap", marginTop: "10px" };
const alertPill = { background: "#ffedd5", border: "1px solid #fdba74", padding: "6px 9px", borderRadius: "999px", fontSize: "13px", fontWeight: "bold", color: "#9a3412", cursor: "pointer" };
const breakdownGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: "16px", padding: "16px" };
const miniPanel = { border: "1px solid #e2e8f0", borderRadius: "8px", overflow: "hidden", background: "#ffffff" };
const miniTitle = { margin: 0, padding: "12px 14px", borderBottom: "1px solid #e2e8f0", color: "#0f2963", fontSize: "15px" };
const clickableRow = { cursor: "pointer" };

export default Dashboard;
