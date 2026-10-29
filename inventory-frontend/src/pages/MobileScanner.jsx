import React, { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { API_BASE } from "../utils/api";

export default function MobileScanner() {
  const [searchParams] = useSearchParams();
  const [stationToken] = useState(() => {
    const fromLink = searchParams.get("pair");
    if (fromLink) localStorage.setItem("vaishnav_scanner_station", fromLink);
    return fromLink || localStorage.getItem("vaishnav_scanner_station") || "";
  });
  const [manualCode, setManualCode] = useState("");
  const [product, setProduct] = useState(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [cameraOn, setCameraOn] = useState(false);
  const videoRef = useRef(null);
  const controlsRef = useRef(null);
  const lastScanRef = useRef({ code: "", at: 0 });

  useEffect(() => () => {
    controlsRef.current?.stop();
    const stream = videoRef.current?.srcObject;
    stream?.getTracks?.().forEach((track) => track.stop());
  }, []);

  const submitCode = async (rawCode) => {
    const barcode = String(rawCode || "").trim();
    if (!barcode || busy) return;
    setBusy(true);
    setProduct(null);
    setMessage("Product dhoondh rahe hain...");
    try {
      const response = await fetch(`${API_BASE}/scanner-bridge/mobile/scan`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stationToken, barcode })
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || result.error || "Product nahi mila ya stock khatam hai.");
      setProduct(result.product);
      setMessage(result.addedToBill
        ? "Laptop ke khule Billing bill mein add ho gaya."
        : "Laptop se connection active nahi hai. Product ki details neeche dikh rahi hain; bill mein jodne ke liye laptop par Billing kholkar uska pairing QR scan karein.");
      setManualCode("");
    } catch (error) {
      setMessage(error.message || "Scan process nahi hua. Connection check karein.");
    } finally {
      setBusy(false);
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
      const reader = new BrowserMultiFormatReader();
      controlsRef.current = await reader.decodeFromConstraints({ video: { facingMode: { ideal: "environment" } } }, videoRef.current, (result) => {
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
        <p style={styles.help}>Phone camera se product scan karein. Laptop connected ho aur Billing khula ho toh item us bill mein turant aa jayega. Connection na ho tab bhi product description aur sell price yahan dikhegi.</p>

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
          {product.description && <p style={{ whiteSpace: "pre-wrap" }}>{product.description}</p>}
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
  video: { width: "100%", maxHeight: 390, objectFit: "cover", background: "#111827", borderRadius: 12, margin: "14px 0" },
  actions: { display: "flex", gap: 10, margin: "14px 0" },
  primary: { flex: 1, border: 0, borderRadius: 10, padding: "14px 16px", background: "#0f2963", color: "white", fontWeight: 700, fontSize: 16 },
  secondary: { border: "1px solid #0f2963", borderRadius: 10, padding: "12px 16px", background: "white", color: "#0f2963", fontWeight: 700 },
  manualForm: { display: "flex", gap: 8 },
  input: { minWidth: 0, flex: 1, border: "1px solid #cbd5e1", borderRadius: 10, padding: 12, fontSize: 16 },
  status: { background: "#eff6ff", color: "#1e3a8a", padding: 12, borderRadius: 10, marginTop: 14 },
  connectedHint: { background: "#f0fdf4", color: "#166534", padding: 12, borderRadius: 10, marginTop: 14 },
  error: { background: "#fff1f2", color: "#9f1239", padding: 12, borderRadius: 10, marginTop: 14 },
  productCard: { marginTop: 16, padding: 16, border: "1px solid #bbf7d0", background: "#f0fdf4", borderRadius: 12, color: "#14532d" },
  price: { fontSize: 22, fontWeight: 800, marginTop: 8 },
  stock: { marginTop: 6 },
  code: { color: "#64748b", marginTop: 10, fontSize: 12 }
};
