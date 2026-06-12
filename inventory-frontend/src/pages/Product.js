import React, { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { API_BASE } from "../utils/api";

function Products() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [products, setProducts] = useState([]);
  const [query, setQuery] = useState("");
  const [lowStockOnly, setLowStockOnly] = useState(false);
  const [highlightProductId, setHighlightProductId] = useState("");
  const [loading, setLoading] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState(emptyProductForm([]));

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
    if (editingId) return;
    setForm((current) => {
      if (current.serialNumber || current.barcode) return current;
      const nextCode = getNextProductCode(products);
      return { ...current, serialNumber: nextCode, barcode: nextCode };
    });
  }, [editingId, products]);

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
    let nextProducts = lowStockOnly
      ? products.filter((product) => Number(product.quantity || 0) <= Number(product.minimumStock || 1))
      : products;

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
  }, [products, query, lowStockOnly]);

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
      const freshProducts = await loadProducts();
      setForm(emptyProductForm(freshProducts || products));
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
    setForm(emptyProductForm(products));
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

      <form onSubmit={handleSubmit} style={panelStyle}>
        <input placeholder="Product name" value={form.productName} onChange={(e) => setForm({ ...form, productName: e.target.value })} style={inputStyle} />
        <input placeholder="Brand" value={form.brand} onChange={(e) => setForm({ ...form, brand: e.target.value })} style={inputStyle} />
        <input placeholder="Make" value={form.make} onChange={(e) => setForm({ ...form, make: e.target.value })} style={inputStyle} />
        <input placeholder="Model" value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} style={inputStyle} />
        <input placeholder="SR No. (Auto)" value={form.serialNumber} onChange={(e) => setForm({ ...form, serialNumber: e.target.value })} style={inputStyle} />
        <input placeholder="Category" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} style={inputStyle} />
        <input placeholder="Barcode (Auto)" value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })} style={inputStyle} />
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
                  <td data-label="SR / Barcode" style={tdStyle}>{product.serialNumber || product.barcode || "-"}</td>
                  <td data-label="Stock" style={tdStyle}>{product.quantity ?? 0}</td>
                  <td data-label="Min" style={tdStyle}>{product.minimumStock ?? 1}</td>
                  <td data-label="Buy" style={tdStyle}>Rs. {product.purchasePrice ?? 0}</td>
                  <td data-label="Sell" style={tdStyle}>Rs. {product.sellPrice ?? 0}</td>
                  <td data-label="Description" style={tdStyle}>{product.description || "-"}</td>
                  <td data-label="Action" style={tdStyle}>
                    <button onClick={() => handleEdit(product)} style={secondaryBtn}>Edit</button>
                    <button onClick={() => handleAddStock(product)} style={stockBtn}>Add Stock</button>
                    <button onClick={() => handleDelete(product)} style={dangerBtn}>Delete</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function emptyProductForm(products) {
  const nextCode = getNextProductCode(products);
  return {
    productName: "",
    brand: "",
    make: "",
    model: "",
    serialNumber: nextCode,
    category: "",
    barcode: nextCode,
    quantity: "",
    minimumStock: "1",
    purchasePrice: "",
    sellPrice: "",
    productLocation: "",
    description: ""
  };
}

function getNextProductCode(products) {
  const maxCode = (products || []).reduce((max, product) => {
    const serial = Number(String(product.serialNumber || "").replace(/\D/g, ""));
    const barcode = Number(String(product.barcode || "").replace(/\D/g, ""));
    return Math.max(max, Number.isFinite(serial) ? serial : 0, Number.isFinite(barcode) ? barcode : 0);
  }, 0);

  return String(maxCode + 1);
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

export default Products;
