import { formatInvoiceCategory, getInvoiceCategoryTotals, getInvoiceProfit, isWashingEntry } from "./api";

export function getCurrentMonthKey() {
  return getMonthKey(new Date());
}

export function getMonthKey(value) {
  if (!value) return "";
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 7);
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${date.getFullYear()}-${month}`;
}

export function getMonthLabel(monthKey) {
  const [year, month] = String(monthKey || "").split("-");
  const date = new Date(Number(year), Number(month || 1) - 1, 1);
  if (Number.isNaN(date.getTime())) return monthKey || "Selected Month";
  return date.toLocaleDateString("en-IN", { month: "long", year: "numeric" });
}

export function isInMonth(value, monthKey) {
  return getMonthKey(value) === monthKey;
}

export function uniqueInvoicesByNumber(invoiceList) {
  const unique = new Map();

  (invoiceList || []).forEach((invoice) => {
    const key = invoice?.invoiceNumber ? String(invoice.invoiceNumber).trim().toLowerCase() : `id-${invoice?.id}`;
    const current = unique.get(key);
    if (!current || Number(invoice?.id || 0) >= Number(current?.id || 0)) {
      unique.set(key, invoice);
    }
  });

  return Array.from(unique.values());
}

export function calculateReport({ invoices = [], dailyBook = [], monthKey = getCurrentMonthKey() }) {
  const monthInvoices = uniqueInvoicesByNumber(invoices).filter((invoice) =>
    isInMonth(invoice.invoiceDate || invoice.createdDate, monthKey)
  );
  const monthDailyBook = (dailyBook || []).filter((entry) =>
    isInMonth(entry.entryDate || entry.createdDate, monthKey)
  );
  const invoiceNumberSet = new Set(
    monthInvoices
      .map((invoice) => invoice.invoiceNumber)
      .filter(Boolean)
      .map((invoiceNumber) => String(invoiceNumber).trim().toLowerCase())
  );

  const fromInvoices = monthInvoices.reduce(
    (sum, invoice) => {
      const split = getInvoiceCategoryTotals(invoice);
      sum.washing += split.serviceProfit;
      sum.accessories += split.accessories;
      sum.accessoriesProfit += split.accessoriesProfit;
      if (Number(invoice.remainingAmount || 0) > 0) sum.udhar += Number(invoice.remainingAmount || 0);
      return sum;
    },
    { washing: 0, accessories: 0, accessoriesProfit: 0, expense: 0, udhar: 0 }
  );

  const totals = monthDailyBook.reduce((sum, entry) => {
    const note = String(entry.note || "").toLowerCase();
    const isInvoiceEntry = note.includes("invoice ");
    const isInvoiceMirror = isInvoiceEntry && Array.from(invoiceNumberSet).some((invoiceNumber) => note.includes(invoiceNumber));
    const isUdhar = entry.paymentStatus === "udhar";

    if (isUdhar) {
      if (isInvoiceMirror) return sum;
      sum.udhar += Number(entry.amount || 0);
      return sum;
    }

    if (isInvoiceEntry && entry.entryType === "income") return sum;
    if (entry.entryType === "income" && isWashingEntry(entry)) sum.washing += Number(entry.amount || 0);
    if (entry.entryType === "income" && !isWashingEntry(entry)) {
      const amount = Number(entry.amount || 0);
      sum.accessories += amount;
      sum.accessoriesProfit += amount;
    }
    if (entry.entryType === "expense") sum.expense += Number(entry.amount || 0);
    return sum;
  }, fromInvoices);

  const washingProfit = totals.washing;
  const grossProfit = washingProfit + totals.accessoriesProfit;
  const netProfit = grossProfit - totals.expense;
  const invoiceTotal = monthInvoices.reduce((sum, invoice) => sum + Number(invoice.totalAmount || 0), 0);

  return {
    monthKey,
    monthLabel: getMonthLabel(monthKey),
    invoices: monthInvoices,
    dailyBook: monthDailyBook,
    totals,
    washingProfit,
    grossProfit,
    netProfit,
    invoiceTotal
  };
}

export function getStatementRows({ invoices = [], dailyBook = [], monthKey = getCurrentMonthKey() }) {
  const report = calculateReport({ invoices, dailyBook, monthKey });
  const invoiceNumberSet = new Set(
    report.invoices
      .map((invoice) => invoice.invoiceNumber)
      .filter(Boolean)
      .map((invoiceNumber) => String(invoiceNumber).trim().toLowerCase())
  );

  const invoiceRows = report.invoices.map((invoice) => ({
    id: `invoice-${invoice.id}`,
    date: invoice.invoiceDate || invoice.createdDate,
    type: "Bill",
    party: invoice.customer?.customerName || "Walk-in",
    note: `${invoice.invoiceNumber || "-"} | ${formatInvoiceCategory(invoice)}`,
    income: Number(invoice.totalAmount || 0),
    expense: 0,
    udhar: Number(invoice.remainingAmount || 0),
    profit: getInvoiceProfit(invoice)
  }));

  const dailyRows = report.dailyBook
    .filter((entry) => {
      const note = String(entry.note || "").toLowerCase();
      const isInvoiceEntry = note.includes("invoice ");
      return !(isInvoiceEntry && Array.from(invoiceNumberSet).some((invoiceNumber) => note.includes(invoiceNumber)));
    })
    .map((entry) => {
      const isExpense = entry.entryType === "expense";
      const isUdhar = entry.paymentStatus === "udhar";
      const amount = Number(entry.amount || 0);
      return {
        id: `daily-${entry.id}`,
        date: entry.entryDate || entry.createdDate,
        type: isExpense ? "Expense" : isUdhar ? "Udhar" : isWashingEntry(entry) ? "Washing / Labour" : "Accessories",
        party: entry.partyName || "-",
        note: entry.note || "-",
        income: !isExpense && !isUdhar ? amount : 0,
        expense: isExpense ? amount : 0,
        udhar: isUdhar ? amount : 0,
        profit: !isExpense && !isUdhar ? amount : 0
      };
    });

  let balance = 0;
  return [...invoiceRows, ...dailyRows]
    .sort((a, b) => new Date(a.date || 0) - new Date(b.date || 0))
    .map((row) => {
      balance += Number(row.income || 0) - Number(row.expense || 0);
      return { ...row, balance };
    });
}
