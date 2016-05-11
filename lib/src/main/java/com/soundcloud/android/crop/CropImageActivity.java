/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soundcloud.android.crop;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.opengl.GLES10;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import org.bonnyfone.brdcompat.BitmapRegionDecoderCompat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;

/*
 * Modified from original in AOSP.
 */
public class CropImageActivity extends MonitoredActivity {

    private static final int SIZE_DEFAULT = 2048;
    private static final int SIZE_LIMIT = 4096;

    private final static int WRITE_PERMISSION_REQUEST = 5;
    private final static int READ_PERMISSION_REQUEST = 4;


    private final Handler handler = new Handler();

    private int layoutResId = R.layout.crop__activity_crop;
    private int resIdCropImage = R.id.crop_image;
    private int resIdBtnDone = R.id.btn_done;
    private int resIdBtnCancel = R.id.btn_cancel;

    // the aspect ratio of the image (default is 1:1)
    private int aspectX;
    private int aspectY;

    // Output image
    private int maxX;
    private int maxY;
    private int exifRotation;

    private Uri sourceUri;
    private Uri saveUri;
    private boolean canRead;
    private boolean canWrite;
    private boolean externalReadDenied=false;
    private boolean externalWriteDenied=false;

    private boolean isSaving;
    private boolean isLoaded=false;

    private int sampleSize;
    private RotateBitmap rotateBitmap;
    private CropImageView imageView;
    private HighlightView cropView;

    private String loopbackTag;

