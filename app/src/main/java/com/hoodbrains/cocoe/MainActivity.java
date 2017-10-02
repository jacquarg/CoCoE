package com.hoodbrains.cocoe;

import android.app.Activity;


import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// https://www.programcreek.com/java-api-examples/index.php?api=android.hardware.camera2.CameraManager
public class MainActivity extends Activity {
    private static final String TAG = "AndroidCameraApi";

    private Button takePictureButton;
    private TextureView textureView;
    private TextView blink;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private String cameraId;
    protected CameraDevice cameraDevice;
    private Size imageDimension;
    private File file;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    private enum Modes {
        HIDDLE("Pause"),
        TEST("Tester"),
        RUN("Mesurer");

        private final String label;

        Modes(String label) {
            this.label = label;
        }
        public Modes next() {
            switch (this) {
                case HIDDLE: return TEST;
                case TEST: return RUN;
                case RUN: return TEST;
                default: return TEST;
            }
        }
    }

    private Modes currentMode = Modes.HIDDLE;

    /* Lifecycle */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        takePictureButton = (Button) findViewById(R.id.btn_takepicture);
        assert takePictureButton != null;
        takePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Button button = (Button) v;

                closeCamera();
                stopBackgroundThread();

                currentMode = currentMode.next();
                button.setText(currentMode.next().label);

                startBackgroundThread();

                if (currentMode == Modes.TEST && !textureView.isAvailable()) {
                    textureView.setSurfaceTextureListener(textureListener);
                } else {
                    openCamera();
                }
            }
        });
        blink = (TextView) findViewById(R.id.blink);
        assert blink != null;
    }

//    @Override
//    protected void onResume() {
//        super.onResume();
//        startBackgroundThread();
//        if (textureView.isAvailable()) {
//            openCamera();
//        } else {
//            textureView.setSurfaceTextureListener(textureListener);
//        }
//    }
//    @Override
//    protected void onPause() {
//        closeCamera();
//        stopBackgroundThread();
//        super.onPause();
//    }

    /* END Lifecycle */

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /* Camera handling */
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
//            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
//                ActivityCompat.requestPermissions(AndroidCameraApi.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
//                return;
//            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            launchCamera();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

//    @Override
//    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
//        if (requestCode == REQUEST_CAMERA_PERMISSION) {
//            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
//                // close the app
//                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
//                finish();
//            }
//        }
//    }

    protected void launchCamera() {
        if(null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {

            List<Surface> outputSurfaces = new ArrayList<Surface>(1);
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            switch(currentMode) {
                case TEST: registerTextureView(captureBuilder, outputSurfaces); break;
                case RUN: registerBlinkDetector(captureBuilder, outputSurfaces); break;
                default: break;
            }

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                    try {
                        cameraCaptureSession.setRepeatingRequest(captureBuilder.build(), null, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, e.getStackTrace().toString());
        }
    }


    /* END Camera handling */

    /* Show preview */
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    void registerTextureView(CaptureRequest.Builder captureBuilder, List<Surface> outputSurfaces) {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        assert texture != null;
        texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
        Surface surface = new Surface(texture);
        outputSurfaces.add(surface);
        captureBuilder.addTarget(surface);
    }


    /* END Show preview */

    /* Detecting blinks */

    void registerBlinkDetector(CaptureRequest.Builder captureBuilder, List<Surface> outputSurfaces) {
        int width = 100;
        int height = 100;
        ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 1);
        outputSurfaces.add(reader.getSurface());
        captureBuilder.addTarget(reader.getSurface());

        // Orientation
        //int rotation = getWindowManager().getDefaultDisplay().getRotation();
        //captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
        //final File file = new File(Environment.getExternalStorageDirectory()+"/pic.jpg");
        ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                //image = reader.acquireLatestImage();
                image = reader.acquireNextImage();
                if (isBlink(image)) {
                    Log.d(TAG, "blink!");
                    persistBlink();
                }

                if (image != null) {
                    image.close();
                }
            }
        };

        reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
    }

    long lastBlinkTime = System.currentTimeMillis();

    boolean isBlink(Image image) {
        int BLINK_TRESHOLD = 30;
        // Minimal Period between two blink. for 1Wh/blink, 300ms -> 11kVA
        int BLINK_MIN_PERIOD = 300;

        long now = System.currentTimeMillis();
        if (now < lastBlinkTime + BLINK_MIN_PERIOD) {
            return false;
        }

        ByteBuffer buffer = image.getPlanes()[0].getBuffer();

        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);

        double moy = 0;
        for (byte y :bytes) {
            moy += 0xff & y;
        }
        moy = moy / buffer.capacity();
        Log.d("Y moy: ", "" + moy);

//        final double average = moy;
//        runOnUiThread(new Runnable(){
//            @Override
//            public void run(){
//                blink.setText("" + average);
//            }
//        });
        boolean blink = moy > BLINK_TRESHOLD;
        if (blink) {
            lastBlinkTime = now;
        }
        return blink;
    }
    /* END Detecting blinks */



    /* Write in file */
    void persistBlink() {
        FileWriter f;
        try {
            SimpleDateFormat isoDate = new SimpleDateFormat("yyyyMMdd");
            SimpleDateFormat isoDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

            Date now = new Date();

            f = new FileWriter(getExternalFilesDir(null).getAbsolutePath() + "/blinks_" + isoDate.format(now) + ".txt", true);
            f.write(isoDateTime.format(now) + "\n");
            f.flush();
            f.close();
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        }
    }
    /* END Write in file */

}
