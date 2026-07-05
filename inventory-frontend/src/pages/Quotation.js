import React, { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { todayDate } from "../utils/storage";
import { API_BASE } from "../utils/api";

function Quotation() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [quotations, setQuotations] = useState([]);
  const [items, setItems] = useState([]);
  const [editingId, setEditingId] = useState(null);
  const [editingItemId, setEditingItemId] = useState(null);
  const [itemForm, setItemForm] = useState({ desc: "", qty: "", rate: "" });
  const [form, setForm] = useState({ quotationNumber: `Q-${Date.now().toString().slice(-5)}`, quotationDate: todayDate(), customerName: "", mobileNumber: "", vehicleNumber: "", note: "Quotation valid for 7 days." });

  const loadQuotations = async () => {
    const response = await fetch(`${API_BASE}/quotations`);
    const data = await response.json();
    setQuotations(Array.isArray(data) ? data : []);
  };

  useEffect(() => {
    loadQuotations();
  }, []);

  useEffect(() => {
    const desc = searchParams.get("desc");
    const rate = searchParams.get("rate");
    if (!desc || !rate) return;
    setItemForm({
      desc,
      qty: searchParams.get("qty") || "1",
      rate
    });
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  const totalAmount = useMemo(() => items.reduce((sum, item) => sum + item.qty * item.rate, 0), [items]);

  const addItem = (event) => {
    event.preventDefault();
    if (!itemForm.desc || !itemForm.qty || !itemForm.rate) return;
    const nextItem = { id: editingItemId || Date.now(), desc: itemForm.desc, qty: Number(itemForm.qty), rate: Number(itemForm.rate) };
    setItems(editingItemId ? items.map((item) => item.id === editingItemId ? nextItem : item) : [...items, nextItem]);
    setEditingItemId(null);
    setItemForm({ desc: "", qty: "", rate: "" });
  };

  const saveQuotation = async () => {
    if (!form.customerName || items.length === 0) return alert("Customer aur item add karo.");
    const response = await fetch(editingId ? `${API_BASE}/quotations/${editingId}` : `${API_BASE}/quotations`, {
      method: editingId ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...form, itemsJson: JSON.stringify(items), totalAmount })
    });
    if (!response.ok) return alert("Quotation save nahi hua.");
    await loadQuotations();
    alert(editingId ? "Quotation update ho gaya." : "Quotation database me save ho gaya.");
  };

  const loadQuotation = (quotation) => {
    setEditingId(quotation.id);
    setForm({
      quotationNumber: quotation.quotationNumber,
      quotationDate: quotation.quotationDate,
      customerName: quotation.customerName || "",
      mobileNumber: quotation.mobileNumber || "",
      vehicleNumber: quotation.vehicleNumber || "",
      note: quotation.note || ""
    });
    try {
      setItems(JSON.parse(quotation.itemsJson || "[]"));
    } catch {
      setItems([]);
    }
  };

  const newQuotation = () => {
    setEditingId(null);
    setEditingItemId(null);
    setItems([]);
    setItemForm({ desc: "", qty: "", rate: "" });
    setForm({ quotationNumber: `Q-${Date.now().toString().slice(-5)}`, quotationDate: todayDate(), customerName: "", mobileNumber: "", vehicleNumber: "", note: "Quotation valid for 7 days." });
  };

  return (
    <div style={pageStyle}>
      <style>{`@media print { .no-print { display: none !important; } body { background: #fff !important; } }`}</style>
      <div className="no-print" style={panelStyle}>
        <h1 style={titleStyle}>Quotation</h1>
        <input placeholder="Customer Name" value={form.customerName} onChange={(e) => setForm({ ...form, customerName: e.target.value })} style={inputStyle} />
        <input placeholder="Mobile No" value={form.mobileNumber} onChange={(e) => setForm({ ...form, mobileNumber: e.target.value })} style={inputStyle} />
        <input placeholder="Vehicle No" value={form.vehicleNumber} onChange={(e) => setForm({ ...form, vehicleNumber: e.target.value })} style={inputStyle} />
        <input placeholder="Quotation No" value={form.quotationNumber} onChange={(e) => setForm({ ...form, quotationNumber: e.target.value })} style={inputStyle} />
        <input type="date" value={form.quotationDate} onChange={(e) => setForm({ ...form, quotationDate: e.target.value })} style={inputStyle} />
        <form onSubmit={addItem}>
          <input placeholder="Description" value={itemForm.desc} onChange={(e) => setItemForm({ ...itemForm, desc: e.target.value })} style={inputStyle} />
          <input type="number" placeholder="Qty" value={itemForm.qty} onChange={(e) => setItemForm({ ...itemForm, qty: e.target.value })} style={inputStyle} />
          <input type="number" placeholder="Rate" value={itemForm.rate} onChange={(e) => setItemForm({ ...itemForm, rate: e.target.value })} style={inputStyle} />
          <button style={primaryBtn}>{editingItemId ? "Update Item" : "Add Item"}</button>
        </form>
        <button onClick={saveQuotation} style={primaryBtn}>{editingId ? "Update Quotation" : "Save Quotation"}</button>
        <button onClick={newQuotation} style={secondaryBtn}>New</button>
        <button onClick={() => window.print()} style={secondaryBtn}>Print</button>
        {quotations.length > 0 && <div style={historyBox}>{quotations.slice(0, 5).map((q) => <button key={q.id} onClick={() => loadQuotation(q)} style={secondaryBtn}>{q.quotationNumber} - {q.customerName}</button>)}</div>}
      </div>

      <div style={quoteSheet}>
        <div style={quoteHeader}>
          <div style={brandLockup}><div style={logoMark}>V</div><div><h1 style={{ margin: 0, color: "#0f2963", letterSpacing: "2px" }}>VAISHNAV</h1><strong>CAR WASH AND ACCESSORIES</strong><div style={goldText}>Premium wash, polish and accessories</div></div></div>
          <div style={{ textAlign: "right" }}><h2 style={quoteTitle}>QUOTATION</h2><div>No: {form.quotationNumber}</div><div>Date: {form.quotationDate}</div></div>
        </div>
        <div style={metaGrid}><div><strong>Customer:</strong> {form.customerName}</div><div><strong>Mobile:</strong> {form.mobileNumber}</div><div><strong>Vehicle:</strong> {form.vehicleNumber}</div></div>
        <table style={tableStyle}><thead><tr><th style={thStyle}>S.No</th><th style={thStyle}>Description</th><th style={thStyle}>Qty</th><th style={thStyle}>Rate</th><th style={thStyle}>Amount</th></tr></thead>
          <tbody>{items.map((item, index) => <tr key={item.id}><td style={tdStyle}>{index + 1}</td><td style={tdStyle}>{item.desc}<span className="no-print" style={itemActions}><button onClick={() => { setEditingItemId(item.id); setItemForm({ desc: item.desc, qty: item.qty, rate: item.rate }); }} style={miniBtn}>Edit</button><button onClick={() => setItems(items.filter((row) => row.id !== item.id))} style={miniDangerBtn}>Delete</button></span></td><td style={tdStyle}>{item.qty}</td><td style={tdStyle}>Rs. {item.rate}</td><td style={tdStyle}>Rs. {item.qty * item.rate}</td></tr>)}</tbody>
        </table>
        <div style={totalBox}>Total: Rs. {totalAmount}/-</div>
        <p style={noteStyle}>{form.note}</p>
        <div style={{ textAlign: "right", marginTop: "45px" }}>Authorised Signature</div>
      </div>
    </div>
  );
}

