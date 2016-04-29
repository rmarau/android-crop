package com.soundcloud.android.crop;

import android.annotation.TargetApi;
import android.media.ExifInterface;
import android.os.Build;

import java.io.IOException;

public class ExifUtil {

    @TargetApi(Build.VERSION_CODES.M)
    public static void copyExif(String oldPath, String newPath) throws IOException
    {
        ExifInterface oldExif = new ExifInterface(oldPath);

        String[] attributes = new String[] {
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_IMAGE_LENGTH,
                ExifInterface.TAG_IMAGE_WIDTH,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_WHITE_BALANCE
        };

        String[] attributes_v11 = new String[]{
                ExifInterface.TAG_APERTURE,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_ISO,
        };

        String[] attributes_v23 = new String[]{
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_DIG,
                ExifInterface.TAG_SUBSEC_TIME_ORIG
        };

        ExifInterface newExif = new ExifInterface(newPath);
        for ( String s: attributes )
            newExif.setAttribute(s, oldExif.getAttribute(s));
        if (Build.VERSION.SDK_INT >= 11)
            for ( String s: attributes_v11 )
                newExif.setAttribute(s, oldExif.getAttribute(s));
        if (Build.VERSION.SDK_INT >= 23)
            for ( String s: attributes_v23 )
                newExif.setAttribute(s, oldExif.getAttribute(s));
        newExif.saveAttributes();
    }
}
