package net.sourceforge.opencamera.ui;

import net.sourceforge.opencamera.MyApplicationInterface;
import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.preview.ApplicationInterface;
import net.sourceforge.opencamera.preview.Preview;
import net.sourceforge.opencamera.R;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.ZoomControls;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

/** This contains functionality related to the main UI.
 */
public class MainUI {
    private static final String TAG = "MainUI";

    private final MainActivity main_activity;

    private int current_orientation;
    enum UIPlacement {
        UIPLACEMENT_RIGHT,
        UIPLACEMENT_LEFT,
        UIPLACEMENT_TOP
    }
    private UIPlacement ui_placement = UIPlacement.UIPLACEMENT_RIGHT;
    private View top_icon = null;
    private boolean view_rotate_animation;
    private final static int view_rotate_animation_duration = 100; // duration in ms of the icon rotation animation

    private boolean immersive_mode;
    private boolean show_gui_photo = true; // result of call to showGUI() - false means a "reduced" GUI is displayed, whilst taking photo or video
    private boolean show_gui_video = true;

    private boolean keydown_volume_up;
    private boolean keydown_volume_down;

    // For remote control: keep track of the currently highlighted
    // line and icon within the line
    private boolean remote_control_mode; // whether remote control mode is enabled
    private int mPopupLine = 0;
    private int mPopupIcon = 0;
    private LinearLayout mHighlightedLine;
    private View mHighlightedIcon;
    private boolean mSelectingIcons = false;
    private boolean mSelectingLines = false;
    private int mExposureLine = 0;
    private boolean mSelectingExposureUIElement = false;
    private final int highlightColor = Color.rgb(183, 28, 28); // Red 900
    private final int highlightColorExposureUIElement = Color.rgb(244, 67, 54); // Red 500

    // for testing:
    private final Map<String, View> test_ui_buttons = new Hashtable<>();
    public int test_saved_popup_width;
    public int test_saved_popup_height;
    public volatile int test_navigation_gap;

    public MainUI(MainActivity main_activity) {
        if( MyDebug.LOG )
            Log.d(TAG, "MainUI");
        this.main_activity = main_activity;

        this.setSeekbarColors();
    }

