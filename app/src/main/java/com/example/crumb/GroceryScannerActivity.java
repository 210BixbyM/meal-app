package com.example.crumb;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
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

        previewView = new PreviewView(this);
        root.addView(previewView, new LinearLayout.LayoutParams(
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
                        runOnUiThread(() -> {
                            status.setText("Barcode found: " + barcode
                                + "\nNow photograph the package and nutrition label.");
                            frontButton.setEnabled(true);
                            labelButton.setEnabled(true);
                            finishButton.setEnabled(true);
                        });
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
