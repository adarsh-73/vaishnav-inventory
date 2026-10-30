import React, { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { API_BASE } from "../utils/api";
import { BarcodeFormat, DecodeHintType } from "@zxing/library";

export default function MobileScanner() {
  const [searchParams] = useSearchParams();
  const [stationToken] = useState(() => {
    const fromLink = searchParams.get("pair");
    if (fromLink) localStorage.setItem("vaishnav_scanner_station", fromLink);
    return fromLink || localStorage.getItem("vaishnav_scanner_station") || "";
  });
  const [manualCode, setManualCode] = useState("");
  const [product, setProduct] = useState(null);
  const [scannedCode, setScannedCode] = useState("");
  const [showSellPrice, setShowSellPrice] = useState(true);
  const [productCache, setProductCache] = useState(() => {
    try { return JSON.parse(localStorage.getItem("vaishnav_product_scan_cache") || "[]"); }
    catch { return []; }
  });
  const [productCacheReady, setProductCacheReady] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [cameraOn, setCameraOn] = useState(false);
  const [unmatchedCode, setUnmatchedCode] = useState(false);
  const [selectedLinkProduct, setSelectedLinkProduct] = useState("");
  const [linkingCode, setLinkingCode] = useState(false);
  const videoRef = useRef(null);
  const controlsRef = useRef(null);
  const lastScanRef = useRef({ code: "", at: 0 });
  const productCacheRef = useRef(productCache);
  const scanSequenceRef = useRef(0);
  const pendingRequestsRef = useRef(0);

  useEffect(() => {
    let active = true;
    fetch(API_BASE + "/products")
      .then((response) => { if (!response.ok) throw new Error("Product list unavailable"); return response.json(); })
      .then((rows) => {
        if (!active || !Array.isArray(rows)) return;
        productCacheRef.current = rows;
        setProductCache(rows);
        localStorage.setItem("vaishnav_product_scan_cache", JSON.stringify(rows));
        setProductCacheReady(true);
      })
      .catch(() => { if (active) setProductCacheReady(productCacheRef.current.length > 0); });
    return () => { active = false; };
  }, []);

  useEffect(() => () => {
    controlsRef.current && controlsRef.current.stop();
    const stream = videoRef.current && videoRef.current.srcObject;
    if (stream && stream.getTracks) stream.getTracks().forEach((track) => track.stop());
  }, []);

  const submitCode = async (rawCode) => {
    const barcode = String(rawCode || "").trim();
    if (!barcode) return;
    const scanSequence = ++scanSequenceRef.current;
    setScannedCode(barcode);
    setShowSellPrice(true);
    setUnmatchedCode(false);
    const cachedProduct = productCacheRef.current.find((row) => String(row.barcode || "").trim().toLowerCase() === barcode.toLowerCase());
    if (cachedProduct) {
      setProduct(cachedProduct);
      setMessage("Product details dikh rahi hain; latest backend price check ho raha hai.");
    } else {
      setProduct(null);
      setMessage("Product backend se check ho raha hai...");
    }
    pendingRequestsRef.current += 1;
    setBusy(true);
    try {
      const response = await fetch(API_BASE + "/scanner-bridge/mobile/scan", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stationToken, barcode })
      });
      let result = await response.json().catch(() => ({}));
      if (!response.ok && (response.status === 404 || response.status === 405)) {
        const fallback = await fetch(API_BASE + "/products/barcode/" + encodeURIComponent(barcode));
        if (fallback.ok) result = { product: await fallback.json(), available: true, addedToBill: false };
      }
      if (!result.product && cachedProduct && cachedProduct.id) {
        const saved = await fetch(API_BASE + "/products/" + cachedProduct.id);
        if (saved.ok) result = { product: await saved.json(), available: Number(cachedProduct.quantity) > 0, addedToBill: false };
      }
      if (!result.product) {
        const reason = result.message || result.error || "Barcode se product nahi mila";
        throw new Error(String(reason) + ". Barcode read: " + barcode);
      }
      const latest = result.product;
      const nextCache = [...productCacheRef.current.filter((row) => String(row.id) !== String(latest.id)), latest];
      productCacheRef.current = nextCache;
      setProductCache(nextCache);
      localStorage.setItem("vaishnav_product_scan_cache", JSON.stringify(nextCache));
      if (scanSequence === scanSequenceRef.current) {
        setProduct(latest);
        setMessage(result.addedToBill
          ? "Product bill mein add hua. Sell price yahan dikh rahi hai."
          : result.available === false
            ? "Product details mil gayi, lekin stock zero hai—bill mein add nahi hua."
            : "Product details aur backend sell price mil gayi.");
      }
      setManualCode("");
    } catch (error) {
      if (scanSequence === scanSequenceRef.current) {
        if (cachedProduct) {
          setProduct(cachedProduct);
          setMessage("Backend abhi respond nahi kar raha; saved product details dikhayi hain. Bill ke liye connection chahiye.");
        } else {
          setProduct(null);
          setMessage(error.message || "Scan process nahi hua. Connection check karein.");
          setUnmatchedCode(/nahi mila|not found|does not exist/i.test(error.message || ""));
        }
      }
    } finally {
      pendingRequestsRef.current = Math.max(0, pendingRequestsRef.current - 1);
      setBusy(pendingRequestsRef.current > 0);
    }
  };

  const startCamera = async () => {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      setMessage("Camera ke liye secure HTTPS link ko Chrome/Safari mein kholiye.");
      return;
    }
    setMessage("Camera permission maang rahe hain...");
    try {
      const { BrowserMultiFormatReader } = await import("@zxing/browser");
      const hints = new Map([
        [DecodeHintType.POSSIBLE_FORMATS, [BarcodeFormat.CODE_128, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF, BarcodeFormat.CODABAR, BarcodeFormat.QR_CODE]],
        [DecodeHintType.TRY_HARDER, true]
      ]);
      const reader = new BrowserMultiFormatReader(hints, { delayBetweenScanAttempts: 120, delayBetweenScanSuccess: 500 });
      controlsRef.current = await reader.decodeFromConstraints({ audio: false, video: {
        facingMode: { ideal: "environment" }, width: { min: 640, ideal: 1920 },
        height: { min: 480, ideal: 1080 }, aspectRatio: { ideal: 1.777 }
      } }, videoRef.current, (result) => {
        if (!result) return;
        const code = result.getText();
        const now = Date.now();
        if (lastScanRef.current.code === code && now - lastScanRef.current.at < 1600) return;
        lastScanRef.current = { code, at: now };
        submitCode(code);
      });
      const track = videoRef.current && videoRef.current.srcObject && videoRef.current.srcObject.getVideoTracks()[0];
      const capabilities = track && track.getCapabilities && track.getCapabilities();
      if (capabilities && capabilities.focusMode && capabilities.focusMode.includes("continuous")) {
        track.applyConstraints({ advanced: [{ focusMode: "continuous" }] }).catch(() => {});
      }
      setCameraOn(true);
      setMessage("Camera ready hai. Barcode ko frame mein seedha rakhein.");
    } catch (error) {
      setCameraOn(false);
      setMessage(error.name === "NotAllowedError" ? "Camera permission allow karein; phone par app HTTPS se kholna zaroori hai." : (error.message || "Camera start nahi hua."));
    }
  };

  const stopCamera = () => {
    controlsRef.current && controlsRef.current.stop();
    controlsRef.current = null;
    const stream = videoRef.current && videoRef.current.srcObject;
    if (stream && stream.getTracks) stream.getTracks().forEach((track) => track.stop());
    if (videoRef.current) videoRef.current.srcObject = null;
    setCameraOn(false);
  };

  const linkScannedCode = async () => {
    if (!selectedLinkProduct || !scannedCode) return;
    setLinkingCode(true);
    try {
      const response = await fetch(API_BASE + "/products/" + selectedLinkProduct, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ barcode: scannedCode })
      });
      if (!response.ok) throw new Error((await response.text()) || "Barcode product se link nahi hua");
      setMessage("Barcode product se link ho gaya; ab lookup kar rahe hain.");
      await submitCode(scannedCode);
    } catch (error) {
      setMessage(error.message || "Barcode link nahi hua.");
    } finally {
      setLinkingCode(false);
    }
  };

  return (
    <main className="mobile-scan-page" style={styles.shell}>
      <section style={styles.card}>
        <div style={styles.brand}>VAISHNAV INVENTORY</div>
        <h1 style={styles.heading}>Mobile Product Scanner</h1>
        <p style={styles.help}>Barcode scan hote hi product description aur sell price dikhegi. Billing khuli aur paired ho toh item bill mein add hoga.</p>
        <div style={styles.cacheHint}>{productCacheReady ? productCache.length + " products scan ke liye ready" : "Product list load ho rahi hai..."}</div>
        <div role="status" style={stationToken ? styles.connectedHint : styles.status}>
          {stationToken ? "Laptop pairing saved hai. Billing active hone par scan bill mein bheja jayega." : "Product check mode. Bill mein bhejne ke liye Billing page ka Phone Scanner QR scan karein."}
        </div>
        <video ref={videoRef} playsInline muted style={{ ...styles.video, display: cameraOn ? "block" : "none" }} />
        <div style={styles.actions}>
          {!cameraOn ? <button type="button" style={styles.primary} onClick={startCamera}>Camera se scan karein</button> : <button type="button" style={styles.secondary} onClick={stopCamera}>Camera band karein</button>}
        </div>
        <form style={styles.manualForm} onSubmit={(event) => { event.preventDefault(); submitCode(manualCode); }}>
          <input value={manualCode} onChange={(event) => setManualCode(event.target.value)} placeholder="Barcode manually type karein" style={styles.input} />
          <button type="submit" disabled={busy || !manualCode.trim()} style={styles.secondary}>Find</button>
        </form>
        {message && <div role="status" style={unmatchedCode ? styles.error : styles.status}>{busy ? "Product dhoondh rahe hain... " : ""}{message}</div>}
        {unmatchedCode && productCache.length > 0 && <div style={styles.linkPanel}>
          <strong>Ye code kisi product se match nahi hua.</strong>
          <p style={{ margin: "6px 0 10px" }}>Product select karke barcode link karein. Isse us product ka purana barcode replace hoga.</p>
          <div style={styles.manualForm}>
            <select value={selectedLinkProduct} onChange={(event) => setSelectedLinkProduct(event.target.value)} style={styles.input}>
              <option value="">Product select karein</option>
              {productCache.map((item) => <option key={item.id} value={item.id}>{item.productName} · current {item.barcode || "no barcode"}</option>)}
            </select>
            <button type="button" disabled={!selectedLinkProduct || linkingCode} onClick={linkScannedCode} style={styles.secondary}>{linkingCode ? "Linking..." : "Link barcode"}</button>
          </div>
        </div>}
        {product && <article style={styles.productCard}>
          <div style={styles.productName}>{product.productName}</div>
          <button type="button" onClick={() => setShowSellPrice((visible) => !visible)} style={styles.priceToggle}>{showSellPrice ? "Hide sell price" : "Show sell price (owner only)"}</button>
          {showSellPrice && <div style={styles.price}>₹ {Number(product.sellPrice || 0).toLocaleString("en-IN")}</div>}
          <div style={styles.stock}>Available quantity: {product.quantity ?? 0}</div>
          <p style={{ whiteSpace: "pre-wrap", marginTop: 12 }}>{product.description || product.productName}</p>
          <div style={styles.code}>Scanned barcode: {scannedCode}</div>
        </article>}
      </section>
    </main>
  );
}

