package com.fragmentation.client.screen;

import com.fragmentation.FragmentationMod;
import com.fragmentation.combat.HitRecord;
import com.fragmentation.network.SettlementPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 对局结算界面（仿暗区突围）。
 *
 * 主页：行动俯视地图（真实地表色块）+ 路线连线 + 击杀标注；
 *      行动时间、淘汰玩家数、AI 数、总淘汰数。
 * 「详细信息」：淘汰列表（最早 → 最晚），点击条目查看「淘汰详情」：
 *      我方对其命中 / 其对我方命中 两张人体图与命中清单（双边受击）。
 */
public class SettlementScreen extends Screen {

    private static final int C_WHITE = 0xFFFFFFFF;
    private static final int C_DIM = 0xFF9A9AA2;
    private static final int C_LABEL = 0xFF6E6E78;
    private static final int C_GOLD = 0xFFD9B36C;
    private static final int C_RED = 0xFFE85D5D;
    private static final int C_ORANGE = 0xFFFFC23D;
    private static final int C_GREEN = 0xFF7FD982;
    private static final int C_MELEE = 0xFF8FE3A0;
    private static final int C_GRAY = 0xFFB0B0B0;
    private static final int[] BUCKET_COLORS = {0xFF281D1D, 0xFFC4070B, 0xFFF93438, 0xFFFC9D9F, 0xFFFFFFFF};

    private static final int ZONE_COUNT = 7;
    private static final int Z_HEAD = 0;
    private static final int Z_CHEST = 1;
    private static final int Z_ABDOMEN = 2;
    private static final int Z_LEFT_ARM = 3;
    private static final int Z_RIGHT_ARM = 4;
    private static final int Z_LEFT_LEG = 5;
    private static final int Z_RIGHT_LEG = 6;
    private static final int[] ZONE_MAX_HP = {40, 85, 70, 60, 60, 65, 65};

    private enum View { MAIN, KILL_LIST, DETAIL }

    private final SettlementPacket data;
    private View view = View.MAIN;
    private int selectedKill = -1;

    // 按钮区域
    private int btnX;
    private int btnY;
    private static final int BTN_W = 110;
    private static final int BTN_H = 18;

    // 地图几何
    private int mapX;
    private int mapY;
    private int pxPerCell;

    // 列表区域
    private int listX;
    private int listW;
    private int listTop;
    private int listBottom;
    private float listScroll;
    private float detailScroll;

    public SettlementScreen(SettlementPacket data) {
        super(Component.literal("对局结算"));
        this.data = data;
    }

    public static void open(SettlementPacket packet) {
        Minecraft.getInstance().setScreen(new SettlementScreen(packet));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ================= 输入 =================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= btnX && mouseX <= btnX + BTN_W
                && mouseY >= btnY && mouseY <= btnY + BTN_H) {
            if (view == View.MAIN) {
                view = View.KILL_LIST;
            } else if (view == View.KILL_LIST) {
                view = View.MAIN;
            } else {
                view = View.KILL_LIST;
            }
            return true;
        }

