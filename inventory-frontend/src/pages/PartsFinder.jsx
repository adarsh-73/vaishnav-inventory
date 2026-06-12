import React, { useMemo, useState } from "react";
import { API_BASE } from "../utils/api";

function PartsFinder() {
  const [form, setForm] = useState({ make: "", model: "", year: "", variant: "", part: "", position: "", detail: "", origin: "any" });
  const [lookup, setLookup] = useState(null);
  const [autoLookup, setAutoLookup] = useState(null);
  const [imagePreview, setImagePreview] = useState("");
  const [imageStatus, setImageStatus] = useState("");
  const [loading, setLoading] = useState(false);
  const [imageLoading, setImageLoading] = useState(false);

  const query = useMemo(() => {
    const expandedPart = expandLocalPartName(form.part.trim());
    return [form.make, form.model, form.year, form.variant, expandedPart, form.position, form.detail, form.origin !== "any" ? form.origin : ""]
      .map((item) => item.trim())
      .filter(Boolean)
      .join(" ");
  }, [form]);

  const hasSearch = Boolean(query);

  const searchMarket = async () => {
    if (!hasSearch) return alert("Make, model aur part daalo.");
    setLoading(true);
    try {
      const params = new URLSearchParams({
        make: form.make,
        model: form.model,
        year: form.year,
        variant: form.variant,
        part: form.part,
        position: form.position,
        detail: form.detail,
        origin: form.origin
      });
      const response = await fetch(`${API_BASE}/parts-finder/search?${params.toString()}`);
      if (!response.ok) throw new Error(await response.text());
      setLookup(await response.json());
      try {
        const autoResponse = await fetch(`http://localhost:8090/search?${params.toString()}`);
        const autoData = await autoResponse.json().catch(() => null);
        setAutoLookup(autoResponse.ok && autoData?.ok ? autoData : { ok: false, message: autoData?.message || "Automation service response fail hai. Service restart karo." });
      } catch {
        setAutoLookup({ ok: false, message: "Automation service start nahi hai. Backend folder me npm run boodmo chalao." });
      }
    } catch (error) {
      alert(`Parts finder search nahi hua: ${error.message}`);
    } finally {
      setLoading(false);
    }
  };

  const results = lookup?.results || [];
  const imageUrl = `https://www.google.com/search?tbm=isch&q=${encodeURIComponent(`${query} price India`)}`;

  const handleImageUpload = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith("image/")) return alert("Image file select karo.");
    const reader = new FileReader();
    reader.onload = () => {
      setImagePreview(String(reader.result || ""));
      setImageStatus("Image ready. AI Identify dabao, ya fields manually fill karke Search Market dabao.");
    };
    reader.readAsDataURL(file);
  };

  const identifyImage = async () => {
    if (!imagePreview) return alert("Pehle spare/vehicle part ki image upload karo.");
    setImageLoading(true);
    setImageStatus("Image identify ho raha hai...");
    try {
      const response = await fetch("http://localhost:8090/image-identify", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ image: imagePreview })
      });
      const data = await response.json();
      if (!response.ok || !data.ok) {
        setImageStatus(data.message || "Image identify nahi hua.");
        return;
      }
      const detected = data.detected || {};
      setForm((current) => ({
        ...current,
        make: detected.make || current.make,
        model: detected.model || current.model,
        year: detected.year || current.year,
        variant: detected.variant || current.variant,
        part: detected.part || current.part,
        position: detected.position || current.position,
        detail: detected.detail || current.detail,
        origin: detected.origin || current.origin
      }));
      setImageStatus(`Detected: ${[detected.make, detected.model, detected.part, detected.position, detected.detail].filter(Boolean).join(" | ") || "fields suggested"}`);
    } catch (error) {
      setImageStatus(`Image identify service nahi chali: ${error.message}`);
    } finally {
      setImageLoading(false);
    }
  };

  return (
    <div style={pageStyle}>
      <section style={heroStyle}>
        <div>
          <div style={eyebrowStyle}>Parts Finder</div>
          <h1 style={titleStyle}>Live Boodmo OEM automation</h1>
          <p style={subText}>Make, model, year, exact side/detail aur OEM choice daalo. App local browser service se Boodmo catalog kholkar closest product cards upar rakhega.</p>
        </div>
      </section>

      <div style={searchPanel}>
        <input placeholder="Make: Maruti, Hyundai..." value={form.make} onChange={(e) => setForm({ ...form, make: e.target.value })} style={inputStyle} />
        <input placeholder="Model: Swift, i20..." value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} style={inputStyle} />
        <input placeholder="Year: 2020" value={form.year} onChange={(e) => setForm({ ...form, year: e.target.value })} style={inputStyle} />
        <input placeholder="Variant / engine: B6 BS6, VXI..." value={form.variant} onChange={(e) => setForm({ ...form, variant: e.target.value })} style={inputStyle} />
        <input placeholder="Part local wording: fogg light, dicky shock..." value={form.part} onChange={(e) => setForm({ ...form, part: e.target.value })} style={{ ...inputStyle, minWidth: "300px", flex: 1 }} />
        <input placeholder="Side/position: front right, LH, rear..." value={form.position} onChange={(e) => setForm({ ...form, position: e.target.value })} style={inputStyle} />
        <input placeholder="Detail: assy, cover, bulb, kit..." value={form.detail} onChange={(e) => setForm({ ...form, detail: e.target.value })} style={inputStyle} />
        <select value={form.origin} onChange={(e) => setForm({ ...form, origin: e.target.value })} style={selectStyle}>
          <option value="any">OEM + Aftermarket</option>
          <option value="oem">OEM only</option>
          <option value="aftermarket">Aftermarket</option>
        </select>
        <button type="button" onClick={searchMarket} disabled={loading || !hasSearch} style={loading || !hasSearch ? disabledBtn : primaryBtn}>
          {loading ? "Searching..." : "Search Market"}
        </button>
      </div>

      <section style={imagePanel}>
        <div style={imageUploadBox}>
          <input type="file" accept="image/*" onChange={handleImageUpload} style={fileInputStyle} />
          <button type="button" onClick={identifyImage} disabled={imageLoading || !imagePreview} style={imageLoading || !imagePreview ? disabledBtn : imageAiBtn}>
            {imageLoading ? "Identifying..." : "AI Identify Image"}
          </button>
          <span style={imageStatusStyle}>{imageStatus || "Part photo upload karo. AI key set hogi to app fields auto-fill karega."}</span>
        </div>
        {imagePreview && <img src={imagePreview} alt="Uploaded spare" style={uploadedImageStyle} />}
      </section>

      <section style={summaryPanel}>
        <div>
          <span style={cardLabel}>Search Query</span>
          <strong style={queryTitle}>{query || "Make, model aur part daalo"}</strong>
          <div style={hintText}>Exact result ke liye: variant, side LH/RH, front/rear, assy/cover/bulb/kit aur OEM/aftermarket zaroor choose karo.</div>
          <div style={catalogHint}>Automation service: localhost:8090. Ye Boodmo vehicle catalog se matching category open karke product list laata hai.</div>
        </div>
        {hasSearch && <a href={imageUrl} target="_blank" rel="noreferrer" style={imageBtn}>Product Images</a>}
      </section>

      <div style={autoResultWrap}>
        {autoLookup && <AutoResultCard data={autoLookup} />}
      </div>

      <div style={resultGrid}>
        {results.map((item) => (
          <SourceCard key={item.source} item={item} />
        ))}
      </div>

      {lookup && results.length === 0 && (
        <div style={emptyPanel}>Koi source result nahi mila.</div>
      )}

      {!lookup && !autoLookup && (
        <div style={emptyPanel}>Search Market dabao, phir source-wise price cards yaha dikhenge.</div>
      )}
    </div>
  );
}

