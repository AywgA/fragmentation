package com.fragmentation.client.screen;

import com.fragmentation.FragmentationMod;
import com.fragmentation.combat.HitRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 死亡战报界面（仿暗区突围）。
 *
 * 三栏固定布局，互不重叠：
 * - 左栏：最后一击伤害、攻击/防护详情（详细信息模式下为近一分钟命中时间线）；
 * - 中栏：人体伤势图（按部位伤势变色 + 每发精确落点，含头皮等），
 *   两侧为部位血量条；
 * - 右栏：淘汰者信息与统计。
 *
 * 主界面只显示本次交战最后 5 发受击/造成；点「详细信息」展开近一分钟全部记录。
 * 仅 ESC 关闭；玩家仍死亡时回到原版死亡界面。
 */
@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID, value = Dist.CLIENT)
public class DeathReportScreen extends Screen {

    // ---------- 人体部位 ----------
    private static final int Z_HEAD = 0;
    private static final int Z_CHEST = 1;
    private static final int Z_ABDOMEN = 2;
    private static final int Z_LEFT_ARM = 3;
    private static final int Z_RIGHT_ARM = 4;
    private static final int Z_LEFT_LEG = 5;
    private static final int Z_RIGHT_LEG = 6;
    private static final int ZONE_COUNT = 7;

    private static final String[] ZONE_NAMES = {"头部", "胸部", "腹部", "左臂", "右臂", "左腿", "右腿"};
    private static final int[] ZONE_MAX_HP = {40, 85, 70, 60, 60, 65, 65};

    /** 主界面每侧显示的子弹数。 */
    private static final int SUMMARY_HITS = 5;

    // ---------- UI 大小缩放（左上角滑块，仅本界面开启时可调） ----------
    private static final float SCALE_MIN = 0.5F;
    private static final float SCALE_MAX = 1.5F;
    private static final float SCALE_DEFAULT = 1.0F;
    private static float uiScale = SCALE_DEFAULT;
    private boolean draggingScale = false;
    private int scaleSliderX;
    private int scaleSliderY;
    private static final int SCALE_SLIDER_W = 150;
    private static final int SCALE_SLIDER_H = 10;

    static {
        loadScale();
    }

    // ---------- 颜色 ----------
    private static final int C_WHITE = 0xFFFFFFFF;
    private static final int C_DIM = 0xFF9A9AA2;
    private static final int C_LABEL = 0xFF6E6E78;
    private static final int C_GOLD = 0xFFD9B36C;
    private static final int C_RED = 0xFFE85D5D;
    private static final int C_ORANGE = 0xFFFFC23D;
    private static final int C_GRAY = 0xFFB0B0B0;
    private static final int C_MELEE = 0xFF8FE3A0;
    private static final int[] BUCKET_COLORS = {0xFF281D1D, 0xFFC4070B, 0xFFF93438, 0xFFFC9D9F, 0xFFFFFFFF};

    // ---------- 自动弹出 ----------
    private static final long FORCE_OPEN_AFTER_TICKS = 100L;
    private static final long RESPAWN_CHECK_GRACE_TICKS = 5L;

    private static List<HitRecord> pendingIncoming;
    private static List<HitRecord> pendingOutgoing;
    private static long pendingAtTick = -1L;

    // ---------- 实例数据 ----------
    private final List<HitRecord> incoming;
    private final List<HitRecord> outgoing;
    private final boolean showIncoming;
    /** true=详细信息（一分钟时间线 + 全部落点）。 */
    private final boolean detailMode;

    private final float[] damageByZone = new float[ZONE_COUNT];
    private final int[] remainingHp = new int[ZONE_COUNT];
    private final int[] buckets = new int[ZONE_COUNT];
    private HitRecord lastRecord;
    private float totalDamage;
    private int blockedCount;

    // 标签页
    private int tabIncomingX;
    private int tabOutgoingX;
    private int tabY;
    private static final int TAB_W = 92;
    private static final int TAB_H = 20;

    // 「详细信息」按钮
    private int detailBtnX;
    private int detailBtnY;
    private static final int DETAIL_BTN_W = 110;
    private static final int DETAIL_BTN_H = 18;

    // 时间线滚动
    private int listX;
    private int listW;
    private int listTop;
    private int listBottom;
    private float listScroll;

    // 人体图几何（供落点绘制）
    private int figX;
    private int figY;
    private int figW;
    private int figH;