const pageStyle = { padding: "30px", background: "#f1f5f9", minHeight: "100vh" };
const titleStyle = { margin: "0 0 15px", color: "#0f172a" };
const panelStyle = { background: "white", padding: "18px", borderRadius: "10px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", marginBottom: "18px" };
const inputStyle = { padding: "11px", marginRight: "10px", marginBottom: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "170px" };
const primaryBtn = { padding: "11px 18px", background: "#0f2963", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer", marginRight: "8px" };
const secondaryBtn = { padding: "10px 14px", background: "#ffffff", color: "#0f2963", border: "1px solid #0f2963", borderRadius: "6px", fontWeight: "bold", cursor: "pointer", marginRight: "8px" };
const historyBox = { marginTop: "12px", display: "flex", gap: "8px", flexWrap: "wrap" };
const quoteSheet = { background: "#fff", width: "210mm", minHeight: "280mm", margin: "0 auto", padding: "15mm", boxSizing: "border-box", boxShadow: "0 6px 18px rgba(0,0,0,0.08)", borderTop: "8px solid #071635" };
const quoteHeader = { display: "flex", justifyContent: "space-between", borderBottom: "3px solid #0f2963", paddingBottom: "14px", marginBottom: "18px", alignItems: "center" };
const brandLockup = { display: "flex", alignItems: "center", gap: "14px" };
const logoMark = { width: "58px", height: "58px", borderRadius: "50%", background: "linear-gradient(135deg, #071635, #0f2963)", color: "#f9d989", border: "3px solid #c49a45", display: "flex", alignItems: "center", justifyContent: "center", fontFamily: "Georgia, serif", fontSize: "34px", fontWeight: "900" };
const goldText = { color: "#9c742a", fontSize: "12px", marginTop: "4px", fontWeight: "bold" };
const quoteTitle = { margin: "0 0 8px", color: "#0f2963", letterSpacing: "1px" };
const metaGrid = { display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "10px", marginBottom: "18px", background: "#f8fafc", border: "1px solid #cbd5e1", padding: "12px" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white", border: "1px solid #0f2963" };
const tdStyle = { padding: "10px", border: "1px solid #cbd5e1" };
const totalBox = { marginLeft: "auto", marginTop: "14px", width: "220px", background: "#0f2963", color: "white", padding: "12px", fontWeight: "bold", textAlign: "center" };
const noteStyle = { marginTop: "18px", color: "#334155", fontWeight: "bold" };
const itemActions = { float: "right", display: "inline-flex", gap: "6px" };
const miniBtn = { border: "1px solid #0f2963", background: "#fff", color: "#0f2963", borderRadius: "4px", cursor: "pointer" };
const miniDangerBtn = { border: "none", background: "#dc2626", color: "#fff", borderRadius: "4px", cursor: "pointer" };

export default Quotation;
