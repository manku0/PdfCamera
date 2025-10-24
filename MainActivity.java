package com.maya.camera;

import android.Manifest;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private PreviewView viewFinder;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private Camera camera;

    private String currentFilter = "Normal";
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private float originalScreenBrightness = -1f;

    private final List<View> filterButtons = new ArrayList<>();

    private final ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> {
                if (Boolean.TRUE.equals(permissions.get(Manifest.permission.CAMERA)) &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || Boolean.TRUE.equals(permissions.get(Manifest.permission.WRITE_EXTERNAL_STORAGE)))) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        FloatingActionButton captureButton = findViewById(R.id.capture_button);
        FloatingActionButton flipCameraButton = findViewById(R.id.flip_camera_button);
        FloatingActionButton pdfButton = findViewById(R.id.pdf_button);
        FloatingActionButton flashButton = findViewById(R.id.flash_button);

        setupFilterButtons();
        setupFlashButton(flashButton);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        // JPEG capture: uses the high-resolution ImageCapture use case.
        captureButton.setOnClickListener(v -> captureAndProcessImage(this::saveAsJpeg));

        // PDF capture: grabs the visible preview from the screen.
        pdfButton.setOnClickListener(v -> {
            Bitmap bitmap = viewFinder.getBitmap();
            if (bitmap != null) {
                Bitmap filteredBitmap = applyFilter(bitmap);
                showPreviewDialog(filteredBitmap);
            } else {
                Toast.makeText(this, "Preview not available for PDF capture.", Toast.LENGTH_SHORT).show();
            }
        });

        flipCameraButton.setOnClickListener(v -> {
            // Restore brightness before switching camera
            restoreOriginalBrightness();

            if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
            } else {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
            }
            startCamera();
        });

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void setupFilterButtons() {
        setupFilterButton(findViewById(R.id.btn_filter_normal), "Normal");
        setupFilterButton(findViewById(R.id.btn_filter_grayscale), "Grayscale");
        setupFilterButton(findViewById(R.id.btn_filter_sepia), "Sepia");
        setupFilterButton(findViewById(R.id.btn_filter_invert), "Invert");
        setupFilterButton(findViewById(R.id.btn_filter_aqua), "Aqua");
        setupFilterButton(findViewById(R.id.btn_filter_red), "Red");
        setupFilterButton(findViewById(R.id.btn_filter_green), "Green");
        setupFilterButton(findViewById(R.id.btn_filter_blue), "Blue");

        // Set initial highlight
        updateButtonHighlights(filterButtons.get(0));
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void setupFilterButton(View button, final String filterName) {
        filterButtons.add(button);
        button.setOnClickListener(v -> {
            currentFilter = filterName;
            updateButtonHighlights(v);
            updatePreviewEffect();
        });
    }

    private void setupFlashButton(FloatingActionButton flashButton) {
        flashButton.setOnClickListener(v -> {
            if (camera == null) {
                Toast.makeText(this, "Camera not ready.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (camera.getCameraInfo().hasFlashUnit()) {
                // Logic for hardware flash (back camera)
                restoreOriginalBrightness(); // Turn off screen flash if it was on
                switch (flashMode) {
                    case ImageCapture.FLASH_MODE_OFF:
                        flashMode = ImageCapture.FLASH_MODE_ON;
                        flashButton.setAlpha(1.0f);
                        Toast.makeText(this, "Flash: ON", Toast.LENGTH_SHORT).show();
                        break;
                    case ImageCapture.FLASH_MODE_ON:
                        flashMode = ImageCapture.FLASH_MODE_AUTO;
                        Toast.makeText(this, "Flash: AUTO", Toast.LENGTH_SHORT).show();
                        break;
                    case ImageCapture.FLASH_MODE_AUTO:
                        flashMode = ImageCapture.FLASH_MODE_OFF;
                        flashButton.setAlpha(0.6f);
                        Toast.makeText(this, "Flash: OFF", Toast.LENGTH_SHORT).show();
                        break;
                }
                imageCapture.setFlashMode(flashMode);
                camera.getCameraControl().enableTorch(flashMode == ImageCapture.FLASH_MODE_ON);
            } else {
                // Logic for screen flash (front camera)
                camera.getCameraControl().enableTorch(false);
                if (flashMode == ImageCapture.FLASH_MODE_ON) { // If screen flash is on, turn it off
                    flashMode = ImageCapture.FLASH_MODE_OFF;
                    restoreOriginalBrightness();
                    flashButton.setAlpha(0.6f);
                    Toast.makeText(this, "Screen Flash: OFF", Toast.LENGTH_SHORT).show();
                } else { // If screen flash is off, turn it on
                    flashMode = ImageCapture.FLASH_MODE_ON;
                    if (originalScreenBrightness < 0) {
                        originalScreenBrightness = getWindow().getAttributes().screenBrightness;
                    }
                    WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
                    layoutParams.screenBrightness = 1.0F; // Max brightness
                    getWindow().setAttributes(layoutParams);

                    flashButton.setAlpha(1.0f);
                    Toast.makeText(this, "Screen Flash: ON", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void restoreOriginalBrightness() {
        if (originalScreenBrightness >= 0) {
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.screenBrightness = originalScreenBrightness;
            getWindow().setAttributes(layoutParams);
            originalScreenBrightness = -1f;
        }
    }

    private void updateButtonHighlights(View selectedView) {
        for (View view : filterButtons) {
            view.setAlpha(view == selectedView ? 1.0f : 0.6f);
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        } else {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE});
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(flashMode)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                updatePreviewEffect();

            } catch (Exception e) {
                Log.e("MainActivity", "Failed to start camera", e);
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalCamera2Interop
    private void updatePreviewEffect() {
        if (camera == null) {
            return;
        }

        int effectMode;
        switch (currentFilter) {
            case "Grayscale":
                effectMode = CaptureRequest.CONTROL_EFFECT_MODE_MONO;
                break;
            case "Sepia":
                effectMode = CaptureRequest.CONTROL_EFFECT_MODE_SEPIA;
                break;
            case "Invert":
                effectMode = CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE;
                break;
            case "Aqua":
                effectMode = CaptureRequest.CONTROL_EFFECT_MODE_AQUA;
                break;
            default:
                effectMode = CaptureRequest.CONTROL_EFFECT_MODE_OFF;
                if (currentFilter.equals("Red") || currentFilter.equals("Green") || currentFilter.equals("Blue")) {
                    Toast.makeText(this, currentFilter + " filter only applied to captured image", Toast.LENGTH_SHORT).show();
                }
                break;
        }

        Camera2CameraControl camera2Control = Camera2CameraControl.from(camera.getCameraControl());
        CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, effectMode)
                .build();
        ListenableFuture<Void> future = camera2Control.setCaptureRequestOptions(options);
        future.addListener(() -> {
            try {
                future.get();
            } catch (Exception e) {
                Log.e("MainActivity", "Failed to set capture request options for filter: " + currentFilter, e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureAndProcessImage(Consumer<Bitmap> saveAction) {
        if (imageCapture == null) return;

        imageCapture.setTargetRotation(viewFinder.getDisplay().getRotation());

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap sourceBitmap = imageProxyToBitmap(image);
                image.close();

                Bitmap filteredBitmap = applyFilter(sourceBitmap);

                runOnUiThread(() -> saveAction.accept(filteredBitmap));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Image capture failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showPreviewDialog(Bitmap bitmap) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_preview);

        ImageView previewImage = dialog.findViewById(R.id.preview_image);
        previewImage.setImageBitmap(bitmap);

        Button saveButton = dialog.findViewById(R.id.save_button);
        saveButton.setOnClickListener(v -> {
            saveAsPdf(bitmap);
            dialog.dismiss();
        });

        Button cancelButton = dialog.findViewById(R.id.cancel_button);
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap sourceBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());

        return Bitmap.createBitmap(
                sourceBitmap, 0, 0, sourceBitmap.getWidth(), sourceBitmap.getHeight(), matrix, true
        );
    }

    private Bitmap applyFilter(Bitmap originalBitmap) {
        switch (currentFilter) {
            case "Grayscale":
                return toGrayscale(originalBitmap);
            case "Sepia":
                return toSepia(originalBitmap);
            case "Invert":
                return toInvert(originalBitmap);
            case "Aqua":
                return toAqua(originalBitmap);
            case "Red":
                return toRed(originalBitmap);
            case "Green":
                return toGreen(originalBitmap);
            case "Blue":
                return toBlue(originalBitmap);
            default: // Normal
                return originalBitmap;
        }
    }

    private Bitmap toGrayscale(Bitmap original) {
        Bitmap grayscaleBitmap = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(grayscaleBitmap);
        Paint paint = new Paint();
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        canvas.drawBitmap(original, 0, 0, paint);
        return grayscaleBitmap;
    }

    private Bitmap toSepia(Bitmap original) {
        Bitmap sepiaBitmap = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sepiaBitmap);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        ColorMatrix sepiaMatrix = new ColorMatrix();
        sepiaMatrix.setScale(1f, 0.95f, 0.82f, 1.0f);
        matrix.postConcat(sepiaMatrix);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(original, 0, 0, paint);
        return sepiaBitmap;
    }

    private Bitmap toInvert(Bitmap original) {
        Bitmap invertedBitmap = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(invertedBitmap);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.set(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(original, 0, 0, paint);
        return invertedBitmap;
    }

    private Bitmap toAqua(Bitmap original) {
        Bitmap aquaBitmap = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(aquaBitmap);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.set(new float[]{
                0, 0, 1, 0, 0,
                0, 1, 0, 0, 0,
                1, 0, 0, 0, 0,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(original, 0, 0, paint);
        return aquaBitmap;
    }

    private Bitmap toRed(Bitmap original) {
        Bitmap redBitmap = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(redBitmap);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.set(new float[]{
                1, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(original, 0, 0, paint);
        return redBitmap;
    }

    private Bitmap toGreen(Bitmap original) {
        Bitmap greenBitmap = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(greenBitmap);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.set(new float[]{
                0, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(original, 0, 0, paint);
        return greenBitmap;
    }

    private Bitmap toBlue(Bitmap original) {
        Bitmap blueBitmap = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(blueBitmap);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.set(new float[]{
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(original, 0, 0, paint);
        return blueBitmap;
    }

    private void saveAsJpeg(Bitmap bitmap) {
        String fileName = new SimpleDateFormat("'IMG'_yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpeg";
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
        }

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        if (uri == null) {
            Toast.makeText(this, "Failed to create new MediaStore record.", Toast.LENGTH_SHORT).show();
            return;
        }

        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                Toast.makeText(this, "Failed to open output stream.", Toast.LENGTH_SHORT).show();
                return;
            }
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            Toast.makeText(this, "Image saved as JPEG", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save JPEG: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveAsPdf(Bitmap bitmap) {
        String fileName = new SimpleDateFormat("'DOC'_yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".pdf";
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
        }

        Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), contentValues);
        if (uri == null) {
            Toast.makeText(this, "Failed to create new MediaStore record.", Toast.LENGTH_SHORT).show();
            return;
        }

        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                Toast.makeText(this, "Failed to open output stream.", Toast.LENGTH_SHORT).show();
                return;
            }
            PdfWriter writer = new PdfWriter(out);
            PdfDocument pdf = new PdfDocument(writer);

            float padding = 10f;
            PageSize pageSize = new PageSize(bitmap.getWidth() + padding * 2, bitmap.getHeight() + padding * 2);
            Document document = new Document(pdf, pageSize);
            document.setMargins(padding, padding, padding, padding);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            Image pdfImage = new Image(ImageDataFactory.create(stream.toByteArray()));
            pdfImage.setAutoScale(true);

            document.add(pdfImage);
            document.close();

            Toast.makeText(this, "Image saved as PDF", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        restoreOriginalBrightness(); // Ensure brightness is restored when the app closes
    }
}
