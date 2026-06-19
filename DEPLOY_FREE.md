# Vaishnav Inventory Free Cloud Deploy

Laptop off hone ke baad app tabhi chalegi jab frontend, backend, aur database cloud par hon.

## Recommended Free Setup

1. Database: Neon Free Postgres
   - Create project and copy JDBC URL.

2. Backend: Render Web Service
   - Root directory: `Inventory`
   - Environment: Docker
   - Set env vars:
     - `SPRING_DATASOURCE_URL`
     - `SPRING_DATASOURCE_USERNAME`
     - `SPRING_DATASOURCE_PASSWORD`
     - `CRYPTO_VAULT_MASTER_KEY`

### Crypto decision engine keys

Add keys only in Render Environment (cloud) or `Inventory/.env` (local). Never put secret values in Git or frontend variables.

Required for the complete Aegis readiness gate:

- `GEMINI_API_KEY` — Google AI Studio; model defaults to `gemini-3.5-flash`
- `OPENAI_API_KEY` — OpenAI; model defaults to `gpt-5.5`
- `ANTHROPIC_API_KEY` — Claude
- `DEEPSEEK_API_KEY` — DeepSeek
- `WHALE_ALERT_API_KEY` — attributed real-time whale transfers
- `CRYPTOQUANT_API_KEY` and/or `GLASSNODE_API_KEY` — optional licensed enrichment for exchange flow and miner data
- `CRYPTO_AUTONOMOUS_PAPER_ENABLED=true` — enables the 5-minute paper scan and 30-second risk monitor

Optional extra AI votes: `GROQ_API_KEY`, `CEREBRAS_API_KEY`, `MISTRAL_API_KEY`.

No key is required for Binance public market data, Yahoo macro data, CoinDesk/Cointelegraph/Decrypt RSS. The Block and Arkham require separate commercial/API access and are reported as unavailable until licensed.

No Render variables are required for Coin Metrics Community, DefiLlama, or Mempool.space. They are connected directly as rate-limited keyless on-chain sources.

After changing any key, restart the backend or redeploy Render. The AI status endpoint is `/crypto/ai/status`; it exposes readiness only, never secret values.

3. Frontend: Render Static Site or Vercel
   - Root directory: `inventory-frontend`
   - Build command: `npm run build`
   - Publish directory: `build`
   - Env vars:
     - `REACT_APP_API_BASE=https://your-backend-url`
     - `REACT_APP_APP_PASSWORD=your-private-app-password`

## Important

- `192.168.x.x` links only work on same Wi-Fi.
- Cloudflare/localtunnel links stop when laptop is off.
- For laptop-off access, cloud hosting is required.
- Free services can sleep or be slower.
- Frontend password lock is light protection. For stronger public use, add backend user login before sharing widely.