    @Override
    public void onCreate(final Bundle icicle) {

        super.onCreate(icicle);
        this.setupWindowFlags();

        this.loadExtras();
        this.setupViews();

        canRead=checkUriReadable(sourceUri);
        canWrite=checkUriWritableAndClean(saveUri);

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == WRITE_PERMISSION_REQUEST) {
            if ( permissions!=null && permissions.length>0 && permissions[0].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) )
                externalWriteDenied = !(grantResults[0] == PackageManager.PERMISSION_GRANTED);
            return; //Handle no more...
        }
        if (requestCode == READ_PERMISSION_REQUEST) {
            if ( permissions!=null && permissions.length>0 && permissions[0].equals(Manifest.permission.READ_EXTERNAL_STORAGE) )
                externalReadDenied = !(grantResults[0] == PackageManager.PERMISSION_GRANTED);
            return; //Handle no more...
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!canWrite /*&& ContentResolver.SCHEME_FILE.equals(saveUri.getScheme())*/) {
                //May be lack of WRITE_EXTERNAL_STORAGE
                if (this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && !externalWriteDenied ) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERMISSION_REQUEST);
                    return;
                }
            }

            if (!canRead /*&& ContentResolver.SCHEME_FILE.equals(sourceUri.getScheme())*/) {
                //May be lack of READ_EXTERNAL_STORAGE
                if (this.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && !externalReadDenied ) {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, READ_PERMISSION_REQUEST);
                    return;
                }
            }
        }

        //canRead and canWrite may have changed after onRequestPermissionsResult
        canRead=checkUriReadable(sourceUri);
        canWrite=checkUriWritableAndClean(saveUri);

        if (!canRead)
            setResultException(new Exception("Permition required for"+sourceUri.toString()));

        if (!canWrite)
            setResultException(new Exception("Permition required for"+saveUri.toString()));

        if (!isLoaded)
            this.loadInput()
                    .doOnCompleted(new Action0() {
                        @Override
                        public void call() {
                            if (rotateBitmap == null) {
                                finish();
                                return;
                            }
                            startCrop();
                        }
                    })
                    .subscribe(new Subscriber<Void>() {
                        @Override
                        public void onCompleted() {

                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                            setResultException(e);
                            finish();
                        }

                        @Override
                        public void onNext(Void aVoid) {

                        }
                    });
        isLoaded=true;

    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void setupWindowFlags() {
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
    }

    /**
     * will load the extras passed with the intent
     */
    private void loadExtras() {

        final Intent intent = getIntent();
        this.sourceUri = intent.getData();

        final Bundle extras = intent.getExtras();

        if (extras != null) {
            this.aspectX = extras.getInt(Crop.Extra.ASPECT_X, 1);
            this.aspectY = extras.getInt(Crop.Extra.ASPECT_Y, 1);
            this.maxX = extras.getInt(Crop.Extra.MAX_X);
            this.maxY = extras.getInt(Crop.Extra.MAX_Y);
            this.saveUri = extras.getParcelable(MediaStore.EXTRA_OUTPUT);

            // load data for the usage of custom layouts
            this.layoutResId = extras.getInt(Crop.Extra.LAYOUT_ID, R.layout.crop__activity_crop);
            this.resIdCropImage = extras.getInt(Crop.Extra.LAYOUT_ID_CROP_IMAGE, R.id.crop_image);
            this.resIdBtnDone = extras.getInt(Crop.Extra.LAYOUT_ID_BTN_DONE, R.id.btn_done);
            this.resIdBtnCancel = extras.getInt(Crop.Extra.LAYOUT_ID_BTN_CANCEL, R.id.btn_cancel);

            this.loopbackTag = extras.getString(Crop.Extra.LOOPBACK_DATA); //null - default
        }


    }

    /**
     * will setup the layout for the activity
     */
    private void setupViews() {

        this.setContentView(this.layoutResId);

        this.imageView = (CropImageView) findViewById(this.resIdCropImage);
        this.imageView.context = this;
        this.imageView.setRecycler(new ImageViewTouchBase.Recycler() {
            @Override
            public void recycle(Bitmap b) {
                b.recycle();
                System.gc();
            }
        });

        this.findViewById(this.resIdBtnCancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        this.findViewById(this.resIdBtnDone).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onSaveClicked();
            }
        });
    }

    /**
     * load the bitmap from the given source Uri,
     * get all the intent extras & setup the aspect ratio for the cropping,
     * define the maximum of the image size
     */
    private Observable<Void> loadInput() {

        final Context context = this;
        return Observable.create(new Observable.OnSubscribe<Void>() {
            @Override
            public void call(Subscriber<? super Void> subscriber) {

                if (sourceUri != null) {
                    final File exifUri = RealPathUtil.getFile(context, sourceUri);// CropUtil.getPath(this, this.sourceUri);
                    if (exifUri == null) {
                        exifRotation = 0;
                    } else {
                        exifRotation = CropUtil.getExifRotation(exifUri);// CropUtil.getExifRotation(CropUtil.getFromMediaUri(context, getContentResolver(), exifUri));
                    }

                    InputStream is = null;
                    try {
                        sampleSize = calculateBitmapSampleSize(sourceUri);
                        is = getContentResolver().openInputStream(sourceUri);
                        final BitmapFactory.Options option = new BitmapFactory.Options();
                        option.inSampleSize = sampleSize;
                        option.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        rotateBitmap = new RotateBitmap(BitmapFactory.decodeStream(is, null, option), exifRotation);
                    } catch (IOException e) {
                        Log.e("Error reading image: " + e.getMessage(), e);
                        setResultException(e);
                    } catch (OutOfMemoryError e) {
                        Log.e("OOM reading image: " + e.getMessage(), e);
                        setResultException(e);
                    } finally {
                        CropUtil.closeSilently(is);
                    }
                }
                subscriber.onCompleted();

            }
        });

    }

    private int calculateBitmapSampleSize(Uri bitmapUri) throws IOException {
        InputStream is = null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            is = getContentResolver().openInputStream(bitmapUri);
            BitmapFactory.decodeStream(is, null, options); // Just get image size
        } finally {
            CropUtil.closeSilently(is);
        }

        int maxSize = getMaxImageSize();
        int sampleSize = 1;
        while (options.outHeight / sampleSize > maxSize || options.outWidth / sampleSize > maxSize) {
            sampleSize = sampleSize << 1;
        }
        return sampleSize;
    }

    private int getMaxImageSize() {
        int textureLimit = getMaxTextureSize();
        if (textureLimit == 0) {
            return SIZE_DEFAULT;
        } else {
            return Math.min(textureLimit, SIZE_LIMIT);
        }
    }

    private int getMaxTextureSize() {
        // The OpenGL texture size is the maximum size that can be drawn in an ImageView
        int[] maxSize = new int[1];
        GLES10.glGetIntegerv(GLES10.GL_MAX_TEXTURE_SIZE, maxSize, 0);
        return maxSize[0];
    }

    private void startCrop() {
        if (isFinishing()) {
            return;
        }
        imageView.setImageRotateBitmapResetBase(rotateBitmap, true);
        CropUtil.startBackgroundJob(this, null, getResources().getString(R.string.crop__wait),
                new Runnable() {
                    public void run() {
                        final CountDownLatch latch = new CountDownLatch(1);
                        handler.post(new Runnable() {
                            public void run() {
                                if (imageView.getScale() == 1f) {
                                    imageView.center();
                                }
                                latch.countDown();
                            }
                        });
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        new Cropper().crop();
                    }
                }, handler
        );
    }

    private class Cropper {

        private void makeDefault() {
            if (rotateBitmap == null) {
                return;
            }

            HighlightView hv = new HighlightView(imageView);
            final int width = rotateBitmap.getWidth();
            final int height = rotateBitmap.getHeight();

            Rect imageRect = new Rect(0, 0, width, height);

            // Make the default size about 4/5 of the width or height
            int cropWidth = Math.min(width, height) * 4 / 5;
            @SuppressWarnings("SuspiciousNameCombination")
            int cropHeight = cropWidth;

            if (aspectX != 0 && aspectY != 0) {
                if (aspectX > aspectY) {
                    cropHeight = cropWidth * aspectY / aspectX;
                } else {
                    cropWidth = cropHeight * aspectX / aspectY;
                }
            }

            int x = (width - cropWidth) / 2;
            int y = (height - cropHeight) / 2;

            RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
            hv.setup(imageView.getUnrotatedMatrix(), imageRect, cropRect, aspectX != 0 && aspectY != 0);
            imageView.add(hv);
        }

        public void crop() {
            handler.post(new Runnable() {
                public void run() {
                    makeDefault();
                    imageView.invalidate();
                    if (imageView.highlightViews.size() == 1) {
                        cropView = imageView.highlightViews.get(0);
                        cropView.setFocus(true);
                    }
                }
            });
        }
    }

    private void onSaveClicked() {
        if (cropView == null || isSaving) {
            return;
        }
        isSaving = true;

        Bitmap croppedImage;
        Rect r = cropView.getScaledCropRect(sampleSize);
        int width = r.width();
        int height = r.height();

        int outWidth = width;
        int outHeight = height;
        if (maxX > 0 && maxY > 0 && (width > maxX || height > maxY)) {
            float ratio = (float) width / (float) height;
            if ((float) maxX / (float) maxY > ratio) {
                outHeight = maxY;
                outWidth = (int) ((float) maxY * ratio + .5f);
            } else {
                outWidth = maxX;
                outHeight = (int) ((float) maxX / ratio + .5f);
            }
        }

        try {
            croppedImage = decodeRegionCrop(r, outWidth, outHeight);
        } catch (IllegalArgumentException e) {
            setResultException(e);
            finish();
            return;
        }

        /*if (croppedImage != null) {
            imageView.setImageRotateBitmapResetBase(new RotateBitmap(croppedImage, exifRotation), true);
            imageView.center();
            imageView.highlightViews.clear();
        }*/
        clearImageView();
        imageView.highlightViews.clear();
        saveImage(croppedImage);
    }

    private void saveImage(Bitmap croppedImage) {
        if (croppedImage != null) {
            final Bitmap b = croppedImage;
            CropUtil.startBackgroundJob(this, null, getResources().getString(R.string.crop__saving),
                    new Runnable() {
                        public void run() {
                            saveOutput(b);
                        }
                    }, handler
            );
        } else {
            finish();
        }
    }

    /**
     * will attempt to create a region decoder. If it fails due difficult encoding, return null
     *
     * @param sourceUri the source Uri of the image
     * @return the decoder or null
     */
    @Nullable
    private BitmapRegionDecoderCompat loadBitmapRegionDecoder(final Uri sourceUri) {

        InputStream is = null;
        BitmapRegionDecoderCompat decoder = null;
        try {
            is = this.getContentResolver().openInputStream(sourceUri);
            decoder = BitmapRegionDecoderCompat.newInstance(is, false);
        } catch (IOException e) {
            // we know this error, meh
        } finally {
            CropUtil.closeSilently(is);
        }
        return decoder;

    }

    /**
     * will attempt to load the bitmap completely.
     *
     * @param sourceUri the source Uri of the image
     * @return the loaded bitmap or null if it fails to load the image
     */
    @Nullable
    private Bitmap loadOriginalImage(final Uri sourceUri) {

        InputStream is = null;
        try {
            final int sampleSize = calculateBitmapSampleSize(sourceUri);
            is = this.getContentResolver().openInputStream(sourceUri);
            final BitmapFactory.Options option = new BitmapFactory.Options();
            option.inPreferredConfig = Bitmap.Config.ARGB_8888;
            option.inSampleSize = sampleSize;
            return BitmapFactory.decodeStream(is, null, option);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            CropUtil.closeSilently(is);
        }
        return null;

    }

    @Nullable
    private Bitmap decodeRegionCrop(Rect rect, int outWidth, int outHeight) {

        // Release memory now
        //clearImageView();

        Bitmap originalImage = null;
        Bitmap croppedImage = null;
        BitmapRegionDecoderCompat decoder;

        // try to create the bitmap decoder
        decoder = loadBitmapRegionDecoder(sourceUri);
        if (decoder == null) {
            // if the decoder creation fails, load the image completely instead
            originalImage = loadOriginalImage(sourceUri);
            if (originalImage == null) {
                return null;
            }
        }

        try {
            final int width;
            final int height;
            if (decoder != null) {
                width = decoder.getWidth();
                height = decoder.getHeight();
            } else {
                width = originalImage.getWidth();
                height = originalImage.getHeight();
            }

            final boolean orientationChanged = (exifRotation / 90) % 2 != 0;
            if (orientationChanged) {
                final int tmp = outWidth;
                outWidth = outHeight;
                outHeight = tmp;
            }

            if (exifRotation != 0) {
                // Adjust crop area to account for image rotation
                Matrix matrix = new Matrix();
                matrix.setRotate(-exifRotation);

                RectF adjusted = new RectF();
                matrix.mapRect(adjusted, new RectF(rect));

                // Adjust to account for origin at 0,0
                adjusted.offset(adjusted.left < 0 ? width : 0, adjusted.top < 0 ? height : 0);
                rect = new Rect((int) adjusted.left, (int) adjusted.top, (int) adjusted.right, (int) adjusted.bottom);
            }

            try {
                if (decoder != null) {
                    croppedImage = decoder.decodeRegion(rect, new BitmapFactory.Options());
                } else {
                    croppedImage = Bitmap.createBitmap(originalImage, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
                    if (originalImage != croppedImage) {
                        originalImage.recycle();
                    }
                }
                Matrix matrix = new Matrix();
                boolean needsCreateBitmap = false;
                if (exifRotation != 0) {
                    matrix.postRotate(exifRotation);
                    needsCreateBitmap = true;
                }
                if (croppedImage != null && (rect.width() > outWidth || rect.height() > outHeight)) {
                    matrix.postScale((float) outWidth / rect.width(), (float) outHeight / rect.height());
                    needsCreateBitmap = true;
                }
                if (needsCreateBitmap) {
                    croppedImage = Bitmap.createBitmap(croppedImage, 0, 0, croppedImage.getWidth(), croppedImage.getHeight(), matrix, true);
                }
            } catch (IllegalArgumentException e) {
                // Rethrow with some extra information
                throw new IllegalArgumentException("Rectangle " + rect + " is outside of the image ("
                        + width + "," + height + "," + exifRotation + ")", e);
            }
        } catch (Exception e) {
            //Log.e("Error cropping image: " + e.getMessage(), e);
            Log.e("Error cropping image: " + e.getMessage(), e);
            setResultException(e);
        } catch (OutOfMemoryError e) {
            Log.e("OOM cropping image: " + e.getMessage(), e);
            setResultException(e);
        }
        return croppedImage;
    }

    private void clearImageView() {
        imageView.clear();
        if (rotateBitmap != null) {
            rotateBitmap.recycle();
        }
        System.gc();
    }


    private void saveOutput(Bitmap croppedImage) {

        int jpgQuality = 90;
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            if (extras.containsKey(Crop.Extra.JPG_QUALITY)) {
                jpgQuality = extras.getInt(Crop.Extra.JPG_QUALITY);
            }
        }

        if (saveUri != null) {
            OutputStream outputStream = null;
            try {
                outputStream = getContentResolver().openOutputStream(saveUri);
                if (outputStream != null) {
                    croppedImage.compress(Bitmap.CompressFormat.JPEG, jpgQuality, outputStream);
                }
            } catch (IOException e) {
                setResultException(e);
                Log.e("Cannot open file: " + saveUri, e);
            } finally {
                CropUtil.closeSilently(outputStream);
            }

            try {
                ExifUtil.copyExif(RealPathUtil.getPath(this, sourceUri),RealPathUtil.getPath(this, saveUri));
            } catch (Exception e) {
                Log.e("Error copying Exif data", e);
            }

            setResultUri(this.saveUri, this.exifRotation);
        }

        final Bitmap b = croppedImage;
        handler.post(new Runnable() {
            public void run() {
                imageView.clear();
                b.recycle();
            }
        });

        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rotateBitmap != null) {
            rotateBitmap.recycle();
        }
    }

    @Override
    public boolean onSearchRequested() {
        return false;
    }

    public boolean isSaving() {
        return isSaving;
    }

    private void setResultUri(final Uri uri, final int rotation) {
        setResult(
                RESULT_OK, new Intent()
                        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .putExtra(Crop.EXTRA_META_EXIF_ROTATION, rotation)
                        .putExtra(Crop.Extra.LOOPBACK_DATA, loopbackTag)
        );
    }

    private void setResultException(Throwable throwable) {
        setResult(Crop.RESULT_ERROR, new Intent()
                .putExtra(Crop.Extra.ERROR, throwable)
                .putExtra(Crop.Extra.LOOPBACK_DATA, loopbackTag)
        );
    }

