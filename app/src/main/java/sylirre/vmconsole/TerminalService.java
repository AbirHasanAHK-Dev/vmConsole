/*
*************************************************************************
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*************************************************************************
*/
package sylirre.vmconsole;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

import sylirre.vmconsole.termlib.TerminalSession;
import sylirre.vmconsole.termlib.TerminalSession.SessionChangedCallback;

public class TerminalService extends Service implements SessionChangedCallback {

    private static final String INTENT_ACTION_SERVICE_STOP = "sylirre.vmconsole.ACTION_STOP_SERVICE";
    private static final String INTENT_ACTION_WAKELOCK_ENABLE = "sylirre.vmconsole.ACTION_ENABLE_WAKELOCK";
    private static final String INTENT_ACTION_WAKELOCK_DISABLE = "sylirre.vmconsole.ACTION_DISABLE_WAKELOCK";

    private static final int NOTIFICATION_ID = 1338;
    private static final String NOTIFICATION_CHANNEL_ID = "sylirre.vmconsole.NOTIFICATION_CHANNEL";

    private TerminalSession mTerminalSession = null;

    private final IBinder mBinder = new LocalBinder();

    public int SSH_PORT = -1;
    public int WEB_PORT = -1;
    public int VNC_PORT = -1;
    private final ArrayList<PortForward> mCustomForwards = new ArrayList<>();

    SessionChangedCallback mSessionChangeCallback;
    boolean mWantsToStop = false;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.application_name),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notifications from " + getString(R.string.application_name));

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onCreate() {
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        if (mWakeLock != null) mWakeLock.release();
        if (mWifiLock != null) mWifiLock.release();
        if (mTerminalSession != null) mTerminalSession.finishIfRunning();
        stopForeground(true);
    }

    @SuppressLint({"Wakelock", "WakelockTimeout"})
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        if (INTENT_ACTION_SERVICE_STOP.equals(action)) {
            terminateService();
        } else if (INTENT_ACTION_WAKELOCK_ENABLE.equals(action)) {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Config.WAKELOCK_LOG_TAG);
                mWakeLock.acquire();

                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, Config.WAKELOCK_LOG_TAG);
                mWifiLock.acquire();

                updateNotification();
            }
        } else if (INTENT_ACTION_WAKELOCK_DISABLE.equals(action)) {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;

                mWifiLock.release();
                mWifiLock = null;

                updateNotification();
            }
        } else if (action != null) {
            Log.w(Config.APP_LOG_TAG, "received an unknown action for TerminalService: " + action);
        }

        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onSessionFinished(final TerminalSession finishedSession) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onSessionFinished(finishedSession);
        }
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onTextChanged(changedSession);
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onTitleChanged(changedSession);
        updateNotification();
    }

    @Override
    public void onClipboardText(TerminalSession session, String text) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onClipboardText(session, text);
        }
    }

    @Override
    public void onBell(TerminalSession session) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onBell(session);
        }
    }

    public TerminalSession getSession() {
        return mTerminalSession;
    }

    public void setSession(TerminalSession session) {
        mTerminalSession = session;
        updateNotification();
    }

    public void resetRuntimeState() {
        SSH_PORT = -1;
        WEB_PORT = -1;
        VNC_PORT = -1;
        mCustomForwards.clear();
        updateNotification();
    }

    public void setVncPort(int vncPort) {
        VNC_PORT = vncPort;
        updateNotification();
    }

    public void setCustomForwards(List<PortForward> forwards) {
        mCustomForwards.clear();
        if (forwards != null) {
            mCustomForwards.addAll(forwards);
        }
        updateNotification();
    }

    public ArrayList<PortForward> getCustomForwards() {
        return new ArrayList<>(mCustomForwards);
    }

    public String getConnectionSummaryText() {
        StringBuilder builder = new StringBuilder();

        String ipAddress = getLocalIpAddress();
        if (ipAddress != null && !ipAddress.isEmpty() && !"0.0.0.0".equals(ipAddress)) {
            builder.append(getString(R.string.notification_label_ip)).append(": ").append(ipAddress);
        }

        appendLine(builder, getString(R.string.notification_label_ssh), SSH_PORT);
        appendLine(builder, getString(R.string.notification_label_web), WEB_PORT);
        appendLine(builder, getString(R.string.notification_label_vnc), VNC_PORT);

        for (PortForward forward : mCustomForwards) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(forward.toDisplayString());
        }

        if (builder.length() == 0) {
            builder.append(getString(R.string.notification_no_ports));
        }

        return builder.toString();
    }

    public void terminateService() {
        mWantsToStop = true;
        if (mTerminalSession != null) mTerminalSession.finishIfRunning();
        stopForeground(true);
        stopSelf();
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getConnectionInfo() == null) {
                return null;
            }
            return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        } catch (Exception e) {
            Log.w(Config.APP_LOG_TAG, "Unable to get local IP address", e);
            return null;
        }
    }

    private void appendLine(StringBuilder builder, String label, int port) {
        if (port == -1) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(port);
    }

    private Notification buildNotification() {
        Intent notifyIntent = new Intent(this, EnhancedTerminalActivity.class);
        notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String contentTitle = getString(R.string.notification_title_stopped);
        if (mTerminalSession != null) {
            contentTitle = getString(R.string.notification_title_running);
        }

        StringBuilder contentText = new StringBuilder(getConnectionSummaryText());
        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) {
            contentText.append('\n').append(getString(R.string.notification_wakelock_held));
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle(contentTitle);
        builder.setContentText(contentText.toString());
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText.toString()));
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setOngoing(true);
        builder.setShowWhen(false);
        builder.setColor(0xFF000000);

        String newWakeAction = wakeLockHeld ? INTENT_ACTION_WAKELOCK_DISABLE : INTENT_ACTION_WAKELOCK_ENABLE;
        Intent toggleWakeLockIntent = new Intent(this, TerminalService.class).setAction(newWakeAction);
        String actionTitle = getResources().getString(
            wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock
        );
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(
            actionIcon,
            actionTitle,
            PendingIntent.getService(this, 0, toggleWakeLockIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
        );

        return builder.build();
    }

    public void updateNotification() {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
    }

    public class LocalBinder extends Binder {
        public final TerminalService service = TerminalService.this;
    }
}
