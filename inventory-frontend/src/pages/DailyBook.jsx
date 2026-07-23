import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { todayDate } from "../utils/storage";
import { apiRequest, isWashingEntry } from "../utils/api";
import { isStockPurchaseExpense } from "../utils/reporting";

function DailyBook() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [entries, setEntries] = useState([]);
  const [invoices, setInvoices] = useState([]);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState({ entryDate: todayDate(), entryType: "expense", incomeCategory: "service", partyName: "", note: "", amount: "", paymentStatus: "paid" });
  const activeType = searchParams.get("type") || "";
  const activeBucket = searchParams.get("bucket") || "";
  const selectedEntryId = searchParams.get("entryId") || "";

  const loadEntries = useCallback(async (loadAll = false) => {
    try {
      const dailyPath = activeType === "udhar"
        ? "/daily-book/udhar?size=300"
        : loadAll
          ? "/daily-book?size=300"
          : "/daily-book/current-month";
      const [dailyData, invoiceData] = await Promise.all([
        apiRequest(dailyPath),
        apiRequest("/invoices/pending-udhar?size=300", { timeoutMs: 8000 }).catch(() => [])
      ]);
      setEntries(Array.isArray(dailyData) ? dailyData : []);
      setInvoices(Array.isArray(invoiceData) ? invoiceData : []);
    } catch {
      const [dailyData, invoiceData] = await Promise.all([
        apiRequest("/daily-book?size=100"),
        apiRequest("/invoices/pending-udhar?size=100", { timeoutMs: 8000 }).catch(() => [])
      ]);
      setEntries(Array.isArray(dailyData) ? dailyData : []);
      setInvoices(Array.isArray(invoiceData) ? invoiceData : []);
    }
  }, [activeType]);

  useEffect(() => {
    loadEntries(activeType === "udhar");
  }, [activeType, loadEntries]);

  useEffect(() => {
    if (!entries.length) return;
    const targetId = selectedEntryId ? `daily-entry-${selectedEntryId}` : activeType ? "daily-book-entries" : "";
    if (!targetId) return;

    window.setTimeout(() => {
      document.getElementById(targetId)?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 60);
  }, [activeType, entries.length, selectedEntryId]);

  const invoiceNumbers = useMemo(() => new Set(
    invoices
      .map((invoice) => invoice.invoiceNumber)
      .filter(Boolean)
      .map((invoiceNumber) => String(invoiceNumber).trim().toLowerCase())
  ), [invoices]);

  const invoiceUdharTotal = useMemo(() => invoices.reduce((sum, invoice) => (
    sum + Number(invoice.remainingAmount || 0)
  ), 0), [invoices]);

  const accountEntries = useMemo(() => entries.filter((entry) => (
    !(entry.paymentStatus === "udhar" && isInvoiceMirrorEntry(entry, invoiceNumbers))
  )), [entries, invoiceNumbers]);

  const totals = useMemo(() => accountEntries.reduce((sum, entry) => {
    if (entry.entryType === "income" && isWashingEntry(entry)) sum.washing += Number(entry.amount || 0);
    if (entry.entryType === "income" && !isWashingEntry(entry)) sum.accessories += Number(entry.amount || 0);
    if (entry.entryType === "expense") {
      if (isStockPurchaseExpense(entry)) sum.stockPurchase += Number(entry.amount || 0);
      else sum.expense += Number(entry.amount || 0);
    }
    if (entry.paymentStatus === "udhar") sum.udhar += Number(entry.amount || 0);
    return sum;
  }, { washing: 0, accessories: 0, expense: 0, stockPurchase: 0, udhar: invoiceUdharTotal }), [accountEntries, invoiceUdharTotal]);

  const visibleEntries = useMemo(() => {
    const rows = activeType === "udhar" ? entries : accountEntries;
    if (activeType === "expense") {
      if (activeBucket === "stock-purchase") return rows.filter((entry) => entry.entryType === "expense" && isStockPurchaseExpense(entry));
      return rows.filter((entry) => entry.entryType === "expense" && !isStockPurchaseExpense(entry));
    }
    if (activeType === "washing") return rows.filter((entry) => entry.entryType === "income" && isWashingEntry(entry));
    if (activeType === "accessories") return rows.filter((entry) => entry.entryType === "income" && !isWashingEntry(entry));
    if (activeType === "income") return rows.filter((entry) => entry.entryType === "income");
    if (activeType === "udhar") return rows.filter((entry) => entry.paymentStatus === "udhar");
    return rows;
  }, [accountEntries, activeBucket, activeType, entries]);

  const visibleTotal = useMemo(() => {
    if (activeType === "udhar") return totals.udhar;
    return visibleEntries.reduce((sum, entry) => sum + Number(entry.amount || 0), 0);
  }, [activeType, totals.udhar, visibleEntries]);

  const showEntries = (type, bucket = "") => {
    const params = {};
    if (type) params.type = type;
    if (bucket) params.bucket = bucket;
    setSearchParams(params);
    window.setTimeout(() => {
      document.getElementById("daily-book-entries")?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 60);
  };

  const activeLabel = getActiveLabel(activeType, activeBucket);

  const resetForm = () => {
    setEditingId(null);
    setForm({ entryDate: todayDate(), entryType: "expense", incomeCategory: "service", partyName: "", note: "", amount: "", paymentStatus: "paid" });
  };

  const handleEntryTypeChange = (entryType) => {
    setForm({
      ...form,
      entryType,
      incomeCategory: entryType === "expense" ? "service" : "accessories"
    });
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!form.partyName || !form.amount) return alert("Name aur amount bharna zaroori hai.");
    const payload = { ...form, amount: Number(form.amount) };
    try {
      await apiRequest(editingId ? `/daily-book/${editingId}` : "/daily-book", {
      method: editingId ? "PUT" : "POST",
      body: JSON.stringify(payload)
      });
      resetForm();
      loadEntries(activeType === "udhar");
    } catch (error) {
      alert(`Daily book save nahi hua: ${error.message}`);
    }
  };

  const editEntry = (entry) => {
    setEditingId(entry.id);
    setForm({
      entryDate: entry.entryDate || todayDate(),
      entryType: entry.entryType || "expense",
      incomeCategory: entry.incomeCategory || "service",
      partyName: entry.partyName || "",
      note: entry.note || "",
      amount: entry.amount ?? "",
      paymentStatus: entry.paymentStatus || "paid"
    });
  };

  const markPaid = async (entry) => {
    const invoiceNumber = String(entry.note || "").match(/V-\d+/i)?.[0];
    if (invoiceNumber) {
      try {
        const invoice = invoices.find((item) =>
          String(item.invoiceNumber || "").toLowerCase() === invoiceNumber.toLowerCase()
        );
        if (invoice?.id) {
          await apiRequest(`/invoices/${invoice.id}/mark-paid`, { method: "PUT" });
        }
      } catch {
        // Daily book row still gets marked paid below, even if the invoice lookup fails.
      }
    }
    await apiRequest(`/daily-book/${entry.id}`, {
      method: "PUT",
      body: JSON.stringify({ ...entry, paymentStatus: "paid", note: `${entry.note || ""} (Udhar paid)` })
    });
    loadEntries(activeType === "udhar");
  };

  return (
    <div style={pageStyle}>
      <h1 style={titleStyle}>Daily Book</h1>
      <div style={summaryRow}>
        <button type="button" onClick={() => showEntries("washing")} style={summaryButtonCard("#0f766e", activeType === "washing")}><span>Washing Income</span><strong>Rs. {totals.washing}</strong></button>
        <button type="button" onClick={() => showEntries("accessories")} style={summaryButtonCard("#0f2963", activeType === "accessories")}><span>Accessories Income</span><strong>Rs. {totals.accessories}</strong></button>
        <button type="button" onClick={() => showEntries("expense", "shop-expense")} style={summaryButtonCard("#7f1d1d", activeType === "expense" && activeBucket !== "stock-purchase")}><span>Labour / Shop Expense</span><strong>Rs. {totals.expense}</strong></button>
        <button type="button" onClick={() => showEntries("expense", "stock-purchase")} style={summaryButtonCard("#6d4c1d", activeBucket === "stock-purchase")}><span>Parts / Stock Purchase</span><strong>Rs. {totals.stockPurchase}</strong></button>
        <button type="button" onClick={() => showEntries("udhar")} style={summaryButtonCard("#b91c1c", activeType === "udhar")}><span>Udhar Pending</span><strong>Rs. {totals.udhar}</strong></button>
      </div>
      <form onSubmit={handleSubmit} style={panelStyle}>
        <input type="date" value={form.entryDate} onChange={(e) => setForm({ ...form, entryDate: e.target.value })} style={inputStyle} />
        <select value={form.entryType} onChange={(e) => handleEntryTypeChange(e.target.value)} style={inputStyle}>
          <option value="expense">Kharch</option>
          <option value="income">Income</option>
        </select>
        <select value={form.incomeCategory} onChange={(e) => setForm({ ...form, incomeCategory: e.target.value })} style={inputStyle}>
          {form.entryType === "expense" ? (
            <>
              <option value="service">Service / Labour Expense</option>
              <option value="parts">Parts / Stock Purchase</option>
              <option value="rent">Rent / Utility</option>
              <option value="salary">Salary</option>
              <option value="other">Other Shop Expense</option>
            </>
          ) : (
            <>
              <option value="accessories">Accessories</option>
              <option value="washing">Washing</option>
            </>
          )}
        </select>
        <select value={form.paymentStatus} onChange={(e) => setForm({ ...form, paymentStatus: e.target.value })} style={inputStyle}>
          <option value="paid">Paid</option>
          <option value="udhar">Udhar</option>
        </select>
        <input placeholder="Name / Party" value={form.partyName} onChange={(e) => setForm({ ...form, partyName: e.target.value })} style={inputStyle} />
        <input placeholder="Note" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} style={inputStyle} />
        <input type="number" placeholder="Amount" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} style={inputStyle} />
        <button style={primaryBtn}>{editingId ? "Update Entry" : "Add Entry"}</button>
        {editingId && <button type="button" onClick={resetForm} style={secondaryBtn}>Cancel</button>}
      </form>
      <div id="daily-book-entries" style={panelStyle}>
        {activeType && (
          <div style={filterBar}>
            <div>
              <strong>{activeLabel}</strong>
              <div style={shownTotalStyle}>Shown total: Rs. {visibleTotal.toLocaleString("en-IN")}</div>
            </div>
            <button type="button" onClick={() => setSearchParams({})} style={secondaryBtn}>Show All</button>
          </div>
        )}
        <table style={tableStyle}>
          <thead><tr><th style={thStyle}>Date</th><th style={thStyle}>Type</th><th style={thStyle}>Category</th><th style={thStyle}>Party</th><th style={thStyle}>Note</th><th style={thStyle}>Amount</th><th style={thStyle}>Action</th></tr></thead>
          <tbody>{visibleEntries.map((entry) => {
            const billLinked = entry.paymentStatus === "udhar" && isInvoiceMirrorEntry(entry, invoiceNumbers);
            const noteText = billLinked ? `${entry.note || "-"} | Bill se linked hai, total invoice se count hoga.` : entry.note;
            return (
            <tr key={entry.id} id={`daily-entry-${entry.id}`} style={String(entry.id) === selectedEntryId ? selectedRowStyle : undefined}>
              <td style={tdStyle}>{entry.entryDate}</td><td style={tdStyle}>{entry.entryType}</td><td style={tdStyle}>{entry.incomeCategory || "-"}</td><td style={tdStyle}>{entry.partyName}</td><td style={tdStyle}>{noteText}</td><td style={tdStyle}>Rs. {entry.amount}</td>
              <td style={tdStyle}><button onClick={() => editEntry(entry)} style={secondaryBtn}>Edit</button>{entry.paymentStatus === "udhar" && !billLinked && <button onClick={() => markPaid(entry)} style={smallBtn}>Mark Paid</button>}</td>
            </tr>
            );
          })}
          {visibleEntries.length === 0 && (
            <tr><td style={emptyTd} colSpan="7">No entries</td></tr>
          )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function isInvoiceMirrorEntry(entry, invoiceNumbers) {
  const note = String(entry.note || "").toLowerCase();
  if (!note.includes("invoice ")) return false;
  if (!invoiceNumbers.size) return /v-\d+/i.test(note);
  return Array.from(invoiceNumbers).some((invoiceNumber) => note.includes(invoiceNumber));
}

function getActiveLabel(type, bucket) {
  if (type === "expense" && bucket === "stock-purchase") return "Parts / stock purchase entries";
  if (type === "expense") return "Labour / shop expense entries";
  if (type === "washing") return "Washing income entries";
  if (type === "accessories") return "Accessories income entries";
  if (type === "udhar") return "Udhar entries";
  if (type === "income") return "Income entries";
  return "Entries";
}

const pageStyle = { padding: "30px", background: "#f1f5f9", minHeight: "100vh" };
const titleStyle = { margin: "0 0 20px", color: "#0f172a" };
const summaryRow = { display: "flex", gap: "15px", flexWrap: "wrap", marginBottom: "18px" };
const summaryCard = { background: "white", padding: "18px", borderRadius: "10px", minWidth: "180px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", display: "flex", flexDirection: "column", gap: "8px" };
const summaryButtonCard = (accent, active) => ({
  ...summaryCard,
  border: `1px solid ${active ? accent : "#e2e8f0"}`,
  color: accent,
  textAlign: "left",
  cursor: "pointer",
  outline: active ? `2px solid ${accent}` : "none",
  outlineOffset: "2px"
});
const panelStyle = { background: "white", padding: "18px", borderRadius: "10px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", marginBottom: "18px", overflowX: "auto" };
const filterBar = { display: "flex", justifyContent: "space-between", alignItems: "center", gap: "12px", marginBottom: "12px", color: "#7f1d1d" };
const shownTotalStyle = { marginTop: "4px", color: "#7f1d1d", fontSize: "13px", fontWeight: "800" };
const inputStyle = { padding: "11px", marginRight: "10px", marginBottom: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "160px" };
const primaryBtn = { padding: "11px 18px", background: "#0f2963", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const secondaryBtn = { padding: "8px 10px", background: "#ffffff", color: "#0f2963", border: "1px solid #0f2963", borderRadius: "5px", cursor: "pointer", marginRight: "6px" };
const smallBtn = { padding: "8px 10px", background: "#128c7e", color: "white", border: "none", borderRadius: "5px", cursor: "pointer" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white" };
const tdStyle = { padding: "10px", borderBottom: "1px solid #e2e8f0" };
const selectedRowStyle = { background: "#fff7ed", outline: "2px solid #fb923c" };
const emptyTd = { padding: "18px", textAlign: "center", color: "#64748b" };

export default DailyBook;
