package com.example.mealjournal;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;
import org.json.JSONArray;

public class MainActivity extends Activity {

    private WebView webView;
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(
            new AndroidBridge(), "MealJournalAndroid"
        );

        webView.loadUrl("file:///android_asset/index.html");
    }

    private class AndroidBridge {

        @JavascriptInterface
        public void searchFoods(String query, String apiKey) {

            executor.execute(() -> {

                JSONArray results = new JSONArray();
                StringBuilder errors = new StringBuilder();

                // Open Food Facts
                try {
                    String offUrl =
                        "https://world.openfoodfacts.org/cgi/search.pl"
                        + "?search_terms=" + Uri.encode(query)
                        + "&search_simple=1&action=process&json=1&page_size=15";

                    JSONObject off = new JSONObject(httpGet(offUrl));
                    JSONArray products = off.optJSONArray("products");

                    if (products != null) {
                        for (int i = 0; i < products.length(); i++) {
                            JSONObject product = products.optJSONObject(i);
                            if (product == null) continue;

                            JSONObject n = product.optJSONObject("nutriments");
                            if (n == null) n = new JSONObject();

                            JSONObject item = new JSONObject();
                            item.put("source", "Open Food Facts");
                            item.put("name", product.optString("product_name", "Food product"));
                            item.put("brand", product.optString("brands", ""));
                            item.put("serving", product.optString("serving_size", "per 100 g"));
                            item.put("basis", "per 100 g");

                            item.put("calories",
                                n.optDouble("energy-kcal_100g", 0));
                            item.put("protein",
                                n.optDouble("proteins_100g", 0));
                            item.put("carbs",
                                n.optDouble("carbohydrates_100g", 0));
                            item.put("fat",
                                n.optDouble("fat_100g", 0));

                            if (!matchesFoodQuery(query, item.optString("name", ""))) continue;

                            results.put(item);
                        }
                    }

                } catch (Exception e) {
                    errors.append("Open Food Facts: ")
                          .append(e.getMessage())
                          .append(". ");
                }

                // USDA FoodData Central
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    try {
                        String usdaUrl =
                            "https://api.nal.usda.gov/fdc/v1/foods/search"
                            + "?api_key=" + Uri.encode(apiKey.trim())
                            + "&query=" + Uri.encode(query)
                            + "&pageSize=15";

                        JSONObject usda = new JSONObject(httpGet(usdaUrl));
                        JSONArray foods = usda.optJSONArray("foods");

                        if (foods != null) {
                            for (int i = 0; i < foods.length(); i++) {
                                JSONObject food = foods.optJSONObject(i);
                                if (food == null) continue;

                                JSONArray nutrients =
                                    food.optJSONArray("foodNutrients");

                                double kcal = 0;
                                double protein = 0;
                                double carbs = 0;
                                double fat = 0;

                                if (nutrients != null) {
                                    for (int j = 0; j < nutrients.length(); j++) {
                                        JSONObject nu = nutrients.optJSONObject(j);
                                        if (nu == null) continue;

                                        String name = nu.optString(
                                            "nutrientName", ""
                                        ).toLowerCase();

                                        String unit = nu.optString(
                                            "unitName", ""
                                        ).toLowerCase();

                                        double v = nu.optDouble("value", 0);

                                        if (name.contains("energy")
                                            && (unit.equals("kcal")
                                            || unit.equals("calories"))) {
                                            kcal = v;
                                        } else if (name.equals("protein")) {
                                            protein = v;
                                        } else if (name.contains("carbohydrate")) {
                                            carbs = v;
                                        } else if (name.equals("total lipid (fat)")) {
                                            fat = v;
                                        }
                                    }
                                }

                                JSONObject item = new JSONObject();
                                item.put("source", "USDA FoodData Central");
                                item.put("name", food.optString("description", "Food"));
                                item.put("brand", food.optString("brandOwner", ""));
                                item.put("serving", "per 100 g");
                                item.put("basis", "per 100 g");
                                item.put("calories", kcal);
                                item.put("protein", protein);
                                item.put("carbs", carbs);
                                item.put("fat", fat);

                                if (!matchesFoodQuery(query, item.optString("name", ""))) continue;

                                results.put(item);
                            }
                        }

                    } catch (Exception e) {
                        errors.append("USDA FoodData Central: ")
                              .append(e.getMessage())
                              .append(". ");
                    }
                }

                final String payload = results.toString();
                final String errorMessage = errors.toString();

                runOnUiThread(() -> {
                    if (!errorMessage.isEmpty()) {
                        Toast.makeText(
                            MainActivity.this,
                            "Food search warning: " + errorMessage,
                            Toast.LENGTH_LONG
                        ).show();
                    }

                    webView.evaluateJavascript(
                        "window.onFoodSearch && window.onFoodSearch("
                        + JSONObject.quote(payload) + ")",
                        null
                    );
                });
            });
        }
    }


    private boolean matchesFoodQuery(String query, String name) {
        String[] terms = query.toLowerCase()
            .replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");

        String[] words = name.toLowerCase()
            .replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");

        for (String term : terms) {
            if (term.isEmpty()) continue;
            if (term.length() > 3 && term.endsWith("s"))
                term = term.substring(0, term.length() - 1);

            boolean found = false;
            for (String word : words) {
                if (word.length() > 3 && word.endsWith("s"))
                    word = word.substring(0, word.length() - 1);
                if (word.equals(term)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private String httpGet(String address) throws Exception {

        HttpURLConnection connection =
            (HttpURLConnection) new URL(address).openConnection();

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);

        connection.setRequestProperty(
            "User-Agent",
            "MealJournalAndroid/1.2 (personal food journal)"
        );

        try {
            int status = connection.getResponseCode();

            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

            StringBuilder body = new StringBuilder();

            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, "UTF-8"))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
            }

            if (status < 200 || status >= 300) {
                throw new Exception(
                    "HTTP " + status + ": " + body.toString()
                );
            }

            return body.toString();

        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }
}
