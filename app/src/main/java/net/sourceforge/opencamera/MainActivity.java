package net.sourceforge.opencamera;

import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.cameracontroller.CameraControllerManager;
import net.sourceforge.opencamera.cameracontroller.CameraControllerManager2;
import net.sourceforge.opencamera.preview.Preview;
import net.sourceforge.opencamera.preview.VideoProfile;
import net.sourceforge.opencamera.ui.MainUI;
import net.sourceforge.opencamera.ui.ManualSeekbars;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.renderscript.RenderScript;
import android.speech.tts.TextToSpeech;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.ZoomControls;

/** The main Activity for Open Camera.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private static int activity_count = 0;

    private boolean app_is_paused = true;

    private SensorManager mSensorManager;
    private Sensor mSensorAccelerometer;

    // components: always non-null (after onCreate())
    private SettingsManager settingsManager;
    private MainUI mainUI;
    private ManualSeekbars manualSeekbars;
    private MyApplicationInterface applicationInterface;
    private TextFormatter textFormatter;

    private SpeechControl speechControl;

    private net.sourceforge.opencamera.preview.Preview preview;
    private OrientationEventListener orientationEventListener;
    private int large_heap_memory;
    private boolean supports_auto_stabilise;
    private boolean supports_force_video_4k;
    private boolean supports_camera2;
    private boolean saf_dialog_from_preferences; // if a SAF dialog is opened, this records whether we opened it from the Preferences
    private boolean camera_in_background; // whether the camera is covered by a fragment/dialog (such as settings or folder picker)
    private GestureDetector gestureDetector;
    private boolean screen_is_locked; // whether screen is "locked" - this is Open Camera's own lock to guard against accidental presses, not the standard Android lock
    private final Map<Integer, Bitmap> preloaded_bitmap_resources = new Hashtable<>();
    private ValueAnimator gallery_save_anim;
    private boolean last_continuous_fast_burst; // whether the last photo operation was a continuous_fast_burst

    private TextToSpeech textToSpeech;
    private boolean textToSpeechSuccess;

    //private boolean ui_placement_right = true;

    private boolean want_no_limits; // whether we want to run with FLAG_LAYOUT_NO_LIMITS
    private boolean set_window_insets_listener; // whether we've enabled a setOnApplyWindowInsetsListener()
    private int navigation_gap;
    public static volatile boolean test_preview_want_no_limits; // test flag, if set to true then instead use test_preview_want_no_limits_value; needs to be static, as it needs to be set before activity is created to take effect
    public static volatile boolean test_preview_want_no_limits_value;

    // whether this is a multi-camera device (note, this isn't simply having more than 1 camera, but also having more than one with the same facing)
    // note that in most cases, code should check the MultiCamButtonPreferenceKey preference as well as the is_multi_cam flag,
    // this can be done via isMultiCamEnabled().
    private boolean is_multi_cam;
    // These lists are lists of camera IDs with the same "facing" (front, back or external).
    // Only initialised if is_multi_cam==true.
    private List<Integer> back_camera_ids;
    private List<Integer> front_camera_ids;
    private List<Integer> other_camera_ids;

    private final ToastBoxer switch_video_toast = new ToastBoxer();
    private final ToastBoxer screen_locked_toast = new ToastBoxer();
    private final ToastBoxer stamp_toast = new ToastBoxer();
    private final ToastBoxer changed_auto_stabilise_toast = new ToastBoxer();
    private final ToastBoxer white_balance_lock_toast = new ToastBoxer();
    private final ToastBoxer exposure_lock_toast = new ToastBoxer();
    private final ToastBoxer audio_control_toast = new ToastBoxer();
    private boolean block_startup_toast = false; // used when returning from Settings/Popup - if we're displaying a toast anyway, don't want to display the info toast too
    private String push_info_toast_text; // can be used to "push" extra text to the info text for showPhotoVideoToast()

    // application shortcuts:
    static private final String ACTION_SHORTCUT_CAMERA = "net.sourceforge.opencamera.SHORTCUT_CAMERA";
    static private final String ACTION_SHORTCUT_VIDEO = "net.sourceforge.opencamera.SHORTCUT_VIDEO";

    private static final int CHOOSE_SAVE_FOLDER_SAF_CODE = 42;
    private static final int CHOOSE_GHOST_IMAGE_SAF_CODE = 43;
    private static final int CHOOSE_LOAD_SETTINGS_SAF_CODE = 44;

    // for testing; must be volatile for test project reading the state
    // n.b., avoid using static, as static variables are shared between different instances of an application,
    // and won't be reset in subsequent tests in a suite!
    public boolean is_test; // whether called from OpenCamera.test testing
    public volatile Bitmap gallery_bitmap;
    public volatile boolean test_low_memory;
    public volatile boolean test_have_angle;
    public volatile float test_angle;
    public volatile String test_last_saved_image;
    public static boolean test_force_supports_camera2; // okay to be static, as this is set for an entire test suite
    public volatile String test_save_settings_file;

    private boolean has_notification;
    private final String CHANNEL_ID = "open_camera_channel";
    private final int image_saving_notification_id = 1;

    private static final float WATER_DENSITY_FRESHWATER = 1.0f;
    private static final float WATER_DENSITY_SALTWATER = 1.03f;
    private float mWaterDensity = 1.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "onCreate: " + this);
            debug_time = System.currentTimeMillis();
        }
        activity_count++;
        if( MyDebug.LOG )
            Log.d(TAG, "activity_count: " + activity_count);
        super.onCreate(savedInstanceState);

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
            // don't show orientation animations
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            layout.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
            getWindow().setAttributes(layout);
        }

        setContentView(R.layout.activity_main);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false); // initialise any unset preferences to their default values
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting default preference values: " + (System.currentTimeMillis() - debug_time));

        if( getIntent() != null && getIntent().getExtras() != null ) {
            // whether called from testing
            is_test = getIntent().getExtras().getBoolean("test_project");
            if( MyDebug.LOG )
                Log.d(TAG, "is_test: " + is_test);
        }
        /*if( getIntent() != null && getIntent().getExtras() != null ) {
            // whether called from Take Photo widget
            if( MyDebug.LOG )
                Log.d(TAG, "take_photo?: " + getIntent().getExtras().getBoolean(TakePhoto.TAKE_PHOTO));
        }*/
        if( MyDebug.LOG ) {
            // whether called from Take Photo widget
            Log.d(TAG, "take_photo?: " + TakePhoto.TAKE_PHOTO);
        }
        if( getIntent() != null && getIntent().getAction() != null ) {
            // invoked via the manifest shortcut?
            if( MyDebug.LOG )
                Log.d(TAG, "shortcut: " + getIntent().getAction());
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // determine whether we should support "auto stabilise" feature
        // risk of running out of memory on lower end devices, due to manipulation of large bitmaps
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if( MyDebug.LOG ) {
            Log.d(TAG, "standard max memory = " + activityManager.getMemoryClass() + "MB");
            Log.d(TAG, "large max memory = " + activityManager.getLargeMemoryClass() + "MB");
        }
        large_heap_memory = activityManager.getLargeMemoryClass();
        if( large_heap_memory >= 128 ) {
            supports_auto_stabilise = true;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "supports_auto_stabilise? " + supports_auto_stabilise);

        // hack to rule out phones unlikely to have 4K video, so no point even offering the option!
        // both S5 and Note 3 have 128MB standard and 512MB large heap (tested via Samsung RTL), as does Galaxy K Zoom
        // also added the check for having 128MB standard heap, to support modded LG G2, which has 128MB standard, 256MB large - see https://sourceforge.net/p/opencamera/tickets/9/
        if( activityManager.getMemoryClass() >= 128 || activityManager.getLargeMemoryClass() >= 512 ) {
            supports_force_video_4k = true;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "supports_force_video_4k? " + supports_force_video_4k);

        // set up components

        settingsManager = new SettingsManager(this);
        mainUI = new MainUI(this);
        manualSeekbars = new ManualSeekbars();
        applicationInterface = new MyApplicationInterface(this, savedInstanceState);
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating application interface: " + (System.currentTimeMillis() - debug_time));
        textFormatter = new TextFormatter(this);
        speechControl = new SpeechControl(this);

        // determine whether we support Camera2 API
        initCamera2Support();

        // set up window flags for normal operation
        setWindowFlagsForCamera();
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting window flags: " + (System.currentTimeMillis() - debug_time));


        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after updating folder history: " + (System.currentTimeMillis() - debug_time));

        // set up sensors
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

        // accelerometer sensor (for device orientation)
        if( mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "found accelerometer");
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "no support for accelerometer");
        }
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating accelerometer sensor: " + (System.currentTimeMillis() - debug_time));

        // magnetic sensor (for compass direction)

        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating magnetic sensor: " + (System.currentTimeMillis() - debug_time));

        // clear any seek bars (just in case??)
        mainUI.closeExposureUI();

        // set up the camera and its preview
        preview = new Preview(applicationInterface, ((ViewGroup) this.findViewById(R.id.preview)));
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating preview: " + (System.currentTimeMillis() - debug_time));

        // Setup multi-camera buttons (must be done after creating preview so we know which Camera API is being used,
        // and before initialising on-screen visibility).
        // We only allow the separate icon for switching cameras if:
        // - there are at least 2 types of "facing" camera, and
        // - there are at least 2 cameras with the same "facing".
        // If there are multiple cameras but all with different "facing", then the switch camera
        // icon is used to iterate over all cameras.
        // If there are more than two cameras, but all cameras have the same "facing, we still stick
        // with using the switch camera icon to iterate over all cameras.
        int n_cameras = preview.getCameraControllerManager().getNumberOfCameras();
        if( n_cameras > 2 ) {
            this.back_camera_ids = new ArrayList<>();
            this.front_camera_ids = new ArrayList<>();
            this.other_camera_ids = new ArrayList<>();
            for(int i=0;i<n_cameras;i++) {
                switch( preview.getCameraControllerManager().getFacing(i) ) {
                    case FACING_BACK:
                        back_camera_ids.add(i);
                        break;
                    case FACING_FRONT:
                        front_camera_ids.add(i);
                        break;
                    default:
                        // we assume any unknown cameras are also external
                        other_camera_ids.add(i);
                        break;
                }
            }
            boolean multi_same_facing = back_camera_ids.size() >= 2 || front_camera_ids.size() >= 2 || other_camera_ids.size() >= 2;
            int n_facing = 0;
            if( back_camera_ids.size() > 0 )
                n_facing++;
            if( front_camera_ids.size() > 0 )
                n_facing++;
            if( other_camera_ids.size() > 0 )
                n_facing++;
            this.is_multi_cam = multi_same_facing && n_facing >= 2;
            //this.is_multi_cam = false; // test
            if( MyDebug.LOG ) {
                Log.d(TAG, "multi_same_facing: " + multi_same_facing);
                Log.d(TAG, "n_facing: " + n_facing);
                Log.d(TAG, "is_multi_cam: " + is_multi_cam);
            }

            if( !is_multi_cam ) {
                this.back_camera_ids = null;
                this.front_camera_ids = null;
                this.other_camera_ids = null;
            }
        }

        // initialise on-screen button visibility
        View switchCameraButton = findViewById(R.id.switch_camera);
        switchCameraButton.setVisibility(n_cameras > 1 ? View.VISIBLE : View.GONE);
        // switchMultiCameraButton visibility updated below in mainUI.updateOnScreenIcons(), as it also depends on user preference
        View speechRecognizerButton = findViewById(R.id.audio_control);
        speechRecognizerButton.setVisibility(View.GONE); // disabled by default, until the speech recognizer is created
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting button visibility: " + (System.currentTimeMillis() - debug_time));
        View pauseVideoButton = findViewById(R.id.pause_video);
        pauseVideoButton.setVisibility(View.GONE);
        View takePhotoVideoButton = findViewById(R.id.take_photo_when_video_recording);
        takePhotoVideoButton.setVisibility(View.GONE);
        View cancelPanoramaButton = findViewById(R.id.cancel_panorama);
        cancelPanoramaButton.setVisibility(View.GONE);

        // We initialise optional controls to invisible/gone, so they don't show while the camera is opening - the actual visibility is
        // set in cameraSetup().
        // Note that ideally we'd set this in the xml, but doing so for R.id.zoom causes a crash on Galaxy Nexus startup beneath
        // setContentView()!
        // To be safe, we also do so for take_photo and zoom_seekbar (we already know we've had no reported crashes for focus_seekbar,
        // however).
        View takePhotoButton = findViewById(R.id.take_photo);
        takePhotoButton.setVisibility(View.INVISIBLE);
        View zoomControls = findViewById(R.id.zoom);
        zoomControls.setVisibility(View.GONE);
        View zoomSeekbar = findViewById(R.id.zoom_seekbar);
        zoomSeekbar.setVisibility(View.INVISIBLE);

        // initialise state of on-screen icons
        mainUI.updateOnScreenIcons();

        // listen for orientation event change
        orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                MainActivity.this.mainUI.onOrientationChanged(orientation);
            }
        };

        // set up gallery button long click
        View galleryButton = findViewById(R.id.gallery);
        galleryButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if( !allowLongPress() ) {
                    // return false, so a regular click will still be triggered when the user releases the touch
                    return false;
                }

                return true;
            }
        });
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting long click listeners: " + (System.currentTimeMillis() - debug_time));

        // listen for gestures
        gestureDetector = new GestureDetector(this, new MyGestureDetector());
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating gesture detector: " + (System.currentTimeMillis() - debug_time));

        View decorView = getWindow().getDecorView();
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
            // set a window insets listener to find the navigation_gap
            if( MyDebug.LOG )
                Log.d(TAG, "set a window insets listener");
            this.set_window_insets_listener = true;
            decorView.getRootView().setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "inset: " + insets.getSystemWindowInsetRight());
                    if( navigation_gap == 0 ) {
                        navigation_gap = insets.getSystemWindowInsetRight();
                        if( MyDebug.LOG )
                            Log.d(TAG, "navigation_gap is " + navigation_gap);
                        // Sometimes when this callback is called, the navigation_gap may still be 0 even if
                        // the device doesn't have physical navigation buttons - we need to wait
                        // until we have found a non-zero value before switching to no limits.
                        // On devices with physical navigation bar, navigation_gap should remain 0
                        // (and there's no point setting FLAG_LAYOUT_NO_LIMITS)
                        if( want_no_limits && navigation_gap != 0 ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "set FLAG_LAYOUT_NO_LIMITS");
                            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                        }
                    }
                    return getWindow().getDecorView().getRootView().onApplyWindowInsets(insets);
                }
            });
        }

        // set up listener to handle immersive mode options
        decorView.setOnSystemUiVisibilityChangeListener
                (new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        // Note that system bars will only be "visible" if none of the
                        // LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
                        if( !usingKitKatImmersiveMode() )
                            return;
                        if( MyDebug.LOG )
                            Log.d(TAG, "onSystemUiVisibilityChange: " + visibility);

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                        String immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
                        boolean hide_ui = immersive_mode.equals("immersive_mode_gui") || immersive_mode.equals("immersive_mode_everything");

                        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "system bars now visible");
                            // The system bars are visible. Make any desired
                            // adjustments to your UI, such as showing the action bar or
                            // other navigational controls.
                            if( hide_ui )
                                mainUI.setImmersiveMode(false);
                            setImmersiveTimer();
                        }
                        else {
                            if( MyDebug.LOG )
                                Log.d(TAG, "system bars now NOT visible");
                            // The system bars are NOT visible. Make any desired
                            // adjustments to your UI, such as hiding the action bar or
                            // other navigational controls.
                            if( hide_ui )
                                mainUI.setImmersiveMode(true);
                        }
                    }
                });
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting immersive mode listener: " + (System.currentTimeMillis() - debug_time));

        // show "about" dialog for first time use; also set some per-device defaults
        boolean has_done_first_time = sharedPreferences.contains(PreferenceKeys.FirstTimePreferenceKey);
        if( MyDebug.LOG )
            Log.d(TAG, "has_done_first_time: " + has_done_first_time);
        if( !has_done_first_time ) {
            setDeviceDefaults();
        }
        if( !has_done_first_time ) {
            if( !is_test ) {
                AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
                alertDialog.setTitle(R.string.app_name);
                alertDialog.setMessage(R.string.intro_text);
                alertDialog.setPositiveButton(android.R.string.ok, null);
                alertDialog.setNegativeButton(R.string.preference_online_help, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "online help");
                        launchOnlineHelp();
                    }
                });
                alertDialog.show();
            }

            setFirstTimeFlag();
        }

        {
            // handle What's New dialog
            int version_code = -1;
            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                version_code = pInfo.versionCode;
            }
            catch(PackageManager.NameNotFoundException e) {
                if( MyDebug.LOG )
                    Log.d(TAG, "NameNotFoundException exception trying to get version number");
                e.printStackTrace();
            }
            if( version_code != -1 ) {
                int latest_version = sharedPreferences.getInt(PreferenceKeys.LatestVersionPreferenceKey, 0);
                if( MyDebug.LOG ) {
                    Log.d(TAG, "version_code: " + version_code);
                    Log.d(TAG, "latest_version: " + latest_version);
                }
                //final boolean whats_new_enabled = false;
                final boolean whats_new_enabled = true;
                if( whats_new_enabled ) {
                    // whats_new_version is the version code that the What's New text is written for. Normally it will equal the
                    // current release (version_code), but it some cases we may want to leave it unchanged.
                    // E.g., we have a "What's New" for 1.44 (64), but then push out a quick fix for 1.44.1 (65). We don't want to
                    // show the dialog again to people who already received 1.44 (64), but we still want to show the dialog to people
                    // upgrading from earlier versions.
                    int whats_new_version = 78; // 1.48.2
                    whats_new_version = Math.min(whats_new_version, version_code); // whats_new_version should always be <= version_code, but just in case!
                    if( MyDebug.LOG ) {
                        Log.d(TAG, "whats_new_version: " + whats_new_version);
                    }
                    final boolean force_whats_new = false;
                    //final boolean force_whats_new = true; // for testing
                    boolean allow_show_whats_new = sharedPreferences.getBoolean(PreferenceKeys.ShowWhatsNewPreferenceKey, true);
                    if( MyDebug.LOG )
                        Log.d(TAG, "allow_show_whats_new: " + allow_show_whats_new);
                    // don't show What's New if this is the first time the user has run
                    if( has_done_first_time && allow_show_whats_new && ( force_whats_new || whats_new_version > latest_version ) ) {
                        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
                        alertDialog.setTitle(R.string.whats_new);
                        alertDialog.setMessage(R.string.whats_new_text);
                        alertDialog.setPositiveButton(android.R.string.ok, null);
                        /*alertDialog.setNegativeButton(R.string.donate, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "donate");
                                // if we change this, remember that any page linked to must abide by Google Play developer policies!
                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.DonateLink));
                                startActivity(browserIntent);
                            }
                        });*/
                        alertDialog.show();
                    }
                }
                // We set the latest_version whether or not the dialog is shown - if we showed the first time dialog, we don't
                // want to then show the What's New dialog next time we run! Similarly if the user had disabled showing the dialog,
                // but then enables it, we still shouldn't show the dialog until the new time Open Camera upgrades.
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(PreferenceKeys.LatestVersionPreferenceKey, version_code);
                editor.apply();
            }
        }

        setModeFromIntents(savedInstanceState);

        // load icons
        preloadIcons(R.array.flash_icons);
        preloadIcons(R.array.focus_mode_icons);
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after preloading icons: " + (System.currentTimeMillis() - debug_time));

        // initialise text to speech engine
        textToSpeechSuccess = false;
        // run in separate thread so as to not delay startup time
        new Thread(new Runnable() {
            public void run() {
                textToSpeech = new TextToSpeech(MainActivity.this, new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "TextToSpeech initialised");
                        if( status == TextToSpeech.SUCCESS ) {
                            textToSpeechSuccess = true;
                            if( MyDebug.LOG )
                                Log.d(TAG, "TextToSpeech succeeded");
                        }
                        else {
                            if( MyDebug.LOG )
                                Log.d(TAG, "TextToSpeech failed");
                        }
                    }
                });
            }
        }).start();

        // create notification channel - only needed on Android 8+
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            CharSequence name = "Open Camera Image Saving";
            String description = "Notification channel for processing and saving images in the background";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: total time for Activity startup: " + (System.currentTimeMillis() - debug_time));
    }

    public int getNavigationGap() {
        return want_no_limits ? navigation_gap : 0;
    }

    /** Whether this is a multi camera device, and the user preference is set to enable the multi-camera button.
     */
    public boolean isMultiCamEnabled() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        return is_multi_cam && sharedPreferences.getBoolean(PreferenceKeys.MultiCamButtonPreferenceKey, true);
    }

    /** Whether this is a multi camera device, whether or not the user preference is set to enable
     *  the multi-camera button.
     */
    public boolean isMultiCam() {
        return is_multi_cam;
    }

    /* Returns the camera Id in use by the preview - or the one we requested, if the camera failed
     * to open.
     * Needed as Preview.getCameraId() returns 0 if camera_controller==null, but if the camera
     * fails to open, we want the switch camera icons to still work as expected!
     */
    private int getActualCameraId() {
        if( preview.getCameraController() == null )
            return applicationInterface.getCameraIdPref();
        else
            return preview.getCameraId();
    }

    /** Whether the icon switch_multi_camera should be displayed. This is if the following are all
     *  true:
     *  - The device is a multi camera device (MainActivity.is_multi_cam==true).
     *  - The user preference for using the separate icons is enabled
     *    (PreferenceKeys.MultiCamButtonPreferenceKey).
     *  - For the current camera ID, there is only one camera with the same front/back/external
     *    "facing" (e.g., imagine a device with two back cameras, but only one front camera).
     */
    public boolean showSwitchMultiCamIcon() {
        if( isMultiCamEnabled() ) {
            int cameraId = getActualCameraId();
            switch( preview.getCameraControllerManager().getFacing(cameraId) ) {
                case FACING_BACK:
                    if( back_camera_ids.size() > 0 )
                        return true;
                    break;
                case FACING_FRONT:
                    if( front_camera_ids.size() > 0 )
                        return true;
                    break;
                default:
                    if( other_camera_ids.size() > 0 )
                        return true;
                    break;
            }
        }
        return false;
    }

    /** Whether user preference is set to allow long press actions.
     */
    private boolean allowLongPress() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        return sharedPreferences.getBoolean(PreferenceKeys.AllowLongPressPreferenceKey, true);
    }

    /* This method sets the preference defaults which are set specific for a particular device.
     * This method should be called when Open Camera is run for the very first time after installation,
     * or when the user has requested to "Reset settings".
     */
    void setDeviceDefaults() {
        if( MyDebug.LOG )
            Log.d(TAG, "setDeviceDefaults");
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean is_samsung = Build.MANUFACTURER.toLowerCase(Locale.US).contains("samsung");
        boolean is_oneplus = Build.MANUFACTURER.toLowerCase(Locale.US).contains("oneplus");
        //boolean is_nexus = Build.MODEL.toLowerCase(Locale.US).contains("nexus");
        //boolean is_nexus6 = Build.MODEL.toLowerCase(Locale.US).contains("nexus 6");
        //boolean is_pixel_phone = Build.DEVICE != null && Build.DEVICE.equals("sailfish");
        //boolean is_pixel_xl_phone = Build.DEVICE != null && Build.DEVICE.equals("marlin");
        if( MyDebug.LOG ) {
            Log.d(TAG, "is_samsung? " + is_samsung);
            Log.d(TAG, "is_oneplus? " + is_oneplus);
            //Log.d(TAG, "is_nexus? " + is_nexus);
            //Log.d(TAG, "is_nexus6? " + is_nexus6);
            //Log.d(TAG, "is_pixel_phone? " + is_pixel_phone);
            //Log.d(TAG, "is_pixel_xl_phone? " + is_pixel_xl_phone);
        }
        if( is_samsung || is_oneplus ) {
            // workaround needed for Samsung Galaxy S7 at least (tested on Samsung RTL)
            // workaround needed for OnePlus 3 at least (see http://forum.xda-developers.com/oneplus-3/help/camera2-support-t3453103 )
            // update for v1.37: significant improvements have been made for standard flash and Camera2 API. But OnePlus 3T still has problem
            // that photos come out with a blue tinge if flash is on, and the scene is bright enough not to need it; Samsung devices also seem
            // to work okay, testing on S7 on RTL, but still keeping the fake flash mode in place for these devices, until we're sure of good
            // behaviour
            // update for testing on Galaxy S10e: still needs fake flash
            if( MyDebug.LOG )
                Log.d(TAG, "set fake flash for camera2");
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(PreferenceKeys.Camera2FakeFlashPreferenceKey, true);
            editor.apply();
        }
		/*if( is_nexus6 ) {
			// Nexus 6 captureBurst() started having problems with Android 7 upgrade - images appeared in wrong order (and with wrong order of shutter speeds in exif info), as well as problems with the camera failing with serious errors
			// we set this even for Nexus 6 devices not on Android 7, as at some point they'll likely be upgraded to Android 7
			// Update: now fixed in v1.37, this was due to bug where we set RequestTag.CAPTURE for all captures in takePictureBurstExpoBracketing(), rather than just the last!
			if( MyDebug.LOG )
				Log.d(TAG, "disable fast burst for camera2");
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putBoolean(PreferenceKeys.getCamera2FastBurstPreferenceKey(), false);
			editor.apply();
		}*/
    }

    /** Switches modes if required, if called from a relevant intent/tile.
     */
    private void setModeFromIntents(Bundle savedInstanceState) {
        if( MyDebug.LOG )
            Log.d(TAG, "setModeFromIntents");
        if( savedInstanceState != null ) {
            // If we're restoring from a saved state, we shouldn't be resetting any modes
            if( MyDebug.LOG )
                Log.d(TAG, "restoring from saved state");
            return;
        }
        boolean done_facing = false;
        String action = this.getIntent().getAction();
        if( MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(action) || MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from video intent");
            applicationInterface.setVideoPref(true);
        }
        else if( MediaStore.ACTION_IMAGE_CAPTURE.equals(action) || MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(action) || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA.equals(action) || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from photo intent");
            applicationInterface.setVideoPref(false);
        }
        else if( (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileService.TILE_ID.equals(action)) || ACTION_SHORTCUT_CAMERA.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from quick settings tile or application shortcut for Open Camera: photo mode");
            applicationInterface.setVideoPref(false);
        }
        else if( (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileServiceVideo.TILE_ID.equals(action)) || ACTION_SHORTCUT_VIDEO.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from quick settings tile or application shortcut for Open Camera: video mode");
            applicationInterface.setVideoPref(true);
        }

        Bundle extras = this.getIntent().getExtras();
        if( extras != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "handle intent extra information");
            if( !done_facing ) {
                int camera_facing = extras.getInt("android.intent.extras.CAMERA_FACING", -1);
                if( camera_facing == 0 || camera_facing == 1 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extras.CAMERA_FACING: " + camera_facing);
                    applicationInterface.switchToCamera(camera_facing==1);
                    done_facing = true;
                }
            }
            if( !done_facing ) {
                if( extras.getInt("android.intent.extras.LENS_FACING_FRONT", -1) == 1 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extras.LENS_FACING_FRONT");
                    applicationInterface.switchToCamera(true);
                    done_facing = true;
                }
            }
            if( !done_facing ) {
                if( extras.getInt("android.intent.extras.LENS_FACING_BACK", -1) == 1 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extras.LENS_FACING_BACK");
                    applicationInterface.switchToCamera(false);
                    done_facing = true;
                }
            }
            if( !done_facing ) {
                if( extras.getBoolean("android.intent.extra.USE_FRONT_CAMERA", false) ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extra.USE_FRONT_CAMERA");
                    applicationInterface.switchToCamera(true);
                    done_facing = true;
                }
            }
        }

        // N.B., in practice the hasSetCameraId() check is pointless as we don't save the camera ID in shared preferences, so it will always
        // be false when application is started from onCreate(), unless resuming from saved instance (in which case we shouldn't be here anyway)
        if( !done_facing && !applicationInterface.hasSetCameraId() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "initialise to back camera");
            // most devices have first camera as back camera anyway so this wouldn't be needed, but some (e.g., LG G6) have first camera
            // as front camera, so we should explicitly switch to back camera
            applicationInterface.switchToCamera(false);
        }
    }

    /** Determine whether we support Camera2 API.
     */
    private void initCamera2Support() {
        if( MyDebug.LOG )
            Log.d(TAG, "initCamera2Support");
        supports_camera2 = false;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
            // originally we allowed Camera2 if all cameras support at least LIMITED
            // as of 1.45, we allow Camera2 if at least one camera supports at least LIMITED - this
            // is to support devices that might have a camera with LIMITED or better support, but
            // also a LEGACY camera
            CameraControllerManager2 manager2 = new CameraControllerManager2(this);
            supports_camera2 = false;
            int n_cameras = manager2.getNumberOfCameras();
            if( n_cameras == 0 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Camera2 reports 0 cameras");
                supports_camera2 = false;
            }
            for(int i=0;i<n_cameras && !supports_camera2;i++) {
                if( manager2.allowCamera2Support(i) ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "camera " + i + " has at least limited support for Camera2 API");
                    supports_camera2 = true;
                }
            }
        }

        //test_force_supports_camera2 = true; // test
        if( test_force_supports_camera2 ) {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "forcing supports_camera2");
                supports_camera2 = true;
            }
        }

        if( MyDebug.LOG )
            Log.d(TAG, "supports_camera2? " + supports_camera2);

        // handle the switch from a boolean preference_use_camera2 to String preference_camera_api
        // that occurred in v1.48
        if( supports_camera2 ) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            if( !sharedPreferences.contains(PreferenceKeys.CameraAPIPreferenceKey) // doesn't have the new key set yet
                    && sharedPreferences.contains("preference_use_camera2") // has the old key set
                    && sharedPreferences.getBoolean("preference_use_camera2", false) // and camera2 was enabled
            ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "transfer legacy camera2 boolean preference to new api option");
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(PreferenceKeys.CameraAPIPreferenceKey, "preference_camera_api_camera2");
                editor.remove("preference_use_camera2"); // remove the old key, just in case
                editor.apply();
            }
        }
    }

    private void preloadIcons(int icons_id) {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "preloadIcons: " + icons_id);
            debug_time = System.currentTimeMillis();
        }
        String [] icons = getResources().getStringArray(icons_id);
        for(String icon : icons) {
            int resource = getResources().getIdentifier(icon, null, this.getApplicationContext().getPackageName());
            if( MyDebug.LOG )
                Log.d(TAG, "load resource: " + resource);
            Bitmap bm = BitmapFactory.decodeResource(getResources(), resource);
            this.preloaded_bitmap_resources.put(resource, bm);
        }
        if( MyDebug.LOG ) {
            Log.d(TAG, "preloadIcons: total time for preloadIcons: " + (System.currentTimeMillis() - debug_time));
            Log.d(TAG, "size of preloaded_bitmap_resources: " + preloaded_bitmap_resources.size());
        }
    }

    @Override
    protected void onStop() {
        if( MyDebug.LOG )
            Log.d(TAG, "onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if( MyDebug.LOG ) {
            Log.d(TAG, "onDestroy");
            Log.d(TAG, "size of preloaded_bitmap_resources: " + preloaded_bitmap_resources.size());
        }
        activity_count--;
        if( MyDebug.LOG )
            Log.d(TAG, "activity_count: " + activity_count);

        // should do asap before waiting for images to be saved - as risk the application will be killed whilst waiting for that to happen,
        // and we want to avoid notifications hanging around
        cancelImageSavingNotification();

        // reduce risk of losing any images
        // we don't do this in onPause or onStop, due to risk of ANRs
        // note that even if we did call this earlier in onPause or onStop, we'd still want to wait again here: as it can happen
        // that a new image appears after onPause/onStop is called, in which case we want to wait until images are saved,
        // otherwise we can have crash if we need Renderscript after calling releaseAllContexts(), or because rs has been set to
        // null from beneath applicationInterface.onDestroy()
        waitUntilImageQueueEmpty();

        preview.onDestroy();
        if( applicationInterface != null ) {
            applicationInterface.onDestroy();
        }
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity_count == 0 ) {
            // See note in HDRProcessor.onDestroy() - but from Android M, renderscript contexts are released with releaseAllContexts()
            // doc for releaseAllContexts() says "If no contexts have been created this function does nothing"
            // Important to only do so if no other activities are running (see activity_count). Otherwise risk
            // of crashes if one activity is destroyed when another instance is still using Renderscript. I've
            // been unable to reproduce this, though such RSInvalidStateException crashes from Google Play.
            if( MyDebug.LOG )
                Log.d(TAG, "release renderscript contexts");
            RenderScript.releaseAllContexts();
        }
        // Need to recycle to avoid out of memory when running tests - probably good practice to do anyway
        for(Map.Entry<Integer, Bitmap> entry : preloaded_bitmap_resources.entrySet()) {
            if( MyDebug.LOG )
                Log.d(TAG, "recycle: " + entry.getKey());
            entry.getValue().recycle();
        }
        preloaded_bitmap_resources.clear();
        if( textToSpeech != null ) {
            // http://stackoverflow.com/questions/4242401/tts-error-leaked-serviceconnection-android-speech-tts-texttospeech-solved
            if( MyDebug.LOG )
                Log.d(TAG, "free textToSpeech");
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }

        super.onDestroy();
        if( MyDebug.LOG )
            Log.d(TAG, "onDestroy done");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private void setFirstTimeFlag() {
        if( MyDebug.LOG )
            Log.d(TAG, "setFirstTimeFlag");
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.FirstTimePreferenceKey, true);
        editor.apply();
    }

    private static String getOnlineHelpUrl(String append) {
        if( MyDebug.LOG )
            Log.d(TAG, "getOnlineHelpUrl: " + append);
        // if we change this, remember that any page linked to must abide by Google Play developer policies!
        //return "https://opencamera.sourceforge.io/" + append;
        return "https://opencamera.org.uk/" + append;
    }

    void launchOnlineHelp() {
        if( MyDebug.LOG )
            Log.d(TAG, "launchOnlineHelp");
        // if we change this, remember that any page linked to must abide by Google Play developer policies!
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getOnlineHelpUrl("")));
        startActivity(browserIntent);
    }

    void launchOnlinePrivacyPolicy() {
        if( MyDebug.LOG )
            Log.d(TAG, "launchOnlinePrivacyPolicy");
        // if we change this, remember that any page linked to must abide by Google Play developer policies!
        //Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getOnlineHelpUrl("index.html#privacy")));
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getOnlineHelpUrl("privacy_oc.html")));
        startActivity(browserIntent);
    }

    void launchOnlineLicences() {
        if( MyDebug.LOG )
            Log.d(TAG, "launchOnlineLicences");
        // if we change this, remember that any page linked to must abide by Google Play developer policies!
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getOnlineHelpUrl("#licence")));
        startActivity(browserIntent);
    }

    /* Audio trigger - either loud sound, or speech recognition.
     * This performs some additional checks before taking a photo.
     */
    void audioTrigger() {
        if( MyDebug.LOG )
            Log.d(TAG, "ignore audio trigger due to popup open");

         if( camera_in_background ) {
            if( MyDebug.LOG )
                Log.d(TAG, "ignore audio trigger due to camera in background");
        }
        else if( preview.isTakingPhotoOrOnTimer() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "ignore audio trigger due to already taking photo or on timer");
        }
        else if( preview.isVideoRecording() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "ignore audio trigger due to already recording video");
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "schedule take picture due to loud noise");
            //takePicture();
            this.runOnUiThread(new Runnable() {
                public void run() {
                    if( MyDebug.LOG )
                        Log.d(TAG, "taking picture due to audio trigger");
                    takePicture(false);
                }
            });
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if( MyDebug.LOG )
            Log.d(TAG, "onKeyDown: " + keyCode);
        if( camera_in_background ) {
            // don't allow keys such as volume keys for taking photo when camera in background!
            if( MyDebug.LOG )
                Log.d(TAG, "camera is in background");
        }
        else {
            boolean handled = mainUI.onKeyDown(keyCode, event);
            if( handled )
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if( MyDebug.LOG )
            Log.d(TAG, "onKeyUp: " + keyCode);
        if( camera_in_background ) {
            // don't allow keys such as volume keys for taking photo when camera in background!
            if( MyDebug.LOG )
                Log.d(TAG, "camera is in background");
        }
        else {
            mainUI.onKeyUp(keyCode, event);
        }
        return super.onKeyUp(keyCode, event);
    }

    public void zoomIn() {
        mainUI.changeSeekbar(R.id.zoom_seekbar, -1);
    }

    public void zoomOut() {
        mainUI.changeSeekbar(R.id.zoom_seekbar, 1);
    }

    public void changeExposure(int change) {
        mainUI.changeSeekbar(R.id.exposure_seekbar, change);
    }

    public void changeISO(int change) {
        mainUI.changeSeekbar(R.id.iso_seekbar, change);
    }

    public void changeFocusDistance(int change, boolean is_target_distance) {
        mainUI.changeSeekbar(is_target_distance ? R.id.focus_bracketing_target_seekbar : R.id.focus_seekbar, change);
    }

    private final SensorEventListener accelerometerListener = new SensorEventListener() {
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            preview.onAccelerometerSensorChanged(event);
        }
    };

    /* To support https://play.google.com/store/apps/details?id=com.miband2.mibandselfie .
     * Allows using the Mi Band 2 as a Bluetooth remote for Open Camera to take photos or start/stop
     * videos.
     */
    private final BroadcastReceiver cameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if( MyDebug.LOG )
                Log.d(TAG, "cameraReceiver.onReceive");
            MainActivity.this.takePicture(false);
        }
    };

    public float getWaterDensity() {
        return this.mWaterDensity;
    }

    @Override
    protected void onResume() {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "onResume");
            debug_time = System.currentTimeMillis();
        }
        super.onResume();
        this.app_is_paused = false; // must be set before initLocation() at least

        cancelImageSavingNotification();

        // Set black window background; also needed if we hide the virtual buttons in immersive mode
        // Note that we do it here rather than customising the theme's android:windowBackground, so this doesn't affect other views - in particular, the MyPreferenceFragment settings
        getWindow().getDecorView().getRootView().setBackgroundColor(Color.BLACK);

        mSensorManager.registerListener(accelerometerListener, mSensorAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);

        orientationEventListener.enable();

        registerReceiver(cameraReceiver, new IntentFilter("com.miband2.action.CAMERA"));

        speechControl.initSpeechRecognizer();

        mainUI.layoutUI();

        applicationInterface.reset(false); // should be called before opening the camera in preview.onResume()

        preview.onResume();

        {
            // show a toast for the camera if it's not the first for front of back facing (otherwise on multi-front/back camera
            // devices, it's easy to forget if set to a different camera)
            // but we only show this when resuming, not every time the camera opens
            int cameraId = applicationInterface.getCameraIdPref();
            if( cameraId > 0 ) {
                CameraControllerManager camera_controller_manager = preview.getCameraControllerManager();
                CameraController.Facing front_facing = camera_controller_manager.getFacing(cameraId);
                if( MyDebug.LOG )
                    Log.d(TAG, "front_facing: " + front_facing);
                if( camera_controller_manager.getNumberOfCameras() > 2 ) {
                    boolean camera_is_default = true;
                    for(int i=0;i<cameraId;i++) {
                        CameraController.Facing that_front_facing = camera_controller_manager.getFacing(i);
                        if( MyDebug.LOG )
                            Log.d(TAG, "camera " + i + " that_front_facing: " + that_front_facing);
                        if( that_front_facing == front_facing ) {
                            // found an earlier camera with same front/back facing
                            camera_is_default = false;
                        }
                    }
                    if( MyDebug.LOG )
                        Log.d(TAG, "camera_is_default: " + camera_is_default);
                    if( !camera_is_default ) {
                        this.pushCameraIdToast(cameraId);
                    }
                }
            }
        }

        if( MyDebug.LOG ) {
            Log.d(TAG, "onResume: total time to resume: " + (System.currentTimeMillis() - debug_time));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if( MyDebug.LOG )
            Log.d(TAG, "onWindowFocusChanged: " + hasFocus);
        super.onWindowFocusChanged(hasFocus);
        if( !this.camera_in_background && hasFocus ) {
            // low profile mode is cleared when app goes into background
            // and for Kit Kat immersive mode, we want to set up the timer
            // we do in onWindowFocusChanged rather than onResume(), to also catch when window lost focus due to notification bar being dragged down (which prevents resetting of immersive mode)
            initImmersiveMode();
        }
    }

    @Override
    protected void onPause() {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "onPause");
            debug_time = System.currentTimeMillis();
        }
        super.onPause(); // docs say to call this before freeing other things
        this.app_is_paused = true;

        mSensorManager.unregisterListener(accelerometerListener);
        orientationEventListener.disable();
        try {
            unregisterReceiver(cameraReceiver);
        }
        catch(IllegalArgumentException e) {
            // this can happen if not registered - simplest to just catch the exception
            e.printStackTrace();
        }

        speechControl.stopSpeechRecognizer();
