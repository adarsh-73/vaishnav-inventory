import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  FiArchive,
  FiBox,
  FiChevronLeft,
  FiChevronRight,
  FiDownload,
  FiEdit3,
  FiFileText,
  FiImage,
  FiPlus,
  FiRefreshCw,
  FiSearch,
  FiUpload,
  FiX
} from "react-icons/fi";
import { API_BASE } from "../utils/api";
import "./AccessoryCatalog.css";

const emptyFitment = () => ({
  make: "",
  model: "",
  variant: "",
  yearFrom: "",
  yearTo: "",
  notes: ""
});

const emptyForm = () => ({
  name: "",
  localName: "",
  brand: "",
  category: "",
  partType: "Aftermarket",
  oemPartNumber: "",
  aftermarketPartNumber: "",
  hsnCode: "",
  wholesalePrice: "",
  retailPrice: "",
  bargainingPrice: "",
  stockQuantity: "0",
  minimumStock: "0",
  barcode: "",
  supplier: "",
  supplierPhone: "",
  photoUrl: "",
  sourceUrl: "",
  verificationStatus: "PRICE_AND_FITMENT_VERIFY",
  notes: "",
  active: true,
  fitments: [emptyFitment()]
});

function AccessoryCatalog() {
  const navigate = useNavigate();
  const [items, setItems] = useState([]);
  const [filters, setFilters] = useState({
    q: "",
    make: "",
    model: "",
    brand: "",
    category: "",
    inStock: false
  });
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const fileInputRef = useRef(null);

  const loadCatalog = useCallback(async () => {
    setLoading(true);
    setMessage("");
    try {
      const params = new URLSearchParams({
        page: String(page),
        size: "30"
      });
      Object.entries(filters).forEach(([key, value]) => {
        if (value !== "" && value !== false) params.set(key, String(value));
      });
      const response = await fetch(`${API_BASE}/accessories?${params.toString()}`);
      if (!response.ok) throw new Error(await response.text());
      const data = await response.json();
      setItems(Array.isArray(data.content) ? data.content : []);
      setTotalPages(Number(data.totalPages || 0));
      setTotalItems(Number(data.totalElements || 0));
    } catch (error) {
      setMessage(`Catalog load error: ${friendlyError(error)}`);
    } finally {
      setLoading(false);
    }
  }, [filters, page]);

  useEffect(() => {
    const timeout = window.setTimeout(loadCatalog, filters.q ? 350 : 0);
    return () => window.clearTimeout(timeout);
  }, [loadCatalog, filters.q]);

  const updateFilter = (key, value) => {
    setPage(0);
    setFilters((current) => ({ ...current, [key]: value }));
  };

  const openCreate = () => {
    setEditingId(null);
    setForm(emptyForm());
    setFormOpen(true);
  };

  const openEdit = (item) => {
    setEditingId(item.id);
    setForm({
      name: item.name || "",
      localName: item.localName || "",
      brand: item.brand || "",
      category: item.category || "",
      partType: item.partType || "Aftermarket",
      oemPartNumber: item.oemPartNumber || "",
      aftermarketPartNumber: item.aftermarketPartNumber || "",
      hsnCode: item.hsnCode || "",
      wholesalePrice: item.wholesalePrice ?? "",
      retailPrice: item.retailPrice ?? "",
      bargainingPrice: item.bargainingPrice ?? "",
      stockQuantity: item.stockQuantity ?? "0",
      minimumStock: item.minimumStock ?? "0",
      barcode: item.barcode || "",
      supplier: item.supplier || "",
      supplierPhone: item.supplierPhone || "",
      photoUrl: item.photoUrl || "",
      sourceUrl: item.sourceUrl || "",
      verificationStatus: item.verificationStatus || "PRICE_AND_FITMENT_VERIFY",
      notes: item.notes || "",
      active: item.active !== false,
      fitments: item.fitments?.length
        ? item.fitments.map((fitment) => ({
            make: fitment.make || "",
            model: fitment.model || "",
            variant: fitment.variant || "",
            yearFrom: fitment.yearFrom ?? "",
            yearTo: fitment.yearTo ?? "",
            notes: fitment.notes || ""
          }))
        : [emptyFitment()]
    });
    setFormOpen(true);
  };

  const closeForm = () => {
    if (saving) return;
    setFormOpen(false);
    setEditingId(null);
  };

  const saveItem = async (event) => {
    event.preventDefault();
    if (!form.name.trim()) return setMessage("Product name zaroori hai.");
    setSaving(true);
    setMessage("");
    try {
      const payload = {
        ...form,
        wholesalePrice: number(form.wholesalePrice),
        retailPrice: number(form.retailPrice),
        bargainingPrice: number(form.bargainingPrice),
        stockQuantity: integer(form.stockQuantity),
        minimumStock: integer(form.minimumStock),
        fitments: form.fitments
          .filter((fitment) => fitment.make || fitment.model || fitment.variant)
          .map((fitment) => ({
            ...fitment,
            yearFrom: nullableInteger(fitment.yearFrom),
            yearTo: nullableInteger(fitment.yearTo)
          }))
      };
      const response = await fetch(
        editingId ? `${API_BASE}/accessories/${editingId}` : `${API_BASE}/accessories`,
        {
          method: editingId ? "PUT" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        }
      );
      if (!response.ok) throw new Error(await response.text());
      setMessage(editingId ? "Catalog item update ho gaya." : "Catalog item save ho gaya.");
      closeForm();
      await loadCatalog();
    } catch (error) {
      setMessage(`Save error: ${friendlyError(error)}`);
    } finally {
      setSaving(false);
    }
  };

  const archiveItem = async (item) => {
    if (!window.confirm(`${item.name} ko catalog se archive karna hai?`)) return;
    try {
      const response = await fetch(`${API_BASE}/accessories/${item.id}`, { method: "DELETE" });
      if (!response.ok) throw new Error(await response.text());
      setMessage("Catalog item archive ho gaya.");
      await loadCatalog();
    } catch (error) {
      setMessage(`Archive error: ${friendlyError(error)}`);
    }
  };

  const addToStock = async (item) => {
    const raw = window.prompt(`${item.name}: kitna stock add karna hai?`, "1");
    if (raw === null) return;
    const quantity = Number(raw);
    if (!Number.isInteger(quantity) || quantity <= 0) {
      return setMessage("Stock quantity positive whole number honi chahiye.");
    }
    try {
      const response = await fetch(`${API_BASE}/accessories/${item.id}/add-to-stock`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ quantity })
      });
      if (!response.ok) throw new Error(await response.text());
      setMessage(`${quantity} item Products stock me add ho gaya.`);
      await loadCatalog();
    } catch (error) {
      setMessage(`Stock add error: ${friendlyError(error)}`);
    }
  };

  const quoteItem = (item) => {
    const rate = Number(item.bargainingPrice || item.retailPrice || 0);
    if (rate <= 0) {
      setMessage("Quotation se pehle Edit me customer price aur available part number save karo.");
      openEdit(item);
      return;
    }
    const fitment = item.fitments?.[0];
    const vehicle = [fitment?.make, fitment?.model, fitment?.variant]
      .filter(Boolean)
      .join(" ");
    const partNumber = item.oemPartNumber || item.aftermarketPartNumber || "";
    const description = [
      item.name,
      vehicle ? `for ${vehicle}` : "",
      partNumber ? `Part No. ${partNumber}` : ""
    ].filter(Boolean).join(" - ");
    const params = new URLSearchParams({
      desc: description,
      qty: "1",
      rate: String(rate)
    });
    navigate(`/quotation?${params.toString()}`);
  };

  const importCatalog = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    const body = new FormData();
    body.append("file", file);
    setLoading(true);
    setMessage("File import ho rahi hai...");
    try {
      const response = await fetch(`${API_BASE}/accessories/import`, { method: "POST", body });
      if (!response.ok) throw new Error(await response.text());
      const result = await response.json();
      const errorText = result.errors?.length ? ` First errors: ${result.errors.join("; ")}` : "";
      setMessage(
        `Import complete: ${result.created} new, ${result.updated} updated, ${result.failed} failed.${errorText}`
      );
      setPage(0);
      await loadCatalog();
    } catch (error) {
      setMessage(`Import error: ${friendlyError(error)}`);
    } finally {
      setLoading(false);
    }
  };

  const downloadTemplate = () => {
    window.open(`${API_BASE}/accessories/import-template`, "_blank", "noopener,noreferrer");
  };

  return (
    <main className="catalog-page">
      <header className="catalog-header">
        <div>
          <p className="catalog-kicker">VAISHNAV RETAIL MASTER</p>
          <h1>Accessories Catalog</h1>
          <p>Vehicle, local name, barcode ya brand se product aur selling price turant khojo.</p>
        </div>
        <div className="catalog-header-actions">
          <button className="catalog-button secondary" onClick={downloadTemplate}>
            <FiDownload /> Template
          </button>
          <button className="catalog-button secondary" onClick={() => fileInputRef.current?.click()}>
            <FiUpload /> Excel / CSV Import
          </button>
          <input
            ref={fileInputRef}
            className="catalog-file-input"
            type="file"
            accept=".xlsx,.xls,.csv"
            onChange={importCatalog}
          />
          <button className="catalog-button primary" onClick={openCreate}>
            <FiPlus /> New Item
          </button>
        </div>
      </header>

      <section className="catalog-search-band" aria-label="Catalog filters">
        <label className="catalog-main-search">
          <FiSearch aria-hidden="true" />
          <input
            value={filters.q}
            onChange={(event) => updateFilter("q", event.target.value)}
            placeholder="Product, local name, barcode, HSN, gaadi..."
          />
          {filters.q && (
            <button type="button" title="Clear search" onClick={() => updateFilter("q", "")}>
              <FiX />
            </button>
          )}
        </label>
        <input value={filters.make} onChange={(event) => updateFilter("make", event.target.value)} placeholder="Make: Mahindra" />
        <input value={filters.model} onChange={(event) => updateFilter("model", event.target.value)} placeholder="Model: Bolero" />
        <input value={filters.brand} onChange={(event) => updateFilter("brand", event.target.value)} placeholder="Brand" />
        <input value={filters.category} onChange={(event) => updateFilter("category", event.target.value)} placeholder="Category" />
        <label className="catalog-stock-toggle">
          <input
            type="checkbox"
            checked={filters.inStock}
            onChange={(event) => updateFilter("inStock", event.target.checked)}
          />
          In stock only
        </label>
        <button className="catalog-icon-button" title="Refresh catalog" onClick={loadCatalog}>
          <FiRefreshCw />
        </button>
      </section>

      <div className="catalog-status-row">
        <strong>{loading ? "Loading..." : `${totalItems.toLocaleString("en-IN")} accessories`}</strong>
        <span>Page {totalPages ? page + 1 : 0} of {totalPages}</span>
      </div>

      {message && <div className="catalog-message">{message}</div>}

      <section className="catalog-results">
        {!loading && !items.length ? (
          <div className="catalog-empty">
            <FiBox />
            <h2>Catalog abhi khali hai</h2>
            <p>Ye page sirf saved/imported items dikhata hai. Product stock aur sale price manage karne ke liye Products kholo.</p>
            <button className="catalog-button primary" onClick={() => navigate("/products")}>
              <FiSearch /> Products
            </button>
          </div>
        ) : (
          <table className="responsive-table catalog-table">
            <thead>
              <tr>
                <th>Product</th>
                <th>Fitment</th>
                <th>Brand / HSN</th>
                <th>Customer Price</th>
                <th>Profit</th>
                <th>Stock</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td data-label="Product">
                    <div className="catalog-product-cell">
                      <ProductPhoto item={item} />
                      <div>
                        <strong>{item.name}</strong>
                        <span>{item.localName || item.category || "Accessory"}</span>
                        <small>{item.oemPartNumber || item.aftermarketPartNumber || item.sku} · {item.barcode}</small>
                        <small className="catalog-verification">{verificationLabel(item.verificationStatus)}</small>
                      </div>
                    </div>
                  </td>
                  <td data-label="Fitment">
                    <FitmentSummary fitments={item.fitments} />
                  </td>
                  <td data-label="Brand / HSN">
                    <strong>{item.brand || "-"}</strong>
                    <span className="catalog-subtext">HSN {item.hsnCode || "-"}</span>
                    {item.sourceUrl && (
                      <a className="catalog-source-link" href={item.sourceUrl} target="_blank" rel="noreferrer">
                        Check source
                      </a>
                    )}
                  </td>
                  <td data-label="Customer Price">
                    <strong className="catalog-price">
                      {Number(item.retailPrice || 0) > 0 ? `Rs. ${money(item.retailPrice)}` : "Price verify"}
                    </strong>
                    {Number(item.bargainingPrice || 0) > 0 && (
                      <span className="catalog-subtext">Deal Rs. {money(item.bargainingPrice)}</span>
                    )}
                  </td>
                  <td data-label="Profit">
                    <strong className={Number(item.profitAmount || 0) >= 0 ? "catalog-profit" : "catalog-loss"}>
                      Rs. {money(item.profitAmount)}
                    </strong>
                    <span className="catalog-subtext">{Number(item.profitPercent || 0).toFixed(1)}%</span>
                  </td>
                  <td data-label="Stock">
                    <span className={`catalog-stock ${Number(item.stockQuantity || 0) > 0 ? "available" : "out"}`}>
                      {Number(item.stockQuantity || 0) > 0 ? `${item.stockQuantity} available` : "Not in stock"}
                    </span>
                  </td>
                  <td data-label="Action">
                    <div className="catalog-row-actions">
                      <button title="Add to quotation" onClick={() => quoteItem(item)}><FiFileText /></button>
                      <button title="Edit details" onClick={() => openEdit(item)}><FiEdit3 /></button>
                      <button title="Add to Products stock" onClick={() => addToStock(item)}><FiPlus /></button>
                      <button className="danger" title="Archive" onClick={() => archiveItem(item)}><FiArchive /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <nav className="catalog-pagination" aria-label="Catalog pages">
        <button disabled={page <= 0} onClick={() => setPage((current) => Math.max(current - 1, 0))}>
          <FiChevronLeft /> Previous
        </button>
        <span>{totalItems ? `${page * 30 + 1}-${Math.min((page + 1) * 30, totalItems)} of ${totalItems}` : "0 items"}</span>
        <button disabled={page + 1 >= totalPages} onClick={() => setPage((current) => current + 1)}>
          Next <FiChevronRight />
        </button>
      </nav>

      {formOpen && (
        <CatalogForm
          form={form}
          setForm={setForm}
          editingId={editingId}
          saving={saving}
          onClose={closeForm}
          onSubmit={saveItem}
        />
      )}
    </main>
  );
}

function CatalogForm({ form, setForm, editingId, saving, onClose, onSubmit }) {
  const setValue = (key, value) => setForm((current) => ({ ...current, [key]: value }));
  const setFitment = (index, key, value) => {
    setForm((current) => ({
      ...current,
      fitments: current.fitments.map((fitment, fitmentIndex) =>
        fitmentIndex === index ? { ...fitment, [key]: value } : fitment
      )
    }));
  };
  const addFitment = () => setForm((current) => ({ ...current, fitments: [...current.fitments, emptyFitment()] }));
  const removeFitment = (index) => {
    setForm((current) => ({
      ...current,
      fitments: current.fitments.length === 1
        ? [emptyFitment()]
        : current.fitments.filter((_, fitmentIndex) => fitmentIndex !== index)
    }));
  };

  const cost = number(form.wholesalePrice);
  const retail = number(form.retailPrice);
  const profit = retail - cost;
  const profitPercent = cost > 0 ? (profit / cost) * 100 : 0;

  return (
    <div className="catalog-modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <form className="catalog-modal" onSubmit={onSubmit}>
        <header>
          <div>
            <p>{editingId ? "UPDATE MASTER RECORD" : "NEW MASTER RECORD"}</p>
            <h2>{editingId ? "Edit Accessory" : "Add Accessory"}</h2>
          </div>
          <button type="button" className="catalog-modal-close" title="Close" onClick={onClose}><FiX /></button>
        </header>

        <section className="catalog-form-section">
          <h3>Product identity</h3>
          <div className="catalog-form-grid">
            <Field label="Product name *" value={form.name} onChange={(value) => setValue("name", value)} />
            <Field label="Local / market name" value={form.localName} onChange={(value) => setValue("localName", value)} />
            <Field label="Brand" value={form.brand} onChange={(value) => setValue("brand", value)} />
            <Field label="Category" value={form.category} onChange={(value) => setValue("category", value)} />
            <Field label="Part type" value={form.partType} onChange={(value) => setValue("partType", value)} />
            <Field label="OEM part number" value={form.oemPartNumber} onChange={(value) => setValue("oemPartNumber", value)} />
            <Field label="Aftermarket part number" value={form.aftermarketPartNumber} onChange={(value) => setValue("aftermarketPartNumber", value)} />
            <Field label="HSN code" value={form.hsnCode} onChange={(value) => setValue("hsnCode", value)} />
            <Field label="Barcode (blank = auto)" value={form.barcode} onChange={(value) => setValue("barcode", value)} />
            <Field label="Photo URL" value={form.photoUrl} onChange={(value) => setValue("photoUrl", value)} />
            <Field label="Source URL" value={form.sourceUrl} onChange={(value) => setValue("sourceUrl", value)} />
            <Field label="Verification status" value={form.verificationStatus} onChange={(value) => setValue("verificationStatus", value)} />
          </div>
        </section>

        <section className="catalog-form-section">
          <div className="catalog-form-heading">
            <h3>Vehicle fitment</h3>
            <button type="button" onClick={addFitment}><FiPlus /> Add vehicle</button>
          </div>
          <div className="catalog-fitment-list">
            {form.fitments.map((fitment, index) => (
              <div className="catalog-fitment-row" key={index}>
                <Field label="Make" value={fitment.make} onChange={(value) => setFitment(index, "make", value)} />
                <Field label="Model" value={fitment.model} onChange={(value) => setFitment(index, "model", value)} />
                <Field label="Variant" value={fitment.variant} onChange={(value) => setFitment(index, "variant", value)} />
                <Field label="Year from" type="number" value={fitment.yearFrom} onChange={(value) => setFitment(index, "yearFrom", value)} />
                <Field label="Year to" type="number" value={fitment.yearTo} onChange={(value) => setFitment(index, "yearTo", value)} />
                <button type="button" className="catalog-remove-fitment" title="Remove vehicle" onClick={() => removeFitment(index)}>
                  <FiX />
                </button>
              </div>
            ))}
          </div>
        </section>

        <section className="catalog-form-section">
          <h3>Pricing and stock</h3>
          <div className="catalog-form-grid">
            <Field label="Wholesale / cost" type="number" value={form.wholesalePrice} onChange={(value) => setValue("wholesalePrice", value)} />
            <Field label="Retail price" type="number" value={form.retailPrice} onChange={(value) => setValue("retailPrice", value)} />
            <Field label="Bargaining price" type="number" value={form.bargainingPrice} onChange={(value) => setValue("bargainingPrice", value)} />
            <Field label="Catalog stock" type="number" value={form.stockQuantity} onChange={(value) => setValue("stockQuantity", value)} />
            <Field label="Minimum stock" type="number" value={form.minimumStock} onChange={(value) => setValue("minimumStock", value)} />
            <div className="catalog-profit-preview">
              <span>Expected profit</span>
              <strong>Rs. {money(profit)} · {profitPercent.toFixed(1)}%</strong>
            </div>
          </div>
        </section>

        <section className="catalog-form-section">
          <h3>Supplier and notes</h3>
          <div className="catalog-form-grid">
            <Field label="Supplier" value={form.supplier} onChange={(value) => setValue("supplier", value)} />
            <Field label="Supplier phone" value={form.supplierPhone} onChange={(value) => setValue("supplierPhone", value)} />
            <label className="catalog-field wide">
              <span>Notes</span>
              <textarea value={form.notes} onChange={(event) => setValue("notes", event.target.value)} rows="3" />
            </label>
          </div>
        </section>

        <footer>
          <button type="button" className="catalog-button secondary" onClick={onClose}>Cancel</button>
          <button type="submit" className="catalog-button primary" disabled={saving}>
            {saving ? "Saving..." : editingId ? "Update Accessory" : "Save Accessory"}
          </button>
        </footer>
      </form>
    </div>
  );
}

function Field({ label, value, onChange, type = "text" }) {
  return (
    <label className="catalog-field">
      <span>{label}</span>
      <input type={type} value={value ?? ""} onChange={(event) => onChange(event.target.value)} min={type === "number" ? "0" : undefined} />
    </label>
  );
}

function ProductPhoto({ item }) {
  const [failed, setFailed] = useState(false);
  if (!item.photoUrl || failed) {
    return <div className="catalog-photo-placeholder"><FiImage /></div>;
  }
  return <img className="catalog-photo" src={item.photoUrl} alt="" onError={() => setFailed(true)} />;
}

function FitmentSummary({ fitments }) {
  if (!fitments?.length) return <span className="catalog-fitment-universal">Universal / verify fitment</span>;
  return (
    <div className="catalog-fitment-summary">
      {fitments.slice(0, 2).map((fitment) => (
        <span key={`${fitment.make}-${fitment.model}-${fitment.variant}-${fitment.yearFrom}`}>
          {[fitment.make, fitment.model, fitment.variant].filter(Boolean).join(" ")}
          {(fitment.yearFrom || fitment.yearTo) && (
            <small>{fitment.yearFrom || "?"}-{fitment.yearTo || "Now"}</small>
          )}
        </span>
      ))}
      {fitments.length > 2 && <em>+{fitments.length - 2} more</em>}
    </div>
  );
}

function number(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function verificationLabel(status) {
  if (status === "OFFICIAL_SOURCE_VERIFIED") return "Official source verified";
  if (status === "SUPPLIER_VERIFIED") return "Supplier verified";
  return "Price / fitment verify";
}

function integer(value) {
  return Math.max(0, Math.round(number(value)));
}

function nullableInteger(value) {
  return value === "" || value === null || value === undefined ? null : integer(value);
}

function money(value) {
  return Number(value || 0).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

function friendlyError(error) {
  const raw = error?.message || String(error);
  try {
    const parsed = JSON.parse(raw);
    return parsed.message || parsed.error || raw;
  } catch {
    return raw.length > 320 ? raw.slice(0, 320) : raw;
  }
}

export default AccessoryCatalog;
