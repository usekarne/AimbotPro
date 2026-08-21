package com.webstrike.aimbotpro

/**
 * Global constants — referenced everywhere, keep changes here.
 */
object Constants {

    object Package {
        const val APP_ID = "com.webstrike.aimbotpro"
    }

    object Actions {
        const val START = "${Package.APP_ID}.action.START"
        const val STOP = "${Package.APP_ID}.action.STOP"
        const val TOGGLE_FEATURE = "${Package.APP_ID}.action.TOGGLE_FEATURE"
        const val UPDATE_CONFIG = "${Package.APP_ID}.action.UPDATE_CONFIG"
    }

    object Notifications {
        const val CHANNEL_ID = "aimbot_pro_channel"
        const val CHANNEL_NAME = "AimbotPro Service"
        const val CHANNEL_DESC = "Persistent capture + inference service"
        const val NOTIF_ID = 7301
    }

    object Capture {
        const val SCREEN_WIDTH_DEFAULT = 1080
        const val SCREEN_HEIGHT_DEFAULT = 1920
        const val SCREEN_DPI_DEFAULT = 320
        const val VIRTUAL_DISPLAY_DPI = 160
        const val TARGET_FPS = 60
        const val FRAME_TIMEOUT_MS = 1000L
    }

    object Detection {
        const val DEFAULT_MODEL_NAME = "yolov8n.tflite"
        const val DEFAULT_LABELS_NAME = "coco_labels.txt"
        const val INPUT_SIZE = 640
        const val DEFAULT_CONF_THRESHOLD = 0.25f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
        const val DEFAULT_MAX_DETECTIONS = 50
        const val HUMAN_CLASS_INDEX = 0  // COCO 'person'
    }

    object Aim {
        const val DEFAULT_FOV_RADIUS_DP = 180f
        const val DEFAULT_AIM_SPEED = 0.85f     // 0..1, higher = snappier
        const val DEFAULT_SMOOTHNESS = 0.3f      // 0..1, higher = more smoothing
        const val DEFAULT_TRIGGER_DELAY_MS = 50L
        const val HEADSHOT_BIAS = 0.18f          // upward bias for headshot mode
    }

    object Overlay {
        const val MENU_WIDTH_DP = 280
        const val MENU_COLLAPSED_WIDTH_DP = 96
        const val TOUCH_SLOP = 8f
        const val MAX_ALPHA = 255
        const val ANIM_DURATION_MS = 200L
    }

    object Prefs {
        const val KEY_OVERLAY_POS_X = "overlay.pos.x"
        const val KEY_OVERLAY_POS_Y = "overlay.pos.y"
        const val KEY_OVERLAY_COLLAPSED = "overlay.collapsed"
    }

    object Misc {
        const val DEMO_MODE_TEXT = "DEMO MODE (no model bundled)"
        const val LOG_TAG = "AimbotPro"
    }
}