function AutoResultCard({ data }) {
  const links = Array.isArray(data.links) ? data.links : [];
  const products = Array.isArray(data.products) ? data.products : [];
  const steps = Array.isArray(data.steps) ? data.steps : [];

  return (
    <section style={{ ...priceCard, borderTopColor: data.ok ? "#128c7e" : "#dc2626" }}>
      <span style={badgeStyle}>Live Boodmo OEM Automation</span>
      <h2 style={priceTitle}>{data.ok ? "Automated catalog search" : "Automation unavailable"}</h2>
      <div style={data.price ? priceValue : pendingValue}>{data.price || "Exact price not found"}</div>
      {data.partNumber && <div style={partNumberStyle}>Part No: {data.partNumber}</div>}
      <p style={priceNote}>{data.note || data.message}</p>
      {data.image && <img src={data.image} alt="Boodmo result" style={autoImage} />}
      {steps.length > 0 && (
        <div style={stepBox}>
          {steps.slice(0, 5).map((step, index) => <span key={`${step}-${index}`} style={stepPill}>{step}</span>)}
        </div>
      )}
      {products.length > 0 && (
        <div style={productGrid}>
          {products.map((product, index) => (
            <article key={`${product.title}-${product.price}-${index}`} style={productCard}>
              {product.image && <img src={product.image} alt={product.title} style={productImage} />}
              <div style={productBody}>
                <strong style={productTitle}>{product.title}</strong>
                <span style={productPrice}>{product.price}</span>
                {product.mrp && <span style={productMeta}>MRP {product.mrp}</span>}
                {product.brand && <span style={productMeta}>{product.brand}</span>}
                {product.partNumber && <span style={productMeta}>Part No: {product.partNumber}</span>}
              </div>
            </article>
          ))}
        </div>
      )}
      <div style={linkStack}>
        {links.map((link, index) => (
          <a key={`${link.url}-${index}`} href={link.url} target="_blank" rel="noreferrer" style={smallLinkBtn}>
            {link.price ? `${link.price} - ` : ""}{link.title || "Open result"}
          </a>
        ))}
        {data.searchUrl && <a href={data.searchUrl} target="_blank" rel="noreferrer" style={sourceBtn}>Open Boodmo Search</a>}
        {data.catalogUrl && <a href={data.catalogUrl} target="_blank" rel="noreferrer" style={sourceBtn}>Open OEM Catalog</a>}
        {data.vehicleUrl && <a href={data.vehicleUrl} target="_blank" rel="noreferrer" style={sourceBtn}>Open Matched Catalog</a>}
      </div>
    </section>
  );
}

