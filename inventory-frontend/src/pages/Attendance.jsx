import React, { useEffect, useState } from "react";
import { todayDate } from "../utils/storage";
import { API_BASE } from "../utils/api";

function Attendance() {
  const [entries, setEntries] = useState([]);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState({ attendanceDate: todayDate(), staffName: "", status: "present", note: "" });

  const loadEntries = async () => {
    const response = await fetch(`${API_BASE}/attendance`);
    const data = await response.json();
    setEntries(Array.isArray(data) ? data : []);
  };

  useEffect(() => {
    loadEntries();
  }, []);

  const resetForm = () => {
    setEditingId(null);
    setForm({ attendanceDate: todayDate(), staffName: "", status: "present", note: "" });
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!form.staffName) return alert("Staff name bharna zaroori hai.");
    const response = await fetch(editingId ? `${API_BASE}/attendance/${editingId}` : `${API_BASE}/attendance`, {
      method: editingId ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(form)
    });
    if (!response.ok) return alert("Attendance save nahi hui.");
    resetForm();
    loadEntries();
  };

  const editEntry = (entry) => {
    setEditingId(entry.id);
    setForm({
      attendanceDate: entry.attendanceDate || todayDate(),
      staffName: entry.staffName || "",
      status: entry.status || "present",
      note: entry.note || ""
    });
  };

  return (
    <div style={pageStyle}>
      <h1 style={titleStyle}>Attendance</h1>
      <form onSubmit={handleSubmit} style={panelStyle}>
        <input type="date" value={form.attendanceDate} onChange={(e) => setForm({ ...form, attendanceDate: e.target.value })} style={inputStyle} />
        <input placeholder="Staff name" value={form.staffName} onChange={(e) => setForm({ ...form, staffName: e.target.value })} style={inputStyle} />
        <select value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })} style={inputStyle}>
          <option value="present">Present</option>
          <option value="absent">Absent</option>
          <option value="half-day">Half Day</option>
        </select>
        <input placeholder="Note" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} style={inputStyle} />
        <button style={primaryBtn}>{editingId ? "Update Attendance" : "Save Attendance"}</button>
        {editingId && <button type="button" onClick={resetForm} style={secondaryBtn}>Cancel</button>}
      </form>
      <div style={panelStyle}>
        <table style={tableStyle}>
          <thead><tr><th style={thStyle}>Date</th><th style={thStyle}>Staff</th><th style={thStyle}>Status</th><th style={thStyle}>Note</th><th style={thStyle}>Action</th></tr></thead>
          <tbody>{entries.map((entry) => (
            <tr key={entry.id}>
              <td style={tdStyle}>{entry.attendanceDate}</td><td style={tdStyle}>{entry.staffName}</td><td style={tdStyle}>{entry.status}</td><td style={tdStyle}>{entry.note}</td>
              <td style={tdStyle}><button onClick={() => editEntry(entry)} style={secondaryBtn}>Edit</button></td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </div>
  );
}

const pageStyle = { padding: "30px", background: "#f1f5f9", minHeight: "100vh" };
const titleStyle = { margin: "0 0 20px", color: "#0f172a" };
const panelStyle = { background: "white", padding: "18px", borderRadius: "10px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", marginBottom: "18px" };
const inputStyle = { padding: "11px", marginRight: "10px", marginBottom: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "170px" };
const primaryBtn = { padding: "11px 18px", background: "#0f2963", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const secondaryBtn = { padding: "8px 10px", background: "#ffffff", color: "#0f2963", border: "1px solid #0f2963", borderRadius: "5px", cursor: "pointer", marginLeft: "6px" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white" };
const tdStyle = { padding: "10px", borderBottom: "1px solid #e2e8f0" };

export default Attendance;
