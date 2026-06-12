const http = require("http");
const { chromium } = require("playwright");

const PORT = Number(process.env.BOODMO_AUTOMATION_PORT || 8090);
const CHROME_PATH = process.env.PLAYWRIGHT_CHROME_PATH
  || "/Users/adarshsingh/Library/Caches/ms-playwright/chromium-1223/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";

let browserPromise;

async function getBrowser() {
  if (!browserPromise) {
    browserPromise = chromium.launch({
      headless: true,
      executablePath: CHROME_PATH
    });
  }

  const browser = await browserPromise.catch(() => null);
  if (!browser || !browser.isConnected()) {
    browserPromise = chromium.launch({
      headless: true,
      executablePath: CHROME_PATH
    });
    return browserPromise;
  }

  return browser;
}

function expandPart(value) {
  const text = String(value || "").toLowerCase();
  if (/fogg|fog|foug/.test(text)) return `${value} fog lamp fog light`;
  if (/dikki|dicky|boot/.test(text)) return `${value} boot tailgate`;
  if (/shocker|shock/.test(text)) return `${value} shock absorber strut`;
  if (/glass machine|window machine/.test(text)) return `${value} power window regulator`;
  if (/side mirror|orpvm|orvm/.test(text)) return `${value} orvm side mirror`;
  if (/bumper clip|clip/.test(text)) return `${value} bumper retainer clip`;
  if (/rear bumper|back bumper/.test(text)) return `${value} rear bumper`;
  if (/front bumper/.test(text)) return `${value} front bumper`;
  if (/bumper/.test(text)) return `${value} bumper`;
  if (/light/.test(text)) return `${value} headlamp tail lamp light`;
  return value;
}

function makeQuery(params) {
  return [
    params.get("make"),
    params.get("model"),
    params.get("year"),
    params.get("variant"),
    expandPart(params.get("part")),
    params.get("position"),
    params.get("detail")
  ]
    .map((item) => String(item || "").trim())
    .filter(Boolean)
    .join(" ");
}

function readJsonBody(req, limitBytes = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (Buffer.byteLength(body) > limitBytes) {
        reject(new Error("Image bahut badi hai. 8MB se chhoti image upload karo."));
        req.destroy();
      }
    });
    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch {
        reject(new Error("Invalid JSON body"));
      }
    });
    req.on("error", reject);
  });
}

function parseVisionText(text) {
  const fallback = { make: "", model: "", year: "", variant: "", part: "", position: "", detail: "", origin: "any", confidence: "low" };
  try {
    const jsonText = String(text || "").replace(/^```json\s*/i, "").replace(/```$/i, "").trim();
    return { ...fallback, ...JSON.parse(jsonText) };
  } catch {
    return { ...fallback, part: cleanText(text).slice(0, 80) };
  }
}

async function identifyImagePart(payload) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return {
      ok: false,
      message: "Image se automatic part pehchanne ke liye AI vision API key set nahi hai. OPENAI_API_KEY set karke npm run boodmo restart karo.",
      suggestions: ["front bumper", "headlight", "fog lamp", "tail light", "orvm mirror", "window regulator", "seat cover"]
    };
  }

  const image = payload.image;
  if (!image || !/^data:image\//.test(image)) {
    return { ok: false, message: "Valid image upload nahi mili." };
  }

  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${apiKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      model: process.env.OPENAI_VISION_MODEL || "gpt-4.1-mini",
      input: [{
        role: "user",
        content: [
          {
            type: "input_text",
            text: "Identify the visible car spare/accessory from this image for an Indian auto parts shop. Return only JSON with keys: make, model, year, variant, part, position, detail, origin, confidence. Use empty strings when unknown. position examples: front right, front left, rear, LH, RH. detail examples: assy, cover, bulb, kit."
          },
          { type: "input_image", image_url: image }
        ]
      }]
    })
  });

  const data = await response.json();
  if (!response.ok) {
    return { ok: false, message: data.error?.message || "AI image identify failed." };
  }

  const text = data.output_text || data.output?.flatMap((item) => item.content || []).map((part) => part.text || "").join("\n");
  return { ok: true, detected: parseVisionText(text), raw: text };
}

