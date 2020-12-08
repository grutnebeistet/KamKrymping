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


    /** getImageQualityPref() returns the image quality used for the Camera Controller for taking a
     *  photo - in some cases, we may set that to a higher value, then perform processing on the
     *  resultant JPEG before resaving. This method returns the image quality setting to be used for
     *  saving the final image (as specified by the user).
     */
    private int getSaveImageQualityPref() { return 90;

    }

    @Override
    public int getImageQualityPref() {

        if( getImageFormatPref() != ImageSaver.Request.ImageFormat.STD )
            return 100;

        return getSaveImageQualityPref();
    }


    /** Returns whether the current fps preference is one that requires a "high speed" video size/
     *  frame rate.
     */
    public boolean fpsIsHighSpeed() {
        return main_activity.getPreview().fpsIsHighSpeed(getVideoFPSPref());
    }

    @Override
    public String getVideoQualityPref() {
        List<String> video_quality = main_activity.getPreview().getVideoQualityHander().getSupportedVideoQuality();
        return video_quality.get(0);

    }

    @Override
    public String getVideoFPSPref() { return "default"; }



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
    public boolean getPausePreviewPref() {
        if( main_activity.getPreview().isVideoRecording() ) {
            // don't pause preview when taking photos while recording video!
            return false;
        }
        return true; //sharedPreferences.getBoolean(PreferenceKeys.PausePreviewPreferenceKey, false);
    }



    @Override
    public int getZoomPref() {
        if( MyDebug.LOG )
            Log.d(TAG, "getZoomPref: " + zoom_factor);
        return zoom_factor;
    }


    @Override
    public boolean canTakeNewPhoto() {
        if( MyDebug.LOG )
            Log.d(TAG, "canTakeNewPhoto");

        // even if the queue isn't full, we may apply additional limits
        int n_images_to_save = imageSaver.getNImagesToSave();

        return true;
    }

    @Override
    public boolean imageQueueWouldBlock(int n_raw, int n_jpegs) {
        if( MyDebug.LOG )
            Log.d(TAG, "imageQueueWouldBlock");
        return imageSaver.queueWouldBlock(n_raw, n_jpegs);
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


    private ImageSaver.Request.ImageFormat getImageFormatPref() {return ImageSaver.Request.ImageFormat.STD; }

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


    @Override
    public int getMaxRawImages() {
        return imageSaver.getMaxDNG();
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
//        main_activity.getMainUI().closeExposureUI();
//        if( main_activity.usingKitKatImmersiveMode() ) {
//            main_activity.setImmersiveMode(false);
//        }
    }

    @Override
    public void startingVideo() {
//        if( sharedPreferences.getBoolean(PreferenceKeys.LockVideoPreferenceKey, false) ) {
//            main_activity.lockScreen();
//        }

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
        boolean store_ypr =
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
        boolean do_auto_stabilise = main_activity.getPreview().hasLevelAngleStable();
        double level_angle = (main_activity.getPreview().hasLevelAngle()) ? main_activity.getPreview().getLevelAngle() : 0.0;
        double pitch_angle = (main_activity.getPreview().hasPitchAngle()) ? main_activity.getPreview().getPitchAngle() : 0.0;
        if( do_auto_stabilise && main_activity.test_have_angle )
            level_angle = main_activity.test_angle;
        if( do_auto_stabilise && main_activity.test_low_memory )
            level_angle = 45.0;
        // I have received crashes where camera_controller was null - could perhaps happen if this thread was running just as the camera is closing?
        boolean is_front_facing = main_activity.getPreview().getCameraController() != null && (main_activity.getPreview().getCameraController().getFacing() == CameraController.Facing.FACING_FRONT);
        boolean mirror = false;

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



        boolean do_in_background = saveInBackground(image_capture_intent);



        int sample_factor = 1;

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
                    iso,
                    exposure_time,
                    zoom_factor,
                    pitch_angle, store_ypr,
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
}
