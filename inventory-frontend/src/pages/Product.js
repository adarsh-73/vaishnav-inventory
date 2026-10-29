import React, { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { API_BASE } from "../utils/api";
import { QRCodeSVG } from "qrcode.react";
import JsBarcode from "jsbarcode";

function Products() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [products, setProducts] = useState([]);
  const [query, setQuery] = useState("");
  const [lowStockOnly, setLowStockOnly] = useState(false);
  const [showOutOfStock, setShowOutOfStock] = useState(false);
  const [barcodeProduct, setBarcodeProduct] = useState(null);
  const [highlightProductId, setHighlightProductId] = useState("");
  const [loading, setLoading] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState(emptyProductForm());

  const loadProducts = async () => {
    setLoading(true);
    try {
      const response = await fetch(`${API_BASE}/products`);
      if (!response.ok) throw new Error("Products load nahi hue");
      const data = await response.json();
      const list = Array.isArray(data) ? data : [];
      setProducts(list);
      return list;
    } catch (error) {
      alert(`Backend product load error: ${error.message}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadProducts();
  }, []);

  useEffect(() => {
    const lowStockParam = searchParams.get("lowStock");
    const qParam = searchParams.get("q");
    const productIdParam = searchParams.get("productId");

    setLowStockOnly(lowStockParam === "1");
    if (qParam) setQuery(qParam);
    setHighlightProductId(productIdParam || "");
  }, [searchParams]);

  const filteredProducts = useMemo(() => {
    const text = query.trim().toLowerCase();
    let nextProducts = showOutOfStock ? products : products.filter((product) => Number(product.quantity || 0) > 0);
    if (lowStockOnly) {
      nextProducts = nextProducts.filter((product) => Number(product.quantity || 0) <= Number(product.minimumStock || 1));
    }

    if (!text) return nextProducts;

    return nextProducts.filter((product) =>
      [
        product.productName,
        product.brand,
        product.make,
        product.model,
        product.serialNumber,
        product.category,
        product.barcode,
        product.description
      ]
        .filter(Boolean)
        .join(" ")
        .toLowerCase()
        .includes(text)
    );
  }, [products, query, lowStockOnly, showOutOfStock]);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!form.productName || form.quantity === "" || form.sellPrice === "") {
      return alert("Product name, quantity aur sell price bharna zaroori hai.");
    }

    const payload = {
      ...form,
      quantity: Number(form.quantity),
      minimumStock: Number(form.minimumStock || 1),
      purchasePrice: Number(form.purchasePrice || 0),
      sellPrice: Number(form.sellPrice)
    };

    try {
      const response = await fetch(editingId ? `${API_BASE}/products/${editingId}` : `${API_BASE}/products`, {
        method: editingId ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || "Product save nahi hua");
      }

      setEditingId(null);
      await loadProducts();
      setForm(emptyProductForm());
      alert(editingId ? "Product update ho gaya." : "Product database me save ho gaya.");
    } catch (error) {
      alert(`Product save error: ${error.message}`);
    }
  };

  const handleEdit = (product) => {
    setEditingId(product.id);
    setForm({
      productName: product.productName || "",
      brand: product.brand || "",
      make: product.make || "",
      model: product.model || "",
      serialNumber: product.serialNumber || "",
      category: product.category || "",
      barcode: product.barcode || "",
      quantity: product.quantity ?? "",
      minimumStock: product.minimumStock ?? 1,
      purchasePrice: product.purchasePrice ?? "",
      sellPrice: product.sellPrice ?? "",
      productLocation: product.productLocation || "",
      description: product.description || ""
    });
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const cancelEdit = () => {
    setEditingId(null);
    setForm(emptyProductForm());
  };

  const handleDelete = async (product) => {
    const confirmed = window.confirm(`${product.productName || "Product"} delete karna hai? Old bill/purchase history me product name safe rahega.`);
    if (!confirmed) return;

    try {
      const response = await fetch(`${API_BASE}/products/${product.id}`, { method: "DELETE" });
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || "Product delete nahi hua");
      }
      await loadProducts();
      alert("Product delete ho gaya.");
    } catch (error) {
      alert(`Delete error: ${error.message}`);
    }
  };

  const handleAddStock = (product) => {
    navigate(`/purchase?productId=${product.id}`);
  };

  return (
    <div style={pageStyle}>
      <h1 style={titleStyle}>Products & Stock Database</h1>
      <button type="button" onClick={() => navigate("/purchase?importPhoto=1")} style={{ ...secondaryBtn, marginBottom: 14 }}>
        📷 Supplier bill photo se products add karein
      </button>

      <form onSubmit={handleSubmit} style={panelStyle}>
        <input placeholder="Product name" value={form.productName} onChange={(e) => setForm({ ...form, productName: e.target.value })} style={inputStyle} />
        <input placeholder="Brand" value={form.brand} onChange={(e) => setForm({ ...form, brand: e.target.value })} style={inputStyle} />
        <input placeholder="Make" value={form.make} onChange={(e) => setForm({ ...form, make: e.target.value })} style={inputStyle} />
        <input placeholder="Model" value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} style={inputStyle} />
        <input placeholder="SR No. (Auto after save)" value={form.serialNumber} onChange={(e) => setForm({ ...form, serialNumber: e.target.value })} style={inputStyle} />
        <input placeholder="Category" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} style={inputStyle} />
        <input placeholder="Barcode (Auto after save)" value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })} style={inputStyle} />
        <input type="number" placeholder="Quantity" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} style={inputStyle} />
        <input type="number" placeholder="Minimum stock" value={form.minimumStock} onChange={(e) => setForm({ ...form, minimumStock: e.target.value })} style={inputStyle} />
        <input type="number" placeholder="Buy price / Cost price" value={form.purchasePrice} onChange={(e) => setForm({ ...form, purchasePrice: e.target.value })} style={inputStyle} />
        <input type="number" placeholder="Sell price" value={form.sellPrice} onChange={(e) => setForm({ ...form, sellPrice: e.target.value })} style={inputStyle} />
        <input placeholder="Location" value={form.productLocation} onChange={(e) => setForm({ ...form, productLocation: e.target.value })} style={inputStyle} />
        <textarea placeholder="Description" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} style={textAreaStyle} />
        <button style={primaryBtn}>{editingId ? "Update Product" : "Save Product"}</button>
        {editingId && <button type="button" onClick={cancelEdit} style={secondaryBtn}>Cancel Edit</button>}
      </form>

      <div style={panelStyle}>
        <input
          placeholder="Search product by name, make, model, SR no, barcode..."
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ ...inputStyle, width: "420px", maxWidth: "100%" }}
        />
        <button onClick={loadProducts} style={secondaryBtn}>Refresh</button>
        <button type="button" onClick={() => setLowStockOnly(!lowStockOnly)} style={lowStockOnly ? activeFilterBtn : secondaryBtn}>
          {lowStockOnly ? "Low Stock: ON" : "Low Stock Only"}
        </button>
        <button type="button" onClick={() => setShowOutOfStock(!showOutOfStock)} style={showOutOfStock ? activeFilterBtn : secondaryBtn}>
          {showOutOfStock ? "Out of Stock: Shown" : "Show Out of Stock"}
        </button>
        <span style={{ marginLeft: "10px", color: "#64748b" }}>{loading ? "Loading..." : `${filteredProducts.length} product found`}</span>

        <table className="responsive-table product-table" style={tableStyle}>
          <thead>
            <tr>
              <th style={thStyle}>Product</th>
              <th style={thStyle}>Make / Model</th>
              <th style={thStyle}>SR No.</th>
              <th style={thStyle}>Stock</th>
              <th style={thStyle}>Min</th>
              <th style={thStyle}>Buy</th>
              <th style={thStyle}>Sell</th>
              <th style={thStyle}>Description</th>
              <th style={thStyle}>Action</th>
            </tr>
          </thead>
          <tbody>
            {filteredProducts.map((product) => {
              const lowStock = Number(product.quantity || 0) <= Number(product.minimumStock || 1);
              const highlighted = String(product.id) === String(highlightProductId);
              return (
                <tr key={product.id} style={highlighted ? highlightedRow : lowStock ? lowStockRow : undefined}>
                  <td data-label="Product" style={tdStyle}>
                    <strong>{product.productName}</strong>
                    <div style={mutedText}>{product.brand || product.category || "-"}</div>
                  </td>
                  <td data-label="Make / Model" style={tdStyle}>{product.make || "-"} / {product.model || "-"}</td>
                  <td data-label="SR / Barcode" style={tdStyle}>
                    {product.serialNumber || product.barcode || "-"}
                    {product.barcode && <div style={{ marginTop: 6, textAlign: "center" }}><Code128Barcode value={product.barcode} width={150} /><QRCodeSVG value={product.barcode} size={60} level="M" /><div style={mutedText}>{product.barcode}</div></div>}
                  </td>
                  <td data-label="Stock" style={tdStyle}>{product.quantity ?? 0}</td>
                  <td data-label="Min" style={tdStyle}>{product.minimumStock ?? 1}</td>
                  <td data-label="Buy" style={tdStyle}>Rs. {product.purchasePrice ?? 0}</td>
                  <td data-label="Sell" style={tdStyle}>Rs. {product.sellPrice ?? 0}</td>
                  <td data-label="Description" style={tdStyle}>{product.description || "-"}</td>
                  <td data-label="Action" style={tdStyle}>
                    <button onClick={() => handleEdit(product)} style={secondaryBtn}>Edit</button>
                    <button onClick={() => setBarcodeProduct(product)} style={secondaryBtn}>QR Label</button>
                    <button onClick={() => handleAddStock(product)} style={stockBtn}>Add Stock</button>
                    <button onClick={() => handleDelete(product)} style={dangerBtn}>Delete</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {barcodeProduct && <div style={labelOverlay}>
        <style>{`@media print { body * { visibility: hidden !important; } .barcode-print-label, .barcode-print-label * { visibility: visible !important; } .barcode-print-label { position: fixed !important; left: 0 !important; top: 0 !important; width: 80mm !important; padding: 8mm !important; background: white !important; color: #111827 !important; } }`}</style>
        <div className="barcode-print-label" style={labelCard}>
          <button className="no-print" type="button" onClick={() => setBarcodeProduct(null)} style={{ ...dangerBtn, float: "right" }}>Close</button>
          <h2 style={{ margin: "0 0 8px", color: "#0f2963" }}>{barcodeProduct.productName}</h2>
          <Code128Barcode value={barcodeProduct.barcode} width={260} />
          <QRCodeSVG value={barcodeProduct.barcode} size={150} level="H" />
          <div style={{ fontWeight: 700, marginTop: 6 }}>Code: {barcodeProduct.barcode}</div>
          {barcodeProduct.description && <p>{barcodeProduct.description}</p>}
          <strong>Sell: ₹ {barcodeProduct.sellPrice ?? 0}</strong>
          <div className="no-print" style={{ marginTop: 12 }}>
            <button type="button" onClick={() => window.print()} style={primaryBtn}>Print QR Label</button>
          </div>
        </div>
      </div>}
    </div>
  );
}

function Code128Barcode({ value, width = 220 }) {
  const svgRef = React.useRef(null);
  useEffect(() => {
    if (svgRef.current && value) {
      JsBarcode(svgRef.current, String(value), { format: "CODE128", displayValue: true, height: 44, margin: 6, fontSize: 13, width: 1.5 });
    }
  }, [value]);
  return <svg ref={svgRef} role="img" aria-label={`Barcode ${value}`} style={{ maxWidth: width, width: "100%", height: "auto" }} />;
}

function emptyProductForm() {
  return {
    productName: "",
    brand: "",
    make: "",
    model: "",
    serialNumber: "",
    category: "",
    barcode: "",
    quantity: "",
    minimumStock: "1",
    purchasePrice: "",
    sellPrice: "",
    productLocation: "",
    description: ""
  };
}

const pageStyle = { padding: "30px", background: "#f1f5f9", minHeight: "100vh" };
const titleStyle = { margin: "0 0 20px", color: "#0f172a" };
const panelStyle = { background: "white", padding: "18px", borderRadius: "10px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", marginBottom: "18px", overflowX: "auto" };
const inputStyle = { padding: "11px", marginRight: "10px", marginBottom: "10px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "170px" };
const textAreaStyle = { ...inputStyle, width: "360px", minHeight: "42px", verticalAlign: "top", fontFamily: "Arial, sans-serif" };
const primaryBtn = { padding: "11px 18px", background: "#0f2963", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const secondaryBtn = { padding: "11px 18px", background: "#ffffff", color: "#0f2963", border: "1px solid #0f2963", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const activeFilterBtn = { ...secondaryBtn, background: "#fff7ed", color: "#9a3412", border: "1px solid #fb923c" };
const stockBtn = { padding: "7px 10px", background: "#128c7e", color: "white", border: "none", borderRadius: "5px", cursor: "pointer", marginLeft: "6px" };
const dangerBtn = { padding: "7px 10px", background: "#dc2626", color: "white", border: "none", borderRadius: "5px", cursor: "pointer" };
const tableStyle = { width: "100%", borderCollapse: "collapse", marginTop: "12px" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white", whiteSpace: "nowrap" };
const tdStyle = { padding: "10px", borderBottom: "1px solid #e2e8f0", verticalAlign: "top" };
const mutedText = { color: "#64748b", fontSize: "12px", marginTop: "3px" };
const lowStockRow = { background: "#fff7ed" };
const highlightedRow = { background: "#fef3c7", outline: "2px solid #f59e0b", outlineOffset: "-2px" };
const labelOverlay = { position: "fixed", inset: 0, zIndex: 1200, background: "rgba(15,23,42,.62)", display: "grid", placeItems: "center", padding: 16 };
const labelCard = { width: "min(100%, 360px)", padding: 24, borderRadius: 14, background: "white", textAlign: "center", boxShadow: "0 16px 44px rgba(0,0,0,.2)" };

export default Products;
