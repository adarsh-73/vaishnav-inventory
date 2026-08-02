import React, { useMemo, useState } from "react";

const expenseKey = "vaishnav_expense_entries_v1";
const fixedKey = "vaishnav_fixed_expenses_v1";

const categories = ["Labour", "Spare", "Fixed", "Fuel", "Transport", "Food", "Other"];
const modes = ["Cash", "UPI", "Bank", "Card", "Credit"];

function ExpenseDashboard() {
  const [expenses, setExpenses] = useState(() => load(expenseKey, sampleExpenses()));
  const [fixedExpenses, setFixedExpenses] = useState(() => load(fixedKey, sampleFixedExpenses()));
  const [entryTab, setEntryTab] = useState("expense");
  const [editingId, setEditingId] = useState("");
  const [fixedEditingId, setFixedEditingId] = useState("");
  const [form, setForm] = useState(emptyExpenseForm());
  const [fixedForm, setFixedForm] = useState(emptyFixedForm());
  const [filters, setFilters] = useState({ month: currentMonth(), from: "", to: "", category: "", search: "" });

  const filteredExpenses = useMemo(() => {
    const search = filters.search.trim().toLowerCase();
    return expenses
      .filter((item) => !filters.month || item.date.startsWith(filters.month))
      .filter((item) => !filters.from || item.date >= filters.from)
      .filter((item) => !filters.to || item.date <= filters.to)
      .filter((item) => !filters.category || item.category === filters.category)
      .filter((item) => {
        if (!search) return true;
        return [item.date, item.paidTo, item.category, item.mode, item.reference, item.note, item.amount]
          .join(" ")
          .toLowerCase()
          .includes(search);
      })
      .sort((a, b) => b.date.localeCompare(a.date));
  }, [expenses, filters]);

  const totals = useMemo(() => ({
    total: total(filteredExpenses),
    labour: total(filteredExpenses, "Labour"),
    spare: total(filteredExpenses, "Spare"),
    fixed: total(filteredExpenses, "Fixed")
  }), [filteredExpenses]);

  const saveExpenses = (nextExpenses) => {
    setExpenses(nextExpenses);
    localStorage.setItem(expenseKey, JSON.stringify(nextExpenses));
  };

  const saveFixedExpenses = (nextFixedExpenses) => {
    setFixedExpenses(nextFixedExpenses);
    localStorage.setItem(fixedKey, JSON.stringify(nextFixedExpenses));
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    if (!form.date || !form.paidTo.trim() || !form.amount) return alert("Date, name aur amount bharna zaroori hai.");
    const item = { ...form, id: editingId || crypto.randomUUID(), amount: Number(form.amount) };
    saveExpenses(expenses.filter((entry) => entry.id !== item.id).concat(item));
    setEditingId("");
    setForm(emptyExpenseForm());
  };

  const handleFixedSubmit = (event) => {
    event.preventDefault();
    if (!fixedForm.name.trim() || !fixedForm.paidTo.trim() || !fixedForm.amount) return alert("Fixed expense ka name, paid to aur amount bharna zaroori hai.");
    const item = { ...fixedForm, id: fixedEditingId || crypto.randomUUID(), amount: Number(fixedForm.amount), day: Number(fixedForm.day) };
    saveFixedExpenses(fixedExpenses.filter((entry) => entry.id !== item.id).concat(item));
    setFixedEditingId("");
    setFixedForm(emptyFixedForm());
  };

  const editExpense = (item) => {
    setEntryTab("expense");
    setEditingId(item.id);
    setForm({ ...item, amount: String(item.amount) });
  };

  const editFixed = (item) => {
    setEntryTab("fixed");
    setFixedEditingId(item.id);
    setFixedForm({ ...item, amount: String(item.amount), day: String(item.day) });
  };

  const addFixedToMonth = () => {
    const month = filters.month || currentMonth();
    const existingKeys = new Set(expenses.map((item) => `${item.reference}|${item.date}|${item.amount}`));
    const additions = fixedExpenses
      .map((item) => ({
        id: crypto.randomUUID(),
        date: clampDateForMonth(month, item.day),
        paidTo: item.paidTo,
        category: item.category,
        mode: item.mode,
        reference: `Fixed: ${item.name}`,
        note: item.note,
        amount: item.amount
      }))
      .filter((item) => !existingKeys.has(`${item.reference}|${item.date}|${item.amount}`));
    saveExpenses(expenses.concat(additions));
    alert(`${additions.length} fixed entries ${month} me add ho gayi.`);
  };

  const copyPreviousMonth = () => {
    const month = filters.month || currentMonth();
    const [year, monthNumber] = month.split("-").map(Number);
    const previousMonth = new Date(year, monthNumber - 2, 1).toISOString().slice(0, 7);
    const copied = expenses
      .filter((item) => item.date.startsWith(previousMonth))
      .map((item) => ({
        ...item,
        id: crypto.randomUUID(),
        date: clampDateForMonth(month, Number(item.date.slice(8, 10))),
        reference: item.reference ? `Ref ${previousMonth}: ${item.reference}` : `Ref ${previousMonth}`
      }));
    saveExpenses(expenses.concat(copied));
    alert(`${copied.length} entries ${previousMonth} se ${month} me copy hui.`);
  };

  const exportCsv = () => {
    const header = ["date", "paidTo", "category", "mode", "reference", "note", "amount"];
    const rows = filteredExpenses.map((item) => header.map((key) => csvCell(item[key])).join(","));
    const blob = new Blob([header.join(",") + "\n" + rows.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `expense-statement-${filters.month || "all"}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const importCsv = async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    saveExpenses(expenses.concat(parseCsv(await file.text())));
    event.target.value = "";
  };

  return (
    <div style={pageStyle}>
      <div style={headerStyle}>
        <div>
          <h1 style={titleStyle}>Expense Dashboard</h1>
          <div style={subtitleStyle}>Date wise kharcha search karo, month statement nikalo, fixed expense repeat karo.</div>
        </div>
        <div style={actionRow}>
          <button type="button" onClick={exportCsv} style={secondaryBtn}>Export CSV</button>
          <label style={secondaryBtn}>
            Import CSV
            <input type="file" accept=".csv,text/csv" onChange={importCsv} style={{ display: "none" }} />
          </label>
          <button type="button" onClick={() => window.print()} style={secondaryBtn}>Print</button>
        </div>
      </div>

      <div style={summaryRow}>
        <SummaryCard label="Total Expense" value={totals.total} color="#0f766e" />
        <SummaryCard label="Labour Paid" value={totals.labour} color="#7f1d1d" />
        <SummaryCard label="Spare Purchase" value={totals.spare} color="#6d4c1d" />
        <SummaryCard label="Fixed Expense" value={totals.fixed} color="#0f2963" />
      </div>

      <div style={layoutStyle}>
        <div>
          <div style={panelStyle}>
            <div style={tabRow}>
              <button type="button" onClick={() => setEntryTab("expense")} style={tabBtn(entryTab === "expense")}>Expense Entry</button>
              <button type="button" onClick={() => setEntryTab("fixed")} style={tabBtn(entryTab === "fixed")}>Fixed Expense</button>
            </div>

            {entryTab === "expense" ? (
              <form onSubmit={handleSubmit} style={formGrid}>
                <input type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} style={inputStyle} />
                <input type="number" min="0" step="0.01" placeholder="Amount" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} style={inputStyle} />
                <input placeholder="Paid to / Kisko" value={form.paidTo} onChange={(e) => setForm({ ...form, paidTo: e.target.value })} style={inputStyle} />
                <select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} style={inputStyle}>{categories.map((item) => <option key={item}>{item}</option>)}</select>
                <select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value })} style={inputStyle}>{modes.map((item) => <option key={item}>{item}</option>)}</select>
                <input placeholder="Reference / Bill no" value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} style={inputStyle} />
                <textarea placeholder="Note" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} style={{ ...inputStyle, gridColumn: "1 / -1", minHeight: 76 }} />
                <button type="submit" style={primaryBtn}>{editingId ? "Update Expense" : "Save Expense"}</button>
                <button type="button" onClick={() => { setEditingId(""); setForm(emptyExpenseForm()); }} style={secondaryBtn}>Clear</button>
              </form>
            ) : (
              <form onSubmit={handleFixedSubmit} style={formGrid}>
                <input placeholder="Name" value={fixedForm.name} onChange={(e) => setFixedForm({ ...fixedForm, name: e.target.value })} style={inputStyle} />
                <input type="number" min="0" step="0.01" placeholder="Amount" value={fixedForm.amount} onChange={(e) => setFixedForm({ ...fixedForm, amount: e.target.value })} style={inputStyle} />
                <input placeholder="Paid to" value={fixedForm.paidTo} onChange={(e) => setFixedForm({ ...fixedForm, paidTo: e.target.value })} style={inputStyle} />
                <input type="number" min="1" max="31" placeholder="Day" value={fixedForm.day} onChange={(e) => setFixedForm({ ...fixedForm, day: e.target.value })} style={inputStyle} />
                <select value={fixedForm.category} onChange={(e) => setFixedForm({ ...fixedForm, category: e.target.value })} style={inputStyle}>{categories.map((item) => <option key={item}>{item}</option>)}</select>
                <select value={fixedForm.mode} onChange={(e) => setFixedForm({ ...fixedForm, mode: e.target.value })} style={inputStyle}>{modes.map((item) => <option key={item}>{item}</option>)}</select>
                <textarea placeholder="Note" value={fixedForm.note} onChange={(e) => setFixedForm({ ...fixedForm, note: e.target.value })} style={{ ...inputStyle, gridColumn: "1 / -1", minHeight: 76 }} />
                <button type="submit" style={primaryBtn}>{fixedEditingId ? "Update Fixed" : "Save Fixed"}</button>
                <button type="button" onClick={addFixedToMonth} style={secondaryBtn}>Add Fixed To Month</button>
              </form>
            )}
          </div>

          <div style={panelStyle}>
            <h2 style={panelTitle}>Fixed Expense List</h2>
            {fixedExpenses.length === 0 ? <div style={emptyStyle}>No fixed expense</div> : fixedExpenses.map((item) => (
              <div key={item.id} style={miniItem}>
                <div>
                  <strong>{item.name} - Rs. {Number(item.amount || 0).toLocaleString("en-IN")}</strong>
                  <div style={mutedText}>{item.paidTo} | Day {item.day} | {item.category}</div>
                </div>
                <div style={actionRow}>
                  <button type="button" onClick={() => editFixed(item)} style={smallBtn}>Edit</button>
                  <button type="button" onClick={() => saveFixedExpenses(fixedExpenses.filter((entry) => entry.id !== item.id))} style={dangerBtn}>Delete</button>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div style={panelStyle}>
          <h2 style={panelTitle}>Statement</h2>
          <div style={filterGrid}>
            <input type="month" value={filters.month} onChange={(e) => setFilters({ ...filters, month: e.target.value })} style={inputStyle} />
            <input type="date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} style={inputStyle} />
            <input type="date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} style={inputStyle} />
            <select value={filters.category} onChange={(e) => setFilters({ ...filters, category: e.target.value })} style={inputStyle}>
              <option value="">All Category</option>
              {categories.map((item) => <option key={item}>{item}</option>)}
            </select>
            <input type="search" placeholder="Search date, name, note, reference" value={filters.search} onChange={(e) => setFilters({ ...filters, search: e.target.value })} style={inputStyle} />
          </div>
          <div style={{ ...actionRow, margin: "12px 0" }}>
            <button type="button" onClick={copyPreviousMonth} style={secondaryBtn}>Copy Previous Month</button>
            <button type="button" onClick={() => setFilters({ month: currentMonth(), from: "", to: "", category: "", search: "" })} style={secondaryBtn}>Reset</button>
          </div>
          <div style={tableWrap}>
            <table style={tableStyle}>
              <thead>
                <tr>
                  <th style={thStyle}>Date</th>
                  <th style={thStyle}>Paid To</th>
                  <th style={thStyle}>Category</th>
                  <th style={thStyle}>Mode</th>
                  <th style={thStyle}>Reference</th>
                  <th style={thStyle}>Note</th>
                  <th style={thStyle}>Amount</th>
                  <th style={thStyle}>Action</th>
                </tr>
              </thead>
              <tbody>
                {filteredExpenses.map((item) => (
                  <tr key={item.id}>
                    <td style={tdStyle}>{item.date}</td>
                    <td style={tdStyle}>{item.paidTo}</td>
                    <td style={tdStyle}><span style={pillStyle}>{item.category}</span></td>
                    <td style={tdStyle}>{item.mode}</td>
                    <td style={tdStyle}>{item.reference || "-"}</td>
                    <td style={tdStyle}>{item.note || "-"}</td>
                    <td style={tdStyle}>Rs. {Number(item.amount || 0).toLocaleString("en-IN")}</td>
                    <td style={tdStyle}>
                      <button type="button" onClick={() => editExpense(item)} style={smallBtn}>Edit</button>
                      <button type="button" onClick={() => saveExpenses(expenses.filter((entry) => entry.id !== item.id))} style={dangerBtn}>Delete</button>
                    </td>
                  </tr>
                ))}
                {filteredExpenses.length === 0 && <tr><td colSpan="8" style={emptyTd}>No expense found</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}

function SummaryCard({ label, value, color }) {
  return (
    <div style={{ ...summaryCard, borderTop: `4px solid ${color}` }}>
      <span>{label}</span>
      <strong>Rs. {Number(value || 0).toLocaleString("en-IN")}</strong>
    </div>
  );
}

function load(key, fallback) {
  try {
    const saved = localStorage.getItem(key);
    return saved ? JSON.parse(saved) : fallback;
  } catch {
    return fallback;
  }
}

function total(list, category) {
  return list.filter((item) => !category || item.category === category).reduce((sum, item) => sum + Number(item.amount || 0), 0);
}

function todayDate() {
  return new Date().toISOString().slice(0, 10);
}

function currentMonth() {
  return todayDate().slice(0, 7);
}

function clampDateForMonth(month, day) {
  const [year, monthNumber] = month.split("-").map(Number);
  const lastDay = new Date(year, monthNumber, 0).getDate();
  return `${month}-${String(Math.min(Number(day), lastDay)).padStart(2, "0")}`;
}

function emptyExpenseForm() {
  return { date: todayDate(), paidTo: "", category: "Labour", mode: "Cash", reference: "", note: "", amount: "" };
}

function emptyFixedForm() {
  return { name: "", paidTo: "", category: "Fixed", mode: "Bank", day: "1", note: "", amount: "" };
}

function csvCell(value) {
  return `"${String(value ?? "").replace(/"/g, '""')}"`;
}

function parseCsv(text) {
  const lines = text.split(/\r?\n/).filter(Boolean);
  if (lines.length < 2) return [];
  const headers = splitCsvLine(lines[0]);
  return lines.slice(1).map((line) => {
    const values = splitCsvLine(line);
    const item = { id: crypto.randomUUID() };
    headers.forEach((key, index) => { item[key] = values[index] || ""; });
    item.amount = Number(item.amount || 0);
    return item;
  }).filter((item) => item.date && item.paidTo && item.amount);
}

function splitCsvLine(line) {
  const result = [];
  let value = "";
  let quoted = false;
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];
    if (char === '"' && line[index + 1] === '"') {
      value += '"';
      index += 1;
    } else if (char === '"') {
      quoted = !quoted;
    } else if (char === "," && !quoted) {
      result.push(value);
      value = "";
    } else {
      value += char;
    }
  }
  result.push(value);
  return result;
}