//    private boolean checkUriReadable(Uri uri){
//        /* canRead() may not work */
//        File file = new File(CropUtil.getPath(this, uri).getPath());
//        return  (file!=null && file.canRead());
//    }

    private boolean checkUriReadable(Uri uri){
        boolean ret=true;
        InputStream is = null;
        try {
            RealPathUtil.getFile(getBaseContext(),uri); // This is necessary to cach SecurityException
            is = getContentResolver().openInputStream(uri);
        } catch (IOException e) {
            ret=false;
        } catch (SecurityException e) {
            ret=false;
        } finally {
            CropUtil.closeSilently(is);
        }
        return ret;
    }

//    private boolean checkUriWritable(Uri uri){
//        /* canWrite() may not work */
//        File file = new File(CropUtil.getPath(this, uri).getPath());
//        return  (file!=null && file.canWrite());
//    }

    private boolean checkUriWritableAndClean(Uri uri){
        boolean ret=true;
        OutputStream os = null;
        try {
            RealPathUtil.getFile(getBaseContext(),uri); // This is necessary to cach SecurityException
            os = getContentResolver().openOutputStream(saveUri);
        } catch (IOException e) {
            ret=false;
        } catch (SecurityException e) {
            ret=false;
        } finally {
            CropUtil.closeSilently(os);
        }

        File f = RealPathUtil.getFile(this, uri);
        if (f!=null) f.delete();

        return ret;
    }
}
