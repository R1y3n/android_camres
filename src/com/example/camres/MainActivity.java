package com.example.camres;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final String PREFS = "camres_presets";
    private static final String KEY_PRESETS = "presets_json";

    private Spinner cameraSpinner;
    private Spinner resSpinner;
    private Spinner fpsSpinner;
    private Spinner encoderSpinner;
    private Spinner formatSpinner;
    private Spinner bitrateSpinner;
    private EditText bitrateMbpsInput;
    private Spinner orientationSpinner;
    private EditText durationSecInput;
    private Spinner focusModeSpinner;
    private SeekBar focusDistSeek;
    private TextView focusDistLabel;
    private TextView focusInfoText;
    private CheckBox cbAudio;
    private TextView supportedText;
    private TextView resultText;
    private TextView liveMetaText;
    private Spinner presetSpinner;
    private EditText presetNameInput;

    private final List<String> cameraIds = new ArrayList<>();
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private volatile CameraDevice openedCamera;
    private volatile CameraCaptureSession openedSession;

    private float currentMinFocusDistance = 0f; // diopters, 0 = fixed/infinity
    private volatile boolean liveMetaEnabled = false;
    private final AtomicLong lastMetaUiMs = new AtomicLong(0);

    // Option model classes
    static class ResOption {
        final int w, h; final boolean supported; final String label;
        ResOption(int w, int h, boolean supported) {
            this.w = w; this.h = h; this.supported = supported;
            this.label = w + "x" + h + (supported ? " [supported]" : " [unsupported probe]");
        }
        public String toString() { return label; }
    }
    static class FpsOption {
        final int fps; final boolean supported; final String label;
        FpsOption(int fps, boolean supported) {
            this.fps = fps; this.supported = supported;
            this.label = fps + " fps" + (supported ? " [in range]" : " [probe]");
        }
        public String toString() { return label; }
    }
    static class EncoderOption {
        final String name; final int code; final String mime;
        EncoderOption(String name, int code, String mime) { this.name = name; this.code = code; this.mime = mime; }
        public String toString() { return name + " (" + mime + ")"; }
    }
    static class FormatOption {
        final String name; final int code; final String ext;
        FormatOption(String name, int code, String ext) { this.name = name; this.code = code; this.ext = ext; }
        public String toString() { return name; }
    }
    static class BitrateOption {
        final String name; final int bps;
        BitrateOption(String name, int bps) { this.name = name; this.bps = bps; }
        public String toString() { return name; }
    }
    static class OrientationOption {
        final String name; final int degrees;
        OrientationOption(String name, int degrees) { this.name = name; this.degrees = degrees; }
        public String toString() { return name; }
    }
    static class FocusModeOption {
        final String name; final int afMode; final boolean manualDistance;
        FocusModeOption(String name, int afMode, boolean manualDistance) { this.name = name; this.afMode = afMode; this.manualDistance = manualDistance; }
        public String toString() { return name; }
    }

    private final List<ResOption> resOptions = new ArrayList<>();
    private final List<FpsOption> fpsOptions = new ArrayList<>();
    private final List<EncoderOption> encoderOptions = new ArrayList<>();
    private final List<FormatOption> formatOptions = new ArrayList<>();
    private final List<BitrateOption> bitrateOptions = new ArrayList<>();
    private final List<OrientationOption> orientationOptions = new ArrayList<>();
    private final List<FocusModeOption> focusModeOptions = new ArrayList<>();
    private final List<String> presetNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraSpinner = findViewById(R.id.camera_spinner);
        resSpinner = findViewById(R.id.res_spinner);
        fpsSpinner = findViewById(R.id.fps_spinner);
        encoderSpinner = findViewById(R.id.encoder_spinner);
        formatSpinner = findViewById(R.id.format_spinner);
        bitrateSpinner = findViewById(R.id.bitrate_spinner);
        bitrateMbpsInput = findViewById(R.id.bitrate_mbps);
        orientationSpinner = findViewById(R.id.orientation_spinner);
        durationSecInput = findViewById(R.id.duration_sec);
        focusModeSpinner = findViewById(R.id.focus_mode_spinner);
        focusDistSeek = findViewById(R.id.focus_dist_seek);
        focusDistLabel = findViewById(R.id.focus_dist_label);
        focusInfoText = findViewById(R.id.focus_info_text);
        cbAudio = findViewById(R.id.cb_audio);
        supportedText = findViewById(R.id.supported_text);
        resultText = findViewById(R.id.result_text);
        liveMetaText = findViewById(R.id.live_meta_text);
        presetSpinner = findViewById(R.id.preset_spinner);
        presetNameInput = findViewById(R.id.preset_name);

        Button btnScan = findViewById(R.id.btn_scan);
        Button btnTest = findViewById(R.id.btn_test);
        Button btnPresetSave = findViewById(R.id.btn_preset_save);
        Button btnPresetLoad = findViewById(R.id.btn_preset_load);
        Button btnPresetRename = findViewById(R.id.btn_preset_rename);

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanSelectedCamera(); }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runTest(); }
        });
        btnPresetSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { savePreset(); }
        });
        btnPresetLoad.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadSelectedPreset(); }
        });
        btnPresetRename.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { renameSelectedPreset(); }
        });

        focusDistSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateFocusDistLabel(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        initStaticOptions();
        refreshPresetSpinner(null);
        ensurePermissionsAndLoadCameras();
    }

    private void initStaticOptions() {
        encoderOptions.clear();
        encoderOptions.add(new EncoderOption("H264/AVC", MediaRecorder.VideoEncoder.H264, "video/avc"));
        encoderOptions.add(new EncoderOption("H263", MediaRecorder.VideoEncoder.H263, "video/3gpp"));
        encoderOptions.add(new EncoderOption("MPEG4_SP", MediaRecorder.VideoEncoder.MPEG_4_SP, "video/mp4v-es"));
        if (Build.VERSION.SDK_INT >= 24) {
            encoderOptions.add(new EncoderOption("HEVC/H265", MediaRecorder.VideoEncoder.HEVC, "video/hevc"));
        } else {
            encoderOptions.add(new EncoderOption("HEVC/H265(code5)", 5, "video/hevc"));
        }
        encoderOptions.add(new EncoderOption("VP8(code4)", 4, "video/x-vnd.on2.vp8"));

        formatOptions.clear();
        formatOptions.add(new FormatOption("MPEG_4", MediaRecorder.OutputFormat.MPEG_4, "mp4"));
        formatOptions.add(new FormatOption("THREE_GPP", MediaRecorder.OutputFormat.THREE_GPP, "3gp"));
        formatOptions.add(new FormatOption("WEBM", MediaRecorder.OutputFormat.WEBM, "webm"));

        bitrateOptions.clear();
        bitrateOptions.add(new BitrateOption("0.128 Mbps (128 kbps)", 128000));
        bitrateOptions.add(new BitrateOption("0.5 Mbps (500 kbps)", 500000));
        bitrateOptions.add(new BitrateOption("1 Mbps", 1000000));
        bitrateOptions.add(new BitrateOption("2 Mbps", 2000000));
        bitrateOptions.add(new BitrateOption("4 Mbps", 4000000));
        bitrateOptions.add(new BitrateOption("5 Mbps", 5000000));
        bitrateOptions.add(new BitrateOption("8 Mbps", 8000000));
        bitrateOptions.add(new BitrateOption("10 Mbps", 10000000));
        bitrateOptions.add(new BitrateOption("12 Mbps", 12000000));
        bitrateOptions.add(new BitrateOption("15 Mbps", 15000000));
        bitrateOptions.add(new BitrateOption("20 Mbps", 20000000));
        bitrateOptions.add(new BitrateOption("30 Mbps", 30000000));
        bitrateOptions.add(new BitrateOption("50 Mbps (probe)", 50000000));
        bitrateOptions.add(new BitrateOption("100 Mbps (probe)", 100000000));

        orientationOptions.clear();
        orientationOptions.add(new OrientationOption("Horizontal / Landscape (0°)", 0));
        orientationOptions.add(new OrientationOption("Vertical / Portrait (90°)", 90));
        orientationOptions.add(new OrientationOption("Horizontal / Landscape (180°)", 180));
        orientationOptions.add(new OrientationOption("Vertical / Portrait (270°)", 270));

        setDefaultFocusModes();
        updateFocusDistLabel();

        setSpinner(encoderSpinner, encoderOptions);
        setSpinner(formatSpinner, formatOptions);
        setSpinner(bitrateSpinner, bitrateOptions);
        setSpinner(orientationSpinner, orientationOptions);
        setSpinner(focusModeSpinner, focusModeOptions);

        resOptions.clear();
        addCommonResolutions(Collections.<String>emptySet());
        setSpinner(resSpinner, resOptions);
        fpsOptions.clear();
        addCommonFps(Collections.<Integer>emptySet());
        setSpinner(fpsSpinner, fpsOptions);
    }

    private void setDefaultFocusModes() {
        focusModeOptions.clear();
        focusModeOptions.add(new FocusModeOption("Auto (AF_MODE_AUTO)", CaptureRequest.CONTROL_AF_MODE_AUTO, false));
        focusModeOptions.add(new FocusModeOption("Continuous Video (AF_MODE_CONTINUOUS_VIDEO)", CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, false));
        focusModeOptions.add(new FocusModeOption("Continuous Picture (AF_MODE_CONTINUOUS_PICTURE)", CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, false));
        focusModeOptions.add(new FocusModeOption("Macro (AF_MODE_MACRO)", CaptureRequest.CONTROL_AF_MODE_MACRO, false));
        focusModeOptions.add(new FocusModeOption("EDOF (AF_MODE_EDOF)", CaptureRequest.CONTROL_AF_MODE_EDOF, false));
        focusModeOptions.add(new FocusModeOption("Manual / OFF (use focus distance)", CaptureRequest.CONTROL_AF_MODE_OFF, true));
    }

    private void updateFocusDistLabel() {
        float dist = getSelectedFocusDistance();
        String extra = (currentMinFocusDistance <= 0f) ? " (fixed-focus or unknown range)" : " of max " + String.format(Locale.US, "%.3f", currentMinFocusDistance);
        focusDistLabel.setText("Manual focus distance (close ← → far, diopters): " + String.format(Locale.US, "%.3f", dist) + extra);
    }

    private float getSelectedFocusDistance() {
        if (currentMinFocusDistance <= 0f) return 0f;
        int p = focusDistSeek != null ? focusDistSeek.getProgress() : 0;
        int max = focusDistSeek != null ? Math.max(1, focusDistSeek.getMax()) : 1000;
        // 0 = close (max diopters), max = far (0.0)
        return currentMinFocusDistance * (1f - (p / (float) max));
    }

    private <T> void setSpinner(Spinner sp, List<T> items) {
        ArrayAdapter<T> ad = new ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
    }

    private void ensurePermissionsAndLoadCameras() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> need = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_PERMS);
                return;
            }
        }
        loadCameras();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            loadCameras();
        }
    }

    private void loadCameras() {
        cameraIds.clear();
        List<String> labels = new ArrayList<>();
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String f = "UNKNOWN";
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) f = "BACK";
                    else if (facing == CameraCharacteristics.LENS_FACING_FRONT) f = "FRONT";
                    else if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) f = "EXTERNAL";
                }
                cameraIds.add(id);
                labels.add("id=" + id + " (" + f + ")");
            }
            if (labels.isEmpty()) labels.add("No cameras found");
        } catch (Exception e) {
            labels.clear();
            labels.add("Error listing cameras: " + e.getMessage());
            supportedText.setText(stackTrace(e));
        }
        setSpinner(cameraSpinner, labels);
    }

    private void scanSelectedCamera() {
        if (cameraIds.isEmpty()) {
            supportedText.setText("No camera available to scan.");
            return;
        }
        int idx = cameraSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= cameraIds.size()) idx = 0;
        String camId = cameraIds.get(idx);
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c = cm.getCameraCharacteristics(camId);
            StringBuilder sb = new StringBuilder();
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            sb.append("Camera id=").append(camId).append(" facing=").append(facing).append("\n");
            Integer hw = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            sb.append("Hardware level=").append(hw).append("\n\n");

            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Set<String> supportedResKeys = new LinkedHashSet<>();
            Set<Integer> supportedFpsVals = new LinkedHashSet<>();

            if (map != null) {
                Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
                sb.append("=== Video sizes (MediaRecorder.class) ===\n");
                if (videoSizes == null || videoSizes.length == 0) {
                    sb.append("  (none reported)\n");
                } else {
                    List<Size> list = Arrays.asList(videoSizes);
                    Collections.sort(list, new Comparator<Size>() {
                        @Override public int compare(Size a, Size b) {
                            int aa = a.getWidth() * a.getHeight();
                            int bb = b.getWidth() * b.getHeight();
                            if (aa != bb) return bb - aa;
                            return b.getWidth() - a.getWidth();
                        }
                    });
                    for (Size s : list) {
                        sb.append("  ").append(s.getWidth()).append("x").append(s.getHeight()).append("\n");
                        supportedResKeys.add(s.getWidth() + "x" + s.getHeight());
                    }
                }
                sb.append("\n=== High-speed video sizes ===\n");
                try {
                    Size[] hs = map.getHighSpeedVideoSizes();
                    if (hs == null || hs.length == 0) sb.append("  (none)\n");
                    else for (Size s : hs) sb.append("  ").append(s.getWidth()).append("x").append(s.getHeight()).append("\n");
                } catch (Throwable t) {
                    sb.append("  (error: ").append(t.getMessage()).append(")\n");
                }

                sb.append("\n=== Output min frame durations (sample) ===\n");
                if (videoSizes != null) {
                    int n = Math.min(videoSizes.length, 10);
                    for (int i = 0; i < n; i++) {
                        Size s = videoSizes[i];
                        try {
                            long ns = map.getOutputMinFrameDuration(MediaRecorder.class, s);
                            if (ns > 0) {
                                double maxFps = 1e9 / (double) ns;
                                sb.append("  ").append(s.getWidth()).append("x").append(s.getHeight())
                                  .append(" minFrame=").append(ns).append("ns (~").append(String.format("%.1f", maxFps)).append(" fps max)\n");
                            }
                        } catch (Throwable ignored) { }
                    }
                }
            } else {
                sb.append("No StreamConfigurationMap.\n");
            }

            Range<Integer>[] fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            sb.append("\n=== AE target FPS ranges ===\n");
            if (fpsRanges == null || fpsRanges.length == 0) {
                sb.append("  (none)\n");
            } else {
                for (Range<Integer> r : fpsRanges) {
                    sb.append("  [").append(r.getLower()).append(", ").append(r.getUpper()).append("]\n");
                    if (r.getLower() != null && r.getLower().equals(r.getUpper())) supportedFpsVals.add(r.getLower());
                    if (r.getUpper() != null) supportedFpsVals.add(r.getUpper());
                    if (r.getLower() != null) supportedFpsVals.add(r.getLower());
                }
            }

            sb.append("\n=== CamcorderProfiles ===\n");
            int camInt = -1;
            try { camInt = Integer.parseInt(camId); } catch (Exception ignored) {}
            int[] qualities = new int[]{CamcorderProfile.QUALITY_LOW, CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_QCIF,
                    CamcorderProfile.QUALITY_CIF, CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_720P,
                    CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_QVGA, CamcorderProfile.QUALITY_2160P};
            String[] qnames = new String[]{"LOW","HIGH","QCIF","CIF","480P","720P","1080P","QVGA","2160P"};
            if (camInt >= 0) {
                for (int i = 0; i < qualities.length; i++) {
                    try {
                        if (CamcorderProfile.hasProfile(camInt, qualities[i])) {
                            CamcorderProfile p = CamcorderProfile.get(camInt, qualities[i]);
                            sb.append("  ").append(qnames[i]).append(": ")
                              .append(p.videoFrameWidth).append("x").append(p.videoFrameHeight)
                              .append(" fps=").append(p.videoFrameRate)
                              .append(" vbr=").append(p.videoBitRate)
                              .append(" codec=").append(p.videoCodec)
                              .append("\n");
                            supportedResKeys.add(p.videoFrameWidth + "x" + p.videoFrameHeight);
                            supportedFpsVals.add(p.videoFrameRate);
                        }
                    } catch (Throwable t) {
                        sb.append("  ").append(qnames[i]).append(": error ").append(t.getMessage()).append("\n");
                    }
                }
            } else {
                sb.append("  (camera id not numeric, skipped)\n");
            }

            // Focus / lens info
            sb.append("\n=== Focus / lens ===\n");
            Float minFd = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            Float hyper = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
            sb.append("  LENS_INFO_MINIMUM_FOCUS_DISTANCE=").append(minFd).append(" diopters (0=fixed/infinity)\n");
            sb.append("  LENS_INFO_HYPERFOCAL_DISTANCE=").append(hyper).append("\n");
            int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            sb.append("  CONTROL_AF_AVAILABLE_MODES=").append(afModes == null ? "null" : Arrays.toString(afModes)).append("\n");
            updateFocusControls(minFd, afModes);
            StringBuilder finfo = new StringBuilder();
            finfo.append("minFocusDistance=").append(minFd).append(" diopters");
            finfo.append(" | hyperfocal=").append(hyper);
            finfo.append(" | AF modes=").append(afModes == null ? "null" : Arrays.toString(afModes));
            focusInfoText.setText(finfo.toString());

            // Extra sensor/auto info summary
            sb.append("\n=== Auto exposure / sensor (summary) ===\n");
            int[] aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            sb.append("  AE modes=").append(aeModes == null ? "null" : Arrays.toString(aeModes)).append("\n");
            Range<Integer> sensRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            sb.append("  Sensitivity(ISO) range=").append(sensRange).append("\n");
            Range<Long> expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            sb.append("  Exposure time(ns) range=").append(expRange).append("\n");
            Integer ois = null, vstab = null;
            try { int[] oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION); sb.append("  OIS modes=").append(oisModes == null ? "null" : Arrays.toString(oisModes)).append("\n"); } catch (Throwable ignored) {}
            try { int[] vs = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES); sb.append("  Video stabilization modes=").append(vs == null ? "null" : Arrays.toString(vs)).append("\n"); } catch (Throwable ignored) {}

            supportedText.setText(sb.toString());

            // rebuild test option lists
            resOptions.clear();
            addCommonResolutions(supportedResKeys);
            if (map != null && map.getOutputSizes(MediaRecorder.class) != null) {
                for (Size s : map.getOutputSizes(MediaRecorder.class)) {
                    if (!containsRes(s.getWidth(), s.getHeight())) {
                        resOptions.add(0, new ResOption(s.getWidth(), s.getHeight(), true));
                    }
                }
            }
            setSpinner(resSpinner, resOptions);

            fpsOptions.clear();
            addCommonFps(supportedFpsVals);
            if (fpsRanges != null) {
                for (Range<Integer> r : fpsRanges) {
                    maybeAddFps(r.getLower(), supportedFpsVals);
                    maybeAddFps(r.getUpper(), supportedFpsVals);
                }
            }
            setSpinner(fpsSpinner, fpsOptions);

        } catch (Exception e) {
            supportedText.setText("Scan failed:\n" + stackTrace(e));
        }
    }

    private void updateFocusControls(Float minFd, int[] afModes) {
        currentMinFocusDistance = (minFd != null && minFd > 0f) ? minFd : 0f;
        focusModeOptions.clear();
        Set<Integer> avail = new LinkedHashSet<>();
        if (afModes != null) for (int m : afModes) avail.add(m);
        if (avail.isEmpty()) {
            setDefaultFocusModes();
        } else {
            if (avail.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) focusModeOptions.add(new FocusModeOption("Auto (AF_MODE_AUTO)", CaptureRequest.CONTROL_AF_MODE_AUTO, false));
            if (avail.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) focusModeOptions.add(new FocusModeOption("Continuous Video (AF_MODE_CONTINUOUS_VIDEO)", CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, false));
            if (avail.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) focusModeOptions.add(new FocusModeOption("Continuous Picture (AF_MODE_CONTINUOUS_PICTURE)", CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, false));
            if (avail.contains(CaptureRequest.CONTROL_AF_MODE_MACRO)) focusModeOptions.add(new FocusModeOption("Macro (AF_MODE_MACRO)", CaptureRequest.CONTROL_AF_MODE_MACRO, false));
            if (avail.contains(CaptureRequest.CONTROL_AF_MODE_EDOF)) focusModeOptions.add(new FocusModeOption("EDOF (AF_MODE_EDOF)", CaptureRequest.CONTROL_AF_MODE_EDOF, false));
            if (avail.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) focusModeOptions.add(new FocusModeOption("OFF (fixed)", CaptureRequest.CONTROL_AF_MODE_OFF, false));
            // Manual distance control (uses OFF + LENS_FOCUS_DISTANCE) if variable focus supported
            if (currentMinFocusDistance > 0f) {
                focusModeOptions.add(new FocusModeOption("Manual (set focus distance, AF OFF)", CaptureRequest.CONTROL_AF_MODE_OFF, true));
            }
        }
        setSpinner(focusModeSpinner, focusModeOptions);
        // default seek to far (0 diopters) when variable focus supported
        if (focusDistSeek != null) focusDistSeek.setProgress(focusDistSeek.getMax());
        updateFocusDistLabel();
    }

    private boolean containsRes(int w, int h) {
        for (ResOption r : resOptions) if (r.w == w && r.h == h) return true;
        return false;
    }

    private void addCommonResolutions(Set<String> supportedKeys) {
        int[][] common = new int[][]{
                {176,144},{320,240},{352,288},{426,240},{640,360},{640,480},{720,480},{854,480},
                {1280,720},{1920,1080},{2560,1440},{3840,2160},{7680,4320},
                {144,176},{240,320},{480,640},{720,1280},{1080,1920}
        };
        for (int[] wh : common) {
            String k = wh[0] + "x" + wh[1];
            resOptions.add(new ResOption(wh[0], wh[1], supportedKeys.contains(k)));
        }
    }

    private void addCommonFps(Set<Integer> supported) {
        int[] common = new int[]{10, 15, 20, 24, 25, 30, 48, 50, 60, 90, 120, 240};
        for (int f : common) fpsOptions.add(new FpsOption(f, supported.contains(f)));
    }
    private void maybeAddFps(Integer f, Set<Integer> supported) {
        if (f == null) return;
        for (FpsOption o : fpsOptions) if (o.fps == f) return;
        fpsOptions.add(0, new FpsOption(f, supported.contains(f)));
    }

    private void runTest() {
        if (cameraIds.isEmpty()) { showResult(false, "No camera available.", null); return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showResult(false, "CAMERA permission not granted.", null); return;
        }
        final int camIdx = Math.max(0, Math.min(cameraSpinner.getSelectedItemPosition(), cameraIds.size() - 1));
        final String camId = cameraIds.get(camIdx);
        final ResOption res = (ResOption) resSpinner.getSelectedItem();
        final FpsOption fps = (FpsOption) fpsSpinner.getSelectedItem();
        final EncoderOption enc = (EncoderOption) encoderSpinner.getSelectedItem();
        final FormatOption fmt = (FormatOption) formatSpinner.getSelectedItem();
        final BitrateOption br = (BitrateOption) bitrateSpinner.getSelectedItem();
        final OrientationOption orient = (OrientationOption) orientationSpinner.getSelectedItem();
        final FocusModeOption focusMode = (FocusModeOption) focusModeSpinner.getSelectedItem();
        final boolean withAudio = cbAudio.isChecked();

        if (res == null || fps == null || enc == null || fmt == null || br == null || orient == null || focusMode == null) {
            showResult(false, "Missing selection.", null); return;
        }
        if (withAudio && Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showResult(false, "RECORD_AUDIO permission not granted (uncheck audio or grant permission).", null); return;
        }

        int effBitrateBps = br.bps;
        String effBitrateLabel = br.name;
        String custom = bitrateMbpsInput != null ? bitrateMbpsInput.getText().toString().trim() : "";
        if (!custom.isEmpty()) {
            try {
                double mbps = Double.parseDouble(custom.replace(',', '.'));
                if (mbps <= 0) throw new NumberFormatException("non-positive");
                long bpsLong = Math.round(mbps * 1000000.0);
                if (bpsLong > Integer.MAX_VALUE) bpsLong = Integer.MAX_VALUE;
                effBitrateBps = (int) bpsLong;
                effBitrateLabel = custom + " Mbps (custom = " + effBitrateBps + " bps)";
            } catch (Exception e) {
                showResult(false, "Invalid custom bitrate (Mbps): '" + custom + "'", e); return;
            }
        }

        double durationSec = 2.0;
        try {
            String ds = durationSecInput != null ? durationSecInput.getText().toString().trim() : "";
            if (!ds.isEmpty()) durationSec = Double.parseDouble(ds.replace(',', '.'));
        } catch (Exception ignored) {}
        if (durationSec <= 0) durationSec = 0.5;
        if (durationSec > 600) durationSec = 600; // cap at 10 min
        final int durationMsFinal = (int) Math.round(durationSec * 1000.0);
        final String durationLabel = String.format(Locale.US, "%.2fs", durationSec);

        final float focusDistFinal = getSelectedFocusDistance();
        final int bitrateBpsFinal = effBitrateBps;
        final String bitrateLabelFinal = effBitrateLabel;
        final int orientationHintFinal = orient.degrees;
        final String orientationLabelFinal = orient.name;
        final int afModeFinal = focusMode.afMode;
        final boolean manualFocusFinal = focusMode.manualDistance;
        final String focusLabelFinal = focusMode.name + (manualFocusFinal ? (" @ " + String.format(Locale.US, "%.3f", focusDistFinal) + "d") : "");

        resultText.setBackgroundColor(0xFF000000);
        resultText.setTextColor(0xFFFFFFFF);
        liveMetaText.setText("Starting...");
        final String configDesc = describeConfig(camId, res, fps, enc, fmt, bitrateLabelFinal, orientationLabelFinal, withAudio, durationLabel, focusLabelFinal);
        resultText.setText("Testing...\n" + configDesc);

        startCameraThread();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    TestOutcome out = performTest(camId, res, fps, enc, fmt, bitrateBpsFinal, orientationHintFinal, withAudio, durationMsFinal, afModeFinal, manualFocusFinal, focusDistFinal);
                    final String msg = configDesc + "\n\n" + out.message;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showResult(out.success, msg, null); }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showResult(false, configDesc + "\n\nFATAL/UNCAUGHT:", t); }
                    });
                } finally {
                    liveMetaEnabled = false;
                }
            }
        }, "CamResTest").start();
    }

    static class TestOutcome { boolean success; String message; TestOutcome(boolean s, String m){success=s;message=m;} }

    private TestOutcome performTest(String camId, ResOption res, FpsOption fps, EncoderOption enc, FormatOption fmt,
                                    int bitrateBps, int orientationHint, boolean withAudio, int durationMs,
                                    int afMode, boolean manualFocus, float focusDistance) {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        MediaRecorder recorder = null;
        CameraDevice camera = null;
        CameraCaptureSession session = null;
        SurfaceTexture previewTexture = null;
        Surface previewSurface = null;
        Uri outUri = null;
        ParcelFileDescriptor outPfd = null;
        String outDisplay = "/storage/emulated/0/DCIM/Camera";
        StringBuilder log = new StringBuilder();
        boolean success = false;
        liveMetaEnabled = true;
        lastMetaUiMs.set(0);
        postLiveMeta("Preparing test...");
        try {
            log.append("Step: create output in /storage/emulated/0/DCIM/Camera/ (MediaStore)\n");
            String mime = "video/mp4";
            if ("webm".equals(fmt.ext)) mime = "video/webm";
            else if ("3gp".equals(fmt.ext)) mime = "video/3gpp";
            String name = "CamRes_test_" + System.currentTimeMillis() + "_" + res.w + "x" + res.h + "_" + fps.fps + "fps." + fmt.ext;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outUri == null) throw new RuntimeException("MediaStore insert failed (null Uri)");
            outPfd = getContentResolver().openFileDescriptor(outUri, "w");
            if (outPfd == null) throw new RuntimeException("openFileDescriptor failed (null)");
            outDisplay = "/storage/emulated/0/DCIM/Camera/" + name + " (" + outUri + ")";
            log.append("  -> ").append(outDisplay).append("\n");

            log.append("Step: configure MediaRecorder\n");
            recorder = new MediaRecorder();
            if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(fmt.code);
            recorder.setVideoEncoder(enc.code);
            recorder.setVideoSize(res.w, res.h);
            recorder.setVideoFrameRate(fps.fps);
            recorder.setVideoEncodingBitRate(bitrateBps);
            if (withAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                try { recorder.setAudioEncodingBitRate(128000); } catch (Throwable ignored) {}
                try { recorder.setAudioSamplingRate(44100); } catch (Throwable ignored) {}
            }
            recorder.setOutputFile(outPfd.getFileDescriptor());
            try { recorder.setOrientationHint(orientationHint); } catch (Throwable ignored) {}
            log.append("  orientationHint=").append(orientationHint).append(" bitrateBps=").append(bitrateBps)
               .append(" durationMs=").append(durationMs).append("\n");
            log.append("  afMode=").append(afMode).append(" manualFocus=").append(manualFocus)
               .append(" focusDistance=").append(focusDistance).append("\n");
            log.append("Step: prepare MediaRecorder\n");
            recorder.prepare();
            Surface recorderSurface = recorder.getSurface();

            log.append("Step: create preview SurfaceTexture\n");
            previewTexture = new SurfaceTexture(1);
            previewTexture.setDefaultBufferSize(Math.max(1, res.w), Math.max(1, res.h));
            previewSurface = new Surface(previewTexture);

            log.append("Step: open camera ").append(camId).append("\n");
            camera = openCameraSync(cm, camId);
            if (camera == null) throw new RuntimeException("openCamera returned null");
            openedCamera = camera;

            log.append("Step: create capture session\n");
            List<Surface> targets = new ArrayList<>();
            targets.add(previewSurface);
            targets.add(recorderSurface);
            session = createSessionSync(camera, targets);
            openedSession = session;

            log.append("Step: build capture request\n");
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(previewSurface);
            b.addTarget(recorderSurface);
            try { b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO); } catch (Throwable ignored) {}
            try { b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); } catch (Throwable ignored) {}
            try { b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO); } catch (Throwable ignored) {}
            try { b.set(CaptureRequest.CONTROL_AF_MODE, afMode); } catch (Throwable t) { log.append("Note: set AF mode failed: ").append(t.getMessage()).append("\n"); }
            if (manualFocus) {
                try { b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance); } catch (Throwable t) { log.append("Note: set focus distance failed: ").append(t.getMessage()).append("\n"); }
            }
            try { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(fps.fps, fps.fps)); } catch (Throwable t) { log.append("Note: could not set exact FPS range: ").append(t.getMessage()).append("\n"); }

            postLiveMeta("Session configured, starting repeating request...");
            CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    handleLiveMeta(result);
                }
                @Override public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
                    // optional, lighter updates if desired
                }
                @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    postLiveMeta("Capture failed: reason=" + failure.getReason());
                }
            };
            session.setRepeatingRequest(b.build(), cb, cameraHandler);

            log.append("Step: MediaRecorder.start()\n");
            recorder.start();
            log.append("Step: recording ~").append(durationMs).append("ms\n");
            sleepQuiet(durationMs);

            liveMetaEnabled = false;
            log.append("Step: stop recorder\n");
            try { recorder.stop(); } catch (Throwable t) { log.append("stop warning: ").append(t).append("\n"); }
            log.append("Step: close session/camera\n");
            try { session.abortCaptures(); } catch (Throwable ignored) {}
            try { session.close(); } catch (Throwable ignored) {}
            session = null; openedSession = null;
            try { camera.close(); } catch (Throwable ignored) {}
            camera = null; openedCamera = null;
            try { recorder.reset(); recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
            try { previewSurface.release(); } catch (Throwable ignored) {}
            try { previewTexture.release(); } catch (Throwable ignored) {}
            try { if (outPfd != null) outPfd.close(); } catch (Throwable ignored) {}
            outPfd = null;

            if (Build.VERSION.SDK_INT >= 29 && outUri != null) {
                try {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, done, null, null);
                } catch (Throwable t) {
                    log.append("Note: could not clear IS_PENDING: ").append(t.getMessage()).append("\n");
                }
            }
            log.append("\nSUCCESS. Stored video: ").append(outDisplay).append("\n");
            postLiveMeta("Idle (test finished OK)");
            success = true;
            return new TestOutcome(true, log.toString());
        } catch (Throwable t) {
            liveMetaEnabled = false;
            log.append("\nFAILED: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
            log.append(stackTrace(t));
            try { if (session != null) session.close(); } catch (Throwable ignored) {}
            try { if (camera != null) camera.close(); } catch (Throwable ignored) {}
            try { if (recorder != null) { try { recorder.stop(); } catch (Throwable ignored) {} recorder.reset(); recorder.release(); } } catch (Throwable ignored) {}
            try { if (previewSurface != null) previewSurface.release(); } catch (Throwable ignored) {}
            try { if (previewTexture != null) previewTexture.release(); } catch (Throwable ignored) {}
            try { if (outPfd != null) outPfd.close(); } catch (Throwable ignored) {}
            if (!success && outUri != null) {
                try { getContentResolver().delete(outUri, null, null); } catch (Throwable ignored) {}
            }
            openedSession = null; openedCamera = null;
            postLiveMeta("Idle (test failed)");
            return new TestOutcome(false, log.toString());
        }
    }

    private void handleLiveMeta(CaptureResult r) {
        if (!liveMetaEnabled || r == null) return;
        long now = System.currentTimeMillis();
        long prev = lastMetaUiMs.get();
        if (now - prev < 150) return; // throttle ~6-7 Hz, update in place (no spam)
        if (!lastMetaUiMs.compareAndSet(prev, now)) return;
        try {
            StringBuilder sb = new StringBuilder();
            Integer afMode = r.get(CaptureResult.CONTROL_AF_MODE);
            Integer afState = r.get(CaptureResult.CONTROL_AF_STATE);
            Float lensDist = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
            Integer lensState = r.get(CaptureResult.LENS_STATE);
            Integer aeMode = r.get(CaptureResult.CONTROL_AE_MODE);
            Integer aeState = r.get(CaptureResult.CONTROL_AE_STATE);
            Integer awbMode = r.get(CaptureResult.CONTROL_AWB_MODE);
            Integer awbState = r.get(CaptureResult.CONTROL_AWB_STATE);
            Long exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            Long fdur = r.get(CaptureResult.SENSOR_FRAME_DURATION);
            Float aperture = r.get(CaptureResult.LENS_APERTURE);
            Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);

            sb.append("AF mode=").append(afMode).append(" state=").append(afStateName(afState)).append("\n");
            sb.append("Lens focusDist=").append(fmtFloat(lensDist)).append("d state=").append(lensStateName(lensState)).append("\n");
            sb.append("AE mode=").append(aeMode).append(" state=").append(aeStateName(aeState))
              .append(" AWB mode=").append(awbMode).append(" state=").append(awbStateName(awbState)).append("\n");
            if (exp != null) sb.append("Exposure=").append(String.format(Locale.US, "%.3fms", exp / 1e6)).append(" (").append(exp).append("ns)");
            if (iso != null) sb.append(" ISO=").append(iso);
            sb.append("\n");
            if (fdur != null && fdur > 0) sb.append("FrameDur=").append(String.format(Locale.US, "%.3fms", fdur / 1e6)).append(" (~").append(String.format(Locale.US, "%.1f", 1e9 / fdur)).append(" fps)");
            if (aperture != null) sb.append(" Aperture=f/").append(fmtFloat(aperture));
            sb.append("\n");
            if (ts != null) sb.append("Timestamp=").append(ts);
            postLiveMeta(sb.toString());
        } catch (Throwable t) {
            postLiveMeta("Live meta error: " + t.getMessage());
        }
    }

    private void postLiveMeta(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (liveMetaText != null) liveMetaText.setText(text);
            }
        });
    }

    private static String fmtFloat(Float f) { return f == null ? "n/a" : String.format(Locale.US, "%.3f", f); }
    private static String afStateName(Integer s) {
        if (s == null) return "n/a";
        switch (s) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN: return "PASSIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: return "PASSIVE_FOCUSED";
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: return "ACTIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: return "FOCUSED_LOCKED";
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: return "NOT_FOCUSED_LOCKED";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: return "PASSIVE_UNFOCUSED";
            default: return String.valueOf(s);
        }
    }
    private static String aeStateName(Integer s) {
        if (s == null) return "n/a";
        switch (s) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "LOCKED";
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: return "FLASH_REQUIRED";
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE: return "PRECAPTURE";
            default: return String.valueOf(s);
        }
    }
    private static String awbStateName(Integer s) {
        if (s == null) return "n/a";
        switch (s) {
            case CaptureResult.CONTROL_AWB_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AWB_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AWB_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AWB_STATE_LOCKED: return "LOCKED";
            default: return String.valueOf(s);
        }
    }
    private static String lensStateName(Integer s) {
        if (s == null) return "n/a";
        switch (s) {
            case CaptureResult.LENS_STATE_STATIONARY: return "STATIONARY";
            case CaptureResult.LENS_STATE_MOVING: return "MOVING";
            default: return String.valueOf(s);
        }
    }

    private CameraDevice openCameraSync(CameraManager cm, String camId) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<CameraDevice> ref = new AtomicReference<>();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        cm.openCamera(camId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) { ref.set(camera); latch.countDown(); }
            @Override public void onDisconnected(CameraDevice camera) { try { camera.close(); } catch (Throwable ignored) {} err.compareAndSet(null, new RuntimeException("Camera disconnected")); latch.countDown(); }
            @Override public void onError(CameraDevice camera, int error) { try { camera.close(); } catch (Throwable ignored) {} err.compareAndSet(null, new RuntimeException("Camera open error: " + error)); latch.countDown(); }
        }, cameraHandler);
        if (!latch.await(5, TimeUnit.SECONDS)) throw new RuntimeException("Timeout opening camera");
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private CameraCaptureSession createSessionSync(CameraDevice camera, List<Surface> targets) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<CameraCaptureSession> ref = new AtomicReference<>();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession session) { ref.set(session); latch.countDown(); }
            @Override public void onConfigureFailed(CameraCaptureSession session) { err.compareAndSet(null, new RuntimeException("Session configure failed")); latch.countDown(); }
        }, cameraHandler);
        if (!latch.await(5, TimeUnit.SECONDS)) throw new RuntimeException("Timeout creating session");
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private void startCameraThread() {
        if (cameraThread == null || !cameraThread.isAlive()) {
            cameraThread = new HandlerThread("CamResCamera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    private void showResult(boolean success, String message, Throwable t) {
        resultText.setBackgroundColor(0xFF000000);
        resultText.setTextColor(success ? 0xFF80FF80 : 0xFFFFFFFF);
        String text = (success ? "SUCCESS\n" : "FAILURE / FALLBACK (black screen text)\n") + message;
        if (t != null) text += "\n" + stackTrace(t);
        resultText.setText(text);
    }

    private String describeConfig(String camId, ResOption res, FpsOption fps, EncoderOption enc, FormatOption fmt,
                                  String bitrateLabel, String orientationLabel, boolean withAudio, String durationLabel, String focusLabel) {
        return "Camera=" + camId +
                "\nResolution=" + res.w + "x" + res.h + (res.supported ? " (supported)" : " (unsupported probe)") +
                "\nFPS=" + fps.fps + (fps.supported ? " (in-range)" : " (probe)") +
                "\nEncoder=" + enc.name + " code=" + enc.code +
                "\nFormat=" + fmt.name + " code=" + fmt.code +
                "\nBitrate=" + bitrateLabel +
                "\nOrientation=" + orientationLabel +
                "\nDuration=" + durationLabel +
                "\nFocus=" + focusLabel +
                "\nAudio=" + withAudio;
    }

    // ---- Presets ----
    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private JSONObject readPresetsObj() {
        String raw = prefs().getString(KEY_PRESETS, "{}");
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }

    private void writePresetsObj(JSONObject obj) {
        prefs().edit().putString(KEY_PRESETS, obj.toString()).apply();
    }

    private void refreshPresetSpinner(String selectName) {
        JSONObject obj = readPresetsObj();
        presetNames.clear();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) presetNames.add(keys.next());
        Collections.sort(presetNames, String.CASE_INSENSITIVE_ORDER);
        setSpinner(presetSpinner, presetNames);
        if (selectName != null) {
            for (int i = 0; i < presetNames.size(); i++) {
                if (presetNames.get(i).equals(selectName)) { presetSpinner.setSelection(i); break; }
            }
        }
    }

    private void savePreset() {
        String name = presetNameInput.getText().toString().trim();
        if (name.isEmpty()) { showResult(false, "Preset name is empty.", null); return; }
        try {
            JSONObject p = new JSONObject();
            String camId = (cameraIds.isEmpty() ? "" : cameraIds.get(Math.max(0, Math.min(cameraSpinner.getSelectedItemPosition(), cameraIds.size() - 1))));
            p.put("cameraId", camId);
            ResOption r = (ResOption) resSpinner.getSelectedItem();
            if (r != null) { p.put("resW", r.w); p.put("resH", r.h); }
            FpsOption f = (FpsOption) fpsSpinner.getSelectedItem();
            if (f != null) p.put("fps", f.fps);
            p.put("encoderIdx", encoderSpinner.getSelectedItemPosition());
            p.put("formatIdx", formatSpinner.getSelectedItemPosition());
            p.put("bitrateIdx", bitrateSpinner.getSelectedItemPosition());
            p.put("customMbps", bitrateMbpsInput.getText().toString().trim());
            p.put("orientationIdx", orientationSpinner.getSelectedItemPosition());
            p.put("durationSec", durationSecInput.getText().toString().trim());
            p.put("focusModeIdx", focusModeSpinner.getSelectedItemPosition());
            p.put("focusDistProgress", focusDistSeek.getProgress());
            p.put("audio", cbAudio.isChecked());
            JSONObject all = readPresetsObj();
            all.put(name, p);
            writePresetsObj(all);
            refreshPresetSpinner(name);
            resultText.setBackgroundColor(0xFF000000);
            resultText.setTextColor(0xFF80FF80);
            resultText.setText("Preset saved: " + name);
        } catch (Exception e) {
            showResult(false, "Failed to save preset", e);
        }
    }

    private void loadSelectedPreset() {
        if (presetNames.isEmpty()) { showResult(false, "No presets saved.", null); return; }
        int idx = presetSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= presetNames.size()) { showResult(false, "No preset selected.", null); return; }
        String name = presetNames.get(idx);
        try {
            JSONObject all = readPresetsObj();
            JSONObject p = all.optJSONObject(name);
            if (p == null) { showResult(false, "Preset not found: " + name, null); return; }
            applyPreset(p);
            presetNameInput.setText(name);
            resultText.setBackgroundColor(0xFF000000);
            resultText.setTextColor(0xFF80FF80);
            resultText.setText("Preset loaded: " + name);
        } catch (Exception e) {
            showResult(false, "Failed to load preset", e);
        }
    }

    private void renameSelectedPreset() {
        if (presetNames.isEmpty()) { showResult(false, "No presets saved.", null); return; }
        int idx = presetSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= presetNames.size()) { showResult(false, "No preset selected.", null); return; }
        String oldName = presetNames.get(idx);
        String newName = presetNameInput.getText().toString().trim();
        if (newName.isEmpty()) { showResult(false, "New preset name is empty.", null); return; }
        if (oldName.equals(newName)) { showResult(false, "New name is the same as old name.", null); return; }
        try {
            JSONObject all = readPresetsObj();
            JSONObject p = all.optJSONObject(oldName);
            if (p == null) { showResult(false, "Preset not found: " + oldName, null); return; }
            all.remove(oldName);
            all.put(newName, p);
            writePresetsObj(all);
            refreshPresetSpinner(newName);
            resultText.setBackgroundColor(0xFF000000);
            resultText.setTextColor(0xFF80FF80);
            resultText.setText("Preset renamed: " + oldName + " -> " + newName);
        } catch (Exception e) {
            showResult(false, "Failed to rename preset", e);
        }
    }

    private void applyPreset(JSONObject p) {
        // camera
        String camId = p.optString("cameraId", "");
        if (camId != null && !camId.isEmpty()) {
            for (int i = 0; i < cameraIds.size(); i++) {
                if (camId.equals(cameraIds.get(i))) { cameraSpinner.setSelection(i); break; }
            }
        }
        // resolution
        if (p.has("resW") && p.has("resH")) {
            int w = p.optInt("resW", -1), h = p.optInt("resH", -1);
            int found = -1;
            for (int i = 0; i < resOptions.size(); i++) {
                ResOption ro = resOptions.get(i);
                if (ro.w == w && ro.h == h) { found = i; break; }
            }
            if (found >= 0) resSpinner.setSelection(found);
        }
        // fps
        if (p.has("fps")) {
            int fps = p.optInt("fps", -1);
            for (int i = 0; i < fpsOptions.size(); i++) {
                if (fpsOptions.get(i).fps == fps) { fpsSpinner.setSelection(i); break; }
            }
        }
        setSel(encoderSpinner, p.optInt("encoderIdx", -1), encoderOptions.size());
        setSel(formatSpinner, p.optInt("formatIdx", -1), formatOptions.size());
        setSel(bitrateSpinner, p.optInt("bitrateIdx", -1), bitrateOptions.size());
        bitrateMbpsInput.setText(p.optString("customMbps", ""));
        setSel(orientationSpinner, p.optInt("orientationIdx", -1), orientationOptions.size());
        durationSecInput.setText(p.optString("durationSec", "2.0"));
        setSel(focusModeSpinner, p.optInt("focusModeIdx", -1), focusModeOptions.size());
        int fp = p.optInt("focusDistProgress", focusDistSeek.getMax());
        if (fp < 0) fp = 0; if (fp > focusDistSeek.getMax()) fp = focusDistSeek.getMax();
        focusDistSeek.setProgress(fp);
        cbAudio.setChecked(p.optBoolean("audio", false));
        updateFocusDistLabel();
    }

    private void setSel(Spinner sp, int idx, int size) {
        if (idx >= 0 && idx < size) sp.setSelection(idx);
    }

    // ---- utils ----
    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    @Override
    protected void onDestroy() {
        liveMetaEnabled = false;
        try { if (openedSession != null) openedSession.close(); } catch (Throwable ignored) {}
        try { if (openedCamera != null) openedCamera.close(); } catch (Throwable ignored) {}
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(1000); } catch (InterruptedException ignored) {}
            cameraThread = null;
        }
        super.onDestroy();
    }
}
