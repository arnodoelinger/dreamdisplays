package com.dreamdisplays.client.ui.widgets;

import com.dreamdisplays.Initializer;
import com.dreamdisplays.ytdlp.Thumbnails;
import com.dreamdisplays.ytdlp.YouTubeWeb;
import com.dreamdisplays.ytdlp.YtDlp;
import com.dreamdisplays.ytdlp.YtVideoInfo;
import me.inotsleep.utils.logging.LoggingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@NullMarked
public final class SuggestionsPanelWidget extends AbstractWidget {

    private static final int RESULT_LIMIT = 12;
    private static final int HEADER_H = 14;
    private static final int CARD_GAP = 6;
    private static final int CARD_W = 152;
    private static final int CARD_TEXT_H = 32;
    private static final int THUMB_H = 86;
    private static final int CARD_H = THUMB_H + CARD_TEXT_H;
    private static final int PANEL_BG = 0x9F0F0F0F;
    private static final int PANEL_BORDER = 0xFF7A7A7A;
    private static final int CARD_BG = 0x602A2A2A;
    private static final int CARD_HOVER_BG = 0x90707070;
    private static final int SEARCH_H = 22;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "DD-Suggestions");
        t.setDaemon(true);
        return t;
    });
    private static final int ACTION_W = SEARCH_H;
    private static final int ACTION_GAP = 4;
    private final EditBox searchBox;
    private final ButtonWidget clearButtonWidget;
    private final ButtonWidget searchActionButtonWidget;
    private final Consumer<YtVideoInfo> onPick;
    private final List<YtVideoInfo> cards = new ArrayList<>();
    private final AtomicInteger requestSeq = new AtomicInteger();
    private @Nullable String currentVideoId;
    private @Nullable String statusMessage = null;
    private long loadStartedAtMs = 0L;
    private int scrollOffset = 0;
    private int hoveredCard = -1;
    private boolean vertical = false;
    private boolean compactCards = false;
    private int verticalCardW = CARD_W;
    public void setVertical(boolean v) {
        this.vertical = v;
    }

    public void setCompactCards(boolean c) {
        this.compactCards = c;
    }

    public SuggestionsPanelWidget(int x, int y, int width, int height, Consumer<YtVideoInfo> onPick) {
        super(x, y, width, height, Component.translatable("dreamdisplays.button.suggestions"));
        this.onPick = onPick;
        Font f = Minecraft.getInstance().font;
        this.searchBox = new EditBox(f, x + 10, y + searchY(),
                searchBoxWidth(width), SEARCH_H,
                Component.translatable("dreamdisplays.suggestions.search"));
        this.searchBox.setHint(Component.translatable("dreamdisplays.suggestions.search"));
        this.searchBox.setMaxLength(200);
        this.clearButtonWidget = new ButtonWidget(0, 0, ACTION_W, SEARCH_H, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "cross"), 4) {
            @Override
            public void onPress() {
                searchBox.setValue("");
                searchBox.setFocused(true);
            }
        };
        this.searchActionButtonWidget = new ButtonWidget(0, 0, ACTION_W, SEARCH_H, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "search"), 4) {
            @Override
            public void onPress() {
                runSearch();
            }
        };
    }

    private static java.util.List<String> wrap(Font f, String s, int maxW, int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String[] words = s.split("\\s+");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            String trial = cur.isEmpty() ? word : cur + " " + word;
            if (f.width(trial) <= maxW) {
                cur.setLength(0);
                cur.append(trial);
            } else {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    if (out.size() == maxLines) break;
                    cur.setLength(0);
                }
                if (f.width(word) > maxW) {
                    out.add(trim(f, word, maxW));
                    if (out.size() == maxLines) break;
                } else {
                    cur.append(word);
                }
            }
        }
        if (!cur.isEmpty() && out.size() < maxLines) out.add(cur.toString());
        if (out.isEmpty()) out.add("");
        // Ellipsize the last line if there's still text we couldn't fit
        return out;
    }

    private static String trim(Font f, String s, int maxW) {
        if (f.width(s) <= maxW) return s;
        String dots = "...";
        int dotsW = f.width(dots);
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (f.width(sb.toString() + c) + dotsW > maxW) break;
            sb.append(c);
        }
        return sb + dots;
    }

    private int searchY() {
        return 10 + HEADER_H + 6;
    }

    private int searchBoxWidth(int panelW) {
        return panelW - 20 - (ACTION_W + ACTION_GAP) * 2;
    }

    private int clearButtonX() {
        return getX() + getWidth() - 10 - ACTION_W * 2 - ACTION_GAP;
    }

    private int searchButtonX() {
        return getX() + getWidth() - 10 - ACTION_W;
    }

    private int actionRowY() {
        return searchBox.getY();
    }

    public void setRelatedTo(@Nullable String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            currentVideoId = null;
            cards.clear();
            statusMessage = null;
            return;
        }
        if (videoId.equals(currentVideoId) && !cards.isEmpty()) return;
        currentVideoId = videoId;
        loadRelated(videoId);
    }

    public void runSearch() {
        String q = searchBox.getValue().trim();
        if (q.isEmpty()) {
            if (currentVideoId != null) loadRelated(currentVideoId);
            return;
        }
        String maybeId = (q.startsWith("http") || q.contains("youtube.com") || q.contains("youtu.be"))
                ? YtDlp.extractVideoId(q)
                : null;
        if (maybeId != null) {
            startLoad();
            int seq2 = requestSeq.incrementAndGet();
            String idForLambda = maybeId;
            EXECUTOR.submit(() -> {
                try {
                    YtVideoInfo meta = YouTubeWeb.metadata(idForLambda);
                    if (meta != null) {
                        publish(seq2, java.util.List.of(meta), null);
                    } else {
                        publish(seq2, java.util.List.of(new YtVideoInfo(
                                idForLambda, "https://youtu.be/" + idForLambda,
                                null, null, null)), null);
                    }
                } catch (Exception e) {
                    LoggingManager.warn("[Suggestions] URL meta fetch failed: " + e.getMessage());
                    publish(seq2, java.util.List.of(new YtVideoInfo(
                            idForLambda, "https://youtu.be/" + idForLambda,
                            null, null, null)), null);
                }
            });
            return;
        }
        startLoad();
        int seq = requestSeq.incrementAndGet();
        long tEnq = System.nanoTime();
        // LoggingManager.info("[Suggestions] search submit '" + q + "' seq=" + seq);
        EXECUTOR.submit(() -> {
            long tStart = System.nanoTime();
            // LoggingManager.info(String.format(
            //        "[Suggestions] search start '%s' (queue wait %d ms)",
            //        q, (tStart - tEnq) / 1_000_000L));
            try {
                List<YtVideoInfo> r = YtDlp.search(q, RESULT_LIMIT);
                long tDone = System.nanoTime();
                // LoggingManager.info(String.format(
                //        "[Suggestions] search done '%s' -> %d in %d ms",
                //        q, r.size(), (tDone - tStart) / 1_000_000L));
                publish(seq, r, null);
            } catch (Exception e) {
                LoggingManager.warn("[Suggestions] search failed '" + q + "': " + e.getMessage());
                publish(seq, null, "dreamdisplays.suggestions.error");
            }
        });
    }

    private void loadRelated(String videoId) {
        startLoad();
        int seq = requestSeq.incrementAndGet();
        long tEnq = System.nanoTime();
        // LoggingManager.info("[Suggestions] related submit " + videoId + " seq=" + seq);
        EXECUTOR.submit(() -> {
            long tStart = System.nanoTime();
            // LoggingManager.info(String.format(
            //        "[Suggestions] related start %s (queue wait %d ms)",
            //        videoId, (tStart - tEnq) / 1_000_000L));
            try {
                List<YtVideoInfo> r = YtDlp.related(videoId, RESULT_LIMIT);
                long tDone = System.nanoTime();
                // LoggingManager.info(String.format(
                //        "[Suggestions] related done %s -> %d in %d ms",
                //        videoId, r.size(), (tDone - tStart) / 1_000_000L));
                publish(seq, r, null);
            } catch (Exception e) {
                LoggingManager.warn("[Suggestions] related failed " + videoId + ": " + e.getMessage());
                publish(seq, null, "dreamdisplays.suggestions.error");
            }
        });
    }

    private void startLoad() {
        statusMessage = "dreamdisplays.suggestions.loading";
        loadStartedAtMs = System.currentTimeMillis();
        cards.clear();
        scrollOffset = 0;
    }

    private void publish(int seq, @Nullable List<YtVideoInfo> results, @Nullable String error) {
        Minecraft.getInstance().execute(() -> {
            if (seq != requestSeq.get()) return;
            cards.clear();
            scrollOffset = 0;
            if (error != null) {
                statusMessage = error;
                return;
            }
            if (results == null || results.isEmpty()) {
                statusMessage = "dreamdisplays.suggestions.empty";
                return;
            }
            statusMessage = null;
            cards.addAll(results.subList(0, Math.min(results.size(), RESULT_LIMIT)));
            int n = Math.min(cards.size(), RESULT_LIMIT);
            for (int i = 0; i < n; i++) {
                YtVideoInfo info = cards.get(i);
                Thumbnails.request(info.getId(), info.getThumbnailUrl());
            }
        });
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        searchBox.setX(x + 10);
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        searchBox.setY(y + searchY());
    }

    @Override
    public void setWidth(int w) {
        super.setWidth(w);
        searchBox.setWidth(searchBoxWidth(w));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float dt) {
        // TODO: fix
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PANEL_BG);
        g.fill(getX(), getY(), getX() + getWidth(), getY() + 1, PANEL_BORDER);
        g.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), PANEL_BORDER);
        g.fill(getX(), getY(), getX() + 1, getY() + getHeight(), PANEL_BORDER);
        g.fill(getX() + getWidth() - 1, getY(), getX() + getWidth(), getY() + getHeight(), PANEL_BORDER);

        Font f = Minecraft.getInstance().font;
        g.drawString(f, getMessage(), getX() + 10, getY() + 10, 0xFFFFFFFF, false);

        searchBox.render(g, mouseX, mouseY, dt);

        clearButtonWidget.setX(clearButtonX());
        clearButtonWidget.setY(actionRowY());
        clearButtonWidget.render(g, mouseX, mouseY, dt);

        searchActionButtonWidget.setX(searchButtonX());
        searchActionButtonWidget.setY(actionRowY());
        searchActionButtonWidget.render(g, mouseX, mouseY, dt);

        int stripTop = searchBox.getY() + SEARCH_H + 8;
        int stripBottom = getY() + getHeight() - 10;
        int stripH = stripBottom - stripTop;
        if (stripH < 40) return;

        if (statusMessage != null) {
            String base = Component.translatable(statusMessage).getString();
            String msg;
            if ("dreamdisplays.suggestions.loading".equals(statusMessage)) {
                long elapsed = Math.max(0, (System.currentTimeMillis() - loadStartedAtMs) / 1000L);
                msg = base.replaceAll("\\.+$", "") + " • " + elapsed + "s";
            } else {
                msg = base;
            }
            g.drawString(f, msg, getX() + 10, stripTop + 6, 0xFFAAAAAA, false);
            return;
        }

        int stripLeft = getX() + 10;
        int stripRight = getX() + getWidth() - 10;
        int viewportW = stripRight - stripLeft;
        lastStripH = stripH;

        if (vertical) {
            verticalCardW = Math.max(CARD_W, viewportW);
            int vCardH = compactCards ? (THUMB_H + 4) : CARD_H;
            int vThumbH = Math.max(THUMB_H, (int) (verticalCardW * 180.0 / 320.0));
            if (!compactCards) vCardH = vThumbH + CARD_TEXT_H;

            int contentH = cards.size() * (vCardH + CARD_GAP) - CARD_GAP;
            int viewportH = stripBottom - stripTop;
            int maxOff = Math.max(0, contentH - viewportH);
            scrollOffset = Math.max(0, Math.min(maxOff, scrollOffset));

            g.enableScissor(stripLeft, stripTop, stripRight, stripBottom);
            hoveredCard = -1;
            int cy = stripTop - scrollOffset;
            for (int i = 0; i < cards.size(); i++) {
                YtVideoInfo info = cards.get(i);
                int cardTop = cy;
                int cardBottom = cy + vCardH;
                if (cardBottom >= stripTop && cardTop <= stripBottom) {
                    boolean hover = mouseX >= stripLeft && mouseX < stripLeft + verticalCardW
                            && mouseY >= cardTop && mouseY < cardBottom
                            && mouseY >= stripTop && mouseY < stripBottom;
                    if (hover) hoveredCard = i;
                    renderCardSized(g, f, info, stripLeft, cardTop, verticalCardW, vThumbH, vCardH, hover);
                    if (Thumbnails.get(info.getId()) == null) {
                        Thumbnails.request(info.getId(), info.getThumbnailUrl());
                    }
                }
                cy += vCardH + CARD_GAP;
            }
            g.disableScissor();
            return;
        }

        int hCardH = dynCardH();
        int hThumbH = dynThumbH();
        int hCardW = dynCardW();
        int rowY = stripTop + Math.max(0, (stripBottom - stripTop - hCardH) / 2);

        int contentW = cards.size() * (hCardW + CARD_GAP) - CARD_GAP;
        int maxOff = Math.max(0, contentW - viewportW);
        scrollOffset = Math.max(0, Math.min(maxOff, scrollOffset));

        g.enableScissor(stripLeft, stripTop, stripRight, stripBottom);
        hoveredCard = -1;
        int cx = stripLeft - scrollOffset;
        for (int i = 0; i < cards.size(); i++) {
            YtVideoInfo info = cards.get(i);
            int cardLeft = cx;
            int cardRight = cx + hCardW;
            if (cardRight >= stripLeft && cardLeft <= stripRight) {
                boolean hover = mouseX >= cardLeft && mouseX < cardRight
                        && mouseY >= rowY && mouseY < rowY + hCardH
                        && mouseX >= stripLeft && mouseX < stripRight;
                if (hover) hoveredCard = i;
                renderCardSized(g, f, info, cardLeft, rowY, hCardW, hThumbH, hCardH, hover);
                if (Thumbnails.get(info.getId()) == null) {
                    Thumbnails.request(info.getId(), info.getThumbnailUrl());
                }
            }
            cx += hCardW + CARD_GAP;
        }
        g.disableScissor();

        if (contentW > viewportW) {
            int barY = stripBottom + 1;
            g.fill(stripLeft, barY, stripRight, barY + 2, 0xFF202020);
            int barW = Math.max(20, (int) ((float) viewportW / contentW * viewportW));
            int barX = stripLeft + (int) ((float) scrollOffset / maxOff * (viewportW - barW));
            g.fill(barX, barY, barX + barW, barY + 2, 0xFF808080);
        }
    }

    private int lastStripH = CARD_H;

    private int dynThumbH() {
        int available = lastStripH - 2 - 3 - CARD_TEXT_H - 2;
        return Math.max(30, Math.min(THUMB_H, available));
    }

    private int dynCardH() {
        return dynThumbH() + 2 + 3 + CARD_TEXT_H + 2;
    }

    private int dynCardW() {
        int th = dynThumbH();
        if (th >= THUMB_H) return CARD_W;
        return Math.max(80, (int) (th * CARD_W / (double) THUMB_H));
    }

    private void renderCardSized(GuiGraphics g, Font f, YtVideoInfo info, int x, int y,
                                 int w, int thumbH, int cardH, boolean hover) {
        int bg;
        if (hover) {
            float pulse = (float) (Math.sin(System.currentTimeMillis() / 400.0 * Math.PI) * 0.5 + 0.5);
            int alpha = (int) (0x60 + pulse * 0x30);
            bg = (alpha << 24) | 0x707070;
        } else {
            bg = CARD_BG;
        }
        g.fill(x, y, x + w, y + cardH, bg);

        int thumbX = x + 2;
        int thumbY = y + 2;
        int thumbW = w - 4;
        Identifier thumb = Thumbnails.get(info.getId());
        if (thumb != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, thumb,
                    thumbX, thumbY, 0F, 0F, thumbW, thumbH, thumbW, thumbH);
        } else {
            g.fill(thumbX, thumbY, thumbX + thumbW, thumbY + thumbH, 0xFF000000);
        }

        if (info.isRecent(7)) {
            String tag = Component.translatable("dreamdisplays.ui.new").getString();
            int tw = f.width(tag) + 4;
            int th = f.lineHeight + 2;
            g.fill(thumbX + 2, thumbY + 2, thumbX + 2 + tw, thumbY + 2 + th, 0xFFE53935);
            g.drawString(f, tag, thumbX + 4, thumbY + 3, 0xFFFFFFFF, false);
        }

        String dur = info.formatDuration();
        if (!dur.isEmpty()) {
            int dw = f.width(dur) + 4;
            int dh = f.lineHeight + 2;
            int dx = thumbX + thumbW - dw - 2;
            int dy = thumbY + thumbH - dh - 2;
            g.fill(dx, dy, dx + dw, dy + dh, 0xC0000000);
            g.drawString(f, dur, dx + 2, dy + 2, 0xFFFFFFFF, false);
        }

        // Compact mode: thumbnail (with badges) only — no text underneath. Used
        // when the strip is too short for the title/meta rows to be readable.
        if (compactCards) return;

        int textX = x + 4;
        int textW = w - 8;
        int textY = thumbY + thumbH + 3;
        java.util.List<String> titleLines = wrap(f, info.getTitle(), textW, 2);
        for (String line : titleLines) {
            g.drawString(f, line, textX, textY, 0xFFFFFFFF, false);
            textY += f.lineHeight + 1;
        }

        String meta = info.getUploader() == null ? "" : info.getUploader();
        String views = info.formatViews();
        if (!views.isEmpty()) {
            meta = meta.isEmpty()
                    ? views
                    : trim(f, meta, Math.max(20, textW - f.width(" • " + views))) + " • " + views;
        }
        if (!meta.isEmpty()) {
            g.drawString(f, trim(f, meta, textW), textX, textY, 0xFFB8B8B8, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        int stripTop = searchBox.getY() + SEARCH_H + 8;
        int stripBottom = getY() + getHeight() - 10;
        if (mouseY < stripTop || mouseY > stripBottom) return false;
        int maxOff;
        if (vertical) {
            int viewportH = stripBottom - stripTop;
            int viewportW2 = getWidth() - 20;
            int vCardW = Math.max(CARD_W, viewportW2);
            int vThumbH2 = Math.max(THUMB_H, (int) (vCardW * 180.0 / 320.0));
            int vCardH2 = compactCards ? (THUMB_H + 4) : (vThumbH2 + CARD_TEXT_H);
            int contentH = cards.size() * (vCardH2 + CARD_GAP) - CARD_GAP;
            maxOff = Math.max(0, contentH - viewportH);
        } else {
            int viewportW = getWidth() - 20;
            int contentW = cards.size() * (dynCardW() + CARD_GAP) - CARD_GAP;
            maxOff = Math.max(0, contentW - viewportW);
        }
        double delta = vertical ? dy * 32 : (dx != 0 ? dx : dy) * 32;
        scrollOffset = Math.max(0, Math.min(maxOff, scrollOffset - (int) delta));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (clearButtonWidget.isMouseOver(mouseX, mouseY)) {
            return clearButtonWidget.mouseClicked(event, dbl);
        }
        if (searchActionButtonWidget.isMouseOver(mouseX, mouseY)) {
            return searchActionButtonWidget.mouseClicked(event, dbl);
        }
        if (searchBox.isMouseOver(mouseX, mouseY)) {
            boolean handled = searchBox.mouseClicked(event, dbl);
            searchBox.setFocused(true);
            return handled;
        }
        searchBox.setFocused(false);
        if (hoveredCard >= 0 && hoveredCard < cards.size()) {
            net.minecraft.client.resources.sounds.SimpleSoundInstance s =
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
            Minecraft.getInstance().getSoundManager().play(s);
            onPick.accept(cards.get(hoveredCard));
            return true;
        }
        return super.mouseClicked(event, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchBox.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                runSearch();
                return true;
            }
            return searchBox.keyPressed(event);
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searchBox.isFocused()) return searchBox.charTyped(event);
        return super.charTyped(event);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
    }
}
