package com.example.mealjournal;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.widget.Toast;
import android.net.Uri;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final int CAMERA_PERMISSION_REQUEST = 741;
    private boolean scanAfterPermission = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
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
        webView.addJavascriptInterface(new AndroidBridge(), "MealJournalAndroid");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class AndroidBridge {
        @JavascriptInterface public void openBarcodeScanner() {
            runOnUiThread(() -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                scanAfterPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                return;
            }
            launchScanner();
        });
        }

        @JavascriptInterface public void lookupBarcode(String barcode) {
            executor.execute(() -> {
                HttpURLConnection connection = null;
                String response = "{\"status\":0}";
                try {
                    URL url = new URL("https://world.openfoodfacts.org/api/v2/product/" + Uri.encode(barcode) + ".json");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("User-Agent", "MealJournalAndroid/1.2 (personal food journal)");
                    InputStream stream = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                    reader.close();
                    response = body.toString();
                } catch (Exception ignored) {
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final String safe = JSONObject.quote(response);
                runOnUiThread(() -> webView.evaluateJavascript("window.onProductLookup && window.onProductLookup(" + safe + ")", null));
            });
        }

        @JavascriptInterface public void searchFoods(String query, String apiKey) {
            executor.execute(() -> {
                org.json.JSONArray results = new org.json.JSONArray();
                try {
                    String offUrl = "https://world.openfoodfacts.org/cgi/search.pl?search_terms=" + Uri.encode(query) + "&search_simple=1&action=process&json=1&page_size=15";
                    JSONObject off = new JSONObject(httpGet(offUrl));
                    org.json.JSONArray products = off.optJSONArray("products");
                    if (products != null) for (int i=0; i<products.length(); i++) {
                        JSONObject product = products.optJSONObject(i); if (product == null) continue;
                        JSONObject n = product.optJSONObject("nutriments"); if (n == null) n = new JSONObject();
                        JSONObject item = new JSONObject(); item.put("source", "Open Food Facts");
                        item.put("name", product.optString("product_name", "Food product"));
                        item.put("brand", product.optString("brands", ""));
                        item.put("serving", product.optString("serving_size", "per 100 g"));
                        item.put("basis", "per 100 g");
                        item.put("calories", n.optDouble("energy-kcal_100g", n.optDouble("energy-kcal", 0)));
                        item.put("protein", n.optDouble("proteins_100g", 0)); item.put("carbs", n.optDouble("carbohydrates_100g", 0)); item.put("fat", n.optDouble("fat_100g", 0));
                        results.put(item);
                    }
                } catch (Exception ignored) {}
                if (apiKey != null && !apiKey.trim().isEmpty()) try {
                    String usdaUrl = "https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + Uri.encode(apiKey.trim()) + "&query=" + Uri.encode(query) + "&pageSize=15";
                    JSONObject usda = new JSONObject(httpGet(usdaUrl)); org.json.JSONArray foods = usda.optJSONArray("foods");
                    if (foods != null) for (int i=0; i<foods.length(); i++) {
                        JSONObject food=foods.optJSONObject(i); if(food==null) continue; org.json.JSONArray nutrients=food.optJSONArray("foodNutrients");
                        double kcal=0, protein=0, carbs=0, fat=0;
                        if(nutrients!=null) for(int j=0;j<nutrients.length();j++){JSONObject nu=nutrients.optJSONObject(j); if(nu==null)continue; String name=nu.optString("nutrientName","").toLowerCase(); String unit=nu.optString("unitName","").toLowerCase(); double v=nu.optDouble("value",0); if(name.contains("energy") && (unit.equals("kcal") || unit.equals("calories")))kcal=v; else if(name.equals("protein"))protein=v; else if(name.contains("carbohydrate"))carbs=v; else if(name.equals("total lipid (fat)"))fat=v;}
                        JSONObject item=new JSONObject(); item.put("source","USDA FoodData Central"); item.put("name",food.optString("description","Food")); item.put("brand",food.optString("brandOwner","")); item.put("serving","per 100 g"); item.put("basis","per 100 g"); item.put("calories",kcal); item.put("protein",protein); item.put("carbs",carbs); item.put("fat",fat); results.put(item);
                    }
                } catch (Exception ignored) {}
                final String payload=results.toString(); runOnUiThread(() -> webView.evaluateJavascript("window.onFoodSearch && window.onFoodSearch(" + JSONObject.quote(payload) + ")", null));
            });
        }
    }

    private String httpGet(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection(); c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setRequestProperty("User-Agent","MealJournalAndroid/1.2 (personal food journal)");
        try(InputStream in=c.getInputStream(); BufferedReader r=new BufferedReader(new InputStreamReader(in,"UTF-8"))){StringBuilder b=new StringBuilder(); String line; while((line=r.readLine())!=null)b.append(line); return b.toString();} finally {c.disconnect();}
    }

    private void launchScanner() {
        try { new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.PRODUCT_CODE_TYPES).setPrompt("Place the product barcode inside the frame").setBeepEnabled(false).setOrientationLocked(false).setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity.class).initiateScan(); }
        catch (Exception e) { Toast.makeText(this, "Could not start barcode scanner: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) { boolean granted=grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED; scanAfterPermission=false; if(granted) launchScanner(); else Toast.makeText(this,"Camera permission is needed to scan barcodes. You can enable it in Android Settings.",Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                String code = JSONObject.quote(result.getContents());
                webView.evaluateJavascript("window.onBarcodeScanned && window.onBarcodeScanned(" + code + ")", null);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
