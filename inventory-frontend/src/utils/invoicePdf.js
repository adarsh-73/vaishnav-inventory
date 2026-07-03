const A4_WIDTH_MM = 210;
const A4_HEIGHT_MM = 297;
const A4_WIDTH_PX = 794;

function waitForImages(root) {
  const pending = Array.from(root.querySelectorAll("img")).filter((image) => !image.complete);
  if (!pending.length) return Promise.resolve();

  return Promise.all(pending.map((image) => new Promise((resolve) => {
    image.addEventListener("load", resolve, { once: true });
    image.addEventListener("error", resolve, { once: true });
    window.setTimeout(resolve, 4000);
  })));
}

function addCanvasPages(pdf, canvas) {
  const pageHeightPx = Math.floor(canvas.width * (A4_HEIGHT_MM / A4_WIDTH_MM));
  let sourceY = 0;
  let pageNumber = 0;

  while (sourceY < canvas.height) {
    const sliceHeight = Math.min(pageHeightPx, canvas.height - sourceY);
    const pageCanvas = document.createElement("canvas");
    pageCanvas.width = canvas.width;
    pageCanvas.height = pageHeightPx;

    const context = pageCanvas.getContext("2d");
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, pageCanvas.width, pageCanvas.height);
    context.drawImage(
      canvas,
      0,
      sourceY,
      canvas.width,
      sliceHeight,
      0,
      0,
      canvas.width,
      sliceHeight
    );

    if (pageNumber > 0) pdf.addPage("a4", "portrait");
    pdf.addImage(
      pageCanvas.toDataURL("image/png"),
      "PNG",
      0,
      0,
      A4_WIDTH_MM,
      A4_HEIGHT_MM,
      undefined,
      "FAST"
    );

    sourceY += sliceHeight;
    pageNumber += 1;
  }
}

export async function createInvoicePdf(elementId) {
  const source = document.getElementById(elementId);
  if (!source) throw new Error("Bill sheet nahi mila.");

  const [{ jsPDF }, html2canvasModule] = await Promise.all([
    import("jspdf"),
    import("html2canvas")
  ]);

  if (document.fonts?.ready) await document.fonts.ready;

  const staging = document.createElement("div");
  const clone = source.cloneNode(true);
  clone.querySelectorAll(".no-print").forEach((node) => node.remove());

  staging.setAttribute("aria-hidden", "true");
  staging.style.cssText = [
    "position:fixed",
    "left:-12000px",
    "top:0",
    `width:${A4_WIDTH_PX}px`,
    "margin:0",
    "padding:0",
    "background:#fff",
    "overflow:visible",
    "z-index:-1"
  ].join(";");

  clone.style.setProperty("position", "relative", "important");
  clone.style.setProperty("inset", "auto", "important");
  clone.style.setProperty("width", `${A4_WIDTH_PX}px`, "important");
  clone.style.setProperty("min-width", `${A4_WIDTH_PX}px`, "important");
  clone.style.setProperty("max-width", `${A4_WIDTH_PX}px`, "important");
  clone.style.setProperty("height", "auto", "important");
  clone.style.setProperty("min-height", "1123px", "important");
  clone.style.setProperty("max-height", "none", "important");
  clone.style.setProperty("margin", "0", "important");
  clone.style.setProperty("overflow", "visible", "important");
  clone.style.setProperty("transform", "none", "important");
  clone.style.setProperty("zoom", "1", "important");
  clone.style.setProperty("box-shadow", "none", "important");

  staging.appendChild(clone);
  document.body.appendChild(staging);

  try {
    await waitForImages(clone);
    const canvas = await html2canvasModule.default(clone, {
      scale: 2,
      useCORS: true,
      allowTaint: false,
      backgroundColor: "#ffffff",
      logging: false,
      imageTimeout: 8000,
      windowWidth: A4_WIDTH_PX,
      scrollX: 0,
      scrollY: 0,
      onclone: (captureDocument) => {
        const style = captureDocument.createElement("style");
        style.textContent = `
          html, body { margin: 0 !important; padding: 0 !important; background: #fff !important; }
          * { box-sizing: border-box !important; -webkit-font-smoothing: antialiased; }
          .no-print { display: none !important; }
          .invoice-print-sheet, #old-bill-print-sheet {
            position: relative !important;
            inset: auto !important;
            width: ${A4_WIDTH_PX}px !important;
            min-width: ${A4_WIDTH_PX}px !important;
            max-width: ${A4_WIDTH_PX}px !important;
            height: auto !important;
            min-height: 1123px !important;
            max-height: none !important;
            margin: 0 !important;
            overflow: visible !important;
            transform: none !important;
            zoom: 1 !important;
            box-shadow: none !important;
          }
          table { border-collapse: collapse !important; }
          tr, td, th { page-break-inside: avoid !important; }
          h1, h2, h3, p, span, td, th { text-rendering: geometricPrecision; }
        `;
        captureDocument.head.appendChild(style);
      }
    });

    const pdf = new jsPDF({
      orientation: "portrait",
      unit: "mm",
      format: "a4",
      compress: true
    });
    addCanvasPages(pdf, canvas);
    return pdf;
  } finally {
    staging.remove();
  }
}
