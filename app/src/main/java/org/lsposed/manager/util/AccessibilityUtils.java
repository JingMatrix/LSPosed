package org.lsposed.manager.util;

import android.content.ContentResolver;
import android.provider.Settings;

public class AccessibilityUtils {
  public static boolean isAnimationEnabled(ContentResolver cr) {
    return !(Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f
        && Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f) == 0.0f
        && Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f) == 0.0f);
  }
}
