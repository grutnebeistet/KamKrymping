package net.sourceforge.opencamera;

import net.sourceforge.opencamera.cameracontroller.CameraController;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.renderscript.Allocation;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

/** Handles the saving (and any required processing) of photos.
 */
public class ImageSaver extends Thread {
    private static final String TAG = "ImageSaver";

    private final Paint p = new Paint();

    private final MainActivity main_activity;

    private int n_images_to_save = 0;
    private int n_real_images_to_save = 0;
    private final int queue_capacity;
    private final BlockingQueue<Request> queue;
    private final static int queue_cost_jpeg_c = 1; // also covers WEBP
    private final static int queue_cost_dng_c = 6;
    public static volatile boolean test_small_queue_size; // needs to be static, as it needs to be set before activity is created to take effect
    public volatile boolean test_slow_saving;
    public volatile boolean test_queue_blocked;

    static class Request {
        enum Type {
            JPEG
        }
        final Type type;
        enum ProcessType {
            NORMAL
        }
        final ProcessType process_type; // for type==JPEG
        final boolean force_suffix; // affects filename suffixes for saving jpeg_images: if true, filenames will always be appended with a suffix like _0, even if there's only 1 image in jpeg_images
        final int suffix_offset; // affects filename suffixes for saving jpeg_images, when force_suffix is true or there are multiple images in jpeg_images: the suffixes will be offset by this number
        enum SaveBase {
            SAVEBASE_NONE,
            SAVEBASE_FIRST,
            SAVEBASE_ALL
        }
        final SaveBase save_base; // whether to save the base images, for process_type HDR, AVERAGE or PANORAMA
        /* jpeg_images: for jpeg (may be null otherwise).
         * If process_type==HDR, this should be 1 or 3 images, and the images are combined/converted to a HDR image (if there's only 1
         * image, this uses fake HDR or "DRO").
         * If process_type==NORMAL, then multiple images are saved sequentially.
         */
        final List<byte []> jpeg_images;
        final boolean image_capture_intent;
        final Uri image_capture_intent_uri;
        final boolean using_camera2;
        /* image_format allows converting the standard JPEG image into another file format.
#		 */
        enum ImageFormat {
            STD // leave unchanged from the standard JPEG format
        }
        ImageFormat image_format;
        int image_quality;
        boolean do_auto_stabilise;
        final double level_angle; // in degrees
        final List<float []> gyro_rotation_matrix; // used for panorama (one 3x3 matrix per jpeg_images entry), otherwise can be null
        final boolean is_front_facing;
        boolean mirror;
        final Date current_date;
        final int iso; // not applicable for RAW image
        final long exposure_time; // not applicable for RAW image
        final float zoom_factor; // not applicable for RAW image
        String preference_stamp;
        String preference_textstamp;
        final boolean store_ypr; // whether to store geo_angle, pitch_angle, level_angle in USER_COMMENT if exif (for JPEGs)
        final double pitch_angle; // the pitch that the phone is at, in degrees
        final int sample_factor; // sampling factor for thumbnail, higher means lower quality

        Request(Type type,
                ProcessType process_type,
                boolean force_suffix,
                int suffix_offset,
                SaveBase save_base,
                List<byte []> jpeg_images,
                boolean image_capture_intent, Uri image_capture_intent_uri,
                boolean using_camera2,
                ImageFormat image_format, int image_quality,
                boolean do_auto_stabilise, double level_angle, List<float []> gyro_rotation_matrix,
                boolean is_front_facing,
                boolean mirror,
                Date current_date,
                int iso,
                long exposure_time,
                float zoom_factor,
                double pitch_angle, boolean store_ypr,
                int sample_factor) {
            this.type = type;
            this.process_type = process_type;
            this.force_suffix = force_suffix;
            this.suffix_offset = suffix_offset;
            this.save_base = save_base;
            this.jpeg_images = jpeg_images;
            this.image_capture_intent = image_capture_intent;
            this.image_capture_intent_uri = image_capture_intent_uri;
            this.using_camera2 = using_camera2;
            this.image_format = image_format;
            this.image_quality = image_quality;
            this.do_auto_stabilise = do_auto_stabilise;
            this.level_angle = level_angle;
            this.gyro_rotation_matrix = gyro_rotation_matrix;
            this.is_front_facing = is_front_facing;
            this.mirror = mirror;
            this.current_date = current_date;
            this.iso = iso;
            this.exposure_time = exposure_time;
            this.zoom_factor = zoom_factor;
            this.preference_stamp = preference_stamp;
            this.preference_textstamp = preference_textstamp;
            this.pitch_angle = pitch_angle;
            this.store_ypr = store_ypr;
            this.sample_factor = sample_factor;
        }

    }

    ImageSaver(MainActivity main_activity) {
        if( MyDebug.LOG )
            Log.d(TAG, "ImageSaver");
        this.main_activity = main_activity;

        ActivityManager activityManager = (ActivityManager) main_activity.getSystemService(Activity.ACTIVITY_SERVICE);
        this.queue_capacity = computeQueueSize(activityManager.getLargeMemoryClass());
        this.queue = new ArrayBlockingQueue<>(queue_capacity); // since we remove from the queue and then process in the saver thread, in practice the number of background photos - including the one being processed - is one more than the length of this queue


        p.setAntiAlias(true);
    }

    /** Returns the length of the image saver queue. In practice, the number of images that can be taken at once before the UI
     *  blocks is 1 more than this, as 1 image will be taken off the queue to process straight away.
     */
    public int getQueueSize() {
        return this.queue_capacity;
    }

    /** Compute a sensible size for the queue, based on the device's memory (large heap).
     */
    public static int computeQueueSize(int large_heap_memory) {
        if( MyDebug.LOG )
            Log.d(TAG, "large max memory = " + large_heap_memory + "MB");
        int max_queue_size;
        if( MyDebug.LOG )
            Log.d(TAG, "test_small_queue_size?: " + test_small_queue_size);
        if( test_small_queue_size ) {
            large_heap_memory = 0;
        }

        if( large_heap_memory >= 512 ) {
            // This should be at least 5*(queue_cost_jpeg_c+queue_cost_dng_c)-1 so we can take a burst of 5 photos
            // (e.g., in expo mode) with RAW+JPEG without blocking (we subtract 1, as the first image can be immediately
            // taken off the queue).
            // This should also be at least 19 so we can take a burst of 20 photos with JPEG without blocking (we subtract 1,
            // as the first image can be immediately taken off the queue).
            // This should be at most 70 for large heap 512MB (estimate based on reserving 160MB for post-processing and HDR
            // operations, then estimate a JPEG image at 5MB).
            max_queue_size = 34;
        }
        else if( large_heap_memory >= 256 ) {
            // This should be at most 19 for large heap 256MB.
            max_queue_size = 12;
        }
        else if( large_heap_memory >= 128 ) {
            // This should be at least 1*(queue_cost_jpeg_c+queue_cost_dng_c)-1 so we can take a photo with RAW+JPEG
            // without blocking (we subtract 1, as the first image can be immediately taken off the queue).
            // This should be at most 8 for large heap 128MB (allowing 80MB for post-processing).
            max_queue_size = 8;
        }
        else {
            // This should be at least 1*(queue_cost_jpeg_c+queue_cost_dng_c)-1 so we can take a photo with RAW+JPEG
            // without blocking (we subtract 1, as the first image can be immediately taken off the queue).
            max_queue_size = 6;
        }
        //max_queue_size = 1;
        //max_queue_size = 3;
        if( MyDebug.LOG )
            Log.d(TAG, "max_queue_size = " + max_queue_size);
        return max_queue_size;
    }

