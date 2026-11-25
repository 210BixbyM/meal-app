package com.example.crumb;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.graphics.Typeface;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.util.Size;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GroceryScannerActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION = 41;

    private PreviewView previewView;
    private TextView status;
    private FrameLayout previewContainer;
    private LinearLayout resultCard;
    private String foodBaseApiKey = "";
    private JSONObject matchedProduct;
    private Button frontButton, labelButton, finishButton;
    private ImageCapture imageCapture;
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private ExecutorService cameraExecutor;

    private String barcode = "";
    private String frontPhoto = "";
    private String labelPhoto = "";
    private String frontText = "";
    private String labelText = "";
    private final AtomicBoolean processing = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        cameraExecutor = Executors.newSingleThreadExecutor();
        foodBaseApiKey = getIntent().getStringExtra("foodBaseApiKey");
        if (foodBaseApiKey == null) foodBaseApiKey = "";

        BarcodeScannerOptions options =
            new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_ITF
                ).build();

        barcodeScanner = BarcodeScanning.getClient(options);
        textRecognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        );

        buildScreen();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION
            );
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        root.setBackgroundColor(Color.WHITE);

        TextView heading = new TextView(this);
        heading.setText("Crumb · Grocery scanner");
        heading.setTextSize(22);
        heading.setTextColor(Color.BLACK);
        root.addView(heading);

        status = new TextView(this);
        status.setText("Point the camera at a product barcode.");
        status.setTextSize(16);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, 12, 0, 12);
        root.addView(status);

        previewContainer = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        resultCard = new LinearLayout(this);
        resultCard.setOrientation(LinearLayout.VERTICAL);
        resultCard.setPadding(20, 18, 20, 18);
        resultCard.setBackgroundColor(Color.WHITE);
        resultCard.setElevation(16);
        resultCard.setVisibility(View.GONE);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        );
        cardParams.setMargins(12, 12, 12, 12);
        previewContainer.addView(resultCard, cardParams);

        root.addView(previewContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        frontButton = new Button(this);
        frontButton.setText("Take package photo");
        frontButton.setEnabled(false);
        root.addView(frontButton);

        labelButton = new Button(this);
        labelButton.setText("Take nutrition label photo");
        labelButton.setEnabled(false);
        root.addView(labelButton);

        finishButton = new Button(this);
        finishButton.setText("Use scanned product");
        finishButton.setEnabled(false);
        root.addView(finishButton);

        frontButton.setOnClickListener(v -> capturePhoto(true));
        labelButton.setOnClickListener(v -> capturePhoto(false));
        finishButton.setOnClickListener(v -> finishWithResult());

        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    ).build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    analysis
                );
            } catch (Exception e) {
                status.setText("Camera could not start: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy proxy) {
        if (barcode != null && !barcode.isEmpty()) {
            proxy.close();
            return;
        }

        if (!processing.compareAndSet(false, true)) {
            proxy.close();
            return;
        }

        android.media.Image media = proxy.getImage();
        if (media == null) {
            processing.set(false);
            proxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
            media, proxy.getImageInfo().getRotationDegrees()
        );

        barcodeScanner.process(image)
            .addOnSuccessListener(results -> {
                for (Barcode item : results) {
                    String value = item.getRawValue();
                    if (value != null && !value.trim().isEmpty()) {
                        barcode = value.trim();
                        String scanned = barcode;
                        runOnUiThread(() -> {
                            status.setText("Barcode detected. Looking up product…");
                            frontButton.setEnabled(true);
                            labelButton.setEnabled(true);
                            finishButton.setEnabled(true);
                        });
                        lookupFoodBase(scanned);
                        break;
                    }
                }
            })
            .addOnFailureListener(e -> runOnUiThread(() ->
                status.setText("Barcode scan error: " + e.getMessage())
            ))
            .addOnCompleteListener(task -> {
                processing.set(false);
                proxy.close();
            });
    }


    private void lookupFoodBase(String code) {
        cameraExecutor.execute(() -> {
            String foodBaseError = "";

            if (!foodBaseApiKey.trim().isEmpty()) {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(
                        "https://foodbase.dev/v1/foods/barcode/" + code + "?lang=en"
                    );
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(15000);
                    connection.setRequestProperty("X-API-Key", foodBaseApiKey.trim());
                    connection.setRequestProperty("Accept", "application/json");

                    int statusCode = connection.getResponseCode();
                    if (statusCode >= 200 && statusCode < 300) {
                        InputStream input = connection.getInputStream();
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) != -1)
                            output.write(buffer, 0, count);
                        input.close();

                        JSONObject response =
                            new JSONObject(output.toString("UTF-8"));
                        JSONObject product = response.optJSONObject("data");
                        if (product == null) product = response;

                        JSONObject macros =
                            product.optJSONObject("nutrition_summary");
                        if (macros == null)
                            macros = product.optJSONObject("macros");
                        if (macros == null) macros = new JSONObject();

                        JSONObject normalized = new JSONObject();
                        normalized.put("barcode", code);
                        normalized.put("name", product.optString("name_default",
                            product.optString("name_localized",
                            product.optString("product_name", "Food product"))));
                        normalized.put("brand", product.optString("brand", ""));
                        normalized.put("source", "FoodBase");
                        normalized.put("basis", "per 100 g");
                        normalized.put("calories", macros.opt("energy_kcal"));
                        normalized.put("protein", macros.opt("proteins_g"));
                        normalized.put("carbs", macros.opt("carbs_g"));
                        normalized.put("fat", macros.opt("fat_g"));
                        normalized.put("fiber", macros.opt("fiber_g"));
                        normalized.put("sugar", macros.opt("sugars_g"));
                        normalized.put("satFat", macros.opt("saturated_fat_g"));
                        normalized.put("sodium", macros.opt("sodium_mg"));

                        matchedProduct = normalized;
                        runOnUiThread(() -> showProductCard(normalized));
                        return;
                    }

                    foodBaseError = "FoodBase error " + statusCode;
                } catch (Exception e) {
                    foodBaseError = e.getMessage() == null
                        ? "FoodBase lookup failed." : e.getMessage();
                } finally {
                    if (connection != null) connection.disconnect();
                }
            } else {
                foodBaseError = "FoodBase key not configured.";
            }

            lookupOpenFoodFacts(code, foodBaseError);
        });
    }

    private void lookupOpenFoodFacts(String code, String previousError) {
        HttpURLConnection connection = null;
        try {
            runOnUiThread(() ->
                status.setText("No FoodBase match. Checking Open Food Facts…")
            );

            URL url = new URL(
                "https://world.openfoodfacts.org/api/v2/product/" + code + ".json"
            );
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent",
                "Crumb Android Grocery Scanner");

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300)
                throw new Exception("Open Food Facts error " + statusCode);

            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1)
                output.write(buffer, 0, count);
            input.close();

            JSONObject response = new JSONObject(output.toString("UTF-8"));
            if (response.optInt("status", 0) != 1)
                throw new Exception("Product not found in Open Food Facts.");

            JSONObject product = response.optJSONObject("product");
            if (product == null)
                throw new Exception("No product details returned.");

            JSONObject nutrition = product.optJSONObject("nutriments");
            if (nutrition == null) nutrition = new JSONObject();

            JSONObject normalized = new JSONObject();
            normalized.put("barcode", code);
            normalized.put("name", product.optString("product_name",
                product.optString("product_name_en", "Food product")));
            normalized.put("brand", product.optString("brands", ""));
            normalized.put("source", "Open Food Facts");
            normalized.put("basis", "per 100 g");

            putNutritionValue(normalized, nutrition, "calories", "energy-kcal_100g");
            putNutritionValue(normalized, nutrition, "protein", "proteins_100g");
            putNutritionValue(normalized, nutrition, "carbs", "carbohydrates_100g");
            putNutritionValue(normalized, nutrition, "fat", "fat_100g");
            putNutritionValue(normalized, nutrition, "fiber", "fiber_100g");
            putNutritionValue(normalized, nutrition, "sugar", "sugars_100g");
            putNutritionValue(normalized, nutrition, "satFat", "saturated-fat_100g");

            Object sodium = nutrition.opt("sodium_100g");
            if (sodium instanceof Number)
                normalized.put("sodium", ((Number) sodium).doubleValue() * 1000);
            else
                normalized.put("sodium", JSONObject.NULL);

            matchedProduct = normalized;
            runOnUiThread(() -> showProductCard(normalized));

        } catch (Exception e) {
            String offError = e.getMessage() == null
                ? "Open Food Facts lookup failed." : e.getMessage();
            runOnUiThread(() -> status.setText(
                "No database match. " + previousError + " / " + offError
                + ". You can still photograph the package."
            ));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void putNutritionValue(
        JSONObject target, JSONObject source, String targetKey, String sourceKey
    ) throws Exception {
        Object value = source.opt(sourceKey);
        target.put(targetKey,
            value == null ? JSONObject.NULL : value);
    }

    private void showProductCard(JSONObject product) {
        resultCard.removeAllViews();

        TextView heading = new TextView(this);
        heading.setText("PRODUCT FOUND");
        heading.setTextColor(Color.rgb(35, 125, 83));
        heading.setTypeface(null, Typeface.BOLD);
        resultCard.addView(heading);

        TextView name = new TextView(this);
        String brand = product.optString("brand", "");
        name.setText(product.optString("name", "Food product")
            + (brand.isEmpty() ? "" : "\n" + brand));
        name.setTextColor(Color.BLACK);
        name.setTextSize(19);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(0, 8, 0, 8);
        resultCard.addView(name);

        TextView nutrition = new TextView(this);
        nutrition.setText("Per 100 g\n"
            + product.optString("calories", "—") + " kcal"
            + "  ·  Protein " + product.optString("protein", "—") + " g"
            + "  ·  Carbs " + product.optString("carbs", "—") + " g"
            + "  ·  Fat " + product.optString("fat", "—") + " g");
        nutrition.setTextColor(Color.DKGRAY);
        resultCard.addView(nutrition);

        Button use = new Button(this);
        use.setText("Use this product");
        use.setOnClickListener(v -> finishWithResult());
        resultCard.addView(use);

        Button dismiss = new Button(this);
        dismiss.setText("Dismiss · scan another");
        dismiss.setOnClickListener(v -> {
            resultCard.setVisibility(View.GONE);
            matchedProduct = null;
            barcode = "";
            frontPhoto = "";
            labelPhoto = "";
            frontText = "";
            labelText = "";
            frontButton.setEnabled(false);
            labelButton.setEnabled(false);
            finishButton.setEnabled(false);
            status.setText("Camera active. Point at another barcode.");
        });
        resultCard.addView(dismiss);

        resultCard.setVisibility(View.VISIBLE);
        status.setText("Product found. Camera remains active.");
    }

    private File photoFile(String suffix) throws Exception {
        File folder = new File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "CrumbGroceries"
        );
        if (!folder.exists() && !folder.mkdirs()) {
            throw new Exception("Could not create photo folder");
        }
        return new File(folder,
            "grocery_" + System.currentTimeMillis() + "_" + suffix + ".jpg");
    }

    private void capturePhoto(boolean isFront) {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera is not ready.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File file = photoFile(isFront ? "front" : "label");
            ImageCapture.OutputFileOptions output =
                new ImageCapture.OutputFileOptions.Builder(file).build();

            status.setText(isFront
                ? "Taking package photo…"
                : "Taking nutrition label photo…");

            imageCapture.takePicture(
                output,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                        ImageCapture.OutputFileResults result
                    ) {
                        String path = file.getAbsolutePath();
                        recognizeText(file, isFront, path);
                    }

                    @Override
                    public void onError(ImageCaptureException error) {
                        status.setText("Photo failed: " + error.getMessage());
                    }
                }
            );
        } catch (Exception e) {
            status.setText("Could not save photo: " + e.getMessage());
        }
    }

    private void recognizeText(File file, boolean isFront, String path) {
        try {
            InputImage image = InputImage.fromFilePath(
                this, android.net.Uri.fromFile(file)
            );

            textRecognizer.process(image)
                .addOnSuccessListener(result -> {
                    StringBuilder text = new StringBuilder();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        text.append(block.getText()).append("\n");
                    }

                    if (isFront) {
                        frontPhoto = path;
                        frontText = text.toString();
                    } else {
                        labelPhoto = path;
                        labelText = text.toString();
                    }

                    status.setText(
                        "Barcode: " + barcode
                        + "\nPackage photo: " + (frontPhoto.isEmpty() ? "Not taken" : "Done")
                        + "\nNutrition label: " + (labelPhoto.isEmpty() ? "Not taken" : "Done")
                        + "\nYou can retake either photo or continue."
                    );
                })
                .addOnFailureListener(e -> status.setText(
                    "Photo saved, but text recognition failed: " + e.getMessage()
                ));
        } catch (Exception e) {
            status.setText("Could not read photo: " + e.getMessage());
        }
    }

    private void finishWithResult() {
        if (barcode == null || barcode.isEmpty()) {
            Toast.makeText(this, "Scan a barcode first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject result = new JSONObject();
            result.put("barcode", barcode);
            result.put("frontPhoto", frontPhoto);
            result.put("labelPhoto", labelPhoto);
            result.put("frontText", frontText);
            result.put("labelText", labelText);
            if (matchedProduct != null)
                result.put("product", matchedProduct);

            setResult(RESULT_OK,
                new android.content.Intent().putExtra("groceryResult", result.toString()));
            finish();
        } catch (Exception e) {
            status.setText("Could not prepare product: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] results
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION && results.length > 0
            && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            status.setText("Camera permission is needed to scan groceries.");
        }
    }

    @Override
    protected void onDestroy() {
        if (barcodeScanner != null) barcodeScanner.close();
        if (textRecognizer != null) textRecognizer.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }
}