function extractPrice(text) {
  const match = String(text || "").match(/(?:₹|Rs\.?|INR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i);
  return match ? `Rs. ${match[1]}` : null;
}

function cleanText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function words(value) {
  return cleanText(value).toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
}

function scoreText(text, terms) {
  const haystack = cleanText(text).toLowerCase();
  return terms.reduce((score, term) => score + (haystack.includes(term) ? 1 : 0), 0);
}

function extractPartNumber(text) {
  const blocked = new Set([
    "CHEVROLET", "HYUNDAI", "MAHINDRA", "MARUTI", "MERCEDES-BENZ", "MITSUBISHI",
    "NISSAN", "RENAULT", "SKODA", "TOYOTA", "VOLVO", "TATA", "FORD", "HONDA"
  ]);
  const patterns = [
    /part\s*(?:no|number|#)\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-\/.]{4,})/i,
    /\b([A-Z0-9]{2,}[-/][A-Z0-9][A-Z0-9\-\/.]{3,})\b/,
    /\b([A-Z]{2,}[0-9][A-Z0-9\-\/.]{4,})\b/
  ];
  for (const pattern of patterns) {
    const match = String(text || "").match(pattern);
    if (match && !blocked.has(match[1].toUpperCase()) && !/^(19|20)\d{2}[-/](19|20)\d{2}$/.test(match[1])) return match[1];
  }
  return null;
}

function slugText(value) {
  return cleanText(value).toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

async function clickHref(page, matcher, label, steps) {
  const href = await page.evaluate((source) => {
    const links = Array.from(document.querySelectorAll("a"));
    const exact = links.find((a) => source.exact && (a.href || "").toLowerCase().includes(source.exact));
    if (exact) return exact.href;
    const textMatch = links.find((a) => {
      const text = String(a.innerText || a.textContent || "").replace(/\s+/g, " ").trim().toLowerCase();
      const href = String(a.href || "").toLowerCase();
      return source.terms.every((term) => text.includes(term) || href.includes(term));
    });
    return textMatch ? textMatch.href : null;
  }, matcher);

  if (!href) return false;
  await page.goto(href, { waitUntil: "domcontentloaded", timeout: 30000 });
  await page.waitForTimeout(2500);
  steps.push(`${label}: ${href}`);
  return true;
}

async function safeGoto(page, url, steps, label, timeout = 18000) {
  try {
    await page.goto(url, { waitUntil: "domcontentloaded", timeout });
    return true;
  } catch (error) {
    steps.push(`${label || "Page"} timeout/slow: ${error.message.split("\n")[0]}`);
    await page.waitForTimeout(1000).catch(() => {});
    return false;
  }
}

async function collectPageData(page) {
  return page.evaluate(() => {
    const norm = (value) => String(value || "").replace(/\s+/g, " ").trim();
    const anchors = Array.from(document.querySelectorAll("a"))
      .map((a) => ({
        text: norm(a.innerText || a.textContent || ""),
        href: a.href
      }))
      .filter((item) => item.href && item.text)
      .slice(0, 180);

    const images = Array.from(document.querySelectorAll("img"))
      .map((img) => img.currentSrc || img.src)
      .filter(Boolean)
      .slice(0, 60);

    return {
      title: document.title || "",
      url: location.href,
      bodyText: document.body ? document.body.innerText.slice(0, 45000) : "",
      anchors,
      images
    };
  });
}

function bestLinksFrom(data, params) {
  const terms = exactTermsFor(params);
  const blocked = blockedCategoryPattern(params);
  const part = cleanText(params.part).toLowerCase();
  return data.anchors
    .map((item) => ({
      title: item.text,
      url: item.href,
      price: extractPrice(item.text),
      score: scoreText(`${item.text} ${item.href}`, terms)
    }))
    .filter((item) =>
      !blocked.test(`${item.title} ${item.url}`)
      && isRelevantLinkForPart(`${item.title} ${item.url}`, part, item.score)
    )
    .sort((a, b) => b.score - a.score)
    .slice(0, 12)
    .map(({ score, ...item }) => item);
}

function isRelevantLinkForPart(value, part, score) {
  const text = cleanText(value).toLowerCase();
  if (/bumper/.test(part)) return /bumper|body|exterior|accessor/.test(text);
  if (/lamp|light|fog|head|tail/.test(part)) return score > 0 && /lamp|light|fog|head|tail|bulb/.test(text);
  if (/mirror|orvm/.test(part)) return score > 0 && /mirror|orvm/.test(text);
  if (/glass|window/.test(part)) return score > 0 && /glass|window|regulator/.test(text);
  if (/brake/.test(part)) return score > 0 && /brake/.test(text);
  return score > 0 || /catalog|part|search|vehicle|spare/i.test(text);
}

function exactTermsFor(params) {
  const position = cleanText(params.position).toLowerCase();
  const synonyms = [];
  if (/right|rh\b/.test(position)) synonyms.push("rh", "right");
  if (/left|lh\b/.test(position)) synonyms.push("lh", "left");
  if (/front|frt\b/.test(position)) synonyms.push("frt", "front");
  if (/rear|back\b/.test(position)) synonyms.push("rear", "back");
  return words([
    expandPart(params.part),
    params.position,
    params.detail,
    params.variant,
    synonyms.join(" ")
  ].filter(Boolean).join(" ")).filter((term) => term.length > 2);
}

function originScore(text, origin) {
  const wanted = cleanText(origin).toLowerCase();
  if (!wanted || wanted === "any") return 0;
  const haystack = cleanText(text).toLowerCase();
  if (wanted === "oem") return /mahindra|maruti|hyundai|tata|toyota|honda|ford|renault|skoda|vw|nissan|chevrolet/.test(haystack) ? 5 : -2;
  if (wanted === "aftermarket") return /mahindra|maruti|hyundai|tata|toyota|honda|ford|renault|skoda|vw|nissan|chevrolet/.test(haystack) ? -2 : 3;
  return 0;
}

function blockedCategoryPattern(params) {
  const part = cleanText(params.part).toLowerCase();
  if (/bumper/.test(part)) return /brake|clutch|filter|engine|shock|absorber|suspension|fuel|wiper|horn|bulb|headlight|tail|lamp/i;
  if (/lamp|light|fog|head|tail/.test(part)) return /brake|clutch|filter|engine|shock|absorber|suspension|fuel|wiper|bumper/i;
  if (/brake/.test(part)) return /bumper|lamp|light|filter|engine|wiper/i;
  return /$a/;
}

function findBestCategoryLink(data, params, currentUrl = "") {
  const partText = cleanText(expandPart(params.part));
  const terms = words(partText).filter((term) => term.length > 2);
  const exact = cleanText(params.part).toLowerCase();

  return data.anchors
    .filter((item) => /\/catalog\//i.test(item.href) && !/\/catalog\/part-/i.test(item.href) && item.href !== currentUrl)
    .map((item) => {
      const title = cleanText(item.text).toLowerCase();
      const href = item.href.toLowerCase();
      const blocked = blockedCategoryPattern(params);
      let score = scoreText(`${title} ${href}`, terms);
      if (blocked.test(`${title} ${href}`)) score -= 30;
      if (exact && title === exact) score += 12;
      if (exact && title.includes(exact)) score += 8;
      if (href.includes(slugText(exact))) score += 4;
      if (/bumper/.test(exact) && /bumper|body|exterior/.test(`${title} ${href}`)) score += 12;
      if (/fog/.test(exact) && /fog/.test(title)) score += 5;
      if (/head/.test(exact) && /head/.test(title)) score += 4;
      if (/tail|rear/.test(exact) && /tail|rear/.test(title)) score += 4;
      if (/bumper/.test(exact) && /bumper/.test(title)) score += 4;
      if (/mirror|orvm/.test(exact) && /mirror|orvm/.test(title)) score += 4;
      if (/shock|shocker/.test(exact) && /shock|absorber|strut/.test(title)) score += 4;
      if (/glass|window/.test(exact) && /glass|window|regulator/.test(title)) score += 4;
      return { ...item, score };
    })
    .filter((item) => item.score > 0)
    .sort((a, b) => b.score - a.score)[0] || null;
}

function extractProducts(text, images, params) {
  const partTerms = words(expandPart(params.part)).filter((term) => term.length > 2);
  const detailTerms = exactTermsFor(params);
  const lines = String(text || "").split(/\n+/).map(cleanText).filter(Boolean);
  const products = [];
  const brandish = /^[A-Z0-9&.\- ]{2,35}$/;

  for (let i = 0; i < lines.length; i += 1) {
    const title = lines[i];
    const price = extractPrice(lines[i + 1]) || extractPrice(`${lines[i]} ${lines[i + 1]} ${lines[i + 2]}`);
    if (!price || title.length < 4 || title.length > 90) continue;
    if (/^(choose|reset|filters|category|vehicle|price|origin|brand|useful links|download|fulfilled by)$/i.test(title)) continue;
    if (/^(?:₹|Rs\.?|INR|MRP:)/i.test(title)) continue;
    if (/^[A-Z0-9.\/\-_]{3,}$/i.test(title) && !/\s/.test(title)) continue;
    if (/^\d{2,}|\.\.\./.test(title) && !/[a-z]{4,}/i.test(title)) continue;

    const nearby = lines.slice(i, i + 8).join(" ");
    const brand = lines.slice(i + 1, i + 8).find((line) =>
      brandish.test(line)
      && !extractPrice(line)
      && !/MRP|Dispatch|Fulfilled|\.\.\./i.test(line)
      && !/^[A-Z0-9.\/\-_]{3,}$/i.test(line)
    ) || "";
    const fullText = `${title} ${nearby} ${brand}`;
    const score = scoreText(fullText, partTerms) + (scoreText(fullText, detailTerms) * 3) + originScore(fullText, params.origin);

    products.push({
      title,
      price,
      mrp: (nearby.match(/MRP:\s*(₹|Rs\.?|INR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i) || [])[2]
        ? `Rs. ${(nearby.match(/MRP:\s*(?:₹|Rs\.?|INR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i) || [])[1]}`
        : null,
      brand,
      partNumber: extractPartNumber(nearby),
      image: images.find((src) => !/logo|icon|app|brand/i.test(src)) || null,
      matchScore: score,
      score
    });
  }

  const seen = new Set();
  return products
    .filter((item) => {
      const key = `${item.title}-${item.price}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return item.score > 0 || products.length <= 12;
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, 10)
    .map(({ score, ...item }) => item);
}

async function clickFirstVisible(locator, timeout = 12000) {
  const count = await locator.count().catch(() => 0);
  for (let i = 0; i < count; i += 1) {
    const item = locator.nth(i);
    if (await item.isVisible().catch(() => false)) {
      await item.click({ timeout });
      return true;
    }
  }
  return false;
}

function variantHintForYear(year) {
  const number = Number(String(year || "").replace(/\D/g, ""));
  if (!number) return null;
  if (number >= 2020) return "BS6";
  if (number >= 2017) return "BS4";
  if (number >= 2010) return "BS3";
  return null;
}

function knownVehicleCatalogUrl(make, model) {
  const key = `${cleanText(make)} ${cleanText(model)}`.toLowerCase();
  if (key.includes("mahindra") && key.includes("bolero")) {
    return "https://boodmo.com/vehicles/mahindra-278/bolero-11275/";
  }
  return null;
}

async function trySearchInsideCatalog(page, params, steps) {
  const partText = cleanText([expandPart(params.part), params.position, params.detail].filter(Boolean).join(" "));
  if (!partText) return false;

  const candidates = [
    page.getByPlaceholder(/search/i).first(),
    page.locator("input[type='search']").first(),
    page.locator("input").filter({ hasText: /search/i }).first(),
    page.locator("input").first()
  ];

  for (const input of candidates) {
    if (await input.isVisible().catch(() => false)) {
      await input.fill(partText).catch(() => {});
      await input.press("Enter").catch(() => {});
      await page.waitForTimeout(2500);
      steps.push(`Catalog page ke search box me "${partText}" search kiya.`);
      return true;
    }
  }

  const clicked = await clickFirstVisible(page.getByText(new RegExp(partText.split(" ")[0], "i")), 5000).catch(() => false);
  if (clicked) {
    await page.waitForTimeout(2500);
    steps.push(`Catalog page par matching part text click kiya.`);
  }
  return clicked;
}

async function searchOemCatalog(page, query, params, steps) {
  const make = cleanText(params.make);
  const model = cleanText(params.model);
  const year = cleanText(params.year);
  const part = cleanText(params.part);
  const catalogUrl = "https://boodmo.com/vehicles/";
  const knownVehicleUrl = knownVehicleCatalogUrl(make, model);

  if (knownVehicleUrl) {
    await safeGoto(page, knownVehicleUrl, steps, `Known vehicle catalog "${make} ${model}"`, 18000);
    await page.waitForTimeout(1800);
    steps.push(`Known vehicle catalog "${make} ${model}" open hua.`);
  } else {
    await safeGoto(page, catalogUrl, steps, "Boodmo OEM vehicles catalog", 18000);
    await page.waitForTimeout(1800);
    steps.push("Boodmo OEM vehicles catalog open hua.");
  }

  if (make && !knownVehicleUrl) {
    const makerClicked = await clickHref(
      page,
      { exact: `/vehicles/${slugText(make).replace(/_/g, "-")}-`, terms: [make.toLowerCase()] },
      `Maker "${make}" select hua`,
      steps
    ) || await clickFirstVisible(page.getByText(new RegExp(`^${make}$`, "i")), 12000);
    if (makerClicked) {
      await page.waitForLoadState("domcontentloaded", { timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(2500);
      if (typeof makerClicked !== "boolean") steps.push(`Maker "${make}" select hua.`);
    } else {
      steps.push(`Maker "${make}" exact visible nahi mila, catalog page fallback diya.`);
    }
  }

  if (model && !knownVehicleUrl) {
    const modelTerms = words(model);
    const modelClicked = await clickHref(
      page,
      { exact: `/${slugText(model).replace(/_/g, "_")}-`, terms: modelTerms },
      `Model "${model}" select hua`,
      steps
    ).catch(() => false);
    if (modelClicked) {
      await page.waitForLoadState("domcontentloaded", { timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(2500);
    } else {
      steps.push(`Model "${model}" visible list me exact click nahi hua.`);
    }
  }

  const hint = variantHintForYear(year);
  if (hint) {
    const variantClicked = await clickFirstVisible(page.getByText(new RegExp(hint, "i")).first(), 12000).catch(() => false);
    if (variantClicked) {
      await page.waitForLoadState("domcontentloaded", { timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(2500);
      steps.push(`Year ${year} ke hisab se "${hint}" variant choose kiya.`);
    } else {
      steps.push(`Year ${year} ke liye "${hint}" variant visible nahi mila.`);
    }
  }

  let data = await collectPageData(page);
  let products = [];
  const visitedCategories = new Set();
  for (let depth = 0; depth < 3; depth += 1) {
    const category = findBestCategoryLink(data, params, data.url);
    if (!category || visitedCategories.has(category.href)) break;
    visitedCategories.add(category.href);
    await safeGoto(page, category.href, steps, `Part category "${category.text}"`, 18000);
    await page.waitForTimeout(1500);
    steps.push(`Part category "${category.text}" open hua.`);
    data = await collectPageData(page);
    products = extractProducts(data.bodyText, data.images, params);
    if (products.length > 0 || extractPrice(data.bodyText)) break;
  }

  if (visitedCategories.size === 0) {
    await trySearchInsideCatalog(page, params, steps).catch((error) => {
      steps.push(`Catalog search attempt fail hua: ${error.message}`);
    });
    data = await collectPageData(page);
    products = extractProducts(data.bodyText, data.images, params);
  }

  const links = bestLinksFrom(data, params);
  return {
    mode: "oem-catalog",
    query,
    catalogUrl,
    vehicleUrl: data.url,
    title: data.title,
    price: extractPrice(data.bodyText),
    partNumber: extractPartNumber(data.bodyText),
    image: data.images.find((src) => !/logo|icon|app|sprite/i.test(src)) || null,
    products,
    links
  };
}

async function searchDirect(page, query, params) {
  const searchUrl = `https://boodmo.com/search/${encodeURIComponent(query)}/`;
  const steps = [];
  await safeGoto(page, searchUrl, steps, "Direct Boodmo search", 18000);
  await page.waitForTimeout(1500);
  const data = await collectPageData(page);

  return {
    mode: "direct-search",
    searchUrl,
    title: data.title,
    price: extractPrice(data.bodyText),
    partNumber: extractPartNumber(data.bodyText),
    image: data.images.find((src) => !/logo|icon|app|sprite/i.test(src)) || null,
    products: extractProducts(data.bodyText, data.images, params),
    links: bestLinksFrom(data, params)
  };
}

async function searchBoodmo(query, rawParams) {
  const browser = await getBrowser();
  const page = await browser.newPage({
    userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120 Safari/537.36"
  });

  const params = {
    make: rawParams.get("make"),
    model: rawParams.get("model"),
    year: rawParams.get("year"),
    part: rawParams.get("part"),
    variant: rawParams.get("variant"),
    position: rawParams.get("position"),
    detail: rawParams.get("detail"),
    origin: rawParams.get("origin")
  };
  const steps = [];

  try {
    const oem = await searchOemCatalog(page, query, params, steps).catch((error) => {
      steps.push(`OEM catalog automation fail hua: ${error.message}`);
      return null;
    });
    const direct = await searchDirect(page, query, params).catch((error) => {
      steps.push(`Direct Boodmo search fail hua: ${error.message}`);
      return null;
    });

    const primary = oem || direct || {};
    const price = primary.price || (direct && direct.price) || null;
    const partNumber = primary.partNumber || (direct && direct.partNumber) || null;
    const image = primary.image || (direct && direct.image) || null;
    const directSearchUrl = `https://boodmo.com/search/${encodeURIComponent(query)}/`;
    const fallbackLinks = [
      { title: "Boodmo direct search", url: directSearchUrl, price: null },
      { title: "Boodmo OEM vehicle catalog", url: "https://boodmo.com/vehicles/", price: null },
      { title: "Google product image search", url: `https://www.google.com/search?tbm=isch&q=${encodeURIComponent(`${query} price India`)}`, price: null }
    ];
    const links = [...fallbackLinks, ...(primary.links || []), ...((direct && direct.links) || [])]
      .filter((item, index, list) => item.url && list.findIndex((other) => other.url === item.url) === index)
      .slice(0, 12);
    const products = [...(primary.products || []), ...((direct && direct.products) || [])]
      .filter((item, index, list) => list.findIndex((other) => other.title === item.title && other.price === item.price) === index)
      .slice(0, 10);

    return {
      ok: true,
      mode: primary.mode || "boodmo",
      query,
      searchUrl: (direct && direct.searchUrl) || directSearchUrl,
      catalogUrl: "https://boodmo.com/vehicles/",
      vehicleUrl: oem && oem.vehicleUrl,
      title: primary.title || (direct && direct.title) || "",
      price,
      partNumber,
      image,
      products,
      links,
      steps,
      note: products.length > 0 || price
        ? "Boodmo automation ne catalog/search page se part detail pakad li."
        : "Exact price/product Boodmo page se nahi mila. Galat item dikhane ke bajay exact source links diye gaye hain."
    };
  } finally {
    await page.close().catch(() => {});
  }
}

function send(res, status, data) {
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type"
  });
  res.end(JSON.stringify(data));
}

const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") return send(res, 200, {});

  const url = new URL(req.url, `http://localhost:${PORT}`);
  if (url.pathname === "/health") {
    const browser = await getBrowser().catch(() => null);
    return send(res, 200, { ok: Boolean(browser), browserConnected: Boolean(browser && browser.isConnected()) });
  }

  if (url.pathname === "/image-identify" && req.method === "POST") {
    try {
      const payload = await readJsonBody(req);
      const data = await identifyImagePart(payload);
      return send(res, data.ok ? 200 : 503, data);
    } catch (error) {
      return send(res, 500, { ok: false, message: error.message });
    }
  }

  if (url.pathname !== "/search") {
    return send(res, 404, { ok: false, message: "Not found" });
  }

  try {
    const query = makeQuery(url.searchParams);
    if (!query) return send(res, 400, { ok: false, message: "Query missing" });
    const data = await searchBoodmo(query, url.searchParams);
    send(res, 200, data);
  } catch (error) {
    send(res, 500, { ok: false, message: error.message });
  }
});

server.listen(PORT, () => {
  console.log(`Boodmo automation service running on http://localhost:${PORT}`);
});

process.on("SIGINT", async () => {
  if (browserPromise) {
    const browser = await browserPromise.catch(() => null);
    if (browser) await browser.close().catch(() => {});
  }
  process.exit(0);
});