function sampleExpenses() {
  return [
    { id: crypto.randomUUID(), date: "2026-06-05", paidTo: "Ramesh Labour", category: "Labour", mode: "Cash", reference: "Site A", note: "Mistri payment", amount: 8500 },
    { id: crypto.randomUUID(), date: "2026-06-12", paidTo: "Sharma Spare", category: "Spare", mode: "UPI", reference: "Bill 104", note: "Bearing and belt", amount: 3200 },
    { id: crypto.randomUUID(), date: "2026-07-03", paidTo: "Workshop Rent", category: "Fixed", mode: "Bank", reference: "Monthly", note: "Rent", amount: 15000 }
  ];
}

function sampleFixedExpenses() {
  return [
    { id: crypto.randomUUID(), name: "Workshop Rent", paidTo: "Owner", category: "Fixed", mode: "Bank", day: 3, note: "Monthly rent", amount: 15000 }
  ];
}

const pageStyle = { padding: "28px", background: "#f8fafc", minHeight: "100vh" };
const headerStyle = { display: "flex", justifyContent: "space-between", gap: "16px", alignItems: "flex-start", flexWrap: "wrap", marginBottom: "18px" };
const titleStyle = { margin: 0, color: "#071635", fontSize: "32px", fontWeight: "900" };
const subtitleStyle = { color: "#64748b", marginTop: "6px", fontWeight: "600" };
const summaryRow = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: "14px", marginBottom: "16px" };
const summaryCard = { background: "#ffffff", border: "1px solid #e2e8f0", borderRadius: "12px", padding: "16px", boxShadow: "0 12px 28px rgba(15,23,42,0.08)", display: "grid", gap: "8px" };
const layoutStyle = { display: "grid", gridTemplateColumns: "minmax(320px, 420px) minmax(0, 1fr)", gap: "16px", alignItems: "start" };
const panelStyle = { background: "#ffffff", border: "1px solid #e2e8f0", borderRadius: "12px", padding: "16px", boxShadow: "0 12px 28px rgba(15,23,42,0.08)", marginBottom: "16px" };
const panelTitle = { margin: "0 0 12px", color: "#0f172a", fontSize: "18px" };
const formGrid = { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "10px" };
const filterGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "10px" };
const inputStyle = { width: "100%", minHeight: "40px", border: "1px solid #cbd5e1", borderRadius: "8px", padding: "9px 10px", boxSizing: "border-box" };
const primaryBtn = { minHeight: "40px", border: 0, borderRadius: "8px", background: "#0f766e", color: "#fff", fontWeight: "800", cursor: "pointer" };
const secondaryBtn = { minHeight: "40px", border: "1px solid #cbd5e1", borderRadius: "8px", background: "#f8fafc", color: "#0f172a", fontWeight: "800", cursor: "pointer", padding: "9px 12px", textDecoration: "none" };
const smallBtn = { ...secondaryBtn, minHeight: "34px", padding: "6px 9px", marginRight: "6px" };
const dangerBtn = { ...smallBtn, color: "#b91c1c", borderColor: "#fecaca" };
const actionRow = { display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" };
const tabRow = { display: "flex", gap: "8px", marginBottom: "12px" };
const tabBtn = (active) => ({ ...secondaryBtn, background: active ? "#0f766e" : "#f8fafc", color: active ? "#fff" : "#0f172a" });
const tableWrap = { overflowX: "auto", border: "1px solid #e2e8f0", borderRadius: "10px" };
const tableStyle = { width: "100%", borderCollapse: "collapse", minWidth: "860px" };
const thStyle = { textAlign: "left", padding: "10px", background: "#f1f5f9", color: "#475569", fontSize: "13px", borderBottom: "1px solid #e2e8f0" };
const tdStyle = { padding: "10px", borderBottom: "1px solid #e2e8f0", color: "#0f172a", verticalAlign: "top" };
const emptyTd = { ...tdStyle, textAlign: "center", color: "#64748b", padding: "24px" };
const emptyStyle = { color: "#64748b", padding: "12px" };
const mutedText = { color: "#64748b", fontSize: "13px", marginTop: "4px" };
const miniItem = { display: "grid", gridTemplateColumns: "1fr auto", gap: "10px", alignItems: "center", border: "1px solid #e2e8f0", borderRadius: "8px", padding: "10px", marginBottom: "8px" };
const pillStyle = { display: "inline-block", borderRadius: "999px", background: "#ccfbf1", color: "#115e59", padding: "3px 8px", fontWeight: "800", fontSize: "12px" };

export default ExpenseDashboard;
