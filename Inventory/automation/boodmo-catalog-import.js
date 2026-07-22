const { chromium } = require("playwright");
const fs = require("fs");

const BACKEND_URL = String(
  process.env.BACKEND_URL || "https://vaishnav-inventory.onrender.com"
).replace(/\/+$/, "");
const MAX_PRODUCTS = Math.max(1, Number(process.env.MAX_PRODUCTS || 25));
const MAX_CATALOG_PAGES = Math.max(5, Number(process.env.MAX_CATALOG_PAGES || 120));
const DRY_RUN = String(process.env.DRY_RUN || "true").toLowerCase() !== "false";
const HEADLESS = String(process.env.HEADLESS || "true").toLowerCase() !== "false";
const DEFAULT_CHROME_PATH = "/Users/adarshsingh/Library/Caches/ms-playwright/chromium-1223/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const CHROME_PATH = process.env.PLAYWRIGHT_CHROME_PATH || DEFAULT_CHROME_PATH;
const CATALOG_URL = clean(process.env.CATALOG_URL);

const vehicle = {
  make: process.env.VEHICLE_MAKE || "MAHINDRA",
  model: process.env.VEHICLE_MODEL || "BOLERO",
  year: process.env.VEHICLE_YEAR || "2020",
  modification: process.env.VEHICLE_MODIFICATION || "1.5L B2 MT/Diesel/BS6"
};

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function clean(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function number(value) {
  const parsed = Number(String(value || "").replace(/[^\d.]/g, ""));
  return Number.isFinite(parsed) ? parsed : null;
}

function yearRange(value) {
  const years = String(value || "").match(/\b(?:19|20)\d{2}\b/g) || [];
  return {
    from: years[0] ? Number(years[0]) : Number(vehicle.year),
    to: years[1] ? Number(years[1]) : Number(vehicle.year)
  };
}

function categoryFromBreadcrumb(items) {
  const ignored = new Set(["boodmo", "catalogues"]);
  const values = items.map(clean).filter((item) => item && !ignored.has(item.toLowerCase()));
  return values.length > 1 ? values[values.length - 2] : values[0] || "OEM Spare";
}

async function openVehicleCatalog(page) {
  if (CATALOG_URL) {
    await page.goto(CATALOG_URL, { waitUntil: "domcontentloaded", timeout: 45000 });
    await page.waitForTimeout(1200);
    if (!page.url().includes("oriparts.com")) {
      throw new Error(`Direct OEM catalog open nahi hua: ${page.url()}`);
    }
    return page;
  }
  await page.goto("https://boodmo.com/", { waitUntil: "domcontentloaded", timeout: 45000 });
  const selects = page.locator("select");
  await selects.nth(0).selectOption({ label: vehicle.make });
  await page.waitForTimeout(900);
  await selects.nth(1).selectOption({ label: vehicle.model });
  await page.waitForTimeout(900);
  await selects.nth(2).selectOption({ label: vehicle.year });
  await page.waitForTimeout(900);
  await selects.nth(3).selectOption({ label: vehicle.modification });
  await page.waitForTimeout(700);

  const oemButton = page.getByText("OEM Catalog", { exact: true });
  if (await oemButton.count() === 0) {
    throw new Error(`OEM Catalog nahi mila: ${JSON.stringify(vehicle)}`);
  }

  const popupPromise = page.context().waitForEvent("page", { timeout: 7000 }).catch(() => null);
  await oemButton.click();
  const popup = await popupPromise;
  const catalogPage = popup || page;
  await catalogPage.waitForLoadState("domcontentloaded", { timeout: 45000 }).catch(() => {});
  await catalogPage.waitForTimeout(1200);
  if (!catalogPage.url().includes("oriparts.com")) {
    throw new Error(`OriParts OEM catalog open nahi hua: ${catalogPage.url()}`);
  }
  return catalogPage;
}

async function collectProductRedirects(catalogPage) {
  const root = new URL(catalogPage.url());
  const rootParts = root.pathname.split("/").filter(Boolean);
  const prefix = `/${rootParts.slice(0, 3).join("/")}/`;
  console.log(`[catalog-root] url=${root.href} prefix=${prefix}`);
  const queue = [catalogPage.url()];
  const visited = new Set();
  const productRedirects = new Set();

  while (queue.length && visited.size < MAX_CATALOG_PAGES && productRedirects.size < MAX_PRODUCTS) {
    const url = queue.shift();
    if (!url || visited.has(url)) continue;
    visited.add(url);
    await catalogPage.goto(url, { waitUntil: "domcontentloaded", timeout: 35000 });
    await catalogPage.waitForTimeout(250);

    const bodyText = clean(await catalogPage.locator("body").innerText());
    if (/sorry, you have been blocked|unable to access oriparts/i.test(bodyText)) {
      throw new Error(
        "OriParts ne separate automation browser block kiya. In-app browser session se manual verified import use karo."
      );
    }

    const redirectLinks = await catalogPage.locator('a[href*="/redirect/product/"]')
      .evaluateAll((links) => links.map((link) => link.href));
    redirectLinks.forEach((href) => productRedirects.add(href));

    const catalogLinks = await catalogPage.locator(`a[href*="${prefix}"]`)
      .evaluateAll((links) => links.map((link) => link.href));
    if (visited.size === 1 && catalogLinks.length === 0) {
      console.log(`[catalog-root] body=${bodyText.slice(0, 500)}`);
    }
    for (const href of catalogLinks) {
      const parsed = new URL(href);
      if (
        parsed.origin === root.origin
        && parsed.pathname.startsWith(prefix)
        && parsed.pathname !== root.pathname
        && !visited.has(href)
      ) {
        queue.push(href);
      }
    }

    console.log(
      `[catalog] pages=${visited.size} queued=${queue.length} products=${productRedirects.size}`
    );
  }

  return [...productRedirects].slice(0, MAX_PRODUCTS);
}

async function readProduct(productPage, redirectUrl) {
  await productPage.goto(redirectUrl, { waitUntil: "domcontentloaded", timeout: 45000 });
  await productPage.waitForTimeout(700);

  const productScripts = productPage.locator('script[type="application/ld+json"]');
  const scripts = await productScripts.allTextContents();
  const schemas = scripts.flatMap((text) => {
    try {
      const parsed = JSON.parse(text);
      return Array.isArray(parsed) ? parsed : [parsed];
    } catch {
      return [];
    }
  });
  const product = schemas.find((item) => item && item["@type"] === "Product");
  if (!product || !product.sku || !product.name) return null;

  const body = clean(await productPage.locator("body").innerText());
  const breadcrumbs = schemas.find((item) => item && item["@type"] === "BreadcrumbList");
  const breadcrumbNames = (breadcrumbs?.itemListElement || [])
    .map((entry) => entry?.item?.name)
    .filter(Boolean);
  const maskedPartNumber = clean(product.mpn);
  const price = number(product.offers?.lowPrice);
  const range = yearRange(body.match(/PRODUCTION DATE:[^.]+/i)?.[0] || "");
  const sourceUrl = product.url || productPage.url();
  const isMasked = maskedPartNumber.includes("...");

  return {
    name: clean(product.name),
    localName: clean(product.name),
    brand: clean(product.brand?.name || vehicle.make),
    category: categoryFromBreadcrumb(breadcrumbNames),
    partType: "OEM",
    oemPartNumber: isMasked ? null : maskedPartNumber,
    aftermarketPartNumber: null,
    hsnCode: null,
    supplier: "Boodmo marketplace",
    wholesalePrice: null,
    retailPrice: price,
    bargainingPrice: price,
    stockQuantity: 0,
    minimumStock: 0,
    photoUrl: clean(product.image),
    sourceUrl,
    verificationStatus: isMasked
      ? "BOODMO_PRICE_FITMENT_VERIFIED_PART_NUMBER_MASKED"
      : "BOODMO_VERIFIED",
    notes: [
      `Boodmo SKU: ${product.sku}`,
      isMasked ? `Boodmo masked OEM number: ${maskedPartNumber}` : null,
      `Current Boodmo price checked by importer: ${price == null ? "not available" : `Rs. ${price}`}`,
      "Wholesale price is intentionally blank because Boodmo shows retail marketplace price."
    ].filter(Boolean).join(" | "),
    active: true,
    barcode: `BD-${product.sku}`,
    fitments: [{
      make: vehicle.make,
      model: vehicle.model,
      variant: vehicle.modification,
      yearFrom: range.from,
      yearTo: range.to,
      notes: `Selected through Boodmo OEM catalog for ${vehicle.year}`
    }]
  };
}

async function findExisting(item) {
  const response = await fetch(
    `${BACKEND_URL}/accessories?q=${encodeURIComponent(item.barcode)}&page=0&size=5`
  );
  if (!response.ok) throw new Error(`Existing item search failed: HTTP ${response.status}`);
  const data = await response.json();
  return (data.content || []).find((candidate) => candidate.barcode === item.barcode) || null;
}

async function saveProduct(item) {
  const existing = await findExisting(item);
  const url = existing
    ? `${BACKEND_URL}/accessories/${existing.id}`
    : `${BACKEND_URL}/accessories`;
  const response = await fetch(url, {
    method: existing ? "PUT" : "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...existing, ...item })
  });
  if (!response.ok) {
    throw new Error(`Save failed HTTP ${response.status}: ${(await response.text()).slice(0, 500)}`);
  }
  return { action: existing ? "updated" : "created", item: await response.json() };
}

async function main() {
  console.log(`[start] vehicle=${JSON.stringify(vehicle)} dryRun=${DRY_RUN} max=${MAX_PRODUCTS}`);
  const browser = await chromium.launch({
    headless: HEADLESS,
    ...(fs.existsSync(CHROME_PATH) ? { executablePath: CHROME_PATH } : {})
  });
  try {
    const context = await browser.newContext({
      userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/126 Safari/537.36"
    });
    const selectorPage = await context.newPage();
    const catalogPage = await openVehicleCatalog(selectorPage);
    const redirects = await collectProductRedirects(catalogPage);
    console.log(`[catalog] ${redirects.length} exact Boodmo product redirects found`);

    const productPage = await context.newPage();
    let created = 0;
    let updated = 0;
    let skipped = 0;
    for (const redirect of redirects) {
      try {
        const item = await readProduct(productPage, redirect);
        if (!item) {
          skipped += 1;
          continue;
        }
        if (DRY_RUN) {
          console.log(`[dry-run] ${item.barcode} ${item.name} Rs.${item.retailPrice}`);
        } else {
          const result = await saveProduct(item);
          if (result.action === "created") created += 1;
          else updated += 1;
          console.log(`[${result.action}] ${item.barcode} ${item.name}`);
        }
        await wait(350);
      } catch (error) {
        skipped += 1;
        console.error(`[skip] ${redirect}: ${error.message}`);
      }
    }
    console.log(JSON.stringify({ vehicle, found: redirects.length, created, updated, skipped, dryRun: DRY_RUN }));
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