        if (view == View.KILL_LIST
                && mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listTop && mouseY <= listBottom) {
            int rowH = 22;
            int idx = (int) ((mouseY - listTop + listScroll) / rowH);
            if (idx >= 0 && idx < data.kills.size()) {
                selectedKill = idx;
                view = View.DETAIL;
                detailScroll = 0.0F;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (view == View.KILL_LIST
                && mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listTop && mouseY <= listBottom) {
            listScroll = clamp(listScroll + (float) delta * 3.0F, 0.0F, killListMaxScroll());
            return true;
        }
        if (view == View.DETAIL) {
            detailScroll = clamp(detailScroll + (float) delta * 3.0F, 0.0F, detailMaxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private float killListMaxScroll() {
        int total = data.kills.size() * 22;
        return Math.max(0.0F, total - (listBottom - listTop));
    }

    private float detailMaxScroll() {
        if (selectedKill < 0) return 0.0F;
        SettlementPacket.Engagement e = data.engagements.get(selectedKill);
        int total = Math.max(e.dealt.size(), e.taken.size()) * (font.lineHeight + 5);
        return Math.max(0.0F, total - (listBottom - listTop));
    }

    // ================= 渲染 =================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoverMouseX = mouseX;
        hoverMouseY = mouseY;
        graphics.fillGradient(0, 0, width, height, 0xE60B0B0E, 0xF2151519);

        btnX = 20;
        btnY = height - 44;

        if (view == View.MAIN) {
            renderMain(graphics);
            drawButton(graphics, "详细信息");
        } else if (view == View.KILL_LIST) {
            renderKillList(graphics);
            drawButton(graphics, "返回");
        } else {
            renderDetail(graphics);
            drawButton(graphics, "返回列表");
        }
    }

    // ---------- 主页 ----------

    private void renderMain(GuiGraphics g) {
        Font font = this.font;
        int margin = 20;

        g.pose().pushPose();
        g.pose().translate(margin, 20.0F, 0.0F);
        g.pose().scale(1.5F, 1.5F, 1.0F);
        g.drawString(font, "对局结算", 0, 0, C_WHITE);
        g.pose().popPose();

        // 统计面板（左）
        int panelY = 64;
        int panelW = Math.min(220, Math.round(width * 0.2F));
        g.fill(margin, panelY, margin + panelW, panelY + 150, 0x1FFFFFFF);
        g.renderOutline(margin, panelY, panelW, 150, 0x2BFFFFFF);

        int playerKills = 0;
        int aiKills = 0;
        for (SettlementPacket.KillInfo k : data.kills) {
            if (k.isPlayer) playerKills++; else aiKills++;
        }

        int ry = panelY + 12;
        drawStatRow(g, font, margin + 12, ry, panelW - 24, "行动时间", formatTime(data.durationTicks));
        ry += 26;
        drawStatRow(g, font, margin + 12, ry, panelW - 24, "淘汰玩家", String.valueOf(playerKills));
        ry += 26;
        drawStatRow(g, font, margin + 12, ry, panelW - 24, "淘汰 AI", String.valueOf(aiKills));
        ry += 26;
        drawStatRow(g, font, margin + 12, ry, panelW - 24, "总淘汰", String.valueOf(data.kills.size()), C_GOLD);

        // 地图区域（右侧剩余空间）
        int areaX = margin + panelW + 24;
        int areaY = panelY;
        int areaW = width - margin - areaX;
        int areaH = height - 44 - 20 - areaY;
        renderMap(g, areaX, areaY, areaW, areaH);
    }

    private void renderMap(GuiGraphics g, int areaX, int areaY, int areaW, int areaH) {
        // 地图外框
        g.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0xFF141418);
        g.renderOutline(areaX, areaY, areaW, areaH, 0x44FFFFFF);

        pxPerCell = (int) Math.min(Math.floor((double) areaW / Math.max(1, data.mapWidth)),
                                   Math.floor((double) areaH / Math.max(1, data.mapDepth)));
        pxPerCell = Math.max(2, Math.min(28, pxPerCell));

        int drawW = data.mapWidth * pxPerCell;
        int drawH = data.mapDepth * pxPerCell;
        mapX = areaX + (areaW - drawW) / 2;
        mapY = areaY + (areaH - drawH) / 2;

        String mapDim = resolveMapDim();

        // 色块
        for (int gz = 0; gz < data.mapDepth; gz++) {
            for (int gx = 0; gx < data.mapWidth; gx++) {
                int color = data.colors[gz * data.mapWidth + gx];
                int x0 = mapX + gx * pxPerCell;
                int y0 = mapY + gz * pxPerCell;
                if ((color >>> 24) == 0) {
                    g.fill(x0, y0, x0 + pxPerCell, y0 + pxPerCell, 0xFF1C1C22);
                } else {
                    g.fill(x0, y0, x0 + pxPerCell, y0 + pxPerCell, color);
                }
            }
        }

        // 路线连线（只连地图维度内的点）
        List<int[]> screenPts = new ArrayList<>();
        for (SettlementPacket.RoutePoint r : data.route) {
            if (!r.dim.equals(mapDim)) {
                screenPts.add(null);
                continue;
            }
            double gx = (r.x - data.originX) / (double) data.step;
            double gz = (r.z - data.originZ) / (double) data.step;
            int sx = mapX + (int) Math.round(gx * pxPerCell);
            int sz = mapY + (int) Math.round(gz * pxPerCell);
            screenPts.add(new int[]{sx, sz});
        }

        for (int i = 1; i < screenPts.size(); i++) {
            int[] a = screenPts.get(i - 1);
            int[] b = screenPts.get(i);
            if (a == null || b == null) continue;
            drawLine(g, a[0], a[1], b[0], b[1], 0xFFF2E8B0);
        }

        // 起点 / 终点
        if (!screenPts.isEmpty()) {
            int[] start = firstNonNull(screenPts);
            int[] end = lastNonNull(screenPts);
            if (start != null) drawMarker(g, start[0], start[1], C_GREEN);
            if (end != null && end != start) drawMarker(g, end[0], end[1], 0xFFE05555);
        }

        // 击杀点（玩家=金色方框，AI=红色小圆）
        for (SettlementPacket.KillInfo k : data.kills) {
            if (!k.dim.equals(mapDim)) continue;
            double gx = (k.x - data.originX) / (double) data.step;
            double gz = (k.z - data.originZ) / (double) data.step;
            int sx = mapX + (int) Math.round(gx * pxPerCell);
            int sz = mapY + (int) Math.round(gz * pxPerCell);
            if (k.isPlayer) {
                g.fill(sx - 3, sz - 3, sx + 3, sz + 3, 0xC0000000);
                g.fill(sx - 2, sz - 2, sx + 2, sz + 2, C_GOLD);
            } else {
                g.fill(sx - 2, sz - 2, sx + 2, sz + 2, 0xC0000000);
                g.fill(sx - 1, sz - 1, sx + 1, sz + 1, C_RED);
            }
        }

        // 图例
        int legendY = areaY + areaH - 16;
        g.drawString(font, "■ 起点", mapX + 6, legendY, 0xFF000000);
        g.drawString(font, "■ 终点", mapX + 52, legendY, 0xFF000000);
        g.drawString(font, "■ 玩家击杀", mapX + 98, legendY, 0xFF000000);
        g.drawString(font, "■ AI击杀", mapX + 182, legendY, 0xFF000000);
    }

    private String resolveMapDim() {
        if (!data.route.isEmpty()) {
            return data.route.get(data.route.size() - 1).dim;
        }
        for (SettlementPacket.KillInfo k : data.kills) {
            return k.dim;
        }
        return "minecraft:overworld";
    }

    // ---------- 淘汰列表 ----------

    private void renderKillList(GuiGraphics g) {
        Font font = this.font;
        int margin = 20;

        g.pose().pushPose();
        g.pose().translate(margin, 20.0F, 0.0F);
        g.pose().scale(1.5F, 1.5F, 1.0F);
        g.drawString(font, "淘汰列表", 0, 0, C_WHITE);
        g.pose().popPose();
        g.drawString(font, "共 " + data.kills.size() + " 个目标（从上往下：最早 → 最晚）",
            margin, 52, C_DIM);

        listX = margin;
        listW = width - margin * 2;
        listTop = 70;
        listBottom = height - 64;

        if (data.kills.isEmpty()) {
            g.drawString(font, "本次行动没有淘汰目标", listX, listTop + 4, C_DIM);
            return;
        }

        int rowH = 22;
        g.enableScissor(listX, listTop, listX + listW, listBottom);
        int y = listTop - Math.round(listScroll);

        for (int i = 0; i < data.kills.size(); i++) {
            SettlementPacket.KillInfo k = data.kills.get(i);
            if (y + rowH >= listTop && y <= listBottom) {
                boolean hovered = lastMouseIn(listX, listW, y, rowH);
                g.fill(listX, y, listX + listW, y + rowH - 2,
                    hovered ? 0x33FFFFFF : (i % 2 == 0 ? 0x14FFFFFF : 0x0AFFFFFF));

                String tag = k.isPlayer ? "玩家" : "AI";
                g.drawString(font, tag, listX + 10, y + 5, k.isPlayer ? C_GOLD : C_RED);
                g.drawString(font, (i + 1) + ".", listX + 42, y + 5, C_LABEL);
                g.drawString(font, k.victimName, listX + 62, y + 5, C_WHITE);

                String at = "+" + (k.tick - data.startTick) / 20 + "s";
                g.drawString(font, at, listX + listW - font.width(at) - 10, y + 5, C_DIM);

                String detail = "淘汰详情 >";
                g.drawString(font, detail, listX + listW - font.width(detail) - font.width(at) - 28,
                    y + 5, C_ORANGE);
            }
            y += rowH;
        }
        g.disableScissor();
    }

    private int hoverMouseX;
    private int hoverMouseY;

    private boolean lastMouseIn(int x, int w, int y, int h) {
        return hoverMouseX >= x && hoverMouseX <= x + w && hoverMouseY >= y && hoverMouseY <= y + h;
    }

    // ---------- 淘汰详情（双边） ----------

    private void renderDetail(GuiGraphics g) {
        Font font = this.font;
        int margin = 20;

        if (selectedKill < 0 || selectedKill >= data.kills.size()) {
            view = View.KILL_LIST;
            return;
        }
        SettlementPacket.KillInfo k = data.kills.get(selectedKill);
        SettlementPacket.Engagement e = data.engagements.get(selectedKill);

        g.pose().pushPose();
        g.pose().translate(margin, 20.0F, 0.0F);
        g.pose().scale(1.5F, 1.5F, 1.0F);
        g.drawString(font, "淘汰详情：", 0, 0, C_WHITE);
        g.pose().popPose();
        g.drawString(font, k.victimName + (k.isPlayer ? "（玩家）" : "（AI）"),
            margin + Math.round(font.width("淘汰详情：") * 1.5F) + 8, 28, C_GOLD);

        int colW = (width - margin * 3) / 2;
        int figTop = 52;
        int figH = Math.min(250, Math.round((height - 200) * 0.55F));
        figH = Math.max(150, figH);

        // 左：我对其命中；右：其对我方命中
        drawEngagementColumn(g, margin, figTop, colW, figH,
            "我对其命中（" + e.dealt.size() + " 发）", e.dealt, true);
        drawEngagementColumn(g, margin * 2 + colW, figTop, colW, figH,
            "其对我方命中（" + e.taken.size() + " 发）", e.taken, false);

        // 命中清单（双列）
        int listsTop = figTop + figH + 14;
        int listsBottom = height - 64;
        drawHitRows(g, margin, listsTop, colW, listsBottom, e.dealt);
        drawHitRows(g, margin * 2 + colW, listsTop, colW, listsBottom, e.taken);
    }

    private void drawEngagementColumn(GuiGraphics g, int x, int y, int colW, int figH,
                                      String title, List<HitRecord> records, boolean mine) {
        Font font = this.font;
        g.drawString(font, title, x, y, C_WHITE);

        int figY0 = y + 14;
        int figH0 = figH - 14;
        int figW = Math.round(figH0 * 1390.0F / 1800.0F);
        figW = Math.min(figW, colW - 10);
        int figHReal = Math.round(figW * 1800.0F / 1390.0F);
        int figX0 = x + (colW - figW) / 2;

        // 伤势分桶
        float[] dmg = new float[ZONE_COUNT];
        for (HitRecord r : records) {
            dmg[zoneIndex(r.zone)] += r.finalDamage;
        }
        int[] buckets = new int[ZONE_COUNT];
        for (int z = 0; z < ZONE_COUNT; z++) {
            float ratio = clamp((ZONE_MAX_HP[z] - dmg[z]) / ZONE_MAX_HP[z], 0.0F, 1.0F);
            buckets[z] = bucket(ratio);
        }

        blitFig(g, "base", figX0, figY0, figW, figHReal);
        blitFig(g, "head", figX0, figY0, figW, figHReal, buckets[Z_HEAD]);
        blitFig(g, "body", figX0, figY0, figW, figHReal,
            Math.min(buckets[Z_CHEST], buckets[Z_ABDOMEN]));
        blitFig(g, "left-arm", figX0, figY0, figW, figHReal, buckets[Z_LEFT_ARM]);
        blitFig(g, "right-arm", figX0, figY0, figW, figHReal, buckets[Z_RIGHT_ARM]);
        blitFig(g, "left-leg", figX0, figY0, figW, figHReal, buckets[Z_LEFT_LEG]);
        blitFig(g, "right-leg", figX0, figY0, figW, figHReal, buckets[Z_RIGHT_LEG]);

        for (HitRecord r : records) {
            int cx = figX0 + Math.round(r.hitX * figW);
            int cy = figY0 + figHReal - Math.round(r.hitY * figHReal);
            int color = dotColor(r);
            g.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xC0000000);
            g.fill(cx - 1, cy - 1, cx + 1, cy + 1, color);
        }
    }

    private void drawHitRows(GuiGraphics g, int x, int top, int colW, int bottom,
                             List<HitRecord> records) {
        Font font = this.font;
        g.fill(x, top - 4, x + colW, top - 3, 0x33FFFFFF);

        int rowH = font.lineHeight + 5;
        g.enableScissor(x, top, x + colW, bottom);
        int y = top - Math.round(detailScroll);
        for (HitRecord r : records) {
            if (y + rowH >= top && y <= bottom) {
                String zone = zoneName(zoneIndex(r.zone));
                g.drawString(font, zone, x, y, C_WHITE);
                String name = r.melee ? r.bulletId : shortName(r.bulletId);
                name = truncate(name, font, colW - 80);
                g.drawString(font, name, x + 30, y, C_DIM);
                String dmg = String.format("%.0f", r.finalDamage);
                g.drawString(font, dmg, x + colW - font.width(dmg), y, C_GOLD);
            }
            y += rowH;
        }
        g.disableScissor();
    }

    // ---------- 通用绘制 ----------

    private void drawButton(GuiGraphics g, String label) {
        g.fill(btnX, btnY, btnX + BTN_W, btnY + BTN_H, 0x55D9B36C);
        g.renderOutline(btnX, btnY, BTN_W, BTN_H, C_GOLD);
        g.drawCenteredString(font, Component.literal(label), btnX + BTN_W / 2, btnY + 5, C_WHITE);
    }

    private void drawStatRow(GuiGraphics g, Font font, int x, int y, int width,
                             String label, String value) {
        drawStatRow(g, font, x, y, width, label, value, C_WHITE);
    }

    private void drawStatRow(GuiGraphics g, Font font, int x, int y, int width,
                             String label, String value, int valueColor) {
        g.drawString(font, label, x, y, C_DIM);
        g.drawString(font, value, x + width - font.width(value), y, valueColor);
    }

    private void blitFig(GuiGraphics g, String part, int x, int y, int w, int h) {
        g.blit(new ResourceLocation(FragmentationMod.MOD_ID, "textures/gui/health/" + part + ".png"),
            x, y, 0.0F, 0.0F, w, h, w, h);
    }

    private void blitFig(GuiGraphics g, String part, int x, int y, int w, int h, int bucket) {
        g.blit(new ResourceLocation(FragmentationMod.MOD_ID,
                "textures/gui/health/" + bucket + "/" + part + ".png"),
            x, y, 0.0F, 0.0F, w, h, w, h);
    }

    /** Bresenham 粗线。 */
    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            g.fill(x0 - 1, y0 - 1, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private static void drawMarker(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 3, y - 3, x + 3, y + 3, 0xC0000000);
        g.fill(x - 2, y - 2, x + 2, y + 2, color);
    }

    private static int[] firstNonNull(List<int[]> list) {
        for (int[] p : list) if (p != null) return p;
        return null;
    }

    private static int[] lastNonNull(List<int[]> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) != null) return list.get(i);
        }
        return null;
    }

    // ---------- 工具 ----------

    private static int dotColor(HitRecord r) {
        if (r.melee) return C_MELEE;
        if (HitRecord.PENETRATED.equals(r.penetrationType)) return C_ORANGE;
        if (HitRecord.OVER_PENETRATED.equals(r.penetrationType)) return C_RED;
        if (HitRecord.NOT_PENETRATED.equals(r.penetrationType)) return C_GRAY;
        return C_WHITE;
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

    private static String zoneName(int z) {
        return switch (z) {
            case Z_HEAD -> "头部";
            case Z_CHEST -> "胸部";
            case Z_ABDOMEN -> "腹部";
            case Z_LEFT_ARM -> "左臂";
            case Z_RIGHT_ARM -> "右臂";
            case Z_LEFT_LEG -> "左腿";
            case Z_RIGHT_LEG -> "右腿";
            default -> "";
        };
    }

    private static int bucket(float ratio) {
        if (ratio >= 0.90F) return 100;
        if (ratio >= 0.65F) return 80;
        if (ratio >= 0.35F) return 50;
        if (ratio >= 0.10F) return 20;
        return 0;
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

    private static String formatTime(long ticks) {
        long totalSeconds = Math.max(0L, ticks / 20L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
