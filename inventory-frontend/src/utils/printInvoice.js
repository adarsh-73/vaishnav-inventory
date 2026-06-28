export function printInvoiceElement(elementId, title = "Vaishnav Premium Invoice") {
  const source = document.getElementById(elementId);
  if (!source) throw new Error("Printable bill sheet nahi mila.");

  const printWindow = window.open("", "_blank", "width=980,height=900");
  if (!printWindow) throw new Error("Print popup block hai. Browser me popups allow karein.");

  const safeTitle = String(title).replace(/[<>&"']/g, "");
  printWindow.document.open();
  printWindow.document.write(`<!doctype html>
    <html>
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width,initial-scale=1" />
        <title>${safeTitle}</title>
        <style>
          @page { size: A4 portrait; margin: 8mm; }
          html, body { margin: 0; padding: 0; background: #eef2f6; }
          body { display: flex; justify-content: center; align-items: flex-start; font-family: Arial, sans-serif; }
          * { box-sizing: border-box; }
          .no-print { display: none !important; }
          .invoice-print-sheet, #old-bill-print-sheet {
            position: relative !important;
            inset: auto !important;
            width: 194mm !important;
            min-width: 194mm !important;
            max-width: 194mm !important;
            height: auto !important;
            min-height: 281mm !important;
            max-height: none !important;
            margin: 0 auto !important;
            padding: 10mm 9mm 5mm !important;
            overflow: visible !important;
            background: #fff !important;
            border: 1.2mm double #0f2963 !important;
            border-radius: 2mm !important;
            outline: .45mm solid #c49a45 !important;
            outline-offset: -3mm !important;
            box-shadow: none !important;
            transform: none !important;
            zoom: 1 !important;
            -webkit-print-color-adjust: exact !important;
            print-color-adjust: exact !important;
          }
          table { page-break-inside: avoid !important; }
          tr { page-break-inside: avoid !important; }
          h1 { line-height: 1.15 !important; overflow: visible !important; padding-top: 1mm !important; }
          @media screen {
            body { padding: 18px 0; }
            .invoice-print-sheet, #old-bill-print-sheet { box-shadow: 0 18px 45px rgba(15,23,42,.18) !important; }
          }
          @media print {
            html, body { width: 194mm !important; min-height: 281mm !important; background: #fff !important; }
            body { display: block !important; }
          }
        </style>
      </head>
      <body>${source.outerHTML}</body>
    </html>`);
  printWindow.document.close();

  const triggerPrint = () => {
    let printed = false;
    const doPrint = () => {
      if (printed) return;
      printed = true;
      printWindow.focus();
      printWindow.print();
    };
    const images = Array.from(printWindow.document.images || []);
    const pendingImages = images.filter((image) => !image.complete);
    if (pendingImages.length) {
      let remaining = pendingImages.length;
      pendingImages.forEach((image) => {
        const done = () => {
          remaining -= 1;
          if (remaining === 0) setTimeout(doPrint, 200);
        };
        image.addEventListener("load", done, { once: true });
        image.addEventListener("error", done, { once: true });
      });
      setTimeout(doPrint, 1800);
      return;
    }
    setTimeout(doPrint, 250);
  };

  if (printWindow.document.readyState === "complete") triggerPrint();
  else printWindow.addEventListener("load", triggerPrint, { once: true });
}