    /** Computes the cost for a particular request.
     *  Note that for RAW+DNG mode, computeRequestCost() is called twice for a given photo (one for each
     *  of the two requests: one RAW, one JPEG).
     * @param is_raw Whether RAW/DNG or JPEG.
     * @param n_images This is the number of JPEG or RAW images that are in the request.
     */
    public static int computeRequestCost(boolean is_raw, int n_images) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "computeRequestCost");
            Log.d(TAG, "is_raw: " + is_raw);
            Log.d(TAG, "n_images: " + n_images);
        }
        int cost;
        if( is_raw )
            cost = n_images * queue_cost_dng_c;
        else {
            cost = n_images * queue_cost_jpeg_c;
            //cost = (n_images > 1 ? 2 : 1) * queue_cost_jpeg_c;
        }
        return cost;
    }

    /** Computes the cost (in terms of number of slots on the image queue) of a new photo.
     * @param n_raw The number of JPEGs that will be taken.
     * @param n_jpegs The number of JPEGs that will be taken.
     */
    int computePhotoCost(int n_raw, int n_jpegs) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "computePhotoCost");
            Log.d(TAG, "n_raw: " + n_raw);
            Log.d(TAG, "n_jpegs: " + n_jpegs);
        }
        int cost = 0;
        if( n_raw > 0 )
            cost += computeRequestCost(true, n_raw);
        if( n_jpegs > 0 )
            cost += computeRequestCost(false, n_jpegs);
        if( MyDebug.LOG )
            Log.d(TAG, "cost: " + cost);
        return cost;
    }

    /** Whether taking an extra photo would overflow the queue, resulting in the UI hanging.
     * @param n_raw The number of JPEGs that will be taken.
     * @param n_jpegs The number of JPEGs that will be taken.
     */
    boolean queueWouldBlock(int n_raw, int n_jpegs) {
        int photo_cost = this.computePhotoCost(n_raw, n_jpegs);
        return this.queueWouldBlock(photo_cost);
    }

    /** Whether taking an extra photo would overflow the queue, resulting in the UI hanging.
     * @param photo_cost The result returned by computePhotoCost().
     */
    synchronized boolean queueWouldBlock(int photo_cost) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "queueWouldBlock");
            Log.d(TAG, "photo_cost: " + photo_cost);
            Log.d(TAG, "n_images_to_save: " + n_images_to_save);
            Log.d(TAG, "queue_capacity: " + queue_capacity);
        }
        // we add one to queue, to account for the image currently being processed; n_images_to_save includes an image
        // currently being processed
        if( n_images_to_save == 0 ) {
            // In theory, we should never have the extra_cost large enough to block the queue even when no images are being
            // saved - but we have this just in case. This means taking the photo will likely block the UI, but we don't want
            // to disallow ever taking photos!
            if( MyDebug.LOG )
                Log.d(TAG, "queue is empty");
            return false;
        }
        else if( n_images_to_save + photo_cost > queue_capacity + 1 ) {
            if( MyDebug.LOG )
                Log.d(TAG, "queue would block");
            return true;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "queue would not block");
        return false;
    }

    /** Returns the maximum number of DNG images that might be held by the image saver queue at once, before blocking.
     */
    int getMaxDNG() {
        int max_dng = (queue_capacity+1)/queue_cost_dng_c;
        max_dng++; // increase by 1, as the user can still take one extra photo if the queue is exactly full
        if( MyDebug.LOG )
            Log.d(TAG, "max_dng = " + max_dng);
        return max_dng;
    }

    /** Returns the number of images to save, weighted by their cost (e.g., so a single RAW image
     *  will be counted as multiple images).
     */
    public synchronized int getNImagesToSave() {
        return n_images_to_save;
    }

    /** Returns the number of images to save (e.g., so a single RAW image will only be counted as
     *  one image, unlike getNImagesToSave()).

     */
    public synchronized int getNRealImagesToSave() {
        return n_real_images_to_save;
    }

    void onDestroy() {
        if( MyDebug.LOG )
            Log.d(TAG, "onDestroy");
    }

    @Override
    public void run() {
        if( MyDebug.LOG )
            Log.d(TAG, "starting ImageSaver thread...");
        while( true ) {
            try {
                if( MyDebug.LOG )
                    Log.d(TAG, "ImageSaver thread reading from queue, size: " + queue.size());
                Request request = queue.take(); // if empty, take() blocks until non-empty
                // Only decrement n_images_to_save after we've actually saved the image! Otherwise waitUntilDone() will return
                // even though we still have a last image to be saved.
                if( MyDebug.LOG )
                    Log.d(TAG, "ImageSaver thread found new request from queue, size is now: " + queue.size());
                boolean success;
                switch (request.type) {
                    case JPEG:
                        if (MyDebug.LOG)
                            Log.d(TAG, "request is jpeg");
                        success = saveImageNow(request);
                        break;

                    default:
                        if (MyDebug.LOG)
                            Log.e(TAG, "request is unknown type!");
                        success = false;
                        break;
                }
                if( test_slow_saving ) {
                    Thread.sleep(2000);
                }
                if( MyDebug.LOG ) {
                    if( success )
                        Log.d(TAG, "ImageSaver thread successfully saved image");
                    else
                        Log.e(TAG, "ImageSaver thread failed to save image");
                }
                synchronized( this ) {
                    n_images_to_save--;
                    if( MyDebug.LOG )
                        Log.d(TAG, "ImageSaver thread processed new request from queue, images to save is now: " + n_images_to_save);
                    if( MyDebug.LOG && n_images_to_save < 0 ) {
                        Log.e(TAG, "images to save has become negative");
                        throw new RuntimeException();
                    }
                    else if( MyDebug.LOG && n_real_images_to_save < 0 ) {
                        Log.e(TAG, "real images to save has become negative");
                        throw new RuntimeException();
                    }
                    notifyAll();

                    main_activity.runOnUiThread(new Runnable() {
                        public void run() {
                            main_activity.imageQueueChanged();
                        }
                    });
                }
            }
            catch(InterruptedException e) {
                e.printStackTrace();
                if( MyDebug.LOG )
                    Log.e(TAG, "interrupted while trying to read from ImageSaver queue");
            }
        }
    }

    /** Saves a photo.
     *  If do_in_background is true, the photo will be saved in a background thread. If the queue is full, the function will wait
     *  until it isn't full. Otherwise it will return immediately. The function always returns true for background saving.
     *  If do_in_background is false, the photo is saved on the current thread, and the function returns whether the photo was saved
     *  successfully.
     */
    boolean saveImageJpeg(boolean do_in_background,
                          boolean is_hdr,
                          boolean force_suffix,
                          int suffix_offset,
                          boolean save_expo,
                          List<byte []> images,
                          boolean image_capture_intent, Uri image_capture_intent_uri,
                          boolean using_camera2,// TODO alltid true, slett
                          Request.ImageFormat image_format, int image_quality,
                          boolean do_auto_stabilise, double level_angle,
                          boolean is_front_facing,
                          boolean mirror,
                          Date current_date,
                          int iso,
                          long exposure_time,
                          float zoom_factor,
                          double pitch_angle, boolean store_ypr,
                          int sample_factor) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "saveImageJpeg");
            Log.d(TAG, "do_in_background? " + do_in_background);
            Log.d(TAG, "number of images: " + images.size());
        }
        return saveImage(do_in_background,
                false,
                is_hdr,
                force_suffix,
                suffix_offset,
                save_expo,
                images,
                image_capture_intent, image_capture_intent_uri,
                using_camera2,
                image_format, image_quality,
                do_auto_stabilise, level_angle,
                is_front_facing,
                mirror,
                current_date,
                iso,
                exposure_time,
                zoom_factor,
                pitch_angle, store_ypr,
                sample_factor);
    }




    /** Internal saveImage method to handle both JPEG and RAW.
     */
    private boolean saveImage(boolean do_in_background,
                              boolean is_raw,
                              boolean is_hdr,
                              boolean force_suffix,
                              int suffix_offset,
                              boolean save_expo,
                              List<byte []> jpeg_images,
                              boolean image_capture_intent, Uri image_capture_intent_uri,
                              boolean using_camera2,
                              Request.ImageFormat image_format, int image_quality,
                              boolean do_auto_stabilise, double level_angle,
                              boolean is_front_facing,
                              boolean mirror,
                              Date current_date,
                              int iso,
                              long exposure_time,
                              float zoom_factor,
                              double pitch_angle, boolean store_ypr,
                              int sample_factor) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "saveImage");
            Log.d(TAG, "do_in_background? " + do_in_background);
        }
        boolean success;

        //do_in_background = false;

        Request request = new Request( Request.Type.JPEG,
                 Request.ProcessType.NORMAL,
                force_suffix,
                suffix_offset,
                save_expo ? Request.SaveBase.SAVEBASE_ALL : Request.SaveBase.SAVEBASE_NONE,
                jpeg_images,
                image_capture_intent, image_capture_intent_uri,
                using_camera2,
                image_format, image_quality,
                do_auto_stabilise, level_angle, null,
                is_front_facing,
                mirror,
                current_date,
                iso,
                exposure_time,
                zoom_factor,
                pitch_angle, store_ypr,
                sample_factor);

        if( do_in_background ) {
            if( MyDebug.LOG )
                Log.d(TAG, "add background request");
            int cost = computeRequestCost(is_raw, is_raw ? 1 : request.jpeg_images.size());
            addRequest(request, cost);
            success = true; // always return true when done in background
        }
        else {
            // wait for queue to be empty
            waitUntilDone();


                success = saveImageNow(request);

        }

        if( MyDebug.LOG )
            Log.d(TAG, "success: " + success);
        return success;
    }

    /** Adds a request to the background queue, blocking if the queue is already full
     */
    private void addRequest(Request request, int cost) {
        if( MyDebug.LOG )
            Log.d(TAG, "addRequest, cost: " + cost);
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && main_activity.isDestroyed() ) {
            // If the application is being destroyed as a new photo is being taken, it's not safe to continue, e.g., we'll
            // crash if needing to use RenderScript.
            // MainDestroy.onDestroy() does call waitUntilDone(), but this is extra protection in case an image comes in after that.
            Log.e(TAG, "application is destroyed, image lost!");
            return;
        }
        // this should not be synchronized on "this": BlockingQueue is thread safe, and if it's blocking in queue.put(), we'll hang because
        // the saver queue will need to synchronize on "this" in order to notifyAll() the main thread
        boolean done = false;
        while( !done ) {
            try {
                if( MyDebug.LOG )
                    Log.d(TAG, "ImageSaver thread adding to queue, size: " + queue.size());
                synchronized( this ) {
                    // see above for why we don't synchronize the queue.put call
                    // but we synchronize modification to avoid risk of problems related to compiler optimisation (local caching or reordering)
                    // also see FindBugs warning due to inconsistent synchronisation
                    n_images_to_save++; // increment before adding to the queue, just to make sure the main thread doesn't think we're all done

                    main_activity.runOnUiThread(new Runnable() {
                        public void run() {
                            main_activity.imageQueueChanged();
                        }
                    });
                }
                if( queue.size() + 1 > queue_capacity ) {
                    Log.e(TAG, "ImageSaver thread is going to block, queue already full: " + queue.size());
                    test_queue_blocked = true;
                    //throw new RuntimeException(); // test
                }
                queue.put(request); // if queue is full, put() blocks until it isn't full
                if( MyDebug.LOG ) {
                    synchronized( this ) { // keep FindBugs happy
                        Log.d(TAG, "ImageSaver thread added to queue, size is now: " + queue.size());
                        Log.d(TAG, "images still to save is now: " + n_images_to_save);
                        Log.d(TAG, "real images still to save is now: " + n_real_images_to_save);
                    }
                }
                done = true;
            }
            catch(InterruptedException e) {
                e.printStackTrace();
                if( MyDebug.LOG )
                    Log.e(TAG, "interrupted while trying to add to ImageSaver queue");
            }
        }
        if( cost > 0 ) {
            // add "dummy" requests to simulate the cost
            for(int i=0;i<cost-1;i++) {
//                addDummyRequest();
            }
        }
    }



    /** Wait until the queue is empty and all pending images have been saved.
     */
    void waitUntilDone() {
        if( MyDebug.LOG )
            Log.d(TAG, "waitUntilDone");
        synchronized( this ) {
            if( MyDebug.LOG ) {
                Log.d(TAG, "waitUntilDone: queue is size " + queue.size());
                Log.d(TAG, "waitUntilDone: images still to save " + n_images_to_save);
            }
            while( n_images_to_save > 0 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "wait until done...");
                try {
                    wait();
                }
                catch(InterruptedException e) {
                    e.printStackTrace();
                    if( MyDebug.LOG )
                        Log.e(TAG, "interrupted while waiting for ImageSaver queue to be empty");
                }
                if( MyDebug.LOG ) {
                    Log.d(TAG, "waitUntilDone: queue is size " + queue.size());
                    Log.d(TAG, "waitUntilDone: images still to save " + n_images_to_save);
                }
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "waitUntilDone: images all saved");
    }

    private void setBitmapOptionsSampleSize(BitmapFactory.Options options, int inSampleSize) {
        if( MyDebug.LOG )
            Log.d(TAG, "setBitmapOptionsSampleSize: " + inSampleSize);
        //options.inSampleSize = inSampleSize;
        if( inSampleSize > 1 ) {
            // use inDensity for better quality, as inSampleSize uses nearest neighbour
            options.inDensity = inSampleSize;
            options.inTargetDensity = 1;
        }
    }

    /** Loads a single jpeg as a Bitmaps.
     * @param mutable Whether the bitmap should be mutable. Note that when converting to bitmaps
     *                for the image post-processing (auto-stabilise etc), in general we need the
     *                bitmap to be mutable (for photostamp to work).
     */
    private Bitmap loadBitmap(byte [] jpeg_image, boolean mutable, int inSampleSize) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "loadBitmap");
            Log.d(TAG, "mutable?: " + mutable);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        if( MyDebug.LOG )
            Log.d(TAG, "options.inMutable is: " + options.inMutable);
        options.inMutable = mutable;
        setBitmapOptionsSampleSize(options, inSampleSize);
        if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
            // setting is ignored in Android 5 onwards
            options.inPurgeable = true;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg_image, 0, jpeg_image.length, options);
        if( bitmap == null ) {
            Log.e(TAG, "failed to decode bitmap");
        }
        return bitmap;
    }

    /** Helper class for loadBitmaps().
     */
    private static class LoadBitmapThread extends Thread {
        Bitmap bitmap;
        final BitmapFactory.Options options;
        final byte [] jpeg;
        LoadBitmapThread(BitmapFactory.Options options, byte [] jpeg) {
            this.options = options;
            this.jpeg = jpeg;
        }

        public void run() {
            this.bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        }
    }

    /** Converts the array of jpegs to Bitmaps. The bitmap with index mutable_id will be marked as mutable (or set to -1 to have no mutable bitmaps).
     */
    private List<Bitmap> loadBitmaps(List<byte []> jpeg_images, int mutable_id, int inSampleSize) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "loadBitmaps");
            Log.d(TAG, "mutable_id: " + mutable_id);
        }
        BitmapFactory.Options mutable_options = new BitmapFactory.Options();
        mutable_options.inMutable = true; // bitmap that needs to be writable
        setBitmapOptionsSampleSize(mutable_options, inSampleSize);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = false; // later bitmaps don't need to be writable
        setBitmapOptionsSampleSize(options, inSampleSize);
        if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
            // setting is ignored in Android 5 onwards
            mutable_options.inPurgeable = true;
            options.inPurgeable = true;
        }
        LoadBitmapThread [] threads = new LoadBitmapThread[jpeg_images.size()];
        for(int i=0;i<jpeg_images.size();i++) {
            threads[i] = new LoadBitmapThread( i==mutable_id ? mutable_options : options, jpeg_images.get(i) );
        }
        // start threads
        if( MyDebug.LOG )
            Log.d(TAG, "start threads");
        for(int i=0;i<jpeg_images.size();i++) {
            threads[i].start();
        }
        // wait for threads to complete
        boolean ok = true;
        if( MyDebug.LOG )
            Log.d(TAG, "wait for threads to complete");
        try {
            for(int i=0;i<jpeg_images.size();i++) {
                threads[i].join();
            }
        }
        catch(InterruptedException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "threads interrupted");
            e.printStackTrace();
            ok = false;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "threads completed");

        List<Bitmap> bitmaps = new ArrayList<>();
        for(int i=0;i<jpeg_images.size() && ok;i++) {
            Bitmap bitmap = threads[i].bitmap;
            if( bitmap == null ) {
                Log.e(TAG, "failed to decode bitmap in thread: " + i);
                ok = false;
            }
            else {
                if( MyDebug.LOG )
                    Log.d(TAG, "bitmap " + i + ": " + bitmap + " is mutable? " + bitmap.isMutable());
            }
            bitmaps.add(bitmap);
        }

        if( !ok ) {
            if( MyDebug.LOG )
                Log.d(TAG, "cleanup from failure");
            for(int i=0;i<jpeg_images.size();i++) {
                if( threads[i].bitmap != null ) {
                    threads[i].bitmap.recycle();
                    threads[i].bitmap = null;
                }
            }
            bitmaps.clear();
            System.gc();
            return null;
        }

        return bitmaps;
    }

    /** Chooses the hdr_alpha to use for contrast enhancement in the HDR algorithm, based on the user
     *  preferences and scene details.
     */
    public static float getHDRAlpha(String preference_hdr_contrast_enhancement, long exposure_time, int n_bitmaps) {
        boolean use_hdr_alpha;
        if( n_bitmaps == 1 ) {
            // DRO always applies hdr_alpha
            use_hdr_alpha = true;
        }
        else {
            // else HDR
            switch( preference_hdr_contrast_enhancement ) {
                case "preference_hdr_contrast_enhancement_off":
                    use_hdr_alpha = false;
                    break;
                case "preference_hdr_contrast_enhancement_smart":
                default:
                    // Using local contrast enhancement helps scenes where the dynamic range is very large, which tends to be when we choose
                    // a short exposure time, due to fixing problems where some regions are too dark.
                    // This helps: testHDR11, testHDR19, testHDR34, testHDR53.
                    // Using local contrast enhancement in all cases can increase noise in darker scenes. This problem would occur
                    // (if we used local contrast enhancement) is: testHDR2, testHDR12, testHDR17, testHDR43, testHDR50, testHDR51,
                    // testHDR54, testHDR55, testHDR56.
                    use_hdr_alpha = (exposure_time < 1000000000L/59);
                    break;
                case "preference_hdr_contrast_enhancement_always":
                    use_hdr_alpha = true;
                    break;
            }
        }
        //use_hdr_alpha = true; // test
        float hdr_alpha = use_hdr_alpha ? 0.5f : 0.0f;
        if( MyDebug.LOG ) {
            Log.d(TAG, "preference_hdr_contrast_enhancement: " + preference_hdr_contrast_enhancement);
            Log.d(TAG, "exposure_time: " + exposure_time);
            Log.d(TAG, "hdr_alpha: " + hdr_alpha);
        }
        return hdr_alpha;
    }

    private final static String gyro_info_doc_tag = "open_camera_gyro_info";
    private final static String gyro_info_panorama_pics_per_screen_tag = "panorama_pics_per_screen";
    private final static String gyro_info_camera_view_angle_x_tag = "camera_view_angle_x";
    private final static String gyro_info_camera_view_angle_y_tag = "camera_view_angle_y";
    private final static String gyro_info_image_tag = "image";
    private final static String gyro_info_vector_tag = "vector";
    private final static String gyro_info_vector_right_type = "X";
    private final static String gyro_info_vector_up_type = "Y";
    private final static String gyro_info_vector_screen_type = "Z";


    @SuppressWarnings("WeakerAccess")
    public static class GyroDebugInfo {
        @SuppressWarnings("unused")
        public static class GyroImageDebugInfo {
            public float [] vectorRight; // X axis
            public float [] vectorUp; // Y axis
            public float [] vectorScreen; // vector into the screen - actually the -Z axis
        }

        public final List<GyroImageDebugInfo> image_info;

        public GyroDebugInfo() {
            image_info = new ArrayList<>();
        }
    }

    public static boolean readGyroDebugXml(InputStream inputStream, GyroDebugInfo info) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(inputStream, null);
            parser.nextTag();

            parser.require(XmlPullParser.START_TAG, null, gyro_info_doc_tag);
            GyroDebugInfo.GyroImageDebugInfo image_info = null;

            while( parser.next() != XmlPullParser.END_DOCUMENT ) {
                switch( parser.getEventType() ) {
                    case XmlPullParser.START_TAG: {
                        String name = parser.getName();
                        if( MyDebug.LOG ) {
                            Log.d(TAG, "start tag, name: " + name);
                        }

                        switch( name ) {
                            case gyro_info_image_tag:
                                info.image_info.add( image_info = new GyroDebugInfo.GyroImageDebugInfo() );
                                break;
                            case gyro_info_vector_tag:
                                if( image_info == null ) {
                                    Log.e(TAG, "vector tag outside of image tag");
                                    return false;
                                }
                                String type = parser.getAttributeValue(null, "type");
                                String x_s = parser.getAttributeValue(null, "x");
                                String y_s = parser.getAttributeValue(null, "y");
                                String z_s = parser.getAttributeValue(null, "z");
                                float [] vector = new float[3];
                                vector[0] = Float.parseFloat(x_s);
                                vector[1] = Float.parseFloat(y_s);
                                vector[2] = Float.parseFloat(z_s);
                                switch( type ) {
                                    case gyro_info_vector_right_type:
                                        image_info.vectorRight = vector;
                                        break;
                                    case gyro_info_vector_up_type:
                                        image_info.vectorUp = vector;
                                        break;
                                    case gyro_info_vector_screen_type:
                                        image_info.vectorScreen = vector;
                                        break;
                                    default:
                                        Log.e(TAG, "unknown type in vector tag: " + type);
                                        return false;
                                }
                                break;
                        }
                        break;
                    }
                    case XmlPullParser.END_TAG: {
                        String name = parser.getName();
                        if( MyDebug.LOG ) {
                            Log.d(TAG, "end tag, name: " + name);
                        }

                        //noinspection SwitchStatementWithTooFewBranches
                        switch( name ) {
                            case gyro_info_image_tag:
                                image_info = null;
                                break;
                        }
                        break;
                    }
                }
            }
        }
        catch(Exception e) {
            e.printStackTrace();
            return false;
        }
        finally {
            try {
                inputStream.close();
            }
            catch(IOException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    /** May be run in saver thread or picture callback thread (depending on whether running in background).
     */
    private boolean saveImageNow(final Request request) {
        if( MyDebug.LOG )
            Log.d(TAG, "saveImageNow");

        if( request.type != Request.Type.JPEG ) {
            if( MyDebug.LOG )
                Log.d(TAG, "saveImageNow called with non-jpeg request");
            // throw runtime exception, as this is a programming error
            throw new RuntimeException();
        }
        else if( request.jpeg_images.size() == 0 ) {
            if( MyDebug.LOG )
                Log.d(TAG, "saveImageNow called with zero images");
            // throw runtime exception, as this is a programming error
            throw new RuntimeException();
        }

        boolean success;


            // see note above how we used to use "_EXP" for the suffix for multiple images
            //String suffix = "_EXP";
            String suffix = "_";
            success = saveImages(request, suffix, false, true, true);


        return success;
    }

    /** Saves all the JPEG images in request.jpeg_images.
     * @param request The request to save.
     * @param suffix If there is more than one image and first_only is false, the i-th image
     *               filename will be appended with (suffix+i).
     * @param first_only If true, only save the first image.
     * @param update_thumbnail Whether to update the thumbnail and show the animation.
     * @param share If true, the median image will be marked as the one to share (for pause preview
     *              option).
     * @return Whether all images were successfully saved.
     */
    private boolean saveImages(Request request, String suffix, boolean first_only, boolean update_thumbnail, boolean share) {
        boolean success = true;
        int mid_image = request.jpeg_images.size()/2;
        for(int i=0;i<request.jpeg_images.size();i++) {
            // note, even if one image fails, we still try saving the other images - might as well give the user as many images as we can...
            byte [] image = request.jpeg_images.get(i);
            boolean multiple_jpegs = request.jpeg_images.size() > 1 && !first_only;
            String filename_suffix = (multiple_jpegs || request.force_suffix) ? suffix + (i + request.suffix_offset) : "";
            boolean share_image = share && (i == mid_image);
            if( !saveSingleImageNow(request, image, null, filename_suffix, update_thumbnail, share_image, false, false) ) {
                if( MyDebug.LOG )
                    Log.e(TAG, "saveSingleImageNow failed for image: " + i);
                success = false;
            }
            if( first_only )
                break; // only requested the first
        }
        return success;
    }

    /** Saves all the images in request.jpeg_images, depending on the save_base option.
     */
    private void saveBaseImages(Request request, String suffix) {
        if( MyDebug.LOG )
            Log.d(TAG, "saveBaseImages");
        if( !request.image_capture_intent && request.save_base != Request.SaveBase.SAVEBASE_NONE ) {
            if( MyDebug.LOG )
                Log.d(TAG, "save base images");

            Request base_request = request;

            // don't update the thumbnails, only do this for the final image - so user doesn't think it's complete, click gallery, then wonder why the final image isn't there
            // also don't mark these images as being shared
            saveImages(base_request, suffix, base_request.save_base == Request.SaveBase.SAVEBASE_FIRST, false, false);
            // ignore return of saveImages - as for deciding whether to pause preview or not (which is all we use the success return for), all that matters is whether we saved the final HDR image
        }
    }

    /** Computes the width and height of a centred crop region after having rotated an image.
     * @param result - Array of length 2 which will be filled with the returned width and height.
     * @param level_angle_rad_abs - Absolute value of angle of rotation, in radians.
     * @param w0 - Rotated width.
     * @param h0 - Rotated height.
     * @param w1 - Original width.
     * @param h1 - Original height.
     * @param max_width - Maximum width to return.
     * @param max_height - Maximum height to return.
     * @return - Whether a crop region could be successfully calculated.
     */
    public static boolean autoStabiliseCrop(int [] result, double level_angle_rad_abs, double w0, double h0, int w1, int h1, int max_width, int max_height) {
        boolean ok = false;
        result[0] = 0;
        result[1] = 0;

        double tan_theta = Math.tan(level_angle_rad_abs);
        double sin_theta = Math.sin(level_angle_rad_abs);
        double denom = ( h0/w0 + tan_theta );
        double alt_denom = ( w0/h0 + tan_theta );
        if( denom == 0.0 || denom < 1.0e-14 ) {
            if( MyDebug.LOG )
                Log.d(TAG, "zero denominator?!");
        }
        else if( alt_denom == 0.0 || alt_denom < 1.0e-14 ) {
            if( MyDebug.LOG )
                Log.d(TAG, "zero alt denominator?!");
        }
        else {
            int w2 = (int)(( h0 + 2.0*h1*sin_theta*tan_theta - w0*tan_theta ) / denom);
            int h2 = (int)(w2*h0/w0);
            int alt_h2 = (int)(( w0 + 2.0*w1*sin_theta*tan_theta - h0*tan_theta ) / alt_denom);
            int alt_w2 = (int)(alt_h2*w0/h0);
            if( MyDebug.LOG ) {
                //Log.d(TAG, "h0 " + h0 + " 2.0*h1*sin_theta*tan_theta " + 2.0*h1*sin_theta*tan_theta + " w0*tan_theta " + w0*tan_theta + " / h0/w0 " + h0/w0 + " tan_theta " + tan_theta);
                Log.d(TAG, "w2 = " + w2 + " , h2 = " + h2);
                Log.d(TAG, "alt_w2 = " + alt_w2 + " , alt_h2 = " + alt_h2);
            }
            if( alt_w2 < w2 ) {
                if( MyDebug.LOG ) {
                    Log.d(TAG, "chose alt!");
                }
                w2 = alt_w2;
                h2 = alt_h2;
            }
            if( w2 <= 0 )
                w2 = 1;
            else if( w2 > max_width )
                w2 = max_width;
            if( h2 <= 0 )
                h2 = 1;
            else if( h2 > max_height )
                h2 = max_height;

            ok = true;
            result[0] = w2;
            result[1] = h2;
        }
        return ok;
    }

    /** Performs the auto-stabilise algorithm on the image.
     * @param data The jpeg data.
     * @param bitmap Optional argument - the bitmap if already unpacked from the jpeg data.
     * @param level_angle The angle in degrees to rotate the image.
     * @param is_front_facing Whether the camera is front-facing.
     * @return A bitmap representing the auto-stabilised jpeg.
     */
    private Bitmap autoStabilise(byte [] data, Bitmap bitmap, double level_angle, boolean is_front_facing) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "autoStabilise");
            Log.d(TAG, "level_angle: " + level_angle);
            Log.d(TAG, "is_front_facing: " + is_front_facing);
        }
        while( level_angle < -90 )
            level_angle += 180;
        while( level_angle > 90 )
            level_angle -= 180;
        if( MyDebug.LOG )
            Log.d(TAG, "auto stabilising... angle: " + level_angle);
        if( bitmap == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "need to decode bitmap to auto-stabilise");
            // bitmap doesn't need to be mutable here, as this won't be the final bitmap returned from the auto-stabilise code
            bitmap = loadBitmapWithRotation(data, false);
            if( bitmap == null ) {
                main_activity.getPreview().showToast(null, R.string.failed_to_auto_stabilise);
                System.gc();
            }
        }
        if( bitmap != null ) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if( MyDebug.LOG ) {
                Log.d(TAG, "level_angle: " + level_angle);
                Log.d(TAG, "decoded bitmap size " + width + ", " + height);
                Log.d(TAG, "bitmap size: " + width*height*4);
            }
    			/*for(int y=0;y<height;y++) {
    				for(int x=0;x<width;x++) {
    					int col = bitmap.getPixel(x, y);
    					col = col & 0xffff0000; // mask out red component
    					bitmap.setPixel(x, y, col);
    				}
    			}*/
            Matrix matrix = new Matrix();
            double level_angle_rad_abs = Math.abs( Math.toRadians(level_angle) );
            int w1 = width, h1 = height;
            double w0 = (w1 * Math.cos(level_angle_rad_abs) + h1 * Math.sin(level_angle_rad_abs));
            double h0 = (w1 * Math.sin(level_angle_rad_abs) + h1 * Math.cos(level_angle_rad_abs));
            // apply a scale so that the overall image size isn't increased
            float orig_size = w1*h1;
            float rotated_size = (float)(w0*h0);
            float scale = (float)Math.sqrt(orig_size/rotated_size);
            if( main_activity.test_low_memory ) {
                if( MyDebug.LOG ) {
                    Log.d(TAG, "TESTING LOW MEMORY");
                    Log.d(TAG, "scale was: " + scale);
                }
                // test 20MP on Galaxy Nexus or Nexus 7; 29MP on Nexus 6 and 36MP OnePlus 3T
                if( width*height >= 7500 )
                    scale *= 1.5f;
                else
                    scale *= 2.0f;
            }
            if( MyDebug.LOG ) {
                Log.d(TAG, "w0 = " + w0 + " , h0 = " + h0);
                Log.d(TAG, "w1 = " + w1 + " , h1 = " + h1);
                Log.d(TAG, "scale = sqrt " + orig_size + " / " + rotated_size + " = " + scale);
            }
            matrix.postScale(scale, scale);
            w0 *= scale;
            h0 *= scale;
            w1 *= scale;
            h1 *= scale;
            if( MyDebug.LOG ) {
                Log.d(TAG, "after scaling: w0 = " + w0 + " , h0 = " + h0);
                Log.d(TAG, "after scaling: w1 = " + w1 + " , h1 = " + h1);
            }
            if( is_front_facing ) {
                matrix.postRotate((float)-level_angle);
            }
            else {
                matrix.postRotate((float)level_angle);
            }
            Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            // careful, as new_bitmap is sometimes not a copy!
            if( new_bitmap != bitmap ) {
                bitmap.recycle();
                bitmap = new_bitmap;
            }
            System.gc();
            if( MyDebug.LOG ) {
                Log.d(TAG, "rotated and scaled bitmap size " + bitmap.getWidth() + ", " + bitmap.getHeight());
                Log.d(TAG, "rotated and scaled bitmap size: " + bitmap.getWidth()*bitmap.getHeight()*4);
            }

            int [] crop = new int [2];
            if( autoStabiliseCrop(crop, level_angle_rad_abs, w0, h0, w1, h1, bitmap.getWidth(), bitmap.getHeight()) ) {
                int w2 = crop[0];
                int h2 = crop[1];
                int x0 = (bitmap.getWidth()-w2)/2;
                int y0 = (bitmap.getHeight()-h2)/2;
                if( MyDebug.LOG ) {
                    Log.d(TAG, "x0 = " + x0 + " , y0 = " + y0);
                }
                // We need the bitmap to be mutable for photostamp to work - contrary to the documentation for Bitmap.createBitmap
                // (which says it returns an immutable bitmap), we seem to always get a mutable bitmap anyway. A mutable bitmap
                // would result in an exception "java.lang.IllegalStateException: Immutable bitmap passed to Canvas constructor"
                // from the Canvas(bitmap) constructor call in the photostamp code, and I've yet to see this from Google Play.
                new_bitmap = Bitmap.createBitmap(bitmap, x0, y0, w2, h2);
                if( new_bitmap != bitmap ) {
                    bitmap.recycle();
                    bitmap = new_bitmap;
                }
                if( MyDebug.LOG )
                    Log.d(TAG, "bitmap is mutable?: " + bitmap.isMutable());
                System.gc();
            }
        }
        return bitmap;
    }

    /** Mirrors the image.
     * @param data The jpeg data.
     * @param bitmap Optional argument - the bitmap if already unpacked from the jpeg data.
     * @return A bitmap representing the mirrored jpeg.
     */
    private Bitmap mirrorImage(byte [] data, Bitmap bitmap) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "mirrorImage");
        }
        if( bitmap == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "need to decode bitmap to mirror");
            // bitmap doesn't need to be mutable here, as this won't be the final bitmap returned from the mirroring code
            bitmap = loadBitmapWithRotation(data, false);
            if( bitmap == null ) {
                // don't bother warning to the user - we simply won't mirror the image
                System.gc();
            }
        }
        if( bitmap != null ) {
            Matrix matrix = new Matrix();
            matrix.preScale(-1.0f, 1.0f);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            // careful, as new_bitmap is sometimes not a copy!
            if( new_bitmap != bitmap ) {
                bitmap.recycle();
                bitmap = new_bitmap;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "bitmap is mutable?: " + bitmap.isMutable());
        }
        return bitmap;
    }

    private static class PostProcessBitmapResult {
        final Bitmap bitmap;

        PostProcessBitmapResult(Bitmap bitmap) {
            this.bitmap = bitmap;
        }
    }

    /** Performs post-processing on the data, or bitmap if non-null, for saveSingleImageNow.
     */
    private PostProcessBitmapResult postProcessBitmap(final Request request, byte [] data, Bitmap bitmap, boolean ignore_exif_orientation) throws IOException {
        if( MyDebug.LOG )
            Log.d(TAG, "postProcessBitmap");
        long time_s = System.currentTimeMillis();


        if( bitmap != null || request.image_format != Request.ImageFormat.STD || request.do_auto_stabilise || request.mirror  ) {
            // either we have a bitmap, or will need to decode the bitmap to do post-processing
            if( !ignore_exif_orientation ) {
                if( bitmap != null ) {
                    // rotate the bitmap if necessary for exif tags
                    if( MyDebug.LOG )
                        Log.d(TAG, "rotate pre-existing bitmap for exif tags?");
                    bitmap = rotateForExif(bitmap, data);
                }
            }
        }
        if( request.do_auto_stabilise ) {
            bitmap = autoStabilise(data, bitmap, request.level_angle, request.is_front_facing);
        }
        if( MyDebug.LOG ) {
            Log.d(TAG, "Save single image performance: time after auto-stabilise: " + (System.currentTimeMillis() - time_s));
        }
        if( request.mirror ) {
            bitmap = mirrorImage(data, bitmap);
        }
        if( request.image_format != Request.ImageFormat.STD && bitmap == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "need to decode bitmap to convert file format");
            bitmap = loadBitmapWithRotation(data, true);
            if( bitmap == null ) {
                // if we can't load bitmap for converting file formats, don't want to continue
                System.gc();
                throw new IOException();
            }
        }
        if( MyDebug.LOG ) {
            Log.d(TAG, "Save single image performance: time after photostamp: " + (System.currentTimeMillis() - time_s));
        }
        return new PostProcessBitmapResult(bitmap);
    }

    /** May be run in saver thread or picture callback thread (depending on whether running in background).
     *  The requests.images field is ignored, instead we save the supplied data or bitmap.
     *  If bitmap is null, then the supplied jpeg data is saved. If bitmap is non-null, then the bitmap is
     *  saved, but the supplied data is still used to read EXIF data from.
     *  @param update_thumbnail - Whether to update the thumbnail (and show the animation).
     *  @param share_image - Whether this image should be marked as the one to share (if multiple images can
     *  be saved from a single shot (e.g., saving exposure images with HDR).
     *  @param ignore_raw_only - If true, then save even if RAW Only is set (needed for HDR mode
     *                         where we always save the HDR image even though it's a JPEG - the
     *                         RAW preference only affects the base images.
     * @param ignore_exif_orientation - If bitmap is non-null, then set this to true if the bitmap has already
     *                                  been rotated to account for Exif orientation tags in the data.
     */

    private boolean saveSingleImageNow(final Request request, byte [] data, Bitmap bitmap, String filename_suffix, boolean update_thumbnail, boolean share_image, boolean ignore_raw_only, boolean ignore_exif_orientation) {
        if( MyDebug.LOG )
            Log.d(TAG, "saveSingleImageNow");

        if( request.type != Request.Type.JPEG ) {
            if( MyDebug.LOG )
                Log.d(TAG, "saveImageNow called with non-jpeg request");
            // throw runtime exception, as this is a programming error
            throw new RuntimeException();
        }
        else if( data == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "saveSingleImageNow called with no data");
            // throw runtime exception, as this is a programming error
            throw new RuntimeException();
        }
        long time_s = System.currentTimeMillis();

        boolean success = false;
        final MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();

        String extension = "jpg";

        main_activity.savingImage(true);

        // If saveUri is non-null, then:
        //     Before Android 7, picFile is a temporary file which we use for saving exif tags too, and then we redirect the picFile to saveUri.
        //     On Android 7+, picFile is null - we can write the exif tags direct to the saveUri.
        final File picFile =new File(main_activity.getFilesDir() + "/" + System.currentTimeMillis() + "." + extension);
        Uri saveUri = null;
        try {
                PostProcessBitmapResult postProcessBitmapResult = postProcessBitmap(request, data, bitmap, ignore_exif_orientation);
                bitmap = postProcessBitmapResult.bitmap;

             if( request.image_capture_intent ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "image_capture_intent");
                if( request.image_capture_intent_uri != null )
                {
                    // Save the bitmap to the specified URI (use a try/catch block)
                    if( MyDebug.LOG )
                        Log.d(TAG, "save to: " + request.image_capture_intent_uri);
                    saveUri = request.image_capture_intent_uri;
                }
                else
                {
                    // If the intent doesn't contain an URI, send the bitmap as a parcel
                    // (it is a good idea to reduce its size to ~50k pixels before)
                    if( MyDebug.LOG )
                        Log.d(TAG, "sent to intent via parcel");
                    if( bitmap == null ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "create bitmap");
                        // bitmap we return doesn't need to be mutable
                        bitmap = loadBitmapWithRotation(data, false);
                    }
                    if( bitmap != null ) {
                        int width = bitmap.getWidth();
                        int height = bitmap.getHeight();
                        if( MyDebug.LOG ) {
                            Log.d(TAG, "decoded bitmap size " + width + ", " + height);
                            Log.d(TAG, "bitmap size: " + width*height*4);
                        }
                        final int small_size_c = 128;
                        if( width > small_size_c ) {
                            float scale = ((float)small_size_c)/(float)width;
                            if( MyDebug.LOG )
                                Log.d(TAG, "scale to " + scale);
                            Matrix matrix = new Matrix();
                            matrix.postScale(scale, scale);
                            Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                            // careful, as new_bitmap is sometimes not a copy!
                            if( new_bitmap != bitmap ) {
                                bitmap.recycle();
                                bitmap = new_bitmap;
                            }
                        }
                    }
                    if( MyDebug.LOG ) {
                        if( bitmap != null ) {
                            Log.d(TAG, "returned bitmap size " + bitmap.getWidth() + ", " + bitmap.getHeight());
                            Log.d(TAG, "returned bitmap size: " + bitmap.getWidth()*bitmap.getHeight()*4);
                        }
                        else {
                            Log.e(TAG, "no bitmap created");
                        }
                    }
                    if( bitmap != null )
                        main_activity.setResult(Activity.RESULT_OK, new Intent("inline-data").putExtra("data", bitmap));
                    main_activity.finish();
                }
            }

            if( MyDebug.LOG )
                Log.d(TAG, "saveUri: " + saveUri);

            if( picFile != null || saveUri != null ) {
                OutputStream outputStream;
                if( picFile != null )
                    outputStream = new FileOutputStream(picFile);
                else
                    outputStream = main_activity.getContentResolver().openOutputStream(saveUri);
                try {
                    if( bitmap != null ) {

                        Bitmap.CompressFormat compress_format = Bitmap.CompressFormat.JPEG;

                        bitmap.compress(compress_format, request.image_quality, outputStream);
                    }
                    else {
                        outputStream.write(data);
                    }
                }
                finally {
                    outputStream.close();
                }
                if( MyDebug.LOG )
                    Log.d(TAG, "saveImageNow saved photo");
                if( MyDebug.LOG ) {
                    Log.d(TAG, "Save single image performance: time after saving photo: " + (System.currentTimeMillis() - time_s));
                }

                if( saveUri == null ) { // if saveUri is non-null, then we haven't succeeded until we've copied to the saveUri
                    success = true;
                }

                if( request.image_format == Request.ImageFormat.STD ) {
                    // handle transferring/setting Exif tags (JPEG format only)
                    if( bitmap != null ) {
                        // need to update EXIF data! (only supported for JPEG image formats)
                        if( MyDebug.LOG )
                            Log.d(TAG, "set Exif tags from data");
                        if( picFile != null ) {
                            setExifFromData(request, data, picFile);
                        }
                        else {
                            ParcelFileDescriptor parcelFileDescriptor = main_activity.getContentResolver().openFileDescriptor(saveUri, "rw");
                            if( parcelFileDescriptor != null ) {
                                FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
                                setExifFromData(request, data, fileDescriptor);
                            }
                            else {
                                Log.e(TAG, "failed to create ParcelFileDescriptor for saveUri: " + saveUri);
                            }
                        }
                    }
                }

                if( picFile != null  ) {
                    // broadcast for SAF is done later, when we've actually written out the file

                    main_activity.test_last_saved_image = picFile.getAbsolutePath();
                }

                if( request.image_capture_intent ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "finish activity due to being called from intent");
                    main_activity.setResult(Activity.RESULT_OK);
                    main_activity.finish();
                }
            }
        }
        catch(FileNotFoundException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "File not found: " + e.getMessage());
            e.printStackTrace();
            main_activity.getPreview().showToast(null, R.string.failed_to_save_photo);
        }
        catch(IOException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "I/O error writing file: " + e.getMessage());
            e.printStackTrace();
            main_activity.getPreview().showToast(null, R.string.failed_to_save_photo);
        }
        catch(SecurityException e) {
            // received security exception from copyFileToUri()->openOutputStream() from Google Play
            if( MyDebug.LOG )
                Log.e(TAG, "security exception writing file: " + e.getMessage());
            e.printStackTrace();
            main_activity.getPreview().showToast(null, R.string.failed_to_save_photo);
        }


        // I have received crashes where camera_controller was null - could perhaps happen if this thread was running just as the camera is closing?
        if( success && main_activity.getPreview().getCameraController() != null && update_thumbnail ) {
            // update thumbnail - this should be done after restarting preview, so that the preview is started asap
            CameraController.Size size = main_activity.getPreview().getCameraController().getPictureSize();
            int ratio = (int) Math.ceil((double) size.width / main_activity.getPreview().getView().getWidth());
            int sample_size = Integer.highestOneBit(ratio);
            sample_size *= request.sample_factor;
            if( MyDebug.LOG ) {
                Log.d(TAG, "    picture width: " + size.width);
                Log.d(TAG, "    preview width: " + main_activity.getPreview().getView().getWidth());
                Log.d(TAG, "    ratio        : " + ratio);
                Log.d(TAG, "    sample_size  : " + sample_size);
            }
            Bitmap thumbnail;
            if( bitmap == null ) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inMutable = false;
                if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
                    // setting is ignored in Android 5 onwards
                    options.inPurgeable = true;
                }
                options.inSampleSize = sample_size;
                thumbnail = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if( MyDebug.LOG ) {
                    Log.d(TAG, "thumbnail width: " + thumbnail.getWidth());
                    Log.d(TAG, "thumbnail height: " + thumbnail.getHeight());
                }
                // now get the rotation from the Exif data
                if( MyDebug.LOG )
                    Log.d(TAG, "rotate thumbnail for exif tags?");
                thumbnail = rotateForExif(thumbnail, data);
            }
            else {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                Matrix matrix = new Matrix();
                float scale = 1.0f / (float)sample_size;
                matrix.postScale(scale, scale);
                if( MyDebug.LOG )
                    Log.d(TAG, "    scale: " + scale);
                try {
                    thumbnail = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                    if( MyDebug.LOG ) {
                        Log.d(TAG, "thumbnail width: " + thumbnail.getWidth());
                        Log.d(TAG, "thumbnail height: " + thumbnail.getHeight());
                    }
                    // don't need to rotate for exif, as we already did that when creating the bitmap
                }
                catch(IllegalArgumentException e) {
                    // received IllegalArgumentException on Google Play from Bitmap.createBitmap; documentation suggests this
                    // means width or height are 0 - but trapping that didn't fix the problem
                    // or "the x, y, width, height values are outside of the dimensions of the source bitmap", but that can't be
                    // true here
                    // crashes seem to all be Android 7.1 or earlier, so maybe this is a bug that's been fixed - but catch it anyway
                    // as it's grown popular
                    Log.e(TAG, "can't create thumbnail bitmap due to IllegalArgumentException?!");
                    e.printStackTrace();
                    thumbnail = null;
                }
            }
            if( thumbnail == null ) {
                // received crashes on Google Play suggesting that thumbnail could not be created
                if( MyDebug.LOG )
                    Log.e(TAG, "failed to create thumbnail bitmap");
            }
            else {
                final Bitmap thumbnail_f = thumbnail;
                main_activity.runOnUiThread(new Runnable() {
                    public void run() {
                        applicationInterface.updateThumbnail(thumbnail_f, false);
                    }
                });
                if( MyDebug.LOG ) {
                    Log.d(TAG, "Save single image performance: time after creating thumbnail: " + (System.currentTimeMillis() - time_s));
                }
            }
        }

        if( bitmap != null ) {
            bitmap.recycle();
        }

        if( picFile != null && saveUri != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "delete temp picFile: " + picFile);
            if( !picFile.delete() ) {
                if( MyDebug.LOG )
                    Log.e(TAG, "failed to delete temp picFile: " + picFile);
            }
        }

        System.gc();

        main_activity.savingImage(false);

        if( MyDebug.LOG ) {
            Log.d(TAG, "Save single image performance: total time: " + (System.currentTimeMillis() - time_s));
        }
        return success;
    }
    /** As setExifFromFile, but can read the Exif tags directly from the jpeg data rather than a file.
     */
    private void setExifFromData(final Request request, byte [] data, File to_file) throws IOException {
        if( MyDebug.LOG ) {
            Log.d(TAG, "setExifFromData");
            Log.d(TAG, "to_file: " + to_file);
        }
        InputStream inputStream = null;
        try {
            inputStream = new ByteArrayInputStream(data);
            ExifInterface exif = new ExifInterface(inputStream);
            ExifInterface exif_new = new ExifInterface(to_file.getAbsolutePath());
            setExif(request, exif, exif_new);
        }
        finally {
            if( inputStream != null ) {
                inputStream.close();
            }
        }
    }


    /** As setExifFromFile, but can read the Exif tags directly from the jpeg data, and to a file descriptor, rather than a file.
     */
    private void setExifFromData(final Request request, byte [] data, FileDescriptor to_file_descriptor) throws IOException {
        if( MyDebug.LOG ) {
            Log.d(TAG, "setExifFromData");
            Log.d(TAG, "to_file_descriptor: " + to_file_descriptor);
        }
        InputStream inputStream = null;
        try {
            inputStream = new ByteArrayInputStream(data);
            ExifInterface exif = new ExifInterface(inputStream);
            ExifInterface exif_new = new ExifInterface(to_file_descriptor);
            setExif(request, exif, exif_new);
        }
        finally {
            if( inputStream != null ) {
                inputStream.close();
            }
        }
    }

    /** Transfers exif tags from exif to exif_new, and then applies any extra Exif tags according to the preferences in the request.
     *  Note that we use several ExifInterface tags that are now deprecated in API level 23 and 24. These are replaced with new tags that have
     *  the same string value (e.g., TAG_APERTURE replaced with TAG_F_NUMBER, but both have value "FNumber"). We use the deprecated versions
     *  to avoid complicating the code (we'd still have to read the deprecated values for older devices).
     */
    private void setExif(final Request request, ExifInterface exif, ExifInterface exif_new) throws IOException {
        if( MyDebug.LOG )
            Log.d(TAG, "setExif");

        if( MyDebug.LOG )
            Log.d(TAG, "read back EXIF data");
        String exif_aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER); // previously TAG_APERTURE
        String exif_datetime = exif.getAttribute(ExifInterface.TAG_DATETIME);
        String exif_exposure_time = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
        String exif_flash = exif.getAttribute(ExifInterface.TAG_FLASH);
        String exif_focal_length = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
        String exif_gps_altitude = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE);
        String exif_gps_altitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF);
        String exif_gps_datestamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP);
        String exif_gps_latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
        String exif_gps_latitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
        String exif_gps_longitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
        String exif_gps_longitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
        String exif_gps_processing_method = exif.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD);
        String exif_gps_timestamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP);
        // leave width/height, as this may have changed! similarly TAG_IMAGE_LENGTH?
        //noinspection deprecation
        String exif_iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS); // previously TAG_ISO
        String exif_make = exif.getAttribute(ExifInterface.TAG_MAKE);
        String exif_model = exif.getAttribute(ExifInterface.TAG_MODEL);
        // leave orientation - since we rotate bitmaps to account for orientation, we don't want to write it to the saved image!
        String exif_white_balance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE);

        String exif_datetime_digitized;
        String exif_subsec_time;
        String exif_subsec_time_dig;
        String exif_subsec_time_orig;
        {
            // tags that are new in Android M - note we skip tags unlikely to be relevant for camera photos
            // update, now available in all Android versions thanks to using AndroidX ExifInterface
            exif_datetime_digitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED);
            exif_subsec_time = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME);
            exif_subsec_time_dig = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED); // previously TAG_SUBSEC_TIME_DIG
            exif_subsec_time_orig = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL); // previously TAG_SUBSEC_TIME_ORIG
        }

        String exif_aperture_value;
        String exif_brightness_value;
        String exif_cfa_pattern;
        String exif_color_space;
        String exif_components_configuration;
        String exif_compressed_bits_per_pixel;
        String exif_compression;
        String exif_contrast;
        String exif_datetime_original;
        String exif_device_setting_description;
        String exif_digital_zoom_ratio;
        String exif_exposure_bias_value;
        String exif_exposure_index;
        String exif_exposure_mode;
        String exif_exposure_program;
        String exif_flash_energy;
        String exif_focal_length_in_35mm_film;
        String exif_focal_plane_resolution_unit;
        String exif_focal_plane_x_resolution;
        String exif_focal_plane_y_resolution;
        String exif_gain_control;
        String exif_gps_area_information;
        String exif_gps_differential;
        String exif_gps_dop;
        String exif_gps_measure_mode;
        String exif_image_description;
        String exif_light_source;
        String exif_maker_note;
        String exif_max_aperture_value;
        String exif_metering_mode;
        String exif_oecf;
        String exif_photometric_interpretation;
        String exif_saturation;
        String exif_scene_capture_type;
        String exif_scene_type;
        String exif_sensing_method;
        String exif_sharpness;
        String exif_shutter_speed_value;
        String exif_software;
        String exif_user_comment;
        {
            // tags that are new in Android N - note we skip tags unlikely to be relevant for camera photos
            // update, now available in all Android versions thanks to using AndroidX ExifInterface
            exif_aperture_value = exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE);
            exif_brightness_value = exif.getAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE);
            exif_cfa_pattern = exif.getAttribute(ExifInterface.TAG_CFA_PATTERN);
            exif_color_space = exif.getAttribute(ExifInterface.TAG_COLOR_SPACE);
            exif_components_configuration = exif.getAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION);
            exif_compressed_bits_per_pixel = exif.getAttribute(ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL);
            exif_compression = exif.getAttribute(ExifInterface.TAG_COMPRESSION);
            exif_contrast = exif.getAttribute(ExifInterface.TAG_CONTRAST);
            exif_datetime_original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            exif_device_setting_description = exif.getAttribute(ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION);
            exif_digital_zoom_ratio = exif.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO);
            // unclear if we should transfer TAG_EXIF_VERSION - don't want to risk conficting with whatever ExifInterface writes itself
            exif_exposure_bias_value = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE);
            exif_exposure_index = exif.getAttribute(ExifInterface.TAG_EXPOSURE_INDEX);
            exif_exposure_mode = exif.getAttribute(ExifInterface.TAG_EXPOSURE_MODE);
            exif_exposure_program = exif.getAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM);
            exif_flash_energy = exif.getAttribute(ExifInterface.TAG_FLASH_ENERGY);
            exif_focal_length_in_35mm_film = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM);
            exif_focal_plane_resolution_unit = exif.getAttribute(ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT);
            exif_focal_plane_x_resolution = exif.getAttribute(ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION);
            exif_focal_plane_y_resolution = exif.getAttribute(ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION);
            // TAG_F_NUMBER same as TAG_APERTURE
            exif_gain_control = exif.getAttribute(ExifInterface.TAG_GAIN_CONTROL);
            exif_gps_area_information = exif.getAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION);
            // don't care about TAG_GPS_DEST_*
            exif_gps_differential = exif.getAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL);
            exif_gps_dop = exif.getAttribute(ExifInterface.TAG_GPS_DOP);
            // TAG_GPS_IMG_DIRECTION, TAG_GPS_IMG_DIRECTION_REF won't have been recorded in the image yet - we add this ourselves in setGPSDirectionExif()
            // don't care about TAG_GPS_MAP_DATUM?
            exif_gps_measure_mode = exif.getAttribute(ExifInterface.TAG_GPS_MEASURE_MODE);
            // don't care about TAG_GPS_SATELLITES?
            // don't care about TAG_GPS_SPEED, TAG_GPS_SPEED_REF, TAG_GPS_STATUS, TAG_GPS_TRACK, TAG_GPS_TRACK_REF, TAG_GPS_VERSION_ID
            exif_image_description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
            // unclear what TAG_IMAGE_UNIQUE_ID, TAG_INTEROPERABILITY_INDEX are
            // TAG_ISO_SPEED_RATINGS same as TAG_ISO
            // skip TAG_JPEG_INTERCHANGE_FORMAT, TAG_JPEG_INTERCHANGE_FORMAT_LENGTH
            exif_light_source = exif.getAttribute(ExifInterface.TAG_LIGHT_SOURCE);
            exif_maker_note = exif.getAttribute(ExifInterface.TAG_MAKER_NOTE);
            exif_max_aperture_value = exif.getAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE);
            exif_metering_mode = exif.getAttribute(ExifInterface.TAG_METERING_MODE);
            exif_oecf = exif.getAttribute(ExifInterface.TAG_OECF);
            exif_photometric_interpretation = exif.getAttribute(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION);
            // skip PIXEL_X/Y_DIMENSION, as it may have changed
            // don't care about TAG_PLANAR_CONFIGURATION
            // don't care about TAG_PRIMARY_CHROMATICITIES, TAG_REFERENCE_BLACK_WHITE?
            // don't care about TAG_RESOLUTION_UNIT
            // TAG_ROWS_PER_STRIP may have changed (if it's even relevant)
            // TAG_SAMPLES_PER_PIXEL may no longer be relevant if we've changed the image dimensions?
            exif_saturation = exif.getAttribute(ExifInterface.TAG_SATURATION);
            exif_scene_capture_type = exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE);
            exif_scene_type = exif.getAttribute(ExifInterface.TAG_SCENE_TYPE);
            exif_sensing_method = exif.getAttribute(ExifInterface.TAG_SENSING_METHOD);
            exif_sharpness = exif.getAttribute(ExifInterface.TAG_SHARPNESS);
            exif_shutter_speed_value = exif.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE);
            exif_software = exif.getAttribute(ExifInterface.TAG_SOFTWARE);
            // don't care about TAG_SPATIAL_FREQUENCY_RESPONSE, TAG_SPECTRAL_SENSITIVITY?
            // don't care about TAG_STRIP_*
            // don't care about TAG_SUBJECT_*
            // TAG_SUBSEC_TIME_DIGITIZED same as TAG_SUBSEC_TIME_DIG
            // TAG_SUBSEC_TIME_ORIGINAL same as TAG_SUBSEC_TIME_ORIG
            // TAG_THUMBNAIL_IMAGE_* may have changed
            // don't care about TAG_TRANSFER_FUNCTION?
            exif_user_comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT);
            // don't care about TAG_WHITE_POINT?
            // TAG_X_RESOLUTION may have changed?
            // don't care about TAG_Y_*?
        }

        if( MyDebug.LOG )
            Log.d(TAG, "now write new EXIF data");
        if( exif_aperture != null )
            exif_new.setAttribute(ExifInterface.TAG_F_NUMBER, exif_aperture);
        if( exif_datetime != null )
            exif_new.setAttribute(ExifInterface.TAG_DATETIME, exif_datetime);
        if( exif_exposure_time != null )
            exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exif_exposure_time);
        if( exif_flash != null )
            exif_new.setAttribute(ExifInterface.TAG_FLASH, exif_flash);
        if( exif_focal_length != null )
            exif_new.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, exif_focal_length);
        if( exif_gps_altitude != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, exif_gps_altitude);
        if( exif_gps_altitude_ref != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, exif_gps_altitude_ref);
        if( exif_gps_datestamp != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, exif_gps_datestamp);
        if( exif_gps_latitude != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE, exif_gps_latitude);
        if( exif_gps_latitude_ref != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, exif_gps_latitude_ref);
        if( exif_gps_longitude != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, exif_gps_longitude);
        if( exif_gps_longitude_ref != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, exif_gps_longitude_ref);
        if( exif_gps_processing_method != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, exif_gps_processing_method);
        if( exif_gps_timestamp != null )
            exif_new.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, exif_gps_timestamp);
        if( exif_iso != null )
            //noinspection deprecation
            exif_new.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, exif_iso);
        if( exif_make != null )
            exif_new.setAttribute(ExifInterface.TAG_MAKE, exif_make);
        if( exif_model != null )
            exif_new.setAttribute(ExifInterface.TAG_MODEL, exif_model);
        if( exif_white_balance != null )
            exif_new.setAttribute(ExifInterface.TAG_WHITE_BALANCE, exif_white_balance);

        {
            if( exif_datetime_digitized != null )
                exif_new.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exif_datetime_digitized);
            if( exif_subsec_time != null )
                exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME, exif_subsec_time);
            if( exif_subsec_time_dig != null )
                exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, exif_subsec_time_dig);
            if( exif_subsec_time_orig != null )
                exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, exif_subsec_time_orig);
        }

        {
            if( exif_aperture_value != null )
                exif_new.setAttribute(ExifInterface.TAG_APERTURE_VALUE, exif_aperture_value);
            if( exif_brightness_value != null )
                exif_new.setAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE, exif_brightness_value);
            if( exif_cfa_pattern != null )
                exif_new.setAttribute(ExifInterface.TAG_CFA_PATTERN, exif_cfa_pattern);
            if( exif_color_space != null )
                exif_new.setAttribute(ExifInterface.TAG_COLOR_SPACE, exif_color_space);
            if( exif_components_configuration != null )
                exif_new.setAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION, exif_components_configuration);
            if( exif_compressed_bits_per_pixel != null )
                exif_new.setAttribute(ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL, exif_compressed_bits_per_pixel);
            if( exif_compression != null )
                exif_new.setAttribute(ExifInterface.TAG_COMPRESSION, exif_compression);
            if( exif_contrast != null )
                exif_new.setAttribute(ExifInterface.TAG_CONTRAST, exif_contrast);
            if( exif_datetime_original != null )
                exif_new.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exif_datetime_original);
            if( exif_device_setting_description != null )
                exif_new.setAttribute(ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION, exif_device_setting_description);
            if( exif_digital_zoom_ratio != null )
                exif_new.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, exif_digital_zoom_ratio);
            if( exif_exposure_bias_value != null )
                exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, exif_exposure_bias_value);
            if( exif_exposure_index != null )
                exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_INDEX, exif_exposure_index);
            if( exif_exposure_mode != null )
                exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, exif_exposure_mode);
            if( exif_exposure_program != null )
                exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, exif_exposure_program);
            if( exif_flash_energy != null )
                exif_new.setAttribute(ExifInterface.TAG_FLASH_ENERGY, exif_flash_energy);
            if( exif_focal_length_in_35mm_film != null )
                exif_new.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, exif_focal_length_in_35mm_film);
            if( exif_focal_plane_resolution_unit != null )
                exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT, exif_focal_plane_resolution_unit);
            if( exif_focal_plane_x_resolution != null )
                exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION, exif_focal_plane_x_resolution);
            if( exif_focal_plane_y_resolution != null )
                exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, exif_focal_plane_y_resolution);
            if( exif_gain_control != null )
                exif_new.setAttribute(ExifInterface.TAG_GAIN_CONTROL, exif_gain_control);
            if( exif_gps_area_information != null )
                exif_new.setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, exif_gps_area_information);
            if( exif_gps_differential != null )
                exif_new.setAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL, exif_gps_differential);
            if( exif_gps_dop != null )
                exif_new.setAttribute(ExifInterface.TAG_GPS_DOP, exif_gps_dop);
            if( exif_gps_measure_mode != null )
                exif_new.setAttribute(ExifInterface.TAG_GPS_MEASURE_MODE, exif_gps_measure_mode);
            if( exif_image_description != null )
                exif_new.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, exif_image_description);
            if( exif_light_source != null )
                exif_new.setAttribute(ExifInterface.TAG_LIGHT_SOURCE, exif_light_source);
            if( exif_maker_note != null )
                exif_new.setAttribute(ExifInterface.TAG_MAKER_NOTE, exif_maker_note);
            if( exif_max_aperture_value != null )
                exif_new.setAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE, exif_max_aperture_value);
            if( exif_metering_mode != null )
                exif_new.setAttribute(ExifInterface.TAG_METERING_MODE, exif_metering_mode);
            if( exif_oecf != null )
                exif_new.setAttribute(ExifInterface.TAG_OECF, exif_oecf);
            if( exif_photometric_interpretation != null )
                exif_new.setAttribute(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, exif_photometric_interpretation);
            if( exif_saturation != null )
                exif_new.setAttribute(ExifInterface.TAG_SATURATION, exif_saturation);
            if( exif_scene_capture_type != null )
                exif_new.setAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE, exif_scene_capture_type);
            if( exif_scene_type != null )
                exif_new.setAttribute(ExifInterface.TAG_SCENE_TYPE, exif_scene_type);
            if( exif_sensing_method != null )
                exif_new.setAttribute(ExifInterface.TAG_SENSING_METHOD, exif_sensing_method);
            if( exif_sharpness != null )
                exif_new.setAttribute(ExifInterface.TAG_SHARPNESS, exif_sharpness);
            if( exif_shutter_speed_value != null )
                exif_new.setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE, exif_shutter_speed_value);
            if( exif_software != null )
                exif_new.setAttribute(ExifInterface.TAG_SOFTWARE, exif_software);
            if( exif_user_comment != null )
                exif_new.setAttribute(ExifInterface.TAG_USER_COMMENT, exif_user_comment);
        }

