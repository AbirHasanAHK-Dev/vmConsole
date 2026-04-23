package sylirre.vmconsole;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import java.io.File;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sylirre.vmconsole.termlib.TerminalSession;
import sylirre.vmconsole.termlib.TerminalSession.SessionChangedCallback;
import sylirre.vmconsole.termview.TerminalView;

public final class EnhancedTerminalActivity extends Activity implements ServiceConnection {

    private static final int MENU_OPEN_SSH = 100;
    private static final int MENU_OPEN_WEB = 101;
    private static final int MENU_OPEN_VNC = 102;
    private static final int MENU_COPY_SSH_COMMAND = 103;
    private static final int MENU_COPY_VNC_ADDRESS = 104;
    private static final int MENU_SHOW_PORTS = 105;
    private static final int MENU_CONFIGURE_PORTS = 106;
    private static final int MENU_CONFIGURE_DISPLAY = 107;
    private static final int MENU_AUTOFILL_PW = 108;
    private static final int MENU_SELECT_URLS = 109;
    private static final int MENU_RESET_TERMINAL = 110;
    private static final int MENU_SHUTDOWN = 111;
    private static final int MENU_TOGGLE_IGNORE_BELL = 112;

    private static int currentFontSize = -1;

    private final int MAX_FONTSIZE = 256;
    private int MIN_FONTSIZE;