function SourceCard({ item }) {
  const hasPrice = Boolean(item.price);

  return (
    <section style={priceCard}>
      <span style={badgeStyle}>{item.source}</span>
      <h2 style={priceTitle}>{item.title}</h2>
      <div style={hasPrice ? priceValue : pendingValue}>{hasPrice ? item.price : "Price not exposed"}</div>
      <p style={priceNote}>{item.status}</p>
      <a href={item.url} target="_blank" rel="noreferrer" style={sourceBtn}>Open Source</a>
    </section>
  );
}

function expandLocalPartName(value) {
  const text = value.toLowerCase();
  const map = [
    [/fogg|fog|foug/, "fog lamp fog light"],
    [/dikki|dicky|boot/, "boot tailgate"],
    [/shocker|shock/, "shock absorber strut"],
    [/glass machine|window machine/, "power window regulator"],
    [/side mirror|orpvm|orvm/, "orvm side mirror"],
    [/bumper clip|clip/, "bumper retainer clip"],
    [/bumper/, "front rear bumper"],
    [/light/, "headlamp tail lamp light"],
    [/mat|7d/, "car floor mat"],
    [/guard/, "bumper guard body kit"],
    [/visor/, "door window visor"]
  ];

  const found = map.find(([pattern]) => pattern.test(text));
  return found ? `${value} ${found[1]}` : value;
}

