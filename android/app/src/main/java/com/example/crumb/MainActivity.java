package com.example.crumb;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import java.util.Calendar;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;
import org.json.JSONArray;

public class MainActivity extends Activity {

    private static final int GROCERY_SCAN_REQUEST = 7301;
    private static final int BACKUP_OPEN_REQUEST = 7302;
    private static final int BACKUP_SAVE_REQUEST = 7303;

    private ValueCallback<Uri[]> fileChooserCallback;
    private String pendingBackupJson;

    private WebView webView;
    private Interpreter foodInterpreter;
    private String[] foodLabels;
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
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");

                try {
                    startActivityForResult(intent, BACKUP_OPEN_REQUEST);
                    return true;
                } catch (Exception ex) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this,
                        "Unable to open file picker", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(
            new AndroidBridge(), "MealJournalAndroid"
        );

        webView.loadUrl("file:///android_asset/index.html");

        initializeNotifications();
    }

    private class AndroidBridge {


        @JavascriptInterface
        public void searchFoods(String query, String foodBaseKey,
                                String usdaApiKey) {
            executor.execute(() -> {
                JSONArray results = new JSONArray();
                String foodBaseError = "";

                if (foodBaseKey != null && !foodBaseKey.trim().isEmpty()) {
                    try {
                        String url = "https://foodbase.dev/v1/foods/search?q="
                            + Uri.encode(query) + "&limit=10&lang=en";

                        JSONObject response = foodBaseGet(url, foodBaseKey);
                        JSONArray foods = response.optJSONArray("data");

                        if (foods != null) {
                            for (int i = 0; i < Math.min(foods.length(), 3); i++) {
                                JSONObject food = foods.optJSONObject(i);
                                if (food == null) continue;

                                String id = food.optString("id", "");
                                if (id.isEmpty()) continue;

                                JSONObject detail = foodBaseGet(
                                    "https://foodbase.dev/v1/foods/"
                                    + Uri.encode(id) + "?lang=en", foodBaseKey);

                                JSONObject product = detail.optJSONObject("data");
                                if (product == null) product = detail;

                                JSONObject macros = product.optJSONObject("nutrition_summary");
                                if (macros == null) macros = product.optJSONObject("macros");
                                if (macros == null) macros = new JSONObject();

                                JSONObject item = new JSONObject();
                                item.put("source", "FoodBase");
                                item.put("name", product.optString("name_default",
                                    food.optString("name_default", "Food product")));
                                item.put("brand", product.optString("brand",
                                    food.optString("brand", "")));
                                item.put("servingLabel", product.optString("serving_size_raw", ""));
                                item.put("serving", "per 100 g");
                                item.put("basis", "per 100 g");
                                item.put("calories", macros.has("energy_kcal") && !macros.isNull("energy_kcal") ? macros.get("energy_kcal") : JSONObject.NULL);
                                item.put("protein", macros.has("proteins_g") && !macros.isNull("proteins_g") ? macros.get("proteins_g") : JSONObject.NULL);
                                item.put("carbs", macros.has("carbs_g") && !macros.isNull("carbs_g") ? macros.get("carbs_g") : JSONObject.NULL);
                                item.put("fat", macros.has("fat_g") && !macros.isNull("fat_g") ? macros.get("fat_g") : JSONObject.NULL);
                                item.put("fiber", macros.has("fiber_g") && !macros.isNull("fiber_g") ? macros.get("fiber_g") : JSONObject.NULL);
                                results.put(item);
                            }
                        }
                    } catch (Exception e) {
                        foodBaseError = e.getMessage() == null
                            ? "FoodBase search failed." : e.getMessage();
                    }
                } else {
                    foodBaseError = "FoodBase key not configured.";
                }

                String error = "";
                if (results.length() == 0) {
                    try {
                        String key = usdaApiKey == null || usdaApiKey.trim().isEmpty()
                            ? "DEMO_KEY" : usdaApiKey.trim();

                        JSONObject response = usdaSearch(query, key);
                        JSONArray foods = response.optJSONArray("foods");

                        if (foods != null) {
                            for (int i = 0; i < Math.min(foods.length(), 10); i++) {
                                JSONObject food = foods.optJSONObject(i);
                                if (food == null) continue;

                                JSONObject item = new JSONObject();
                                item.put("source", "USDA FoodData Central");
                                item.put("name", food.optString("description", "Food product"));
                                item.put("brand", food.optString("brandOwner", ""));
                                item.put("fdcId", food.optString("fdcId", ""));
                                item.put("serving", "per 100 g");
                                item.put("basis", "per 100 g");
                                item.put("calories", usdaNutrient(food, 1008));
                                item.put("protein", usdaNutrient(food, 1003));
                                item.put("carbs", usdaNutrient(food, 1005));
                                item.put("fat", usdaNutrient(food, 1004));
                                item.put("fiber", usdaNutrient(food, 1079));
                                item.put("sugar", JSONObject.NULL);
                                item.put("satFat", JSONObject.NULL);
                                item.put("sodium", JSONObject.NULL);
                                results.put(item);
                            }
                        }
                    } catch (Exception e) {
                        String usdaError = e.getMessage() == null
                            ? "USDA search failed." : e.getMessage();
                        error = foodBaseError.isEmpty()
                            ? "USDA: " + usdaError
                            : "FoodBase: " + foodBaseError + " USDA: " + usdaError;
                    }
                }

                final String payload = results.toString();
                final String message = error;

                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFoodSearch && window.onFoodSearch("
                    + JSONObject.quote(payload) + ","
                    + JSONObject.quote(message) + ")", null));
            });
        }

        @JavascriptInterface
        public void getFoodPortions(String fdcId, String apiKey) {
            executor.execute(() -> {
                JSONArray portions = new JSONArray();
                String error = "";
                try {
                    if (fdcId == null || !fdcId.matches("\\d+"))
                        throw new Exception("Invalid USDA food ID.");

                    String key = apiKey == null || apiKey.trim().isEmpty()
                        ? "DEMO_KEY" : apiKey.trim();
                    String address = "https://api.nal.usda.gov/fdc/v1/food/"
                        + Uri.encode(fdcId) + "?api_key=" + Uri.encode(key);
                    JSONObject food = new JSONObject(httpGet(address));
                    JSONArray source = food.optJSONArray("foodPortions");

                    if (source != null) {
                        for (int i = 0; i < source.length(); i++) {
                            JSONObject portion = source.optJSONObject(i);
                            if (portion == null) continue;
                            double grams = portion.optDouble("gramWeight", 0);
                            if (!Double.isFinite(grams) || grams <= 0) continue;

                            String description = portion.optString("portionDescription", "").trim();
                            String modifier = portion.optString("modifier", "").trim();
                            JSONObject measure = portion.optJSONObject("measureUnit");
                            String unit = measure == null ? "" : measure.optString("name", "").trim();
                            double amount = portion.optDouble("amount", 0);

                            String label = description;
                            if (label.isEmpty()) {
                                label = (amount > 0 ? amount + " " : "")
                                    + (modifier.isEmpty() ? "" : modifier + " ")
                                    + unit;
                            }
                            if (label.isEmpty()) label = "Serving";
                            JSONObject item = new JSONObject();
                            item.put("label", label);
                            item.put("grams", grams);
                            portions.put(item);
                        }
                    }

                    double servingSize = food.optDouble("servingSize", 0);
                    String servingUnit = food.optString("servingSizeUnit", "");
                    if (servingSize > 0 && "g".equalsIgnoreCase(servingUnit)) {
                        JSONObject item = new JSONObject();
                        item.put("label", "1 package serving");
                        item.put("grams", servingSize);
                        portions.put(item);
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null
                        ? "Could not load serving sizes." : e.getMessage();
                }

                final String payload = portions.toString();
                final String message = error;
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFoodPortions && window.onFoodPortions("
                    + JSONObject.quote(fdcId) + ","
                    + JSONObject.quote(payload) + ","
                    + JSONObject.quote(message) + ")", null));
            });
        }

        private JSONObject usdaSearch(String query, String apiKey)
                throws Exception {
            String address = "https://api.nal.usda.gov/fdc/v1/foods/search?api_key="
                + Uri.encode(apiKey) + "&query=" + Uri.encode(query)
                + "&pageSize=10";

            HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (input != null) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1)
                    output.write(buffer, 0, count);
                input.close();
            }
            connection.disconnect();

            if (status < 200 || status >= 300)
                throw new Exception("USDA error " + status
                    + (status == 403 ? ": check API key or request limit." : ""));

            return new JSONObject(output.toString("UTF-8"));
        }

        private Object usdaNutrient(JSONObject food, int nutrientId) {
            JSONArray nutrients = food.optJSONArray("foodNutrients");
            if (nutrients == null) return JSONObject.NULL;

            for (int i = 0; i < nutrients.length(); i++) {
                JSONObject nutrient = nutrients.optJSONObject(i);
                if (nutrient != null
                        && nutrient.optInt("nutrientId", -1) == nutrientId
                        && nutrient.has("value")
                        && !nutrient.isNull("value")) {
                    return nutrient.opt("value");
                }
            }
            return JSONObject.NULL;
        }

        private JSONObject foodBaseGet(String address, String apiKey)
                throws Exception {
            HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("X-API-Key", apiKey.trim());
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (input != null) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1)
                    output.write(buffer, 0, count);
                input.close();
            }
            connection.disconnect();

            if (status < 200 || status >= 300)
                throw new Exception("FoodBase error " + status
                    + (status == 401 ? ": check your API key."
                    : status == 429 ? ": daily request limit reached." : ""));

            return new JSONObject(output.toString("UTF-8"));
        }

        @JavascriptInterface
        public void importRecipeUrl(String address) {
            MainActivity.this.importRecipeUrl(address);
        }
        @JavascriptInterface
        public void updateCrumbWidget(String planText, String groceryText) {
            getSharedPreferences("crumb_widget", MODE_PRIVATE).edit()
                .putString("plan", planText == null ? "" : planText)
                .putString("groceries", groceryText == null ? "" : groceryText)
                .apply();

            android.appwidget.AppWidgetManager manager =
                android.appwidget.AppWidgetManager.getInstance(MainActivity.this);
            android.content.ComponentName provider =
                new android.content.ComponentName(MainActivity.this,
                    CrumbWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(provider);
            android.content.Intent update =
                new android.content.Intent(MainActivity.this, CrumbWidgetProvider.class);
            update.setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            update.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            sendBroadcast(update);
        }

        @JavascriptInterface
        public void saveBackup(String json) {
            runOnUiThread(() -> {
                pendingBackupJson = json;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "meal-journal-backup.json");
                startActivityForResult(intent, BACKUP_SAVE_REQUEST);
            });
        }

        @JavascriptInterface
        public void scanGrocery(String apiKey) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this,
                    GroceryScannerActivity.class);
                intent.putExtra("foodBaseApiKey", apiKey == null ? "" : apiKey);
                startActivityForResult(intent, GROCERY_SCAN_REQUEST);
            });
        }

        @JavascriptInterface
        public void recognizeFood(String dataUrl) {
            MainActivity.this.recognizeFood(dataUrl);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == BACKUP_OPEN_REQUEST) {
            if (fileChooserCallback != null) {
                Uri[] results = resultCode == RESULT_OK && data != null
                    && data.getData() != null
                    ? new Uri[]{data.getData()} : null;
                fileChooserCallback.onReceiveValue(results);
                fileChooserCallback = null;
            }
            return;
        }

        if (requestCode == BACKUP_SAVE_REQUEST) {
            if (resultCode == RESULT_OK && data != null
                    && data.getData() != null && pendingBackupJson != null) {
                try (OutputStream out =
                        getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new Exception("Could not open file");
                    out.write(pendingBackupJson.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                    Toast.makeText(this, "Backup exported successfully",
                        Toast.LENGTH_LONG).show();
                } catch (Exception ex) {
                    Toast.makeText(this, "Backup export failed: " + ex.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            }
            pendingBackupJson = null;
            return;
        }

        if (requestCode == GROCERY_SCAN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                String result = data.getStringExtra("groceryResult");
                if (result != null && webView != null) {
                    webView.evaluateJavascript(
                        "window.onGroceryScanResult && window.onGroceryScanResult("
                        + JSONObject.quote(result) + ")",
                        null
                    );
                }
            } else if (webView != null) {
                webView.evaluateJavascript(
                    "window.onGroceryScanCancelled && window.onGroceryScanCancelled()",
                    null
                );
            }
        }
    }

    private void recognizeFood(String dataUrl) {
        executor.execute(() -> {
            String response;
            Bitmap original = null;
            Bitmap resized = null;

            try {
                if (foodInterpreter == null) loadFoodModel();

                int comma = dataUrl.indexOf(',');
                if (comma < 0) throw new Exception("Invalid image data");

                byte[] bytes = Base64.decode(
                    dataUrl.substring(comma + 1), Base64.DEFAULT
                );
                original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (original == null) throw new Exception("Could not read photo");

                Tensor inputTensor = foodInterpreter.getInputTensor(0);
                int[] shape = inputTensor.shape();

                if (shape.length != 4 || shape[0] != 1 || shape[3] != 3
                    || inputTensor.dataType() != DataType.FLOAT32) {
                    throw new Exception("Unsupported model input format");
                }

                int height = shape[1];
                int width = shape[2];
                resized = Bitmap.createScaledBitmap(original, width, height, true);

                int[] pixels = new int[width * height];
                resized.getPixels(pixels, 0, width, 0, 0, width, height);

                ByteBuffer input = ByteBuffer.allocateDirect(inputTensor.numBytes())
                    .order(ByteOrder.nativeOrder());

                for (int pixel : pixels) {
                    input.putFloat(((pixel >> 16) & 255) / 255.0f);
                    input.putFloat(((pixel >> 8) & 255) / 255.0f);
                    input.putFloat((pixel & 255) / 255.0f);
                }
                input.rewind();

                Tensor outputTensor = foodInterpreter.getOutputTensor(0);
                int[] outputShape = outputTensor.shape();

                if (outputShape.length != 2 || outputShape[0] != 1
                    || outputTensor.dataType() != DataType.FLOAT32) {
                    throw new Exception("Unsupported model output format");
                }

                int count = outputShape[1];
                float[][] scores = new float[1][count];
                foodInterpreter.run(input, scores);

                Integer[] indices = new Integer[Math.max(0, count - 1)];
                for (int i = 1; i < count; i++) indices[i - 1] = i;

                Arrays.sort(indices, (a, b) ->
                    Float.compare(scores[0][b], scores[0][a])
                );

                JSONArray matches = new JSONArray();
                for (int i = 0; i < Math.min(5, indices.length); i++) {
                    int index = indices[i];
                    if (index >= foodLabels.length || foodLabels[index] == null) continue;

                    JSONObject item = new JSONObject();
                    item.put("name", foodLabels[index]);
                    item.put("score", scores[0][index]);
                    matches.put(item);
                }

                JSONObject result = new JSONObject();
                result.put("results", matches);
                response = result.toString();

            } catch (Exception e) {
                JSONObject error = new JSONObject();
                try {
                    error.put("error", e.getMessage() == null
                        ? "Recognition failed" : e.getMessage());
                } catch (Exception ignored) {}
                response = error.toString();
            } finally {
                if (resized != null && resized != original) resized.recycle();
                if (original != null) original.recycle();
            }

            final String result = response;
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(
                        "window.onFoodRecognition && window.onFoodRecognition("
                        + JSONObject.quote(result) + ")",
                        null
                    );
                }
            });
        });
    }

    private void loadFoodModel() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (InputStream stream = getAssets().open("food_classifier.tflite")) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                bytes.write(buffer, 0, length);
            }
        }

        ByteBuffer model = ByteBuffer.allocateDirect(bytes.size())
            .order(ByteOrder.nativeOrder());
        model.put(bytes.toByteArray());
        model.rewind();

        foodInterpreter = new Interpreter(model);

        int count = foodInterpreter.getOutputTensor(0).shape()[1];
        foodLabels = new String[count];

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(
                getAssets().open("aiy_food_V1_labelmap.csv"), "UTF-8"
            )
        )) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 2);
                if (parts.length != 2) continue;

                try {
                    int index = Integer.parseInt(parts[0].trim());
                    if (index >= 0 && index < count) {
                        foodLabels[index] = parts[1].trim();
                    }
                } catch (NumberFormatException ignored) {}
            }
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

    private void importRecipeUrl(String address) {
        executor.execute(() -> {
            String result = null;
            String error = null;

            try {
                JSONObject recipe = fetchRecipeFromPage(address);
                result = recipe.toString();
            } catch (Exception ex) {
                error = ex.getMessage() == null
                    ? "Could not import this recipe." : ex.getMessage();
            }

            final String payload = result;
            final String failure = error;

            runOnUiThread(() -> {
                if (webView == null) return;

                String callback = "window.onRecipeImport && window.onRecipeImport("
                    + (payload == null ? "null" : JSONObject.quote(payload))
                    + ","
                    + (failure == null ? "null" : JSONObject.quote(failure))
                    + ")";
                webView.evaluateJavascript(callback, null);
            });
        });
    }

    private JSONObject fetchRecipeFromPage(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || url.getUserInfo() != null
                || url.getHost() == null
                || url.getHost().isEmpty()) {
            throw new Exception("Please enter a valid HTTPS recipe URL.");
        }

        InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
        for (InetAddress ip : addresses) {
            if (ip.isAnyLocalAddress() || ip.isLoopbackAddress()
                    || ip.isLinkLocalAddress() || ip.isSiteLocalAddress()
                    || ip.isMulticastAddress()) {
                throw new Exception("That webpage address is not allowed.");
            }
        }

        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty(
            "User-Agent", "Mozilla/5.0 (compatible; CrumbRecipeImporter/1.0)"
        );
        connection.setRequestProperty("Accept", "text/html");

        String page;
        try {
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400)
                throw new Exception("This webpage redirects. Try its final HTTPS URL.");
            if (status < 200 || status >= 300)
                throw new Exception("The webpage returned HTTP " + status + ".");

            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 2 * 1024 * 1024)
                    throw new Exception("The webpage is too large to import.");
                output.write(buffer, 0, count);
            }
            input.close();
            page = output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }

        Pattern scriptPattern = Pattern.compile(
            "(?is)<script\\\\b[^>]*type\\\\s*=\\\\s*['\\\"]application/ld\\\\+json['\\\"][^>]*>(.*?)</script\\\\s*>"
        );
        Matcher matcher = scriptPattern.matcher(page);

        while (matcher.find()) {
            String jsonText = matcher.group(1).trim();
            try {
                Object data = new org.json.JSONTokener(jsonText).nextValue();
                JSONObject recipe = findRecipeObject(data, 0);
                if (recipe != null) return recipeToImport(recipe);
            } catch (Exception ignored) {
                // Continue to other JSON-LD blocks.
            }
        }

        throw new Exception("No structured recipe was found on that webpage.");
    }

    private JSONObject findRecipeObject(Object node, int depth) {
        if (node == null || depth > 20) return null;

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findRecipeObject(array.opt(i), depth + 1);
                if (found != null) return found;
            }
            return null;
        }

        if (!(node instanceof JSONObject)) return null;

        JSONObject object = (JSONObject) node;
        Object type = object.opt("@type");
        boolean isRecipe = false;

        if (type instanceof String) {
            isRecipe = "Recipe".equalsIgnoreCase((String) type);
        } else if (type instanceof JSONArray) {
            JSONArray types = (JSONArray) type;
            for (int i = 0; i < types.length(); i++) {
                if ("Recipe".equalsIgnoreCase(types.optString(i))) {
                    isRecipe = true;
                    break;
                }
            }
        }

        if (isRecipe) return object;

        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject found = findRecipeObject(object.opt(key), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private JSONObject recipeToImport(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        String name = source.optString("name", "").trim();
        if (name.isEmpty()) throw new Exception("The recipe has no name.");

        result.put("name", name);
        result.put("ingredients", recipeIngredients(source.opt("recipeIngredient")));
        result.put("instructions", recipeInstructions(source.opt("recipeInstructions")));
        result.put("servings", recipeServings(source.opt("recipeYield")));
        return result;
    }

    private String recipeIngredients(Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String item = array.optString(i, "").trim();
                if (!item.isEmpty()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(item);
                }
            }
            return text.toString();
        }
        return value == null || value == JSONObject.NULL ? "" : value.toString();
    }

    private String recipeInstructions(Object value) {
        StringBuilder text = new StringBuilder();
        appendRecipeInstructions(value, text, 0);
        return text.toString().trim();
    }

    private void appendRecipeInstructions(Object value,
            StringBuilder text, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 12) return;

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++)
                appendRecipeInstructions(array.opt(i), text, depth + 1);
            return;
        }

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String instruction = object.optString("text", "").trim();
            if (!instruction.isEmpty()) {
                if (text.length() > 0) text.append("\n");
                text.append(instruction);
            } else {
                appendRecipeInstructions(object.opt("itemListElement"),
                    text, depth + 1);
            }
            return;
        }

        String instruction = value.toString().trim();
        if (!instruction.isEmpty()) {
            if (text.length() > 0) text.append("\n");
            text.append(instruction);
        }
    }

    private int recipeServings(Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > 0) value = array.opt(0);
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            value = object.opt("value");
        }
        if (value == null || value == JSONObject.NULL) return 1;

        Matcher matcher = Pattern.compile("\\\\d+").matcher(value.toString());
        if (matcher.find()) {
            try {
                return Math.max(1, Math.min(100,
                    Integer.parseInt(matcher.group())));
            } catch (NumberFormatException ignored) {}
        }
        return 1;
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


    private static final int NOTIFICATION_PERMISSION_REQUEST = 9201;
    private static final String AFFIRMATION_CHANNEL = "daily_affirmations";

    private void initializeNotifications() {
        ReminderScheduler.scheduleAll(this);

        NotificationManager manager =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                AFFIRMATION_CHANNEL,
                "Daily affirmations",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("A gentle daily thought from Crumb");
            manager.createNotificationChannel(channel);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST
            );
            return;
        }

        showDailyAffirmationNotification();
    }

    private void showDailyAffirmationNotification() {
        String[] affirmations = {
            "You deserve kindness, including from yourself.",
            "You do not have to earn your right to feel good.",
            "Progress does not have to be perfect.",
            "You are more than numbers on a screen.",
            "You deserve patience on difficult days.",
            "Taking care of yourself is worthwhile.",
            "You are allowed to take things one step at a time.",
            "Your worth does not depend on what you eat or achieve."
        };

        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        long dayNumber = today.getTimeInMillis() / 86400000L;
        int index = (int) ((dayNumber % affirmations.length
            + affirmations.length) % affirmations.length);

        Notification.Builder builder =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, AFFIRMATION_CHANNEL)
                : new Notification.Builder(this);

        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("A thought for today ✿")
            .setContentText(affirmations[index])
            .setStyle(new Notification.BigTextStyle()
                .bigText(affirmations[index]))
            .setAutoCancel(true);

        NotificationManager manager =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (manager != null) {
            manager.notify(9200, builder.build());
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == NOTIFICATION_PERMISSION_REQUEST &&
            grantResults.length > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showDailyAffirmationNotification();
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

        if (foodInterpreter != null) foodInterpreter.close();

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }
}
