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
