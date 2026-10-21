package com.fragmentation.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** 调试显示相关按键：J 切换视图模式，K 清除全部显示。 */
public final class ModKeyBindings {
    public static final String CATEGORY = "key.categories.fragmentation";

    public static final KeyMapping SWITCH_VIEW = new KeyMapping(
        "key.fragmentation.switch_view",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_J,
        CATEGORY
    );

    public static final KeyMapping CLEAR_VIEW = new KeyMapping(
        "key.fragmentation.clear_view",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_K,
        CATEGORY
    );

    /** 屏息按键（默认左 Alt，可在控制设置中自定义）。 */
    public static final KeyMapping BREATH_HOLD = new KeyMapping(
        "key.fragmentation.breath_hold",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_LEFT_ALT,
        CATEGORY
    );

    private ModKeyBindings() {
    }
}