//        modifyExif(exif_new, request.type == Request.Type.JPEG, request.using_camera2, request.current_date, request.store_location, request.store_geo_direction, request.geo_direction, request.custom_tag_artist, request.custom_tag_copyright, request.level_angle, request.pitch_angle, request.store_ypr);
        setDateTimeExif(exif_new);
        exif_new.saveAttributes();
    }


    /** Rotates the supplied bitmap according to the orientation tag stored in the exif data.. If no
     *  rotation is required, the input bitmap is returned.
     * @param data Jpeg data containing the Exif information to use.
     */
    private Bitmap rotateForExif(Bitmap bitmap, byte [] data) {
        if( MyDebug.LOG )
            Log.d(TAG, "rotateForExif");
        InputStream inputStream = null;
        try {
            ExifInterface exif;

            if( MyDebug.LOG )
                Log.d(TAG, "use data stream to read exif tags");
            inputStream = new ByteArrayInputStream(data);
            exif = new ExifInterface(inputStream);

            int exif_orientation_s = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            if( MyDebug.LOG )
                Log.d(TAG, "    exif orientation string: " + exif_orientation_s);
            boolean needs_tf = false;
            int exif_orientation = 0;
            // see http://jpegclub.org/exif_orientation.html
            // and http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
            switch (exif_orientation_s) {
                case ExifInterface.ORIENTATION_UNDEFINED:
                case ExifInterface.ORIENTATION_NORMAL:
                    // leave unchanged
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    needs_tf = true;
                    exif_orientation = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    needs_tf = true;
                    exif_orientation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    needs_tf = true;
                    exif_orientation = 270;
                    break;
                default:
                    // just leave unchanged for now
                    if (MyDebug.LOG)
                        Log.e(TAG, "    unsupported exif orientation: " + exif_orientation_s);
                    break;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "    exif orientation: " + exif_orientation);

            if( needs_tf ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "    need to rotate bitmap due to exif orientation tag");
                Matrix m = new Matrix();
                m.setRotate(exif_orientation, bitmap.getWidth() * 0.5f, bitmap.getHeight() * 0.5f);
                Bitmap rotated_bitmap = Bitmap.createBitmap(bitmap, 0, 0,bitmap.getWidth(), bitmap.getHeight(), m, true);
                if( rotated_bitmap != bitmap ) {
                    bitmap.recycle();
                    bitmap = rotated_bitmap;
                }
            }
        }
        catch(IOException exception) {
            if( MyDebug.LOG )
                Log.e(TAG, "exif orientation ioexception");
            exception.printStackTrace();
        }
        catch(NoClassDefFoundError exception) {
            // have had Google Play crashes from new ExifInterface() for Galaxy Ace4 (vivalto3g), Galaxy S Duos3 (vivalto3gvn)
            if( MyDebug.LOG )
                Log.e(TAG, "exif orientation NoClassDefFoundError");
            exception.printStackTrace();
        }
        finally {
            if( inputStream != null ) {
                try {
                    inputStream.close();
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return bitmap;
    }

    /** Loads the bitmap from the supplied jpeg data, rotating if necessary according to the
     *  supplied EXIF orientation tag.
     * @param data The jpeg data.
     * @param mutable Whether to create a mutable bitmap.
     * @return A bitmap representing the correctly rotated jpeg.
     */
    private Bitmap loadBitmapWithRotation(byte [] data, boolean mutable) {
        Bitmap bitmap = loadBitmap(data, mutable, 1);
        if( bitmap != null ) {
            // rotate the bitmap if necessary for exif tags
            if( MyDebug.LOG )
                Log.d(TAG, "rotate bitmap for exif tags?");
            bitmap = rotateForExif(bitmap, data);
        }
        return bitmap;
    }


    /** This fixes a problem when we save from a bitmap - we need to set extra exiftags.
     *  Exiftool shows these tags as "Date/Time Original" and "Create Date".
     *  Without these tags, Windows properties for the image doesn't show anything for
     *  Origin/"Date taken".
     *  N.B., this is probably redundant on Android 7+, where we'll have transferred these tags
     *  across from the original JPEG in setExif().
     */
    private void setDateTimeExif(ExifInterface exif) {
        if( MyDebug.LOG )
            Log.d(TAG, "setDateTimeExif");
        String exif_datetime = exif.getAttribute(ExifInterface.TAG_DATETIME);
        if( exif_datetime != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "write datetime tags: " + exif_datetime);
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exif_datetime);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exif_datetime);
        }
    }

}
