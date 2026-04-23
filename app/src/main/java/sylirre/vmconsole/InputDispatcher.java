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
import android.content.Context;
import android.media.AudioManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

import java.util.Objects;

import sylirre.vmconsole.termlib.KeyHandler;
import sylirre.vmconsole.termlib.TerminalEmulator;
import sylirre.vmconsole.termlib.TerminalSession;
import sylirre.vmconsole.termview.TerminalView;
import sylirre.vmconsole.termview.TerminalViewClient;

@SuppressWarnings("WeakerAccess")
public final class InputDispatcher implements TerminalViewClient {

    private final TerminalActivity mActivity;
    private final EnhancedTerminalActivity mEnhancedActivity;

    private boolean mVirtualControlKeyDown, mVirtualFnKeyDown;

    public InputDispatcher(TerminalActivity activity) {
        this.mActivity = activity;
        this.mEnhancedActivity = null;
    }

    public InputDispatcher(EnhancedTerminalActivity activity) {
        this.mActivity = null;
        this.mEnhancedActivity = activity;
    }

    private Context getContext() {
        return mActivity != null ? mActivity : mEnhancedActivity;
    }

    private TerminalView getTerminalView() {
        return mActivity != null ? mActivity.mTerminalView : mEnhancedActivity.mTerminalView;
    }

    private ExtraKeysView getExtraKeysView() {
        return mActivity != null ? mActivity.mExtraKeysView : mEnhancedActivity.mExtraKeysView;
    }

    private void changeFontSize(boolean increase) {
        if (mActivity != null) {
            mActivity.changeFontSize(increase);
        } else {
            mEnhancedActivity.changeFontSize(increase);
        }
    }

    private void showUrlSelection() {
        if (mActivity != null) {
            mActivity.showUrlSelection();
        } else {
            mEnhancedActivity.showUrlSelection();
        }
    }

    private void doPaste() {
        if (mActivity != null) {
            mActivity.doPaste();
        } else {
            mEnhancedActivity.doPaste();
        }
    }

    private void toggleShowExtraKeys() {
        if (mActivity != null) {
            mActivity.toggleShowExtraKeys();
        } else {
            mEnhancedActivity.toggleShowExtraKeys();
        }
    }

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            boolean increase = scale > 1.f;
            changeFontSize(increase);
            return 1.0f;
        }

        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        InputMethodManager mgr = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mgr != null) {
            mgr.showSoftInput(getTerminalView(), InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) {
        if (handleVirtualKeys(keyCode, e, true)) return true;

        if (e.isCtrlPressed() && e.isAltPressed()) {
            int unicodeChar = e.getUnicodeChar(0);

            if (unicodeChar == 'k') {
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                }
            } else if (unicodeChar == 'm') {
                getTerminalView().showContextMenu();
            } else if (unicodeChar == 'u') {
                showUrlSelection();
            } else if (unicodeChar == 'v') {
                doPaste();
            } else if (unicodeChar == '+' || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+') {
                changeFontSize(true);
            } else if (unicodeChar == '-') {
                changeFontSize(false);
            }

            return true;
        }

        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return handleVirtualKeys(keyCode, e, false);
    }

    @Override
    public boolean readControlKey() {
        ExtraKeysView extraKeysView = getExtraKeysView();
        return (extraKeysView != null && extraKeysView.readControlButton()) || mVirtualControlKeyDown;
    }

    @Override
    public boolean readAltKey() {
        ExtraKeysView extraKeysView = getExtraKeysView();
        return extraKeysView != null && extraKeysView.readAltButton();
    }

    @Override
    public boolean readShiftKey() {
        ExtraKeysView extraKeysView = getExtraKeysView();
        return extraKeysView != null && extraKeysView.readShiftButton();
    }

    @Override
    public boolean readFnKey() {
        ExtraKeysView extraKeysView = getExtraKeysView();
        return extraKeysView != null && extraKeysView.readFnButton();
    }

    @Override
    public boolean onCodePoint(final int codePoint, boolean ctrlDown, TerminalSession session) {
        if (mVirtualFnKeyDown) {
            int resultingKeyCode = -1;
            int resultingCodePoint = -1;
            boolean altDown = false;

            int lowerCase = Character.toLowerCase(codePoint);

            switch (lowerCase) {
                case 'w':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP;
                    break;
                case 'a':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                    break;
                case 's':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                    break;
                case 'd':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                    break;
                case 'p':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP;
                    break;
                case 'n':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN;
                    break;
                case 't':
                    resultingKeyCode = KeyEvent.KEYCODE_TAB;
                    break;
                case 'i':
                    resultingKeyCode = KeyEvent.KEYCODE_INSERT;
                    break;
                case 'h':
                    resultingCodePoint = '~';
                    break;
                case 'u':
                    resultingCodePoint = '_';
                    break;
                case 'l':
                    resultingCodePoint = '|';
                    break;
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    resultingKeyCode = (codePoint - '1') + KeyEvent.KEYCODE_F1;
                    break;
                case '0':
                    resultingKeyCode = KeyEvent.KEYCODE_F10;
                    break;
                case 'e':
                    resultingCodePoint = 27;
                    break;
                case '.':
                    resultingCodePoint = 28;
                    break;
                case 'b':
                case 'f':
                case 'x':
                    resultingCodePoint = lowerCase;
                    altDown = true;
                    break;
                case 'v':
                    resultingCodePoint = -1;
                    AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                    if (audio != null) {
                        audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME,
                            AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                    }
                    break;
                case 'k':
                    toggleShowExtraKeys();
                    mVirtualFnKeyDown = false;
                    break;
            }

            if (resultingKeyCode != -1) {
                TerminalEmulator term = session.getEmulator();
                session.write(Objects.requireNonNull(KeyHandler.getCode(resultingKeyCode, 0,
                    term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode())));
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    private boolean handleVirtualKeys(int keyCode, KeyEvent event, boolean down) {
        InputDevice inputDevice = event.getDevice();

        if (inputDevice != null && inputDevice.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down;
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down;
            return true;
        }

        return false;
    }
}