//        applicationInterface.clearLastImages(); // this should happen when pausing the preview, but call explicitly just to be safe
        preview.onPause();

        if( applicationInterface.getImageSaver().getNImagesToSave() > 0) {
            createImageSavingNotification();
        }


        if( MyDebug.LOG ) {
            Log.d(TAG, "onPause: total time to pause: " + (System.currentTimeMillis() - debug_time));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if( MyDebug.LOG )
            Log.d(TAG, "onConfigurationChanged()");
        // configuration change can include screen orientation (landscape/portrait) when not locked (when settings is open)
        // needed if app is paused/resumed when settings is open and device is in portrait mode
        preview.setCameraDisplayOrientation();
        super.onConfigurationChanged(newConfig);
    }

    public void waitUntilImageQueueEmpty() {
        if( MyDebug.LOG )
            Log.d(TAG, "waitUntilImageQueueEmpty");
        applicationInterface.getImageSaver().waitUntilDone();
    }

    public void clickedTakePhoto(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedTakePhoto");
        this.takePicture(false);
    }

    /** User has clicked button to take a photo snapshot whilst video recording.
     */
    public void clickedTakePhotoVideoSnapshot(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedTakePhotoVideoSnapshot");
        this.takePicture(true);
    }

    public void clickedPauseVideo(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedPauseVideo");
        if( preview.isVideoRecording() ) { // just in case
            preview.pauseVideo();
            mainUI.setPauseVideoContentDescription();
        }
    }

    public void clickedCancelPanorama(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedCancelPanorama");
    }

    public void clickedCycleRaw(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedCycleRaw");

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String new_value = null;
        switch( sharedPreferences.getString(PreferenceKeys.RawPreferenceKey, "preference_raw_no") ) {
            case "preference_raw_no":
                new_value = "preference_raw_yes";
                break;
            case "preference_raw_yes":
                new_value = "preference_raw_only";
                break;
            case "preference_raw_only":
                new_value = "preference_raw_no";
                break;
            default:
                Log.e(TAG, "unrecognised raw preference");
                break;
        }
        if( new_value != null ) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PreferenceKeys.RawPreferenceKey, new_value);
            editor.apply();

            mainUI.updateCycleRawIcon();
            preview.reopenCamera(); // needed for RAW options to take effect
        }
    }

    public void clickedStoreLocation(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedStoreLocation");
        boolean value = applicationInterface.getGeotaggingPref();
        value = !value;

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.LocationPreferenceKey, value);
        editor.apply();

        mainUI.updateStoreLocationIcon();

    }

    public void clickedTextStamp(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedTextStamp");

        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
        alertDialog.setTitle(R.string.preference_textstamp);

        final EditText editText = new EditText(this);
        editText.setText(applicationInterface.getTextStampPref());
        alertDialog.setView(editText);
        alertDialog.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                if( MyDebug.LOG )
                    Log.d(TAG, "custom text stamp clicked okay");

                String custom_text = editText.getText().toString();
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(PreferenceKeys.TextStampPreferenceKey, custom_text);
                editor.apply();

                mainUI.updateTextStampIcon();
            }
        });
        alertDialog.setNegativeButton(android.R.string.cancel, null);

        final AlertDialog alert = alertDialog.create();
        alert.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface arg0) {
                if( MyDebug.LOG )
                    Log.d(TAG, "custom stamp text dialog dismissed");
                setWindowFlagsForCamera();
                showPreview(true);
            }
        });

        showPreview(false);
        setWindowFlagsForSettings();
        showAlert(alert);
    }

    public void clickedStamp(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedStamp");

        boolean value = applicationInterface.getStampPref().equals("preference_stamp_yes");
        value = !value;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.StampPreferenceKey, value ? "preference_stamp_yes" : "preference_stamp_no");
        editor.apply();

        mainUI.updateStampIcon();
        preview.showToast(stamp_toast, value ? R.string.stamp_enabled : R.string.stamp_disabled);
    }

    public void clickedAutoLevel(View view) {
        clickedAutoLevel();
    }

    public void clickedAutoLevel() {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedAutoLevel");
        boolean value = applicationInterface.getAutoStabilisePref();
        value = !value;

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.AutoStabilisePreferenceKey, value);
        editor.apply();

        boolean done_dialog = false;
        if( value ) {
            boolean done_auto_stabilise_info = sharedPreferences.contains(PreferenceKeys.AutoStabiliseInfoPreferenceKey);
            if( !done_auto_stabilise_info ) {
                mainUI.showInfoDialog(R.string.preference_auto_stabilise, R.string.auto_stabilise_info, PreferenceKeys.AutoStabiliseInfoPreferenceKey);
                done_dialog = true;
            }
        }

        if( !done_dialog ) {
            String message = getResources().getString(R.string.preference_auto_stabilise) + ": " + getResources().getString(value ? R.string.on : R.string.off);
            preview.showToast(this.getChangedAutoStabiliseToastBoxer(), message);
        }

        mainUI.updateAutoLevelIcon();
    }

    public void clickedCycleFlash(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedCycleFlash");

        preview.cycleFlash(true, true);
        mainUI.updateCycleFlashIcon();
    }

    public void clickedFaceDetection(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedFaceDetection");

        boolean value = applicationInterface.getFaceDetectionPref();
        value = !value;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.FaceDetectionPreferenceKey, value);
        editor.apply();

        mainUI.updateFaceDetectionIcon();
        preview.showToast(stamp_toast, value ? R.string.face_detection_enabled : R.string.face_detection_disabled);
        block_startup_toast = true; // so the toast from reopening camera is suppressed, otherwise it conflicts with the face detection toast
        preview.reopenCamera();
    }

    public void clickedAudioControl(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedAudioControl");
        // check hasAudioControl just in case!
        if( !hasAudioControl() ) {
            if( MyDebug.LOG )
                Log.e(TAG, "clickedAudioControl, but hasAudioControl returns false!");
            return;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String audio_control = sharedPreferences.getString(PreferenceKeys.AudioControlPreferenceKey, "none");
        if( audio_control.equals("voice") && speechControl.hasSpeechRecognition() ) {
            if( speechControl.isStarted() ) {
                speechControl.stopListening();
            }
            else {
                boolean has_audio_permission = true;
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                    // we restrict the checks to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
                    if( MyDebug.LOG )
                        Log.d(TAG, "check for record audio permission");
                    if( ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "record audio permission not available");
                        has_audio_permission = false;
                    }
                }
                if( has_audio_permission ) {
                    String toast_string = this.getResources().getString(R.string.speech_recognizer_started) + "\n" +
                            this.getResources().getString(R.string.speech_recognizer_extra_info);
                    preview.showToast(audio_control_toast, toast_string);
                    speechControl.startSpeechRecognizerIntent();
                    speechControl.speechRecognizerStarted();
                }
            }
        }
    }

    /* Returns the cameraId that the "Switch camera" button will switch to.
     * Note that this may not necessarily be the next camera ID, on multi camera devices (if
     * isMultiCamEnabled() returns true).
     */
    public int getNextCameraId() {
        if( MyDebug.LOG )
            Log.d(TAG, "getNextCameraId");
        int cameraId = getActualCameraId();
        if( MyDebug.LOG )
            Log.d(TAG, "current cameraId: " + cameraId);
        if( this.preview.canSwitchCamera() ) {
            if( isMultiCamEnabled() ) {
                // don't use preview.getCameraController(), as it may be null if user quickly switches between cameras
                switch( preview.getCameraControllerManager().getFacing(cameraId) ) {
                    case FACING_BACK:
                        if( front_camera_ids.size() > 0 )
                            cameraId = front_camera_ids.get(0);
                        else if( other_camera_ids.size() > 0 )
                            cameraId = other_camera_ids.get(0);
                        break;
                    case FACING_FRONT:
                        if( other_camera_ids.size() > 0 )
                            cameraId = other_camera_ids.get(0);
                        else if( back_camera_ids.size() > 0 )
                            cameraId = back_camera_ids.get(0);
                        break;
                    default:
                        if( back_camera_ids.size() > 0 )
                            cameraId = back_camera_ids.get(0);
                        else if( front_camera_ids.size() > 0 )
                            cameraId = front_camera_ids.get(0);
                        break;
                }
            }
            else {
                int n_cameras = preview.getCameraControllerManager().getNumberOfCameras();
                cameraId = (cameraId+1) % n_cameras;
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "next cameraId: " + cameraId);
        return cameraId;
    }

    /* Returns the cameraId that the "Switch multi camera" button will switch to.
     * Should only be called if isMultiCamEnabled() returns true.
     */
    public int getNextMultiCameraId() {
        if( MyDebug.LOG )
            Log.d(TAG, "getNextMultiCameraId");
        if( !isMultiCamEnabled() ) {
            Log.e(TAG, "getNextMultiCameraId() called but not in multi-cam mode");
            throw new RuntimeException("getNextMultiCameraId() called but not in multi-cam mode");
        }
        List<Integer> camera_set;
        // don't use preview.getCameraController(), as it may be null if user quickly switches between cameras
        int currCameraId = getActualCameraId();
        switch( preview.getCameraControllerManager().getFacing(currCameraId) ) {
            case FACING_BACK:
                camera_set = back_camera_ids;
                break;
            case FACING_FRONT:
                camera_set = front_camera_ids;
                break;
            default:
                camera_set = other_camera_ids;
                break;
        }
        int cameraId;
        int indx = camera_set.indexOf(currCameraId);
        if( indx == -1 ) {
            Log.e(TAG, "camera id not in current camera set");
            // this shouldn't happen, but if it does, revert to the first camera id in the set
            cameraId = camera_set.get(0);
        }
        else {
            indx = (indx+1) % camera_set.size();
            cameraId = camera_set.get(indx);
        }
        if( MyDebug.LOG )
            Log.d(TAG, "next multi cameraId: " + cameraId);
        return cameraId;
    }

    private void pushCameraIdToast(int cameraId) {
        if( MyDebug.LOG )
            Log.d(TAG, "pushCameraIdToast: " + cameraId);
        if( preview.getCameraControllerManager().getNumberOfCameras() > 2 ) {
            // telling the user which camera is pointless for only two cameras, but on devices that now
            // expose many cameras it can be confusing, so show a toast to at least display the id
            String description = preview.getCameraControllerManager().getDescription(this, cameraId);
            if( description != null ) {
                String toast_string = description + ": ";
                toast_string += getResources().getString(R.string.camera_id) + " " + cameraId;
                //preview.showToast(null, toast_string);
                this.push_info_toast_text = toast_string;
            }
        }
    }

    private void userSwitchToCamera(int cameraId) {
        if( MyDebug.LOG )
            Log.d(TAG, "userSwitchToCamera: " + cameraId);
        View switchCameraButton = findViewById(R.id.switch_camera);
        View switchMultiCameraButton = findViewById(R.id.switch_multi_camera);
        // prevent slowdown if user repeatedly clicks:
        switchCameraButton.setEnabled(false);
        switchMultiCameraButton.setEnabled(false);
        applicationInterface.reset(true);
        this.preview.setCamera(cameraId);
        switchCameraButton.setEnabled(true);
        switchMultiCameraButton.setEnabled(true);
        // no need to call mainUI.setSwitchCameraContentDescription - this will be called from Preview.cameraSetup when the
        // new camera is opened
    }

    /**
     * Selects the next camera on the phone - in practice, switches between
     * front and back cameras
     */
    public void clickedSwitchCamera(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedSwitchCamera");
        if( preview.isOpeningCamera() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "already opening camera in background thread");
            return;
        }
        if( this.preview.canSwitchCamera() ) {
            int cameraId = getNextCameraId();
            if( !isMultiCamEnabled() ) {
                pushCameraIdToast(cameraId);
            }
            else {
                // In multi-cam mode, no need to show the toast when just switching between front and back cameras.
                // But it is useful to clear an active fake toast, otherwise have issue if the user uses
                // clickedSwitchMultiCamera() (which displays a fake toast for the camera via the info toast), then
                // immediately uses clickedSwitchCamera() - the toast for the wrong camera will still be lingering
                // until it expires, which looks a bit strange.
                // (If using non-fake toasts, this isn't an issue, at least on Android 10+, as now toasts seem to
                // disappear when the user touches the screen anyway.)
                preview.clearActiveFakeToast();
            }
            userSwitchToCamera(cameraId);
        }
    }

    public void clickedSwitchMultiCamera(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedSwitchMultiCamera");
        if( !isMultiCamEnabled() ) {
            Log.e(TAG, "switch multi camera icon shouldn't have been visible");
            return;
        }
        if( preview.isOpeningCamera() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "already opening camera in background thread");
            return;
        }

        if( this.preview.canSwitchCamera() ) {
            int cameraId = getNextMultiCameraId();
            pushCameraIdToast(cameraId);
            userSwitchToCamera(cameraId);
        }
    }

    /**
     * Toggles Photo/Video mode
     */
    public void clickedSwitchVideo(View view) {

        View switchVideoButton = findViewById(R.id.switch_video);
        switchVideoButton.setEnabled(false); // prevent slowdown if user repeatedly clicks
        applicationInterface.reset(false);
        this.preview.switchVideo(false, true);
        switchVideoButton.setEnabled(true);

        mainUI.setTakePhotoIcon();
        mainUI.setPopupIcon(); // needed as turning to video mode or back can turn flash mode off or back on

        // ensure icons invisible if they're affected by being in video mode or not (e.g., on-screen RAW icon)
        // (if enabling them, we'll make the icon visible later on)
        checkDisableGUIIcons();

        if( !block_startup_toast ) {
            this.showPhotoVideoToast(true);
        }
    }

    public void clickedWhiteBalanceLock(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedWhiteBalanceLock");
        this.preview.toggleWhiteBalanceLock();
        mainUI.updateWhiteBalanceLockIcon();
        preview.showToast(white_balance_lock_toast, preview.isWhiteBalanceLocked() ? R.string.white_balance_locked : R.string.white_balance_unlocked);
    }

    public void clickedExposureLock(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedExposureLock");
        this.preview.toggleExposureLock();
        mainUI.updateExposureLockIcon();
        preview.showToast(exposure_lock_toast, preview.isExposureLocked() ? R.string.exposure_locked : R.string.exposure_unlocked);
    }

    public void clickedExposure(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedExposure");
        mainUI.toggleExposureUI();
    }

    // for testing
    public View getUIButton(String key) {
        return mainUI.getUIButton(key);
    }


    public Bitmap getPreloadedBitmap(int resource) {
        return this.preloaded_bitmap_resources.get(resource);
    }


    /** Disables the optional on-screen icons if either user doesn't want to enable them, or not
     *  supported). Note that displaying icons is done via MainUI.showGUI.
     * @return Whether an icon's visibility was changed.
     */
    private boolean checkDisableGUIIcons() {
        if( MyDebug.LOG )
            Log.d(TAG, "checkDisableGUIIcons");
        boolean changed = false;
        if( !mainUI.showExposureLockIcon() ) {
            View button = findViewById(R.id.exposure_lock);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showWhiteBalanceLockIcon() ) {
            View button = findViewById(R.id.white_balance_lock);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showCycleRawIcon() ) {
            View button = findViewById(R.id.cycle_raw);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showStoreLocationIcon() ) {
            View button = findViewById(R.id.store_location);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showTextStampIcon() ) {
            View button = findViewById(R.id.text_stamp);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showStampIcon() ) {
            View button = findViewById(R.id.stamp);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showAutoLevelIcon() ) {
            View button = findViewById(R.id.auto_level);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showCycleFlashIcon() ) {
            View button = findViewById(R.id.cycle_flash);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        if( !showSwitchMultiCamIcon() ) {
            // also handle the multi-cam icon here, as this can change when switching between front/back cameras
            // (e.g., if say a device only has multiple back cameras)
            View button = findViewById(R.id.switch_multi_camera);
            changed = changed || (button.getVisibility() != View.GONE);
            button.setVisibility(View.GONE);
        }
        return changed;
    }

    @Override
    public void onBackPressed() {
        if( MyDebug.LOG )
            Log.d(TAG, "onBackPressed");
        if( screen_is_locked ) {
            preview.showToast(screen_locked_toast, R.string.screen_is_locked);
            return;
        }
        else if( preview != null && preview.isPreviewPaused() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "preview was paused, so unpause it");
            preview.startCameraPreview();
            return;
        }
        else {
        }
        super.onBackPressed();
    }

    public boolean usingKitKatImmersiveMode() {
        // whether we are using a Kit Kat style immersive mode (either hiding GUI, or everything)
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            String immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
            if( immersive_mode.equals("immersive_mode_navigation") || immersive_mode.equals("immersive_mode_gui") || immersive_mode.equals("immersive_mode_everything") )
                return true;
        }
        return false;
    }
    public boolean usingKitKatImmersiveModeEverything() {
        // whether we are using a Kit Kat style immersive mode for everything
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            String immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
            if( immersive_mode.equals("immersive_mode_everything") )
                return true;
        }
        return false;
    }


    private Handler immersive_timer_handler = null;
    private Runnable immersive_timer_runnable = null;

    private void setImmersiveTimer() {
        if( immersive_timer_handler != null && immersive_timer_runnable != null ) {
            immersive_timer_handler.removeCallbacks(immersive_timer_runnable);
        }
        immersive_timer_handler = new Handler();
        immersive_timer_handler.postDelayed(immersive_timer_runnable = new Runnable(){
            @Override
            public void run(){
                if( MyDebug.LOG )
                    Log.d(TAG, "setImmersiveTimer: run");
                if( !camera_in_background && usingKitKatImmersiveMode() )
                    setImmersiveMode(true);
            }
        }, 5000);
    }

    public void initImmersiveMode() {
        if( !usingKitKatImmersiveMode() ) {
            setImmersiveMode(true);
        }
        else {
            // don't start in immersive mode, only after a timer
            setImmersiveTimer();
        }
    }

    void setImmersiveMode(boolean on) {
        if( MyDebug.LOG )
            Log.d(TAG, "setImmersiveMode: " + on);
        // n.b., preview.setImmersiveMode() is called from onSystemUiVisibilityChange()
        if( on ) {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && usingKitKatImmersiveMode() ) {

                 {
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
                }
            }
            else {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                String immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
                if( immersive_mode.equals("immersive_mode_low_profile") )
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
                else
                    getWindow().getDecorView().setSystemUiVisibility(0);
            }
        }
        else
            getWindow().getDecorView().setSystemUiVisibility(0);
    }

    /** Sets the brightness level for normal operation (when camera preview is visible).
     *  If force_max is true, this always forces maximum brightness; otherwise this depends on user preference.
     */
    public void setBrightnessForCamera(boolean force_max) {
        if( MyDebug.LOG )
            Log.d(TAG, "setBrightnessForCamera");
        // set screen to max brightness - see http://stackoverflow.com/questions/11978042/android-screen-brightness-max-value
        // done here rather than onCreate, so that changing it in preferences takes effect without restarting app
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final WindowManager.LayoutParams layout = getWindow().getAttributes();
        if( force_max || sharedPreferences.getBoolean(PreferenceKeys.MaxBrightnessPreferenceKey, true) ) {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        }
        else {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }

        // this must be called from the ui thread
        // sometimes this method may be called not on UI thread, e.g., Preview.takePhotoWhenFocused->CameraController2.takePicture
        // ->CameraController2.runFakePrecapture->Preview/onFrontScreenTurnOn->MyApplicationInterface.turnFrontScreenFlashOn
        // -> this.setBrightnessForCamera
        this.runOnUiThread(new Runnable() {
            public void run() {
                getWindow().setAttributes(layout);
            }
        });
    }

    /**
     * Set the brightness to minimal in case the preference key is set to do it
     */
    public void setBrightnessToMinimumIfWanted() {
        if( MyDebug.LOG )
            Log.d(TAG, "setBrightnessToMinimum");
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final WindowManager.LayoutParams layout = getWindow().getAttributes();
        if( sharedPreferences.getBoolean(PreferenceKeys.DimWhenDisconnectedPreferenceKey, false) ) {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        }
        else {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }

        this.runOnUiThread(new Runnable() {
            public void run() {
                getWindow().setAttributes(layout);
            }
        });

    }

    /** Sets the window flags for normal operation (when camera preview is visible).
     */
    public void setWindowFlagsForCamera() {
        if( MyDebug.LOG )
            Log.d(TAG, "setWindowFlagsForCamera");
    	/*{
    		Intent intent = new Intent(this, MyWidgetProvider.class);
    		intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
    		AppWidgetManager widgetManager = AppWidgetManager.getInstance(this);
    		ComponentName widgetComponent = new ComponentName(this, MyWidgetProvider.class);
    		int[] widgetIds = widgetManager.getAppWidgetIds(widgetComponent);
    		intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds);
    		sendBroadcast(intent);
    	}*/
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // force to landscape mode
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE); // testing for devices with unusual sensor orientation (e.g., Nexus 5X)
        if( preview != null ) {
            // also need to call setCameraDisplayOrientation, as this handles if the user switched from portrait to reverse landscape whilst in settings/etc
            // as switching from reverse landscape back to landscape isn't detected in onConfigurationChanged
            preview.setCameraDisplayOrientation();
        }
        if( preview != null && mainUI != null ) {
            // layoutUI() is needed because even though we call layoutUI from MainUI.onOrientationChanged(), certain things
            // (ui_rotation) depend on the system orientation too.
            // Without this, going to Settings, then changing orientation, then exiting settings, would show the icons with the
            // wrong orientation.
            // We put this here instead of onConfigurationChanged() as onConfigurationChanged() isn't called when switching from
            // reverse landscape to landscape orientation: so it's needed to fix if the user starts in portrait, goes to settings
            // or a dialog, then switches to reverse landscape, then exits settings/dialog - the system orientation will switch
            // to landscape (which Open Camera is forced to).
            mainUI.layoutUI();
        }


        // keep screen active - see http://stackoverflow.com/questions/2131948/force-screen-on
        if( sharedPreferences.getBoolean(PreferenceKeys.KeepDisplayOnPreferenceKey, true) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "do keep screen on");
            this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "don't keep screen on");
            this.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if( sharedPreferences.getBoolean(PreferenceKeys.ShowWhenLockedPreferenceKey, true) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "do show when locked");
            // keep Open Camera on top of screen-lock (will still need to unlock when going to gallery or settings)
            showWhenLocked(true);
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "don't show when locked");
            showWhenLocked(false);
        }

        if( want_no_limits && navigation_gap != 0 ) {
            if( MyDebug.LOG )
                Log.d(TAG, "set FLAG_LAYOUT_NO_LIMITS");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }

        setBrightnessForCamera(false);

        initImmersiveMode();
        camera_in_background = false;

        if( !app_is_paused ) {
            // Needs to be called after camera_in_background is set to false.
            // Note that the app_is_paused guard is in some sense unnecessary, as initLocation tests for that too,
            // but useful for error tracking - ideally we want to make sure that initLocation is never called when
            // app is paused. It can happen here because setWindowFlagsForCamera() is called from
            // onCreate()
        }
    }

    private void setWindowFlagsForSettings() {
        setWindowFlagsForSettings(true);
    }

    /** Sets the window flags for when the settings window is open.
     * @param set_lock_protect If true, then window flags will be set to protect by screen lock, no
     *                         matter what the preference setting
     *                         PreferenceKeys.getShowWhenLockedPreferenceKey() is set to. This
     *                         should be true for the Settings window, and anything else that might
     *                         need protecting. But some callers use this method for opening other
     *                         things (such as info dialogs).
     */
    public void setWindowFlagsForSettings(boolean set_lock_protect) {
        if( MyDebug.LOG )
            Log.d(TAG, "setWindowFlagsForSettings: " + set_lock_protect);
        // allow screen rotation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        // revert to standard screen blank behaviour
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if( want_no_limits && navigation_gap != 0 ) {
            if( MyDebug.LOG )
                Log.d(TAG, "clear FLAG_LAYOUT_NO_LIMITS");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
        if( set_lock_protect ) {
            // settings should still be protected by screen lock
            showWhenLocked(false);
        }

        {
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(layout);
        }

        setImmersiveMode(false);
        camera_in_background = true;
    }

    private void showWhenLocked(boolean show) {
        if( MyDebug.LOG )
            Log.d(TAG, "showWhenLocked: " + show);
        // although FLAG_SHOW_WHEN_LOCKED is deprecated, setShowWhenLocked(false) does not work
        // correctly: if we turn screen off and on when camera is open (so we're now running above
        // the lock screen), going to settings does not show the lock screen, i.e.,
        // setShowWhenLocked(false) does not take effect!
		/*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			if( MyDebug.LOG )
				Log.d(TAG, "use setShowWhenLocked");
			setShowWhenLocked(show);
		}
		else*/ {
            if( show ) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            }
            else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            }
        }
    }

    /** Use this is place of simply alert.show(), if the orientation has just been set to allow
     *  rotation via setWindowFlagsForSettings(). On some devices (e.g., OnePlus 3T with Android 8),
     *  the dialog doesn't show properly if the phone is held in portrait. A workaround seems to be
     *  to use postDelayed. Note that postOnAnimation() doesn't work.
     */
    public void showAlert(final AlertDialog alert) {
        if( MyDebug.LOG )
            Log.d(TAG, "showAlert");
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                alert.show();
            }
        }, 20);
        // note that 1ms usually fixes the problem, but not always; 10ms seems fine, have set 20ms
        // just in case
    }

    public void showPreview(boolean show) {
        if( MyDebug.LOG )
            Log.d(TAG, "showPreview: " + show);
        final ViewGroup container = findViewById(R.id.hide_container);
        container.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    /** Shows the default "blank" gallery icon, when we don't have a thumbnail available.
     */
    private void updateGalleryIconToBlank() {
        if( MyDebug.LOG )
            Log.d(TAG, "updateGalleryIconToBlank");
        ImageButton galleryButton = this.findViewById(R.id.gallery);
        int bottom = galleryButton.getPaddingBottom();
        int top = galleryButton.getPaddingTop();
        int right = galleryButton.getPaddingRight();
        int left = galleryButton.getPaddingLeft();
	    /*if( MyDebug.LOG )
			Log.d(TAG, "padding: " + bottom);*/
        galleryButton.setImageBitmap(null);
        galleryButton.setImageResource(R.drawable.baseline_photo_library_white_48);
        // workaround for setImageResource also resetting padding, Android bug
        galleryButton.setPadding(left, top, right, bottom);
        gallery_bitmap = null;
    }

    /** Shows a thumbnail for the gallery icon.
     */
    void updateGalleryIcon(Bitmap thumbnail) {
        if( MyDebug.LOG )
            Log.d(TAG, "updateGalleryIcon: " + thumbnail);
        ImageButton galleryButton = this.findViewById(R.id.gallery);
        galleryButton.setImageBitmap(thumbnail);
        gallery_bitmap = thumbnail;
    }

    /** Updates the gallery icon by searching for the most recent photo.
     *  Launches the task in a separate thread.
     */

    void savingImage(final boolean started) {
        if( MyDebug.LOG )
            Log.d(TAG, "savingImage: " + started);

        this.runOnUiThread(new Runnable() {
            public void run() {
                final ImageButton galleryButton = findViewById(R.id.gallery);
                if( started ) {
                    //galleryButton.setColorFilter(0x80ffffff, PorterDuff.Mode.MULTIPLY);
                    if( gallery_save_anim == null ) {
                        gallery_save_anim = ValueAnimator.ofInt(Color.argb(200, 255, 255, 255), Color.argb(63, 255, 255, 255));
                        gallery_save_anim.setEvaluator(new ArgbEvaluator());
                        gallery_save_anim.setRepeatCount(ValueAnimator.INFINITE);
                        gallery_save_anim.setRepeatMode(ValueAnimator.REVERSE);
                        gallery_save_anim.setDuration(500);
                    }
                    gallery_save_anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            galleryButton.setColorFilter((Integer)animation.getAnimatedValue(), PorterDuff.Mode.MULTIPLY);
                        }
                    });
                    gallery_save_anim.start();
                }
                else
                if( gallery_save_anim != null ) {
                    gallery_save_anim.cancel();
                }
                galleryButton.setColorFilter(null);
            }
        });
    }

    /** Called when the number of images being saved in ImageSaver changes (or otherwise something
     *  that changes our calculation of whether we can take a new photo, e.g., changing photo mode).
     */
    void imageQueueChanged() {
        if( MyDebug.LOG )
            Log.d(TAG, "imageQueueChanged");

        if( applicationInterface.getImageSaver().getNImagesToSave() == 0) {
            cancelImageSavingNotification();
        }
        else if( has_notification) {
            // call again to update the text of remaining images
            createImageSavingNotification();
        }
    }

    /** Creates a notification to indicate still saving images (or updates an existing one).
     */
    private void createImageSavingNotification() {
        if( MyDebug.LOG )
            Log.d(TAG, "createImageSavingNotification");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            int n_images_to_save = applicationInterface.getImageSaver().getNRealImagesToSave();
            Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notify_take_photo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.image_saving_notification) + " " + n_images_to_save + " " + getString(R.string.remaining))
                    //.setStyle(new Notification.BigTextStyle()
                    //        .bigText("Much longer text that cannot fit one line..."))
                    //.setPriority(Notification.PRIORITY_DEFAULT)
                    ;
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.notify(image_saving_notification_id, builder.build());
            has_notification = true;
        }
    }

    /** Cancels the notification for saving images.
     */
    private void cancelImageSavingNotification() {
        if( MyDebug.LOG )
            Log.d(TAG, "cancelImageSavingNotification");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.cancel(image_saving_notification_id);
            has_notification = false;
        }
    }


    /** Opens the Storage Access Framework dialog to select a file for ghost image.
     * @param from_preferences Whether called from the Preferences
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void openGhostImageChooserDialogSAF(boolean from_preferences) {
        if( MyDebug.LOG )
            Log.d(TAG, "openGhostImageChooserDialogSAF: " + from_preferences);
        this.saf_dialog_from_preferences = from_preferences;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, CHOOSE_GHOST_IMAGE_SAF_CODE);
        }
        catch(ActivityNotFoundException e) {
            // see https://stackoverflow.com/questions/34021039/action-open-document-not-working-on-miui/34045627
            preview.showToast(null, R.string.open_files_saf_exception_ghost);
            Log.e(TAG, "ActivityNotFoundException from startActivityForResult");
            e.printStackTrace();
        }
    }

    /** Listens for the response from the Storage Access Framework dialog to select a folder
     *  (as opened with openFolderChooserDialogSAF()).
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if( MyDebug.LOG )
            Log.d(TAG, "onActivityResult: " + requestCode);
        switch( requestCode ) {
            case CHOOSE_GHOST_IMAGE_SAF_CODE:
                if( resultCode == RESULT_OK && resultData != null ) {
                    Uri fileUri = resultData.getData();
                    if( MyDebug.LOG )
                        Log.d(TAG, "returned single fileUri: " + fileUri);
                    // persist permission just in case?
                    final int takeFlags = resultData.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
					/*if( true )
						throw new SecurityException(); // test*/
                        // Check for the freshest data.
                        getContentResolver().takePersistableUriPermission(fileUri, takeFlags);

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(PreferenceKeys.GhostSelectedImageSAFPreferenceKey, fileUri.toString());
                        editor.apply();
                    }
                    catch(SecurityException e) {
                        Log.e(TAG, "SecurityException failed to take permission");
                        e.printStackTrace();
                        preview.showToast(null, R.string.saf_permission_failed_open_image);
                        // failed - if the user had yet to set a ghost image
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        String uri = sharedPreferences.getString(PreferenceKeys.GhostSelectedImageSAFPreferenceKey, "");
                        if( uri.length() == 0 ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "no SAF ghost image was set");
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putString(PreferenceKeys.GhostImagePreferenceKey, "preference_ghost_image_off");
                            editor.apply();
                        }
                    }
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "SAF dialog cancelled");
                    // cancelled - if the user had yet to set a ghost image, make sure we switch the option back off
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                    String uri = sharedPreferences.getString(PreferenceKeys.GhostSelectedImageSAFPreferenceKey, "");
                    if( uri.length() == 0 ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "no SAF ghost image was set");
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(PreferenceKeys.GhostImagePreferenceKey, "preference_ghost_image_off");
                        editor.apply();
                    }
                }

                if( !saf_dialog_from_preferences ) {
                    setWindowFlagsForCamera();
                    showPreview(true);
                }
                break;
            case CHOOSE_LOAD_SETTINGS_SAF_CODE:
                if( resultCode == RESULT_OK && resultData != null ) {
                    Uri fileUri = resultData.getData();
                    if( MyDebug.LOG )
                        Log.d(TAG, "returned single fileUri: " + fileUri);
                    // persist permission just in case?
                    final int takeFlags = resultData.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
					/*if( true )
						throw new SecurityException(); // test*/
                        // Check for the freshest data.
                        getContentResolver().takePersistableUriPermission(fileUri, takeFlags);

                        settingsManager.loadSettings(fileUri);
                    }
                    catch(SecurityException e) {
                        Log.e(TAG, "SecurityException failed to take permission");
                        e.printStackTrace();
                        preview.showToast(null, R.string.restore_settings_failed);
                    }
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "SAF dialog cancelled");
                }

                if( !saf_dialog_from_preferences ) {
                    setWindowFlagsForCamera();
                    showPreview(true);
                }
                break;
        }
    }



    static private void putBundleExtra(Bundle bundle, String key, List<String> values) {
        if( values != null ) {
            String [] values_arr = new String[values.size()];
            int i=0;
            for(String value: values) {
                values_arr[i] = value;
                i++;
            }
            bundle.putStringArray(key, values_arr);
        }
    }

    /** User has pressed the take picture button, or done an equivalent action to request this (e.g.,
     *  volume buttons, audio trigger).
     * @param photo_snapshot If true, then the user has requested taking a photo whilst video
     *                       recording. If false, either take a photo or start/stop video depending
     *                       on the current mode.
     */
    public void takePicture(boolean photo_snapshot) {
        if( MyDebug.LOG )
            Log.d(TAG, "takePicture");


        this.takePicturePressed(photo_snapshot, false);
    }

    /** Returns whether the last photo operation was a continuous fast burst.
     */
    boolean lastContinuousFastBurst() {
        return this.last_continuous_fast_burst;
    }

    /**
     * @param photo_snapshot If true, then the user has requested taking a photo whilst video
     *                       recording. If false, either take a photo or start/stop video depending
     *                       on the current mode.
     * @param continuous_fast_burst If true, then start a continuous fast burst.
     */
    void takePicturePressed(boolean photo_snapshot, boolean continuous_fast_burst) {
        if( MyDebug.LOG )
            Log.d(TAG, "takePicturePressed");

        this.last_continuous_fast_burst = continuous_fast_burst;
        this.preview.takePicturePressed(photo_snapshot, continuous_fast_burst);
    }

    /** Lock the screen - this is Open Camera's own lock to guard against accidental presses,
     *  not the standard Android lock.
     */
    void lockScreen() {
        findViewById(R.id.locker).setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility") @Override
            public boolean onTouch(View arg0, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
                //return true;
            }
        });
        screen_is_locked = true;
    }

    /** Unlock the screen (see lockScreen()).
     */
    void unlockScreen() {
        findViewById(R.id.locker).setOnTouchListener(null);
        screen_is_locked = false;
    }

    /** Whether the screen is locked (see lockScreen()).
     */
    public boolean isScreenLocked() {
        return screen_is_locked;
    }

    /** Listen for gestures.
     *  Doing a swipe will unlock the screen (see lockScreen()).
     */
    private class MyGestureDetector extends SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            try {
                if( MyDebug.LOG )
                    Log.d(TAG, "from " + e1.getX() + " , " + e1.getY() + " to " + e2.getX() + " , " + e2.getY());
                final ViewConfiguration vc = ViewConfiguration.get(MainActivity.this);
                //final int swipeMinDistance = 4*vc.getScaledPagingTouchSlop();
                final float scale = getResources().getDisplayMetrics().density;
                final int swipeMinDistance = (int) (160 * scale + 0.5f); // convert dps to pixels
                final int swipeThresholdVelocity = vc.getScaledMinimumFlingVelocity();
                if( MyDebug.LOG ) {
                    Log.d(TAG, "from " + e1.getX() + " , " + e1.getY() + " to " + e2.getX() + " , " + e2.getY());
                    Log.d(TAG, "swipeMinDistance: " + swipeMinDistance);
                }
                float xdist = e1.getX() - e2.getX();
                float ydist = e1.getY() - e2.getY();
                float dist2 = xdist*xdist + ydist*ydist;
                float vel2 = velocityX*velocityX + velocityY*velocityY;
                if( dist2 > swipeMinDistance*swipeMinDistance && vel2 > swipeThresholdVelocity*swipeThresholdVelocity ) {
                    preview.showToast(screen_locked_toast, R.string.unlocked);
                    unlockScreen();
                }
            }
            catch(Exception e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            preview.showToast(screen_locked_toast, R.string.screen_is_locked);
            return true;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if( MyDebug.LOG )
            Log.d(TAG, "onSaveInstanceState");
        super.onSaveInstanceState(state);
        if( this.preview != null ) {
            preview.onSaveInstanceState(state);
        }
        if( this.applicationInterface != null ) {
            applicationInterface.onSaveInstanceState(state);
        }
    }

    public boolean supportsExposureButton() {
        if( preview.getCameraController() == null )
            return false;
        if( preview.isVideoHighSpeed() ) {
            // manual ISO/exposure not supported for high speed video mode
            // it's safer not to allow opening the panel at all (otherwise the user could open it, and switch to manual)
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String iso_value = sharedPreferences.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
        boolean manual_iso = !iso_value.equals(CameraController.ISO_DEFAULT);
        return preview.supportsExposures() || (manual_iso && preview.supportsISORange() );
    }

    void cameraSetup() {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "cameraSetup");
            debug_time = System.currentTimeMillis();
        }

        boolean old_want_no_limits = want_no_limits;
        this.want_no_limits = false;
        if( set_window_insets_listener ) {
            Point display_size = new Point();
            Display display = getWindowManager().getDefaultDisplay();
            display.getSize(display_size);
            int display_width = Math.max(display_size.x, display_size.y);
            int display_height = Math.min(display_size.x, display_size.y);
            double display_aspect_ratio = ((double)display_width)/(double)display_height;
            double preview_aspect_ratio = preview.getCurrentPreviewAspectRatio();
            if( MyDebug.LOG ) {
                Log.d(TAG, "display_aspect_ratio: " + display_aspect_ratio);
                Log.d(TAG, "preview_aspect_ratio: " + preview_aspect_ratio);
            }
            boolean preview_is_wide = preview_aspect_ratio > display_aspect_ratio + 1.0e-5f;
            if( test_preview_want_no_limits ) {
                preview_is_wide = test_preview_want_no_limits_value;
            }
            if( preview_is_wide ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "preview is wide, set want_no_limits");
                this.want_no_limits = true;

                if( !old_want_no_limits ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "need to change to FLAG_LAYOUT_NO_LIMITS");
                    // Ideally we'd just go straight to FLAG_LAYOUT_NO_LIMITS mode, but then all calls to onApplyWindowInsets()
                    // end up returning a value of 0 for the navigation_gap! So we need to wait until we know the navigation_gap.
                    if( navigation_gap != 0 ) {
                        // already have navigation gap, can go straight into no limits mode
                        if( MyDebug.LOG )
                            Log.d(TAG, "set FLAG_LAYOUT_NO_LIMITS");
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                        // need to layout the UI again due to now taking the navigation gap into account
                        mainUI.layoutUI();
                    }
                    else {
                        if( MyDebug.LOG )
                            Log.d(TAG, "but navigation_gap is 0");
                    }
                }
            }
            else if( old_want_no_limits && navigation_gap != 0 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "clear FLAG_LAYOUT_NO_LIMITS");
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                // need to layout the UI again due to no longer taking the navigation gap into account
                mainUI.layoutUI();
            }
        }

        if( this.supportsForceVideo4K()) {
            if( MyDebug.LOG )
                Log.d(TAG, "using Camera2 API, so can disable the force 4K option");
            this.disableForceVideo4K();
        }
        if( this.supportsForceVideo4K() && preview.getVideoQualityHander().getSupportedVideoSizes() != null ) {
            for(CameraController.Size size : preview.getVideoQualityHander().getSupportedVideoSizes()) {
                if( size.width >= 3840 && size.height >= 2160 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "camera natively supports 4K, so can disable the force option");
                    this.disableForceVideo4K();
                }
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after handling Force 4K option: " + (System.currentTimeMillis() - debug_time));

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        {
            if( MyDebug.LOG )
                Log.d(TAG, "set up zoom");
            if( MyDebug.LOG )
                Log.d(TAG, "has_zoom? " + preview.supportsZoom());
            ZoomControls zoomControls = findViewById(R.id.zoom);
            SeekBar zoomSeekBar = findViewById(R.id.zoom_seekbar);

            if( preview.supportsZoom() ) {
                if( sharedPreferences.getBoolean(PreferenceKeys.ShowZoomControlsPreferenceKey, false) ) {
                    zoomControls.setIsZoomInEnabled(true);
                    zoomControls.setIsZoomOutEnabled(true);
                    zoomControls.setZoomSpeed(20);

                    zoomControls.setOnZoomInClickListener(new View.OnClickListener(){
                        public void onClick(View v){
                            zoomIn();
                        }
                    });
                    zoomControls.setOnZoomOutClickListener(new View.OnClickListener(){
                        public void onClick(View v){
                            zoomOut();
                        }
                    });
                    if( !mainUI.inImmersiveMode() ) {
                        zoomControls.setVisibility(View.VISIBLE);
                    }
                }
                else {
                    zoomControls.setVisibility(View.GONE);
                }

                zoomSeekBar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                zoomSeekBar.setMax(preview.getMaxZoom());
                zoomSeekBar.setProgress(preview.getMaxZoom()-preview.getCameraController().getZoom());
                zoomSeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "zoom onProgressChanged: " + progress);
                        // note we zoom even if !fromUser, as various other UI controls (multitouch, volume key zoom, -/+ zoomcontrol)
                        // indirectly set zoom via this method, from setting the zoom slider
                        preview.zoomTo(preview.getMaxZoom() - progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

                if( sharedPreferences.getBoolean(PreferenceKeys.ShowZoomSliderControlsPreferenceKey, true) ) {
                    if( !mainUI.inImmersiveMode() ) {
                        zoomSeekBar.setVisibility(View.VISIBLE);
                    }
                }
                else {
                    zoomSeekBar.setVisibility(View.INVISIBLE); // should be INVISIBLE not GONE, as the focus_seekbar is aligned to be left to this; in future we might want this similarly for exposure panel
                }
            }
            else {
                zoomControls.setVisibility(View.GONE);
                zoomSeekBar.setVisibility(View.INVISIBLE); // should be INVISIBLE not GONE, as the focus_seekbar is aligned to be left to this; in future we might want this similarly for the exposure panel
            }
            if( MyDebug.LOG )
                Log.d(TAG, "cameraSetup: time after setting up zoom: " + (System.currentTimeMillis() - debug_time));

            View takePhotoButton = findViewById(R.id.take_photo);
            if( sharedPreferences.getBoolean(PreferenceKeys.ShowTakePhotoPreferenceKey, true) ) {
                if( !mainUI.inImmersiveMode() ) {
                    takePhotoButton.setVisibility(View.VISIBLE);
                }
            }
            else {
                takePhotoButton.setVisibility(View.INVISIBLE);
            }
        }
        {
        }
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting up manual focus: " + (System.currentTimeMillis() - debug_time));
        {
            if( preview.supportsISORange()) {
                if( MyDebug.LOG )
                    Log.d(TAG, "set up iso");
                final SeekBar iso_seek_bar = findViewById(R.id.iso_seekbar);
                iso_seek_bar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                //setProgressSeekbarExponential(iso_seek_bar, preview.getMinimumISO(), preview.getMaximumISO(), preview.getCameraController().getISO());
                manualSeekbars.setProgressSeekbarISO(iso_seek_bar, preview.getMinimumISO(), preview.getMaximumISO(), preview.getCameraController().getISO());
                iso_seek_bar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "iso seekbar onProgressChanged: " + progress);
						/*double frac = progress/(double)iso_seek_bar.getMax();
						if( MyDebug.LOG )
							Log.d(TAG, "exposure_time frac: " + frac);
						double scaling = MainActivity.seekbarScaling(frac);
						if( MyDebug.LOG )
							Log.d(TAG, "exposure_time scaling: " + scaling);
						int min_iso = preview.getMinimumISO();
						int max_iso = preview.getMaximumISO();
						int iso = min_iso + (int)(scaling * (max_iso - min_iso));*/
						/*int min_iso = preview.getMinimumISO();
						int max_iso = preview.getMaximumISO();
						int iso = (int)exponentialScaling(frac, min_iso, max_iso);*/
                        // n.b., important to update even if fromUser==false (e.g., so this works when user changes ISO via clicking
                        // the ISO buttons rather than moving the slider directly, see MainUI.setupExposureUI())
                        preview.setISO( manualSeekbars.getISO(progress) );
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });
                if( preview.supportsExposureTime() ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "set up exposure time");
                    final SeekBar exposure_time_seek_bar = findViewById(R.id.exposure_time_seekbar);
                    exposure_time_seek_bar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                    //setProgressSeekbarExponential(exposure_time_seek_bar, preview.getMinimumExposureTime(), preview.getMaximumExposureTime(), preview.getCameraController().getExposureTime());
                    manualSeekbars.setProgressSeekbarShutterSpeed(exposure_time_seek_bar, preview.getMinimumExposureTime(), preview.getMaximumExposureTime(), preview.getCameraController().getExposureTime());
                    exposure_time_seek_bar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "exposure_time seekbar onProgressChanged: " + progress);
							/*double frac = progress/(double)exposure_time_seek_bar.getMax();
							if( MyDebug.LOG )
								Log.d(TAG, "exposure_time frac: " + frac);
							long min_exposure_time = preview.getMinimumExposureTime();
							long max_exposure_time = preview.getMaximumExposureTime();
							long exposure_time = exponentialScaling(frac, min_exposure_time, max_exposure_time);*/
                            preview.setExposureTime( manualSeekbars.getExposureTime(progress) );
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {
                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {
                        }
                    });
                }
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting up iso: " + (System.currentTimeMillis() - debug_time));
        {
            if( preview.supportsExposures() ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "set up exposure compensation");
                final int min_exposure = preview.getMinimumExposure();
                SeekBar exposure_seek_bar = findViewById(R.id.exposure_seekbar);
                exposure_seek_bar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                exposure_seek_bar.setMax( preview.getMaximumExposure() - min_exposure );
                exposure_seek_bar.setProgress( preview.getCurrentExposure() - min_exposure );
                exposure_seek_bar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "exposure seekbar onProgressChanged: " + progress);
                        preview.setExposure(min_exposure + progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

                ZoomControls seek_bar_zoom = findViewById(R.id.exposure_seekbar_zoom);
                seek_bar_zoom.setOnZoomInClickListener(new View.OnClickListener(){
                    public void onClick(View v){
                        changeExposure(1);
                    }
                });
                seek_bar_zoom.setOnZoomOutClickListener(new View.OnClickListener(){
                    public void onClick(View v){
                        changeExposure(-1);
                    }
                });
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting up exposure: " + (System.currentTimeMillis() - debug_time));

        // On-screen icons such as exposure lock, white balance lock, face detection etc are made visible if necessary in
        // MainUI.showGUI()
        // However still nee to update visibility of icons where visibility depends on camera setup - e.g., exposure button
        // not supported for high speed video frame rates - see testTakeVideoFPSHighSpeedManual().
        View exposureButton = findViewById(R.id.exposure);
        exposureButton.setVisibility(supportsExposureButton() && !mainUI.inImmersiveMode() ? View.VISIBLE : View.GONE);

        // needed as availability of some icons is per-camera (e.g., flash, RAW)
        // for making icons visible, this is done elsewhere in call to MainUI.showGUI()
        if( checkDisableGUIIcons() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "cameraSetup: need to layoutUI as we hid some icons");
            mainUI.layoutUI();
        }

        // need to update some icons, e.g., white balance and exposure lock due to them being turned off when pause/resuming
        mainUI.updateOnScreenIcons();

        mainUI.setPopupIcon(); // needed so that the icon is set right even if no flash mode is set when starting up camera (e.g., switching to front camera with no flash)
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting popup icon: " + (System.currentTimeMillis() - debug_time));

        mainUI.setTakePhotoIcon();
        mainUI.setSwitchCameraContentDescription();
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting take photo icon: " + (System.currentTimeMillis() - debug_time));

        if( !block_startup_toast ) {
            this.showPhotoVideoToast(false);
        }
        block_startup_toast = false;
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: total time for cameraSetup: " + (System.currentTimeMillis() - debug_time));
    }

    public boolean supportsAutoStabilise() {
        return this.supports_auto_stabilise;
    }


    public boolean supportsPreviewBitmaps() {
        // In practice we only use TextureView on Android 5+ (with Camera2 API enabled) anyway, but have put an explicit check here -
        // even if in future we allow TextureView pre-Android 5, we still need Android 5+ for Renderscript.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && preview.getView() instanceof TextureView && large_heap_memory >= 128;
    }

    public boolean supportsForceVideo4K() {
        return this.supports_force_video_4k;
    }

    public boolean supportsCamera2() {
        return this.supports_camera2;
    }

    private void disableForceVideo4K() {
        this.supports_force_video_4k = false;
    }

    // if we change this, remember that any page linked to must abide by Google Play developer policies!
    //public static final String DonateLink = "https://play.google.com/store/apps/details?id=harman.mark.donation";

    public Preview getPreview() {
        return this.preview;
    }

    public boolean isCameraInBackground() {
        return this.camera_in_background;
    }


    public SettingsManager getSettingsManager() {
        return settingsManager;
    }

    public MainUI getMainUI() {
        return this.mainUI;
    }

    public ManualSeekbars getManualSeekbars() {
        return this.manualSeekbars;
    }

    public MyApplicationInterface getApplicationInterface() {
        return this.applicationInterface;
    }

    public TextFormatter getTextFormatter() {
        return this.textFormatter;
    }

    public ToastBoxer getChangedAutoStabiliseToastBoxer() {
        return changed_auto_stabilise_toast;
    }

    private String getPhotoModeString(MyApplicationInterface.PhotoMode photo_mode, boolean string_for_std) {
        String photo_mode_string = null;
        switch( photo_mode ) {
            case Standard:
                if( string_for_std )
                    photo_mode_string = getResources().getString(R.string.photo_mode_standard_full);
                break;
        }
        return photo_mode_string;
    }

    /** Displays a toast with information about the current preferences.
     *  If always_show is true, the toast is always displayed; otherwise, we only display
     *  a toast if it's important to notify the user (i.e., unusual non-default settings are
     *  set). We want a balance between not pestering the user too much, whilst also reminding
     *  them if certain settings are on.
     */
    private void showPhotoVideoToast(boolean always_show) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "showPhotoVideoToast");
            Log.d(TAG, "always_show? " + always_show);
        }
        CameraController camera_controller = preview.getCameraController();
        if( camera_controller == null || this.camera_in_background ) {
            if( MyDebug.LOG )
                Log.d(TAG, "camera not open or in background");
            return;
        }
        String toast_string;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean simple = true;
        boolean video_high_speed = preview.isVideoHighSpeed();
        MyApplicationInterface.PhotoMode photo_mode = applicationInterface.getPhotoMode();
        if( preview.isVideo() ) {
            VideoProfile profile = preview.getVideoProfile();

            String extension_string = profile.fileExtension;
            if( !profile.fileExtension.equals("mp4") ) {
                simple = false;
            }

            String bitrate_string;
            if( profile.videoBitRate >= 10000000 )
                bitrate_string = profile.videoBitRate/1000000 + "Mbps";
            else if( profile.videoBitRate >= 10000 )
                bitrate_string = profile.videoBitRate/1000 + "Kbps";
            else
                bitrate_string = profile.videoBitRate + "bps";
            String bitrate_value = applicationInterface.getVideoBitratePref();
            if( !bitrate_value.equals("default") ) {
                simple = false;
            }

            double capture_rate = profile.videoCaptureRate;
            String capture_rate_string = (capture_rate < 9.5f) ? new DecimalFormat("#0.###").format(capture_rate) : "" + (int)(profile.videoCaptureRate+0.5);
            toast_string = getResources().getString(R.string.video) + ": " + profile.videoFrameWidth + "x" + profile.videoFrameHeight + "\n" +
                    capture_rate_string + getResources().getString(R.string.fps) + (video_high_speed ? " [" + getResources().getString(R.string.high_speed) + "]" : "") + ", " + bitrate_string + " (" + extension_string + ")";

            String fps_value = applicationInterface.getVideoFPSPref();
            if( !fps_value.equals("default") || video_high_speed ) {
                simple = false;
            }

            float capture_rate_factor = applicationInterface.getVideoCaptureRateFactor();
            if( Math.abs(capture_rate_factor - 1.0f) > 1.0e-5 ) {
                toast_string += "\n" + getResources().getString(R.string.preference_video_capture_rate) + ": " + capture_rate_factor + "x";
                simple = false;
            }

            {
                CameraController.TonemapProfile tonemap_profile = applicationInterface.getVideoTonemapProfile();
                if( tonemap_profile != CameraController.TonemapProfile.TONEMAPPROFILE_OFF && preview.supportsTonemapCurve() ) {
                    if( applicationInterface.getVideoTonemapProfile() != CameraController.TonemapProfile.TONEMAPPROFILE_OFF && preview.supportsTonemapCurve() ) {
                        int string_id = 0;
                        switch( tonemap_profile ) {
                            case TONEMAPPROFILE_REC709:
                                string_id = R.string.preference_video_rec709;
                                break;
                            case TONEMAPPROFILE_SRGB:
                                string_id = R.string.preference_video_srgb;
                                break;
                            case TONEMAPPROFILE_LOG:
                                string_id = R.string.video_log;
                                break;
                            case TONEMAPPROFILE_GAMMA:
                                string_id = R.string.preference_video_gamma;
                                break;
                            case TONEMAPPROFILE_JTVIDEO:
                                string_id = R.string.preference_video_jtvideo;
                                break;
                            case TONEMAPPROFILE_JTLOG:
                                string_id = R.string.preference_video_jtlog;
                                break;
                            case TONEMAPPROFILE_JTLOG2:
                                string_id = R.string.preference_video_jtlog2;
                                break;
                        }
                        if( string_id != 0 ) {
                            simple = false;
                            toast_string += "\n" + getResources().getString(string_id);
                            if( tonemap_profile == CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA ) {
                                toast_string += " " + applicationInterface.getVideoProfileGamma();
                            }
                        }
                        else {
                            Log.e(TAG, "unknown tonemap_profile: " + tonemap_profile);
                        }
                    }
                }
            }

            boolean record_audio = applicationInterface.getRecordAudioPref();
            if( !record_audio ) {
                toast_string += "\n" + getResources().getString(R.string.audio_disabled);
                simple = false;
            }
            String max_duration_value = sharedPreferences.getString(PreferenceKeys.VideoMaxDurationPreferenceKey, "0");
            if( max_duration_value.length() > 0 && !max_duration_value.equals("0") ) {
                String [] entries_array = getResources().getStringArray(R.array.preference_video_max_duration_entries);
                String [] values_array = getResources().getStringArray(R.array.preference_video_max_duration_values);
                int index = Arrays.asList(values_array).indexOf(max_duration_value);
                if( index != -1 ) { // just in case!
                    String entry = entries_array[index];
                    toast_string += "\n" + getResources().getString(R.string.max_duration) +": " + entry;
                    simple = false;
                }
            }
            long max_filesize = applicationInterface.getVideoMaxFileSizeUserPref();
            if( max_filesize != 0 ) {
                toast_string += "\n" + getResources().getString(R.string.max_filesize) +": ";
                if( max_filesize >= 1024*1024*1024 ) {
                    long max_filesize_gb = max_filesize/(1024*1024*1024);
                    toast_string += max_filesize_gb + getResources().getString(R.string.gb_abbreviation);
                }
                else {
                    long max_filesize_mb = max_filesize/(1024*1024);
                    toast_string += max_filesize_mb + getResources().getString(R.string.mb_abbreviation);
                }
                simple = false;
            }
            if( applicationInterface.getVideoFlashPref() && preview.supportsFlash() ) {
                toast_string += "\n" + getResources().getString(R.string.preference_video_flash);
                simple = false;
            }
        }
        else {

            {
                toast_string = getResources().getString(R.string.photo);
                CameraController.Size current_size = preview.getCurrentPictureSize();
                toast_string += " " + current_size.width + "x" + current_size.height;
            }

            String photo_mode_string = getPhotoModeString(photo_mode, false);
            if( photo_mode_string != null ) {
                toast_string += (toast_string.length()==0 ? "" : "\n") + getResources().getString(R.string.photo_mode) + ": " + photo_mode_string;
                simple = false;
            }

            if( preview.supportsFocus() && preview.getSupportedFocusValues().size() > 1 ) {
                String focus_value = preview.getCurrentFocusValue();
                if( focus_value != null && !focus_value.equals("focus_mode_auto") && !focus_value.equals("focus_mode_continuous_picture") ) {
                    String focus_entry = preview.findFocusEntryForValue(focus_value);
                    if( focus_entry != null ) {
                        toast_string += "\n" + focus_entry;
                    }
                }
            }

            if( applicationInterface.getAutoStabilisePref() ) {
                // important as users are sometimes confused at the behaviour if they don't realise the option is on
                toast_string += (toast_string.length()==0 ? "" : "\n") + getResources().getString(R.string.preference_auto_stabilise);
                simple = false;
            }
        }
        if( applicationInterface.getFaceDetectionPref() ) {
            // important so that the user realises why touching for focus/metering areas won't work - easy to forget that face detection has been turned on!
            toast_string += "\n" + getResources().getString(R.string.preference_face_detection);
            simple = false;
        }
        if( !video_high_speed ) {
            //manual ISO only supported for high speed video
            String iso_value = applicationInterface.getISOPref();
            if( !iso_value.equals(CameraController.ISO_DEFAULT) ) {
                toast_string += "\nISO: " + iso_value;
                if( preview.supportsExposureTime() ) {
                    long exposure_time_value = applicationInterface.getExposureTimePref();
                    toast_string += " " + preview.getExposureTimeString(exposure_time_value);
                }
                simple = false;
            }
            int current_exposure = camera_controller.getExposureCompensation();
            if( current_exposure != 0 ) {
                toast_string += "\n" + preview.getExposureCompensationString(current_exposure);
                simple = false;
            }
        }
        try {

            String color_effect = camera_controller.getColorEffect();

            if( color_effect != null && !color_effect.equals(CameraController.COLOR_EFFECT_DEFAULT) ) {
                toast_string += "\n" + getResources().getString(R.string.color_effect) + ": " + mainUI.getEntryForColorEffect(color_effect);
                simple = false;
            }
        }
        catch(RuntimeException e) {
            // catch runtime error from camera_controller old API from camera.getParameters()
            e.printStackTrace();
        }
        String lock_orientation = applicationInterface.getLockOrientationPref();
        if( !lock_orientation.equals("none") ) {
            // panorama locks to portrait, but don't want to display that in the toast
            String [] entries_array = getResources().getStringArray(R.array.preference_lock_orientation_entries);
            String [] values_array = getResources().getStringArray(R.array.preference_lock_orientation_values);
            int index = Arrays.asList(values_array).indexOf(lock_orientation);
            if( index != -1 ) { // just in case!
                String entry = entries_array[index];
                toast_string += "\n" + entry;
                simple = false;
            }
        }
        String timer = sharedPreferences.getString(PreferenceKeys.TimerPreferenceKey, "0");
        if( !timer.equals("0")  ) {
            String [] entries_array = getResources().getStringArray(R.array.preference_timer_entries);
            String [] values_array = getResources().getStringArray(R.array.preference_timer_values);
            int index = Arrays.asList(values_array).indexOf(timer);
            if( index != -1 ) { // just in case!
                String entry = entries_array[index];
                toast_string += "\n" + getResources().getString(R.string.preference_timer) + ": " + entry;
                simple = false;
            }
        }
        String repeat = applicationInterface.getRepeatPref();
        if( !repeat.equals("1") ) {
            String [] entries_array = getResources().getStringArray(R.array.preference_burst_mode_entries);
            String [] values_array = getResources().getStringArray(R.array.preference_burst_mode_values);
            int index = Arrays.asList(values_array).indexOf(repeat);
            if( index != -1 ) { // just in case!
                String entry = entries_array[index];
                toast_string += "\n" + getResources().getString(R.string.preference_burst_mode) + ": " + entry;
                simple = false;
            }
        }
		/*if( audio_listener != null ) {
			toast_string += "\n" + getResources().getString(R.string.preference_audio_noise_control);
		}*/

        if( MyDebug.LOG ) {
            Log.d(TAG, "toast_string: " + toast_string);
            Log.d(TAG, "simple?: " + simple);
            Log.d(TAG, "push_info_toast_text: " + push_info_toast_text);
        }
        final boolean use_fake_toast = true;
        if( !simple || always_show ) {
            if( push_info_toast_text != null ) {
                toast_string = push_info_toast_text + "\n" + toast_string;
            }
            preview.showToast(switch_video_toast, toast_string, use_fake_toast);
        }
        else if( push_info_toast_text != null ) {
            preview.showToast(switch_video_toast, push_info_toast_text, use_fake_toast);
        }
        push_info_toast_text = null; // reset
    }



    public boolean hasAudioControl() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String audio_control = sharedPreferences.getString(PreferenceKeys.AudioControlPreferenceKey, "none");
        if( audio_control.equals("voice") ) {
            return speechControl.hasSpeechRecognition();
        }
        else if( audio_control.equals("noise") ) {
            return true;
        }
        return false;
    }



    void speak(String text) {
        if( textToSpeech != null && textToSpeechSuccess ) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if( MyDebug.LOG )
            Log.d(TAG, "onRequestPermissionsResult: requestCode " + requestCode);
    }

    public void restartOpenCamera() {
        if( MyDebug.LOG )
            Log.d(TAG, "restartOpenCamera");
        this.waitUntilImageQueueEmpty();
        // see http://stackoverflow.com/questions/2470870/force-application-to-restart-on-first-activity
        Intent intent = this.getBaseContext().getPackageManager().getLaunchIntentForPackage( this.getBaseContext().getPackageName() );
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        this.startActivity(intent);
    }

    ToastBoxer getAudioControlToast() {
        return this.audio_control_toast;
    }

    // for testing:

    public boolean hasThumbnailAnimation() {
        return this.applicationInterface.hasThumbnailAnimation();
    }

    public boolean testHasNotification() {
        return has_notification;
    }
}
