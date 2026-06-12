import React, { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../utils/api";

function SellingItem() {
  const [products, setProducts] = useState([]);
  const [sales, setSales] = useState([]);
  const [form, setForm] = useState({ customerName: "", mobileNo: "", vehicleNo: "", productId: "", qty: "", price: "", paymentStatus: "paid" });

  const loadData = async () => {
    try {
      const [productData, invoiceData] = await Promise.all([
        apiRequest("/products"),
        apiRequest("/invoices")
      ]);
      setProducts(Array.isArray(productData) ? productData : []);
      setSales(Array.isArray(invoiceData) ? invoiceData : []);
    } catch (error) {
      alert(`Selling data load nahi hua: ${error.message}`);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const selectedProduct = useMemo(
    () => products.find((product) => product.id === Number(form.productId)),
    [form.productId, products]
  );

  const handleProductChange = (productId) => {
    const product = products.find((item) => item.id === Number(productId));
    setForm({ ...form, productId, price: product?.sellPrice || "" });
  };

  const handleSell = async (event) => {
    event.preventDefault();
    if (!selectedProduct || !form.qty || !form.price) return alert("Product, quantity aur price bharna zaroori hai.");

    const qty = Number(form.qty);
    const price = Number(form.price);
    if (qty <= 0) return alert("Quantity 1 ya usse zyada honi chahiye.");
    if (Number(selectedProduct.quantity || 0) < qty) return alert(`Stock kam hai. Available stock: ${selectedProduct.quantity || 0}`);

    try {
      await apiRequest("/invoices", {
        method: "POST",
        body: JSON.stringify({
          invoiceNumber: `S-${Date.now().toString().slice(-5)}`,
          customer: {
            customerName: form.customerName || "Walk-in Customer",
            mobileNumber: form.mobileNo || null
          },
          paidAmount: form.paymentStatus === "udhar" ? 0 : qty * price,
          remainingAmount: form.paymentStatus === "udhar" ? qty * price : 0,
          paymentMethod: "CASH/UPI",
          businessCategory: "accessories",
          invoiceItems: [{
            productInvoiceitem: { id: selectedProduct.id },
            description: selectedProduct.productName,
            quantity: qty,
            sellPrice: price
          }]
        })
      });

      setForm({ customerName: "", mobileNo: "", vehicleNo: "", productId: "", qty: "", price: "", paymentStatus: "paid" });
      await loadData();
      alert("Sale database me save ho gayi. Product stock automatic kam ho gaya.");
    } catch (error) {
      alert(`Sale save nahi hui: ${error.message}`);
    }
  };

  return (
    <div style={pageStyle}>
      <h1 style={titleStyle}>Selling Items</h1>

      <form onSubmit={handleSell} style={panelStyle}>
        <input placeholder="Customer Name" value={form.customerName} onChange={(e) => setForm({ ...form, customerName: e.target.value })} style={inputStyle} />
        <input placeholder="Mobile No" value={form.mobileNo} onChange={(e) => setForm({ ...form, mobileNo: e.target.value })} style={inputStyle} />
        <input placeholder="Vehicle Number" value={form.vehicleNo} onChange={(e) => setForm({ ...form, vehicleNo: e.target.value })} style={inputStyle} />
        <select value={form.productId} onChange={(e) => handleProductChange(e.target.value)} style={inputStyle}>
          <option value="">Select product</option>
          {products.map((product) => (
            <option key={product.id} value={product.id}>{product.productName} - Stock {product.quantity ?? 0}</option>
          ))}
        </select>
        <input type="number" placeholder="Quantity" value={form.qty} onChange={(e) => setForm({ ...form, qty: e.target.value })} style={inputStyle} />
        <input type="number" placeholder="Sale Price" value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} style={inputStyle} />
        <select value={form.paymentStatus} onChange={(e) => setForm({ ...form, paymentStatus: e.target.value })} style={inputStyle}>
          <option value="paid">Paid</option>
          <option value="udhar">Udhar</option>
        </select>
        <button style={primaryBtn}>Save Sale & Reduce Stock</button>
      </form>

      <div style={panelStyle}>
        <h3>Recent Sales</h3>
        <table style={tableStyle}>
          <thead>
            <tr><th style={thStyle}>Date</th><th style={thStyle}>Customer</th><th style={thStyle}>Bill</th><th style={thStyle}>Amount</th><th style={thStyle}>Status</th></tr>
          </thead>
          <tbody>
            {sales.slice().reverse().slice(0, 8).map((sale) => (
              <tr key={sale.id}>
                <td style={tdStyle}>{String(sale.invoiceDate || sale.createdDate || "").slice(0, 10)}</td>
                <td style={tdStyle}>{sale.customer?.customerName || "Walk-in Customer"}</td>
                <td style={tdStyle}>{sale.invoiceNumber}</td>
                <td style={tdStyle}>Rs. {sale.totalAmount || 0}</td>
                <td style={tdStyle}>{Number(sale.remainingAmount || 0) > 0 ? "udhar" : "paid"}</td>
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
const panelStyle = { background: "white", padding: "18px", borderRadius: "10px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", marginBottom: "18px" };
const inputStyle = { padding: "11px", marginRight: "10px", marginBottom: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "190px" };
const primaryBtn = { padding: "11px 18px", background: "#0f2963", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white" };
const tdStyle = { padding: "10px", borderBottom: "1px solid #e2e8f0" };

export default SellingItem;
