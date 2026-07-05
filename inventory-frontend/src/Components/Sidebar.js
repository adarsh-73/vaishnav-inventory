import { NavLink } from "react-router-dom";

const navItems = [
  { to: "/", icon: "D", label: "Dashboard" },
  { to: "/billing", icon: "B", label: "Billing" },
  { to: "/products", icon: "P", label: "Products" },
  { to: "/accessories-catalog", icon: "X", label: "Catalog" },
  { to: "/price-enquiry", icon: "₹", label: "Price Enquiry" },
  { to: "/old-bills", icon: "O", label: "Old Bills" },
  { to: "/daily-book", icon: "K", label: "Daily Book" },
  { to: "/purchase", icon: "S", label: "Purchase" },
  { to: "/monthly-statement", icon: "M", label: "Statement" },
  { to: "/quotation", icon: "Q", label: "Quotation" },
  { to: "/attendance", icon: "A", label: "Attendance" },
  { to: "/crypto-trading", icon: "C", label: "Crypto" }
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
  background: "linear-gradient(180deg, #071635 0%, #0f172a 68%, #111827 100%)",
  color: "white",
  padding: "20px",
  boxSizing: "border-box",
  borderRight: "1px solid rgba(249,217,137,0.2)",
  position: "sticky",
  top: 0,
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
  boxShadow: "0 8px 18px rgba(0,0,0,0.28)",
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
  padding: "13px 14px",
  background: "rgba(30,41,59,0.78)",
  border: "1px solid rgba(148,163,184,0.16)",
  borderRadius: "7px",
  fontSize: "15px",
  fontWeight: "700",
  boxShadow: "inset 0 1px 0 rgba(255,255,255,0.04)",
};

export default Sidebar;