    TerminalPreferences mSettings;
    TerminalView mTerminalView;
    ExtraKeysView mExtraKeysView;
    TerminalService mTermService;
    private boolean mIsVisible;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.main);

        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setOnKeyListener(new InputDispatcher(this));
        mTerminalView.setKeepScreenOn(true);
        mTerminalView.requestFocus();
        setupTerminalStyle();
        registerForContextMenu(mTerminalView);

        mSettings = new TerminalPreferences(this);
        mExtraKeysView = findViewById(R.id.extra_keys);
        if (mSettings.isExtraKeysEnabled()) {
            mExtraKeysView.setVisibility(View.VISIBLE);
        }

        startApplication();
    }

    private void startApplication() {
        Intent serviceIntent = new Intent(this, TerminalService.class);
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0)) {
            throw new RuntimeException("bindService() failed");
        }
    }

    private void setupTerminalStyle() {
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        int defaultFontSize = Math.round(7.5f * dipInPixels);
        if (defaultFontSize % 2 == 1) defaultFontSize--;
        if (currentFontSize == -1) currentFontSize = defaultFontSize;
        MIN_FONTSIZE = (int) (4f * dipInPixels);
        currentFontSize = Math.max(MIN_FONTSIZE, Math.min(currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(currentFontSize);
        mTerminalView.setTypeface(Typeface.createFromAsset(getAssets(), "console_font.ttf"));
    }

    public void changeFontSize(boolean increase) {
        currentFontSize += (increase ? 2 : -2);
        currentFontSize = Math.max(MIN_FONTSIZE, Math.min(currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(currentFontSize);
    }

    public void toggleShowExtraKeys() {
        boolean enabled = mSettings.toggleShowExtraKeys(this);
        if (mExtraKeysView != null) {
            mExtraKeysView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsVisible = true;
        if (mTermService != null) {
            TerminalSession session = mTermService.getSession();
            if (session != null) {
                mTerminalView.attachSession(session);
            }
        }
        mTerminalView.onScreenUpdated();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsVisible = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTermService != null) {
            mTermService.mSessionChangeCallback = null;
            mTermService = null;
            unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TerminalService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (mTerminalView.getCurrentSession() == changedSession) {
                    mTerminalView.onScreenUpdated();
                }
            }

            @Override
            public void onTitleChanged(TerminalSession updatedSession) {
                return;
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                currentFontSize = -1;
                if (!BuildConfig.DEBUG) {
                    if (mTermService.mWantsToStop) {
                        if (!EnhancedTerminalActivity.this.isFinishing()) {
                            finish();
                        }
                        return;
                    }
                    mTermService.terminateService();
                }
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(text)));
                }
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible || mSettings.isBellIgnored()) {
                    return;
                }
                Bell.getInstance(EnhancedTerminalActivity.this).doBell();
            }
        };

        if (mTermService.getSession() == null) {
            if (mIsVisible) {
                Installer.setupIfNeeded(EnhancedTerminalActivity.this, () -> {
                    if (mTermService == null) return;
                    try {
                        TerminalSession session = startQemu();
                        mTerminalView.attachSession(session);
                        mTermService.setSession(session);
                    } catch (WindowManager.BadTokenException e) {
                        Log.w(Config.APP_LOG_TAG, "Unable to attach session", e);
                    }
                });
            } else {
                if (!EnhancedTerminalActivity.this.isFinishing()) {
                    finish();
                }
            }
        } else {
            mTerminalView.attachSession(mTermService.getSession());
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (!EnhancedTerminalActivity.this.isFinishing()) {
            finish();
        }
    }

    private boolean hasStoragePermission() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private int getFreePort(int minPort, int maxPort, List<Integer> reservedPorts, int preferredPort) {
        if (preferredPort >= minPort && preferredPort <= maxPort && !reservedPorts.contains(preferredPort)) {
            try (ServerSocket sock = new ServerSocket(preferredPort)) {
                sock.setReuseAddress(true);
                return sock.getLocalPort();
            } catch (Exception e) {
                Log.w(Config.APP_LOG_TAG, "preferred tcp port unavailable", e);
            }
        }

        Random rnd = new Random();
        for (int i = 0; i < 32; i++) {
            int candidate = minPort + rnd.nextInt(maxPort - minPort + 1);
            if (reservedPorts.contains(candidate)) {
                continue;
            }
            try (ServerSocket sock = new ServerSocket(candidate)) {
                sock.setReuseAddress(true);
                return sock.getLocalPort();
            } catch (Exception e) {
                Log.w(Config.APP_LOG_TAG, "cannot acquire tcp port", e);
            }
        }
        return -1;
    }

    private int[] getSafeMem() {
        Context appContext = this;
        ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();

        if (am == null) {
            return new int[]{Config.QEMU_MIN_TCG_BUF, Config.QEMU_MIN_SAFE_RAM};
        }

        am.getMemoryInfo(memInfo);
        int safeMem = (int) ((memInfo.availMem * 0.8 - memInfo.threshold) / 1048576);
        int tcgAlloc = Math.min(Config.QEMU_MAX_TCG_BUF, Math.max(Config.QEMU_MIN_TCG_BUF, (int) (safeMem * 0.12)));
        int ramAlloc = Math.min(Config.QEMU_MAX_SAFE_RAM, Math.max(Config.QEMU_MIN_SAFE_RAM, (int) (safeMem - safeMem * 0.12)));
        return new int[]{tcgAlloc, ramAlloc};
    }

    private TerminalSession startQemu() {
        ArrayList<String> environment = new ArrayList<>();
        String runtimeDataPath = Config.getDataDirectory(this);

        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));
        environment.add("APP_RUNTIME_DIR=" + runtimeDataPath);
        environment.add("LANG=en_US.UTF-8");
        environment.add("HOME=" + runtimeDataPath);
        environment.add("PATH=/system/bin");
        environment.add("TMPDIR=" + getCacheDir().getAbsolutePath());
        environment.add("CONFIG_QEMU_DNS=" + Config.QEMU_UPSTREAM_DNS_V4);
        environment.add("CONFIG_QEMU_DNS6=" + Config.QEMU_UPSTREAM_DNS_V6);

        String[] androidExtra = {"ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT"};
        for (String var : androidExtra) {
            String value = System.getenv(var);
            if (value != null) {
                environment.add(var + "=" + value);
            }
        }

        ArrayList<String> processArgs = new ArrayList<>();
        processArgs.add("QEMU");
        processArgs.addAll(Arrays.asList("-L", runtimeDataPath));
        processArgs.addAll(Arrays.asList("-cpu", "max"));
        processArgs.addAll(Arrays.asList("-smp", "cpus=4,cores=1,threads=1"));

        int[] mem = getSafeMem();
        processArgs.addAll(Arrays.asList("-accel", "tcg,tb-size=" + mem[0], "-m", String.valueOf(mem[1])));
        processArgs.add("-nodefaults");

        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/" + Config.CDROM_IMAGE_NAME + ",if=none,media=cdrom,index=0,id=cd0"));
        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/" + Config.PRIMARY_HDD_IMAGE_NAME + ",if=none,index=1,discard=unmap,detect-zeroes=unmap,cache=writeback,id=hd0"));
        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/" + Config.SECONDARY_HDD_IMAGE_NAME + ",if=none,index=2,discard=unmap,detect-zeroes=unmap,cache=writeback,id=hd1"));
        processArgs.addAll(Arrays.asList("-device", "virtio-scsi-pci,id=virtio-scsi-pci0"));
        processArgs.addAll(Arrays.asList("-device", "scsi-cd,bus=virtio-scsi-pci0.0,id=scsi-cd0,drive=cd0"));
        processArgs.addAll(Arrays.asList("-device", "scsi-hd,bus=virtio-scsi-pci0.0,id=scsi-hd0,drive=hd0"));
        processArgs.addAll(Arrays.asList("-device", "scsi-hd,bus=virtio-scsi-pci0.0,id=scsi-hd1,drive=hd1"));
        processArgs.addAll(Arrays.asList("-boot", "c,menu=on"));
        processArgs.addAll(Arrays.asList("-object", "rng-random,filename=/dev/urandom,id=rng0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-rng-pci,rng=rng0,id=virtio-rng-pci0"));

        mTermService.resetRuntimeState();
        StringBuilder vmnicArgs = new StringBuilder("user,id=vmnic0");
        ArrayList<Integer> reservedPorts = new ArrayList<>();

        int sshPort = getFreePort(20000, 24999, reservedPorts, 22022);
        if (sshPort != -1) {
            reservedPorts.add(sshPort);
            mTermService.SSH_PORT = sshPort;
            vmnicArgs.append(",hostfwd=tcp::").append(sshPort).append("-:22");
        }

        int webPort = getFreePort(25000, 29999, reservedPorts, 28080);
        if (webPort != -1) {
            reservedPorts.add(webPort);
            mTermService.WEB_PORT = webPort;
            vmnicArgs.append(",hostfwd=tcp::").append(webPort).append("-:80");
        }

        ArrayList<PortForward> runtimeCustomForwards = new ArrayList<>();
        for (PortForward configuredForward : mSettings.getCustomPortForwards()) {
            int assignedHostPort = getFreePort(30000, 45000, reservedPorts, configuredForward.hostPort);
            if (assignedHostPort == -1) {
                continue;
            }
            reservedPorts.add(assignedHostPort);
            runtimeCustomForwards.add(new PortForward(configuredForward.label, assignedHostPort, configuredForward.guestPort));
            vmnicArgs.append(",hostfwd=tcp::").append(assignedHostPort).append("-:").append(configuredForward.guestPort);
        }
        mTermService.setCustomForwards(runtimeCustomForwards);

        processArgs.addAll(Arrays.asList("-netdev", vmnicArgs.toString()));
        processArgs.addAll(Arrays.asList("-device", "virtio-net-pci,netdev=vmnic0,id=virtio-net-pci0"));

        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            if (hasStoragePermission()) {
                File sharedStorage = Environment.getExternalStorageDirectory();
                processArgs.addAll(Arrays.asList("-fsdev", "local,security_model=none,id=fsdev0,multidevs=remap,path=" + sharedStorage.getAbsolutePath()));
                processArgs.addAll(Arrays.asList("-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=host_storage,id=virtio-9p-pci0"));
            } else {
                Toast.makeText(this, R.string.toast_no_storage_permission, Toast.LENGTH_LONG).show();
            }
        }

        boolean vncEnabled = mSettings.isVncDisplayEnabled();
        if (vncEnabled) {
            int vncPort = getFreePort(5901, 5999, reservedPorts, 5901);
            if (vncPort != -1) {
                mTermService.setVncPort(vncPort);
                processArgs.addAll(Arrays.asList("-device", "VGA,id=vga-pci0,vgamem_mb=16"));
                processArgs.addAll(Arrays.asList("-vnc", "127.0.0.1:" + (vncPort - 5900)));
            } else {
                Toast.makeText(this, R.string.toast_open_vnc_unavailable, Toast.LENGTH_LONG).show();
                processArgs.add("-nographic");
            }
        } else {
            processArgs.add("-nographic");
        }

        processArgs.addAll(Arrays.asList("-parallel", "none"));
        processArgs.addAll(Arrays.asList("-chardev", "stdio,id=serial0,mux=off,signal=off"));
        processArgs.addAll(Arrays.asList("-serial", "chardev:serial0"));

        Log.i(Config.APP_LOG_TAG, "initiating QEMU session with following arguments: " + processArgs.toString());
        return new TerminalSession(processArgs.toArray(new String[0]), environment.toArray(new String[0]), runtimeDataPath, mTermService);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        if (mTermService != null) {
            if (mTermService.SSH_PORT != -1) {
                menu.add(Menu.NONE, MENU_OPEN_SSH, Menu.NONE, getString(R.string.menu_open_ssh, "localhost:" + mTermService.SSH_PORT));
                menu.add(Menu.NONE, MENU_COPY_SSH_COMMAND, Menu.NONE, R.string.menu_copy_ssh_command);
            }
            if (mTermService.WEB_PORT != -1) {
                menu.add(Menu.NONE, MENU_OPEN_WEB, Menu.NONE, getString(R.string.menu_open_web, "localhost:" + mTermService.WEB_PORT));
            }
            if (mTermService.VNC_PORT != -1) {
                menu.add(Menu.NONE, MENU_OPEN_VNC, Menu.NONE, getString(R.string.menu_open_vnc, "localhost:" + mTermService.VNC_PORT));
                menu.add(Menu.NONE, MENU_COPY_VNC_ADDRESS, Menu.NONE, R.string.menu_copy_vnc_address);
            }
            menu.add(Menu.NONE, MENU_SHOW_PORTS, Menu.NONE, R.string.menu_show_ports);
            menu.add(Menu.NONE, MENU_CONFIGURE_PORTS, Menu.NONE, R.string.menu_configure_ports);
            menu.add(Menu.NONE, MENU_CONFIGURE_DISPLAY, Menu.NONE, R.string.menu_configure_display);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillManager autofillManager = getSystemService(AutofillManager.class);
            if (autofillManager != null && autofillManager.isEnabled()) {
                menu.add(Menu.NONE, MENU_AUTOFILL_PW, Menu.NONE, R.string.menu_autofill_pw);
            }
        }

        menu.add(Menu.NONE, MENU_SELECT_URLS, Menu.NONE, R.string.menu_select_urls);
        menu.add(Menu.NONE, MENU_RESET_TERMINAL, Menu.NONE, R.string.menu_reset_terminal);
        menu.add(Menu.NONE, MENU_SHUTDOWN, Menu.NONE, R.string.menu_shutdown);
        menu.add(Menu.NONE, MENU_TOGGLE_IGNORE_BELL, Menu.NONE, R.string.menu_toggle_ignore_bell)
            .setCheckable(true)
            .setChecked(mSettings.isBellIgnored());
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_OPEN_SSH:
                openSsh();
                return true;
            case MENU_OPEN_WEB:
                openWeb();
                return true;
            case MENU_OPEN_VNC:
                openVnc();
                return true;
            case MENU_COPY_SSH_COMMAND:
                copySshCommand();
                return true;
            case MENU_COPY_VNC_ADDRESS:
                copyVncAddress();
                return true;
            case MENU_SHOW_PORTS:
                showCurrentPortsDialog();
                return true;
            case MENU_CONFIGURE_PORTS:
                showPortConfigurationDialog();
                return true;
            case MENU_CONFIGURE_DISPLAY:
                showDisplayConfigurationDialog();
                return true;
            case MENU_AUTOFILL_PW:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AutofillManager autofillManager = getSystemService(AutofillManager.class);
                    if (autofillManager != null && autofillManager.isEnabled()) {
                        autofillManager.requestAutofill(mTerminalView);
                    }
                }
                return true;
            case MENU_SELECT_URLS:
                showUrlSelection();
                return true;
            case MENU_RESET_TERMINAL:
                TerminalSession session = mTerminalView.getCurrentSession();
                if (session != null) {
                    session.reset(true);
                    Toast.makeText(this, R.string.toast_reset_terminal, Toast.LENGTH_SHORT).show();
                }
                return true;
            case MENU_SHUTDOWN:
                if (mTermService != null) {
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_shut_down_title)
                        .setMessage(R.string.dialog_shut_down_desc)
                        .setPositiveButton(R.string.dialog_shut_down_yes_btn, (dialog, which) -> {
                            dialog.dismiss();
                            mTermService.terminateService();
                        })
                        .setNegativeButton(R.string.cancel_label, (dialog, which) -> dialog.dismiss())
                        .show();
                }
                return true;
            case MENU_TOGGLE_IGNORE_BELL:
                mSettings.setIgnoreBellCharacter(this, !mSettings.isBellIgnored());
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private void openSsh() {
        if (mTermService == null || mTermService.SSH_PORT == -1) {
            Toast.makeText(this, R.string.toast_open_ssh_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder prompt = new AlertDialog.Builder(this);
        EditText userNameInput = new EditText(this);
        userNameInput.setText(mSettings.getDefaultSshUser());
        prompt.setTitle(R.string.dialog_set_ssh_user_title);
        prompt.setView(userNameInput);
        prompt.setPositiveButton(R.string.ok_label, (dialog, which) -> {
            String userName = userNameInput.getText().toString();
            if (!userName.matches("[a-z_][a-z0-9_-]{0,31}")) {
                dialog.dismiss();
                Toast.makeText(this, R.string.dialog_set_ssh_user_invalid_name, Toast.LENGTH_LONG).show();
                return;
            }

            mSettings.setDefaultSshUser(this, userName);
            String address = "ssh://" + userName + "@127.0.0.1:" + mTermService.SSH_PORT + "/#vmConsole";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, R.string.toast_open_ssh_intent_failure, Toast.LENGTH_LONG).show();
                Log.e(Config.APP_LOG_TAG, "failed to start SSH intent", e);
            }
            dialog.dismiss();
        }).setNegativeButton(R.string.cancel_label, (dialog, which) -> dialog.dismiss()).show();
    }

    private void openWeb() {
        if (mTermService == null || mTermService.WEB_PORT == -1) {
            Toast.makeText(this, R.string.toast_open_web_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + mTermService.WEB_PORT));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_open_web_intent_failure, Toast.LENGTH_LONG).show();
            Log.e(Config.APP_LOG_TAG, "failed to start web intent", e);
        }
    }

    private void openVnc() {
        if (mTermService == null || mTermService.VNC_PORT == -1) {
            Toast.makeText(this, R.string.toast_open_vnc_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("vnc://127.0.0.1:" + mTermService.VNC_PORT));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_open_vnc_intent_failure, Toast.LENGTH_LONG).show();
            Log.e(Config.APP_LOG_TAG, "failed to start VNC intent", e);
        }
    }

    private void copySshCommand() {
        if (mTermService == null || mTermService.SSH_PORT == -1) {
            Toast.makeText(this, R.string.toast_open_ssh_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String command = "ssh " + mSettings.getDefaultSshUser() + "@127.0.0.1 -p " + mTermService.SSH_PORT;
        copyToClipboard(command);
    }

    private void copyVncAddress() {
        if (mTermService == null || mTermService.VNC_PORT == -1) {
            Toast.makeText(this, R.string.toast_open_vnc_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        copyToClipboard("vnc://127.0.0.1:" + mTermService.VNC_PORT);
    }

    private void copyToClipboard(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("vmconsole", value));
            Toast.makeText(this, R.string.toast_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    private void showCurrentPortsDialog() {
        if (mTermService == null) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_current_ports_title)
            .setMessage(mTermService.getConnectionSummaryText())
            .setPositiveButton(R.string.ok_label, null)
            .show();
    }

    private void showPortConfigurationDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_port_config, null);
        EditText input = dialogView.findViewById(R.id.custom_port_mappings);
        input.setText(mSettings.getCustomPortForwardsRaw());

        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_port_config_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok_label, (dialog, which) -> {
                mSettings.setCustomPortForwards(this, input.getText().toString());
                Toast.makeText(this, R.string.toast_ports_saved, Toast.LENGTH_LONG).show();
            })
            .setNegativeButton(R.string.cancel_label, null)
            .show();
    }

    private void showDisplayConfigurationDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_display_config, null);
        RadioGroup group = dialogView.findViewById(R.id.display_mode_group);
        RadioButton radioText = dialogView.findViewById(R.id.radio_display_text);
        RadioButton radioVnc = dialogView.findViewById(R.id.radio_display_vnc);

        if (mSettings.isVncDisplayEnabled()) {
            radioVnc.setChecked(true);
        } else {
            radioText.setChecked(true);
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_display_mode_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok_label, (dialog, which) -> {
                String displayMode = group.getCheckedRadioButtonId() == R.id.radio_display_vnc
                    ? TerminalPreferences.DISPLAY_MODE_VNC
                    : TerminalPreferences.DISPLAY_MODE_TEXT;
                mSettings.setDisplayMode(this, displayMode);
                Toast.makeText(this, R.string.toast_display_mode_saved, Toast.LENGTH_LONG).show();
            })
            .setNegativeButton(R.string.cancel_label, null)
            .show();
    }

    public void doPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null) {
            return;
        }
        CharSequence paste = clipData.getItemAt(0).coerceToText(this);
        if (!TextUtils.isEmpty(paste)) {
            TerminalSession currentSession = mTerminalView.getCurrentSession();
            if (currentSession != null) {
                currentSession.getEmulator().paste(paste.toString());
            }
        }
    }

    public void showUrlSelection() {
        TerminalSession currentSession = mTerminalView.getCurrentSession();
        if (currentSession == null) {
            return;
        }

        String text = currentSession.getEmulator().getScreen().getTranscriptText();
        LinkedHashSet<CharSequence> urlSet = extractUrls(text);
        if (urlSet.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_urls_found, Toast.LENGTH_SHORT).show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls));

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setItems(urls, (di, which) -> copyToClipboard((String) urls[which]))
            .setTitle(R.string.select_url_dialog_title)
            .create();

        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView();
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String url = (String) urls[position];
                if (!url.startsWith("file://")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    try {
                        startActivity(i, null);
                    } catch (ActivityNotFoundException e) {
                        startActivity(Intent.createChooser(i, null));
                    }
                } else {
                    Toast.makeText(this, R.string.toast_bad_url, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        });

        dialog.show();
    }

    private static LinkedHashSet<CharSequence> extractUrls(String text) {
        LinkedHashSet<CharSequence> urls = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("((?:[a-zA-Z][a-zA-Z0-9+.-]*://)[^\\s]+)");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        return urls;
    }
}
