package com.fragmentation.client;

import com.fragmentation.FragmentationMod;
import com.fragmentation.registry.ModSounds;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * 闪光弹客户端表现：爆炸瞬间的纯白闪光、zm.png 全屏致盲贴图
 * （先短暂保持再平滑淡出），以及 em.ogg 的循环耳鸣声（随时间渐弱）。
 */
@Mod.EventBusSubscriber(
    modid = FragmentationMod.MOD_ID,
    value = Dist.CLIENT
)
public final class ClientFlashbangManager {
    /** 致盲全屏贴图（用户提供的 zm.png）。 */
    private static final ResourceLocation TEXTURE =
        new ResourceLocation(
            FragmentationMod.MOD_ID, "textures/gui/flashbang/zm.png");

    private static int whiteTicks;
    private static float whiteStart;

    private static int blindElapsed;
    private static int blindTotal;
    private static float blindPeak;

    /** 贴图只设置一次双线性过滤，保证全屏拉伸平滑。 */
    private static boolean textureFilterReady;

    private ClientFlashbangManager() {
    }

    public static void trigger(float whiteFlash, float overlayPeak, int blindTicks, int deafTicks) {
        if (whiteFlash > 0.0F) {
            whiteTicks = 3;
            whiteStart = whiteFlash;
        }
        if (blindTicks > 1) {
            blindElapsed = 0;
            blindTotal = blindTicks;
            blindPeak = overlayPeak;
        }
        if (deafTicks > 10) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.getSoundManager().play(new TinnitusSound(deafTicks, 1.0F));
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            whiteTicks = 0;
            blindTotal = 0;
            blindElapsed = 0;
            return;
        }
        if (whiteTicks > 0) {
            whiteTicks--;
        }
        if (blindTotal > 0) {
            blindElapsed++;
            if (blindElapsed >= blindTotal) {
                blindTotal = 0;
                blindElapsed = 0;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        if (blindTotal > 0 && blindPeak > 0.0F) {
            double t = (double) blindElapsed / blindTotal;
            double fade;
            // 前 45% 完全致盲（现实中视网膜漂白后数秒内完全失去视力），
            // 之后 55% 平滑缓慢恢复
            if (t < 0.45) {
                fade = 1.0;
            } else {
                fade = Math.max(0.0, 1.0 - (t - 0.45) / 0.55);
            }
            fade = fade * fade * (3.0 - 2.0 * fade);
            // 只要触发了致盲，完全致盲期 zm.png 就以 alpha=1 完整显示
            // （用户提供的是不透明图片），恢复期 alpha 从 1 平滑到 0。
            // 不再用 blindPeak 压低 alpha——之前峰值随朝向/距离只有
            // 0.3~0.8，是贴图看起来发透的根因。
            float alpha = (float) fade;
            // 立即模式直接上传 zm.png：在本方法内完成 flush→绑定→建
            // 四边形→drawWithShader 全过程，不经过 GuiGraphics 的延迟
            // 批处理，因此贴图必然出现且完全不透明，淡出由 uniform 控制。
            if (alpha > 0.01F) {
                drawBlindTexture(graphics, width, height, alpha);
            }
        }

        // 白闪最后画，覆盖在致盲贴图之上（顶点色填充走 GuiGraphics
        // 批处理，会在稍后 flush，自然位于立即模式贴图之上）。
        if (whiteTicks > 0) {
            float alpha = whiteStart * (whiteTicks / 3.0F);
            int argb = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24)
                | 0x00FFFFFF;
            graphics.fill(0, 0, width, height, argb);
        }
    }

    /** 立即模式把 zm.png 铺满全屏，alpha 控制整体不透明度。 */
    private static void drawBlindTexture(
        GuiGraphics graphics,
        int width,
        int height,
        float alpha
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (!textureFilterReady) {
            try {
                AbstractTexture texture =
                    mc.getTextureManager().getTexture(TEXTURE);
                texture.setFilter(true, true);
            } catch (Exception ignored) {
                // 贴图尚未就绪时本次跳过，下一帧重试
                return;
            }
            textureFilterReady = true;
        }

        // 先把本帧已积压的 GUI（快捷栏、血量、准星等）立即刷出，
        // 再画致盲贴图——这样 zm.png 盖在 HUD 之上，不会露出界面。
        graphics.flush();

        Matrix4f pose = graphics.pose().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // 左上
        builder.vertex(pose, 0.0F, 0.0F, 0.0F).uv(0.0F, 0.0F).endVertex();
        // 左下
        builder.vertex(pose, 0.0F, (float) height, 0.0F).uv(0.0F, 1.0F).endVertex();
        // 右下
        builder.vertex(pose, (float) width, (float) height, 0.0F)
            .uv(1.0F, 1.0F).endVertex();
        // 右上
        builder.vertex(pose, (float) width, 0.0F, 0.0F).uv(1.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(builder.end());

        // 恢复渲染状态，避免影响后续 GUI 绘制
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /**
     * 循环耳鸣声（em.ogg，全长 140 tick ≈ 7 秒），到时自动停止。
     *
     * 防中断三保险：
     * 1. sounds.json 中本音效为流式解码（stream:true），循环处
     *    无重解码缝隙；
     * 2. 总时长设有下限 MIN_TICKS——远距离爆炸 deafTicks 很短时
     *    也保证音频至少完整播放一遍，不再"放一半就停"；
     * 3. 音量按 smoothstep 包络平滑收敛到 0，停止瞬间无音量残留，
     *    消除硬切咔哒声。
     */
    private static final class TinnitusSound extends AbstractTickableSoundInstance {
        /** 最短播放时长（tick）：正好等于 em.ogg 一遍的长度。 */
        private static final int MIN_TICKS = 140;

        private final int totalTicks;
        private final float baseVolume;
        private int ticks;

        TinnitusSound(int deafTicks, float volume) {
            super(
                ModSounds.FLASHBANG_TINNITUS.get(),
                SoundSource.PLAYERS,
                SoundInstance.createUnseededRandom()
            );
            this.totalTicks = Math.max(deafTicks, MIN_TICKS);
            this.baseVolume = volume;
            this.volume = volume;
            this.pitch = 1.0F;
            this.looping = true;
            this.attenuation = Attenuation.NONE;
            this.relative = true;
        }

        @Override
        public void tick() {
            ticks++;
            if (ticks >= totalTicks || Minecraft.getInstance().player == null) {
                stop();
                return;
            }
            // smoothstep 包络：起止导数为 0，末端精确收敛到静音
            float r = 1.0F - (float) ticks / totalTicks;
            this.volume = baseVolume * (r * r * (3.0F - 2.0F * r));
        }
    }
}
