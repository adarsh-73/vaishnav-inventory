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
  const [productCache, setProductCache] = useState(() => {
    try { return JSON.parse(localStorage.getItem("vaishnav_product_scan_cache") || "[]"); }
    catch { return []; }
  });
  const [productCacheReady, setProductCacheReady] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [cameraOn, setCameraOn] = useState(false);
  const videoRef = useRef(null);
  const controlsRef = useRef(null);
  const lastScanRef = useRef({ code: "", at: 0 });
  const productCacheRef = useRef(productCache);
  const scanSequenceRef = useRef(0);
  const pendingRequestsRef = useRef(0);

  useEffect(() => {
    let active = true;
    fetch(`${API_BASE}/products`)
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
    controlsRef.current?.stop();
    const stream = videoRef.current?.srcObject;
    stream?.getTracks?.().forEach((track) => track.stop());
  }, []);

  const submitCode = async (rawCode) => {
    const barcode = String(rawCode || "").trim();
    if (!barcode) return;
    const scanSequence = ++scanSequenceRef.current;
    const cachedProduct = productCacheRef.current.find((row) => String(row.barcode || "").trim().toLowerCase() === barcode.toLowerCase());
    if (cachedProduct) {
      setProduct(cachedProduct);
      setMessage("Product details turant dikhayi gayi; latest backend price check ho raha hai.");
    } else {
      setProduct(null);
      setMessage("Product backend se check ho raha hai...");
    }
    pendingRequestsRef.current += 1;
    setBusy(true);
    try {
      const response = await fetch(`${API_BASE}/scanner-bridge/mobile/scan`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stationToken, barcode })
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || result.error || "Product nahi mila ya stock khatam hai.");
      const latestProduct = result.product;
      const updatedCache = [...productCacheRef.current.filter((row) => String(row.barcode) !== String(latestProduct.barcode)), latestProduct];
      productCacheRef.current = updatedCache;
      setProductCache(updatedCache);
      localStorage.setItem("vaishnav_product_scan_cache", JSON.stringify(updatedCache));
      if (scanSequence === scanSequenceRef.current) {
        setProduct(latestProduct);
        setMessage(result.addedToBill
          ? "Latest backend price mil gaya; product laptop ke khule Billing bill mein add ho gaya."
          : result.available === false
            ? "Product details aur sell price dikh rahe hain, lekin stock zero hai—bill mein add nahi hua."
            : "Latest backend price mil gaya. Laptop connected nahi hai; details yahin dikh rahi hain.");
      }
      setManualCode("");
    } catch (error) {
      if (scanSequence === scanSequenceRef.current) {
        if (cachedProduct) {
          setProduct(cachedProduct);
          setMessage("Network/backend abhi respond nahi kar raha; saved product details dikhayi hain. Bill mein bhejne ke liye connection chahiye.");
        } else {
          setMessage(error.message || "Scan process nahi hua. Connection check karein.");
        }
      }
    } finally {
      pendingRequestsRef.current = Math.max(0, pendingRequestsRef.current - 1);
      setBusy(pendingRequestsRef.current > 0);
    }
  };

  const startCamera = async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setMessage("Camera ke liye secure HTTPS link ko Chrome/Safari mein kholiye.");
      return;
    }
    setMessage("Camera permission maang rahe hain...");
    try {
      const { BrowserMultiFormatReader } = await import("@zxing/browser");
      const hints = new Map([
        [DecodeHintType.POSSIBLE_FORMATS, [BarcodeFormat.CODE_128]],
        [DecodeHintType.TRY_HARDER, true]
      ]);
      const reader = new BrowserMultiFormatReader(hints, { delayBetweenScanAttempts: 120, delayBetweenScanSuccess: 600 });
      controlsRef.current = await reader.decodeFromConstraints({ video: {
        facingMode: { ideal: "environment" },
        width: { ideal: 1920 },
        height: { ideal: 1080 }
      } }, videoRef.current, (result) => {
        if (!result) return;
        const code = result.getText();
        const now = Date.now();
        if (lastScanRef.current.code === code && now - lastScanRef.current.at < 1600) return;
        lastScanRef.current = { code, at: now };
        submitCode(code);
      });
      setCameraOn(true);
      setMessage("Camera ready hai. Product ka QR/barcode frame mein laaiye.");
    } catch (error) {
      setCameraOn(false);
      setMessage(error.message || "Camera start nahi hua. Permission allow karke dobara try karein.");
    }
  };

  const stopCamera = () => {
    controlsRef.current?.stop();
    controlsRef.current = null;
    const stream = videoRef.current?.srcObject;
    stream?.getTracks?.().forEach((track) => track.stop());
    if (videoRef.current) videoRef.current.srcObject = null;
    setCameraOn(false);
  };

  return (
    <main className="mobile-scan-page" style={styles.shell}>
      <section style={styles.card}>
        <div style={styles.brand}>VAISHNAV INVENTORY</div>
        <h1 style={styles.heading}>Mobile Product Scanner</h1>
        <p style={styles.help}>Line barcode ko camera frame ke beech mein seedha rakhein. Scan hote hi product description aur sell price dikh jayegi; Billing khuli aur paired ho toh item bill mein bhi judega.</p>

        <div style={styles.cacheHint}>{productCacheReady ? `${productCache.length} products scan ke liye ready` : "Product list background mein refresh ho rahi hai; saved products ki scan details available hain."}</div>

        <div role="status" style={stationToken ? styles.connectedHint : styles.status}>
          {stationToken ? "Laptop pairing saved hai. Billing active hone par scan bill mein bheja jayega." : "Standalone mode: scan karne par product details dikhegi. Bill mein bhejne ke liye laptop ke Billing page ka pairing QR scan karein."}
        </div>

        <video ref={videoRef} playsInline muted style={{ ...styles.video, display: cameraOn ? "block" : "none" }} />

        <div style={styles.actions}>
          {!cameraOn
            ? <button type="button" style={styles.primary} onClick={startCamera}>Camera se scan karein</button>
            : <button type="button" style={styles.secondary} onClick={stopCamera}>Camera band karein</button>}
        </div>

        <form style={styles.manualForm} onSubmit={(event) => { event.preventDefault(); submitCode(manualCode); }}>
          <input value={manualCode} onChange={(event) => setManualCode(event.target.value)} placeholder="Barcode manually type karein" style={styles.input} />
          <button type="submit" disabled={busy || !manualCode.trim()} style={styles.secondary}>Find</button>
        </form>

        {message && <div role="status" style={message.includes("nahi mila") || message.includes("nahi hua") ? styles.error : styles.status}>{message}</div>}

        {product && <article style={styles.productCard}>
          <h2>{product.productName}</h2>
          <p style={{ whiteSpace: "pre-wrap" }}>{product.description || product.productName}</p>
          <div style={styles.price}>Sell price: ₹ {Number(product.sellPrice || 0).toLocaleString("en-IN")}</div>
          <div style={styles.stock}>Available stock: {product.quantity ?? 0}</div>
          <div style={styles.code}>Code: {product.barcode}</div>
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
  productCard: { marginTop: 16, padding: 16, border: "1px solid #bbf7d0", background: "#f0fdf4", borderRadius: 12, color: "#14532d" },
  price: { fontSize: 22, fontWeight: 800, marginTop: 8 },
  stock: { marginTop: 6 },
  code: { color: "#64748b", marginTop: 10, fontSize: 12 }
};
