import React, { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../utils/api";
import JsBarcode from "jsbarcode";
import { QRCodeSVG } from "qrcode.react";

function Purchase() {
  const [searchParams] = useSearchParams();
  const billPhotoInputRef = useRef(null);
  const [products, setProducts] = useState([]);
  const [purchases, setPurchases] = useState([]);
  const [billImageBusy, setBillImageBusy] = useState(false);
  const [billImageStatus, setBillImageStatus] = useState("");
  const [ocrText, setOcrText] = useState("");
  const [importRows, setImportRows] = useState([]);
  const [importBillNumber, setImportBillNumber] = useState("");
  const [importSaving, setImportSaving] = useState(false);
  const [billPhotoCount, setBillPhotoCount] = useState(0);
  const [savedBarcodeProducts, setSavedBarcodeProducts] = useState([]);
  const [ocrLanguage, setOcrLanguage] = useState("eng");
  const [form, setForm] = useState({ productId: "", qty: "", price: "", invoiceNumber: "" });

  const loadData = async () => {
    try {
      const [productData, purchaseData] = await Promise.all([
        apiRequest("/products/options"),
        apiRequest("/purchases/recent?limit=20")
      ]);
      setProducts(Array.isArray(productData) ? productData : []);
      setPurchases(Array.isArray(purchaseData) ? purchaseData : []);
    } catch (error) {
      alert(`Purchase data load nahi hua: ${error.message}`);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  useEffect(() => {
    const productId = searchParams.get("productId");
    if (productId) setForm((current) => ({ ...current, productId }));
  }, [searchParams]);

  useEffect(() => {
    if (searchParams.get("importPhoto") !== "1") return;
    const timer = window.setTimeout(() => billPhotoInputRef.current?.click(), 350);
    return () => window.clearTimeout(timer);
  }, [searchParams]);

  const handlePurchase = async (event) => {
    event.preventDefault();
    if (!form.productId || !form.qty) return alert("Product aur quantity select karo.");

    try {
      await apiRequest("/purchases", {
        method: "POST",
        body: JSON.stringify({
          productdata: { id: Number(form.productId) },
          quantity: Number(form.qty),
          purchasePrice: Number(form.price || 0),
          invoiceNumber: form.invoiceNumber || null
        })
      });
      setForm({ productId: "", qty: "", price: "", invoiceNumber: "" });
      await loadData();
      alert("Purchase database me save ho gaya aur stock update ho gaya.");
    } catch (error) {
      alert(`Purchase save nahi hua: ${error.message}`);
    }
  };

  const handleBillPhoto = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setBillImageBusy(true);
    setBillImageStatus("Photo se text padh rahe hain. Pehli baar language model load hone mein thoda waqt lag sakta hai...");
    let worker;
    try {
      const { createWorker } = await import("tesseract.js");
      worker = await createWorker(ocrLanguage, 1, {
        logger: (progress) => {
          if (progress.status === "recognizing text") {
            setBillImageStatus(`Bill padh rahe hain: ${Math.round((progress.progress || 0) * 100)}%`);
          }
        }
      });
      const result = await worker.recognize(file);
      const text = result.data.text || "";
      const pageNumber = billPhotoCount + 1;
      const parsedRows = parseBillLines(text, products);
      setOcrText((current) => `${current}${current ? "\n\n" : ""}--- BILL PAGE ${pageNumber} ---\n${text}`);
      setImportRows((current) => [...current, ...parsedRows]);
      setBillPhotoCount(pageNumber);
      setBillImageStatus(parsedRows.length
        ? `Page ${pageNumber}: ${parsedRows.length} possible item lines mili. Is bill mein ab tak ${importRows.length + parsedRows.length} rows hain. Agla page bhi photo le sakte hain; save se pehle sab verify karein.`
        : `Page ${pageNumber} ka text padha, par item rows identify nahi hui. Agla page lein ya text edit karke reparse karein.`);
    } catch (error) {
      setBillImageStatus(`Photo read nahi hui: ${error.message || "Image clear karke dobara try karein."}`);
    } finally {
      await worker?.terminate();
      setBillImageBusy(false);
    }
  };

  const reparseBillText = () => {
    const rows = parseBillLines(ocrText, products);
    setImportRows(rows);
    setBillImageStatus(rows.length
      ? `${rows.length} possible item lines parse hui hain. Har row verify karein.`
      : "Koi item row identify nahi hui. Add item se manually row bana sakte hain.");
  };

  const updateImportRow = (rowId, updates) => {
    setImportRows((current) => current.map((row) => row.id === rowId ? { ...row, ...updates } : row));
  };

  const addImportRow = () => {
    setImportRows((current) => [...current, {
      id: `manual-${Date.now()}-${Math.random()}`, productId: "", productName: "", quantity: "1",
      purchasePrice: "", sellPrice: "", barcode: ""
    }]);
  };

  const generateImportBarcode = (row) => {
    const randomPart = typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID().replace(/-/g, "").slice(0, 14).toUpperCase()
      : `${Date.now().toString(36).toUpperCase()}${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
    updateImportRow(row.id, { barcode: `VN-${randomPart}` });
  };

  const generateAllImportBarcodes = () => {
    setImportRows((current) => current.map((row) => {
      if (row.productId || row.barcode) return row;
      const randomPart = typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID().replace(/-/g, "").slice(0, 14).toUpperCase()
        : `${Date.now().toString(36).toUpperCase()}${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
      return { ...row, barcode: `VN-${randomPart}` };
    }));
  };

  const saveImportedBill = async () => {
    if (!importRows.length) return alert("Save karne ke liye bill item add karein.");
    const invalidRow = importRows.find((row) => !Number(row.quantity) || Number(row.quantity) <= 0
      || row.purchasePrice === "" || Number(row.purchasePrice) < 0
      || (!row.productId && (!row.productName.trim() || !Number(row.sellPrice) || Number(row.sellPrice) <= 0 || !row.barcode)));
    if (invalidRow) return alert("Har row ka naam, quantity, buy price, sell price bharein aur naye product ka barcode generate karein.");

    setImportSaving(true);
    try {
      const saved = await apiRequest("/purchases/import", {
        method: "POST",
        body: JSON.stringify(importRows.map((row) => ({
          productId: row.productId ? Number(row.productId) : null,
          productName: row.productId ? null : row.productName.trim(),
          quantity: Number(row.quantity),
          purchasePrice: Number(row.purchasePrice),
          sellPrice: Number(row.sellPrice) > 0 ? Number(row.sellPrice) : null,
          barcode: row.productId ? null : (row.barcode || null),
          invoiceNumber: importBillNumber || null
        })))
      });
      setSavedBarcodeProducts((Array.isArray(saved) ? saved : []).map((purchase) => purchase.productdata).filter((product) => product?.barcode));
      setImportRows([]);
      setOcrText("");
      setImportBillNumber("");
      setBillPhotoCount(0);
      setBillImageStatus("Bill items inventory mein add ho gaye; stock aur purchase history update ho gayi.");
      await loadData();
    } catch (error) {
      alert(`Bill import save nahi hua: ${error.message}`);
    } finally {
      setImportSaving(false);
    }
  };

  return (
    <div style={pageStyle}>
      <h1 style={titleStyle}>Purchase Entry</h1>
      <section style={panelStyle}>
        <h2 style={{ marginTop: 0, color: "#0f2963" }}>Supplier bill photo se items import karein</h2>
        <p style={{ color: "#64748b" }}>Photo isi app mein process hoti hai. OCR galti kar sakta hai, isliye stock save karne se pehle har item verify karein.</p>
        <select value={ocrLanguage} onChange={(e) => setOcrLanguage(e.target.value)} style={inputStyle} disabled={billImageBusy}>
          <option value="eng">Bill language: English</option>
          <option value="eng+hin">Bill language: English + Hindi</option>
        </select>
        <label style={primaryBtn}>
          {billImageBusy ? "Photo read ho rahi hai..." : `📷 ${billPhotoCount ? `Agla bill page lein (${billPhotoCount} liye)` : "Bill ki pehli photo lein / upload karein"}`}
          <input ref={billPhotoInputRef} type="file" accept="image/*" capture="environment" onChange={handleBillPhoto} disabled={billImageBusy} style={{ display: "none" }} />
        </label>
        {billImageStatus && <div style={{ margin: "14px 0", color: "#334155" }}>{billImageStatus}</div>}
        {(ocrText || importRows.length > 0) && <>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", margin: "12px 0" }}>
            <input placeholder="Supplier bill no (optional)" value={importBillNumber} onChange={(e) => setImportBillNumber(e.target.value)} style={inputStyle} />
            <button type="button" onClick={reparseBillText} style={secondaryBtn}>Saare pages dobara parse karein</button>
            <button type="button" onClick={addImportRow} style={secondaryBtn}>+ Add item</button>
            <button type="button" onClick={generateAllImportBarcodes} style={secondaryBtn}>Generate all new item barcodes</button>
          </div>
          <textarea value={ocrText} onChange={(e) => setOcrText(e.target.value)} placeholder="OCR se nikla text; zarurat pade toh edit karke reparse karein" style={{ ...inputStyle, width: "100%", minHeight: 100, boxSizing: "border-box" }} />
          {importRows.length > 0 && <>
            <div style={{ overflowX: "auto" }}><table style={tableStyle}>
              <thead><tr><th style={thStyle}>Existing / New product</th><th style={thStyle}>Description</th><th style={thStyle}>Qty</th><th style={thStyle}>Buy price / unit</th><th style={thStyle}>Sell price / unit</th><th style={thStyle}>Barcode</th><th style={thStyle}>Remove</th></tr></thead>
              <tbody>{importRows.map((row) => {
                const selected = products.find((product) => String(product.id) === String(row.productId));
                return <tr key={row.id}>
                  <td style={tdStyle}><select value={row.productId} onChange={(e) => {
                    const found = products.find((product) => String(product.id) === e.target.value);
                    updateImportRow(row.id, { productId: e.target.value, productName: found?.productName || row.productName, sellPrice: found?.sellPrice ? String(found.sellPrice) : row.sellPrice });
                  }} style={inputStyle}><option value="">Create new product</option>{products.map((product) => <option key={product.id} value={product.id}>{product.productName} (stock {product.quantity ?? 0})</option>)}</select></td>
                  <td style={tdStyle}><input value={selected?.productName || row.productName} onChange={(e) => updateImportRow(row.id, { productId: "", productName: e.target.value })} style={inputStyle} /></td>
                  <td style={tdStyle}><input type="number" min="1" value={row.quantity} onChange={(e) => updateImportRow(row.id, { quantity: e.target.value })} style={inputStyle} /></td>
                  <td style={tdStyle}><input type="number" min="0" step="0.01" value={row.purchasePrice} onChange={(e) => updateImportRow(row.id, { purchasePrice: e.target.value })} style={inputStyle} /></td>
                  <td style={tdStyle}><input type="number" min="0" step="0.01" placeholder={selected ? String(selected.sellPrice || "") : "Required for new"} value={row.sellPrice} onChange={(e) => updateImportRow(row.id, { sellPrice: e.target.value })} style={inputStyle} /></td>
                  <td style={tdStyle}>{selected?.barcode || row.barcode ? <><Code128Barcode value={selected?.barcode || row.barcode} /><div style={{ fontSize: 11 }}>{selected?.barcode || row.barcode}</div></> : <button type="button" onClick={() => generateImportBarcode(row)} style={secondaryBtn}>Generate barcode</button>}</td>
                  <td style={tdStyle}><button type="button" onClick={() => setImportRows((current) => current.filter((entry) => entry.id !== row.id))} style={dangerBtn}>Remove</button></td>
                </tr>;
              })}</tbody>
            </table></div>
            <button type="button" onClick={saveImportedBill} disabled={importSaving} style={{ ...primaryBtn, marginTop: 12, opacity: importSaving ? 0.65 : 1 }}>{importSaving ? "Saving..." : `Verify karke ${importRows.length} item save karein`}</button>
          </>}
        </>}
        {savedBarcodeProducts.length > 0 && <div style={{ ...panelStyle, marginTop: 16, border: "1px solid #86efac" }}>
          <style>{`@media print { body * { visibility: hidden !important; } .import-labels-print, .import-labels-print * { visibility: visible !important; } .import-labels-print { position: absolute !important; left: 0 !important; top: 0 !important; width: 100% !important; display: grid !important; grid-template-columns: repeat(3, 1fr) !important; } }`}</style>
          <h3 style={{ color: "#166534" }}>Products saved with barcode</h3>
          <p>In labels ko print karke products par chipkayein. Sell price product record mein save hai.</p>
          <div className="import-labels-print" style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(210px, 1fr))", gap: 12 }}>
            {savedBarcodeProducts.map((product, index) => <div key={`${product.id}-${index}`} style={{ border: "1px solid #cbd5e1", borderRadius: 8, padding: 12, textAlign: "center" }}>
              <strong>{product.productName}</strong><Code128Barcode value={product.barcode} /><QRCodeSVG value={product.barcode} size={120} level="H" /><div>{product.barcode}</div><div>Sell: ₹ {product.sellPrice}</div>
            </div>)}
          </div>
          <button type="button" onClick={() => window.print()} style={{ ...primaryBtn, marginTop: 12 }}>Print barcode labels</button>
        </div>}
      </section>
      <form onSubmit={handlePurchase} style={panelStyle}>
        <select value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })} style={inputStyle}>
          <option value="">Select product</option>
          {products.map((product) => (
            <option key={product.id} value={product.id}>
              {product.productName} - Stock {product.quantity ?? 0} - Buy Rs. {product.purchasePrice ?? 0} - Sell Rs. {product.sellPrice ?? 0}
            </option>
          ))}
        </select>
        <input type="number" placeholder="Quantity" value={form.qty} onChange={(e) => setForm({ ...form, qty: e.target.value })} style={inputStyle} />
        <input type="number" placeholder="Purchase price per item" value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} style={inputStyle} />
        <input placeholder="Supplier bill no / note" value={form.invoiceNumber} onChange={(e) => setForm({ ...form, invoiceNumber: e.target.value })} style={inputStyle} />
        <button style={primaryBtn}>Save Purchase</button>
      </form>

      <div style={panelStyle}>
        <h3>Purchase History</h3>
        <table style={tableStyle}>
          <thead>
            <tr><th style={thStyle}>Date</th><th style={thStyle}>Product</th><th style={thStyle}>Qty</th><th style={thStyle}>Buy Price</th><th style={thStyle}>Sell Price</th><th style={thStyle}>Bill No</th></tr>
          </thead>
          <tbody>
            {purchases.map((purchase) => (
              <tr key={purchase.id}>
                <td style={tdStyle}>{String(purchase.purchaseDate || purchase.createdDate || "").slice(0, 10)}</td>
                <td style={tdStyle}>{purchase.productdata?.productName || "-"}</td>
                <td style={tdStyle}>{purchase.quantity}</td>
                <td style={tdStyle}>Rs. {purchase.purchasePrice || 0}</td>
                <td style={tdStyle}>Rs. {purchase.productdata?.sellPrice || 0}</td>
                <td style={tdStyle}>{purchase.invoiceNumber || "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

const pageStyle = { padding: "30px", background: "#f1f5f9", minHeight: "100vh" };
const titleStyle = { margin: "0 0 20px", color: "#0f172a" };
const panelStyle = { background: "white", padding: "18px", borderRadius: "10px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", marginBottom: "18px", overflowX: "auto" };
const inputStyle = { padding: "11px", marginRight: "10px", marginBottom: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "190px" };
const primaryBtn = { padding: "11px 18px", background: "#0f2963", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const secondaryBtn = { padding: "10px 14px", background: "#ffffff", color: "#0f2963", border: "1px solid #0f2963", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const dangerBtn = { padding: "8px 10px", background: "#dc2626", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white" };
const tdStyle = { padding: "10px", borderBottom: "1px solid #e2e8f0" };

export default Purchase;

function Code128Barcode({ value }) {
  const svgRef = useRef(null);
  useEffect(() => {
    if (svgRef.current && value) JsBarcode(svgRef.current, String(value), { format: "CODE128", displayValue: true, height: 38, margin: 4, fontSize: 11, width: 1.3 });
  }, [value]);
  return <svg ref={svgRef} role="img" aria-label={`Barcode ${value}`} style={{ maxWidth: 190, width: "100%", height: "auto", display: "block", margin: "6px auto" }} />;
}

function parseBillLines(text, products) {
  const parsed = [];
  const ignored = /^(?:invoice|bill|date|gst|tax|total|subtotal|amount|description|item|qty|quantity|rate|price|page|phone|mobile|address|customer|supplier|terms|thank)/i;
  for (const [index, rawLine] of String(text || "").split(/\r?\n/).entries()) {
    const line = rawLine.replace(/[|]/g, " ").replace(/\s+/g, " ").trim()
      .replace(/^\d{1,3}\s*[.)-]\s*/, "").replace(/^\d{1,3}\s+(?=[\p{L}])/u, "");
    if (!line || ignored.test(line)) continue;
    const numbers = [...line.matchAll(/\d[\d,]*(?:\.\d{1,2})?/g)];
    if (numbers.length < 2) continue;
    const quantityIndex = numbers.length >= 3 ? numbers.length - 3 : numbers.length - 2;
    const priceIndex = numbers.length >= 3 ? numbers.length - 2 : numbers.length - 1;
    const description = line.slice(0, numbers[quantityIndex].index)
      .replace(/^\s*\d+\s*[.)-]?\s*/, "")
      .replace(/[^\p{L}\p{N}#/+(). -]/gu, " ")
      .replace(/\s+/g, " ").trim();
    if (description.length < 2 || ignored.test(description)) continue;
    const values = numbers.map((match) => Number(match[0].replace(/,/g, ""))).filter(Number.isFinite);
    const qty = values[quantityIndex];
    const buyPrice = values[priceIndex];
    if (!(qty > 0 && qty <= 100000 && buyPrice >= 0 && buyPrice < 100000000)) continue;
    const match = products.find((product) => String(product.productName || "").trim().toLowerCase() === description.toLowerCase());
    parsed.push({
      id: `ocr-${index}-${Date.now()}`, productId: match ? String(match.id) : "", productName: match?.productName || description,
      quantity: String(Math.trunc(qty)), purchasePrice: String(buyPrice), sellPrice: match?.sellPrice ? String(match.sellPrice) : ""
    });
  }
  return parsed;
}
