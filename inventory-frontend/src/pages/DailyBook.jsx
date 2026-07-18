import React, { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { todayDate } from "../utils/storage";
import { apiRequest, isWashingEntry } from "../utils/api";

function DailyBook() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [entries, setEntries] = useState([]);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState({ entryDate: todayDate(), entryType: "expense", incomeCategory: "accessories", partyName: "", note: "", amount: "", paymentStatus: "paid" });
  const activeType = searchParams.get("type") || "";
  const selectedEntryId = searchParams.get("entryId") || "";

  const loadEntries = async () => {
    const data = await apiRequest("/daily-book");
    setEntries(Array.isArray(data) ? data : []);
  };

  useEffect(() => {
    loadEntries();
  }, []);

  useEffect(() => {
    if (!entries.length) return;
    const targetId = selectedEntryId ? `daily-entry-${selectedEntryId}` : activeType ? "daily-book-entries" : "";
    if (!targetId) return;

    window.setTimeout(() => {
      document.getElementById(targetId)?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 60);
  }, [activeType, entries.length, selectedEntryId]);

  const totals = useMemo(() => entries.reduce((sum, entry) => {
    if (entry.entryType === "income" && isWashingEntry(entry)) sum.washing += Number(entry.amount || 0);
    if (entry.entryType === "income" && !isWashingEntry(entry)) sum.accessories += Number(entry.amount || 0);
    if (entry.entryType === "expense") sum.expense += Number(entry.amount || 0);
    if (entry.paymentStatus === "udhar") sum.udhar += Number(entry.amount || 0);
    return sum;
  }, { washing: 0, accessories: 0, expense: 0, udhar: 0 }), [entries]);

  const visibleEntries = useMemo(() => {
    if (activeType === "expense") return entries.filter((entry) => entry.entryType === "expense");
    if (activeType === "washing") return entries.filter((entry) => entry.entryType === "income" && isWashingEntry(entry));
    if (activeType === "accessories") return entries.filter((entry) => entry.entryType === "income" && !isWashingEntry(entry));
    if (activeType === "income") return entries.filter((entry) => entry.entryType === "income");
    if (activeType === "udhar") return entries.filter((entry) => entry.paymentStatus === "udhar");
    return entries;
  }, [activeType, entries]);

  const showEntries = (type) => {
    setSearchParams({ type });
    window.setTimeout(() => {
      document.getElementById("daily-book-entries")?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 60);
  };

  const activeLabel = {
    washing: "Washing income entries",
    accessories: "Accessories income entries",
    expense: "Expense entries",
    udhar: "Udhar entries",
    income: "Income entries"
  }[activeType];

  const resetForm = () => {
    setEditingId(null);
    setForm({ entryDate: todayDate(), entryType: "expense", incomeCategory: "accessories", partyName: "", note: "", amount: "", paymentStatus: "paid" });
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
      loadEntries();
    } catch (error) {
      alert(`Daily book save nahi hua: ${error.message}`);
    }
  };

  const editEntry = (entry) => {
    setEditingId(entry.id);
    setForm({
      entryDate: entry.entryDate || todayDate(),
      entryType: entry.entryType || "expense",
      incomeCategory: entry.incomeCategory || "accessories",
      partyName: entry.partyName || "",
      note: entry.note || "",
      amount: entry.amount ?? "",
      paymentStatus: entry.paymentStatus || "paid"
    });
  };

  const markPaid = async (entry) => {
    await apiRequest(`/daily-book/${entry.id}`, {
      method: "PUT",
      body: JSON.stringify({ ...entry, paymentStatus: "paid", note: `${entry.note || ""} (Udhar paid)` })
    });
    loadEntries();
  };

  return (
    <div style={pageStyle}>
      <h1 style={titleStyle}>Daily Book</h1>
      <div style={summaryRow}>
        <button type="button" onClick={() => showEntries("washing")} style={summaryButtonCard("#0f766e", activeType === "washing")}><span>Washing Income</span><strong>Rs. {totals.washing}</strong></button>
        <button type="button" onClick={() => showEntries("accessories")} style={summaryButtonCard("#0f2963", activeType === "accessories")}><span>Accessories Income</span><strong>Rs. {totals.accessories}</strong></button>
        <button type="button" onClick={() => showEntries("expense")} style={summaryButtonCard("#7f1d1d", activeType === "expense")}><span>Expense</span><strong>Rs. {totals.expense}</strong></button>
        <button type="button" onClick={() => showEntries("udhar")} style={summaryButtonCard("#b91c1c", activeType === "udhar")}><span>Udhar Pending</span><strong>Rs. {totals.udhar}</strong></button>
      </div>
      <form onSubmit={handleSubmit} style={panelStyle}>
        <input type="date" value={form.entryDate} onChange={(e) => setForm({ ...form, entryDate: e.target.value })} style={inputStyle} />
        <select value={form.entryType} onChange={(e) => setForm({ ...form, entryType: e.target.value })} style={inputStyle}>
          <option value="expense">Kharch</option>
          <option value="income">Income</option>
        </select>
        <select value={form.incomeCategory} onChange={(e) => setForm({ ...form, incomeCategory: e.target.value })} style={inputStyle}>
          <option value="accessories">Accessories</option>
          <option value="washing">Washing</option>
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
            <strong>{activeLabel}</strong>
            <button type="button" onClick={() => setSearchParams({})} style={secondaryBtn}>Show All</button>
          </div>
        )}
        <table style={tableStyle}>
          <thead><tr><th style={thStyle}>Date</th><th style={thStyle}>Type</th><th style={thStyle}>Category</th><th style={thStyle}>Party</th><th style={thStyle}>Note</th><th style={thStyle}>Amount</th><th style={thStyle}>Action</th></tr></thead>
          <tbody>{visibleEntries.map((entry) => (
            <tr key={entry.id} id={`daily-entry-${entry.id}`} style={String(entry.id) === selectedEntryId ? selectedRowStyle : undefined}>
              <td style={tdStyle}>{entry.entryDate}</td><td style={tdStyle}>{entry.entryType}</td><td style={tdStyle}>{entry.incomeCategory || "-"}</td><td style={tdStyle}>{entry.partyName}</td><td style={tdStyle}>{entry.note}</td><td style={tdStyle}>Rs. {entry.amount}</td>
              <td style={tdStyle}><button onClick={() => editEntry(entry)} style={secondaryBtn}>Edit</button>{entry.paymentStatus === "udhar" && <button onClick={() => markPaid(entry)} style={smallBtn}>Mark Paid</button>}</td>
            </tr>
          ))}
          {visibleEntries.length === 0 && (
            <tr><td style={emptyTd} colSpan="7">No entries</td></tr>
          )}
          </tbody>
        </table>
      </div>
    </div>
  );
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
