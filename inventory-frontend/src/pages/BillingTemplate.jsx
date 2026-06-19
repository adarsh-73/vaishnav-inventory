import React, { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { todayDate } from '../utils/storage';
import { API_BASE, getInvoiceBusinessCategory, getInvoiceCategoryTotals, inferInvoiceItemCategory, isServiceText } from '../utils/api';
import { printInvoiceElement } from '../utils/printInvoice';

export default function VaishnavFinalInvoice() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const [customerName, setCustomerName] = useState("");
  const [mobileNo, setMobileNo] = useState("");
  const [vehicleNo, setVehicleNo] = useState("");
  const [billNo, setBillNo] = useState(`V-${Date.now().toString().slice(-5)}`);
  const [billDate, setBillDate] = useState(todayDate());
  const [businessCategory, setBusinessCategory] = useState("accessories");
  const [paidAmount, setPaidAmount] = useState("");
  const [discountAmount, setDiscountAmount] = useState("");
  const [discountNote, setDiscountNote] = useState("");
  const [editingInvoiceId, setEditingInvoiceId] = useState(null);
  const [invoiceHistory, setInvoiceHistory] = useState([]);

  const [items, setItems] = useState([]);

  const [newDesc, setNewDesc] = useState("");
  const [newQty, setNewQty] = useState("");
  const [newRate, setNewRate] = useState("");
  const [newPurchasePrice, setNewPurchasePrice] = useState("");
  const [newItemMode, setNewItemMode] = useState("manual_accessory");
  const [editingItemId, setEditingItemId] = useState(null);
  const [backendProducts, setBackendProducts] = useState([]);
  const [selectedProductId, setSelectedProductId] = useState("");
  const [productSearch, setProductSearch] = useState("");
  const [isPrinting, setIsPrinting] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [qrImage, setQrImage] = useState("");
  const [upiId, setUpiId] = useState("7985433770@hdfc");

  const loadBackendProducts = async () => {
    try {
      const response = await fetch(`${API_BASE}/products`);
      if (!response.ok) throw new Error("Products API failed");
      const data = await response.json();
      setBackendProducts(Array.isArray(data) ? data : []);
    } catch {
      setBackendProducts([]);
    }
  };

  const loadInvoiceHistory = async () => {
    try {
      const response = await fetch(`${API_BASE}/invoices`);
      if (!response.ok) throw new Error("Invoice API failed");
      const data = await response.json();
      setInvoiceHistory(Array.isArray(data) ? uniqueInvoicesByNumber(data) : []);
    } catch {
      setInvoiceHistory([]);
    }
  };

  useEffect(() => {
    loadBackendProducts();
    loadInvoiceHistory();
  }, []);

  const selectedProduct = useMemo(
    () => backendProducts.find((product) => String(product.id) === String(selectedProductId)),
    [backendProducts, selectedProductId]
  );

  const filteredBackendProducts = useMemo(() => {
    const text = productSearch.trim().toLowerCase();
    const source = text
      ? backendProducts.filter((product) => getProductSearchText(product).includes(text))
      : backendProducts;

    return source.slice(0, 12);
  }, [backendProducts, productSearch]);

  const handleAddItem = (e) => {
    e.preventDefault();
    const desc = selectedProduct ? selectedProduct.productName : newDesc;
    const rate = selectedProduct ? selectedProduct.sellPrice : newRate;

    if (!desc || !newQty || !rate) return;
    const manualCategory = !selectedProduct && (newItemMode === "service" || isServiceText(desc)) ? "service" : "accessories";
    const itemCategory = selectedProduct
      ? "inventory_accessory"
      : newItemMode === "service"
        ? "service"
        : newItemMode === "old_accessory"
          ? "old_accessory"
          : newItemMode === "direct_stock"
            ? "direct_stock_accessory"
            : manualCategory === "service"
              ? "service"
              : "manual_accessory";
    const nextItem = {
      id: editingItemId || Date.now(),
      productId: selectedProduct?.id,
      desc,
      identity: selectedProduct ? getProductIdentity(selectedProduct) : "",
      category: itemCategory === "service" ? "service" : itemCategory === "old_accessory" ? "old_accessories" : "accessories",
      itemCategory,
      qty: Number(newQty),
      rate: Number(rate),
      purchasePrice: Number(newPurchasePrice || selectedProduct?.purchasePrice || 0),
      autoCreateProduct: itemCategory === "direct_stock_accessory"
    };
    setItems(editingItemId ? items.map((item) => item.id === editingItemId ? nextItem : item) : [...items, nextItem]);
    setEditingItemId(null);
    setSelectedProductId("");
    setProductSearch("");
    setNewDesc("");
    setNewQty("");
    setNewRate("");
    setNewPurchasePrice("");
    setNewItemMode("manual_accessory");
  };

  const subTotalAmount = items.reduce((sum, item) => sum + (item.qty * item.rate), 0);
  const discountValue = Math.min(Number(discountAmount || 0), subTotalAmount);
  const totalAmount = Math.max(0, subTotalAmount - discountValue);
  const billSplit = useMemo(() => getInvoiceCategoryTotals({ items, discountAmount: discountValue }), [items, discountValue]);
  const paidValue = paidAmount === "" ? totalAmount : Number(paidAmount || 0);
  const remainingAmount = Math.max(totalAmount - paidValue, 0);

  const qrData = useMemo(() => {
    const params = new URLSearchParams({
      pa: upiId.trim(),
      pn: "Vaishnav Car Wash And Accessories",
      cu: "INR",
      tn: `Bill ${billNo || ""}`.trim()
    });

    if (totalAmount > 0) {
      params.set("am", totalAmount.toFixed(2));
    }

    return `upi://pay?${params.toString()}`;
  }, [billNo, totalAmount, upiId]);

  const qrSrc = qrImage || `https://api.qrserver.com/v1/create-qr-code/?size=180x180&margin=8&data=${encodeURIComponent(qrData)}`;

  const handlePrint = () => {
    try {
      printInvoiceElement("vaishnav-print-sheet", `${billNo || "Vaishnav"} Premium Invoice`);
    } catch (error) {
      alert(error.message);
    }
  };

  const createInvoicePdf = async () => {
    const sheet = document.getElementById("vaishnav-print-sheet");
    if (!sheet) throw new Error("Bill sheet nahi mila.");

    const [{ jsPDF }, html2canvasModule] = await Promise.all([
      import("jspdf"),
      import("html2canvas")
    ]);
    const html2canvas = html2canvasModule.default;
    const clone = sheet.cloneNode(true);

    clone.querySelectorAll(".no-print").forEach((node) => node.remove());
    clone.style.position = "fixed";
    clone.style.left = "-10000px";
    clone.style.top = "0";
    clone.style.margin = "0";
    clone.style.boxShadow = "none";
    clone.style.width = "210mm";
    clone.style.height = "297mm";
    document.body.appendChild(clone);

    try {
      const canvas = await html2canvas(clone, {
        scale: 2,
        useCORS: true,
        backgroundColor: "#ffffff"
      });

      const pdf = new jsPDF("p", "mm", "a4");
      const imageData = canvas.toDataURL("image/png");
      pdf.addImage(imageData, "PNG", 0, 0, 210, 297);
      return pdf;
    } finally {
      document.body.removeChild(clone);
    }
  };

  const handleDownloadPdf = async () => {
    try {
      const pdf = await createInvoicePdf();
      pdf.save(`${billNo || "vaishnav-bill"}.pdf`);
    } catch (error) {
      alert(`PDF download nahi hua: ${error.message}`);
    }
  };

  const invoiceMessage = useMemo(() => {
    const itemLines = items.map((item, index) =>
      `${index + 1}. ${item.desc}${item.identity ? ` (${item.identity})` : ""} - Qty ${item.qty} x Rs. ${item.rate} = Rs. ${item.qty * item.rate}`
    ).join("\n");

    return [
      "VAISHNAV CAR WASH AND ACCESSORIES",
      `Bill No: ${billNo || "-"}`,
      `Date: ${billDate || "-"}`,
      `Customer: ${customerName || "-"}`,
      `Mobile: ${mobileNo || "-"}`,
      `Vehicle: ${vehicleNo || "-"}`,
      "",
      itemLines || "No items added",
      "",
      `Subtotal: Rs. ${subTotalAmount}/-`,
      discountValue > 0 ? `Discount: Rs. ${discountValue}/-` : "",
      `Total Amount: Rs. ${totalAmount}/-`,
      `Paid: Rs. ${paidValue}/-`,
      `Balance: Rs. ${remainingAmount}/-`,
      "",
      "Thank you for choosing Vaishnav."
    ].join("\n");
  }, [billDate, billNo, customerName, discountValue, items, mobileNo, paidValue, remainingAmount, subTotalAmount, totalAmount, vehicleNo]);

  const handleWhatsAppSend = async () => {
    const cleanMobile = mobileNo.replace(/\D/g, '');
    const phone = cleanMobile.length === 10 ? `91${cleanMobile}` : cleanMobile;
    if (!phone) {
      alert("Direct WhatsApp open karne ke liye customer ka mobile number bharna zaroori hai.");
      return;
    }
    const url = phone
      ? `https://wa.me/${phone}?text=${encodeURIComponent(invoiceMessage)}`
      : `https://wa.me/?text=${encodeURIComponent(invoiceMessage)}`;

    try {
      const pdf = await createInvoicePdf();
      pdf.save(`${billNo || "vaishnav-bill"}.pdf`);
      window.open(url, '_blank', 'noopener,noreferrer');
    } catch (error) {
      window.open(url, '_blank', 'noopener,noreferrer');
    }
  };

  const handleQrUpload = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = () => setQrImage(String(reader.result));
    reader.readAsDataURL(file);
  };

  const buildInvoicePayload = (nextItems = items) => {
    const nextSubTotalAmount = nextItems.reduce((sum, item) => sum + (item.qty * item.rate), 0);
    const nextDiscountValue = Math.min(Number(discountAmount || 0), nextSubTotalAmount);
    const nextTotalAmount = Math.max(0, nextSubTotalAmount - nextDiscountValue);
    const nextPaidValue = paidAmount === "" ? nextTotalAmount : Number(paidAmount || 0);
    const nextRemainingAmount = Math.max(nextTotalAmount - nextPaidValue, 0);
    const invoiceItemsPayload = nextItems.map((item) => ({
      productInvoiceitem: item.productId ? { id: item.productId } : null,
      description: item.desc,
      itemCategory: item.itemCategory || (item.productId ? "inventory_accessory" : (item.category === "service" || isServiceText(item.desc)) ? "service" : "manual_accessory"),
      autoCreateProduct: Boolean(item.autoCreateProduct),
      quantity: item.qty,
      sellPrice: item.rate,
      purchasePrice: Number(item.purchasePrice || 0),
      stockCategory: "Accessories"
    }));
    const inferredBusinessCategory = invoiceItemsPayload.length > 0
      ? getInvoiceBusinessCategory({ invoiceItems: invoiceItemsPayload })
      : businessCategory;

    return {
      invoiceNumber: billNo,
      invoiceDate: billDate ? `${billDate}T00:00:00` : null,
      customer: {
        customerName: customerName || "Walk-in Customer",
        mobileNumber: mobileNo || null
      },
      paidAmount: nextPaidValue,
      remainingAmount: nextRemainingAmount,
      paymentMethod: "CASH/UPI",
      businessCategory: inferredBusinessCategory,
      totalAmount: nextTotalAmount,
      discountAmount: nextDiscountValue,
      discountNote: discountNote || null,
      invoiceItems: invoiceItemsPayload
    };
  };

  const persistBill = async (nextItems = items) => {
    const response = await fetch(editingInvoiceId ? `${API_BASE}/invoices/${editingInvoiceId}` : `${API_BASE}/invoices`, {
      method: editingInvoiceId ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(buildInvoicePayload(nextItems))
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(errorText || "Invoice save failed");
    }

    await loadBackendProducts();
    await loadInvoiceHistory();
    return response.json();
  };

  const handleSaveBill = async () => {
    if (!customerName && items.length === 0) return alert("Bill save karne ke liye customer ya item add karo.");
    if (isSaving) return;

    setIsSaving(true);
      try {
        await persistBill(items);
      } catch (error) {
        alert(`Backend me bill save nahi hua: ${error.message}`);
        return;
      } finally {
        setIsSaving(false);
      }

    alert(editingInvoiceId ? "Bill update ho gaya aur stock adjust ho gaya." : "Bill database me save ho gaya, stock reduce ho gaya, aur income daily book me add ho gayi.");
  };

  const editItem = (item) => {
    setEditingItemId(item.id);
    setSelectedProductId(item.productId ? String(item.productId) : "");
    setProductSearch(item.identity ? `${item.desc} ${item.identity}` : item.desc);
    setNewItemMode(getManualModeForItem(item));
    setNewDesc(item.desc);
    setNewQty(String(item.qty));
    setNewRate(String(item.rate));
    setNewPurchasePrice(item.purchasePrice ? String(item.purchasePrice) : "");
  };

  const deleteItem = async (id) => {
    const removedItem = items.find((item) => item.id === id);
    const nextItems = items.filter((item) => item.id !== id);
    const shouldPersist = editingInvoiceId && window.confirm(`${removedItem?.desc || "Item"} bill se delete karke stock/bill abhi update karna hai?`);

    setItems(nextItems);
    if (editingItemId === id) setEditingItemId(null);

    if (!shouldPersist) return;

    setIsSaving(true);
    try {
      await persistBill(nextItems);
      alert(removedItem?.productId ? "Spare bill se delete ho gaya aur stock restore ho gaya." : "Item bill se delete ho gaya aur bill update ho gaya.");
    } catch (error) {
      setItems(items);
      alert(`Item delete save nahi hua: ${error.message}`);
    } finally {
      setIsSaving(false);
    }
  };

  const startNewBill = () => {
    navigate("/billing", { replace: true });
    setBillNo(`V-${Date.now().toString().slice(-5)}`);
    setBillDate(todayDate());
    setCustomerName("");
    setMobileNo("");
    setVehicleNo("");
    setBusinessCategory("accessories");
    setDiscountAmount("");
    setDiscountNote("");
    setItems([]);
    setEditingItemId(null);
    setEditingInvoiceId(null);
    setSelectedProductId("");
    setProductSearch("");
    setNewDesc("");
    setNewQty("");
    setNewRate("");
    setNewPurchasePrice("");
    setNewItemMode("manual_accessory");
    setPaidAmount("");
  };

  const loadInvoiceForEdit = (invoice) => {
    setEditingInvoiceId(invoice.id);
    setBillNo(invoice.invoiceNumber || "");
    setBillDate(String(invoice.invoiceDate || invoice.createdDate || todayDate()).slice(0, 10));
    setCustomerName(invoice.customer?.customerName || "");
    setMobileNo(invoice.customer?.mobileNumber || "");
    setVehicleNo(invoice.vehicleNumber || "");
    setBusinessCategory(invoice.businessCategory || "accessories");
    setPaidAmount(invoice.paidAmount ?? invoice.totalAmount ?? "");
    setDiscountAmount(invoice.discountAmount ? String(invoice.discountAmount) : "");
    setDiscountNote(invoice.discountNote || "");
    setItems((invoice.invoiceItems || []).map((item) => ({
      id: item.id || Date.now() + Math.random(),
      productId: item.productInvoiceitem?.id,
      desc: item.description || item.productInvoiceitem?.productName || "Item",
      identity: item.productInvoiceitem ? getProductIdentity(item.productInvoiceitem) : "",
      category: inferInvoiceItemCategory(item),
      itemCategory: item.itemCategory || (item.productInvoiceitem ? "inventory_accessory" : inferInvoiceItemCategory(item) === "service" ? "service" : "manual_accessory"),
      qty: Number(item.quantity || 0),
      rate: Number(item.sellPrice || item.productInvoiceitem?.sellPrice || 0),
      purchasePrice: Number(item.purchasePrice || item.productInvoiceitem?.purchasePrice || 0),
      autoCreateProduct: Boolean(item.autoCreateProduct)
    })));
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  useEffect(() => {
    const invoiceId = searchParams.get("invoiceId");
    if (!invoiceId || String(editingInvoiceId || "") === String(invoiceId)) return;

    const loadInvoiceFromUrl = async () => {
      try {
        const stateInvoice = location.state?.invoice;
        if (stateInvoice && String(stateInvoice.id || "") === String(invoiceId)) {
          loadInvoiceForEdit(stateInvoice);
          return;
        }

        const response = await fetch(`${API_BASE}/invoices/${invoiceId}`);
        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(errorText || `Bill edit ke liye load nahi hua (${response.status})`);
        }
        const invoice = await response.json();
        loadInvoiceForEdit(invoice);
      } catch (error) {
        try {
          const fallbackResponse = await fetch(`${API_BASE}/invoices`);
          if (!fallbackResponse.ok) throw error;
          const list = await fallbackResponse.json();
          const fallbackInvoice = Array.isArray(list)
            ? list.find((invoice) => String(invoice.id || "") === String(invoiceId))
            : null;
          if (!fallbackInvoice) throw error;
          loadInvoiceForEdit(fallbackInvoice);
        } catch {
          alert(`Bill edit load error: ${error.message}`);
        }
      }
    };

    loadInvoiceFromUrl();
  }, [searchParams, editingInvoiceId, location.state]);

  const handleDeleteInvoice = async (invoice) => {
    const confirmed = window.confirm(`Bill ${invoice.invoiceNumber || invoice.id} delete karna hai? Stock restore hoga aur daily-book income entry bhi remove hogi.`);
    if (!confirmed) return;

    try {
      const response = await fetch(`${API_BASE}/invoices/${invoice.id}`, { method: "DELETE" });
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || "Invoice delete failed");
      }

      if (editingInvoiceId === invoice.id) startNewBill();
      await loadBackendProducts();
      await loadInvoiceHistory();
      alert("Bill delete ho gaya, stock aur daily book update ho gaya.");
    } catch (error) {
      alert(`Bill delete nahi hua: ${error.message}`);
    }
  };

  const selectInventoryProduct = (product) => {
    setNewItemMode("inventory");
    setSelectedProductId(String(product.id));
    setProductSearch(`${product.productName || ""} ${getProductIdentity(product)}`.trim());
    setNewDesc(product.productName || "");
    setNewRate(product.sellPrice || "");
  };

  return (
    <div style={{ background: isPrinting ? '#ffffff' : '#f1f5f9', minHeight: '100vh', padding: isPrinting ? '0' : '30px 0', fontFamily: 'Arial, sans-serif', overflowY: isPrinting ? 'visible' : 'auto' }}>
      <style>{`
        @page { size: A4 portrait; margin: 7mm; }

        @media print {
          html, body, #root {
            width: 196mm !important;
            min-height: 283mm !important;
            margin: 0 !important;
            padding: 0 !important;
            background: #ffffff !important;
            overflow: visible !important;
          }

          .app-shell, .app-content {
            display: block !important;
            width: 196mm !important;
            margin: 0 !important;
            padding: 0 !important;
          }

          .app-sidebar { display: none !important; }
          body * { visibility: hidden !important; }

          .invoice-print-sheet,
          .invoice-print-sheet * { visibility: visible !important; }

          .no-print {
            display: none !important;
            visibility: hidden !important;
          }

          .invoice-print-sheet {
            position: fixed !important;
            left: 0 !important;
            top: 0 !important;
            width: 196mm !important;
            height: 283mm !important;
            margin: 0 !important;
            box-shadow: none !important;
            border: 1mm double #0F2963 !important;
            border-radius: 2mm !important;
            padding: 7mm 9mm 6mm !important;
            box-sizing: border-box !important;
            overflow: hidden !important;
            -webkit-print-color-adjust: exact !important;
            print-color-adjust: exact !important;
          }

          .invoice-print-sheet table { page-break-inside: avoid !important; }
          .invoice-print-sheet tr { page-break-inside: avoid !important; }
        }
      `}</style>
      
      {/* CONTROLS ENGINE DESK (Es block ko print me bilkul gayab hona hi padega) */}
      <div className="no-print" style={styles.topControlPanel}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
          <span style={{ fontWeight: 'bold', color: '#0F2963' }}>⚙️ Vaishnav Invoice Engine Control Desk</span>
          <div style={styles.actionButtonsRow}>
            <button onClick={startNewBill} style={styles.downloadActionBtn}>New Bill</button>
            <button onClick={handleSaveBill} disabled={isSaving} style={isSaving ? { ...styles.insertRowBtn, opacity: 0.65, cursor: "not-allowed" } : styles.insertRowBtn}>{isSaving ? "Saving..." : "Save Bill"}</button>
            <button onClick={handlePrint} style={styles.printActionBtn}>Print Bill</button>
            <button onClick={handleDownloadPdf} style={styles.downloadActionBtn}>Download PDF</button>
            <button onClick={handleWhatsAppSend} style={styles.whatsappActionBtn}>WhatsApp Send</button>
          </div>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '10px', marginBottom: '12px' }}>
          <input type="text" placeholder="Customer Name" value={customerName} onChange={e => setCustomerName(e.target.value)} style={styles.panelInput} />
          <input type="text" placeholder="Mobile No" value={mobileNo} onChange={e => setMobileNo(e.target.value)} style={styles.panelInput} />
          <input type="text" placeholder="Vehicle No" value={vehicleNo} onChange={e => setVehicleNo(e.target.value)} style={styles.panelInput} />
          <input type="text" placeholder="Bill No" value={billNo} onChange={e => setBillNo(e.target.value)} style={styles.panelInput} />
          <input type="text" placeholder="Date" value={billDate} onChange={e => setBillDate(e.target.value)} style={styles.panelInput} />
          <select value={businessCategory} onChange={e => setBusinessCategory(e.target.value)} style={styles.panelInput}>
            <option value="accessories">Auto Split: Product = Accessories</option>
            <option value="washing">Auto Split: Service/Labour</option>
          </select>
          <input type="number" placeholder="Paid Amount" value={paidAmount} onChange={e => setPaidAmount(e.target.value)} style={styles.panelInput} />
          <input type="number" placeholder="Discount (optional)" value={discountAmount} onChange={e => setDiscountAmount(e.target.value)} style={styles.panelInput} />
          <input type="text" placeholder="Discount note (optional)" value={discountNote} onChange={e => setDiscountNote(e.target.value)} style={styles.panelInput} />
        </div>
        <div style={styles.splitSummaryBar}>
          <span>Washing/Labour: ₹ {billSplit.serviceProfit}</span>
          <span>Accessories Sale: ₹ {billSplit.accessories}</span>
          <span>Accessories Profit: ₹ {billSplit.accessoriesProfit}</span>
          <span>Old Accessories: ₹ {billSplit.oldAccessoriesProfit}</span>
          <span>Discount: ₹ {discountValue}</span>
          <span>Detected: {getInvoiceBusinessCategory({ items })}</span>
        </div>
        <form onSubmit={handleAddItem} style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
          <select value={newItemMode} onChange={e => {
            setNewItemMode(e.target.value);
            if (e.target.value !== "inventory") {
              setSelectedProductId("");
              setProductSearch("");
            }
          }} style={{ ...styles.panelInput, flex: 1.4 }}>
            <option value="manual_accessory">Manual Accessories Bill (No Stock Out)</option>
            <option value="direct_stock">Direct Stock Add + Sell</option>
            <option value="old_accessory">Old Accessories Earning</option>
            <option value="service">Manual Washing/Labour</option>
            <option value="inventory">Inventory Product (Stock Out)</option>
          </select>
          <div style={styles.productSearchBox}>
            <input
              type="text"
              placeholder="Inventory product search: name, SR, barcode..."
              value={productSearch}
              disabled={newItemMode !== "inventory"}
              onChange={e => {
                setProductSearch(e.target.value);
                setSelectedProductId("");
              }}
              style={{ ...styles.panelInput, width: "100%", marginRight: 0, opacity: newItemMode !== "inventory" ? 0.55 : 1 }}
            />
            {newItemMode === "inventory" && productSearch.trim() && !selectedProductId && (
              <div style={styles.productResultList}>
                {filteredBackendProducts.map((product) => (
                  <button
                    key={product.id}
                    type="button"
                    onClick={() => selectInventoryProduct(product)}
                    style={Number(product.quantity || 0) <= 0 ? styles.productResultLowStock : styles.productResultBtn}
                  >
                    <strong>{product.productName}</strong>
                    <span>{getProductIdentity(product) || `ID ${product.id}`}</span>
                    <span>Stock {product.quantity ?? 0} | Rs. {product.sellPrice ?? 0}</span>
                  </button>
                ))}
                {filteredBackendProducts.length === 0 && <div style={styles.productNoResult}>Product nahi mila.</div>}
              </div>
            )}
            {selectedProduct && (
              <div style={styles.selectedProductPill}>
                Selected: {selectedProduct.productName} | Stock {selectedProduct.quantity ?? 0} | Rs. {selectedProduct.sellPrice ?? 0}
              </div>
            )}
          </div>
          <input type="text" placeholder="Work / Service Description..." value={newDesc} onChange={e => {
            setNewDesc(e.target.value);
            if (selectedProductId) {
              setSelectedProductId("");
              setNewItemMode("manual_accessory");
            }
          }} style={{ ...styles.panelInput, flex: 3 }} />
          <input type="number" placeholder="Qty" value={newQty} onChange={e => setNewQty(e.target.value)} style={{ ...styles.panelInput, flex: 1 }} />
          <input type="number" placeholder="Rate" value={newRate} onChange={e => setNewRate(e.target.value)} style={{ ...styles.panelInput, flex: 1 }} />
          {(newItemMode === "direct_stock" || newItemMode === "manual_accessory") && (
            <input type="number" placeholder="Buy Price" value={newPurchasePrice} onChange={e => setNewPurchasePrice(e.target.value)} style={{ ...styles.panelInput, flex: 1 }} />
          )}
          <button type="submit" style={styles.insertRowBtn}>{editingItemId ? "Update Item" : "+ Add Item"}</button>
        </form>
        <div style={styles.qrControlsRow}>
          <input type="text" placeholder="UPI ID" value={upiId} onChange={e => setUpiId(e.target.value)} style={{ ...styles.panelInput, flex: 1 }} />
          <input type="file" accept="image/*" onChange={handleQrUpload} style={{ ...styles.panelInput, flex: 1 }} />
        </div>
        {invoiceHistory.length > 0 && (
          <div style={styles.historyStrip}>
            {invoiceHistory.slice().reverse().slice(0, 8).map((invoice) => (
              <div key={invoice.id} style={styles.historyItem}>
                <button type="button" onClick={() => loadInvoiceForEdit(invoice)} style={styles.historyBtn}>
                  Edit {invoice.invoiceNumber} - Rs. {invoice.totalAmount || 0}
                </button>
                <button type="button" onClick={() => handleDeleteInvoice(invoice)} style={styles.historyDeleteBtn}>
                  Delete
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* INVOICE CANVAS */}
      <div id="vaishnav-print-sheet" className="invoice-print-sheet" style={isPrinting ? styles.a4SheetFramework : { ...styles.a4SheetFramework, ...styles.screenBillSheet }}>
        
        {/* BRAND HEADER SECTION */}
        <div style={styles.headerRow}>
          <div style={{ width: '130px', display: 'flex', justifyContent: 'center' }}>
            <div style={styles.printSafeLogo}>
              <div style={styles.printSafeLogoInner}>V</div>
              <div style={styles.printSafeLogoTop}>PREMIUM</div>
              <div style={styles.printSafeLogoBottom}>DETAILING</div>
            </div>
          </div>

          <div style={styles.headerCenterText}>
            <h1 style={styles.mainTitleHeader}>VAISHNAV</h1>
            <h2 style={styles.subTitleServicesHeader}>CAR WASH AND ACCESSORIES</h2>
            <div style={styles.lineSpacerContainer}>
              <div style={styles.slimBlueLine}></div>
              <div style={styles.diamondCrest}>♦❖♦</div>
              <div style={styles.slimBlueLine}></div>
            </div>
            <div style={styles.servicesGridRow}>
              <div style={styles.serviceItem}><span style={styles.serviceIcon}>🚗</span><span style={styles.serviceText}>CAR WASH</span></div>
              <div style={styles.serviceItem}><span style={styles.serviceIcon}>🪠</span><span style={styles.serviceText}>VACUUM</span></div>
              <div style={styles.serviceItem}><span style={styles.serviceIcon}>🧽</span><span style={styles.serviceText}>POLISH</span></div>
              <div style={styles.serviceItem}><span style={styles.serviceIcon}>⚙️</span><span style={styles.serviceText}>ACCESSORIES</span></div>
            </div>
          </div>

          <div style={styles.headerRightCarAsset}>
            <div style={styles.premiumBadgePanel}>
              <div style={styles.premiumBadgeTop}>PREMIUM</div>
              <div style={styles.premiumBadgeCircle}>
                <div style={styles.premiumBadgeInner}>V</div>
              </div>
              <div style={styles.premiumBadgeMain}>DETAILING STUDIO</div>
              <div style={styles.premiumBadgeSub}>WASH • POLISH • ACCESSORIES</div>
            </div>
          </div>
        </div>

        <div style={{ height: '3px', backgroundColor: '#0F2963', margin: '15px 0 20px 0' }}></div>

        {/* LOGISTICS ROW */}
        <div style={styles.logisticsBlockRow}>
          <div style={{ width: '60%', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <h2 style={styles.invoiceMainHeadingBadge}>BILL</h2>
            <div style={{ width: '45px', height: '2px', backgroundColor: '#C49A45', margin: '-5px 0 10px 0' }}></div>
            
            <div style={styles.clientMetaLine}>
              <span style={styles.clientKeyLabel}>Customer Name</span>
              <span style={styles.clientColonSpacer}>:</span>
              <span style={styles.clientValueUnderline}>{customerName}</span>
            </div>
            <div style={styles.clientMetaLine}>
              <span style={styles.clientKeyLabel}>Mobile No.</span>
              <span style={styles.clientColonSpacer}>:</span>
              <span style={styles.clientValueUnderline}>{mobileNo}</span>
            </div>
            <div style={styles.clientMetaLine}>
              <span style={styles.clientKeyLabel}>Vehicle No.</span>
              <span style={styles.clientColonSpacer}>:</span>
              <span style={styles.clientValueUnderline}>{vehicleNo}</span>
            </div>
          </div>

          <div style={{ width: '35%', paddingTop: '32px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <div style={styles.rightLogisticsLine}>
              <span style={styles.rightLogisticsKey}>Bill No.</span>
              <span style={{ color: '#0F2963', fontWeight: 'bold' }}>:</span>
              <span style={styles.rightLogisticsValue}>{billNo}</span>
            </div>
            <div style={styles.rightLogisticsLine}>
              <span style={styles.rightLogisticsKey}>Date</span>
              <span style={{ color: '#0F2963', fontWeight: 'bold' }}>:</span>
              <span style={styles.rightLogisticsValue}>{billDate}</span>
            </div>
          </div>
        </div>

        {/* DATA TABLE */}
        <div style={styles.tableBorderBoxContainerFrame}>
          <div style={styles.tableCenterWatermarkBg}>V</div>
          <table style={styles.ledgerMainTableElement}>
            <thead>
              <tr style={styles.tableHeaderRowBackgroundStrip}>
                <th style={{ ...styles.thCell, width: '12%' }}>SR. NO.</th>
                <th style={{ ...styles.thCell, width: '50%', textAlign: 'left', paddingLeft: '20px' }}>DESCRIPTION</th>
                <th style={{ ...styles.thCell, width: '12%' }}>QTY.</th>
                <th style={{ ...styles.thCell, width: '12%' }}>RATE (₹)</th>
                <th style={{ ...styles.thCell, width: '14%' }}>AMOUNT (₹)</th>
                <th className="no-print" style={{ ...styles.thCell, width: '10%' }}>EDIT</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item, index) => (
                <tr key={item.id} style={styles.ledgerDataRowHeightBorder}>
                  <td style={{ ...styles.tdCell, textAlign: 'center' }}>{index + 1}</td>
                  <td style={{ ...styles.tdCell, textAlign: 'left', paddingLeft: '20px', color: '#0F2963', fontWeight: '600' }}>
                    {item.desc}
                    {item.identity && <div style={styles.itemIdentityText}>{item.identity}</div>}
                    <div className="no-print" style={item.category === "service" ? styles.serviceTag : styles.accessoryTag}>
                      {item.category === "service" ? "Washing/Labour" : item.itemCategory === "old_accessory" ? "Old Accessories" : item.productId ? "Inventory Stock Out" : item.autoCreateProduct ? "Auto Stock + Sold" : "Manual Accessories"}
                    </div>
                  </td>
                  <td style={{ ...styles.tdCell, textAlign: 'center' }}>{item.qty}</td>
                  <td style={{ ...styles.tdCell, textAlign: 'center' }}>{item.rate}</td>
                  <td style={{ ...styles.tdCell, textAlign: 'right', paddingRight: '20px', fontWeight: 'bold', color: '#0F2963' }}>{item.qty * item.rate}</td>
                  <td className="no-print" style={{ ...styles.tdCell, textAlign: 'center' }}>
                    <button onClick={() => editItem(item)} style={styles.rowEditBtn}>Edit</button>
                    <button onClick={() => deleteItem(item.id)} style={styles.rowDeleteBtn}>Delete</button>
                  </td>
                </tr>
              ))}
              {items.length < 5 && Array.from({ length: 5 - items.length }).map((_, emptyIdx) => (
                <tr key={emptyIdx} style={{ height: '40px', borderBottom: '1px solid #CBD5E1' }}>
                  <td style={{ borderRight: '1px solid #B4C6E7' }}></td>
                  <td style={{ borderRight: '1px solid #B4C6E7' }}></td>
                  <td style={{ borderRight: '1px solid #B4C6E7' }}></td>
                  <td style={{ borderRight: '1px solid #B4C6E7' }}></td>
                  <td></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div style={styles.tableBottomSummaryBlock}>
            <div style={styles.totalStack}>
              <div style={styles.totalLine}><span>Subtotal</span><strong>₹ {subTotalAmount}</strong></div>
              {discountValue > 0 && <div style={styles.discountLine}><span>Discount{discountNote ? ` (${discountNote})` : ""}</span><strong>- ₹ {discountValue}</strong></div>}
            </div>
            <div style={styles.totalLabelBlockBackgroundCell}>TOTAL AMOUNT</div>
            <div style={styles.totalValueDisplayDigits}>₹ {totalAmount}</div>
          </div>
          <div style={styles.paymentSummaryLine}>
            <span>Paid: ₹ {paidValue}</span>
            <span>Balance: ₹ {remainingAmount}</span>
          </div>
        </div>

        {/* SIGNATURE AREA */}
        <div style={styles.wordsAndSignatureFlexContainerRow}>
          <div>
            <div style={styles.wordsBlockHeadline}>Amount in Words :</div>
            <div style={styles.wordsValueStringHandwrittenScript}>
              {totalAmount === 720 ? "Seven Hundred Twenty Only" : "Amount Charged Successfully Only"}
            </div>
          </div>
          <div style={styles.signatureBlockUnitBox}>
            <div style={styles.handwrittenSignatureGlyphPlaceholder}>Adarsh Singh</div>
            <div style={styles.signatureSolidStrokeLine}></div>
            <div style={styles.signatureSubtitleLabelText}>Authorised Signature</div>
          </div>
        </div>

        {/* DYNAMIC UPI GATEWAY PANEL */}
        <div style={styles.gatewaysOuterShellContainerFrame}>
          
          <div style={styles.qrBlockUnitContainerColumn}>
            <div style={styles.qrTopLabelPillText}>SCAN &amp; PAY</div>
            <div style={styles.qrGraphicWhiteOuterFrameBox}>
              
              {/* Dynamic Timestamp url automatic load karega bina purani memory fassaye */}
             {/* QR Code section ko bas itna sa change karke dekho */}
<img 
  src={qrSrc} 
  alt="QR Code" 
  style={{ width: '90px', height: '90px', objectFit: 'contain' }}
/>

            </div>
          </div>

          <div style={styles.upiAddressCenterContainerText}>
            <div style={styles.upiLabelStringHeaderTitle}>UPI ID:</div>
            <div style={styles.upiAddressBoldValueString}>{upiId}</div>
            <div style={{ height: '1px', backgroundColor: '#B4C6E7', width: '55%', margin: '6px auto' }}></div>
            <div style={styles.gratitudeNoteItalicFontLine}>Thank you for your payment!</div>
            <div style={{ fontSize: '13px', marginTop: '3px' }}>💙</div>
          </div>

          <div style={styles.gatewaysRightContainerPanel}>
            <div style={styles.weAcceptTitleStringText}>WE ACCEPT</div>
            <div style={styles.badgesWrapperFlexHorizontalRow}>
              <div style={styles.gpayBadgeBox}><span style={{ color: '#4285F4', fontWeight: 'bold' }}>G</span><span style={{ color: '#EA4335' }}>Pay</span></div>
              <div style={styles.phonepeBadgeBox}>
                <div style={{ color: '#FFF', fontWeight: 'bold', fontSize: '11px' }}>पे</div>
                <div style={{ color: '#FFF', fontSize: '7px', marginTop: '-2px' }}>PhonePe</div>
              </div>
              <div style={styles.paytmBadgeBox}><span style={{ color: '#002E6E', fontWeight: 'bold', fontStyle: 'italic' }}>paytm</span></div>
            </div>
            <div style={styles.acceptedChannelsSubtitleSummaryText}>CASH / CARD / UPI</div>
          </div>

        </div>

        <div className="no-print" style={styles.billBottomActionBar}>
          <button onClick={handleSaveBill} disabled={isSaving} style={isSaving ? { ...styles.insertRowBtn, opacity: 0.65, cursor: "not-allowed" } : styles.insertRowBtn}>{isSaving ? "Saving..." : "Save Bill"}</button>
          <button onClick={handlePrint} style={styles.printActionBtn}>Print</button>
          <button onClick={handleDownloadPdf} style={styles.downloadActionBtn}>Download PDF</button>
          <button onClick={handleWhatsAppSend} style={styles.whatsappActionBtn}>WhatsApp Send</button>
        </div>

        {/* SOLID FOOTER IDENTITY */}
        <div style={styles.solidNavyIdentityRibbonFooterStripBar}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '2px' }}>
            <span style={{ fontSize: '9px', color: '#94A3B8' }}>Thank you for choosing</span>
            <span style={{ fontSize: '11px', fontWeight: 'bold', color: '#FFFFFF' }}>Vaishnav Car Wash and Accessories.</span>
            <span style={{ fontSize: '9px', color: '#94A3B8' }}>We value your trust.</span>
          </div>
          <div style={styles.footerCenterVisitEmphasisText}>
            — Thank You! Visit Again —
            <div style={{ display: 'flex', gap: '15px', marginTop: '4px', fontSize: '12px', fontWeight: 'bold', color: '#FFF' }}>
              <span>📞 7985433770</span>
              <span>📸 vaishnav_car_wash</span>
            </div>
          </div>
          <div style={{ textAlign: 'right', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
            <div style={{ color: '#F9D989', fontWeight: 'bold', fontSize: '10px', letterSpacing: '0.5px' }}>CARE THAT</div>
            <div style={{ color: '#FFFFFF', fontWeight: 'bold', fontSize: '12px', marginTop: '2px' }}>MAKES A DIFFERENCE</div>
          </div>
        </div>

      </div>

      {/* STYLES ME PRINT KA SAKHT LAW CHALA DIYA HAI */}
      
      
       

    </div>
  );
}

function uniqueInvoicesByNumber(invoiceList) {
  const unique = new Map();

  invoiceList.forEach((invoice) => {
    const key = invoice?.invoiceNumber ? String(invoice.invoiceNumber).trim().toLowerCase() : `id-${invoice?.id}`;
    const current = unique.get(key);
    if (!current || Number(invoice?.id || 0) >= Number(current?.id || 0)) {
      unique.set(key, invoice);
    }
  });

  return Array.from(unique.values());
}

function getProductIdentity(product) {
  return [
    product?.make,
    product?.model,
    product?.serialNumber ? `SR ${product.serialNumber}` : "",
    product?.barcode ? `Barcode ${product.barcode}` : "",
    product?.id ? `ID ${product.id}` : ""
  ]
    .filter(Boolean)
    .join(" | ");
}

function getProductSearchText(product) {
  return [
    product?.productName,
    product?.brand,
    product?.make,
    product?.model,
    product?.serialNumber,
    product?.barcode,
    product?.category,
    product?.sellPrice,
    product?.purchasePrice,
    product?.id
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
}

function getManualModeForItem(item) {
  if (item?.productId) return "inventory";
  if (item?.category === "service" || item?.itemCategory === "service" || isServiceText(item?.desc)) return "service";
  if (item?.itemCategory === "old_accessory" || item?.category === "old_accessories") return "old_accessory";
  if (item?.autoCreateProduct || item?.itemCategory === "direct_stock_accessory") return "direct_stock";
  return "manual_accessory";
}

const styles = {
  topControlPanel: { maxWidth: '210mm', margin: '0 auto 20px auto', padding: '16px', background: '#FFF', borderRadius: '6px', boxShadow: '0 4px 10px rgba(0,0,0,0.1)', border: '1px solid #CBD5E1' },
  actionButtonsRow: { display: 'flex', gap: '8px', flexWrap: 'wrap', justifyContent: 'flex-end' },
  qrControlsRow: { display: 'flex', gap: '10px', marginTop: '10px', flexWrap: 'wrap' },
  productSearchBox: { flex: 2.6, minWidth: '320px', position: 'relative' },
  productResultList: { position: 'absolute', zIndex: 20, top: '44px', left: 0, right: 0, maxHeight: '260px', overflowY: 'auto', background: '#FFFFFF', border: '1px solid #CBD5E1', borderRadius: '6px', boxShadow: '0 10px 24px rgba(15,23,42,0.18)', padding: '6px' },
  productResultBtn: { width: '100%', border: '1px solid #E2E8F0', background: '#FFFFFF', color: '#0F172A', borderRadius: '5px', padding: '8px', marginBottom: '6px', textAlign: 'left', display: 'grid', gap: '2px', cursor: 'pointer', fontSize: '12px' },
  productResultLowStock: { width: '100%', border: '1px solid #FDBA74', background: '#FFF7ED', color: '#9A3412', borderRadius: '5px', padding: '8px', marginBottom: '6px', textAlign: 'left', display: 'grid', gap: '2px', cursor: 'pointer', fontSize: '12px' },
  productNoResult: { padding: '12px', color: '#64748B', fontSize: '12px', fontWeight: 'bold' },
  selectedProductPill: { marginTop: '-4px', marginBottom: '8px', background: '#ECFDF5', color: '#047857', border: '1px solid #A7F3D0', borderRadius: '999px', padding: '5px 9px', width: 'fit-content', maxWidth: '100%', fontSize: '11px', fontWeight: '900' },
  historyStrip: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '12px', borderTop: '1px solid #E2E8F0', paddingTop: '12px' },
  historyItem: { display: 'flex', alignItems: 'center', gap: '5px', background: '#F8FAFC', border: '1px solid #CBD5E1', borderRadius: '4px', padding: '3px' },
  historyBtn: { border: '1px solid #CBD5E1', background: '#F8FAFC', color: '#0F2963', borderRadius: '4px', padding: '7px 10px', cursor: 'pointer', fontSize: '12px', fontWeight: 'bold' },
  historyDeleteBtn: { border: 'none', background: '#DC2626', color: '#FFFFFF', borderRadius: '4px', padding: '7px 9px', cursor: 'pointer', fontSize: '12px', fontWeight: 'bold' },
  splitSummaryBar: { display: 'flex', gap: '10px', flexWrap: 'wrap', background: '#F8FAFC', border: '1px solid #CBD5E1', borderRadius: '4px', padding: '9px 10px', marginBottom: '12px', color: '#0F2963', fontSize: '12px', fontWeight: 'bold' },
  downloadActionBtn: { background: '#FFFFFF', color: '#0F2963', border: '1px solid #0F2963', padding: '8px 15px', fontWeight: 'bold', borderRadius: '4px', cursor: 'pointer', fontSize: '12px' },
  whatsappActionBtn: { background: '#128C7E', color: '#FFF', border: 'none', padding: '8px 15px', fontWeight: 'bold', borderRadius: '4px', cursor: 'pointer', fontSize: '12px' },
  billBottomActionBar: { display: 'flex', justifyContent: 'center', gap: '10px', margin: '10px 0', position: 'relative', zIndex: 5 },
  rowEditBtn: { border: '1px solid #0F2963', background: '#FFFFFF', color: '#0F2963', borderRadius: '4px', padding: '4px 6px', cursor: 'pointer', fontSize: '10px', marginRight: '4px' },
  rowDeleteBtn: { border: 'none', background: '#DC2626', color: '#FFFFFF', borderRadius: '4px', padding: '4px 6px', cursor: 'pointer', fontSize: '10px' },
  panelInput: { padding: '8px 10px', border: '1px solid #CBD5E1', borderRadius: '4px', fontSize: '12px', boxSizing: 'border-box', outline: 'none' },
  printActionBtn: { background: '#0F2963', color: '#FFF', border: 'none', padding: '8px 15px', fontWeight: 'bold', borderRadius: '4px', cursor: 'pointer', fontSize: '12px' },
  insertRowBtn: { background: '#C49A45', color: '#FFF', border: 'none', padding: '8px 14px', fontWeight: 'bold', borderRadius: '4px', cursor: 'pointer', fontSize: '12px' },
  a4SheetFramework: { width: '210mm', height: '297mm', backgroundColor: '#FFFFFF', padding: '10mm 14mm 8mm 14mm', boxSizing: 'border-box', boxShadow: '0 10px 25px rgba(0,0,0,0.08)', border: '1.5px solid #0F2963', outline: '4px double #C49A45', outlineOffset: '-7px', position: 'relative', display: 'flex', flexDirection: 'column', margin: '0 auto' },
  screenBillSheet: { minHeight: '297mm', height: 'auto', overflow: 'visible', marginBottom: '50px' },
  headerRow: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', height: '108px' },
  headerCenterText: { flexGrow: 1, textAlign: 'center', display: 'flex', flexDirection: 'column', justifyContent: 'center' },
  mainTitleHeader: { fontSize: '50px', fontWeight: '900', color: '#0F2963', margin: '0', letterSpacing: '3px', fontFamily: '"Impact", Arial, sans-serif' },
  subTitleServicesHeader: { fontSize: '15px', fontWeight: 'bold', color: '#9C742A', margin: '4px 0 0 0', letterSpacing: '0.5px' },
  lineSpacerContainer: { display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '4px auto', width: '80%' },
  slimBlueLine: { flexGrow: 1, height: '1px', backgroundColor: '#0F2963' },
  diamondCrest: { color: '#0F2963', fontSize: '9px', padding: '0 6px' },
  servicesGridRow: { display: 'flex', justifyContent: 'center', gap: '15px', marginTop: '4px' },
  serviceItem: { display: 'flex', flexDirection: 'column', alignItems: 'center', width: '65px' },
  serviceIcon: { background: '#0F2963', borderRadius: '4px', width: '22px', height: '22px', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#FFF', fontSize: '11px', marginBottom: '2px' },
  serviceText: { fontSize: '8px', fontWeight: 'bold', color: '#0F2963', whiteSpace: 'nowrap' },
  printSafeLogo: { width: '112px', height: '112px', borderRadius: '50%', backgroundColor: '#0F2963', border: '5px solid #C49A45', color: '#F9D989', display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', boxSizing: 'border-box', boxShadow: '0 4px 10px rgba(15,41,99,0.18)' },
  printSafeLogoInner: { fontFamily: 'Georgia, serif', fontSize: '44px', fontWeight: '900', lineHeight: '42px', color: '#F9D989' },
  printSafeLogoTop: { fontSize: '8px', fontWeight: '900', letterSpacing: '1.4px', marginTop: '2px' },
  printSafeLogoBottom: { fontSize: '7px', fontWeight: '800', letterSpacing: '1.2px', marginTop: '2px' },
  headerRightCarAsset: { width: '170px', height: '108px', overflow: 'hidden', display: 'flex', alignItems: 'center' },
  premiumBadgePanel: { width: '168px', height: '104px', border: '1.5px solid #C49A45', borderRadius: '7px', background: 'linear-gradient(135deg, #071635 0%, #0F2963 52%, #071635 100%)', color: '#F9D989', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', boxShadow: 'inset 0 0 0 2px rgba(249,217,137,0.16)', position: 'relative', overflow: 'hidden' },
  premiumBadgeTop: { fontSize: '9px', fontWeight: '900', letterSpacing: '2px', marginBottom: '4px' },
  premiumBadgeCircle: { width: '42px', height: '42px', borderRadius: '50%', border: '2px solid #F9D989', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'radial-gradient(circle, rgba(249,217,137,0.18), rgba(7,22,53,0.55))' },
  premiumBadgeInner: { fontFamily: 'Georgia, serif', fontSize: '27px', fontWeight: '900', lineHeight: 1 },
  premiumBadgeMain: { fontSize: '10px', fontWeight: '900', letterSpacing: '1px', marginTop: '6px', color: '#FFFFFF' },
  premiumBadgeSub: { fontSize: '7px', fontWeight: '700', letterSpacing: '0.8px', marginTop: '3px', color: '#F9D989' },
  logisticsBlockRow: { display: 'flex', justifyContent: 'space-between', marginBottom: '10px' },
  invoiceMainHeadingBadge: { fontSize: '34px', fontWeight: '900', color: '#0F2963', margin: '0', letterSpacing: '1px', fontFamily: '"Times New Roman", serif' },
  clientMetaLine: { display: 'flex', alignItems: 'center', fontSize: '14px' },
  clientKeyLabel: { width: '110px', fontWeight: 'bold', color: '#0F2963' },
  clientColonSpacer: { width: '15px', color: '#0F2963', fontWeight: 'bold' },
  clientValueUnderline: { flexGrow: 1, borderBottom: '1.5px solid #0F2963', color: '#0F2963', fontWeight: 'bold', fontSize: '14px', paddingLeft: '4px', paddingBottom: '1px' },
  rightLogisticsLine: { display: 'flex', fontSize: '14px', alignItems: 'center' },
  rightLogisticsKey: { fontWeight: 'bold', color: '#0F2963', width: '55px' },
  rightLogisticsValue: { fontWeight: 'bold', color: '#0F2963', paddingLeft: '10px' },
  tableBorderBoxContainerFrame: { border: '2px solid #0F2963', borderRadius: '6px', position: 'relative', overflow: 'hidden', backgroundColor: '#FFFFFF', marginTop: '5px' },
  tableCenterWatermarkBg: { position: 'absolute', top: '45%', left: '50%', transform: 'translate(-50%, -50%)', zIndex: 0, opacity: 0.04, fontSize: '160px', fontWeight: '900', color: '#0F2963', fontFamily: '"Times New Roman", serif' },
  ledgerMainTableElement: { width: '100%', borderCollapse: 'collapse', position: 'relative', zIndex: 1, background: 'transparent' },
  tableHeaderRowBackgroundStrip: { backgroundColor: '#0F2963' },
  thCell: { color: '#FFFFFF', padding: '11px 4px', fontSize: '11px', fontWeight: 'bold', borderRight: '1px solid #FFFFFF' },
  ledgerDataRowHeightBorder: { borderBottom: '1px dashed #CBD5E1', height: '34px' },
  tdCell: { padding: '10px 8px', fontSize: '13px', borderRight: '1px solid #A2B9E3' },
  itemIdentityText: { fontSize: '9px', color: '#64748B', fontWeight: '700', marginTop: '3px', lineHeight: '1.2' },
  serviceTag: { display: 'inline-block', marginTop: '4px', background: '#ECFDF5', color: '#047857', border: '1px solid #A7F3D0', borderRadius: '999px', padding: '2px 7px', fontSize: '9px', fontWeight: '900' },
  accessoryTag: { display: 'inline-block', marginTop: '4px', background: '#EFF6FF', color: '#0F2963', border: '1px solid #BFDBFE', borderRadius: '999px', padding: '2px 7px', fontSize: '9px', fontWeight: '900' },
  tableBottomSummaryBlock: { display: 'flex', justifyContent: 'flex-end', borderTop: '2px solid #0F2963', position: 'relative', zIndex: 2, backgroundColor: '#FFF' },
  totalStack: { minWidth: '190px', padding: '7px 14px', borderLeft: '1px solid #CBD5E1', fontSize: '11px', color: '#0F2963', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: '3px' },
  totalLine: { display: 'flex', justifyContent: 'space-between', gap: '18px', fontWeight: 'bold' },
  discountLine: { display: 'flex', justifyContent: 'space-between', gap: '18px', color: '#B91C1C', fontWeight: 'bold' },
  paymentSummaryLine: { display: 'flex', justifyContent: 'flex-end', gap: '28px', padding: '8px 18px', fontSize: '12px', fontWeight: 'bold', color: '#0F2963', borderTop: '1px solid #CBD5E1', position: 'relative', zIndex: 2, backgroundColor: '#F8FAFC' },
  totalLabelBlockBackgroundCell: { backgroundColor: '#0F2963', color: '#FFFFFF', fontWeight: 'bold', fontSize: '11px', padding: '12px 24px', width: '130px', textAlign: 'center' },
  totalValueDisplayDigits: { width: '140px', textAlign: 'center', fontSize: '20px', fontWeight: '900', color: '#0F2963', alignSelf: 'center' },
  wordsAndSignatureFlexContainerRow: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', margin: '12px 0 10px 0', flexGrow: 0 },
  wordsBlockHeadline: { fontSize: '13px', fontWeight: 'bold', color: '#0F2963' },
  wordsValueStringHandwrittenScript: { fontFamily: 'Courier, monospace', fontSize: '15px', fontWeight: 'bold', color: '#0F2963', marginTop: '6px', borderBottom: '1px dashed #0F2963', paddingBottom: '2px', fontStyle: 'italic' },
  signatureBlockUnitBox: { textAlign: 'center', width: '190px' },
  handwrittenSignatureGlyphPlaceholder: { fontFamily: '"Georgia", serif', fontStyle: 'italic', fontSize: '18px', color: '#0F2963', fontWeight: 'bold', marginBottom: '2px' },
  signatureSolidStrokeLine: { borderTop: '1px solid #0F2963', width: '100%' },
  signatureSubtitleLabelText: { fontSize: '11px', color: '#0F2963', fontWeight: 'bold', marginTop: '6px' },
  gatewaysOuterShellContainerFrame: { border: '1.5px solid #0F2963', borderRadius: '6px', padding: '8px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', backgroundColor: '#FFF', marginBottom: '6px' },
  qrBlockUnitContainerColumn: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' },
  qrTopLabelPillText: { background: '#0F2963', color: '#FFF', fontSize: '9px', fontWeight: 'bold', padding: '2px 12px', borderRadius: '4px' },
  qrGraphicWhiteOuterFrameBox: { border: '1.5px solid #0F2963', borderRadius: '4px', padding: '4px', backgroundColor: '#FFF', display: 'flex', alignItems: 'center', justifyContent: 'center', width: '102px', height: '102px' },
  upiAddressCenterContainerText: { textAlign: 'center', flexGrow: 1 },
  upiLabelStringHeaderTitle: { fontSize: '11px', color: '#64748B', fontWeight: 'bold' },
  upiAddressBoldValueString: { fontSize: '16px', fontWeight: '900', color: '#0F2963', margin: '2px 0' },
  gratitudeNoteItalicFontLine: { fontFamily: 'Georgia, serif', fontStyle: 'italic', fontSize: '13px', color: '#0F2963', fontWeight: 'bold' },
  gatewaysRightContainerPanel: { textAlign: 'center', width: '175px' },
  weAcceptTitleStringText: { fontSize: '11px', fontWeight: 'bold', color: '#64748B', marginBottom: '5px' },
  badgesWrapperFlexHorizontalRow: { display: 'flex', gap: '5px', justifyContent: 'center', marginBottom: '6px' },
  gpayBadgeBox: { border: '1px solid #CBD5E1', borderRadius: '4px', padding: '2px 6px', fontSize: '11px' },
  phonepeBadgeBox: { background: '#5F259F', borderRadius: '4px', padding: '2px 7px', textAlign: 'center', minWidth: '32px' },
  paytmBadgeBox: { border: '1px solid #CBD5E1', borderRadius: '4px', padding: '2px 5px', display: 'flex', alignItems: 'center' },
  acceptedChannelsSubtitleSummaryText: { fontSize: '9px', fontWeight: 'bold', color: '#0F2963' },
  solidNavyIdentityRibbonFooterStripBar: { backgroundColor: '#071635', margin: '0 -14px -10px -14px', padding: '12px 16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottomLeftRadius: '4px', borderBottomRightRadius: '4px' },
  footerCenterVisitEmphasisText: { color: '#F9D989', fontWeight: 'bold', fontSize: '13px', textAlign: 'center', fontStyle: 'italic' }
};
