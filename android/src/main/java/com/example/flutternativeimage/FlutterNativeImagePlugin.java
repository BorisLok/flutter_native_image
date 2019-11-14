package com.example.flutternativeimage;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * FlutterNativeImagePlugin
 */
public class FlutterNativeImagePlugin implements MethodCallHandler {
    private final Activity activity;

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "flutter_native_image");
        channel.setMethodCallHandler(new FlutterNativeImagePlugin(registrar.activity()));
    }

    private FlutterNativeImagePlugin(Activity activity) {
        this.activity = activity;
    }

    private final android.os.Handler mHandler = new android.os.Handler();

    // MethodChannel.Result wrapper that responds on the platform thread.
    private static class MethodResultWrapper implements MethodChannel.Result {
        private MethodChannel.Result methodResult;
        private Handler handler;

        MethodResultWrapper(MethodChannel.Result result) {
            methodResult = result;
            handler = new Handler(Looper.getMainLooper());
        }

        @Override
        public void success(final Object result) {
            handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            methodResult.success(result);
                        }
                    });
        }

        @Override
        public void error(
                final String errorCode, final String errorMessage, final Object errorDetails) {
            handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            methodResult.error(errorCode, errorMessage, errorDetails);
                        }
                    });
        }

        @Override
        public void notImplemented() {
            handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            methodResult.notImplemented();
                        }
                    });
        }
    }

    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        final MethodChannel.Result resultWrapper = new MethodResultWrapper(result);
        if (call.method.equals("compressImage")) {
            final android.os.Handler handler = new android.os.Handler();

            final String fileName = call.argument("file");
            final int resizePercentage = call.argument("percentage");
            final int targetWidth = call.argument("targetWidth") == null ? 0 : (int) call.argument("targetWidth");
            final int targetHeight = call.argument("targetHeight") == null ? 0 : (int) call.argument("targetHeight");
            final int quality = call.argument("quality");

            final File file = new File(fileName);
            Thread compressImageThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (!file.exists()) {
                        resultWrapper.error("file does not exist", fileName, null);
                        return;
                    }

                    Bitmap bmp = BitmapFactory.decodeFile(fileName);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();

                    int newWidth = targetWidth == 0 ? (bmp.getWidth() / 100 * resizePercentage) : targetWidth;
                    int newHeight = targetHeight == 0 ? (bmp.getHeight() / 100 * resizePercentage) : targetHeight;

                    bmp = Bitmap.createScaledBitmap(
                            bmp, newWidth, newHeight, true);

                    // reconfigure bitmap to use RGB_565 before compressing
                    // fixes https://github.com/btastic/flutter_native_image/issues/47
                    Bitmap newBmp = bmp.copy(Bitmap.Config.RGB_565, false);
                    newBmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);

                    try {
                        final String outputFileName = File.createTempFile(
                                getFilenameWithoutExtension(file).concat("_compressed"),
                                ".jpg",
                                activity.getExternalCacheDir()
                        ).getPath();

                        OutputStream outputStream = new FileOutputStream(outputFileName);
                        bos.writeTo(outputStream);

                        copyExif(fileName, outputFileName);

                        resultWrapper.success(outputFileName);

                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        resultWrapper.error("file does not exist", fileName, null);
                    } catch (IOException e) {
                        e.printStackTrace();
                        resultWrapper.error("something went wrong", fileName, null);
                    }
                }
            });

            compressImageThread.start();

            return;
        }
        if (call.method.equals("getImageProperties")) {
            String fileName = call.argument("file");
            File file = new File(fileName);

            if (!file.exists()) {
                result.error("file does not exist", fileName, null);
                return;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(fileName, options);
            HashMap<String, Integer> properties = new HashMap<String, Integer>();
            properties.put("width", options.outWidth);
            properties.put("height", options.outHeight);

            int orientation = ExifInterface.ORIENTATION_UNDEFINED;
            try {
                ExifInterface exif = new ExifInterface(fileName);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            } catch (IOException ex) {
                // EXIF could not be read from the file; ignore
            }
            properties.put("orientation", orientation);

            result.success(properties);
            return;
        }
        if (call.method.equals("cropImage")) {
            String fileName = call.argument("file");
            int originX = call.argument("originX");
            int originY = call.argument("originY");
            int width = call.argument("width");
            int height = call.argument("height");

            File file = new File(fileName);

            if (!file.exists()) {
                result.error("file does not exist", fileName, null);
                return;
            }

            Bitmap bmp = BitmapFactory.decodeFile(fileName);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            try {
                bmp = Bitmap.createBitmap(bmp, originX, originY, width, height);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                result.error("bounds are outside of the dimensions of the source image", fileName, null);
            }

            bmp.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            bmp.recycle();
            OutputStream outputStream = null;
            try {
                String outputFileName = File.createTempFile(
                        getFilenameWithoutExtension(file).concat("_cropped"),
                        ".jpg",
                        activity.getExternalCacheDir()
                ).getPath();


                outputStream = new FileOutputStream(outputFileName);
                bos.writeTo(outputStream);

                copyExif(fileName, outputFileName);

                result.success(outputFileName);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                result.error("file does not exist", fileName, null);
            } catch (IOException e) {
                e.printStackTrace();
                result.error("something went wrong", fileName, null);
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }


            return;
        }
        if (call.method.equals("getPlatformVersion")) {
            result.success("Android " + android.os.Build.VERSION.RELEASE);
        } else {
            result.notImplemented();
        }
    }

    private void copyExif(String filePathOri, String filePathDest) {
        try {
            ExifInterface oldExif = new ExifInterface(filePathOri);
            ExifInterface newExif = new ExifInterface(filePathDest);

            List<String> attributes =
                    Arrays.asList(
                            "FNumber",
                            "ExposureTime",
                            "ISOSpeedRatings",
                            "GPSAltitude",
                            "GPSAltitudeRef",
                            "FocalLength",
                            "GPSDateStamp",
                            "WhiteBalance",
                            "GPSProcessingMethod",
                            "GPSTimeStamp",
                            "DateTime",
                            "Flash",
                            "GPSLatitude",
                            "GPSLatitudeRef",
                            "GPSLongitude",
                            "GPSLongitudeRef",
                            "Make",
                            "Model",
                            "Orientation");
            for (String attribute : attributes) {
                setIfNotNull(oldExif, newExif, attribute);
            }

            newExif.saveAttributes();

        } catch (Exception ex) {
            Log.e("FlutterNativeImagePlugin", "Error preserving Exif data on selected image: " + ex);
        }
    }

    private void setIfNotNull(ExifInterface oldExif, ExifInterface newExif, String property) {
        if (oldExif.getAttribute(property) != null) {
            newExif.setAttribute(property, oldExif.getAttribute(property));
        }
    }

    private static String pathComponent(String filename) {
        int i = filename.lastIndexOf(File.separator);
        return (i > -1) ? filename.substring(0, i) : filename;
    }

    private static String getFilenameWithoutExtension(File file) {
        String fileName = file.getName();

        if (fileName.indexOf(".") > 0) {
            return fileName.substring(0, fileName.lastIndexOf("."));
        } else {
            return fileName;
        }
    }
}