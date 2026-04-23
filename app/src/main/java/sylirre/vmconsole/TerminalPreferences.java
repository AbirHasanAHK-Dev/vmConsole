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

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;

public class TerminalPreferences {

    public static final String DISPLAY_MODE_TEXT = "text";
    public static final String DISPLAY_MODE_VNC = "vnc";

    private static final String PREF_SHOW_EXTRA_KEYS = "show_extra_keys";
    private static final String PREF_IGNORE_BELL = "ignore_bell";
    private static final String PREF_DATA_VERSION = "data_version";
    private static final String PREF_DEFAULT_SSH_USER = "default_ssh_user";
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final String PREF_CUSTOM_PORT_FORWARDS = "custom_port_forwards";

    private boolean mShowExtraKeys;
    private boolean mIgnoreBellCharacter;
    private int mDataVersion;
    private String mDefaultSshUser;
    private String mDisplayMode;
    private String mCustomPortForwards;

    public TerminalPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        mShowExtraKeys = prefs.getBoolean(PREF_SHOW_EXTRA_KEYS, true);
        mIgnoreBellCharacter = prefs.getBoolean(PREF_IGNORE_BELL, false);
        mDataVersion = prefs.getInt(PREF_DATA_VERSION, 0);
        mDefaultSshUser = prefs.getString(PREF_DEFAULT_SSH_USER, "root");
        mDisplayMode = prefs.getString(PREF_DISPLAY_MODE, DISPLAY_MODE_TEXT);
        mCustomPortForwards = prefs.getString(PREF_CUSTOM_PORT_FORWARDS, "");
    }

    public boolean isExtraKeysEnabled() {
        return mShowExtraKeys;
    }

    public boolean toggleShowExtraKeys(Context context) {
        mShowExtraKeys = !mShowExtraKeys;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_SHOW_EXTRA_KEYS, mShowExtraKeys).apply();
        return mShowExtraKeys;
    }

    public boolean isBellIgnored() {
        return mIgnoreBellCharacter;
    }

    public void setIgnoreBellCharacter(Context context, boolean newValue) {
        mIgnoreBellCharacter = newValue;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_IGNORE_BELL, newValue).apply();
    }

    public void updateDataVersion(Context context) {
        mDataVersion = BuildConfig.VERSION_CODE;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_DATA_VERSION, mDataVersion).apply();
    }

    public int getDataVersion() {
        return mDataVersion;
    }

    public void setDefaultSshUser(Context context, String userName) {
        mDefaultSshUser = userName;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_DEFAULT_SSH_USER, userName).apply();
    }

    public String getDefaultSshUser() {
        return mDefaultSshUser;
    }

    public String getDisplayMode() {
        return mDisplayMode;
    }

    public boolean isVncDisplayEnabled() {
        return DISPLAY_MODE_VNC.equals(mDisplayMode);
    }

    public void setDisplayMode(Context context, String displayMode) {
        mDisplayMode = DISPLAY_MODE_VNC.equals(displayMode) ? DISPLAY_MODE_VNC : DISPLAY_MODE_TEXT;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_DISPLAY_MODE, mDisplayMode).apply();
    }

    public String getCustomPortForwardsRaw() {
        return mCustomPortForwards;
    }

    public void setCustomPortForwards(Context context, String rawValue) {
        mCustomPortForwards = rawValue == null ? "" : rawValue.trim();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_CUSTOM_PORT_FORWARDS, mCustomPortForwards).apply();
    }

    public ArrayList<PortForward> getCustomPortForwards() {
        return PortForward.parseUserConfig(mCustomPortForwards);
    }
}
