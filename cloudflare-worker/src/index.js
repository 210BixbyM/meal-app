let fatSecretToken = null;
let fatSecretExpiry = 0;

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": "no-store"
    }
  });

async function getFatSecretToken(env) {
  if (fatSecretToken && Date.now() < fatSecretExpiry) {
    return fatSecretToken;
  }

  if (!env.FATSECRET_CLIENT_ID || !env.FATSECRET_CLIENT_SECRET) {
    throw new Error("FatSecret credentials are not configured.");
  }

  const credentials = btoa(
    `${env.FATSECRET_CLIENT_ID}:${env.FATSECRET_CLIENT_SECRET}`
  );

  const response = await fetch("https://oauth.fatsecret.com/connect/token", {
    method: "POST",
    headers: {
      "Authorization": `Basic ${credentials}`,
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: "grant_type=client_credentials&scope=basic"
  });

  if (!response.ok) throw new Error("FatSecret authentication failed.");

  const data = await response.json();
  fatSecretToken = data.access_token;
  fatSecretExpiry = Date.now() + (data.expires_in - 120) * 1000;

  return fatSecretToken;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type"
        }
      });
    }

    if (url.pathname === "/health") {
      return json({ status: "ok", service: "crumb-api" });
    }

    if (request.method !== "GET") {
      return json({ error: "Method not allowed." }, 405);
    }

    try {
      if (url.pathname === "/api/fatsecret/search") {
        const query = url.searchParams.get("q")?.trim();

        if (!query || query.length > 100) {
          return json({ error: "A search query is required (max 100 characters)." }, 400);
        }

        const token = await getFatSecretToken(env);
        const params = new URLSearchParams({
          method: "foods.search",
          search_expression: query,
          format: "json"
        });

        const response = await fetch(
          "https://platform.fatsecret.com/rest/server.api",
          {
            method: "POST",
            headers: {
              "Authorization": `Bearer ${token}`,
              "Content-Type": "application/x-www-form-urlencoded"
            },
            body: params
          }
        );

        return json(await response.json(), response.status);
      }

      if (url.pathname === "/api/ninjas/nutrition") {
        const query = url.searchParams.get("q")?.trim();

        if (!query || query.length > 300) {
          return json({ error: "A nutrition query is required (max 300 characters)." }, 400);
        }

        if (!env.API_NINJAS_KEY) {
          return json({ error: "API Ninjas credentials are not configured." }, 503);
        }

        const endpoint = new URL("https://api.api-ninjas.com/v1/nutrition");
        endpoint.searchParams.set("query", query);

        const response = await fetch(endpoint, {
          headers: { "X-Api-Key": env.API_NINJAS_KEY }
        });

        return json(await response.json(), response.status);
      }

      if (url.pathname === "/api/off/product") {
        const barcode = url.searchParams.get("barcode")?.trim();

        if (!barcode || !/^\d{8,14}$/.test(barcode)) {
          return json({ error: "A valid 8–14 digit barcode is required." }, 400);
        }

        const response = await fetch(
          `https://world.openfoodfacts.org/api/v2/product/${barcode}.json`,
          {
            headers: {
              "User-Agent": "CrumbMealJournal/1.0 (food lookup)"
            }
          }
        );

        return json(await response.json(), response.status);
      }

      return json({ error: "Endpoint not found." }, 404);
    } catch (error) {
      return json({ error: error.message || "Internal server error." }, 500);
    }
  }
};