const pageStyle = { padding: "28px", background: "#eef2f6", minHeight: "100vh", color: "#0f172a" };
const heroStyle = { background: "#0f2963", color: "#ffffff", borderRadius: "8px", padding: "24px", marginBottom: "18px", boxShadow: "0 12px 28px rgba(15,41,99,0.18)" };
const eyebrowStyle = { color: "#f9d989", fontSize: "12px", fontWeight: "900", textTransform: "uppercase", marginBottom: "6px" };
const titleStyle = { margin: 0, fontSize: "28px", fontWeight: "900" };
const subText = { color: "#dbeafe", margin: "8px 0 0", maxWidth: "820px" };
const searchPanel = { background: "#ffffff", padding: "16px", borderRadius: "8px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", marginBottom: "18px", display: "flex", flexWrap: "wrap", gap: "10px", alignItems: "center" };
const inputStyle = { padding: "11px", border: "1px solid #cbd5e1", borderRadius: "6px", minWidth: "170px", fontSize: "14px" };
const selectStyle = { ...inputStyle, background: "#ffffff", fontWeight: "800" };
const primaryBtn = { padding: "11px 15px", border: "none", borderRadius: "6px", background: "#0f2963", color: "#ffffff", fontWeight: "900", cursor: "pointer" };
const disabledBtn = { ...primaryBtn, opacity: 0.55, cursor: "not-allowed" };
const summaryPanel = { background: "#ffffff", padding: "16px", borderRadius: "8px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", marginBottom: "18px", display: "flex", justifyContent: "space-between", gap: "14px", alignItems: "center", flexWrap: "wrap" };
const imagePanel = { background: "#ffffff", padding: "14px 16px", borderRadius: "8px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)", marginBottom: "18px", display: "flex", gap: "14px", alignItems: "center", flexWrap: "wrap" };
const imageUploadBox = { display: "flex", gap: "10px", alignItems: "center", flexWrap: "wrap", flex: 1 };
const fileInputStyle = { ...inputStyle, background: "#f8fafc" };
const imageAiBtn = { padding: "11px 15px", border: "none", borderRadius: "6px", background: "#7c3aed", color: "#ffffff", fontWeight: "900", cursor: "pointer" };
const imageStatusStyle = { color: "#64748b", fontSize: "13px", fontWeight: "800" };
const uploadedImageStyle = { width: "120px", height: "90px", objectFit: "contain", border: "1px solid #cbd5e1", borderRadius: "8px", background: "#f8fafc" };
const cardLabel = { color: "#64748b", fontSize: "12px", fontWeight: "900", textTransform: "uppercase", marginBottom: "8px", display: "block" };
const queryTitle = { color: "#0f2963", fontSize: "22px", lineHeight: 1.25, overflowWrap: "anywhere" };
const hintText = { marginTop: "10px", color: "#64748b" };
const catalogHint = { marginTop: "8px", color: "#9a3412", fontWeight: "800", fontSize: "13px" };
const imageBtn = { padding: "10px 13px", background: "#0f5132", color: "#ffffff", borderRadius: "6px", textDecoration: "none", fontWeight: "900" };
const autoResultWrap = { marginBottom: "16px" };
const resultGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(250px, 1fr))", gap: "16px" };
const priceCard = { background: "#ffffff", borderRadius: "8px", border: "1px solid #e2e8f0", borderTop: "4px solid #c49a45", padding: "18px", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)" };
const badgeStyle = { display: "inline-block", background: "#fff7ed", color: "#9c742a", border: "1px solid #f9d989", padding: "5px 9px", borderRadius: "999px", fontSize: "12px", fontWeight: "900", textTransform: "uppercase" };
const priceTitle = { color: "#0f2963", fontSize: "18px", margin: "14px 0 8px" };
const priceValue = { color: "#0f5132", fontSize: "24px", fontWeight: "900", marginBottom: "10px" };
const pendingValue = { color: "#9a3412", fontSize: "20px", fontWeight: "900", marginBottom: "10px" };
const partNumberStyle = { color: "#334155", fontSize: "13px", fontWeight: "900", marginBottom: "10px" };
const priceNote = { color: "#64748b", margin: "0 0 14px", lineHeight: 1.45 };
const sourceBtn = { display: "inline-block", padding: "10px 13px", background: "#0f2963", color: "#ffffff", borderRadius: "6px", textDecoration: "none", fontWeight: "900" };
const autoImage = { width: "100%", maxHeight: "150px", objectFit: "contain", background: "#f8fafc", border: "1px solid #e2e8f0", borderRadius: "6px", marginBottom: "12px" };
const linkStack = { display: "flex", flexDirection: "column", gap: "8px" };
const smallLinkBtn = { display: "block", padding: "8px 10px", background: "#f8fafc", color: "#0f2963", border: "1px solid #cbd5e1", borderRadius: "6px", textDecoration: "none", fontWeight: "800", fontSize: "12px" };
const stepBox = { display: "flex", flexWrap: "wrap", gap: "6px", marginBottom: "12px" };
const stepPill = { background: "#e0f2fe", color: "#075985", border: "1px solid #bae6fd", borderRadius: "999px", padding: "5px 8px", fontSize: "11px", fontWeight: "800" };
const productGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: "10px", margin: "12px 0" };
const productCard = { border: "1px solid #e2e8f0", borderRadius: "8px", overflow: "hidden", background: "#f8fafc", display: "flex", minHeight: "116px" };
const productImage = { width: "78px", objectFit: "contain", background: "#ffffff", borderRight: "1px solid #e2e8f0", padding: "6px" };
const productBody = { padding: "9px", display: "flex", flexDirection: "column", gap: "4px", minWidth: 0 };
const productTitle = { color: "#0f172a", fontSize: "13px", lineHeight: 1.25 };
const productPrice = { color: "#0f5132", fontSize: "16px", fontWeight: "900" };
const productMeta = { color: "#64748b", fontSize: "11px", fontWeight: "800" };
const emptyPanel = { background: "#ffffff", borderRadius: "8px", padding: "22px", color: "#64748b", textAlign: "center", fontWeight: "800", boxShadow: "0 8px 20px rgba(15, 23, 42, 0.07)" };

export default PartsFinder;
