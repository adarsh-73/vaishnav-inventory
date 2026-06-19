import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { API_BASE } from "../utils/api";
import { printInvoiceElement } from "../utils/printInvoice";

function OldBills() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [invoices, setInvoices] = useState([]);
  const [dailyBookEntries, setDailyBookEntries] = useState([]);
  const [selectedInvoice, setSelectedInvoice] = useState(null);
  const [searchBy, setSearchBy] = useState("all");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [showReturnPanel, setShowReturnPanel] = useState(false);
  const [returnReason, setReturnReason] = useState("");
  const [returnQuantities, setReturnQuantities] = useState({});

  const loadInvoices = useCallback(async () => {
    setLoading(true);
    try {
      const [invoiceResponse, dailyBookResponse] = await Promise.all([
        fetch(`${API_BASE}/invoices`),
        fetch(`${API_BASE}/daily-book`)
      ]);
      if (!invoiceResponse.ok) throw new Error("Bills load nahi hue");
      if (!dailyBookResponse.ok) throw new Error("Daily book load nahi hua");
      const data = await invoiceResponse.json();
      const dailyBookData = await dailyBookResponse.json();
      const unique = uniqueInvoicesByNumber(Array.isArray(data) ? data : []);
      setInvoices(unique);
      setDailyBookEntries(Array.isArray(dailyBookData) ? dailyBookData : []);
      setSelectedInvoice((current) => current || unique[0] || null);
    } catch (error) {
      alert(`Old bills / udhar load error: ${error.message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadInvoices();
  }, [loadInvoices]);

  useEffect(() => {
    const qParam = searchParams.get("q");
    const searchByParam = searchParams.get("searchBy");
    if (qParam !== null) setQuery(qParam);
    if (searchByParam) setSearchBy(searchByParam);
  }, [searchParams]);

  const filteredInvoices = useMemo(() => {
    const text = query.trim().toLowerCase();
    const udharOnly = searchParams.get("udhar") === "1";
    let nextInvoices = udharOnly
      ? invoices.filter((invoice) => Number(invoice.remainingAmount || 0) > 0)
      : invoices;

    if (!text) return nextInvoices;

    return nextInvoices.filter((invoice) => {
      const fields = {
        billNo: invoice.invoiceNumber,
        customer: invoice.customer?.customerName,
        mobile: invoice.customer?.mobileNumber,
        vehicle: invoice.vehicleNumber,
        date: String(invoice.invoiceDate || invoice.createdDate || "").slice(0, 10),
        amount: invoice.totalAmount,
        item: (invoice.invoiceItems || []).map((item) => `${item.description || ""} ${item.productInvoiceitem?.productName || ""}`).join(" ")
      };

      if (searchBy === "all") {
        return Object.values(fields).join(" ").toLowerCase().includes(text);
      }

      return String(fields[searchBy] || "").toLowerCase().includes(text);
    });
  }, [invoices, query, searchBy, searchParams]);

  const filteredDailyBookUdhar = useMemo(() => {
    if (searchParams.get("udhar") !== "1") return [];
    const text = query.trim().toLowerCase();
    const pending = dailyBookEntries.filter((entry) => {
      if (entry.paymentStatus !== "udhar") return false;
      const note = String(entry.note || "").toLowerCase();
      const alreadyShownAsBill = note.includes("invoice ")
        && invoices.some((invoice) => invoice.invoiceNumber && note.includes(String(invoice.invoiceNumber).toLowerCase()));
      return !alreadyShownAsBill;
    });

    if (!text) return pending;

    return pending.filter((entry) => {
      const fields = {
        customer: entry.partyName,
        date: entry.entryDate || entry.createdDate,
        amount: entry.amount,
        item: entry.note,
        all: [entry.partyName, entry.note, entry.entryType, entry.incomeCategory, entry.amount, entry.entryDate].join(" ")
      };

      if (searchBy === "all" || searchBy === "billNo" || searchBy === "mobile" || searchBy === "vehicle") {
        return fields.all.toLowerCase().includes(text);
      }

      return String(fields[searchBy] || "").toLowerCase().includes(text);
    });
  }, [dailyBookEntries, invoices, query, searchBy, searchParams]);

  const allUdharRows = useMemo(() => {
    if (searchParams.get("udhar") !== "1") return [];

    const billRows = filteredInvoices.map((invoice) => ({
      id: `bill-${invoice.id}`,
      kind: "bill",
      invoice,
      type: "Bill",
      party: invoice.customer?.customerName || "Customer",
      date: formatDate(invoice.invoiceDate || invoice.createdDate),
      source: invoice.invoiceNumber || `Bill ${invoice.id}`,
      note: (invoice.invoiceItems || []).map((item) => item.description || item.productInvoiceitem?.productName).filter(Boolean).join(", "),
      amount: Number(invoice.remainingAmount || 0)
    }));

    const dailyRows = filteredDailyBookUdhar.map((entry) => ({
      id: `daily-${entry.id}`,
      kind: "daily",
      entry,
      type: "Daily Book",
      party: entry.partyName || "Party",
      date: formatDate(entry.entryDate || entry.createdDate),
      source: entry.entryType || "-",
      note: entry.note || "-",
      amount: Number(entry.amount || 0)
    }));

    return [...dailyRows, ...billRows].sort((a, b) => b.amount - a.amount);
  }, [filteredDailyBookUdhar, filteredInvoices, searchParams]);

  const udharPartyTotals = useMemo(() => {
    const totals = new Map();
    allUdharRows.forEach((row) => {
      const key = row.party.trim() || "Party";
      totals.set(key, (totals.get(key) || 0) + row.amount);
    });
    return Array.from(totals.entries()).map(([party, amount]) => ({ party, amount }));
  }, [allUdharRows]);

  const invoiceMessage = useMemo(() => {
    if (!selectedInvoice) return "";
    const itemLines = (selectedInvoice.invoiceItems || []).map((item, index) =>
      `${index + 1}. ${item.description || item.productInvoiceitem?.productName || "Item"} - Qty ${item.quantity || 0} x Rs. ${item.sellPrice || 0} = Rs. ${item.totalPrice || 0}`
    ).join("\n");

    return [
      "VAISHNAV CAR WASH AND ACCESSORIES",
      `Bill No: ${selectedInvoice.invoiceNumber || "-"}`,
      `Date: ${formatDate(selectedInvoice.invoiceDate || selectedInvoice.createdDate)}`,
      `Customer: ${selectedInvoice.customer?.customerName || "-"}`,
      `Mobile: ${selectedInvoice.customer?.mobileNumber || "-"}`,
      "",
      itemLines,
      "",
      `Total: Rs. ${selectedInvoice.totalAmount || 0}/-`,
      `Paid: Rs. ${selectedInvoice.paidAmount || 0}/-`,
      `Balance: Rs. ${selectedInvoice.remainingAmount || 0}/-`
    ].join("\n");
  }, [selectedInvoice]);

  const handlePrint = () => {
    try {
      printInvoiceElement("old-bill-print-sheet", `${selectedInvoice?.invoiceNumber || "Vaishnav"} Premium Invoice`);
    } catch (error) {
      alert(error.message);
    }
  };

  const handleDownloadPdf = async () => {
    try {
      const sheet = document.getElementById("old-bill-print-sheet");
      const [{ jsPDF }, html2canvasModule] = await Promise.all([
        import("jspdf"),
        import("html2canvas")
      ]);
      const canvas = await html2canvasModule.default(sheet, { scale: 2, backgroundColor: "#ffffff" });
      const pdf = new jsPDF("p", "mm", "a4");
      pdf.addImage(canvas.toDataURL("image/png"), "PNG", 0, 0, 210, 297);
      pdf.save(`${selectedInvoice?.invoiceNumber || "old-bill"}.pdf`);
    } catch (error) {
      alert(`PDF download nahi hua: ${error.message}`);
    }
  };

  const handleWhatsAppSend = () => {
    const cleanMobile = selectedInvoice?.customer?.mobileNumber?.replace(/\D/g, "") || "";
    const phone = cleanMobile.length === 10 ? `91${cleanMobile}` : cleanMobile;
    if (!phone) return alert("Is bill me customer mobile number nahi mila.");
    window.open(`https://wa.me/${phone}?text=${encodeURIComponent(invoiceMessage)}`, "_blank", "noopener,noreferrer");
  };

  const handleEditBill = (invoice) => {
    navigate(`/billing?invoiceId=${invoice.id}`, { state: { invoice } });
  };

  const handleDeleteBill = async (invoice) => {
    const confirmed = window.confirm(`Bill ${invoice.invoiceNumber || invoice.id} delete karna hai? Stock restore hoga aur daily-book income entry bhi remove hogi.`);
    if (!confirmed) return;

    try {
      const response = await fetch(`${API_BASE}/invoices/${invoice.id}`, { method: "DELETE" });
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || "Bill delete failed");
      }

      const nextInvoices = invoices.filter((item) => item.id !== invoice.id);
      setInvoices(nextInvoices);
      if (selectedInvoice?.id === invoice.id) setSelectedInvoice(nextInvoices[0] || null);
      await loadInvoices();
      alert("Bill delete ho gaya, stock aur daily book update ho gaya.");
    } catch (error) {
      alert(`Bill delete nahi hua: ${error.message}`);
    }
  };

  const openReturnPanel = (invoice) => {
    setSelectedInvoice(invoice);
    setShowReturnPanel(true);
    setReturnReason("");
    setReturnQuantities({});
  };

  const returnableQuantity = (item) => Math.max(0, Number(item.quantity || 0) - Number(item.returnedQuantity || 0));

  const handleReturnItems = async (invoice, fullReturn = false) => {
    const sourceItems = invoice.invoiceItems || [];
    const items = sourceItems
      .map((item) => ({
        invoiceItemId: item.id,
        quantity: fullReturn ? returnableQuantity(item) : Number(returnQuantities[item.id] || 0)
      }))
      .filter((item) => item.invoiceItemId && item.quantity > 0);

    if (items.length === 0) return alert("Return quantity select karo.");

    const confirmed = window.confirm(
      fullReturn
        ? `Bill ${invoice.invoiceNumber || invoice.id} full return/cancel karna hai? Linked products stock me wapas add honge.`
        : `Selected item return karna hai? Linked products stock me wapas add honge.`
    );
    if (!confirmed) return;

    try {
      const response = await fetch(`${API_BASE}/invoices/${invoice.id}/return-items`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          reason: returnReason || (fullReturn ? "Full bill return/cancel" : "Customer returned selected item"),
          items
        })
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || "Return save failed");
      }

      const updatedInvoice = await response.json();
      setSelectedInvoice(updatedInvoice);
      setShowReturnPanel(false);
      setReturnQuantities({});
      setReturnReason("");
      await loadInvoices();
      alert("Return save ho gaya, stock wapas add ho gaya.");
    } catch (error) {
      alert(`Return save nahi hua: ${error.message}`);
    }
  };

  const handleMarkInvoicePaid = async (invoice) => {
    const confirmed = window.confirm(`${invoice.customer?.customerName || "Customer"} ka ${invoice.invoiceNumber || "bill"} udhar paid mark karna hai?`);
    if (!confirmed) return;

    try {
      const response = await fetch(`${API_BASE}/invoices/${invoice.id}/mark-paid`, { method: "PUT" });
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || "Udhar paid update failed");
      }
      const updatedInvoice = await response.json();
      setSelectedInvoice(updatedInvoice);
      await loadInvoices();
      alert("Udhar paid update ho gaya.");
    } catch (error) {
      alert(`Udhar paid update nahi hua: ${error.message}`);
    }
  };

  const handleMarkDailyBookPaid = async (entry) => {
    const confirmed = window.confirm(`${entry.partyName || "Party"} ka Rs. ${entry.amount || 0} daily-book udhar paid mark karna hai?`);
    if (!confirmed) return;

    try {
      const response = await fetch(`${API_BASE}/daily-book/${entry.id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...entry,
          paymentStatus: "paid",
          note: `${entry.note || ""} (Udhar paid)`
        })
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || "Daily book udhar update failed");
      }

      await loadInvoices();
      alert("Daily book udhar paid update ho gaya.");
    } catch (error) {
      alert(`Daily book udhar paid update nahi hua: ${error.message}`);
    }
  };

  return (
    <div style={pageStyle}>
      <style>{`
        @page { size: A4 portrait; margin: 7mm; }
        @media print {
          .no-print { display: none !important; }
          html, body, #root { width: 196mm !important; min-height: 283mm !important; margin: 0 !important; padding: 0 !important; background: #fff !important; }
          .app-sidebar { display: none !important; }
          .app-shell, .app-content { display: block !important; width: 196mm !important; margin: 0 !important; padding: 0 !important; }
          body * { visibility: hidden !important; }
          #old-bill-print-sheet, #old-bill-print-sheet * { visibility: visible !important; }
          #old-bill-print-sheet {
            position: fixed !important;
            inset: 0 auto auto 0 !important;
            width: 196mm !important;
            min-height: 283mm !important;
            margin: 0 !important;
            padding: 10mm !important;
            box-sizing: border-box !important;
            border: 1mm double #0f2963 !important;
            border-radius: 2mm !important;
            box-shadow: inset 0 0 0 .4mm #c49a45 !important;
            -webkit-print-color-adjust: exact !important;
            print-color-adjust: exact !important;
          }
          #old-bill-print-sheet table, #old-bill-print-sheet tr { page-break-inside: avoid !important; }
        }
      `}</style>

      <div className="no-print" style={panelStyle}>
        <div style={headerRow}>
            <div>
            <h1 style={titleStyle}>{searchParams.get("udhar") === "1" ? "Udhar Pending Bills" : "Old Bills"}</h1>
            <div style={subText}>Purane bills search karke print, PDF, WhatsApp resend ya udhar paid update karo.</div>
          </div>
          <button onClick={loadInvoices} style={secondaryBtn}>{loading ? "Loading..." : "Refresh"}</button>
        </div>

        <div style={searchRow}>
          <select value={searchBy} onChange={(e) => setSearchBy(e.target.value)} style={inputStyle}>
            <option value="all">Search All</option>
            <option value="billNo">Bill No</option>
            <option value="customer">Customer Name</option>
            <option value="mobile">Mobile No</option>
            <option value="vehicle">Vehicle No</option>
            <option value="date">Date</option>
            <option value="amount">Amount</option>
            <option value="item">Product / Service</option>
          </select>
          <input
            placeholder="Search..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            style={{ ...inputStyle, minWidth: "340px" }}
          />
          <span style={countText}>
            {filteredInvoices.length} bill found
            {searchParams.get("udhar") === "1" ? ` + ${filteredDailyBookUdhar.length} daily-book udhar` : ""}
          </span>
        </div>

        {searchParams.get("udhar") === "1" && (
          <div style={allUdharPanel}>
            <div style={sectionHeaderRow}>
              <h2 style={sectionTitle}>All Pending Udhar</h2>
              <strong style={totalDueText}>Total Due: Rs. {allUdharRows.reduce((sum, row) => sum + row.amount, 0)}</strong>
            </div>
            <div style={partyPillRow}>
              {udharPartyTotals.map((item) => (
                <button key={item.party} type="button" onClick={() => setQuery(item.party)} style={partyPill}>
                  {item.party}: Rs. {item.amount}
                </button>
              ))}
            </div>
            <table style={miniTable}>
              <thead>
                <tr>
                  <th style={miniTh}>Party</th>
                  <th style={miniTh}>Type</th>
                  <th style={miniTh}>Date</th>
                  <th style={miniTh}>Source</th>
                  <th style={miniTh}>Note</th>
                  <th style={miniTh}>Due</th>
                  <th style={miniTh}>Action</th>
                </tr>
              </thead>
              <tbody>
                {allUdharRows.map((row) => (
                  <tr key={row.id}>
                    <td style={miniTd}>{row.party}</td>
                    <td style={miniTd}>{row.type}</td>
                    <td style={miniTd}>{row.date}</td>
                    <td style={miniTd}>{row.source}</td>
                    <td style={miniTd}>{row.note}</td>
                    <td style={miniAmountTd}>Rs. {row.amount}</td>
                    <td style={miniTd}>
                      <button type="button" onClick={() => row.kind === "bill" ? setSelectedInvoice(row.invoice) : navigate("/daily-book")} style={miniEditBtn}>View</button>
                      <button type="button" onClick={() => row.kind === "bill" ? handleMarkInvoicePaid(row.invoice) : handleMarkDailyBookPaid(row.entry)} style={miniPaidBtn}>Paid</button>
                    </td>
                  </tr>
                ))}
                {allUdharRows.length === 0 && (
                  <tr><td style={emptyMiniTd} colSpan="7">Koi pending udhar nahi hai.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}

        {searchParams.get("udhar") === "1" && filteredDailyBookUdhar.length > 0 && (
          <div style={dailyBookUdharPanel}>
            <h2 style={sectionTitle}>Daily Book Udhar</h2>
            <div style={resultGrid}>
              {filteredDailyBookUdhar.map((entry) => (
                <div key={entry.id} style={dailyBookCardStyle}>
                  <strong>{entry.partyName || "Party"}</strong>
                  <span>{formatDate(entry.entryDate || entry.createdDate)} | {entry.entryType || "-"}</span>
                  <span>{entry.note || "-"}</span>
                  <span style={udharBadge}>Udhar Rs. {entry.amount || 0}</span>
                  <div style={cardActionRow}>
                    <button type="button" onClick={() => handleMarkDailyBookPaid(entry)} style={miniPaidBtn}>
                      Mark Paid
                    </button>
                    <button type="button" onClick={() => navigate("/daily-book")} style={miniEditBtn}>
                      Daily Book
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        <div style={resultGrid}>
          {filteredInvoices.map((invoice) => (
            <div
              key={invoice.id}
              onClick={() => setSelectedInvoice(invoice)}
              style={selectedInvoice?.id === invoice.id ? selectedCardStyle : billCardStyle}
            >
              <strong>{invoice.invoiceNumber || `Bill ${invoice.id}`}</strong>
              <span>{invoice.customer?.customerName || "Customer"}</span>
              <span>{invoice.customer?.mobileNumber || "-"}</span>
              <span>{formatDate(invoice.invoiceDate || invoice.createdDate)} | Rs. {invoice.totalAmount || 0}</span>
              {Number(invoice.remainingAmount || 0) > 0 && <span style={udharBadge}>Udhar Rs. {invoice.remainingAmount || 0}</span>}
              {invoice.invoiceStatus && invoice.invoiceStatus !== "ACTIVE" && <span style={returnBadge}>{formatReturnStatus(invoice.invoiceStatus)}</span>}
              <div style={cardActionRow}>
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    handleEditBill(invoice);
                  }}
                  style={miniEditBtn}
                >
                  Edit
                </button>
                {Number(invoice.remainingAmount || 0) > 0 && (
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      handleMarkInvoicePaid(invoice);
                    }}
                    style={miniPaidBtn}
                  >
                    Mark Paid
                  </button>
                )}
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    handleDeleteBill(invoice);
                  }}
                  style={miniDeleteBtn}
                >
                  Delete
                </button>
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    openReturnPanel(invoice);
                  }}
                  style={miniReturnBtn}
                >
                  Return
                </button>
              </div>
            </div>
          ))}
        </div>

        {selectedInvoice && (
          <div style={actionRow}>
            <button onClick={() => handleEditBill(selectedInvoice)} style={primaryBtn}>Edit Bill</button>
            <button onClick={handlePrint} style={primaryBtn}>Print</button>
            <button onClick={handleDownloadPdf} style={secondaryBtn}>Download PDF</button>
            <button onClick={handleWhatsAppSend} style={whatsAppBtn}>WhatsApp Send</button>
            {Number(selectedInvoice.remainingAmount || 0) > 0 && <button onClick={() => handleMarkInvoicePaid(selectedInvoice)} style={paidBtn}>Mark Udhar Paid</button>}
            <button onClick={() => openReturnPanel(selectedInvoice)} style={returnBtn}>Return / Cancel Items</button>
            <button onClick={() => handleDeleteBill(selectedInvoice)} style={dangerBtn}>Delete Bill</button>
          </div>
        )}

        {selectedInvoice && showReturnPanel && (
          <div style={returnPanel}>
            <div style={sectionHeaderRow}>
              <h2 style={returnTitle}>Return / Cancel Items</h2>
              <button type="button" onClick={() => setShowReturnPanel(false)} style={secondaryBtn}>Close</button>
            </div>
            <textarea
              placeholder="Return reason / note"
              value={returnReason}
              onChange={(event) => setReturnReason(event.target.value)}
              style={returnReasonInput}
            />
            <table style={miniTable}>
              <thead>
                <tr>
                  <th style={miniTh}>Item</th>
                  <th style={miniTh}>Sold</th>
                  <th style={miniTh}>Already Returned</th>
                  <th style={miniTh}>Return Now</th>
                  <th style={miniTh}>Stock</th>
                </tr>
              </thead>
              <tbody>
                {(selectedInvoice.invoiceItems || []).map((item) => {
                  const maxReturn = returnableQuantity(item);
                  return (
                    <tr key={item.id}>
                      <td style={miniTd}>{item.description || item.productInvoiceitem?.productName || "Item"}</td>
                      <td style={miniTd}>{item.quantity || 0}</td>
                      <td style={miniTd}>{item.returnedQuantity || 0}</td>
                      <td style={miniTd}>
                        <input
                          type="number"
                          min="0"
                          max={maxReturn}
                          value={returnQuantities[item.id] || ""}
                          disabled={maxReturn <= 0}
                          onChange={(event) => {
                            const value = Math.min(maxReturn, Math.max(0, Number(event.target.value || 0)));
                            setReturnQuantities({ ...returnQuantities, [item.id]: value || "" });
                          }}
                          style={smallNumberInput}
                        />
                      </td>
                      <td style={miniTd}>{item.productInvoiceitem ? "Stock me add hoga" : "Manual item, stock link nahi"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div style={cardActionRow}>
              <button type="button" onClick={() => handleReturnItems(selectedInvoice, false)} style={returnBtn}>Save Selected Return</button>
              <button type="button" onClick={() => handleReturnItems(selectedInvoice, true)} style={dangerBtn}>Full Bill Return / Cancel</button>
            </div>
          </div>
        )}
      </div>

      {selectedInvoice ? (
        <div id="old-bill-print-sheet" style={sheetStyle}>
          <div style={sheetHeader}>
            <div>
              <div style={brandTitle}>VAISHNAV</div>
              <div style={brandSub}>CAR WASH AND ACCESSORIES</div>
            </div>
            <div style={{ textAlign: "right" }}>
              <div style={billTitle}>INVOICE</div>
              <div><strong>Bill No:</strong> {selectedInvoice.invoiceNumber}</div>
            <div><strong>Date:</strong> {formatDate(selectedInvoice.invoiceDate || selectedInvoice.createdDate)}</div>
              {selectedInvoice.invoiceStatus && selectedInvoice.invoiceStatus !== "ACTIVE" && (
                <div style={returnStatusText}><strong>Status:</strong> {formatReturnStatus(selectedInvoice.invoiceStatus)}</div>
              )}
            </div>
          </div>

          <div style={metaGrid}>
            <div><strong>Customer:</strong> {selectedInvoice.customer?.customerName || "-"}</div>
            <div><strong>Mobile:</strong> {selectedInvoice.customer?.mobileNumber || "-"}</div>
            <div><strong>Vehicle:</strong> {selectedInvoice.vehicleNumber || "-"}</div>
          </div>

          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>S.No</th>
                <th style={thStyle}>Description</th>
                <th style={thStyle}>Qty</th>
                <th style={thStyle}>Rate</th>
                <th style={thStyle}>Amount</th>
              </tr>
            </thead>
            <tbody>
              {(selectedInvoice.invoiceItems || []).map((item, index) => (
                <tr key={item.id || index}>
                  <td style={tdStyle}>{index + 1}</td>
                  <td style={tdStyle}>
                    {item.description || item.productInvoiceitem?.productName || "Item"}
                    {item.productInvoiceitem && <div style={identityText}>{productIdentity(item.productInvoiceitem)}</div>}
                    {Number(item.returnedQuantity || 0) > 0 && (
                      <div style={returnedText}>Returned: {item.returnedQuantity} {item.returnNote ? `| ${item.returnNote}` : ""}</div>
                    )}
                  </td>
                  <td style={tdStyle}>{item.quantity || 0}</td>
                  <td style={tdStyle}>Rs. {item.sellPrice || 0}</td>
                  <td style={tdStyle}>Rs. {item.totalPrice || 0}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div style={totalBlock}>
            <div>Total: Rs. {selectedInvoice.totalAmount || 0}/-</div>
            <div>Paid: Rs. {selectedInvoice.paidAmount || 0}/-</div>
            <div>Balance: Rs. {selectedInvoice.remainingAmount || 0}/-</div>
          </div>

          <div style={footerRow}>
            <div>Thank you for choosing Vaishnav.</div>
            <div>Authorised Signature</div>
          </div>
        </div>
      ) : (
        <div style={emptyState}>No bill selected.</div>
      )}
    </div>
  );
}

function uniqueInvoicesByNumber(invoiceList) {
  const unique = new Map();
  invoiceList.forEach((invoice) => {
    const key = invoice?.invoiceNumber ? String(invoice.invoiceNumber).trim().toLowerCase() : `id-${invoice?.id}`;
    const current = unique.get(key);
    if (!current || Number(invoice?.id || 0) >= Number(current?.id || 0)) unique.set(key, invoice);
  });
  return Array.from(unique.values()).sort((a, b) => Number(b.id || 0) - Number(a.id || 0));
}

function formatDate(value) {
  return value ? String(value).slice(0, 10) : "-";
}

function productIdentity(product) {
  return [
    product.make,
    product.model,
    product.serialNumber ? `SR ${product.serialNumber}` : "",
    product.barcode ? `Barcode ${product.barcode}` : "",
    product.id ? `ID ${product.id}` : ""
  ].filter(Boolean).join(" | ");
}

function formatReturnStatus(status) {
  if (status === "CANCELLED_RETURNED") return "Returned / Cancelled";
  if (status === "PARTIAL_RETURN") return "Partial Return";
  return status || "Active";
}

const pageStyle = { padding: "30px", background: "#f1f5f9", minHeight: "100vh" };
const panelStyle = { background: "white", padding: "18px", borderRadius: "10px", boxShadow: "0 4px 10px rgba(0,0,0,0.08)", marginBottom: "18px" };
const headerRow = { display: "flex", justifyContent: "space-between", gap: "15px", alignItems: "center" };
const titleStyle = { margin: 0, color: "#0f172a" };
const subText = { color: "#64748b", marginTop: "4px" };
const searchRow = { display: "flex", gap: "10px", flexWrap: "wrap", alignItems: "center", marginTop: "18px" };
const inputStyle = { padding: "11px", border: "1px solid #cbd5e1", borderRadius: "6px", fontSize: "14px" };
const countText = { color: "#475569", fontWeight: "bold" };
const resultGrid = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", gap: "10px", marginTop: "16px" };
const billCardStyle = { textAlign: "left", padding: "12px", border: "1px solid #cbd5e1", borderRadius: "8px", background: "#f8fafc", cursor: "pointer", display: "flex", flexDirection: "column", gap: "4px", color: "#0f172a" };
const selectedCardStyle = { ...billCardStyle, border: "2px solid #0f2963", background: "#eef2ff" };
const dailyBookUdharPanel = { marginTop: "18px", padding: "14px", border: "1px solid #fecaca", borderRadius: "8px", background: "#fff7f7" };
const sectionTitle = { margin: "0 0 10px", color: "#991b1b", fontSize: "18px" };
const dailyBookCardStyle = { ...billCardStyle, border: "1px solid #fecaca", background: "#ffffff", cursor: "default" };
const allUdharPanel = { marginTop: "18px", padding: "14px", border: "1px solid #fca5a5", borderRadius: "8px", background: "#fff7ed", overflowX: "auto" };
const sectionHeaderRow = { display: "flex", justifyContent: "space-between", alignItems: "center", gap: "10px", flexWrap: "wrap" };
const totalDueText = { color: "#991b1b", fontSize: "16px" };
const partyPillRow = { display: "flex", gap: "8px", flexWrap: "wrap", margin: "8px 0 12px" };
const partyPill = { border: "1px solid #fca5a5", background: "#ffffff", color: "#991b1b", borderRadius: "999px", padding: "7px 10px", fontWeight: "900", cursor: "pointer" };
const miniTable = { width: "100%", borderCollapse: "collapse", background: "#ffffff", borderRadius: "8px", overflow: "hidden" };
const miniTh = { textAlign: "left", padding: "10px", background: "#991b1b", color: "#ffffff", fontSize: "12px", whiteSpace: "nowrap" };
const miniTd = { padding: "10px", borderBottom: "1px solid #fee2e2", color: "#334155", fontSize: "13px", verticalAlign: "top" };
const miniAmountTd = { ...miniTd, color: "#991b1b", fontWeight: "900", whiteSpace: "nowrap" };
const emptyMiniTd = { ...miniTd, textAlign: "center", color: "#94a3b8" };
const cardActionRow = { display: "flex", gap: "7px", marginTop: "8px", flexWrap: "wrap" };
const actionRow = { display: "flex", gap: "10px", marginTop: "16px", flexWrap: "wrap" };
const primaryBtn = { padding: "10px 16px", background: "#0f2963", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const secondaryBtn = { padding: "10px 16px", background: "white", color: "#0f2963", border: "1px solid #0f2963", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const whatsAppBtn = { padding: "10px 16px", background: "#128c7e", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const dangerBtn = { padding: "10px 16px", background: "#dc2626", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const miniEditBtn = { padding: "7px 10px", background: "#0f2963", color: "white", border: "none", borderRadius: "5px", fontWeight: "bold", cursor: "pointer", fontSize: "12px" };
const miniDeleteBtn = { padding: "7px 10px", background: "#dc2626", color: "white", border: "none", borderRadius: "5px", fontWeight: "bold", cursor: "pointer", fontSize: "12px" };
const miniPaidBtn = { padding: "7px 10px", background: "#128c7e", color: "white", border: "none", borderRadius: "5px", fontWeight: "bold", cursor: "pointer", fontSize: "12px" };
const miniReturnBtn = { padding: "7px 10px", background: "#9c742a", color: "white", border: "none", borderRadius: "5px", fontWeight: "bold", cursor: "pointer", fontSize: "12px" };
const paidBtn = { padding: "10px 16px", background: "#128c7e", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const returnBtn = { padding: "10px 16px", background: "#9c742a", color: "white", border: "none", borderRadius: "6px", fontWeight: "bold", cursor: "pointer" };
const udharBadge = { display: "inline-block", width: "fit-content", background: "#fee2e2", color: "#991b1b", border: "1px solid #fecaca", borderRadius: "999px", padding: "4px 8px", fontSize: "12px", fontWeight: "900" };
const returnBadge = { display: "inline-block", width: "fit-content", background: "#fef3c7", color: "#92400e", border: "1px solid #fcd34d", borderRadius: "999px", padding: "4px 8px", fontSize: "12px", fontWeight: "900" };
const returnPanel = { marginTop: "16px", padding: "14px", border: "1px solid #fcd34d", borderRadius: "8px", background: "#fffbeb", overflowX: "auto" };
const returnTitle = { margin: "0 0 10px", color: "#92400e", fontSize: "18px" };
const returnReasonInput = { ...inputStyle, width: "100%", minHeight: "54px", boxSizing: "border-box", marginBottom: "12px", fontFamily: "Arial, sans-serif" };
const smallNumberInput = { ...inputStyle, width: "95px", minWidth: "95px", padding: "8px" };
const returnStatusText = { color: "#92400e", fontWeight: "900", marginTop: "4px" };
const returnedText = { fontSize: "12px", color: "#92400e", marginTop: "5px", fontWeight: "900" };
const sheetStyle = { width: "210mm", minHeight: "297mm", background: "white", margin: "0 auto", padding: "16mm", boxSizing: "border-box", border: "1.5px solid #0f2963", outline: "4px double #c49a45", outlineOffset: "-8px", boxShadow: "0 8px 24px rgba(0,0,0,0.08)", color: "#0f172a" };
const sheetHeader = { display: "flex", justifyContent: "space-between", borderBottom: "3px solid #0f2963", paddingBottom: "14px", marginBottom: "18px" };
const brandTitle = { color: "#0f2963", fontSize: "42px", fontWeight: "900", letterSpacing: "2px" };
const brandSub = { color: "#9c742a", fontWeight: "900", letterSpacing: "1px" };
const billTitle = { color: "#0f2963", fontSize: "28px", fontWeight: "900" };
const metaGrid = { display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "10px", marginBottom: "18px" };
const tableStyle = { width: "100%", borderCollapse: "collapse" };
const thStyle = { textAlign: "left", padding: "10px", background: "#0f2963", color: "white", border: "1px solid #0f2963" };
const tdStyle = { padding: "10px", border: "1px solid #cbd5e1", verticalAlign: "top" };
const identityText = { fontSize: "11px", color: "#64748b", marginTop: "4px", fontWeight: "bold" };
const totalBlock = { marginLeft: "auto", marginTop: "18px", width: "260px", background: "#f8fafc", border: "1px solid #cbd5e1", padding: "12px", display: "flex", flexDirection: "column", gap: "6px", fontWeight: "bold" };
const footerRow = { display: "flex", justifyContent: "space-between", marginTop: "60px", fontWeight: "bold" };
const emptyState = { background: "white", padding: "30px", textAlign: "center", borderRadius: "10px", color: "#64748b" };

export default OldBills;
