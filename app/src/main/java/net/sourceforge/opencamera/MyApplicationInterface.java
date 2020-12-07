package net.sourceforge.opencamera;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.preview.ApplicationInterface;
import net.sourceforge.opencamera.preview.BasicApplicationInterface;
import net.sourceforge.opencamera.preview.VideoProfile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

/** Our implementation of ApplicationInterface, see there for details.
 */
public class MyApplicationInterface extends BasicApplicationInterface {
    private static final String TAG = "MyApplicationInterface";

    // note, okay to change the order of enums in future versions, as getPhotoMode() does not rely on the order for the saved photo mode
    public enum PhotoMode {
        Standard
    }

    private final MainActivity main_activity;
    private final ImageSaver imageSaver;

    private final static float panorama_pics_per_screen = 3.33333f;
    private int n_capture_images = 0; // how many calls to onPictureTaken() since the last call to onCaptureStarted()
    private int n_capture_images_raw = 0; // how many calls to onRawPictureTaken() since the last call to onCaptureStarted()
    private int n_panorama_pics = 0;
    public final static int max_panorama_pics_c = 10; // if we increase this, review against memory requirements under MainActivity.supportsPanorama()
    private boolean panorama_pic_accepted; // whether the last panorama picture was accepted, or else needs to be retaken
    private boolean panorama_dir_left_to_right = true; // direction of panorama (set after we've captured two images)

    private File last_video_file = null;
    private Uri last_video_file_saf = null;

    private final Timer subtitleVideoTimer = new Timer();
    private TimerTask subtitleVideoTimerTask;

    private final Rect text_bounds = new Rect();
    private boolean used_front_screen_flash ;

    // store to avoid calling PreferenceManager.getDefaultSharedPreferences() repeatedly
    private final SharedPreferences sharedPreferences;

    private boolean last_images_saf; // whether the last images array are using SAF or not


    private final ToastBoxer photo_delete_toast = new ToastBoxer();

    // camera properties which are saved in bundle, but not stored in preferences (so will be remembered if the app goes into background, but not after restart)
    private final static int cameraId_default = 0;
    private boolean has_set_cameraId;
    private int cameraId = cameraId_default;
    private final static String nr_mode_default = "preference_nr_mode_normal";
    private String nr_mode = nr_mode_default;
    private final static float aperture_default = -1.0f;
    private float aperture = aperture_default;
    // camera properties that aren't saved even in the bundle; these should be initialised/reset in reset()
    private int zoom_factor; // don't save zoom, as doing so tends to confuse users; other camera applications don't seem to save zoom when pause/resuming

    // for testing:
    public volatile int test_n_videos_scanned;
    public volatile int test_max_mp;

