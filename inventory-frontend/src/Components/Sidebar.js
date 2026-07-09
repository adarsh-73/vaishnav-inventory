import { NavLink } from "react-router-dom";

const navItems = [
  { to: "/", icon: "D", label: "Dashboard" },
  { to: "/billing", icon: "B", label: "Billing", preload: () => import("../pages/BillingPage") },
  { to: "/products", icon: "P", label: "Products", preload: () => import("../pages/Product") },
  { to: "/old-bills", icon: "O", label: "Old Bills", preload: () => import("../pages/OldBills") },
  { to: "/daily-book", icon: "K", label: "Daily Book", preload: () => import("../pages/DailyBook") },
  { to: "/purchase", icon: "S", label: "Purchase", preload: () => import("../pages/Purchase") },
  { to: "/monthly-statement", icon: "M", label: "Statement", preload: () => import("../pages/MonthlyStatement") },
  { to: "/quotation", icon: "Q", label: "Quotation", preload: () => import("../pages/Quotation") },
  { to: "/attendance", icon: "A", label: "Attendance", preload: () => import("../pages/Attendance") },
  { to: "/crypto-trading", icon: "C", label: "Crypto", preload: () => import("../pages/CryptoTrading") }
];

function Sidebar() {
  return (
    <div className="app-sidebar" style={sidebarStyle}>
      <div className="sidebar-brand" style={brandBox}>
        <div style={logoShell}>
          <div style={logoRing}>
            <div style={logoLetter}>V</div>
          </div>
          <div style={logoRibbon}>DETAILING</div>
        </div>
        <div>
          <div style={brandName}>VAISHNAV</div>
          <div style={brandSub}>Car Wash And Accessories</div>
        </div>
      </div>

      <div className="sidebar-menu" style={menuListStyle}>
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === "/"}
            className={({ isActive }) => `sidebar-link${isActive ? " is-active" : ""}`}
            style={menuStyle}
            onPointerEnter={item.preload}
            onFocus={item.preload}
            onTouchStart={item.preload}
          >
            <span className="nav-icon" aria-hidden="true">{item.icon}</span>
            <span className="nav-label">{item.label}</span>
          </NavLink>
        ))}
      </div>
    </div>
  );
}

const sidebarStyle = {
  width: "280px",
  height: "100vh",
  background: "radial-gradient(circle at 28% 0%, rgba(249,217,137,0.16), transparent 32%), linear-gradient(180deg, #06122c 0%, #071635 46%, #0f172a 100%)",
  color: "white",
  padding: "20px",
  boxSizing: "border-box",
  borderRight: "1px solid rgba(249,217,137,0.2)",
  position: "sticky",
  top: 0,
  boxShadow: "16px 0 48px rgba(2, 6, 23, 0.16)",
};

const brandBox = {
  display: "flex",
  alignItems: "center",
  gap: "12px",
  padding: "12px 8px 22px",
  borderBottom: "1px solid rgba(226,232,240,0.14)",
  marginBottom: "22px",
};

const logoShell = {
  width: "68px",
  minWidth: "68px",
  height: "78px",
  position: "relative",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
};

const logoRing = {
  width: "56px",
  height: "56px",
  borderRadius: "50%",
  background: "linear-gradient(135deg, #c49a45, #f9d989 45%, #9c742a)",
  padding: "4px",
  boxShadow: "0 12px 26px rgba(0,0,0,0.32), 0 0 0 1px rgba(249,217,137,0.22)",
};

const logoLetter = {
  width: "100%",
  height: "100%",
  borderRadius: "50%",
  background: "radial-gradient(circle at 35% 25%, #203a72, #071635 70%)",
  border: "1.5px solid rgba(249,217,137,0.65)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  color: "#f9d989",
  fontFamily: "Georgia, serif",
  fontSize: "32px",
  fontWeight: "900",
};

const logoRibbon = {
  position: "absolute",
  bottom: "5px",
  background: "#f9d989",
  color: "#071635",
  borderRadius: "4px",
  padding: "3px 7px",
  fontSize: "8px",
  fontWeight: "900",
  letterSpacing: "0.8px",
};

const brandName = {
  color: "#ffffff",
  fontSize: "22px",
  fontWeight: "900",
  letterSpacing: "1.2px",
  lineHeight: 1,
};

const brandSub = {
  color: "#f9d989",
  fontSize: "12px",
  fontWeight: "700",
  lineHeight: 1.35,
  marginTop: "6px",
};

const menuListStyle = {
  display: "flex",
  flexDirection: "column",
  gap: "11px",
};

const menuStyle = {
  color: "#f8fafc",
  textDecoration: "none",
  padding: "14px 14px",
  background: "linear-gradient(135deg, rgba(30,41,59,0.86), rgba(15,23,42,0.78))",
  border: "1px solid rgba(226,232,240,0.13)",
  borderRadius: "14px",
  fontSize: "15px",
  fontWeight: "700",
  boxShadow: "inset 0 1px 0 rgba(255,255,255,0.06), 0 12px 24px rgba(2,6,23,0.14)",
};

export default Sidebar;
