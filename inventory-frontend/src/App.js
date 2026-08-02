import { lazy, Suspense, useEffect, useState } from "react";
import { BrowserRouter, Navigate, Routes, Route } from "react-router-dom";
import Sidebar from "./Components/Sidebar";
import Dashboard from "./pages/Dashboard";

const Products = lazy(() => import("./pages/Product"));
const Purchase = lazy(() => import("./pages/Purchase"));
const BillingPage = lazy(() => import("./pages/BillingPage"));
const Quotation = lazy(() => import("./pages/Quotation"));
const SellingItem = lazy(() => import("./pages/Selling-Item"));
const DailyBook = lazy(() => import("./pages/DailyBook"));
const Attendance = lazy(() => import("./pages/Attendance"));
const OldBills = lazy(() => import("./pages/OldBills"));
const MonthlyStatement = lazy(() => import("./pages/MonthlyStatement"));

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
      <div className="app-shell" style={{ display: "flex" }}>
        <Sidebar />
        <div className="app-content" style={{ flex: 1 }}>
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
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        </div>
      </div>
    </BrowserRouter>

    
  );

  
}



export default App;

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
