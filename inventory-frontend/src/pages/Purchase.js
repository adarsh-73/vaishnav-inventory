import React, { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../utils/api";

function Purchase() {
  const [searchParams] = useSearchParams();
  const [products, setProducts] = useState([]);
  const [purchases, setPurchases] = useState([]);
  const [form, setForm] = useState({ productId: "", qty: "", price: "", invoiceNumber: "" });

  const loadData = async () => {
    try {
      const [productData, purchaseData] = await Promise.all([
        apiRequest("/products"),
        apiRequest("/purchases")
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

  return (
    <div style={pageStyle}>
      <h1 style={titleStyle}>Purchase Entry</h1>
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
            {purchases.slice().reverse().slice(0, 10).map((purchase) => (
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
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white" };
const tdStyle = { padding: "10px", borderBottom: "1px solid #e2e8f0" };

export default Purchase;
