function resolveApiBase() {
  if (process.env.REACT_APP_API_BASE) return process.env.REACT_APP_API_BASE;
  if (typeof window !== "undefined" && window.location.hostname.includes("onrender.com")) {
    return "https://vaishnav-inventory.onrender.com";
  }
  return "";
}

export const API_BASE = resolveApiBase();

export async function apiRequest(path, options = {}) {
  const { timeoutMs = 12000, ...fetchOptions } = options;
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);

  const response = await fetch(`${API_BASE}${path}`, {
    ...fetchOptions,
    signal: fetchOptions.signal || controller.signal,
    headers: {
      "Content-Type": "application/json",
      ...(fetchOptions.headers || {})
    }
  }).finally(() => window.clearTimeout(timeoutId));

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || "Backend request failed");
  }

  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

export function isWashingEntry(entry) {
  const text = [
    entry?.incomeCategory,
    entry?.businessCategory,
    entry?.note,
    entry?.description,
    entry?.partyName
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();

  return isServiceText(text);
}

export function isServiceText(value) {
  const text = String(value || "").toLowerCase();
  return [
    "wash",
    "washing",
    "foam",
    "vacuum",
    "polish",
    "detailing",
    "labour",
    "labor",
    "service",
    "work",
    "fitting",
    "feeting",
    "fitment",
    "install",
    "installation",
    "repair",
    "gas cutting",
    "cutting",
    "welding",
    "seat",
    "seating",
    "clean",
    "rubbing",
    "coating",
    "lamination",
    "laminate",
    "denting",
    "painting"
  ].some((keyword) => text.includes(keyword));
}

export function inferInvoiceItemCategory(item) {
  const savedCategory = String(item?.itemCategory || item?.category || "").toLowerCase();
  if (savedCategory.includes("service") || savedCategory.includes("washing") || savedCategory.includes("labour")) return "service";
  if (savedCategory.includes("old_accessory")) return "old_accessories";
  if (savedCategory.includes("manual_accessory") || savedCategory.includes("accessor")) return "accessories";
  if (item?.productInvoiceitem || item?.productId) return "accessories";

  const text = [
    item?.description,
    item?.desc,
    item?.productName,
    item?.name
  ]
    .filter(Boolean)
    .join(" ");

  return isServiceText(text) ? "service" : "accessories";
}

export function getInvoiceCategoryTotals(invoice) {
  const invoiceItems = invoice?.invoiceItems || invoice?.items || [];

  const totals = invoiceItems.reduce(
    (sum, item) => {
      const quantity = Math.max(0, Number(item.quantity ?? item.qty ?? 0) - Number(item.returnedQuantity || 0));
      const saleRate = Number(item.sellPrice ?? item.rate ?? item.productInvoiceitem?.sellPrice ?? 0);
      const costRate = Number(item.purchasePrice ?? item.buyPrice ?? item.productInvoiceitem?.purchasePrice ?? 0);
      const itemSale = Number(item.totalPrice ?? quantity * saleRate);
      const category = inferInvoiceItemCategory(item);

      if (category === "service") {
        sum.service += itemSale;
        sum.serviceProfit += itemSale;
      } else if (category === "old_accessories") {
        sum.oldAccessories += itemSale;
        sum.oldAccessoriesProfit += itemSale;
      } else {
        sum.accessories += itemSale;
        sum.accessoriesProfit += item.productInvoiceitem || item.productId
          ? (saleRate - costRate) * quantity
          : Math.max(0, itemSale - costRate * quantity);
      }

      return sum;
    },
    { service: 0, serviceProfit: 0, accessories: 0, accessoriesProfit: 0, oldAccessories: 0, oldAccessoriesProfit: 0 }
  );

  const discount = Math.max(0, Number(invoice?.discountAmount || 0));
  if (discount > 0) {
    const grossSale = totals.service + totals.accessories + totals.oldAccessories;
    const applyDiscount = (key, profitKey, shareBase) => {
      if (grossSale <= 0 || shareBase <= 0) return;
      const share = discount * (shareBase / grossSale);
      totals[key] = Math.max(0, totals[key] - share);
      totals[profitKey] = Math.max(0, totals[profitKey] - share);
    };
    applyDiscount("service", "serviceProfit", totals.service);
    applyDiscount("accessories", "accessoriesProfit", totals.accessories);
    applyDiscount("oldAccessories", "oldAccessoriesProfit", totals.oldAccessories);
  }

  return totals;
}

export function getInvoiceBusinessCategory(invoice) {
  const totals = getInvoiceCategoryTotals(invoice);
  const accessoryTotal = totals.accessories + totals.oldAccessories;
  if (totals.service > 0 && accessoryTotal > 0) return "mixed";
  if (totals.service > 0) return "washing";
  return "accessories";
}

export function getInvoiceProfit(invoice) {
  const totals = getInvoiceCategoryTotals(invoice);
  return totals.serviceProfit + totals.accessoriesProfit + totals.oldAccessoriesProfit;
}

export function formatInvoiceCategory(invoice) {
  const category = getInvoiceBusinessCategory(invoice);
  if (category === "mixed") return "Mixed";
  if (category === "washing") return "Washing / Labour";
  return "Accessories";
}