    MyApplicationInterface(MainActivity main_activity, Bundle savedInstanceState) {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "MyApplicationInterface");
            debug_time = System.currentTimeMillis();
        }
        this.main_activity = main_activity;
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);

        if( MyDebug.LOG )
            Log.d(TAG, "MyApplicationInterface: time after creating location supplier: " + (System.currentTimeMillis() - debug_time));
        if( MyDebug.LOG )
            Log.d(TAG, "MyApplicationInterface: time after creating storage utils: " + (System.currentTimeMillis() - debug_time));

        this.imageSaver = new ImageSaver(main_activity);
        this.imageSaver.start();

        this.reset(false);
        if( savedInstanceState != null ) {
            // load the things we saved in onSaveInstanceState().
            if( MyDebug.LOG )
                Log.d(TAG, "read from savedInstanceState");
            has_set_cameraId = true;
            cameraId = savedInstanceState.getInt("cameraId", cameraId_default);
            if( MyDebug.LOG )
                Log.d(TAG, "found cameraId: " + cameraId);
            nr_mode = savedInstanceState.getString("nr_mode", nr_mode_default);
            if( MyDebug.LOG )
                Log.d(TAG, "found nr_mode: " + nr_mode);
            aperture = savedInstanceState.getFloat("aperture", aperture_default);
            if( MyDebug.LOG )
                Log.d(TAG, "found aperture: " + aperture);
        }

        if( MyDebug.LOG )
            Log.d(TAG, "MyApplicationInterface: total time to create MyApplicationInterface: " + (System.currentTimeMillis() - debug_time));
    }

    /** Here we save states which aren't saved in preferences (we don't want them to be saved if the
     *  application is restarted from scratch), but we do want to preserve if Android has to recreate
     *  the application (e.g., configuration change, or it's destroyed while in background).
     */
    void onSaveInstanceState(Bundle state) {
        if( MyDebug.LOG )
            Log.d(TAG, "onSaveInstanceState");
        if( MyDebug.LOG )
            Log.d(TAG, "save cameraId: " + cameraId);
        state.putInt("cameraId", cameraId);
        if( MyDebug.LOG )
            Log.d(TAG, "save nr_mode: " + nr_mode);
        state.putString("nr_mode", nr_mode);
        if( MyDebug.LOG )
            Log.d(TAG, "save aperture: " + aperture);
        state.putFloat("aperture", aperture);
    }

    void onDestroy() {
        if( MyDebug.LOG )
            Log.d(TAG, "onDestroy");

        if( imageSaver != null ) {
            imageSaver.onDestroy();
        }
    }

    public ImageSaver getImageSaver() {
        return imageSaver;
    }


    @Override
    public Context getContext() {
        return main_activity;
    }


    @Override
    public int createOutputVideoMethod() {
        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from video capture intent");
            Bundle myExtras = main_activity.getIntent().getExtras();
            if (myExtras != null) {
                Uri intent_uri = myExtras.getParcelable(MediaStore.EXTRA_OUTPUT);
                if( intent_uri != null ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "save to: " + intent_uri);
                    return VIDEOMETHOD_URI;
                }
            }
            // if no EXTRA_OUTPUT, we should save to standard location, and will pass back the Uri of that location
            if( MyDebug.LOG )
                Log.d(TAG, "intent uri not specified");
            // note that SAF URIs don't seem to work for calling applications (tested with Grabilla and "Photo Grabber Image From Video" (FreezeFrame)), so we use standard folder with non-SAF method
            return VIDEOMETHOD_FILE;
        }
        return  VIDEOMETHOD_FILE;
    }

    @Override
    public File createOutputVideoFile(String extension) throws IOException {
        last_video_file =new File(main_activity.getFilesDir() + "/" + System.currentTimeMillis() + "." + extension);// storageUtils.createOutputMediaFile(StorageUtils.MEDIA_TYPE_VIDEO, "", extension, new Date());
        return last_video_file;
    }


    @Override
    public Uri createOutputVideoUri() {
        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from video capture intent");
            Bundle myExtras = main_activity.getIntent().getExtras();
            if (myExtras != null) {
                Uri intent_uri = myExtras.getParcelable(MediaStore.EXTRA_OUTPUT);
                if( intent_uri != null ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "save to: " + intent_uri);
                    return intent_uri;
                }
            }
        }
        throw new RuntimeException(); // programming error if we arrived here
    }

    @Override
    public int getCameraIdPref() {
        return cameraId;
    }

    @Override
    public String getFlashPref() {
        return sharedPreferences.getString(PreferenceKeys.getFlashPreferenceKey(cameraId), "");
    }

    @Override
    public String getFocusPref(boolean is_video) {
        return sharedPreferences.getString(PreferenceKeys.getFocusPreferenceKey(cameraId, is_video), "");
    }

    int getFocusAssistPref() {
        String focus_assist_value = sharedPreferences.getString(PreferenceKeys.FocusAssistPreferenceKey, "0");
        int focus_assist;
        try {
            focus_assist = Integer.parseInt(focus_assist_value);
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse focus_assist_value: " + focus_assist_value);
            e.printStackTrace();
            focus_assist = 0;
        }
        if( focus_assist > 0 && main_activity.getPreview().isVideoRecording() ) {
            // focus assist not currently supported while recording video - don't want to zoom the resultant video!
            focus_assist = 0;
        }
        return focus_assist;
    }

    @Override
    public boolean isVideoPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.IsVideoPreferenceKey, false);
    }

    @Override
    public String getColorEffectPref() {
        return sharedPreferences.getString(PreferenceKeys.ColorEffectPreferenceKey, CameraController.COLOR_EFFECT_DEFAULT);
    }

    @Override
    public String getWhiteBalancePref() {
        return sharedPreferences.getString(PreferenceKeys.WhiteBalancePreferenceKey, CameraController.WHITE_BALANCE_DEFAULT);
    }

    @Override
    public int getWhiteBalanceTemperaturePref() {
        return sharedPreferences.getInt(PreferenceKeys.WhiteBalanceTemperaturePreferenceKey, 5000);
    }

    @Override
    public String getAntiBandingPref() {
        return sharedPreferences.getString(PreferenceKeys.AntiBandingPreferenceKey, CameraController.ANTIBANDING_DEFAULT);
    }

    @Override
    public String getEdgeModePref() {
        return sharedPreferences.getString(PreferenceKeys.EdgeModePreferenceKey, CameraController.EDGE_MODE_DEFAULT);
    }

    @Override
    public String getCameraNoiseReductionModePref() {
        return sharedPreferences.getString(PreferenceKeys.CameraNoiseReductionModePreferenceKey, CameraController.NOISE_REDUCTION_MODE_DEFAULT);
    }

    @Override
    public String getISOPref() {
        return sharedPreferences.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
    }

    @Override
    public int getExposureCompensationPref() {
        String value = sharedPreferences.getString(PreferenceKeys.ExposurePreferenceKey, "0");
        if( MyDebug.LOG )
            Log.d(TAG, "saved exposure value: " + value);
        int exposure = 0;
        try {
            exposure = Integer.parseInt(value);
            if( MyDebug.LOG )
                Log.d(TAG, "exposure: " + exposure);
        }
        catch(NumberFormatException exception) {
            if( MyDebug.LOG )
                Log.d(TAG, "exposure invalid format, can't parse to int");
        }
        return exposure;
    }


    @Override
    public Pair<Integer, Integer> getCameraResolutionPref(CameraResolutionConstraints constraints) {

        String resolution_value = sharedPreferences.getString(PreferenceKeys.getResolutionPreferenceKey(cameraId), "");
        if( MyDebug.LOG )
            Log.d(TAG, "resolution_value: " + resolution_value);
        Pair<Integer, Integer> result = null;
        if( resolution_value.length() > 0 ) {
            // parse the saved size, and make sure it is still valid
            int index = resolution_value.indexOf(' ');
            if( index == -1 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "resolution_value invalid format, can't find space");
            }
            else {
                String resolution_w_s = resolution_value.substring(0, index);
                String resolution_h_s = resolution_value.substring(index+1);
                if( MyDebug.LOG ) {
                    Log.d(TAG, "resolution_w_s: " + resolution_w_s);
                    Log.d(TAG, "resolution_h_s: " + resolution_h_s);
                }
                try {
                    int resolution_w = Integer.parseInt(resolution_w_s);
                    if( MyDebug.LOG )
                        Log.d(TAG, "resolution_w: " + resolution_w);
                    int resolution_h = Integer.parseInt(resolution_h_s);
                    if( MyDebug.LOG )
                        Log.d(TAG, "resolution_h: " + resolution_h);
                    result = new Pair<>(resolution_w, resolution_h);
                }
                catch(NumberFormatException exception) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "resolution_value invalid format, can't parse w or h to int");
                }
            }
        }

        return result;
    }

    /** getImageQualityPref() returns the image quality used for the Camera Controller for taking a
     *  photo - in some cases, we may set that to a higher value, then perform processing on the
     *  resultant JPEG before resaving. This method returns the image quality setting to be used for
     *  saving the final image (as specified by the user).
     */
    private int getSaveImageQualityPref() {
        if( MyDebug.LOG )
            Log.d(TAG, "getSaveImageQualityPref");
        String image_quality_s = sharedPreferences.getString(PreferenceKeys.QualityPreferenceKey, "90");
        int image_quality;
        try {
            image_quality = Integer.parseInt(image_quality_s);
        }
        catch(NumberFormatException exception) {
            if( MyDebug.LOG )
                Log.e(TAG, "image_quality_s invalid format: " + image_quality_s);
            image_quality = 90;
        }
        if( isRawOnly() ) {
            // if raw only mode, we can set a lower quality for the JPEG, as it isn't going to be saved - only used for
            // the thumbnail and pause preview option
            if( MyDebug.LOG )
                Log.d(TAG, "set lower quality for raw_only mode");
            image_quality = Math.min(image_quality, 70);
        }
        return image_quality;
    }

    @Override
    public int getImageQualityPref() {

        if( getImageFormatPref() != ImageSaver.Request.ImageFormat.STD )
            return 100;

        return getSaveImageQualityPref();
    }

    @Override
    public boolean getFaceDetectionPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.FaceDetectionPreferenceKey, false);
    }

    /** Returns whether the current fps preference is one that requires a "high speed" video size/
     *  frame rate.
     */
    public boolean fpsIsHighSpeed() {
        return main_activity.getPreview().fpsIsHighSpeed(getVideoFPSPref());
    }

    @Override
    public String getVideoQualityPref() {
        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from video capture intent");
            if( main_activity.getIntent().hasExtra(MediaStore.EXTRA_VIDEO_QUALITY) ) {
                int intent_quality = main_activity.getIntent().getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
                if( MyDebug.LOG )
                    Log.d(TAG, "intent_quality: " + intent_quality);
                if( intent_quality == 0 || intent_quality == 1 ) {
                    List<String> video_quality = main_activity.getPreview().getVideoQualityHander().getSupportedVideoQuality();
                    if( intent_quality == 0 ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "return lowest quality");
                        // return lowest quality, video_quality is sorted high to low
                        return video_quality.get(video_quality.size()-1);
                    }
                    else {
                        if( MyDebug.LOG )
                            Log.d(TAG, "return highest quality");
                        // return highest quality, video_quality is sorted high to low
                        return video_quality.get(0);
                    }
                }
            }
        }

        // Conceivably, we might get in a state where the fps isn't supported at all (e.g., an upgrade changes the available
        // supported video resolutions/frame-rates).
        return sharedPreferences.getString(PreferenceKeys.getVideoQualityPreferenceKey(cameraId, fpsIsHighSpeed()), "");
    }

    @Override
    public boolean getVideoStabilizationPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, false);
    }

    @Override
    public boolean getForce4KPref() {
        return cameraId == 0 && sharedPreferences.getBoolean(PreferenceKeys.ForceVideo4KPreferenceKey, false) && main_activity.supportsForceVideo4K();
    }

    @Override
    public String getRecordVideoOutputFormatPref() {
        return sharedPreferences.getString(PreferenceKeys.VideoFormatPreferenceKey, "preference_video_output_format_default");
    }

    @Override
    public String getVideoBitratePref() {
        return sharedPreferences.getString(PreferenceKeys.VideoBitratePreferenceKey, "default");
    }

    @Override
    public String getVideoFPSPref() {
        // if check for EXTRA_VIDEO_QUALITY, if set, best to fall back to default FPS - see corresponding code in getVideoQualityPref
        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from video capture intent");
            if( main_activity.getIntent().hasExtra(MediaStore.EXTRA_VIDEO_QUALITY) ) {
                int intent_quality = main_activity.getIntent().getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
                if (MyDebug.LOG)
                    Log.d(TAG, "intent_quality: " + intent_quality);
                if (intent_quality == 0 || intent_quality == 1) {
                    return "default";
                }
            }
        }

        float capture_rate_factor = getVideoCaptureRateFactor();
        if( capture_rate_factor < 1.0f-1.0e-5f ) {
            if( MyDebug.LOG )
                Log.d(TAG, "set fps for slow motion, capture rate: " + capture_rate_factor);
            int preferred_fps = (int)(30.0/capture_rate_factor+0.5);
            if( MyDebug.LOG )
                Log.d(TAG, "preferred_fps: " + preferred_fps);
            if( main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRateHighSpeed(preferred_fps) ||
                    main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRate(preferred_fps) )
                return "" + preferred_fps;
            // just in case say we support 120fps but NOT 60fps, getSupportedSlowMotionRates() will have returned that 2x slow
            // motion is supported, but we need to set 120fps instead of 60fps
            while( preferred_fps < 240 ) {
                preferred_fps *= 2;
                if( MyDebug.LOG )
                    Log.d(TAG, "preferred_fps not supported, try: " + preferred_fps);
                if( main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRateHighSpeed(preferred_fps) ||
                        main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRate(preferred_fps) )
                    return "" + preferred_fps;
            }
            // shouln't happen based on getSupportedSlowMotionRates()
            Log.e(TAG, "can't find valid fps for slow motion");
            return "default";
        }
        return sharedPreferences.getString(PreferenceKeys.getVideoFPSPreferenceKey(cameraId), "default");
    }

    @Override
    public float getVideoCaptureRateFactor() {
        float capture_rate_factor = sharedPreferences.getFloat(PreferenceKeys.getVideoCaptureRatePreferenceKey(main_activity.getPreview().getCameraId()), 1.0f);
        if( MyDebug.LOG )
            Log.d(TAG, "capture_rate_factor: " + capture_rate_factor);
        if( Math.abs(capture_rate_factor - 1.0f) > 1.0e-5 ) {
            // check stored capture rate is valid
            if( MyDebug.LOG )
                Log.d(TAG, "check stored capture rate is valid");
            List<Float> supported_capture_rates = getSupportedVideoCaptureRates();
            if( MyDebug.LOG )
                Log.d(TAG, "supported_capture_rates: " + supported_capture_rates);
            boolean found = false;
            for(float this_capture_rate : supported_capture_rates) {
                if( Math.abs(capture_rate_factor - this_capture_rate) < 1.0e-5 ) {
                    found = true;
                    break;
                }
            }
            if( !found ) {
                Log.e(TAG, "stored capture_rate_factor: " + capture_rate_factor + " not supported");
                capture_rate_factor = 1.0f;
            }
        }
        return capture_rate_factor;
    }

    /** This will always return 1, even if slow motion isn't supported (i.e.,
     *  slow motion should only be considered as supported if at least 2 entries
     *  are returned. Entries are returned in increasing order.
     */
    public List<Float> getSupportedVideoCaptureRates() {
        List<Float> rates = new ArrayList<>();
        if( main_activity.getPreview().supportsVideoHighSpeed() ) {
            // We consider a slow motion rate supported if we can get at least 30fps in slow motion.
            // If this code is updated, see if we also need to update how slow motion fps is chosen
            // in getVideoFPSPref().
            if( main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRateHighSpeed(240) ||
                    main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRate(240) ) {
                rates.add(1.0f/8.0f);
                rates.add(1.0f/4.0f);
                rates.add(1.0f/2.0f);
            }
            else if( main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRateHighSpeed(120) ||
                    main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRate(120) ) {
                rates.add(1.0f/4.0f);
                rates.add(1.0f/2.0f);
            }
            else if( main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRateHighSpeed(60) ||
                    main_activity.getPreview().getVideoQualityHander().videoSupportsFrameRate(60) ) {
                rates.add(1.0f/2.0f);
            }
        }
        rates.add(1.0f);
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
            // add timelapse options
            // in theory this should work on any Android version, though video fails to record in timelapse mode on Galaxy Nexus...
            rates.add(2.0f);
            rates.add(3.0f);
            rates.add(4.0f);
            rates.add(5.0f);
            rates.add(10.0f);
            rates.add(20.0f);
            rates.add(30.0f);
            rates.add(60.0f);
            rates.add(120.0f);
            rates.add(240.0f);
        }
        return rates;
    }

    @Override
    public CameraController.TonemapProfile getVideoTonemapProfile() {
        String video_log = sharedPreferences.getString(PreferenceKeys.VideoLogPreferenceKey, "off");
        // only return TONEMAPPROFILE_LOG for values recognised by getVideoLogProfileStrength()
        switch( video_log ) {
            case "off":
                return CameraController.TonemapProfile.TONEMAPPROFILE_OFF;
            case "rec709":
                return CameraController.TonemapProfile.TONEMAPPROFILE_REC709;
            case "srgb":
                return CameraController.TonemapProfile.TONEMAPPROFILE_SRGB;
            case "fine":
            case "low":
            case "medium":
            case "strong":
            case "extra_strong":
                return CameraController.TonemapProfile.TONEMAPPROFILE_LOG;
            case "gamma":
                return CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA;
            case "jtvideo":
                return CameraController.TonemapProfile.TONEMAPPROFILE_JTVIDEO;
            case "jtlog":
                return CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG;
            case "jtlog2":
                return CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG2;
        }
        return CameraController.TonemapProfile.TONEMAPPROFILE_OFF;
    }

    @Override
    public float getVideoLogProfileStrength() {
        String video_log = sharedPreferences.getString(PreferenceKeys.VideoLogPreferenceKey, "off");
        // remember to update getVideoTonemapProfile() if adding/changing modes
        switch( video_log ) {
            case "off":
            case "rec709":
            case "srgb":
            case "gamma":
            case "jtvideo":
            case "jtlog":
            case "jtlog2":
                return 0.0f;
            /*case "fine":
                return 1.0f;
            case "low":
                return 5.0f;
            case "medium":
                return 10.0f;
            case "strong":
                return 100.0f;
            case "extra_strong":
                return 500.0f;*/
            // need a range of values as behaviour can vary between devices - e.g., "fine" has more effect on Nexus 6 than
            // other devices such as OnePlus 3T or Galaxy S10e
            // recalibrated in v1.48 to correspond to improvements made in CameraController2
            case "fine":
                return 10.0f;
            case "low":
                return 32.0f;
            case "medium":
                return 100.0f;
            case "strong":
                return 224.0f;
            case "extra_strong":
                return 500.0f;
        }
        return 0.0f;
    }

    @Override
    public float getVideoProfileGamma() {
        String gamma_value = sharedPreferences.getString(PreferenceKeys.VideoProfileGammaPreferenceKey, "2.2");
        float gamma = 0.0f;
        try {
            gamma = Float.parseFloat(gamma_value);
            if( MyDebug.LOG )
                Log.d(TAG, "gamma: " + gamma);
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse gamma value: " + gamma_value);
            e.printStackTrace();
        }
        return gamma;
    }

    @Override
    public long getVideoMaxDurationPref() {
        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from video capture intent");
            if( main_activity.getIntent().hasExtra(MediaStore.EXTRA_DURATION_LIMIT) ) {
                int intent_duration_limit = main_activity.getIntent().getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0);
                if( MyDebug.LOG )
                    Log.d(TAG, "intent_duration_limit: " + intent_duration_limit);
                return intent_duration_limit * 1000;
            }
        }

        String video_max_duration_value = sharedPreferences.getString(PreferenceKeys.VideoMaxDurationPreferenceKey, "0");
        long video_max_duration;
        try {
            video_max_duration = (long)Integer.parseInt(video_max_duration_value) * 1000;
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse preference_video_max_duration value: " + video_max_duration_value);
            e.printStackTrace();
            video_max_duration = 0;
        }
        return video_max_duration;
    }

    @Override
    public int getVideoRestartTimesPref() {
        String restart_value = sharedPreferences.getString(PreferenceKeys.VideoRestartPreferenceKey, "0");
        int remaining_restart_video;
        try {
            remaining_restart_video = Integer.parseInt(restart_value);
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse preference_video_restart value: " + restart_value);
            e.printStackTrace();
            remaining_restart_video = 0;
        }
        return remaining_restart_video;
    }

    long getVideoMaxFileSizeUserPref() {
        if( MyDebug.LOG )
            Log.d(TAG, "getVideoMaxFileSizeUserPref");

        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from video capture intent");
            if( main_activity.getIntent().hasExtra(MediaStore.EXTRA_SIZE_LIMIT) ) {
                long intent_size_limit = main_activity.getIntent().getLongExtra(MediaStore.EXTRA_SIZE_LIMIT, 0);
                if( MyDebug.LOG )
                    Log.d(TAG, "intent_size_limit: " + intent_size_limit);
                return intent_size_limit;
            }
        }

        String video_max_filesize_value = sharedPreferences.getString(PreferenceKeys.VideoMaxFileSizePreferenceKey, "0");
        long video_max_filesize;
        try {
            video_max_filesize = Long.parseLong(video_max_filesize_value);
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse preference_video_max_filesize value: " + video_max_filesize_value);
            e.printStackTrace();
            video_max_filesize = 0;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "video_max_filesize: " + video_max_filesize);
        return video_max_filesize;
    }

    private boolean getVideoRestartMaxFileSizeUserPref() {
        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from video capture intent");
            if( main_activity.getIntent().hasExtra(MediaStore.EXTRA_SIZE_LIMIT) ) {
                // if called from a video capture intent that set a max file size, this will be expecting a single file with that maximum size
                return false;
            }
        }

        return sharedPreferences.getBoolean(PreferenceKeys.VideoRestartMaxFileSizePreferenceKey, true);
    }

    @Override
    public VideoMaxFileSize getVideoMaxFileSizePref() throws NoFreeStorageException {
        if( MyDebug.LOG )
            Log.d(TAG, "getVideoMaxFileSizePref");
        VideoMaxFileSize video_max_filesize = new VideoMaxFileSize();
        video_max_filesize.max_filesize = getVideoMaxFileSizeUserPref();
        video_max_filesize.auto_restart = getVideoRestartMaxFileSizeUserPref();
		
		/* Try to set the max filesize so we don't run out of space.
		   If using SD card without storage access framework, it's not reliable to get the free storage
		   (see https://sourceforge.net/p/opencamera/tickets/153/ ).
		   If using Storage Access Framework, getting the available space seems to be reliable for
		   internal storage or external SD card.
		   */

        return video_max_filesize;
    }

    @Override
    public boolean getVideoFlashPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.VideoFlashPreferenceKey, false);
    }

    @Override
    public boolean getVideoLowPowerCheckPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.VideoLowPowerCheckPreferenceKey, true);
    }

    @Override
    public String getPreviewSizePref() {
        return sharedPreferences.getString(PreferenceKeys.PreviewSizePreferenceKey, "preference_preview_size_wysiwyg");
    }

    @Override
    public String getPreviewRotationPref() {
        return sharedPreferences.getString(PreferenceKeys.RotatePreviewPreferenceKey, "0");
    }

    @Override
    public String getLockOrientationPref() {
        return sharedPreferences.getString(PreferenceKeys.LockOrientationPreferenceKey, "none");
    }

    @Override
    public boolean getTouchCapturePref() {
        String value = sharedPreferences.getString(PreferenceKeys.TouchCapturePreferenceKey, "none");
        return value.equals("single");
    }

    @Override
    public boolean getDoubleTapCapturePref() {
        String value = sharedPreferences.getString(PreferenceKeys.TouchCapturePreferenceKey, "none");
        return value.equals("double");
    }

    @Override
    public boolean getPausePreviewPref() {
        if( main_activity.getPreview().isVideoRecording() ) {
            // don't pause preview when taking photos while recording video!
            return false;
        }
        else if( main_activity.lastContinuousFastBurst() ) {
            // Don't use pause preview mode when doing a continuous fast burst
            // Firstly due to not using background thread for pause preview mode, this will be
            // sluggish anyway, but even when this is fixed, I'm not sure it makes sense to use
            // pause preview in this mode.
            return false;
        }
        return sharedPreferences.getBoolean(PreferenceKeys.PausePreviewPreferenceKey, false);
    }

    @Override
    public boolean getShowToastsPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.ShowToastsPreferenceKey, true);
    }

    public boolean getThumbnailAnimationPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.ThumbnailAnimationPreferenceKey, true);
    }

    @Override
    public boolean getShutterSoundPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.ShutterSoundPreferenceKey, true);
    }

    @Override
    public boolean getStartupFocusPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.StartupFocusPreferenceKey, true);
    }

    @Override
    public long getTimerPref() {
        String timer_value = sharedPreferences.getString(PreferenceKeys.TimerPreferenceKey, "0");
        long timer_delay;
        try {
            timer_delay = (long)Integer.parseInt(timer_value) * 1000;
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse preference_timer value: " + timer_value);
            e.printStackTrace();
            timer_delay = 0;
        }
        return timer_delay;
    }

    @Override
    public String getRepeatPref() {
        return sharedPreferences.getString(PreferenceKeys.RepeatModePreferenceKey, "1");
    }

    @Override
    public long getRepeatIntervalPref() {
        String timer_value = sharedPreferences.getString(PreferenceKeys.RepeatIntervalPreferenceKey, "0");
        long timer_delay;
        try {
            float timer_delay_s = Float.parseFloat(timer_value);
            if( MyDebug.LOG )
                Log.d(TAG, "timer_delay_s: " + timer_delay_s);
            timer_delay = (long)(timer_delay_s * 1000);
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse repeat interval value: " + timer_value);
            e.printStackTrace();
            timer_delay = 0;
        }
        return timer_delay;
    }

    @Override
    public boolean getGeotaggingPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.LocationPreferenceKey, false);
    }

    @Override
    public boolean getRequireLocationPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.RequireLocationPreferenceKey, false);
    }

    boolean getGeodirectionPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.GPSDirectionPreferenceKey, false);
    }

    @Override
    public boolean getRecordAudioPref() {
        return sharedPreferences.getBoolean(PreferenceKeys.RecordAudioPreferenceKey, true);
    }

    @Override
    public String getRecordAudioChannelsPref() {
        return sharedPreferences.getString(PreferenceKeys.RecordAudioChannelsPreferenceKey, "audio_default");
    }

    @Override
    public String getRecordAudioSourcePref() {
        return sharedPreferences.getString(PreferenceKeys.RecordAudioSourcePreferenceKey, "audio_src_camcorder");
    }

    public boolean getAutoStabilisePref() {
        boolean auto_stabilise = sharedPreferences.getBoolean(PreferenceKeys.AutoStabilisePreferenceKey, false);
        return auto_stabilise && main_activity.supportsAutoStabilise();
    }

    /** Returns the alpha value to use for ghost image, as a number from 0 to 255.
     *  Note that we store the preference as a percentage from 0 to 100, but scale this to 0 to 255.
     */
    public int getGhostImageAlpha() {
        String ghost_image_alpha_value = sharedPreferences.getString(PreferenceKeys.GhostImageAlphaPreferenceKey, "50");
        int ghost_image_alpha;
        try {
            ghost_image_alpha = Integer.parseInt(ghost_image_alpha_value);
        }
        catch(NumberFormatException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "failed to parse ghost_image_alpha_value: " + ghost_image_alpha_value);
            e.printStackTrace();
            ghost_image_alpha = 50;
        }
        ghost_image_alpha = (int)(ghost_image_alpha*2.55f+0.1f);
        return ghost_image_alpha;
    }

    public String getStampPref() {
        return sharedPreferences.getString(PreferenceKeys.StampPreferenceKey, "preference_stamp_no");
    }

    private String getStampDateFormatPref() {
        return sharedPreferences.getString(PreferenceKeys.StampDateFormatPreferenceKey, "preference_stamp_dateformat_default");
    }

    private String getStampTimeFormatPref() {
        return sharedPreferences.getString(PreferenceKeys.StampTimeFormatPreferenceKey, "preference_stamp_timeformat_default");
    }

    private String getStampGPSFormatPref() {
        return sharedPreferences.getString(PreferenceKeys.StampGPSFormatPreferenceKey, "preference_stamp_gpsformat_default");
    }

    private String getStampGeoAddressPref() {
        return sharedPreferences.getString(PreferenceKeys.StampGeoAddressPreferenceKey, "preference_stamp_geo_address_no");
    }

    private String getUnitsDistancePref() {
        return sharedPreferences.getString(PreferenceKeys.UnitsDistancePreferenceKey, "preference_units_distance_m");
    }

    public String getTextStampPref() {
        return sharedPreferences.getString(PreferenceKeys.TextStampPreferenceKey, "");
    }

    private int getTextStampFontSizePref() {
        int font_size = 12;
        String value = sharedPreferences.getString(PreferenceKeys.StampFontSizePreferenceKey, "12");
        if( MyDebug.LOG )
            Log.d(TAG, "saved font size: " + value);
        try {
            font_size = Integer.parseInt(value);
            if( MyDebug.LOG )
                Log.d(TAG, "font_size: " + font_size);
        }
        catch(NumberFormatException exception) {
            if( MyDebug.LOG )
                Log.d(TAG, "font size invalid format, can't parse to int");
        }
        return font_size;
    }

    private String getVideoSubtitlePref() {
        return sharedPreferences.getString(PreferenceKeys.VideoSubtitlePref, "preference_video_subtitle_no");
    }

    @Override
    public int getZoomPref() {
        if( MyDebug.LOG )
            Log.d(TAG, "getZoomPref: " + zoom_factor);
        return zoom_factor;
    }

    @Override
    public double getCalibratedLevelAngle() {
        return sharedPreferences.getFloat(PreferenceKeys.CalibratedLevelAnglePreferenceKey, 0.0f);
    }

    @Override
    public boolean canTakeNewPhoto() {
        if( MyDebug.LOG )
            Log.d(TAG, "canTakeNewPhoto");

        int  n_jpegs;
        // default
        n_jpegs = 1;

        int photo_cost = imageSaver.computePhotoCost(0, n_jpegs);
        if( imageSaver.queueWouldBlock(photo_cost) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "canTakeNewPhoto: no, as queue would block");
            return false;
        }

        // even if the queue isn't full, we may apply additional limits
        int n_images_to_save = imageSaver.getNImagesToSave();

        if( n_jpegs > 1 ) {
            // if in any other kind of burst mode (e.g., expo burst, HDR), allow a max of 3 photos in memory
            if( n_images_to_save >= 3*photo_cost ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "canTakeNewPhoto: no, as too many for burst");
                return false;
            }
        }

        // otherwise, still have a max limit of 5 photos
        if( n_images_to_save >= 5*photo_cost ) {
            {
                if( MyDebug.LOG )
                    Log.d(TAG, "canTakeNewPhoto: no, as too many for regular");
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean imageQueueWouldBlock(int n_raw, int n_jpegs) {
        if( MyDebug.LOG )
            Log.d(TAG, "imageQueueWouldBlock");
        return imageSaver.queueWouldBlock(n_raw, n_jpegs);
    }

    @Override
    public long getExposureTimePref() {
        return sharedPreferences.getLong(PreferenceKeys.ExposureTimePreferenceKey, CameraController.EXPOSURE_TIME_DEFAULT);
    }

    @Override
    public float getFocusDistancePref(boolean is_target_distance) {
        return sharedPreferences.getFloat(is_target_distance ? PreferenceKeys.FocusBracketingTargetDistancePreferenceKey : PreferenceKeys.FocusDistancePreferenceKey, 0.0f);
    }


    public void setNRMode(String nr_mode) {
        this.nr_mode = nr_mode;
    }

    public String getNRMode() {
		/*if( MyDebug.LOG )
			Log.d(TAG, "nr_mode: " + nr_mode);*/
        return nr_mode;
    }

    @Override
    public NRModePref getNRModePref() {
		/*if( MyDebug.LOG )
			Log.d(TAG, "nr_mode: " + nr_mode);*/
        //noinspection SwitchStatementWithTooFewBranches
        switch( nr_mode ) {
            case "preference_nr_mode_low_light":
                return NRModePref.NRMODE_LOW_LIGHT;
        }
        return NRModePref.NRMODE_NORMAL;
    }

    public void setAperture(float aperture) {
        this.aperture = aperture;
    }

    @Override
    public float getAperturePref() {
        return aperture;
    }


    /** Returns the current photo mode.
     *  Note, this always should return the true photo mode - if we're in video mode and taking a photo snapshot while
     *  video recording, the caller should override. We don't override here, as this preference may be used to affect how
     *  the CameraController is set up, and we don't always re-setup the camera when switching between photo and video modes.
     */
    public PhotoMode getPhotoMode() {
        return PhotoMode.Standard;
    }


    private ImageSaver.Request.ImageFormat getImageFormatPref() {
        switch( sharedPreferences.getString(PreferenceKeys.ImageFormatPreferenceKey, "preference_image_format_jpeg") ) {
            case "preference_image_format_webp":
                return ImageSaver.Request.ImageFormat.WEBP;
            case "preference_image_format_png":
                return ImageSaver.Request.ImageFormat.PNG;
            default:
                return ImageSaver.Request.ImageFormat.STD;
        }
    }

    /** Returns whether RAW is currently allowed, even if RAW is enabled in the preference (RAW
     *  isn't allowed for some photo modes, or in video mode, or when called from an intent).
     *  Note that this doesn't check whether RAW is supported by the camera.
     */
    public boolean isRawAllowed(PhotoMode photo_mode) {
        return false;
    }

    /** Return whether to capture JPEG, or RAW+JPEG.
     *  Note even if in RAW only mode, we still capture RAW+JPEG - the JPEG is needed for things like
     *  getting the bitmap for the thumbnail and pause preview option; we simply don't do any post-
     *  processing or saving on the JPEG.
     */
    @Override
    public RawPref getRawPref() {
        return RawPref.RAWPREF_JPEG_ONLY;
    }

    /** Whether RAW only mode is enabled.
     */
    public boolean isRawOnly() {
        PhotoMode photo_mode = getPhotoMode();
        return isRawOnly(photo_mode);
    }

    /** Use this instead of isRawOnly() if the photo mode is already known - useful to call e.g. from MainActivity.supportsDRO()
     *  without causing an infinite loop!
     */
    boolean isRawOnly(PhotoMode photo_mode) {
        if( isRawAllowed(photo_mode) ) {
            //noinspection SwitchStatementWithTooFewBranches
            switch( sharedPreferences.getString(PreferenceKeys.RawPreferenceKey, "preference_raw_no") ) {
                case "preference_raw_only":
                    return true;
            }
        }
        return false;
    }

    @Override
    public int getMaxRawImages() {
        return imageSaver.getMaxDNG();
    }

    @Override
    public boolean useCamera2FakeFlash() {
        return sharedPreferences.getBoolean(PreferenceKeys.Camera2FakeFlashPreferenceKey, false);
    }

    @Override
    public boolean useCamera2FastBurst() {
        return sharedPreferences.getBoolean(PreferenceKeys.Camera2FastBurstPreferenceKey, true);
    }

    @Override
    public boolean usePhotoVideoRecording() {
        // we only show the preference for Camera2 API (since there's no point disabling the feature for old API)
        return sharedPreferences.getBoolean(PreferenceKeys.Camera2PhotoVideoRecordingPreferenceKey, true);
    }

    @Override
    public boolean isPreviewInBackground() {
        return main_activity.isCameraInBackground();
    }

    @Override
    public boolean allowZoom() {
        return true;
    }

    @Override
    public boolean isTestAlwaysFocus() {
        if( MyDebug.LOG ) {
            Log.d(TAG, "isTestAlwaysFocus: " + main_activity.is_test);
        }
        return main_activity.is_test;
    }

    @Override
    public void cameraSetup() {
        main_activity.cameraSetup();

        // Need to cause drawPreview.updateSettings(), otherwise icons like HDR won't show after force-restart, because we only
        // know that HDR is supported after the camera is opened
        // Also needed for settings which update when switching between photo and video mode.
    }

    @Override
    public void onContinuousFocusMove(boolean start) {
        if( MyDebug.LOG )
            Log.d(TAG, "onContinuousFocusMove: " + start);

    }


    @Override
    public void touchEvent(MotionEvent event) {
        main_activity.getMainUI().closeExposureUI();
        if( main_activity.usingKitKatImmersiveMode() ) {
            main_activity.setImmersiveMode(false);
        }
    }

    @Override
    public void startingVideo() {
        if( sharedPreferences.getBoolean(PreferenceKeys.LockVideoPreferenceKey, false) ) {
            main_activity.lockScreen();
        }

        ImageButton view = main_activity.findViewById(R.id.take_photo);
        view.setImageResource(R.drawable.take_video_recording);
        view.setContentDescription( getContext().getResources().getString(R.string.stop_video) );
        view.setTag(R.drawable.take_video_recording); // for testing
    }

    @Override
    public void startedVideo() {
        if( MyDebug.LOG )
            Log.d(TAG, "startedVideo()");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ) {
            if( !( main_activity.getMainUI().inImmersiveMode() && main_activity.usingKitKatImmersiveModeEverything() ) ) {
                View pauseVideoButton = main_activity.findViewById(R.id.pause_video);
                pauseVideoButton.setVisibility(View.VISIBLE);
            }
            main_activity.getMainUI().setPauseVideoContentDescription();
        }
        if( main_activity.getPreview().supportsPhotoVideoRecording() && this.usePhotoVideoRecording() ) {
            if( !( main_activity.getMainUI().inImmersiveMode() && main_activity.usingKitKatImmersiveModeEverything() ) ) {
                View takePhotoVideoButton = main_activity.findViewById(R.id.take_photo_when_video_recording);
                takePhotoVideoButton.setVisibility(View.VISIBLE);
            }
        }
        if( main_activity.getMainUI().isExposureUIOpen() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "need to update exposure UI for start video recording");
            // need to update the exposure UI when starting/stopping video recording, to remove/add
            // ability to switch between auto and manual
        }
        final int video_method = this.createOutputVideoMethod();
        boolean dategeo_subtitles = getVideoSubtitlePref().equals("preference_video_subtitle_yes");
        if( dategeo_subtitles && video_method != ApplicationInterface.VIDEOMETHOD_URI ) {
            final String preference_stamp_dateformat = this.getStampDateFormatPref();
            final String preference_stamp_timeformat = this.getStampTimeFormatPref();
            final String preference_stamp_gpsformat = this.getStampGPSFormatPref();
            final String preference_units_distance = this.getUnitsDistancePref();
            final String preference_stamp_geo_address = this.getStampGeoAddressPref();
            final boolean store_location = getGeotaggingPref();
            final boolean store_geo_direction = getGeodirectionPref();
            class SubtitleVideoTimerTask extends TimerTask {
                // need to keep a reference to pfd_saf for as long as writer, to avoid getting garbage collected - see https://sourceforge.net/p/opencamera/tickets/417/
                @SuppressWarnings("FieldCanBeLocal")
                private ParcelFileDescriptor pfd_saf;
                private OutputStreamWriter writer;
                private int count = 1;
                private long min_video_time_from = 0;

                private String getSubtitleFilename(String video_filename) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "getSubtitleFilename");
                    int indx = video_filename.indexOf('.');
                    if( indx != -1 ) {
                        video_filename = video_filename.substring(0, indx);
                    }
                    video_filename = video_filename + ".srt";
                    if( MyDebug.LOG )
                        Log.d(TAG, "return filename: " + video_filename);
                    return video_filename;
                }

                public void run() {
                    if( MyDebug.LOG )
                        Log.d(TAG, "SubtitleVideoTimerTask run");
                    long video_time = main_activity.getPreview().getVideoTime();
                    if( !main_activity.getPreview().isVideoRecording() ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "no longer video recording");
                        return;
                    }
                    if( main_activity.getPreview().isVideoRecordingPaused() ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "video recording is paused");
                        return;
                    }
                    Date current_date = new Date();
                    Calendar current_calendar = Calendar.getInstance();
                    int offset_ms = current_calendar.get(Calendar.MILLISECOND);
                    // We subtract an offset, because if the current time is say 00:00:03.425 and the video has been recording for
                    // 1s, we instead need to record the video time when it became 00:00:03.000. This does mean that the GPS
                    // location is going to be off by up to 1s, but that should be less noticeable than the clock being off.
                    if( MyDebug.LOG ) {
                        Log.d(TAG, "count: " + count);
                        Log.d(TAG, "offset_ms: " + offset_ms);
                        Log.d(TAG, "video_time: " + video_time);
                    }
                    String date_stamp = TextFormatter.getDateString(preference_stamp_dateformat, current_date);
                    String time_stamp = TextFormatter.getTimeString(preference_stamp_timeformat, current_date);
                    Location location = store_location ? getLocation() : null;
                    double geo_direction = store_geo_direction && main_activity.getPreview().hasGeoDirection() ? main_activity.getPreview().getGeoDirection() : 0.0;
                    String gps_stamp = main_activity.getTextFormatter().getGPSString(preference_stamp_gpsformat, preference_units_distance, store_location && location!=null, location, store_geo_direction && main_activity.getPreview().hasGeoDirection(), geo_direction);
                    if( MyDebug.LOG ) {
                        Log.d(TAG, "date_stamp: " + date_stamp);
                        Log.d(TAG, "time_stamp: " + time_stamp);
                        // don't log gps_stamp, in case of privacy!
                    }

                    String datetime_stamp = "";
                    if( date_stamp.length() > 0 )
                        datetime_stamp += date_stamp;
                    if( time_stamp.length() > 0 ) {
                        if( datetime_stamp.length() > 0 )
                            datetime_stamp += " ";
                        datetime_stamp += time_stamp;
                    }

                    // build subtitles
                    StringBuilder subtitles = new StringBuilder();
                    if( datetime_stamp.length() > 0 )
                        subtitles.append(datetime_stamp).append("\n");

                    if( gps_stamp.length() > 0 ) {
                        Address address = null;
                        if( store_location && !preference_stamp_geo_address.equals("preference_stamp_geo_address_no") ) {
                            // try to find an address
                            if( Geocoder.isPresent() ) {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "geocoder is present");
                                Geocoder geocoder = new Geocoder(main_activity, Locale.getDefault());
                                try {
                                    List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                                    if( addresses != null && addresses.size() > 0 ) {
                                        address = addresses.get(0);
                                        // don't log address, in case of privacy!
                                        if( MyDebug.LOG ) {
                                            Log.d(TAG, "max line index: " + address.getMaxAddressLineIndex());
                                        }
                                    }
                                }
                                catch(Exception e) {
                                    Log.e(TAG, "failed to read from geocoder");
                                    e.printStackTrace();
                                }
                            }
                            else {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "geocoder not present");
                            }
                        }

                        if( address != null ) {
                            for(int i=0;i<=address.getMaxAddressLineIndex();i++) {
                                // write in forward order
                                String addressLine = address.getAddressLine(i);
                                subtitles.append(addressLine).append("\n");
                            }
                        }

                        if( address == null || preference_stamp_geo_address.equals("preference_stamp_geo_address_both") ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "display gps coords");
                            subtitles.append(gps_stamp).append("\n");
                        }
                        else if( store_geo_direction ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "not displaying gps coords, but need to display geo direction");
                            gps_stamp = main_activity.getTextFormatter().getGPSString(preference_stamp_gpsformat, preference_units_distance, false, null, store_geo_direction && main_activity.getPreview().hasGeoDirection(), geo_direction);
                            if( gps_stamp.length() > 0 ) {
                                // don't log gps_stamp, in case of privacy!
                                subtitles.append(gps_stamp).append("\n");
                            }
                        }
                    }

                    if( subtitles.length() == 0 ) {
                        return;
                    }
                    long video_time_from = video_time - offset_ms;
                    long video_time_to = video_time_from + 999;
                    // don't want to start from before 0; also need to keep track of min_video_time_from to avoid bug reported at
                    // https://forum.xda-developers.com/showpost.php?p=74827802&postcount=345 for pause video where we ended up
                    // with overlapping times when resuming
                    if( video_time_from < min_video_time_from )
                        video_time_from = min_video_time_from;
                    min_video_time_from = video_time_to + 1;
                    String subtitle_time_from = TextFormatter.formatTimeMS(video_time_from);
                    String subtitle_time_to = TextFormatter.formatTimeMS(video_time_to);
                    try {
                        synchronized( this ) {
                            if( writer == null ) {
                                if( video_method == ApplicationInterface.VIDEOMETHOD_FILE ) {
                                    String subtitle_filename = last_video_file.getAbsolutePath();
                                    subtitle_filename = getSubtitleFilename(subtitle_filename);
                                    writer = new FileWriter(subtitle_filename);
                                }

                            }
                            if( writer != null ) {
                                writer.append(Integer.toString(count));
                                writer.append('\n');
                                writer.append(subtitle_time_from);
                                writer.append(" --> ");
                                writer.append(subtitle_time_to);
                                writer.append('\n');
                                writer.append(subtitles.toString()); // subtitles should include the '\n' at the end
                                writer.append('\n'); // additional newline to indicate end of this subtitle
                                writer.flush();
                                // n.b., we flush rather than closing/reopening the writer each time, as appending doesn't seem to work with storage access framework
                            }
                        }
                        count++;
                    }
                    catch(IOException e) {
                        if( MyDebug.LOG )
                            Log.e(TAG, "SubtitleVideoTimerTask failed to create or write");
                        e.printStackTrace();
                    }
                    if( MyDebug.LOG )
                        Log.d(TAG, "SubtitleVideoTimerTask exit");
                }

                public boolean cancel() {
                    if( MyDebug.LOG )
                        Log.d(TAG, "SubtitleVideoTimerTask cancel");
                    synchronized( this ) {
                        if( writer != null ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "close writer");
                            try {
                                writer.close();
                            }
                            catch(IOException e) {
                                e.printStackTrace();
                            }
                            writer = null;
                        }
                    }
                    return super.cancel();
                }
            }
            subtitleVideoTimer.schedule(subtitleVideoTimerTask = new SubtitleVideoTimerTask(), 0, 1000);
        }
    }

    @Override
    public void stoppingVideo() {
        if( MyDebug.LOG )
            Log.d(TAG, "stoppingVideo()");
        main_activity.unlockScreen();
        ImageButton view = main_activity.findViewById(R.id.take_photo);
        view.setImageResource(R.drawable.take_video_selector);
        view.setContentDescription( getContext().getResources().getString(R.string.start_video) );
        view.setTag(R.drawable.take_video_selector); // for testing
    }

    @Override
    public void stoppedVideo(final int video_method, final Uri uri, final String filename) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "stoppedVideo");
            Log.d(TAG, "video_method " + video_method);
            Log.d(TAG, "uri " + uri);
            Log.d(TAG, "filename " + filename);
        }
        View pauseVideoButton = main_activity.findViewById(R.id.pause_video);
        pauseVideoButton.setVisibility(View.GONE);
        View takePhotoVideoButton = main_activity.findViewById(R.id.take_photo_when_video_recording);
        takePhotoVideoButton.setVisibility(View.GONE);
        main_activity.getMainUI().setPauseVideoContentDescription(); // just to be safe
        if( main_activity.getMainUI().isExposureUIOpen() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "need to update exposure UI for stop video recording");
            // need to update the exposure UI when starting/stopping video recording, to remove/add
            // ability to switch between auto and manual
        }
        if( subtitleVideoTimerTask != null ) {
            subtitleVideoTimerTask.cancel();
            subtitleVideoTimerTask = null;
        }

        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {

        }
    }


    @Override
    public void onVideoInfo(int what, int extra) {
        // we don't show a toast for MEDIA_RECORDER_INFO_MAX_DURATION_REACHED - conflicts with "n repeats to go" toast from Preview
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED ) {
            if( MyDebug.LOG )
                Log.d(TAG, "next output file started");
            int message_id = R.string.video_max_filesize;
            main_activity.getPreview().showToast(null, message_id);
        }
        else if( what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ) {
            if( MyDebug.LOG )
                Log.d(TAG, "max filesize reached");
            int message_id = R.string.video_max_filesize;
            main_activity.getPreview().showToast(null, message_id);
        }
        // in versions 1.24 and 1.24, there was a bug where we had "info_" for onVideoError and "error_" for onVideoInfo!
        // fixed in 1.25; also was correct for 1.23 and earlier
        String debug_value = "info_" + what + "_" + extra;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("last_video_error", debug_value);
        editor.apply();
    }

    @Override
    public void onFailedStartPreview() {
        main_activity.getPreview().showToast(null, R.string.failed_to_start_camera_preview);
    }

    @Override
    public void onCameraError() {
        main_activity.getPreview().showToast(null, R.string.camera_error);
    }

    @Override
    public void onPhotoError() {
        main_activity.getPreview().showToast(null, R.string.failed_to_take_picture);
    }

    @Override
    public void onVideoError(int what, int extra) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "onVideoError: " + what + " extra: " + extra);
        }
        int message_id = R.string.video_error_unknown;
        if( what == MediaRecorder.MEDIA_ERROR_SERVER_DIED  ) {
            if( MyDebug.LOG )
                Log.d(TAG, "error: server died");
            message_id = R.string.video_error_server_died;
        }
        main_activity.getPreview().showToast(null, message_id);
        // in versions 1.24 and 1.24, there was a bug where we had "info_" for onVideoError and "error_" for onVideoInfo!
        // fixed in 1.25; also was correct for 1.23 and earlier
        String debug_value = "error_" + what + "_" + extra;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("last_video_error", debug_value);
        editor.apply();
    }

    @Override
    public void onVideoRecordStartError(VideoProfile profile) {
        if( MyDebug.LOG )
            Log.d(TAG, "onVideoRecordStartError");
        String error_message;
        String features = main_activity.getPreview().getErrorFeatures(profile);
        if( features.length() > 0 ) {
            error_message = getContext().getResources().getString(R.string.sorry) + ", " + features + " " + getContext().getResources().getString(R.string.not_supported);
        }
        else {
            error_message = getContext().getResources().getString(R.string.failed_to_record_video);
        }
        main_activity.getPreview().showToast(null, error_message);
        ImageButton view = main_activity.findViewById(R.id.take_photo);
        view.setImageResource(R.drawable.take_video_selector);
        view.setContentDescription( getContext().getResources().getString(R.string.start_video) );
        view.setTag(R.drawable.take_video_selector); // for testing
    }

    @Override
    public void onVideoRecordStopError(VideoProfile profile) {
        if( MyDebug.LOG )
            Log.d(TAG, "onVideoRecordStopError");
        //main_activity.getPreview().showToast(null, R.string.failed_to_record_video);
        String features = main_activity.getPreview().getErrorFeatures(profile);
        String error_message = getContext().getResources().getString(R.string.video_may_be_corrupted);
        if( features.length() > 0 ) {
            error_message += ", " + features + " " + getContext().getResources().getString(R.string.not_supported);
        }
        main_activity.getPreview().showToast(null, error_message);
    }

    @Override
    public void onFailedReconnectError() {
        main_activity.getPreview().showToast(null, R.string.failed_to_reconnect_camera);
    }

    @Override
    public void onFailedCreateVideoFileError() {
        main_activity.getPreview().showToast(null, R.string.failed_to_save_video);
        ImageButton view = main_activity.findViewById(R.id.take_photo);
        view.setImageResource(R.drawable.take_video_selector);
        view.setContentDescription( getContext().getResources().getString(R.string.start_video) );
        view.setTag(R.drawable.take_video_selector); // for testing
    }

    @Override
    public void hasPausedPreview(boolean paused) {
        if( MyDebug.LOG )
            Log.d(TAG, "hasPausedPreview: " + paused);
        View shareButton = main_activity.findViewById(R.id.share);
        View trashButton = main_activity.findViewById(R.id.trash);
        if( paused ) {
            shareButton.setVisibility(View.VISIBLE);
            trashButton.setVisibility(View.VISIBLE);
        }
        else {
            shareButton.setVisibility(View.GONE);
            trashButton.setVisibility(View.GONE);
        }
    }

    @Override
    public void cameraInOperation(boolean in_operation, boolean is_video) {
        if( MyDebug.LOG )
            Log.d(TAG, "cameraInOperation: " + in_operation);
        if( !in_operation && used_front_screen_flash ) {
            main_activity.setBrightnessForCamera(false); // ensure screen brightness matches user preference, after using front screen flash
            used_front_screen_flash = false;
        }
        main_activity.getMainUI().showGUI(!in_operation, is_video);
    }

    @Override
    public void turnFrontScreenFlashOn() {
        if( MyDebug.LOG )
            Log.d(TAG, "turnFrontScreenFlashOn");
        used_front_screen_flash = true;
        main_activity.setBrightnessForCamera(true); // ensure we have max screen brightness, even if user preference not set for max brightness
    }

    @Override
    public void onCaptureStarted() {
        if( MyDebug.LOG )
            Log.d(TAG, "onCaptureStarted");
        n_capture_images = 0;
        n_capture_images_raw = 0;
    }

    @Override
    public void onPictureCompleted() {
        if( MyDebug.LOG )
            Log.d(TAG, "onPictureCompleted");

        PhotoMode photo_mode = getPhotoMode();
        if( main_activity.getPreview().isVideo() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "snapshot mode");
            // must be in photo snapshot while recording video mode, only support standard photo mode
            photo_mode = PhotoMode.Standard;
        }

    }

    @Override
    public void cameraClosed() {
        if( MyDebug.LOG )
            Log.d(TAG, "cameraClosed");
        main_activity.getMainUI().closeExposureUI();

    }

    void updateThumbnail(Bitmap thumbnail, boolean is_video) {
        if( MyDebug.LOG )
            Log.d(TAG, "updateThumbnail");
        main_activity.updateGalleryIcon(thumbnail);

    }

    @Override
    public void timerBeep(long remaining_time) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "timerBeep()");
            Log.d(TAG, "remaining_time: " + remaining_time);
        }
        if( sharedPreferences.getBoolean(PreferenceKeys.TimerBeepPreferenceKey, true) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "play beep!");
            boolean is_last = remaining_time <= 1000;
        }
        if( sharedPreferences.getBoolean(PreferenceKeys.TimerSpeakPreferenceKey, false) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "speak countdown!");
            int remaining_time_s = (int)(remaining_time/1000);
            if( remaining_time_s <= 60 )
                main_activity.speak("" + remaining_time_s);
        }
    }

    @Override
    public void multitouchZoom(int new_zoom) {
        main_activity.getMainUI().setSeekbarZoom(new_zoom);
    }

    /** Switch to the first available camera that is front or back facing as desired.
     * @param front_facing Whether to switch to a front or back facing camera.
     */
    void switchToCamera(boolean front_facing) {
        if( MyDebug.LOG )
            Log.d(TAG, "switchToCamera: " + front_facing);
        int n_cameras = main_activity.getPreview().getCameraControllerManager().getNumberOfCameras();
        CameraController.Facing want_facing = front_facing ? CameraController.Facing.FACING_FRONT : CameraController.Facing.FACING_BACK;
        for(int i=0;i<n_cameras;i++) {
            if( main_activity.getPreview().getCameraControllerManager().getFacing(i) == want_facing ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "found desired camera: " + i);
                this.setCameraIdPref(i);
                break;
            }
        }
    }

    /* Note that the cameraId is still valid if this returns false, it just means that a cameraId hasn't be explicitly set yet.
     */
    boolean hasSetCameraId() {
        return has_set_cameraId;
    }

    @Override
    public void setCameraIdPref(int cameraId) {
        this.has_set_cameraId = true;
        this.cameraId = cameraId;
    }

    @Override
    public void setFlashPref(String flash_value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.getFlashPreferenceKey(cameraId), flash_value);
        editor.apply();
    }

    @Override
    public void setFocusPref(String focus_value, boolean is_video) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.getFocusPreferenceKey(cameraId, is_video), focus_value);
        editor.apply();
    }

    @Override
    public void setVideoPref(boolean is_video) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.IsVideoPreferenceKey, is_video);
        editor.apply();
    }

    @Override
    public void setColorEffectPref(String color_effect) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.ColorEffectPreferenceKey, color_effect);
        editor.apply();
    }

    @Override
    public void clearColorEffectPref() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(PreferenceKeys.ColorEffectPreferenceKey);
        editor.apply();
    }

    @Override
    public void setWhiteBalancePref(String white_balance) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.WhiteBalancePreferenceKey, white_balance);
        editor.apply();
    }

    @Override
    public void clearWhiteBalancePref() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(PreferenceKeys.WhiteBalancePreferenceKey);
        editor.apply();
    }

    @Override
    public void setWhiteBalanceTemperaturePref(int white_balance_temperature) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(PreferenceKeys.WhiteBalanceTemperaturePreferenceKey, white_balance_temperature);
        editor.apply();
    }

    @Override
    public void setISOPref(String iso) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.ISOPreferenceKey, iso);
        editor.apply();
    }

    @Override
    public void clearISOPref() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(PreferenceKeys.ISOPreferenceKey);
        editor.apply();
    }

    @Override
    public void setExposureCompensationPref(int exposure) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.ExposurePreferenceKey, "" + exposure);
        editor.apply();
    }

    @Override
    public void clearExposureCompensationPref() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(PreferenceKeys.ExposurePreferenceKey);
        editor.apply();
    }

    @Override
    public void setCameraResolutionPref(int width, int height) {
        String resolution_value = width + " " + height;
        if( MyDebug.LOG ) {
            Log.d(TAG, "save new resolution_value: " + resolution_value);
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.getResolutionPreferenceKey(cameraId), resolution_value);
        editor.apply();
    }

    @Override
    public void setVideoQualityPref(String video_quality) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.getVideoQualityPreferenceKey(cameraId, fpsIsHighSpeed()), video_quality);
        editor.apply();
    }

    @Override
    public void setZoomPref(int zoom) {
        if( MyDebug.LOG )
            Log.d(TAG, "setZoomPref: " + zoom);
        this.zoom_factor = zoom;
    }


    private int getStampFontColor() {
        String color = sharedPreferences.getString(PreferenceKeys.StampFontColorPreferenceKey, "#ffffff");
        return Color.parseColor(color);
    }

    /** Should be called to reset parameters which aren't expected to be saved (e.g., resetting zoom when application is paused,
     *  when switching between photo/video modes, or switching cameras).
     */
    void reset(boolean switched_camera) {
        if( MyDebug.LOG )
            Log.d(TAG, "reset");
        if( switched_camera ) {
            // aperture is reset when switching camera, but not when application is paused or switching between photo/video etc
            this.aperture = aperture_default;
        }
        this.zoom_factor = 0;
    }

    @Override
    public void onDrawPreview(Canvas canvas) {
        if( !main_activity.isCameraInBackground() ) {
            // no point drawing when in background (e.g., settings open)
        }
    }

    public enum Alignment {
        ALIGNMENT_TOP,
        ALIGNMENT_CENTRE,
        ALIGNMENT_BOTTOM
    }

    public enum Shadow {
        SHADOW_NONE,
        SHADOW_OUTLINE,
        SHADOW_BACKGROUND
    }

    public int drawTextWithBackground(Canvas canvas, Paint paint, String text, int foreground, int background, int location_x, int location_y) {
        return drawTextWithBackground(canvas, paint, text, foreground, background, location_x, location_y, Alignment.ALIGNMENT_BOTTOM);
    }

    public int drawTextWithBackground(Canvas canvas, Paint paint, String text, int foreground, int background, int location_x, int location_y, Alignment alignment_y) {
        return drawTextWithBackground(canvas, paint, text, foreground, background, location_x, location_y, alignment_y, null, Shadow.SHADOW_OUTLINE);
    }

    public int drawTextWithBackground(Canvas canvas, Paint paint, String text, int foreground, int background, int location_x, int location_y, Alignment alignment_y, String ybounds_text, Shadow shadow) {
        return drawTextWithBackground(canvas, paint, text, foreground, background, location_x, location_y, alignment_y, null, shadow, null);
    }

    public int drawTextWithBackground(Canvas canvas, Paint paint, String text, int foreground, int background, int location_x, int location_y, Alignment alignment_y, String ybounds_text, Shadow shadow, Rect bounds) {
        final float scale = getContext().getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        paint.setAlpha(64);
        if( bounds != null ) {
            text_bounds.set(bounds);
        }
        else {
            int alt_height = 0;
            if( ybounds_text != null ) {
                paint.getTextBounds(ybounds_text, 0, ybounds_text.length(), text_bounds);
                alt_height = text_bounds.bottom - text_bounds.top;
            }
            paint.getTextBounds(text, 0, text.length(), text_bounds);
            if( ybounds_text != null ) {
                text_bounds.bottom = text_bounds.top + alt_height;
            }
        }
        final int padding = (int) (2 * scale + 0.5f); // convert dps to pixels
        if( paint.getTextAlign() == Paint.Align.RIGHT || paint.getTextAlign() == Paint.Align.CENTER ) {
            float width = paint.measureText(text); // n.b., need to use measureText rather than getTextBounds here
			/*if( MyDebug.LOG )
				Log.d(TAG, "width: " + width);*/
            if( paint.getTextAlign() == Paint.Align.CENTER )
                width /= 2.0f;
            text_bounds.left -= width;
            text_bounds.right -= width;
        }
		/*if( MyDebug.LOG )
			Log.d(TAG, "text_bounds left-right: " + text_bounds.left + " , " + text_bounds.right);*/
        text_bounds.left += location_x - padding;
        text_bounds.right += location_x + padding;
        // unclear why we need the offset of -1, but need this to align properly on Galaxy Nexus at least
        int top_y_diff = - text_bounds.top + padding - 1;
        if( alignment_y == Alignment.ALIGNMENT_TOP ) {
            int height = text_bounds.bottom - text_bounds.top + 2*padding;
            text_bounds.top = location_y - 1;
            text_bounds.bottom = text_bounds.top + height;
            location_y += top_y_diff;
        }
        else if( alignment_y == Alignment.ALIGNMENT_CENTRE ) {
            int height = text_bounds.bottom - text_bounds.top + 2*padding;
            //int y_diff = - text_bounds.top + padding - 1;
            text_bounds.top = (int)(0.5 * ( (location_y - 1) + (text_bounds.top + location_y - padding) )); // average of ALIGNMENT_TOP and ALIGNMENT_BOTTOM
            text_bounds.bottom = text_bounds.top + height;
            location_y += (int)(0.5*top_y_diff); // average of ALIGNMENT_TOP and ALIGNMENT_BOTTOM
        }
        else {
            text_bounds.top += location_y - padding;
            text_bounds.bottom += location_y + padding;
        }
        if( shadow == Shadow.SHADOW_BACKGROUND ) {
            paint.setColor(background);
            paint.setAlpha(64);
            canvas.drawRect(text_bounds, paint);
            paint.setAlpha(255);
        }
        paint.setColor(foreground);
        canvas.drawText(text, location_x, location_y, paint);
        if( shadow == Shadow.SHADOW_OUTLINE ) {
            paint.setColor(background);
            paint.setStyle(Paint.Style.STROKE);
            float current_stroke_width = paint.getStrokeWidth();
            paint.setStrokeWidth(1);
            canvas.drawText(text, location_x, location_y, paint);
            paint.setStyle(Paint.Style.FILL); // set back to default
            paint.setStrokeWidth(current_stroke_width); // reset
        }
        return text_bounds.bottom - text_bounds.top;
    }

    private boolean saveInBackground(boolean image_capture_intent) {
        boolean do_in_background = true;
		/*if( !sharedPreferences.getBoolean(PreferenceKeys.BackgroundPhotoSavingPreferenceKey, true) )
			do_in_background = false;
		else*/ if( image_capture_intent )
            do_in_background = false;
        else if( getPausePreviewPref() )
            do_in_background = false;
        return do_in_background;
    }

    boolean isImageCaptureIntent() {
        boolean image_capture_intent = false;
        String action = main_activity.getIntent().getAction();
        if( MediaStore.ACTION_IMAGE_CAPTURE.equals(action) || MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from image capture intent");
            image_capture_intent = true;
        }
        return image_capture_intent;
    }


    /** Saves the supplied image(s)
     * @param save_expo If the photo mode is one where multiple images are saved to a single
     *                  resultant image, this indicates if all the base images should also be saved
     *                  as separate images.
     * @param images The set of images.
     * @param current_date The current date/time stamp for the images.
     * @return Whether saving was successful.
     */
    private boolean saveImage(boolean save_expo, List<byte []> images, Date current_date) {
        if( MyDebug.LOG )
            Log.d(TAG, "saveImage");

        System.gc();

        boolean image_capture_intent = isImageCaptureIntent();
        Uri image_capture_intent_uri = null;
        if( image_capture_intent ) {
            if( MyDebug.LOG )
                Log.d(TAG, "from image capture intent");
            Bundle myExtras = main_activity.getIntent().getExtras();
            if( myExtras != null ) {
                image_capture_intent_uri = myExtras.getParcelable(MediaStore.EXTRA_OUTPUT);
                if( MyDebug.LOG )
                    Log.d(TAG, "save to: " + image_capture_intent_uri);
            }
        }

        ImageSaver.Request.ImageFormat image_format = getImageFormatPref();
        boolean store_ypr = sharedPreferences.getBoolean(PreferenceKeys.AddYPRToComments, false) &&
                main_activity.getPreview().hasLevelAngle() &&
                main_activity.getPreview().hasPitchAngle() &&
                main_activity.getPreview().hasGeoDirection();
        if( MyDebug.LOG ) {
            Log.d(TAG, "store_ypr: " + store_ypr);
            Log.d(TAG, "has level angle: " + main_activity.getPreview().hasLevelAngle());
            Log.d(TAG, "has pitch angle: " + main_activity.getPreview().hasPitchAngle());
            Log.d(TAG, "has geo direction: " + main_activity.getPreview().hasGeoDirection());
        }
        int image_quality = getSaveImageQualityPref();
        if( MyDebug.LOG )
            Log.d(TAG, "image_quality: " + image_quality);
        boolean do_auto_stabilise = getAutoStabilisePref() && main_activity.getPreview().hasLevelAngleStable();
        double level_angle = (main_activity.getPreview().hasLevelAngle()) ? main_activity.getPreview().getLevelAngle() : 0.0;
        double pitch_angle = (main_activity.getPreview().hasPitchAngle()) ? main_activity.getPreview().getPitchAngle() : 0.0;
        if( do_auto_stabilise && main_activity.test_have_angle )
            level_angle = main_activity.test_angle;
        if( do_auto_stabilise && main_activity.test_low_memory )
            level_angle = 45.0;
        // I have received crashes where camera_controller was null - could perhaps happen if this thread was running just as the camera is closing?
        boolean is_front_facing = main_activity.getPreview().getCameraController() != null && (main_activity.getPreview().getCameraController().getFacing() == CameraController.Facing.FACING_FRONT);
        boolean mirror = is_front_facing && sharedPreferences.getString(PreferenceKeys.FrontCameraMirrorKey, "preference_front_camera_mirror_no").equals("preference_front_camera_mirror_photo");
        String preference_stamp = this.getStampPref();
        String preference_textstamp = this.getTextStampPref();
        int font_size = getTextStampFontSizePref();
        int color = getStampFontColor();
        String pref_style = sharedPreferences.getString(PreferenceKeys.StampStyleKey, "preference_stamp_style_shadowed");
        String preference_stamp_dateformat = this.getStampDateFormatPref();
        String preference_stamp_timeformat = this.getStampTimeFormatPref();
        String preference_stamp_gpsformat = this.getStampGPSFormatPref();
        String preference_stamp_geo_address = this.getStampGeoAddressPref();
        String preference_units_distance = this.getUnitsDistancePref();
        boolean panorama_crop = sharedPreferences.getString(PreferenceKeys.PanoramaCropPreferenceKey, "preference_panorama_crop_on").equals("preference_panorama_crop_on");
        boolean store_location = getGeotaggingPref() && getLocation() != null;
        Location location = store_location ? getLocation() : null;
        boolean store_geo_direction = main_activity.getPreview().hasGeoDirection() && getGeodirectionPref();
        double geo_direction = main_activity.getPreview().hasGeoDirection() ? main_activity.getPreview().getGeoDirection() : 0.0;
        String custom_tag_artist = sharedPreferences.getString(PreferenceKeys.ExifArtistPreferenceKey, "");
        String custom_tag_copyright = sharedPreferences.getString(PreferenceKeys.ExifCopyrightPreferenceKey, "");
        String preference_hdr_contrast_enhancement = sharedPreferences.getString(PreferenceKeys.HDRContrastEnhancementPreferenceKey, "preference_hdr_contrast_enhancement_smart");

        int iso = 800; // default value if we can't get ISO
        long exposure_time = 1000000000L/30; // default value if we can't get shutter speed
        float zoom_factor = 1.0f;
        if( main_activity.getPreview().getCameraController() != null ) {
            if( main_activity.getPreview().getCameraController().captureResultHasIso() ) {
                iso = main_activity.getPreview().getCameraController().captureResultIso();
                if( MyDebug.LOG )
                    Log.d(TAG, "iso: " + iso);
            }
            if( main_activity.getPreview().getCameraController().captureResultHasExposureTime() ) {
                exposure_time = main_activity.getPreview().getCameraController().captureResultExposureTime();
                if( MyDebug.LOG )
                    Log.d(TAG, "exposure_time: " + exposure_time);
            }

            zoom_factor = main_activity.getPreview().getZoomRatio();
        }

        boolean has_thumbnail_animation = getThumbnailAnimationPref();

        boolean do_in_background = saveInBackground(image_capture_intent);

        String ghost_image_pref = sharedPreferences.getString(PreferenceKeys.GhostImagePreferenceKey, "preference_ghost_image_off");

        int sample_factor = 1;
        if( !this.getPausePreviewPref() && !ghost_image_pref.equals("preference_ghost_image_last") ) {
            // if pausing the preview, we use the thumbnail also for the preview, so don't downsample
            // similarly for ghosting last image
            // otherwise, we can downsample by 4 to increase performance, without noticeable loss in visual quality (even for the thumbnail animation)
            sample_factor *= 4;
            if( !has_thumbnail_animation ) {
                // can use even lower resolution if we don't have the thumbnail animation
                sample_factor *= 4;
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "sample_factor: " + sample_factor);

        boolean success;
        PhotoMode photo_mode = getPhotoMode();
        if( main_activity.getPreview().isVideo() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "snapshot mode");
            // must be in photo snapshot while recording video mode, only support standard photo mode
            photo_mode = PhotoMode.Standard;
        }


         {
            boolean is_hdr = false;
            boolean force_suffix = false;
            success = imageSaver.saveImageJpeg(do_in_background, is_hdr,
                    force_suffix,
                    // N.B., n_capture_images will be 1 for first image, not 0, so subtract 1 so we start off from _0.
                    // (It wouldn't be a huge problem if we did start from _1, but it would be inconsistent with the naming
                    // of images where images.size() > 1 (e.g., expo bracketing mode) where we also start from _0.)
                    force_suffix ? (n_capture_images-1) : 0,
                    save_expo, images,
                    image_capture_intent, image_capture_intent_uri,
                    true,
                    image_format, image_quality,
                    do_auto_stabilise, level_angle,
                    is_front_facing,
                    mirror,
                    current_date,
                    preference_hdr_contrast_enhancement,
                    iso,
                    exposure_time,
                    zoom_factor,
                    preference_stamp, preference_textstamp, font_size, color, pref_style, preference_stamp_dateformat, preference_stamp_timeformat, preference_stamp_gpsformat, preference_stamp_geo_address, preference_units_distance,
                    false, // panorama doesn't use this codepath
                    store_location, location, store_geo_direction, geo_direction,
                    pitch_angle, store_ypr,
                    custom_tag_artist, custom_tag_copyright,
                    sample_factor);
        }

        if( MyDebug.LOG )
            Log.d(TAG, "saveImage complete, success: " + success);

        return success;
    }

    @Override
    public boolean onPictureTaken(byte [] data, Date current_date) {
        if( MyDebug.LOG )
            Log.d(TAG, "onPictureTaken");

        n_capture_images++;
        if( MyDebug.LOG )
            Log.d(TAG, "n_capture_images is now " + n_capture_images);

        List<byte []> images = new ArrayList<>();
        images.add(data);

        boolean success = saveImage(false, images, current_date);

        if( MyDebug.LOG )
            Log.d(TAG, "onPictureTaken complete, success: " + success);

        return success;
    }




    boolean hasThumbnailAnimation() {
        return false;
    }

    public boolean test_set_available_memory = false;
    public long test_available_memory = 0;
}