    // 三栏边界（按屏幕宽度比例）
    private int col1Right;
    private int col3Left;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    public DeathReportScreen(List<HitRecord> incoming, List<HitRecord> outgoing,
                             boolean showIncoming, boolean detailMode) {
        super(Component.literal("死亡战报"));
        this.incoming = incoming != null ? incoming : new ArrayList<>();
        this.outgoing = outgoing != null ? outgoing : new ArrayList<>();
        this.showIncoming = showIncoming;
        this.detailMode = detailMode;
    }

    // ================= 自动弹出 =================

    public static void open(List<HitRecord> incoming, List<HitRecord> outgoing) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        pendingIncoming = incoming;
        pendingOutgoing = outgoing;
        pendingAtTick = mc.level.getGameTime();
        FragmentationMod.LOGGER.info("[DeathReport] Received death report, waiting for death screen (incoming="
            + (incoming != null ? incoming.size() : 0)
            + ", outgoing=" + (outgoing != null ? outgoing.size() : 0) + ")");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingAtTick < 0L) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clearPending();
            return;
        }

        long elapsed = mc.level.getGameTime() - pendingAtTick;

        if (elapsed >= RESPAWN_CHECK_GRACE_TICKS
                && mc.player.getHealth() > 0.0F
                && !(mc.screen instanceof DeathScreen)) {
            clearPending();
            return;
        }

        if (mc.screen instanceof DeathScreen || elapsed >= FORCE_OPEN_AFTER_TICKS) {
            List<HitRecord> in = pendingIncoming;
            List<HitRecord> out = pendingOutgoing;
            clearPending();
            mc.setScreen(new DeathReportScreen(in, out, true, false));
            FragmentationMod.LOGGER.info("[DeathReport] Opening death report after " + elapsed + " ticks");
        }
    }

    private static void clearPending() {
        pendingIncoming = null;
        pendingOutgoing = null;
        pendingAtTick = -1L;
    }

    // ================= Screen 行为 =================

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null && mc.player.getHealth() <= 0.0F) {
            mc.setScreen(new DeathScreen(null, mc.level.getLevelData().isHardcore()));
            return;
        }
        super.onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 缩放滑块在左上角，先判定（滑块不参与缩放命中，用屏幕原始坐标）。
        if (mouseX >= scaleSliderX - 2 && mouseX <= scaleSliderX + SCALE_SLIDER_W + 2
                && mouseY >= scaleSliderY - 4 && mouseY <= scaleSliderY + SCALE_SLIDER_H + 4) {
            draggingScale = true;
            updateScaleFromMouse(mouseX);
            return true;
        }

        // 其余控件在缩放后的逻辑坐标系内，鼠标坐标需除以缩放系数。
        double sx = mouseX / uiScale;
        double sy = mouseY / uiScale;

        // 标签页切换
        if (sy >= tabY && sy <= tabY + TAB_H) {
            if (showIncoming && sx >= tabOutgoingX && sx <= tabOutgoingX + TAB_W) {
                Minecraft.getInstance().setScreen(new DeathReportScreen(incoming, outgoing, false, detailMode));
                return true;
            }
            if (!showIncoming && sx >= tabIncomingX && sx <= tabIncomingX + TAB_W) {
                Minecraft.getInstance().setScreen(new DeathReportScreen(incoming, outgoing, true, detailMode));
                return true;
            }
        }
        // 「详细信息 / 返回」按钮
        if (sx >= detailBtnX && sx <= detailBtnX + DETAIL_BTN_W
                && sy >= detailBtnY && sy <= detailBtnY + DETAIL_BTN_H) {
            Minecraft.getInstance().setScreen(new DeathReportScreen(incoming, outgoing, showIncoming, !detailMode));
            return true;
        }
        // 不再支持鼠标点击关闭，只能 ESC。
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScale) {
            updateScaleFromMouse(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScale) {
            draggingScale = false;
            saveScale();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateScaleFromMouse(double mouseX) {
        float ratio = (float) (mouseX - scaleSliderX) / (float) SCALE_SLIDER_W;
        uiScale = clamp(ratio, 0.0F, 1.0F) * (SCALE_MAX - SCALE_MIN) + SCALE_MIN;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double sx = mouseX / uiScale;
        double sy = mouseY / uiScale;
        if (detailMode && sx >= listX && sx <= listX + listW
                && sy >= listTop && sy <= listBottom) {
            listScroll = clamp(listScroll + (float) delta * 4.0F, 0.0F, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // ================= 数据聚合 =================

    private void aggregate() {
        List<HitRecord> records = showIncoming ? incoming : outgoing;

        java.util.Arrays.fill(damageByZone, 0.0F);
        totalDamage = 0.0F;
        blockedCount = 0;

        for (HitRecord r : records) {
            int z = zoneIndex(r.zone);
            damageByZone[z] += r.finalDamage;
            totalDamage += r.finalDamage;
            if (HitRecord.NOT_PENETRATED.equals(r.penetrationType) && r.finalDamage <= 0.0F) {
                blockedCount++;
            }
        }

        for (int z = 0; z < ZONE_COUNT; z++) {
            remainingHp[z] = Math.max(0, Math.round(Math.max(0.0F, ZONE_MAX_HP[z] - damageByZone[z])));
        }

        // 原血量系统死亡条件：头部=0 且 胸部=0。本界面在玩家死亡时弹出，
        // 因此 incoming（死者自身）视角下，头部与胸部血量强制为 0。
        if (showIncoming) {
            remainingHp[Z_HEAD] = 0;
            remainingHp[Z_CHEST] = 0;
        }

        lastRecord = records.isEmpty() ? null : records.get(records.size() - 1);
        if (lastRecord != null && lastRecord.finalDamage > 0.0F) {
            remainingHp[zoneIndex(lastRecord.zone)] = 0;
        }
        for (int z = 0; z < ZONE_COUNT; z++) {
            buckets[z] = bucket(remainingHp[z] / (float) ZONE_MAX_HP[z]);
        }
    }

    /** 当前页用于落点绘制的记录：主模式最后 5 发；详情模式一分钟全部。 */
    private List<HitRecord> displayedHits() {
        List<HitRecord> records = showIncoming ? incoming : outgoing;
        if (detailMode || records.size() <= SUMMARY_HITS) {
            return records;
        }
        return new ArrayList<>(records.subList(records.size() - SUMMARY_HITS, records.size()));
    }

    private static int zoneIndex(String zone) {
        if (zone == null) return Z_CHEST;
        return switch (zone) {
            case "HEAD", "FACE" -> Z_HEAD;
            case "CHEST" -> Z_CHEST;
            case "ABDOMEN" -> Z_ABDOMEN;
            case "LEFT_ARM" -> Z_LEFT_ARM;
            case "RIGHT_ARM" -> Z_RIGHT_ARM;
            case "LEFT_LEG" -> Z_LEFT_LEG;
            case "RIGHT_LEG" -> Z_RIGHT_LEG;
            default -> Z_CHEST;
        };
    }

    private static int bucket(float ratio) {
        if (ratio >= 0.90F) return 100;
        if (ratio >= 0.65F) return 80;
        if (ratio >= 0.35F) return 50;
        if (ratio >= 0.10F) return 20;
        return 0;
    }

    private static int bucketArrayIndex(int bucket) {
        return switch (bucket) {
            case 100 -> 4;
            case 80 -> 3;
            case 50 -> 2;
            case 20 -> 1;
            default -> 0;
        };
    }

    private float maxScroll() {
        int rowH = font.lineHeight + 5;
        int total = (showIncoming ? incoming.size() : outgoing.size()) * rowH;
        return Math.max(0.0F, total - (listBottom - listTop));
    }

    // ================= 渲染 =================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        aggregate();

        Font font = this.font;
        int screenW = this.width;
        int screenH = this.height;

        // 背景铺满整屏（不参与缩放）。
        graphics.fillGradient(0, 0, screenW, screenH, 0xE60B0B0E, 0xF2151519);

        // 左上角缩放滑块（屏幕原始坐标，不随 UI 缩放）。
        renderScaleSlider(graphics, font);

        // 布局使用逻辑尺寸（屏幕 / 缩放系数），整体用 pose 缩放渲染。
        int viewW = Math.round(screenW / uiScale);
        int viewH = Math.round(screenH / uiScale);

        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1.0F);

        int margin = 20;
        col1Right = Math.round(viewW * 0.32F);
        col3Left = Math.round(viewW * 0.68F);
        int col1W = col1Right - margin;
        int col3W = viewW - margin - col3Left;

        renderTabs(graphics, font, viewW, margin);
        renderTitle(graphics, font, margin);

        if (detailMode) {
            renderTimelineFull(graphics, font, margin, col1W, viewH);
        } else {
            renderLastHitBar(graphics, font, margin, col1W);
            renderDetailCard(graphics, font, margin, col1W);
        }

        renderDetailButton(graphics, font, margin, viewH);
        renderMannequin(graphics, font, viewW, viewH);
        renderEliminatedCard(graphics, font, col3Left, col3W, viewH);

        graphics.drawString(font, LocalDateTime.now().format(TIME_FMT), margin, viewH - 16, C_LABEL);
        graphics.drawString(font, "按 ESC 关闭", viewW - margin - font.width("按 ESC 关闭"), viewH - 16, C_LABEL);

        graphics.pose().popPose();
    }

    /** 左上角 UI 大小滑块：仅本界面开启时显示。 */
    private void renderScaleSlider(GuiGraphics g, Font font) {
        scaleSliderX = 12;
        scaleSliderY = 8;

        String label = "UI 大小";
        g.drawString(font, label, scaleSliderX, scaleSliderY, C_WHITE);

        int trackY = scaleSliderY + font.lineHeight + 2;
        int trackH = SCALE_SLIDER_H;

        // 轨道
        g.fill(scaleSliderX, trackY, scaleSliderX + SCALE_SLIDER_W, trackY + trackH, 0x55FFFFFF);
        g.renderOutline(scaleSliderX, trackY, SCALE_SLIDER_W, trackH, 0x88FFFFFF);

        // 已填充段
        float ratio = (uiScale - SCALE_MIN) / (SCALE_MAX - SCALE_MIN);
        ratio = clamp(ratio, 0.0F, 1.0F);
        int fillW = Math.round(SCALE_SLIDER_W * ratio);
        if (fillW > 0) {
            g.fill(scaleSliderX, trackY, scaleSliderX + fillW, trackY + trackH, C_GOLD);
        }

        // 滑块手柄
        int handleX = scaleSliderX + fillW - 3;
        g.fill(handleX - 1, trackY - 2, handleX + 6, trackY + trackH + 2, 0xFF000000);
        g.fill(handleX, trackY - 1, handleX + 5, trackY + trackH + 1, C_WHITE);

        // 数值
        String val = String.format("%.2fx", uiScale);
        g.drawString(font, val, scaleSliderX + SCALE_SLIDER_W + 8, trackY + 1, C_GOLD);
    }

    // ---------- 标题 / 标签页 ----------

    private void renderTitle(GuiGraphics graphics, Font font, int margin) {
        graphics.pose().pushPose();
        graphics.pose().translate(margin, 40.0F, 0.0F);
        graphics.pose().scale(1.4F, 1.4F, 1.0F);
        graphics.drawString(font, showIncoming ? "我方伤势" : "对方伤势", 0, 0, C_WHITE);
        graphics.pose().popPose();

        int titleW = Math.round(font.width(showIncoming ? "我方伤势" : "对方伤势") * 1.4F);
        String sub = detailMode ? "（详细信息 · 近一分钟）" : "（最后 5 发命中）";
        graphics.drawString(font, sub, margin + titleW + 8, 48, C_DIM);
    }

    private void renderTabs(GuiGraphics graphics, Font font, int screenW, int margin) {
        tabY = 21;
        tabOutgoingX = screenW - margin - TAB_W;
        tabIncomingX = tabOutgoingX - TAB_W - 8;
        drawTab(graphics, font, tabIncomingX, "我方受击", showIncoming);
        drawTab(graphics, font, tabOutgoingX, "对方受击", !showIncoming);
    }

    private void drawTab(GuiGraphics graphics, Font font, int x, String label, boolean active) {
        graphics.fill(x, tabY, x + TAB_W, tabY + TAB_H, active ? 0xFFEDEDED : 0x33FFFFFF);
        graphics.renderOutline(x, tabY, TAB_W, TAB_H, active ? 0xFFFFFFFF : 0x55FFFFFF);
        graphics.drawCenteredString(font, Component.literal(label), x + TAB_W / 2, tabY + 6,
            active ? 0xFF111144 : C_DIM);
    }

    // ---------- 最后一击 ----------

    private void renderLastHitBar(GuiGraphics graphics, Font font, int margin, int colW) {
        int y = 54;
        graphics.fill(margin, y, margin + colW, y + 26, 0x42FFFFFF);
        graphics.fill(margin, y, margin + 3, y + 26, C_ORANGE);
        graphics.drawString(font, "最后一击伤害：", margin + 12, y + 9, C_WHITE);
        String dmg = lastRecord != null ? String.format("%.1f", lastRecord.finalDamage) : "--";
        graphics.drawString(font, dmg, margin + 12 + font.width("最后一击伤害：") + 4, y + 9, C_ORANGE);
    }

    // ---------- 攻击/防护卡片 ----------

    private void renderDetailCard(GuiGraphics graphics, Font font, int margin, int colW) {
        int y = 90;
        int cardH = 200;
        graphics.fill(margin, y, margin + colW, y + cardH, 0x1FFFFFFF);
        graphics.renderOutline(margin, y, colW, cardH, 0x2BFFFFFF);

        int halfW = (colW - 30) / 2;
        int attackX = margin + 12;
        int defenseX = margin + colW - 12 - halfW;

        graphics.drawString(font, showIncoming ? "对方攻击" : "我的攻击", attackX, y + 10, C_WHITE);
        graphics.drawString(font, showIncoming ? "我方防护" : "对方防护", defenseX, y + 10, C_WHITE);

        int iconY = y + 24;
        drawBulletIcon(graphics, attackX + halfW / 2 - 20, iconY);
        if (showIncoming) drawVestIcon(graphics, defenseX + halfW / 2 - 20, iconY);
        else drawHelmetIcon(graphics, defenseX + halfW / 2 - 20, iconY);

        if (lastRecord != null) {
            graphics.drawCenteredString(font, statusName(lastRecord.penetrationType),
                margin + colW / 2, iconY + 22, statusColor(lastRecord.penetrationType));
        }

        int divY = y + 70;
        graphics.fill(margin + 10, divY, margin + colW - 10, divY + 1, 0x24FFFFFF);

        int rowY = divY + 10;
        int rowH = 24;

        String bulletName = lastRecord != null
            ? (lastRecord.melee ? lastRecord.bulletId : shortName(lastRecord.bulletId)) : "无数据";
        String armorName = lastRecord != null && !lastRecord.armorName.isEmpty()
            ? lastRecord.armorName : "无防护";
        drawCardRow(graphics, font, attackX, halfW, rowY, truncate(bulletName, font, halfW));
        drawCardRow(graphics, font, defenseX, halfW, rowY, truncate(armorName, font, halfW));

        rowY += rowH;
        String penVal = lastRecord != null && !lastRecord.melee
            ? String.valueOf(Math.round(lastRecord.bulletPenLevel)) : "--";
        String lvlVal = lastRecord != null && lastRecord.armorLevel > 0
            ? String.valueOf(lastRecord.armorLevel) : "无";
        drawCardRow(graphics, font, attackX, halfW, rowY, "穿透", penVal);
        drawCardRow(graphics, font, defenseX, halfW, rowY, "防护等级", lvlVal);

        rowY += rowH;
        String baseVal = lastRecord != null ? String.format("%.0f", lastRecord.bulletBaseDamage) : "--";
        String durVal = "--";
        int durColor = C_WHITE;
        if (lastRecord != null && lastRecord.armorMaxDurability > 0) {
            durVal = lastRecord.armorDurability + "/" + lastRecord.armorMaxDurability;
            durColor = lastRecord.armorDurability <= lastRecord.armorMaxDurability / 4 ? C_RED : C_WHITE;
        }
        drawCardRow(graphics, font, attackX, halfW, rowY, "基础伤害", baseVal);
        drawCardRow(graphics, font, defenseX, halfW, rowY, "耐久", durVal, durColor);

        graphics.drawString(font, "护甲耐久越低，抗穿透越差", margin + 12, y + cardH - 14, C_LABEL);
    }

    private void drawCardRow(GuiGraphics graphics, Font font, int x, int width, int y, String text) {
        graphics.drawString(font, text, x, y, C_WHITE);
    }

    private void drawCardRow(GuiGraphics graphics, Font font, int x, int width, int y,
                             String label, String value) {
        drawCardRow(graphics, font, x, width, y, label, value, C_WHITE);
    }

    private void drawCardRow(GuiGraphics graphics, Font font, int x, int width, int y,
                             String label, String value, int valueColor) {
        graphics.drawString(font, label, x, y, C_DIM);
        graphics.drawString(font, value, x + width - font.width(value), y, valueColor);
    }

    // ---------- 「详细信息」按钮 ----------

    private void renderDetailButton(GuiGraphics graphics, Font font, int margin, int screenH) {
        detailBtnX = margin;
        detailBtnY = screenH - 44;
        String label = detailMode ? "返回" : "详细信息";
        graphics.fill(detailBtnX, detailBtnY, detailBtnX + DETAIL_BTN_W, detailBtnY + DETAIL_BTN_H,
            0x55D9B36C);
        graphics.renderOutline(detailBtnX, detailBtnY, DETAIL_BTN_W, DETAIL_BTN_H, C_GOLD);
        graphics.drawCenteredString(font, Component.literal(label),
            detailBtnX + DETAIL_BTN_W / 2, detailBtnY + 5, C_WHITE);
    }

    // ---------- 详细信息：一分钟时间线 ----------

    private void renderTimelineFull(GuiGraphics graphics, Font font, int margin, int colW, int screenH) {
        int y = 54;
        List<HitRecord> records = showIncoming ? incoming : outgoing;
        graphics.drawString(font, "近一分钟命中记录", margin, y, C_WHITE);
        graphics.drawString(font, records.size() + " 发 · 总伤害 " + String.format("%.0f", totalDamage)
            + " · 挡下 " + blockedCount, margin, y + 12, C_DIM);

        int headBottom = y + 28;
        graphics.fill(margin, headBottom, margin + colW, headBottom + 1, 0x33FFFFFF);

        listX = margin;
        listW = colW;
        listTop = headBottom + 6;
        listBottom = screenH - 56;

        if (records.isEmpty()) {
            graphics.drawString(font, "无命中记录", listX, listTop, C_DIM);
            return;
        }

        int rowH = font.lineHeight + 5;
        graphics.enableScissor(listX, listTop, listX + listW, listBottom);
        int yy = listTop - Math.round(listScroll);
        long lastTick = records.get(records.size() - 1).tick;

        for (HitRecord r : records) {
            if (yy + rowH >= listTop && yy <= listBottom) {
                long secs = Math.max(0L, (lastTick - r.tick) / 20L);
                graphics.drawString(font, "-" + secs + "s", listX, yy, C_LABEL);

                int z = zoneIndex(r.zone);
                int zoneColor = remainingHp[z] <= 0 ? C_RED : BUCKET_COLORS[bucketArrayIndex(buckets[z])];
                graphics.drawString(font, ZONE_NAMES[z], listX + 28, yy, zoneColor);

                String status = statusName(r.penetrationType);
                int statusX = listX + listW - font.width(status);
                graphics.drawString(font, status, statusX, yy, statusColor(r.penetrationType));

                String dmg = String.format("%.0f", r.finalDamage);
                graphics.drawString(font, dmg, statusX - font.width(dmg) - 8, yy, C_GOLD);

                String name = r.melee ? r.bulletId : shortName(r.bulletId);
                String bullet = truncate(name, font, statusX - 8 - (listX + 58));
                graphics.drawString(font, bullet, listX + 58, yy, C_WHITE);
            }
            yy += rowH;
        }
        graphics.disableScissor();
    }

    // ---------- 中栏：人体图 ----------

    private void renderMannequin(GuiGraphics graphics, Font font, int screenW, int screenH) {
        int centerLeft = col1Right + 12;
        int centerRight = col3Left - 12;
        int centerW = centerRight - centerLeft;

        int barW = Math.min(56, Math.max(40, Math.round(centerW * 0.16F)));
        int barSpace = barW + 10;

        int maxFigW = Math.max(90, centerW - 2 * barSpace);
        int naturalH = Math.min(screenH - 116, 420);
        int naturalW = Math.round(naturalH * 1390.0F / 1800.0F);
        figW = Math.min(naturalW, maxFigW);
        figH = Math.round(figW * 1800.0F / 1390.0F);

        figX = centerLeft + (centerW - figW) / 2;
        figY = 84 + Math.max(0, (naturalH - figH) / 2);

        blitFig(graphics, "base", figX, figY, figW, figH);
        blitFig(graphics, "head", figX, figY, figW, figH, buckets[Z_HEAD]);
        blitFig(graphics, "body", figX, figY, figW, figH, Math.min(buckets[Z_CHEST], buckets[Z_ABDOMEN]));
        blitFig(graphics, "left-arm", figX, figY, figW, figH, buckets[Z_LEFT_ARM]);
        blitFig(graphics, "right-arm", figX, figY, figW, figH, buckets[Z_RIGHT_ARM]);
        blitFig(graphics, "left-leg", figX, figY, figW, figH, buckets[Z_LEFT_LEG]);
        blitFig(graphics, "right-leg", figX, figY, figW, figH, buckets[Z_RIGHT_LEG]);

        // 精确落点（主模式最后5发 / 详情全部）。
        List<HitRecord> dots = displayedHits();
        for (HitRecord r : dots) {
            int cx = figX + Math.round(r.hitX * figW);
            int cy = figY + figH - Math.round(r.hitY * figH);
            int color = dotColor(r);
            // 外圈 + 内点，保证在任何底色上都清晰。
            graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xC0000000);
            graphics.fill(cx - 1, cy - 1, cx + 1, cy + 1, color);
        }

        int leftAnchor = figX - 8;
        int rightAnchor = figX + figW + 8;

        drawHealthBlock(graphics, font, Z_HEAD, leftAnchor, figY + Math.round(figH * 0.02F), barW, false);
        drawHealthBlock(graphics, font, Z_CHEST, rightAnchor, figY + Math.round(figH * 0.16F), barW, true);
        drawHealthBlock(graphics, font, Z_RIGHT_ARM, leftAnchor, figY + Math.round(figH * 0.30F), barW, false);
        drawHealthBlock(graphics, font, Z_LEFT_ARM, rightAnchor, figY + Math.round(figH * 0.30F), barW, true);
        drawHealthBlock(graphics, font, Z_ABDOMEN, leftAnchor, figY + Math.round(figH * 0.46F), barW, false);
        drawHealthBlock(graphics, font, Z_RIGHT_LEG, leftAnchor, figY + Math.round(figH * 0.78F), barW, false);
        drawHealthBlock(graphics, font, Z_LEFT_LEG, rightAnchor, figY + Math.round(figH * 0.78F), barW, true);
    }

    private static int dotColor(HitRecord r) {
        if (r.melee) return C_MELEE;
        if (HitRecord.PENETRATED.equals(r.penetrationType)) return C_ORANGE;
        if (HitRecord.OVER_PENETRATED.equals(r.penetrationType)) return C_RED;
        if (HitRecord.NOT_PENETRATED.equals(r.penetrationType)) return C_GRAY;
        return C_WHITE;
    }

    private void blitFig(GuiGraphics graphics, String part, int x, int y, int w, int h) {
        graphics.blit(new ResourceLocation(FragmentationMod.MOD_ID, "textures/gui/health/" + part + ".png"),
            x, y, 0.0F, 0.0F, w, h, w, h);
    }

    private void blitFig(GuiGraphics graphics, String part, int x, int y, int w, int h, int bucket) {
        graphics.blit(new ResourceLocation(FragmentationMod.MOD_ID,
                "textures/gui/health/" + bucket + "/" + part + ".png"),
            x, y, 0.0F, 0.0F, w, h, w, h);
    }

    private void drawHealthBlock(GuiGraphics graphics, Font font, int zone,
                                 int anchor, int y, int barW, boolean leftAligned) {
        int x = leftAligned ? anchor : anchor - barW;
        graphics.drawString(font, ZONE_NAMES[zone], x, y, C_WHITE);

        int barY = y + 11;
        graphics.fill(x, barY, x + barW, barY + 3, 0x50000000);
        int fillW = Math.round(barW * (remainingHp[zone] / (float) ZONE_MAX_HP[zone]));
        if (fillW > 0) {
            int color = remainingHp[zone] <= 0 ? 0xFFC4070B
                : BUCKET_COLORS[bucketArrayIndex(buckets[zone])];
            graphics.fill(x, barY, x + fillW, barY + 3, color);
        }

        String value = remainingHp[zone] + "/" + ZONE_MAX_HP[zone];
        graphics.drawString(font, value,
            leftAligned ? x : x + barW - font.width(value),
            barY + 5, remainingHp[zone] <= 0 ? C_RED : C_WHITE);
    }

    // ---------- 右栏：淘汰者 ----------

    private void renderEliminatedCard(GuiGraphics graphics, Font font, int x, int colW, int screenH) {
        int y = 90;

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(1.45F, 1.45F, 1.0F);
        graphics.drawString(font, showIncoming ? "淘汰者" : "被淘汰者", 0, 0, C_WHITE);
        graphics.pose().popPose();

        int boxY = y + 28;
        int boxH = 52;
        graphics.fill(x, boxY, x + colW, boxY + boxH, 0x24FFFFFF);
        graphics.fill(x, boxY, x + 3, boxY + boxH, C_ORANGE);

        String name = lastRecord != null && !lastRecord.opponentName.isEmpty()
            ? lastRecord.opponentName : "未知";
        graphics.drawString(font, truncate(name, font, colW - 20), x + 12, boxY + 9, C_WHITE);

        String ammo = "使用： ";
        if (lastRecord != null) {
            ammo += truncate(lastRecord.melee ? lastRecord.bulletId : shortName(lastRecord.bulletId),
                font, colW - 70);
        } else ammo += "未知";
        graphics.drawString(font, ammo, x + 12, boxY + 29, C_DIM);

        int statY = boxY + boxH + 14;
        int rowH = 18;
        List<HitRecord> records = showIncoming ? incoming : outgoing;
        drawStatLine(graphics, font, x, statY, colW, "一分钟枪数", String.valueOf(records.size()));
        statY += rowH;
        drawStatLine(graphics, font, x, statY, colW, "总伤害", String.format("%.1f", totalDamage));
        statY += rowH;
        drawStatLine(graphics, font, x, statY, colW, "被挡下", String.valueOf(blockedCount));
        statY += rowH;
        drawStatLine(graphics, font, x, statY, colW, "有效命中", String.valueOf(records.size() - blockedCount));
    }

    private void drawStatLine(GuiGraphics graphics, Font font, int x, int y, int width,
                              String label, String value) {
        graphics.drawString(font, label, x, y, C_DIM);
        graphics.drawString(font, value, x + width - font.width(value), y, C_WHITE);
    }

    // ---------- 图标 ----------

    private void drawBulletIcon(GuiGraphics g, int x, int y) {
        g.fill(x, y + 26, x + 40, y + 40, 0x33D9B36C);
        g.renderOutline(x, y + 26, 40, 14, C_GOLD);
        g.fill(x + 16, y + 8, x + 24, y + 30, C_GOLD);
        g.fill(x + 14, y + 30, x + 26, y + 38, C_ORANGE);
        g.fill(x + 18, y + 4, x + 22, y + 8, C_GOLD);
    }

    private void drawHelmetIcon(GuiGraphics g, int x, int y) {
        g.fill(x + 6, y + 8, x + 34, y + 26, 0xFFD8DDE2);
        g.fill(x + 4, y + 24, x + 36, y + 32, 0xFFB8BEC6);
        g.fill(x + 10, y + 2, x + 30, y + 10, 0xFFEEF2F5);
        g.fill(x + 10, y + 30, x + 30, y + 38, 0x669FC8E8);
        g.renderOutline(x + 10, y + 30, 20, 8, 0xFF9FC8E8);
    }

    private void drawVestIcon(GuiGraphics g, int x, int y) {
        g.fill(x + 8, y + 6, x + 32, y + 36, 0xFF8FA45C);
        g.fill(x + 4, y + 10, x + 10, y + 30, 0xFF7C9050);
        g.fill(x + 30, y + 10, x + 36, y + 30, 0xFF7C9050);
        g.renderOutline(x + 8, y + 6, 24, 30, 0xFFB7C98A);
        g.fill(x + 12, y + 14, x + 28, y + 18, 0x55000000);
        g.fill(x + 12, y + 24, x + 28, y + 28, 0x55000000);
    }

    // ---------- 文本 ----------

    private static String statusName(String type) {
        if (HitRecord.PENETRATED.equals(type)) return "穿透";
        if (HitRecord.OVER_PENETRATED.equals(type)) return "过穿";
        if (HitRecord.NOT_PENETRATED.equals(type)) return "未穿透";
        if (HitRecord.MELEE.equals(type)) return "近战";
        return type != null ? type : "未知";
    }

    private static int statusColor(String type) {
        if (HitRecord.PENETRATED.equals(type)) return C_ORANGE;
        if (HitRecord.OVER_PENETRATED.equals(type)) return C_RED;
        if (HitRecord.NOT_PENETRATED.equals(type)) return C_GRAY;
        if (HitRecord.MELEE.equals(type)) return C_MELEE;
        return C_WHITE;
    }

    private static String shortName(String id) {
        if (id == null || id.isEmpty()) return "未知";
        int i = id.lastIndexOf(':');
        return (i >= 0 ? id.substring(i + 1) : id).replace('_', ' ');
    }

    private String truncate(String text, Font font, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int limit = maxWidth - font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(sb.toString() + c) > limit) break;
            sb.append(c);
        }
        return sb + ellipsis;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ================= UI 缩放持久化 =================

    private static Path scaleFile() {
        return FMLPaths.CONFIGDIR.get().resolve("fragmentation").resolve("deathreport_ui_scale.txt");
    }

    private static void loadScale() {
        try {
            Path f = scaleFile();
            if (Files.exists(f)) {
                String s = Files.readString(f).trim();
                float v = Float.parseFloat(s);
                if (v >= SCALE_MIN && v <= SCALE_MAX) {
                    uiScale = v;
                }
            }
        } catch (Throwable t) {
            // 读取失败保持默认值
        }
    }

    private static void saveScale() {
        try {
            Path f = scaleFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, String.valueOf(uiScale));
        } catch (Throwable t) {
            // 忽略持久化失败
        }
    }
}
