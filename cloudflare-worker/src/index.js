const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": "no-store"
    }
  });

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