    private void setSeekbarColors() {
        if( MyDebug.LOG )
            Log.d(TAG, "setSeekbarColors");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
            ColorStateList progress_color = ColorStateList.valueOf( Color.argb(255, 240, 240, 240) );
            ColorStateList thumb_color = ColorStateList.valueOf( Color.argb(255, 255, 255, 255) );

            SeekBar seekBar = main_activity.findViewById(R.id.zoom_seekbar);
            seekBar.setProgressTintList(progress_color);
            seekBar.setThumbTintList(thumb_color);

            seekBar = main_activity.findViewById(R.id.focus_seekbar);
            seekBar.setProgressTintList(progress_color);
            seekBar.setThumbTintList(thumb_color);

            seekBar = main_activity.findViewById(R.id.focus_bracketing_target_seekbar);
            seekBar.setProgressTintList(progress_color);
            seekBar.setThumbTintList(thumb_color);

            seekBar = main_activity.findViewById(R.id.exposure_seekbar);
            seekBar.setProgressTintList(progress_color);
            seekBar.setThumbTintList(thumb_color);

            seekBar = main_activity.findViewById(R.id.iso_seekbar);
            seekBar.setProgressTintList(progress_color);
            seekBar.setThumbTintList(thumb_color);

            seekBar = main_activity.findViewById(R.id.exposure_time_seekbar);
            seekBar.setProgressTintList(progress_color);
            seekBar.setThumbTintList(thumb_color);

            seekBar = main_activity.findViewById(R.id.white_balance_seekbar);
            seekBar.setProgressTintList(progress_color);
            seekBar.setThumbTintList(thumb_color);
        }
    }

    /** Similar view.setRotation(ui_rotation), but achieves this via an animation.
     */
    private void setViewRotation(View view, float ui_rotation) {
        if( !view_rotate_animation ) {
            view.setRotation(ui_rotation);
        }
        float rotate_by = ui_rotation - view.getRotation();
        if( rotate_by > 181.0f )
            rotate_by -= 360.0f;
        else if( rotate_by < -181.0f )
            rotate_by += 360.0f;
        // view.animate() modifies the view's rotation attribute, so it ends up equivalent to view.setRotation()
        // we use rotationBy() instead of rotation(), so we get the minimal rotation for clockwise vs anti-clockwise
        view.animate().rotationBy(rotate_by).setDuration(view_rotate_animation_duration).setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    public void layoutUI() {
        layoutUI(false);
    }

    private UIPlacement computeUIPlacement() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        String ui_placement_string = sharedPreferences.getString(PreferenceKeys.UIPlacementPreferenceKey, "ui_top");
        switch( ui_placement_string ) {
            case "ui_left":
                return UIPlacement.UIPLACEMENT_LEFT;
            case "ui_top":
                return UIPlacement.UIPLACEMENT_TOP;
            default:
                return UIPlacement.UIPLACEMENT_RIGHT;
        }
    }

    private void layoutUI(boolean popup_container_only) {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "layoutUI");
            debug_time = System.currentTimeMillis();
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        // we cache the preference_ui_placement to save having to check it in the draw() method
        this.ui_placement = computeUIPlacement();
        if( MyDebug.LOG )
            Log.d(TAG, "ui_placement: " + ui_placement);
        // new code for orientation fixed to landscape
        // the display orientation should be locked to landscape, but how many degrees is that?
        int rotation = main_activity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default:
                break;
        }
        // getRotation is anti-clockwise, but current_orientation is clockwise, so we add rather than subtract
        // relative_orientation is clockwise from landscape-left
        //int relative_orientation = (current_orientation + 360 - degrees) % 360;
        int relative_orientation = (current_orientation + degrees) % 360;
        if( MyDebug.LOG ) {
            Log.d(TAG, "    current_orientation = " + current_orientation);
            Log.d(TAG, "    degrees = " + degrees);
            Log.d(TAG, "    relative_orientation = " + relative_orientation);
        }
        final int ui_rotation = (360 - relative_orientation) % 360;
        main_activity.getPreview().setUIRotation(ui_rotation);
        int align_left = RelativeLayout.ALIGN_LEFT;
        int align_right = RelativeLayout.ALIGN_RIGHT;
        //int align_top = RelativeLayout.ALIGN_TOP;
        //int align_bottom = RelativeLayout.ALIGN_BOTTOM;
        int left_of = RelativeLayout.LEFT_OF;
        int right_of = RelativeLayout.RIGHT_OF;
        int iconpanel_left_of = left_of;
        int iconpanel_right_of = right_of;
        int above = RelativeLayout.ABOVE;
        int below = RelativeLayout.BELOW;
        int iconpanel_above = above;
        int iconpanel_below = below;
        int align_parent_left = RelativeLayout.ALIGN_PARENT_LEFT;
        int align_parent_right = RelativeLayout.ALIGN_PARENT_RIGHT;
        int iconpanel_align_parent_left = align_parent_left;
        int iconpanel_align_parent_right = align_parent_right;
        int align_parent_top = RelativeLayout.ALIGN_PARENT_TOP;
        int align_parent_bottom = RelativeLayout.ALIGN_PARENT_BOTTOM;
        int iconpanel_align_parent_top = align_parent_top;
        int iconpanel_align_parent_bottom = align_parent_bottom;
        if( ui_placement == UIPlacement.UIPLACEMENT_LEFT ) {
            above = RelativeLayout.BELOW;
            below = RelativeLayout.ABOVE;
            align_parent_top = RelativeLayout.ALIGN_PARENT_BOTTOM;
            align_parent_bottom = RelativeLayout.ALIGN_PARENT_TOP;
            iconpanel_align_parent_top = align_parent_top;
            iconpanel_align_parent_bottom = align_parent_bottom;
        }
        else if( ui_placement == UIPlacement.UIPLACEMENT_TOP ) {
            iconpanel_left_of = RelativeLayout.BELOW;
            iconpanel_right_of = RelativeLayout.ABOVE;
            iconpanel_above = RelativeLayout.LEFT_OF;
            iconpanel_below = RelativeLayout.RIGHT_OF;
            //noinspection SuspiciousNameCombination
            iconpanel_align_parent_left = RelativeLayout.ALIGN_PARENT_BOTTOM;
            //noinspection SuspiciousNameCombination
            iconpanel_align_parent_right = RelativeLayout.ALIGN_PARENT_TOP;
            //noinspection SuspiciousNameCombination
            iconpanel_align_parent_top = RelativeLayout.ALIGN_PARENT_LEFT;
            //noinspection SuspiciousNameCombination
            iconpanel_align_parent_bottom = RelativeLayout.ALIGN_PARENT_RIGHT;
        }

        Point display_size = new Point();
        Display display = main_activity.getWindowManager().getDefaultDisplay();
        display.getSize(display_size);
        final int display_height = Math.min(display_size.x, display_size.y);

        /*int navigation_gap = 0;
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ) {
            final int display_width = Math.max(display_size.x, display_size.y);
            Point real_display_size = new Point();
            display.getRealSize(real_display_size);
            final int real_display_width = Math.max(real_display_size.x, real_display_size.y);
            navigation_gap = real_display_width - display_width;
            if( MyDebug.LOG ) {
                Log.d(TAG, "display_width: " + display_width);
                Log.d(TAG, "real_display_width: " + real_display_width);
                Log.d(TAG, "navigation_gap: " + navigation_gap);
            }
        }*/
        int navigation_gap = main_activity.getNavigationGap();
        test_navigation_gap = navigation_gap;
        if( MyDebug.LOG ) {
            Log.d(TAG, "navigation_gap: " + navigation_gap);
        }

        if( !popup_container_only )
        {
            // reset:
            top_icon = null;

            // we use a dummy button, so that the GUI buttons keep their positioning even if the Settings button is hidden (visibility set to View.GONE)
            View view = main_activity.findViewById(R.id.gui_anchor);
            RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(iconpanel_align_parent_left, 0);
            layoutParams.addRule(iconpanel_align_parent_right, RelativeLayout.TRUE);
            layoutParams.addRule(iconpanel_align_parent_top, RelativeLayout.TRUE);
            layoutParams.addRule(iconpanel_align_parent_bottom, 0);
            layoutParams.addRule(iconpanel_above, 0);
            layoutParams.addRule(iconpanel_below, 0);
            layoutParams.addRule(iconpanel_left_of, 0);
            layoutParams.addRule(iconpanel_right_of, 0);
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);
            View previous_view = view;

            List<View> buttons_permanent = new ArrayList<>();
            if( ui_placement == UIPlacement.UIPLACEMENT_TOP ) {
                // not part of the icon panel in TOP mode
                view = main_activity.findViewById(R.id.gallery);
                layoutParams = (RelativeLayout.LayoutParams) view.getLayoutParams();
                layoutParams.addRule(align_parent_left, 0);
                layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
                layoutParams.addRule(align_parent_top, RelativeLayout.TRUE);
                layoutParams.addRule(align_parent_bottom, 0);
                layoutParams.addRule(above, 0);
                layoutParams.addRule(below, 0);
                layoutParams.addRule(left_of, 0);
                layoutParams.addRule(right_of, 0);
                layoutParams.setMargins(0, 0, navigation_gap, 0);
                view.setLayoutParams(layoutParams);
                setViewRotation(view, ui_rotation);
            }
            else {
                buttons_permanent.add(main_activity.findViewById(R.id.gallery));
            }
            buttons_permanent.add(main_activity.findViewById(R.id.settings));
            buttons_permanent.add(main_activity.findViewById(R.id.popup));
            buttons_permanent.add(main_activity.findViewById(R.id.exposure));
            //buttons_permanent.add(main_activity.findViewById(R.id.switch_video));
            //buttons_permanent.add(main_activity.findViewById(R.id.switch_camera));
            buttons_permanent.add(main_activity.findViewById(R.id.exposure_lock));
            buttons_permanent.add(main_activity.findViewById(R.id.white_balance_lock));
            buttons_permanent.add(main_activity.findViewById(R.id.cycle_raw));
            buttons_permanent.add(main_activity.findViewById(R.id.store_location));
            buttons_permanent.add(main_activity.findViewById(R.id.text_stamp));
            buttons_permanent.add(main_activity.findViewById(R.id.stamp));
            buttons_permanent.add(main_activity.findViewById(R.id.auto_level));
            buttons_permanent.add(main_activity.findViewById(R.id.cycle_flash));
            buttons_permanent.add(main_activity.findViewById(R.id.face_detection));
            buttons_permanent.add(main_activity.findViewById(R.id.audio_control));
            buttons_permanent.add(main_activity.findViewById(R.id.kraken_icon));

            List<View> buttons_all = new ArrayList<>(buttons_permanent);
            // icons which only sometimes show on the icon panel:
            buttons_all.add(main_activity.findViewById(R.id.trash));
            buttons_all.add(main_activity.findViewById(R.id.share));

            for(View this_view : buttons_all) {
                layoutParams = (RelativeLayout.LayoutParams)this_view.getLayoutParams();
                layoutParams.addRule(iconpanel_align_parent_left, 0);
                layoutParams.addRule(iconpanel_align_parent_right, 0);
                layoutParams.addRule(iconpanel_align_parent_top, RelativeLayout.TRUE);
                layoutParams.addRule(iconpanel_align_parent_bottom, 0);
                layoutParams.addRule(iconpanel_above, 0);
                layoutParams.addRule(iconpanel_below, 0);
                layoutParams.addRule(iconpanel_left_of, previous_view.getId());
                layoutParams.addRule(iconpanel_right_of, 0);
                this_view.setLayoutParams(layoutParams);
                setViewRotation(this_view, ui_rotation);
                previous_view = this_view;
            }

            int button_size = main_activity.getResources().getDimensionPixelSize(R.dimen.onscreen_button_size);
            if( ui_placement == UIPlacement.UIPLACEMENT_TOP ) {
                // need to dynamically lay out the permanent icons

                int count = 0;
                View first_visible_view = null;
                View last_visible_view = null;
                for(View this_view : buttons_permanent) {
                    if( this_view.getVisibility() == View.VISIBLE ) {
                        if( first_visible_view == null )
                            first_visible_view = this_view;
                        last_visible_view = this_view;
                        count++;
                    }
                }
                //count = 10; // test
                if( MyDebug.LOG ) {
                    Log.d(TAG, "count: " + count);
                    Log.d(TAG, "display_height: " + display_height);
                }
                if( count > 0 ) {
					/*int button_size = display_height / count;
					if( MyDebug.LOG )
						Log.d(TAG, "button_size: " + button_size);
					for(View this_view : buttons) {
						if( this_view.getVisibility() == View.VISIBLE ) {
							layoutParams = (RelativeLayout.LayoutParams)this_view.getLayoutParams();
							layoutParams.width = button_size;
							layoutParams.height = button_size;
							this_view.setLayoutParams(layoutParams);
						}
					}*/
                    int total_button_size = count*button_size;
                    int margin = 0;
                    if( total_button_size > display_height ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "need to reduce button size");
                        button_size = display_height / count;
                    }
                    else {
                        if( MyDebug.LOG )
                            Log.d(TAG, "need to increase margin");
                        if( count > 1 )
                            margin = (display_height - total_button_size) / (count-1);
                    }
                    if( MyDebug.LOG ) {
                        Log.d(TAG, "button_size: " + button_size);
                        Log.d(TAG, "total_button_size: " + total_button_size);
                        Log.d(TAG, "margin: " + margin);
                    }
                    for(View this_view : buttons_permanent) {
                        if( this_view.getVisibility() == View.VISIBLE ) {
                            if( MyDebug.LOG ) {
                                Log.d(TAG, "set view layout for: " + this_view.getContentDescription());
                                if( this_view==first_visible_view ) {
                                    Log.d(TAG,"    first visible view");
                                }
                            }
                            //this_view.setPadding(0, margin/2, 0, margin/2);
                            layoutParams = (RelativeLayout.LayoutParams)this_view.getLayoutParams();
                            // be careful if we change how the margins are laid out: it looks nicer when only the settings icon
                            // is displayed (when taking a photo) if it is still shown left-most, rather than centred; also
                            // needed for "pause preview" trash/icons to be shown properly (test by rotating the phone to update
                            // the layout)
                            layoutParams.setMargins(0, this_view==first_visible_view ? 0 : margin/2, 0, this_view==last_visible_view ? 0 : margin/2);
                            layoutParams.width = button_size;
                            layoutParams.height = button_size;
                            this_view.setLayoutParams(layoutParams);
                        }
                    }
                    top_icon = first_visible_view;
                }
            }
            else {
                // need to reset size/margins to their default
                for(View this_view : buttons_permanent) {
                    layoutParams = (RelativeLayout.LayoutParams)this_view.getLayoutParams();
                    layoutParams.setMargins(0, 0, 0, 0);
                    layoutParams.width = button_size;
                    layoutParams.height = button_size;
                    this_view.setLayoutParams(layoutParams);
                }
            }

            // end icon panel

            view = main_activity.findViewById(R.id.take_photo);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_parent_left, 0);
            layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
            layoutParams.setMargins(0, 0, navigation_gap, 0);
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);

            view = main_activity.findViewById(R.id.switch_camera);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_parent_left, 0);
            layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
            layoutParams.setMargins(0, 0, navigation_gap, 0);
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);

            view = main_activity.findViewById(R.id.switch_multi_camera);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);

            view = main_activity.findViewById(R.id.pause_video);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_parent_left, 0);
            layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
            layoutParams.setMargins(0, 0, navigation_gap, 0);
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);

            view = main_activity.findViewById(R.id.cancel_panorama);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_parent_left, 0);
            layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
            layoutParams.setMargins(0, 0, navigation_gap, 0);
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);

            view = main_activity.findViewById(R.id.switch_video);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_parent_left, 0);
            layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
            layoutParams.setMargins(0, 0, navigation_gap, 0);
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);

            view = main_activity.findViewById(R.id.take_photo_when_video_recording);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_parent_left, 0);
            layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
            layoutParams.setMargins(0, 0, navigation_gap, 0);
            view.setLayoutParams(layoutParams);
            setViewRotation(view, ui_rotation);

            view = main_activity.findViewById(R.id.zoom);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_parent_left, 0);
            layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
            layoutParams.addRule(align_parent_top, 0);
            layoutParams.addRule(align_parent_bottom, RelativeLayout.TRUE);
            layoutParams.setMargins(0, 0, navigation_gap, 0);
            view.setLayoutParams(layoutParams);
            view.setRotation(180.0f); // should always match the zoom_seekbar, so that zoom in and out are in the same directions

            view = main_activity.findViewById(R.id.zoom_seekbar);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            // if we are showing the zoom control, the align next to that; otherwise have it aligned close to the edge of screen
            if( sharedPreferences.getBoolean(PreferenceKeys.ShowZoomControlsPreferenceKey, false) ) {
                layoutParams.addRule(align_left, 0);
                layoutParams.addRule(align_right, R.id.zoom);
                layoutParams.addRule(above, R.id.zoom);
                layoutParams.addRule(below, 0);
                // need to clear the others, in case we turn zoom controls on/off
                layoutParams.addRule(align_parent_left, 0);
                layoutParams.addRule(align_parent_right, 0);
                layoutParams.addRule(align_parent_top, 0);
                layoutParams.addRule(align_parent_bottom, 0);
                layoutParams.setMargins(0, 0, 0, 0);
            }
            else {
                layoutParams.addRule(align_parent_left, 0);
                layoutParams.addRule(align_parent_right, RelativeLayout.TRUE);
                layoutParams.addRule(align_parent_top, 0);
                layoutParams.addRule(align_parent_bottom, RelativeLayout.TRUE);
                layoutParams.setMargins(0, 0, navigation_gap, 0);
                // need to clear the others, in case we turn zoom controls on/off
                layoutParams.addRule(align_left, 0);
                layoutParams.addRule(align_right, 0);
                layoutParams.addRule(above, 0);
                layoutParams.addRule(below, 0);
            }
            view.setLayoutParams(layoutParams);

            view = main_activity.findViewById(R.id.focus_seekbar);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_left, R.id.preview);
            layoutParams.addRule(align_right, 0);
            layoutParams.addRule(left_of, R.id.zoom_seekbar);
            layoutParams.addRule(right_of, 0);
            layoutParams.addRule(align_parent_top, 0);
            layoutParams.addRule(align_parent_bottom, RelativeLayout.TRUE);
            view.setLayoutParams(layoutParams);

            view = main_activity.findViewById(R.id.focus_bracketing_target_seekbar);
            layoutParams = (RelativeLayout.LayoutParams)view.getLayoutParams();
            layoutParams.addRule(align_left, R.id.preview);
            layoutParams.addRule(align_right, 0);
            layoutParams.addRule(left_of, R.id.zoom_seekbar);
            layoutParams.addRule(right_of, 0);
            layoutParams.addRule(above, R.id.focus_seekbar);
            layoutParams.addRule(below, 0);
            view.setLayoutParams(layoutParams);
        }

        if( !popup_container_only )
        {
            // set seekbar info
            int width_dp;
            if( ui_rotation == 0 || ui_rotation == 180 ) {
                // landscape
                width_dp = 350;
            }
            else {
                // portrait
                width_dp = 250;
                // prevent being too large on smaller devices (e.g., Galaxy Nexus or smaller)
                int max_width_dp = getMaxHeightDp(true);
                if( width_dp > max_width_dp )
                    width_dp = max_width_dp;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "width_dp: " + width_dp);
            int height_dp = 50;
            final float scale = main_activity.getResources().getDisplayMetrics().density;
            int width_pixels = (int) (width_dp * scale + 0.5f); // convert dps to pixels
            int height_pixels = (int) (height_dp * scale + 0.5f); // convert dps to pixels

            View view = main_activity.findViewById(R.id.sliders_container);
            setViewRotation(view, ui_rotation);
            view.setTranslationX(0.0f);
            view.setTranslationY(0.0f);

            if( ui_rotation == 90 || ui_rotation == 270 ) {
                // portrait
                view.setTranslationX(2*height_pixels);
            }
            else if( ui_rotation == 0 ) {
                // landscape
                view.setTranslationY(height_pixels);
            }
            else {
                // upside-down landscape
                view.setTranslationY(-1*height_pixels);
            }

            /*
            // align sliders_container
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)view.getLayoutParams();
            if( ui_rotation == 90 || ui_rotation == 270 ) {
                // portrait
                view.setTranslationX(2*height_pixels);
                lp.addRule(left_of, 0);
                lp.addRule(right_of, 0);
                lp.addRule(above, 0);
                lp.addRule(below, 0);
                lp.addRule(align_parent_top, 0);
                lp.addRule(align_parent_bottom, 0);
            }
            else if( ui_rotation == (ui_placement == UIPlacement.UIPLACEMENT_LEFT ? 180 : 0) ) {
                // landscape (or upside-down landscape if ui-left)
                view.setTranslationY(0);
                lp.addRule(left_of, R.id.zoom_seekbar);
                lp.addRule(right_of, 0);

                if( main_activity.showManualFocusSeekbar(true) ) {
                    lp.addRule(above, R.id.focus_bracketing_target_seekbar);
                    lp.addRule(below, 0);
                    lp.addRule(align_parent_top, 0);
                    lp.addRule(align_parent_bottom, 0);
                }
                else if( main_activity.showManualFocusSeekbar(false) ) {
                    lp.addRule(above, R.id.focus_seekbar);
                    lp.addRule(below, 0);
                    lp.addRule(align_parent_top, 0);
                    lp.addRule(align_parent_bottom, 0);
                }
                else {
                    lp.addRule(above, 0);
                    lp.addRule(below, 0);
                    lp.addRule(align_parent_top, 0);
                    lp.addRule(align_parent_bottom, RelativeLayout.TRUE);
                }
            }
            else {
                // upside-down landscape (or landscape if ui-left)
                if( ui_rotation == 0 )
                    view.setTranslationY(height_pixels);
                else
                    view.setTranslationY(-1*height_pixels);
                lp.addRule(left_of, 0);
                lp.addRule(right_of, 0);
                lp.addRule(above, 0);
                lp.addRule(below, 0);
                lp.addRule(align_parent_bottom, 0);
            }
            view.setLayoutParams(lp);*/

            view = main_activity.findViewById(R.id.exposure_seekbar);
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)view.getLayoutParams();
            lp.width = width_pixels;
            lp.height = height_pixels;
            view.setLayoutParams(lp);

            view = main_activity.findViewById(R.id.exposure_seekbar_zoom);
            view.setAlpha(0.5f);

            view = main_activity.findViewById(R.id.iso_seekbar);
            lp = (RelativeLayout.LayoutParams)view.getLayoutParams();
            lp.width = width_pixels;
            lp.height = height_pixels;
            view.setLayoutParams(lp);

            view = main_activity.findViewById(R.id.exposure_time_seekbar);
            lp = (RelativeLayout.LayoutParams)view.getLayoutParams();
            lp.width = width_pixels;
            lp.height = height_pixels;
            view.setLayoutParams(lp);

            view = main_activity.findViewById(R.id.white_balance_seekbar);
            lp = (RelativeLayout.LayoutParams)view.getLayoutParams();
            lp.width = width_pixels;
            lp.height = height_pixels;
            view.setLayoutParams(lp);
        }

        if( !popup_container_only ) {
            setTakePhotoIcon();
            // no need to call setSwitchCameraContentDescription()
        }

        if( MyDebug.LOG ) {
            Log.d(TAG, "layoutUI: total time: " + (System.currentTimeMillis() - debug_time));
        }
    }



    /** Set icons for taking photos vs videos.
     *  Also handles content descriptions for the take photo button and switch video button.
     */
    public void setTakePhotoIcon() {
        if( MyDebug.LOG )
            Log.d(TAG, "setTakePhotoIcon()");
        if( main_activity.getPreview() != null ) {
            ImageButton view = main_activity.findViewById(R.id.take_photo);
            int resource;
            int content_description;
            int switch_video_content_description;
            if( main_activity.getPreview().isVideo() ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "set icon to video");
                resource = main_activity.getPreview().isVideoRecording() ? R.drawable.take_video_recording : R.drawable.take_video_selector;
                content_description = main_activity.getPreview().isVideoRecording() ? R.string.stop_video : R.string.start_video;
                switch_video_content_description = R.string.switch_to_photo;
            }

             {
                if( MyDebug.LOG )
                    Log.d(TAG, "set icon to photo");
                resource = R.drawable.take_photo_selector;
                content_description = R.string.take_photo;
                switch_video_content_description = R.string.switch_to_video;
            }
            view.setImageResource(resource);
            view.setContentDescription( main_activity.getResources().getString(content_description) );
            view.setTag(resource); // for testing

            view = main_activity.findViewById(R.id.switch_video);
            view.setContentDescription( main_activity.getResources().getString(switch_video_content_description) );
            resource = main_activity.getPreview().isVideo() ? R.drawable.take_photo : R.drawable.take_video;
            view.setImageResource(resource);
            view.setTag(resource); // for testing
        }
    }

    /** Set content description for switch camera button.
     */
    public void setSwitchCameraContentDescription() {
        if( MyDebug.LOG )
            Log.d(TAG, "setSwitchCameraContentDescription()");
        if( main_activity.getPreview() != null && main_activity.getPreview().canSwitchCamera() ) {
            ImageButton view = main_activity.findViewById(R.id.switch_camera);
            int content_description;
            int cameraId = main_activity.getNextCameraId();
            switch( main_activity.getPreview().getCameraControllerManager().getFacing( cameraId  ) ) {
                case FACING_FRONT:
                    content_description = R.string.switch_to_front_camera;
                    break;
                case FACING_BACK:
                    content_description = R.string.switch_to_back_camera;
                    break;
                case FACING_EXTERNAL:
                    content_description = R.string.switch_to_external_camera;
                    break;
                default:
                    content_description = R.string.switch_to_unknown_camera;
                    break;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "content_description: " + main_activity.getResources().getString(content_description));
            view.setContentDescription( main_activity.getResources().getString(content_description) );
        }
    }

    /** Set content description for pause video button.
     */
    public void setPauseVideoContentDescription() {
        if (MyDebug.LOG)
            Log.d(TAG, "setPauseVideoContentDescription()");
        ImageButton pauseVideoButton = main_activity.findViewById(R.id.pause_video);
        int content_description;
        if( main_activity.getPreview().isVideoRecordingPaused() ) {
            content_description = R.string.resume_video;
            pauseVideoButton.setImageResource(R.drawable.ic_play_circle_outline_white_48dp);
        }
        else {
            content_description = R.string.pause_video;
            pauseVideoButton.setImageResource(R.drawable.ic_pause_circle_outline_white_48dp);
        }
        if( MyDebug.LOG )
            Log.d(TAG, "content_description: " + main_activity.getResources().getString(content_description));
        pauseVideoButton.setContentDescription(main_activity.getResources().getString(content_description));
    }

    public UIPlacement getUIPlacement() {
        return this.ui_placement;
    }

    public void onOrientationChanged(int orientation) {
        if( orientation == OrientationEventListener.ORIENTATION_UNKNOWN )
            return;
        int diff = Math.abs(orientation - current_orientation);
        if( diff > 180 )
            diff = 360 - diff;
        // only change orientation when sufficiently changed
        if( diff > 60 ) {
            orientation = (orientation + 45) / 90 * 90;
            orientation = orientation % 360;
            if( orientation != current_orientation ) {
                this.current_orientation = orientation;
                if( MyDebug.LOG ) {
                    Log.d(TAG, "current_orientation is now: " + current_orientation);
                }
                view_rotate_animation = true;
                layoutUI();
                view_rotate_animation = false;

            }
        }
    }

    public boolean showExposureLockIcon() {
        if( !main_activity.getPreview().supportsExposureLock() )
            return false;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowExposureLockPreferenceKey, true);
    }

    public boolean showWhiteBalanceLockIcon() {
        if( !main_activity.getPreview().supportsWhiteBalanceLock() )
            return false;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowWhiteBalanceLockPreferenceKey, false);
    }

    public boolean showCycleRawIcon() {
        if( !main_activity.getApplicationInterface().isRawAllowed(main_activity.getApplicationInterface().getPhotoMode()) )
            return false;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowCycleRawPreferenceKey, false);
    }

    public boolean showStoreLocationIcon() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowStoreLocationPreferenceKey, false);
    }

    public boolean showTextStampIcon() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowTextStampPreferenceKey, false);
    }

    public boolean showStampIcon() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowStampPreferenceKey, false);
    }

    public boolean showAutoLevelIcon() {
        if( !main_activity.supportsAutoStabilise() )
            return false;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowAutoLevelPreferenceKey, false);
    }

    public boolean showCycleFlashIcon() {
        if( !main_activity.getPreview().supportsFlash() )
            return false;
        if( main_activity.getPreview().isVideo() )
            return false; // no point showing flash icon in video mode, as we only allow flash auto and flash torch, and we don't support torch on the on-screen cycle flash icon
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        return sharedPreferences.getBoolean(PreferenceKeys.ShowCycleFlashPreferenceKey, false);
    }

    public void setImmersiveMode(final boolean immersive_mode) {
        if( MyDebug.LOG )
            Log.d(TAG, "setImmersiveMode: " + immersive_mode);
        this.immersive_mode = immersive_mode;
        main_activity.runOnUiThread(new Runnable() {
            public void run() {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
                // if going into immersive mode, the we should set GONE the ones that are set GONE in showGUI(false)
                //final int visibility_gone = immersive_mode ? View.GONE : View.VISIBLE;
                final int visibility = immersive_mode ? View.GONE : View.VISIBLE;
                if( MyDebug.LOG )
                    Log.d(TAG, "setImmersiveMode: set visibility: " + visibility);
                // n.b., don't hide share and trash buttons, as they require immediate user input for us to continue
                View switchCameraButton = main_activity.findViewById(R.id.switch_camera);
                View switchMultiCameraButton = main_activity.findViewById(R.id.switch_multi_camera);
                View switchVideoButton = main_activity.findViewById(R.id.switch_video);
                View exposureButton = main_activity.findViewById(R.id.exposure);
                View exposureLockButton = main_activity.findViewById(R.id.exposure_lock);
                View whiteBalanceLockButton = main_activity.findViewById(R.id.white_balance_lock);
                View cycleRawButton = main_activity.findViewById(R.id.cycle_raw);
                View storeLocationButton = main_activity.findViewById(R.id.store_location);
                View textStampButton = main_activity.findViewById(R.id.text_stamp);
                View stampButton = main_activity.findViewById(R.id.stamp);
                View autoLevelButton = main_activity.findViewById(R.id.auto_level);
                View cycleFlashButton = main_activity.findViewById(R.id.cycle_flash);
                View faceDetectionButton = main_activity.findViewById(R.id.face_detection);
                View audioControlButton = main_activity.findViewById(R.id.audio_control);
                View popupButton = main_activity.findViewById(R.id.popup);
                View galleryButton = main_activity.findViewById(R.id.gallery);
                View settingsButton = main_activity.findViewById(R.id.settings);
                View zoomControls = main_activity.findViewById(R.id.zoom);
                View zoomSeekBar = main_activity.findViewById(R.id.zoom_seekbar);
                View focusSeekBar = main_activity.findViewById(R.id.focus_seekbar);
                View focusBracketingTargetSeekBar = main_activity.findViewById(R.id.focus_bracketing_target_seekbar);
                if( main_activity.getPreview().getCameraControllerManager().getNumberOfCameras() > 1 )
                    switchCameraButton.setVisibility(visibility);
                if( main_activity.showSwitchMultiCamIcon() )
                    switchMultiCameraButton.setVisibility(visibility);
                switchVideoButton.setVisibility(visibility);
                if( main_activity.supportsExposureButton() )
                    exposureButton.setVisibility(visibility);
                if( showExposureLockIcon() )
                    exposureLockButton.setVisibility(visibility);
                if( showWhiteBalanceLockIcon() )
                    whiteBalanceLockButton.setVisibility(visibility);
                if( showCycleRawIcon() )
                    cycleRawButton.setVisibility(visibility);
                if( showStoreLocationIcon() )
                    storeLocationButton.setVisibility(visibility);
                if( showTextStampIcon() )
                    textStampButton.setVisibility(visibility);
                if( showStampIcon() )
                    stampButton.setVisibility(visibility);
                if( showAutoLevelIcon() )
                    autoLevelButton.setVisibility(visibility);
                if( showCycleFlashIcon() )
                    cycleFlashButton.setVisibility(visibility);
                if( main_activity.hasAudioControl() )
                    audioControlButton.setVisibility(visibility);
                popupButton.setVisibility(visibility);
                galleryButton.setVisibility(visibility);
                settingsButton.setVisibility(visibility);
                if( MyDebug.LOG ) {
                    Log.d(TAG, "has_zoom: " + main_activity.getPreview().supportsZoom());
                }
                if( main_activity.getPreview().supportsZoom() && sharedPreferences.getBoolean(PreferenceKeys.ShowZoomControlsPreferenceKey, false) ) {
                    zoomControls.setVisibility(visibility);
                }
                if( main_activity.getPreview().supportsZoom() && sharedPreferences.getBoolean(PreferenceKeys.ShowZoomSliderControlsPreferenceKey, true) ) {
                    zoomSeekBar.setVisibility(visibility);
                }
                String pref_immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
                if( pref_immersive_mode.equals("immersive_mode_everything") ) {
                    if( sharedPreferences.getBoolean(PreferenceKeys.ShowTakePhotoPreferenceKey, true) ) {
                        View takePhotoButton = main_activity.findViewById(R.id.take_photo);
                        takePhotoButton.setVisibility(visibility);
                    }
                    if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && main_activity.getPreview().isVideoRecording() ) {
                        View pauseVideoButton = main_activity.findViewById(R.id.pause_video);
                        pauseVideoButton.setVisibility(visibility);
                    }
                    if( main_activity.getPreview().supportsPhotoVideoRecording() && main_activity.getApplicationInterface().usePhotoVideoRecording() && main_activity.getPreview().isVideoRecording() ) {
                        View takePhotoVideoButton = main_activity.findViewById(R.id.take_photo_when_video_recording);
                        takePhotoVideoButton.setVisibility(visibility);
                    }
                }
                if( !immersive_mode ) {
                    // make sure the GUI is set up as expected
                    showGUI();
                }
            }
        });
    }

    public boolean inImmersiveMode() {
        return immersive_mode;
    }

    public void showGUI(final boolean show, final boolean is_video) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "showGUI: " + show);
            Log.d(TAG, "is_video: " + is_video);
        }
        if( is_video )
            this.show_gui_video = show;
        else
            this.show_gui_photo = show;
        showGUI();
    }

    public void showGUI() {
        if( MyDebug.LOG ) {
            Log.d(TAG, "showGUI");
            Log.d(TAG, "show_gui_photo: " + show_gui_photo);
            Log.d(TAG, "show_gui_video: " + show_gui_video);
        }
        if( inImmersiveMode() )
            return;
        if( (show_gui_photo || show_gui_video) && main_activity.usingKitKatImmersiveMode() ) {
            // call to reset the timer
            main_activity.initImmersiveMode();
        }
        main_activity.runOnUiThread(new Runnable() {
            public void run() {
                final boolean is_panorama_recording = false;
                final int visibility = is_panorama_recording ? View.GONE : (show_gui_photo && show_gui_video) ? View.VISIBLE : View.GONE; // for UI that is hidden while taking photo or video
                final int visibility_video = is_panorama_recording ? View.GONE : show_gui_photo ? View.VISIBLE : View.GONE; // for UI that is only hidden while taking photo
                View switchCameraButton = main_activity.findViewById(R.id.switch_camera);
                View switchMultiCameraButton = main_activity.findViewById(R.id.switch_multi_camera);
                View switchVideoButton = main_activity.findViewById(R.id.switch_video);
                View exposureButton = main_activity.findViewById(R.id.exposure);
                View exposureLockButton = main_activity.findViewById(R.id.exposure_lock);
                View whiteBalanceLockButton = main_activity.findViewById(R.id.white_balance_lock);
                View cycleRawButton = main_activity.findViewById(R.id.cycle_raw);
                View storeLocationButton = main_activity.findViewById(R.id.store_location);
                View textStampButton = main_activity.findViewById(R.id.text_stamp);
                View stampButton = main_activity.findViewById(R.id.stamp);
                View autoLevelButton = main_activity.findViewById(R.id.auto_level);
                View cycleFlashButton = main_activity.findViewById(R.id.cycle_flash);
                View faceDetectionButton = main_activity.findViewById(R.id.face_detection);
                View audioControlButton = main_activity.findViewById(R.id.audio_control);
                View popupButton = main_activity.findViewById(R.id.popup);
                if( main_activity.getPreview().getCameraControllerManager().getNumberOfCameras() > 1 )
                    switchCameraButton.setVisibility(visibility);
                if( main_activity.showSwitchMultiCamIcon() )
                    switchMultiCameraButton.setVisibility(visibility);
                switchVideoButton.setVisibility(visibility);
                if( main_activity.supportsExposureButton() )
                    exposureButton.setVisibility(visibility_video); // still allow exposure when recording video
                if( showExposureLockIcon() )
                    exposureLockButton.setVisibility(visibility_video); // still allow exposure lock when recording video
                if( showWhiteBalanceLockIcon() )
                    whiteBalanceLockButton.setVisibility(visibility_video); // still allow white balance lock when recording video
                if( showCycleRawIcon() )
                    cycleRawButton.setVisibility(visibility);
                if( showStoreLocationIcon() )
                    storeLocationButton.setVisibility(visibility);
                if( showTextStampIcon() )
                    textStampButton.setVisibility(visibility);
                if( showStampIcon() )
                    stampButton.setVisibility(visibility);
                if( showAutoLevelIcon() )
                    autoLevelButton.setVisibility(visibility);
                if( showCycleFlashIcon() )
                    cycleFlashButton.setVisibility(visibility);
                if( main_activity.hasAudioControl() )
                    audioControlButton.setVisibility(visibility);

                View remoteConnectedIcon = main_activity.findViewById(R.id.kraken_icon);
                    remoteConnectedIcon.setVisibility(View.GONE);

                popupButton.setVisibility(main_activity.getPreview().supportsFlash() ? visibility_video : visibility); // still allow popup in order to change flash mode when recording video

                if( show_gui_photo && show_gui_video ) {
                    layoutUI(); // needed for "top" UIPlacement, to auto-arrange the buttons
                }
            }
        });
    }

    public void updateExposureLockIcon() {
        ImageButton view = main_activity.findViewById(R.id.exposure_lock);
        boolean enabled = main_activity.getPreview().isExposureLocked();
        view.setImageResource(enabled ? R.drawable.exposure_locked : R.drawable.exposure_unlocked);
        view.setContentDescription( main_activity.getResources().getString(enabled ? R.string.exposure_unlock : R.string.exposure_lock) );
    }

    public void updateWhiteBalanceLockIcon() {
        ImageButton view = main_activity.findViewById(R.id.white_balance_lock);
        boolean enabled = main_activity.getPreview().isWhiteBalanceLocked();
        view.setImageResource(enabled ? R.drawable.white_balance_locked : R.drawable.white_balance_unlocked);
        view.setContentDescription( main_activity.getResources().getString(enabled ? R.string.white_balance_unlock : R.string.white_balance_lock) );
    }

    public void updateCycleRawIcon() {
        ApplicationInterface.RawPref raw_pref = main_activity.getApplicationInterface().getRawPref();
        ImageButton view = main_activity.findViewById(R.id.cycle_raw);
            view.setImageResource(R.drawable.raw_off_icon);
    }

    public void updateStoreLocationIcon() {
        ImageButton view = main_activity.findViewById(R.id.store_location);
        boolean enabled = main_activity.getApplicationInterface().getGeotaggingPref();
        view.setImageResource(enabled ? R.drawable.ic_gps_fixed_red_48dp : R.drawable.ic_gps_fixed_white_48dp);
        view.setContentDescription( main_activity.getResources().getString(enabled ? R.string.preference_location_disable : R.string.preference_location_enable) );
    }

    public void updateTextStampIcon() {
        ImageButton view = main_activity.findViewById(R.id.text_stamp);
        boolean enabled = !main_activity.getApplicationInterface().getTextStampPref().isEmpty();
        view.setImageResource(enabled ? R.drawable.baseline_text_fields_red_48 : R.drawable.baseline_text_fields_white_48);
    }

    public void updateStampIcon() {
        ImageButton view = main_activity.findViewById(R.id.stamp);
        boolean enabled = main_activity.getApplicationInterface().getStampPref().equals("preference_stamp_yes");
        view.setImageResource(enabled ? R.drawable.ic_text_format_red_48dp : R.drawable.ic_text_format_white_48dp);
        view.setContentDescription( main_activity.getResources().getString(enabled ? R.string.stamp_disable : R.string.stamp_enable) );
    }

    public void updateAutoLevelIcon() {
        ImageButton view = main_activity.findViewById(R.id.auto_level);
        boolean enabled = main_activity.getApplicationInterface().getAutoStabilisePref();
        view.setImageResource(enabled ? R.drawable.auto_stabilise_icon_red : R.drawable.auto_stabilise_icon);
        view.setContentDescription( main_activity.getResources().getString(enabled ? R.string.auto_level_disable : R.string.auto_level_enable) );
    }

    public void updateCycleFlashIcon() {
        // n.b., read from preview rather than saved application preference - so the icon updates correctly when in flash
        // auto mode, but user switches to manual ISO where flash auto isn't supported
        String flash_value = main_activity.getPreview().getCurrentFlashValue();
        if( flash_value != null ) {
            ImageButton view = main_activity.findViewById(R.id.cycle_flash);
            switch( flash_value ) {
                case "flash_off":
                    view.setImageResource(R.drawable.flash_off);
                    break;
                case "flash_auto":
                case "flash_frontscreen_auto":
                    view.setImageResource(R.drawable.flash_auto);
                    break;
                case "flash_on":
                case "flash_frontscreen_on":
                    view.setImageResource(R.drawable.flash_on);
                    break;
                case "flash_torch":
                case "flash_frontscreen_torch":
                    view.setImageResource(R.drawable.baseline_highlight_white_48);
                    break;
                case "flash_red_eye":
                    view.setImageResource(R.drawable.baseline_remove_red_eye_white_48);
                    break;
                default:
                    // just in case??
                    Log.e(TAG, "unknown flash value " + flash_value);
                    view.setImageResource(R.drawable.flash_off);
                    break;
            }
        }
        else {
            ImageButton view = main_activity.findViewById(R.id.cycle_flash);
            view.setImageResource(R.drawable.flash_off);
        }
    }

    public void updateFaceDetectionIcon() {
        ImageButton view = main_activity.findViewById(R.id.face_detection);
        boolean enabled = main_activity.getApplicationInterface().getFaceDetectionPref();
        view.setImageResource(enabled ? R.drawable.ic_face_red_48dp : R.drawable.ic_face_white_48dp);
        view.setContentDescription( main_activity.getResources().getString(enabled ? R.string.face_detection_disable : R.string.face_detection_enable) );
    }

    public void updateOnScreenIcons() {
        if( MyDebug.LOG )
            Log.d(TAG, "updateOnScreenIcons");
        this.updateExposureLockIcon();
        this.updateWhiteBalanceLockIcon();
        this.updateCycleRawIcon();
        this.updateStoreLocationIcon();
        this.updateTextStampIcon();
        this.updateStampIcon();
        this.updateAutoLevelIcon();
        this.updateCycleFlashIcon();
        this.updateFaceDetectionIcon();
    }

    public void audioControlStarted() {
        ImageButton view = main_activity.findViewById(R.id.audio_control);
        view.setImageResource(R.drawable.ic_mic_red_48dp);
        view.setContentDescription( main_activity.getResources().getString(R.string.audio_control_stop) );
    }

    public void audioControlStopped() {
        ImageButton view = main_activity.findViewById(R.id.audio_control);
        view.setImageResource(R.drawable.ic_mic_white_48dp);
        view.setContentDescription( main_activity.getResources().getString(R.string.audio_control_start) );
    }

    public boolean isExposureUIOpen() {
        View exposure_seek_bar = main_activity.findViewById(R.id.exposure_container);
        int exposure_visibility = exposure_seek_bar.getVisibility();
        View manual_exposure_seek_bar = main_activity.findViewById(R.id.manual_exposure_container);
        int manual_exposure_visibility = manual_exposure_seek_bar.getVisibility();
        return exposure_visibility == View.VISIBLE || manual_exposure_visibility == View.VISIBLE;
    }

    /**
     * Opens or close the exposure settings (ISO, white balance, etc)
     */
    public void toggleExposureUI() {
        if( MyDebug.LOG )
            Log.d(TAG, "toggleExposureUI");

        mSelectingExposureUIElement = false;
        if( isExposureUIOpen() ) {
            closeExposureUI();
        }
    }


    private void resetExposureUIHighlights() {
        if( MyDebug.LOG )
            Log.d(TAG, "resetExposureUIHighlights");
        ViewGroup iso_buttons_container = main_activity.findViewById(R.id.iso_buttons); // Shown when Camera API2 enabled
        View exposure_seek_bar = main_activity.findViewById(R.id.exposure_container);
        View shutter_seekbar = main_activity.findViewById(R.id.exposure_time_seekbar);
        View iso_seekbar = main_activity.findViewById(R.id.iso_seekbar);
        View wb_seekbar = main_activity.findViewById(R.id.white_balance_seekbar);
        // Set all lines to black
        iso_buttons_container.setBackgroundColor(Color.TRANSPARENT);
        exposure_seek_bar.setBackgroundColor(Color.TRANSPARENT);
        shutter_seekbar.setBackgroundColor(Color.TRANSPARENT);
        iso_seekbar.setBackgroundColor(Color.TRANSPARENT);
        wb_seekbar.setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * Highlights the relevant line on the Exposure UI based on
     * the value of mExposureLine
     *
     */
    private void highlightExposureUILine(boolean selectNext) {
        if( MyDebug.LOG )
            Log.d(TAG, "highlightExposureUILine: " + selectNext);
        if (!isExposureUIOpen()) { // Safety check
            return;
        }
        ViewGroup iso_buttons_container = main_activity.findViewById(R.id.iso_buttons); // Shown when Camera API2 enabled
        View exposure_seek_bar = main_activity.findViewById(R.id.exposure_container);
        View shutter_seekbar = main_activity.findViewById(R.id.exposure_time_seekbar);
        View iso_seekbar = main_activity.findViewById(R.id.iso_seekbar);
        View wb_seekbar = main_activity.findViewById(R.id.white_balance_seekbar);
        // Our order for lines is:
        // - ISO buttons
        // - ISO slider
        // - Shutter speed
        // - exposure seek bar
        if( MyDebug.LOG )
            Log.d(TAG, "mExposureLine: " + mExposureLine);
        mExposureLine = ( mExposureLine  + 5 ) % 5;
        if( MyDebug.LOG )
            Log.d(TAG, "mExposureLine modulo: " + mExposureLine);
        if (selectNext) {
            if (mExposureLine == 0 && !iso_buttons_container.isShown())
                mExposureLine++;
            if (mExposureLine == 1 && !iso_seekbar.isShown())
                mExposureLine++;
            if (mExposureLine == 2 && !shutter_seekbar.isShown())
                mExposureLine++;
            if ((mExposureLine == 3) && !exposure_seek_bar.isShown())
                mExposureLine++;
            if ((mExposureLine == 4) && !wb_seekbar.isShown())
                mExposureLine++;
        } else {
            // Select previous
            if (mExposureLine == 4 && !wb_seekbar.isShown())
                mExposureLine--;
            if (mExposureLine == 3 && !exposure_seek_bar.isShown())
                mExposureLine--;
            if (mExposureLine == 2 && !shutter_seekbar.isShown())
                mExposureLine--;
            if (mExposureLine == 1 && !iso_seekbar.isShown())
                mExposureLine--;
            if (mExposureLine == 0 && !iso_buttons_container.isShown())
                mExposureLine--;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "after skipping: mExposureLine: " + mExposureLine);
        mExposureLine = ( mExposureLine  + 5 ) % 5;
        if( MyDebug.LOG )
            Log.d(TAG, "after skipping: mExposureLine modulo: " + mExposureLine);
        resetExposureUIHighlights();

        if (mExposureLine == 0) {
            iso_buttons_container.setBackgroundColor(highlightColor);
            //iso_buttons_container.setAlpha(0.5f);
        } else if (mExposureLine == 1) {
            iso_seekbar.setBackgroundColor(highlightColor);
            //iso_seekbar.setAlpha(0.5f);
        } else if (mExposureLine == 2) {
            shutter_seekbar.setBackgroundColor(highlightColor);
            //shutter_seekbar.setAlpha(0.5f);
        } else if (mExposureLine == 3) { //
            exposure_seek_bar.setBackgroundColor(highlightColor);
            //exposure_seek_bar.setAlpha(0.5f);
        } else if (mExposureLine == 4) {
            wb_seekbar.setBackgroundColor(highlightColor);
            //wb_seekbar.setAlpha(0.5f);
        }
    }

    private void nextExposureUILine() {
        mExposureLine++;
        highlightExposureUILine(true);
    }

    private void previousExposureUILine() {
        mExposureLine--;
        highlightExposureUILine(false);
    }

    /**
     * Our order for lines is:
     *  -0: ISO buttons
     *  -1: ISO slider
     *  -2: Shutter speed
     *  -3: exposure seek bar
     */
    private void nextExposureUIItem() {
        if( MyDebug.LOG )
            Log.d(TAG, "nextExposureUIItem");
        switch (mExposureLine) {
            case 0:
                nextIsoItem(false);
                break;
            case 1:
                changeSeekbar(R.id.iso_seekbar, 10);
                break;
            case 2:
                changeSeekbar(R.id.exposure_time_seekbar, 5);
                break;
            case 3:
                changeSeekbar(R.id.exposure_seekbar, 1);
                break;
            case 4:
                changeSeekbar(R.id.white_balance_seekbar, 3);
                break;
        }
    }

    private void previousExposureUIItem() {
        if( MyDebug.LOG )
            Log.d(TAG, "previousExposureUIItem");
        switch (mExposureLine) {
            case 0:
                nextIsoItem(true);
                break;
            case 1:
                changeSeekbar(R.id.iso_seekbar, -10);
                break;
            case 2:
                changeSeekbar(R.id.exposure_time_seekbar, -5);
                break;
            case 3:
                changeSeekbar(R.id.exposure_seekbar, -1);
                break;
            case 4:
                changeSeekbar(R.id.white_balance_seekbar, -3);
                break;
        }
    }

    private void nextIsoItem(boolean previous) {
        if( MyDebug.LOG )
            Log.d(TAG, "nextIsoItem: " + previous);
        // Find current ISO
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        String current_iso = sharedPreferences.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
        int count = iso_buttons.size();
        int step = previous ? -1 : 1;
        boolean found = false;
        for(int i = 0; i < count; i++) {
            Button button= (Button) iso_buttons.get(i);
            String button_text = "" + button.getText();
            if( button_text.contains(current_iso) ) {
                found = true;
                // Select next one, unless it's "Manual", which we skip since
                // it's not practical in remote mode.
                Button nextButton = (Button) iso_buttons.get((i + count + step)%count);
                String nextButton_text = "" + nextButton.getText();
                if (nextButton_text.contains("m")) {
                    nextButton = (Button) iso_buttons.get((i+count+ 2*step)%count);
                }
                nextButton.callOnClick();
                break;
            }
        }
        if (!found) {
            // For instance, we are in ISO manual mode and "M" is selected. default
            // back to "Auto" to avoid being stuck since we're with a remote control
            iso_buttons.get(0).callOnClick();
        }
    }


    /** Returns the height of the device in dp (or width in portrait mode), allowing for space for the
     *  on-screen UI icons.
     * @param centred If true, then find the max height for a view that will be centred.
     */
    int getMaxHeightDp(boolean centred) {
        Display display = main_activity.getWindowManager().getDefaultDisplay();
        // ensure we have display for landscape orientation (even if we ever allow Open Camera
        DisplayMetrics outMetrics = new DisplayMetrics();
        display.getMetrics(outMetrics);

        // normally we should always have heightPixels < widthPixels, but good not to assume we're running in landscape orientation
        int smaller_dim = Math.min(outMetrics.widthPixels, outMetrics.heightPixels);
        // the smaller dimension should limit the width, due to when held in portrait
        final float scale = main_activity.getResources().getDisplayMetrics().density;
        int dpHeight = (int)(smaller_dim / scale);
        if( MyDebug.LOG ) {
            Log.d(TAG, "display size: " + outMetrics.widthPixels + " x " + outMetrics.heightPixels);
            Log.d(TAG, "dpHeight: " + dpHeight);
        }
        // allow space for the icons at top/right of screen
        int margin = centred ? 120 : 50;
        dpHeight -= margin;
        return dpHeight;
    }

    public boolean isSelectingExposureUIElement() {
        if( MyDebug.LOG )
            Log.d(TAG, "isSelectingExposureUIElement returns:" + mSelectingExposureUIElement);
        return mSelectingExposureUIElement;
    }


    /**
     * Process a press to the "Up" button on a remote. Called from MainActivity.
     * @return true if an action was taken
     */
    public boolean processRemoteUpButton() {
        if( MyDebug.LOG )
            Log.d(TAG, "processRemoteUpButton");
        boolean didProcess = false;
  if (isExposureUIOpen()) {
            didProcess = true;
            if (isSelectingExposureUIElement()) {
                nextExposureUIItem();
            } else {
                previousExposureUILine();
            }
        }
        return didProcess;
    }

    /**
     * Process a press to the "Down" button on a remote. Called from MainActivity.
     * @return true if an action was taken
     */
    public boolean processRemoteDownButton() {
        if( MyDebug.LOG )
            Log.d(TAG, "processRemoteDownButton");
        boolean didProcess = false;
if (isExposureUIOpen()) {
            if (isSelectingExposureUIElement()) {
                previousExposureUIItem();
            } else {
                nextExposureUILine();
            }
            didProcess = true;
        }
        return didProcess;
    }

    private List<View> iso_buttons;
    private int iso_button_manual_index = -1;
    private final static String manual_iso_value = "m";


    public void setSeekbarZoom(int new_zoom) {
        if( MyDebug.LOG )
            Log.d(TAG, "setSeekbarZoom: " + new_zoom);
        SeekBar zoomSeekBar = main_activity.findViewById(R.id.zoom_seekbar);
        if( MyDebug.LOG )
            Log.d(TAG, "progress was: " + zoomSeekBar.getProgress());
        zoomSeekBar.setProgress(main_activity.getPreview().getMaxZoom()-new_zoom);
        if( MyDebug.LOG )
            Log.d(TAG, "progress is now: " + zoomSeekBar.getProgress());
    }

    public void changeSeekbar(int seekBarId, int change) {
        if( MyDebug.LOG )
            Log.d(TAG, "changeSeekbar: " + change);
        SeekBar seekBar = main_activity.findViewById(seekBarId);
        int value = seekBar.getProgress();
        int new_value = value + change;
        if( new_value < 0 )
            new_value = 0;
        else if( new_value > seekBar.getMax() )
            new_value = seekBar.getMax();
        if( MyDebug.LOG ) {
            Log.d(TAG, "value: " + value);
            Log.d(TAG, "new_value: " + new_value);
            Log.d(TAG, "max: " + seekBar.getMax());
        }
        if( new_value != value ) {
            seekBar.setProgress(new_value);
        }
    }

    /** Closes the exposure UI.
     */
    public void closeExposureUI() {
        ImageButton image_button = main_activity.findViewById(R.id.exposure);
        image_button.setImageResource(R.drawable.ic_exposure_white_48dp);

        View view = main_activity.findViewById(R.id.sliders_container);
        view.setVisibility(View.GONE);
        view = main_activity.findViewById(R.id.iso_container);
        view.setVisibility(View.GONE);
        view = main_activity.findViewById(R.id.exposure_container);
        view.setVisibility(View.GONE);
        view = main_activity.findViewById(R.id.manual_exposure_container);
        view.setVisibility(View.GONE);
        view = main_activity.findViewById(R.id.manual_white_balance_container);
        view.setVisibility(View.GONE);
    }

    public void setPopupIcon() {
        if( MyDebug.LOG )
            Log.d(TAG, "setPopupIcon");
        ImageButton popup = main_activity.findViewById(R.id.popup);
        String flash_value = main_activity.getPreview().getCurrentFlashValue();
        if( MyDebug.LOG )
            Log.d(TAG, "flash_value: " + flash_value);
        if( main_activity.getMainUI().showCycleFlashIcon() ) {
            popup.setImageResource(R.drawable.popup);
        }
        else if( flash_value != null && flash_value.equals("flash_off") ) {
            popup.setImageResource(R.drawable.popup_flash_off);
        }
        else if( flash_value != null && ( flash_value.equals("flash_torch") || flash_value.equals("flash_frontscreen_torch") ) ) {
            popup.setImageResource(R.drawable.popup_flash_torch);
        }
        else if( flash_value != null && ( flash_value.equals("flash_auto") || flash_value.equals("flash_frontscreen_auto") ) ) {
            popup.setImageResource(R.drawable.popup_flash_auto);
        }
        else if( flash_value != null && ( flash_value.equals("flash_on") || flash_value.equals("flash_frontscreen_on") ) ) {
            popup.setImageResource(R.drawable.popup_flash_on);
        }
        else if( flash_value != null && flash_value.equals("flash_red_eye") ) {
            popup.setImageResource(R.drawable.popup_flash_red_eye);
        }
        else {
            popup.setImageResource(R.drawable.popup);
        }
    }


    public boolean selectingIcons() {
        return mSelectingIcons;
    }

    public boolean selectingLines() {
        return mSelectingLines;
    }


    /**
     * Higlights the next LinearLayout view
     */
    private void highlightPopupLine(boolean highlight, boolean goUp) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "highlightPopupLine");
            Log.d(TAG, "highlight: " + highlight);
            Log.d(TAG, "goUp: " + goUp);
        }
        final ViewGroup popup_container = main_activity.findViewById(R.id.popup_container);
        Rect scrollBounds = new Rect();
        popup_container.getDrawingRect(scrollBounds);
        final LinearLayout inside = (LinearLayout) popup_container.getChildAt(0);
        if (inside == null)
            return; // Safety check
        int count = inside.getChildCount();
        boolean foundLine = false;
        while (!foundLine) {
            // Ensure we stay within our bounds:
            mPopupLine = (mPopupLine + count ) % count;
            View v = inside.getChildAt(mPopupLine);
            if( MyDebug.LOG )
                Log.d(TAG, "line: " + mPopupLine + " view: " + v);
            // to test example with HorizontalScrollView, see popup menu on Nokia 8 with Camera2 API, the flash icons row uses a HorizontalScrollView
            if( v instanceof HorizontalScrollView && ((HorizontalScrollView) v).getChildCount() > 0 )
                v = ((HorizontalScrollView) v).getChildAt(0);
            if (v.isShown() && v instanceof LinearLayout ) {
                if (highlight) {
                    v.setBackgroundColor(highlightColor);
                    //v.setAlpha(0.3f);
                    if (v.getBottom() > scrollBounds.bottom || v.getTop() < scrollBounds.top)
                        popup_container.scrollTo(0, v.getTop());
                    mHighlightedLine = (LinearLayout) v;
                } else {
                    v.setBackgroundColor(Color.TRANSPARENT);
                    v.setAlpha(1f);
                }
                foundLine = true;
                if( MyDebug.LOG )
                    Log.d(TAG, "found at line: " + foundLine);
            } else {
                mPopupLine += goUp ? -1 : 1;
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG,"Current line: " + mPopupLine);
    }




    /**
     * Simulates a press on the currently selected icon
     */
    private void clickSelectedIcon() {
        if( MyDebug.LOG )
            Log.d(TAG, "clickSelectedIcon: " + mHighlightedIcon);
        if (mHighlightedIcon != null) {
            mHighlightedIcon.callOnClick();
        }
    }

    /**
     * Ensure all our selection tracking variables are cleared when we
     * exit menu selection (used in remote control mode)
     */
    private void clearSelectionState() {
        if( MyDebug.LOG )
            Log.d(TAG, "clearSelectionState");
        mPopupLine = 0;
        mPopupIcon = 0;
        mSelectingIcons = false;
        mSelectingLines = false;
        mHighlightedIcon= null;
        mHighlightedLine = null;
    }


    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if( MyDebug.LOG )
            Log.d(TAG, "onKeyDown: " + keyCode);
        switch( keyCode ) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: // media codes are for "selfie sticks" buttons
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            {
                if( keyCode == KeyEvent.KEYCODE_VOLUME_UP )
                    keydown_volume_up = true;
                else if( keyCode == KeyEvent.KEYCODE_VOLUME_DOWN )
                    keydown_volume_down = true;

                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
                String volume_keys = sharedPreferences.getString(PreferenceKeys.VolumeKeysPreferenceKey, "volume_take_photo");

                if((keyCode==KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        ||keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        ||keyCode==KeyEvent.KEYCODE_MEDIA_STOP)
                        &&!(volume_keys.equals("volume_take_photo"))) {
                    AudioManager audioManager = (AudioManager) main_activity.getSystemService(Context.AUDIO_SERVICE);
                    if(audioManager==null) break;
                    if(!audioManager.isWiredHeadsetOn()) break; // isWiredHeadsetOn() is deprecated, but comment says "Use only to check is a headset is connected or not."
                }

                switch(volume_keys) {
                    case "volume_take_photo":
                        main_activity.takePicture(false);
                        return true;
                    case "volume_focus":
                        if(keydown_volume_up && keydown_volume_down) {
                            if (MyDebug.LOG)
                                Log.d(TAG, "take photo rather than focus, as both volume keys are down");
                            main_activity.takePicture(false);
                        }
                        else if (main_activity.getPreview().getCurrentFocusValue() != null && main_activity.getPreview().getCurrentFocusValue().equals("focus_mode_manual2")) {
                            if(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                                main_activity.changeFocusDistance(-1, false);
                            else
                                main_activity.changeFocusDistance(1, false);
                        }
                        else {
                            // important not to repeatedly request focus, even though main_activity.getPreview().requestAutoFocus() will cancel, as causes problem if key is held down (e.g., flash gets stuck on)
                            // also check DownTime vs EventTime to prevent repeated focusing whilst the key is held down
                            if(event.getDownTime() == event.getEventTime() && !main_activity.getPreview().isFocusWaiting()) {
                                if(MyDebug.LOG)
                                    Log.d(TAG, "request focus due to volume key");
                                main_activity.getPreview().requestAutoFocus();
                            }
                        }
                        return true;
                    case "volume_zoom":
                        if(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                            main_activity.zoomIn();
                        else
                            main_activity.zoomOut();
                        return true;
                    case "volume_exposure":
                        if(main_activity.getPreview().getCameraController() != null) {
                            String value = sharedPreferences.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
                            boolean manual_iso = !value.equals(CameraController.ISO_DEFAULT);
                            if(keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                                if(manual_iso) {
                                    if(main_activity.getPreview().supportsISORange())
                                        main_activity.changeISO(1);
                                }
                                else
                                    main_activity.changeExposure(1);
                            }
                            else {
                                if(manual_iso) {
                                    if(main_activity.getPreview().supportsISORange())
                                        main_activity.changeISO(-1);
                                }
                                else
                                    main_activity.changeExposure(-1);
                            }
                        }
                        return true;
                    case "volume_auto_stabilise":
                        if( main_activity.supportsAutoStabilise() ) {
                            boolean auto_stabilise = sharedPreferences.getBoolean(PreferenceKeys.AutoStabilisePreferenceKey, false);
                            auto_stabilise = !auto_stabilise;
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean(PreferenceKeys.AutoStabilisePreferenceKey, auto_stabilise);
                            editor.apply();
                            String message = main_activity.getResources().getString(R.string.preference_auto_stabilise) + ": " + main_activity.getResources().getString(auto_stabilise ? R.string.on : R.string.off);
                            main_activity.getPreview().showToast(main_activity.getChangedAutoStabiliseToastBoxer(), message);
                        }
                        else {
                            main_activity.getPreview().showToast(main_activity.getChangedAutoStabiliseToastBoxer(), R.string.auto_stabilise_not_supported);
                        }
                        return true;
                    case "volume_really_nothing":
                        // do nothing, but still return true so we don't change volume either
                        return true;
                }
                // else do nothing here, but still allow changing of volume (i.e., the default behaviour)
                break;
            }
            case KeyEvent.KEYCODE_MENU:
            {
                // needed to support hardware menu button
                // tested successfully on Samsung S3 (via RTL)
                // see http://stackoverflow.com/questions/8264611/how-to-detect-when-user-presses-menu-key-on-their-android-device
                return true;
            }
            case KeyEvent.KEYCODE_CAMERA:
            {
                if( event.getRepeatCount() == 0 ) {
                    main_activity.takePicture(false);
                    return true;
                }
            }
            case KeyEvent.KEYCODE_FOCUS:
            {
                // important not to repeatedly request focus, even though main_activity.getPreview().requestAutoFocus() will cancel - causes problem with hardware camera key where a half-press means to focus
                // also check DownTime vs EventTime to prevent repeated focusing whilst the key is held down - see https://sourceforge.net/p/opencamera/tickets/174/ ,
                // or same issue above for volume key focus
                if( event.getDownTime() == event.getEventTime() && !main_activity.getPreview().isFocusWaiting() ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "request focus due to focus key");
                    main_activity.getPreview().requestAutoFocus();
                }
                return true;
            }
            case KeyEvent.KEYCODE_ZOOM_IN:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
            {
                main_activity.zoomIn();
                return true;
            }
            case KeyEvent.KEYCODE_ZOOM_OUT:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
            {
                main_activity.zoomOut();
                return true;
            }
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_NUMPAD_5:
            {
                if( isExposureUIOpen() && remote_control_mode ) {
                    commandMenuExposure();
                    return true;
                }
                else if( event.getRepeatCount() == 0 ) {
                    main_activity.takePicture(false);
                    return true;
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_NUMPAD_8:
                //case KeyEvent.KEYCODE_VOLUME_UP: // test
                if( !remote_control_mode ) {
                }
                else if( processRemoteUpButton() )
                    return true;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_NUMPAD_2:
                //case KeyEvent.KEYCODE_VOLUME_DOWN: // test
                if( !remote_control_mode ) {
                }
                else if( processRemoteDownButton() )
                    return true;
                break;
            case KeyEvent.KEYCODE_FUNCTION:
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
                break;
            case KeyEvent.KEYCODE_SLASH:
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
                toggleExposureUI();
                break;
        }
        return false;
    }

    public void onKeyUp(int keyCode, KeyEvent event) {
        if( MyDebug.LOG )
            Log.d(TAG, "onKeyUp: " + keyCode);
        if( keyCode == KeyEvent.KEYCODE_VOLUME_UP )
            keydown_volume_up = false;
        else if( keyCode == KeyEvent.KEYCODE_VOLUME_DOWN )
            keydown_volume_down = false;
    }

    /** If the exposure menu is open, selects a current line or option. Else does nothing.
     */
    public void commandMenuExposure() {
        if( MyDebug.LOG )
            Log.d(TAG, "commandMenuExposure");
        if( isExposureUIOpen() ) {
            if( isSelectingExposureUIElement() ) {
                // Close Exposure UI if new press on MENU
                // while already selecting
                toggleExposureUI();
            }
            else {
                // Select current element in Exposure UI
            }
        }
    }

    /** If the popup menu is open, selects a current line or option. Else does nothing.
     */
    public void commandMenuPopup() {
        if( MyDebug.LOG )
            Log.d(TAG, "commandMenuPopup");
    }

    /** Shows an information dialog, with a button to request not to show again.
     *  Note it's up to the caller to check whether the info_preference_key (to not show again) was
     *  already set.
     * @param title_id Resource id for title string.
     * @param info_id Resource id for dialog text string.
     * @param info_preference_key Preference key to set in SharedPreferences if the user selects to
     *                            not show the dialog again.
     * @return The AlertDialog that was created.
     */
    public AlertDialog showInfoDialog(int title_id, int info_id, final String info_preference_key) {
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(main_activity);
        alertDialog.setTitle(title_id);
        if( info_id != 0 )
            alertDialog.setMessage(info_id);
        alertDialog.setPositiveButton(android.R.string.ok, null);
        alertDialog.setNegativeButton(R.string.dont_show_again, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if( MyDebug.LOG )
                    Log.d(TAG, "user clicked dont_show_again for info dialog");
                final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putBoolean(info_preference_key, true);
                editor.apply();
            }
        });

        main_activity.showPreview(false);
        main_activity.setWindowFlagsForSettings(false); // set set_lock_protect to false, otherwise if screen is locked, user will need to unlock to see the info dialog!

        AlertDialog alert = alertDialog.create();
        // AlertDialog.Builder.setOnDismissListener() requires API level 17, so do it this way instead
        alert.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface arg0) {
                if( MyDebug.LOG )
                    Log.d(TAG, "info dialog dismissed");
                main_activity.setWindowFlagsForCamera();
                main_activity.showPreview(true);
            }
        });
        main_activity.showAlert(alert);
        return alert;
    }

    /** Returns a (possibly translated) user readable string for a white balance preference value.
     *  If the value is not recognised (this can happen for the old Camera API, some devices can
     *  have device-specific options), then the received value is returned.
     */
    public String getEntryForWhiteBalance(String value) {
        int id = -1;
        switch( value ) {
            case CameraController.WHITE_BALANCE_DEFAULT:
                id = R.string.white_balance_auto;
                break;
            case "cloudy-daylight":
                id = R.string.white_balance_cloudy;
                break;
            case "daylight":
                id = R.string.white_balance_daylight;
                break;
            case "fluorescent":
                id = R.string.white_balance_fluorescent;
                break;
            case "incandescent":
                id = R.string.white_balance_incandescent;
                break;
            case "shade":
                id = R.string.white_balance_shade;
                break;
            case "twilight":
                id = R.string.white_balance_twilight;
                break;
            case "warm-fluorescent":
                id = R.string.white_balance_warm;
                break;
            case "manual":
                id = R.string.white_balance_manual;
                break;
            default:
                break;
        }
        String entry;
        if( id != -1 ) {
            entry = main_activity.getResources().getString(id);
        }
        else {
            entry = value;
        }
        return entry;
    }

    /** Returns a (possibly translated) user readable string for a scene mode preference value.
     *  If the value is not recognised (this can happen for the old Camera API, some devices can
     *  have device-specific options), then the received value is returned.
     */
    public String getEntryForSceneMode(String value) {
        int id = -1;
        switch( value ) {
            case "action":
                id = R.string.scene_mode_action;
                break;
            case "barcode":
                id = R.string.scene_mode_barcode;
                break;
            case "beach":
                id = R.string.scene_mode_beach;
                break;
            case "candlelight":
                id = R.string.scene_mode_candlelight;
                break;
            case CameraController.SCENE_MODE_DEFAULT:
                id = R.string.scene_mode_auto;
                break;
            case "fireworks":
                id = R.string.scene_mode_fireworks;
                break;
            case "landscape":
                id = R.string.scene_mode_landscape;
                break;
            case "night":
                id = R.string.scene_mode_night;
                break;
            case "night-portrait":
                id = R.string.scene_mode_night_portrait;
                break;
            case "party":
                id = R.string.scene_mode_party;
                break;
            case "portrait":
                id = R.string.scene_mode_portrait;
                break;
            case "snow":
                id = R.string.scene_mode_snow;
                break;
            case "sports":
                id = R.string.scene_mode_sports;
                break;
            case "steadyphoto":
                id = R.string.scene_mode_steady_photo;
                break;
            case "sunset":
                id = R.string.scene_mode_sunset;
                break;
            case "theatre":
                id = R.string.scene_mode_theatre;
                break;
            default:
                break;
        }
        String entry;
        if( id != -1 ) {
            entry = main_activity.getResources().getString(id);
        }
        else {
            entry = value;
        }
        return entry;
    }

    /** Returns a (possibly translated) user readable string for a color effect preference value.
     *  If the value is not recognised (this can happen for the old Camera API, some devices can
     *  have device-specific options), then the received value is returned.
     */
    public String getEntryForColorEffect(String value) {
        int id = -1;
        switch( value ) {
            case "aqua":
                id = R.string.color_effect_aqua;
                break;
            case "blackboard":
                id = R.string.color_effect_blackboard;
                break;
            case "mono":
                id = R.string.color_effect_mono;
                break;
            case "negative":
                id = R.string.color_effect_negative;
                break;
            case CameraController.COLOR_EFFECT_DEFAULT:
                id = R.string.color_effect_none;
                break;
            case "posterize":
                id = R.string.color_effect_posterize;
                break;
            case "sepia":
                id = R.string.color_effect_sepia;
                break;
            case "solarize":
                id = R.string.color_effect_solarize;
                break;
            case "whiteboard":
                id = R.string.color_effect_whiteboard;
                break;
            default:
                break;
        }
        String entry;
        if( id != -1 ) {
            entry = main_activity.getResources().getString(id);
        }
        else {
            entry = value;
        }
        return entry;
    }

    /** Returns a (possibly translated) user readable string for an antibanding preference value.
     *  If the value is not recognised, then the received value is returned.
     */
    public String getEntryForAntiBanding(String value) {
        int id = -1;
        switch( value ) {
            case CameraController.ANTIBANDING_DEFAULT:
                id = R.string.anti_banding_auto;
                break;
            case "50hz":
                id = R.string.anti_banding_50hz;
                break;
            case "60hz":
                id = R.string.anti_banding_60hz;
                break;
            case "off":
                id = R.string.anti_banding_off;
                break;
            default:
                break;
        }
        String entry;
        if( id != -1 ) {
            entry = main_activity.getResources().getString(id);
        }
        else {
            entry = value;
        }
        return entry;
    }

    /** Returns a (possibly translated) user readable string for an noise reduction mode preference value.
     *  If the value is not recognised, then the received value is returned.
     *  Also used for edge mode.
     */
    public String getEntryForNoiseReductionMode(String value) {
        int id = -1;
        switch( value ) {
            case CameraController.NOISE_REDUCTION_MODE_DEFAULT:
                id = R.string.noise_reduction_mode_default;
                break;
            case "off":
                id = R.string.noise_reduction_mode_off;
                break;
            case "minimal":
                id = R.string.noise_reduction_mode_minimal;
                break;
            case "fast":
                id = R.string.noise_reduction_mode_fast;
                break;
            case "high_quality":
                id = R.string.noise_reduction_mode_high_quality;
                break;
            default:
                break;
        }
        String entry;
        if( id != -1 ) {
            entry = main_activity.getResources().getString(id);
        }
        else {
            entry = value;
        }
        return entry;
    }

    View getTopIcon() {
        return this.top_icon;
    }

    // for testing
    public View getUIButton(String key) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "getPopupButton(" + key + "): " + test_ui_buttons.get(key));
            Log.d(TAG, "this: " + this);
            Log.d(TAG, "popup_buttons: " + test_ui_buttons);
        }
        return test_ui_buttons.get(key);
    }

    Map<String, View> getTestUIButtonsMap() {
        return test_ui_buttons;
    }

    public boolean testGetRemoteControlMode() {
        return remote_control_mode;
    }

    public int testGetPopupLine() {
        return mPopupLine;
    }

    public int testGetPopupIcon() {
        return mPopupIcon;
    }

    public int testGetExposureLine() {
        return mExposureLine;
    }
}
