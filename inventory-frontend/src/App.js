import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { BrowserRouter, Navigate, Routes, Route, useLocation } from "react-router-dom";
import Sidebar from "./Components/Sidebar";
import Dashboard from "./pages/Dashboard";
import { API_BASE } from "./utils/api";

const Products = lazy(() => import("./pages/Product"));
const Purchase = lazy(() => import("./pages/Purchase"));
const BillingPage = lazy(() => import("./pages/BillingPage"));
const Quotation = lazy(() => import("./pages/Quotation"));
const SellingItem = lazy(() => import("./pages/Selling-Item"));
const DailyBook = lazy(() => import("./pages/DailyBook"));
const Attendance = lazy(() => import("./pages/Attendance"));
const OldBills = lazy(() => import("./pages/OldBills"));
const MonthlyStatement = lazy(() => import("./pages/MonthlyStatement"));
const MobileScanner = lazy(() => import("./pages/MobileScanner"));

const APP_PASSWORD = process.env.REACT_APP_APP_PASSWORD || "vaishnav";

function App() {
  const [isUnlocked, setIsUnlocked] = useState(() => localStorage.getItem("vaishnav_app_unlocked") === "1");

  useEffect(() => {
    if (!isUnlocked) return undefined;

    const preloadCorePages = () => {
      import("./pages/BillingPage");
      import("./pages/Product");
      import("./pages/OldBills");
      import("./pages/DailyBook");
    };

    if ("requestIdleCallback" in window) {
      const idleId = window.requestIdleCallback(preloadCorePages, { timeout: 2500 });
      return () => window.cancelIdleCallback?.(idleId);
    }

    const timeoutId = window.setTimeout(preloadCorePages, 1200);
    return () => window.clearTimeout(timeoutId);
  }, [isUnlocked]);

  if (!isUnlocked) {
    return <LoginGate onUnlock={() => setIsUnlocked(true)} />;
  }

  return (
    <BrowserRouter>
      <AppLayout />
    </BrowserRouter>

    
  );

  
}



export default App;

function AppLayout() {
  const location = useLocation();
  const isMobileScanner = location.pathname === "/mobile-scan";
  const scanBufferRef = useRef({ value: "", lastAt: 0, startedAt: 0, target: null, initialValue: "" });

  useEffect(() => {
    const handleKeyboardWedge = (event) => {
      const now = Date.now();
      const buffer = scanBufferRef.current;
      if (event.key === "Enter") {
        const looksLikeScanner = buffer.value.length >= 5 && now - buffer.lastAt < 220 && now - buffer.startedAt < 3000;
        scanBufferRef.current = { value: "", lastAt: 0, startedAt: 0, target: null, initialValue: "" };
        if (!looksLikeScanner || location.pathname === "/billing" || location.pathname === "/mobile-scan") return;
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation?.();
        const scannedCode = buffer.value;
        fetch(`${API_BASE}/products/barcode/${encodeURIComponent(scannedCode)}`)
          .then((response) => {
            if (!response.ok) return null;
            return response.json();
          })
          .then((product) => {
            if (!product) return;
            const target = buffer.target;
            if (target && "value" in target) {
              const prototype = Object.getPrototypeOf(target);
              const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
              setter?.call(target, buffer.initialValue);
              target.dispatchEvent(new Event("input", { bubbles: true }));
            }
            window.alert(`${product.productName} scan hua. Bill banane ke liye pehle Billing section kholiye.`);
          })
          .catch(() => {});
        return;
      }

      if (event.key.length !== 1 || event.ctrlKey || event.metaKey || event.altKey) return;
      if (now - buffer.lastAt > 180) {
        const target = event.target;
        scanBufferRef.current = {
          value: event.key, lastAt: now, startedAt: now, target,
          initialValue: target && "value" in target ? target.value : ""
        };
      } else if (buffer.value.length < 80) {
        buffer.value += event.key;
        buffer.lastAt = now;
      }
    };

    window.addEventListener("keydown", handleKeyboardWedge, true);
    return () => window.removeEventListener("keydown", handleKeyboardWedge, true);
  }, [location.pathname]);

  return (
    <div className="app-shell" style={{ display: "flex" }}>
      {!isMobileScanner && <Sidebar />}
      <div className="app-content" style={{ flex: 1, minWidth: 0 }}>
        <Suspense fallback={<div className="route-loading">Loading...</div>}>
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/products" element={<Products />} />
            <Route path="/purchase" element={<Purchase />} />
            <Route path="/billing" element={<BillingPage />} />
            <Route path="/old-bills" element={<OldBills />} />
            <Route path="/monthly-statement" element={<MonthlyStatement />} />
            <Route path="/quotation" element={<Quotation />} />
            <Route path="/selling-item" element={<SellingItem />} />
            <Route path="/daily-book" element={<DailyBook />} />
            <Route path="/attendance" element={<Attendance />} />
            <Route path="/mobile-scan" element={<MobileScanner />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </div>
    </div>
  );
}

function LoginGate({ onUnlock }) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  const handleSubmit = (event) => {
    event.preventDefault();
    if (password.trim() === APP_PASSWORD) {
      localStorage.setItem("vaishnav_app_unlocked", "1");
      onUnlock();
      return;
    }
    setError("Wrong password");
  };

  return (
    <main className="login-screen">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-logo">V</div>
        <h1>VAISHNAV</h1>
        <p>Car Wash And Accessories</p>
        <input
          type="password"
          placeholder="App password"
          value={password}
          onChange={(event) => {
            setPassword(event.target.value);
            setError("");
          }}
          autoFocus
        />
        {error && <div className="login-error">{error}</div>}
        <button type="submit">Open App</button>
      </form>
    </main>
  );
}