const styles = {
  shell: { minHeight: "100vh", background: "#eef2f7", padding: "20px 14px", fontFamily: "Arial, sans-serif" },
  card: { maxWidth: 560, margin: "0 auto", background: "white", padding: 22, borderRadius: 18, boxShadow: "0 12px 34px rgba(15,41,99,.12)" },
  brand: { color: "#b28a35", fontSize: 12, fontWeight: 800, letterSpacing: 1.5 },
  heading: { color: "#0f2963", margin: "8px 0" },
  help: { color: "#526074", lineHeight: 1.5 },
  video: { width: "100%", maxHeight: 440, objectFit: "contain", background: "#111827", borderRadius: 12, margin: "14px 0" },
  actions: { display: "flex", gap: 10, margin: "14px 0" },
  primary: { flex: 1, border: 0, borderRadius: 10, padding: "14px 16px", background: "#0f2963", color: "white", fontWeight: 700, fontSize: 16 },
  secondary: { border: "1px solid #0f2963", borderRadius: 10, padding: "12px 16px", background: "white", color: "#0f2963", fontWeight: 700 },
  manualForm: { display: "flex", gap: 8 },
  input: { minWidth: 0, flex: 1, border: "1px solid #cbd5e1", borderRadius: 10, padding: 12, fontSize: 16 },
  status: { background: "#eff6ff", color: "#1e3a8a", padding: 12, borderRadius: 10, marginTop: 14 },
  connectedHint: { background: "#f0fdf4", color: "#166534", padding: 12, borderRadius: 10, marginTop: 14 },
  cacheHint: { color: "#64748b", fontSize: 13, marginTop: 10 },
  error: { background: "#fff1f2", color: "#9f1239", padding: 12, borderRadius: 10, marginTop: 14 },
  linkPanel: { background: "#fffbeb", color: "#92400e", padding: 14, borderRadius: 10, marginTop: 14, border: "1px solid #fde68a" },
  productCard: { marginTop: 16, padding: 16, border: "1px solid #bbf7d0", background: "#f0fdf4", borderRadius: 12, color: "#14532d" },
  productName: { fontSize: 19, fontWeight: 700 },
  price: { fontSize: 22, fontWeight: 800, marginTop: 8 },
  priceToggle: { marginTop: 10, border: "1px solid #14532d", borderRadius: 8, padding: "9px 12px", background: "white", color: "#14532d", fontWeight: 700 },
  stock: { marginTop: 6 },
  code: { color: "#64748b", marginTop: 10, fontSize: 12 }
};
