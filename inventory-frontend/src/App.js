import { useState } from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import Sidebar from "./Components/Sidebar";
import Dashboard from "./pages/Dashboard";
import Products from "./pages/Product";
import Purchase from "./pages/Purchase";
import BillingPage from "./pages/BillingPage";     // ← Correct Import
import Quotation from "./pages/Quotation";
import SellingItem from "./pages/Selling-Item";
import DailyBook from "./pages/DailyBook";
import Attendance from "./pages/Attendance";
import OldBills from "./pages/OldBills";
import MonthlyStatement from "./pages/MonthlyStatement";
import CryptoTrading from "./pages/CryptoTrading";
import AccessoryCatalog from "./pages/AccessoryCatalog";

const APP_PASSWORD = process.env.REACT_APP_APP_PASSWORD || "vaishnav";

function App() {
  const [isUnlocked, setIsUnlocked] = useState(() => localStorage.getItem("vaishnav_app_unlocked") === "1");

  if (!isUnlocked) {
    return <LoginGate onUnlock={() => setIsUnlocked(true)} />;
  }

  return (
    <BrowserRouter>
      <div className="app-shell" style={{ display: "flex" }}>
        <Sidebar />
        <div className="app-content" style={{ flex: 1 }}>
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/products" element={<Products />} />
            <Route path="/accessories-catalog" element={<AccessoryCatalog />} />
            <Route path="/purchase" element={<Purchase />} />
            <Route path="/billing" element={<BillingPage />} />   {/* ← Yeh change kiya */}
            <Route path="/old-bills" element={<OldBills />} />
            <Route path="/monthly-statement" element={<MonthlyStatement />} />
            <Route path="/crypto-trading" element={<CryptoTrading />} />
            <Route path="/quotation" element={<Quotation />} />
            <Route path="/selling-item" element={<SellingItem />} />
            <Route path="/daily-book" element={<DailyBook />} />
            <Route path="/attendance" element={<Attendance />} />
          </Routes>
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
