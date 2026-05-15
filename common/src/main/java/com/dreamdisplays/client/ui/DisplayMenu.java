package com.dreamdisplays.client.ui;

import com.dreamdisplays.Initializer;
import com.dreamdisplays.client.ui.widgets.*;
import com.dreamdisplays.display.DisplayManager;
import com.dreamdisplays.display.DisplayScreen;
import com.dreamdisplays.display.DisplaySettings;
import com.dreamdisplays.meta.UpdateCheck;
import com.dreamdisplays.net.Packets.Delete;
import com.dreamdisplays.net.Packets.Report;
import com.dreamdisplays.util.GeneralUtil;
import com.dreamdisplays.ytdlp.Thumbnails;
import com.dreamdisplays.ytdlp.YtDlp;
import com.dreamdisplays.ytdlp.YtVideoInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Configuration of a display screen GUI.
 */
@NullMarked
public class DisplayMenu extends Screen {

    private static final int PADDING = 10;
    private static final int PANEL_GAP = 8;
    private static final int PANEL_PADDING_X = 10;
    private static final int PANEL_PADDING_Y = 10;
    private static final int ROW_GAP = 4;
    private static final int CTRL_BTN = 22;
    private static final int ROW_H = CTRL_BTN;
    private static final int RESET_W = CTRL_BTN;
    private static final int CONTROL_W = 130;
    private static final int PANEL_BG = 0x90101010;
    private static final int PANEL_BORDER = 0xFF606060;
    private static final int ROW_BG = 0x40000000;
    private static final String GITHUB_URL = "https://github.com/arsmotorin/dreamdisplays";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/dreamdisplays/versions";
    @Nullable SliderWidget volume = null;
    @Nullable SliderWidget renderD = null;
    @Nullable SliderWidget quality = null;
    @Nullable SliderWidget brightness = null;
    @Nullable ToggleWidget sync = null;
    @Nullable ButtonWidget backButtonWidget = null;
    @Nullable ButtonWidget forwardButtonWidget = null;
    @Nullable ButtonWidget pauseButtonWidget = null;
    @Nullable ButtonWidget renderDReset = null;
    @Nullable ButtonWidget qualityReset = null;
    @Nullable ButtonWidget brightnessReset = null;
    @Nullable ButtonWidget volumeReset = null;
    @Nullable ButtonWidget syncReset = null;
    @Nullable ButtonWidget muteButtonWidget = null;
    @Nullable ButtonWidget deleteButtonWidget = null;
    @Nullable ButtonWidget reportButtonWidget = null;
    @Nullable ProgressSliderWidget progress = null;
    @Nullable SuggestionsPanelWidget suggestions = null;
    @Nullable String lastSuggestedVideoId = null;
    public @Nullable DisplayScreen displayScreen = null;
    private @Nullable HoverArea volumeHover, renderDHover, qualityHover, brightnessHover, syncHover;
    private @Nullable HoverArea modLabelHover;
    private long modLabelOpenedAtMs = System.currentTimeMillis();

    protected DisplayMenu() {
        super(Component.translatable("dreamdisplays.ui.title"));
    }

    public static void open(DisplayScreen displayScreen) {
        DisplayMenu s = new DisplayMenu();
        s.setScreen(displayScreen);
        Minecraft.getInstance().setScreen(s);
    }

    private static boolean hovered(int mx, int my, AbstractWidget w) {
        return mx >= w.getX() && mx < w.getX() + w.getWidth()
                && my >= w.getY() && my < w.getY() + w.getHeight();
    }

    private void setScreen(DisplayScreen s) {
        this.displayScreen = s;
    }

    @Override
    protected void init() {
        if (displayScreen == null) return;

        volume = new SliderWidget(0, 0, 0, 0,
                Component.literal((int) Math.floor(displayScreen.getVolume() * 200) + "%"),
                displayScreen.getVolume()) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal((int) Math.floor(value * 200) + "%"));
            }

            @Override
            protected void applyValue() {
                displayScreen.setVolume((float) value);
            }
        };

        backButtonWidget = iconButton("left", () -> displayScreen.seekBackward());
        forwardButtonWidget = iconButton("right", () -> displayScreen.seekForward());
        pauseButtonWidget = new ButtonWidget(0, 0, 0, 0, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pause"), 2) {
            @Override
            public void onPress() {
                displayScreen.setPaused(!displayScreen.getPaused());
                setIconTextureId(Identifier.fromNamespaceAndPath(Initializer.MOD_ID,
                        displayScreen.getPaused() ? "play" : "pause"));
            }
        };
        pauseButtonWidget.setIconTextureId(Identifier.fromNamespaceAndPath(Initializer.MOD_ID,
                displayScreen.getPaused() ? "play" : "pause"));

        muteButtonWidget = new ButtonWidget(0, 0, 0, 0, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID,
                        displayScreen.muted ? "mute" : "sound"), 2) {
            @Override
            public void onPress() {
                displayScreen.mute(!displayScreen.muted);
                setIconTextureId(Identifier.fromNamespaceAndPath(Initializer.MOD_ID,
                        displayScreen.muted ? "mute" : "sound"));
            }
        };

        progress = new ProgressSliderWidget(0, 0, 100, CTRL_BTN,
                () -> displayScreen != null ? displayScreen.getCurrentTimeNanos() : 0,
                () -> displayScreen != null ? displayScreen.getMediaPlayerDurationNanos() : 0,
                nanos -> {
                    if (displayScreen != null && displayScreen.canSeek() && !displayScreen.isLive()) {
                        displayScreen.seekToMillis(nanos / 1_000_000L);
                    }
                });

        renderD = new SliderWidget(0, 0, 0, 0,
                Component.literal(displayScreen.getRenderDistance() + " blocks"),
                (displayScreen.getRenderDistance() - 24) / (double) (128 - 24)) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(((int) (value * (128 - 24)) + 24) + " blocks"));
            }

            @Override
            protected void applyValue() {
                displayScreen.setRenderDistance((int) (value * (128 - 24) + 24));
                DisplayManager.saveScreenData(displayScreen);
            }
        };

        quality = new SliderWidget(0, 0, 0, 0,
                Component.literal(displayScreen.getQuality() + "p"),
                qualityFraction(displayScreen.getQuality())) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(qualityFromFraction(value) + "p"));
            }

            @Override
            protected void applyValue() {
                displayScreen.setQuality(qualityFromFraction(value));
            }
        };

        brightness = new SliderWidget(0, 0, 0, 0,
                Component.literal((int) Math.floor(displayScreen.getBrightness() * 100) + "%"),
                displayScreen.getBrightness() / 2.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal((int) Math.floor(value * 200) + "%"));
            }

            @Override
            protected void applyValue() {
                displayScreen.setBrightness((float) (value * 2.0));
            }
        };

        renderDReset = resetButton(() -> {
            displayScreen.setRenderDistance(Initializer.config.defaultDistance);
            renderD.value = (Initializer.config.defaultDistance - 24) / (double) (128 - 24);
            renderD.setMessage(Component.literal(Initializer.config.defaultDistance + " blocks"));
            DisplayManager.saveScreenData(displayScreen);
        });
        qualityReset = resetButton(() -> {
            displayScreen.setQuality("720");
            quality.value = qualityFraction("720");
            quality.setMessage(Component.literal("720p"));
        });
        brightnessReset = resetButton(() -> {
            displayScreen.setBrightness(1.0f);
            if (brightness != null) {
                brightness.value = 0.5;
                brightness.setMessage(Component.literal("100%"));
            }
        });
        volumeReset = resetButton(() -> {
            displayScreen.setVolume(0.5f);
            volume.value = 0.5;
            volume.setMessage(Component.literal("100%"));
        });

        sync = new ToggleWidget(0, 0, 0, 0,
                Component.translatable(displayScreen.isSync ? "dreamdisplays.button.enabled"
                        : "dreamdisplays.button.disabled"),
                displayScreen.isSync) {
            @Override
            protected void updateMessage() {
                setMessage(Component.translatable(value ? "dreamdisplays.button.enabled"
                        : "dreamdisplays.button.disabled"));
            }

            @Override
            public void applyValue() {
                if (displayScreen.owner && syncReset != null) {
                    displayScreen.isSync = value;
                    syncReset.active = !value;
                    displayScreen.waitForMFInit(() -> displayScreen.sendSync());
                }
            }
        };
        syncReset = resetButton(() -> {
            if (displayScreen.owner && sync != null) {
                sync.setValue(false);
                displayScreen.waitForMFInit(() -> displayScreen.sendSync());
            }
        });
        sync.active = displayScreen.owner;
        if (brightness != null) brightness.active = !displayScreen.isSync || displayScreen.owner;

        WidgetSprites red = new WidgetSprites(
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button"),
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button_disabled"),
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button_highlighted"));

        deleteButtonWidget = new ButtonWidget(0, 0, 0, 0, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "delete"), 2) {
            @Override
            public void onPress() {
                DisplaySettings.removeDisplay(displayScreen.getUUID());
                DisplayManager.unregisterScreen(displayScreen);
                Initializer.sendPacket(new Delete(displayScreen.getUUID()));
                onClose();
            }
        };
        deleteButtonWidget.setSprites(red);
        deleteButtonWidget.active = displayScreen.owner;

        if (Initializer.isReportingEnabled) {
            reportButtonWidget = new ButtonWidget(0, 0, 0, 0, 64, 64,
                    Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "report"), 2) {
                @Override
                public void onPress() {
                    Initializer.sendPacket(new Report(displayScreen.getUUID()));
                    onClose();
                }
            };
            reportButtonWidget.setSprites(red);
        } else {
            reportButtonWidget = null;
        }

        addRenderableWidget(volume);
        addRenderableWidget(backButtonWidget);
        addRenderableWidget(forwardButtonWidget);
        addRenderableWidget(muteButtonWidget);
        addRenderableWidget(progress);
        addRenderableWidget(pauseButtonWidget);
        addRenderableWidget(renderD);
        addRenderableWidget(quality);
        addRenderableWidget(qualityReset);
        addRenderableWidget(brightness);
        addRenderableWidget(brightnessReset);
        addRenderableWidget(renderDReset);
        addRenderableWidget(volumeReset);
        addRenderableWidget(sync);
        addRenderableWidget(syncReset);
        addRenderableWidget(deleteButtonWidget);
        if (reportButtonWidget != null) addRenderableWidget(reportButtonWidget);

        suggestions = new SuggestionsPanelWidget(0, 0, 100, 100, this::onPickSuggested);
        addRenderableWidget(suggestions);
    }

    private ButtonWidget iconButton(String icon, Runnable action) {
        return new ButtonWidget(0, 0, 0, 0, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, icon), 2) {
            @Override
            public void onPress() {
                action.run();
            }
        };
    }

    private ButtonWidget resetButton(Runnable action) {
        return new ButtonWidget(0, 0, 0, 0, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "refresh"), 2) {
            @Override
            public void onPress() {
                action.run();
            }
        };
    }

    private void onPickSuggested(YtVideoInfo info) {
        if (displayScreen == null) return;
        displayScreen.playSuggestedVideo(info.getWatchUrl(),
                displayScreen.getLang() == null ? "" : displayScreen.getLang());
        // Cache title + full metadata so the overlay shows them instantly
        com.dreamdisplays.ytdlp.VideoTitleCache.put(info.getId(), info.getTitle());
        com.dreamdisplays.ytdlp.VideoMetadataCache.put(info.getId(), info);
        lastSuggestedVideoId = info.getId();
        if (suggestions != null) suggestions.setRelatedTo(info.getId());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderTransparentBackground(g);

        if (displayScreen == null) {
            super.render(g, mouseX, mouseY, delta);
            return;
        }

        int titleY = 6;
        renderModLabel(g, PADDING, titleY);

        boolean videoReady = displayScreen.isVideoStarted() && !displayScreen.errored;
        if (syncReset != null) syncReset.active = videoReady && displayScreen.owner && displayScreen.isSync;
        if (renderDReset != null)
            renderDReset.active = videoReady && displayScreen.getRenderDistance() != Initializer.config.defaultDistance;
        if (qualityReset != null) qualityReset.active = videoReady && !Objects.equals(displayScreen.getQuality(), "720");
        if (brightnessReset != null && brightness != null)
            brightnessReset.active = videoReady && Math.abs(brightness.value - 0.5) > 0.01;
        if (volumeReset != null && volume != null)
            volumeReset.active = videoReady && Math.abs(volume.value - 0.5) > 0.01;

        boolean enabled = videoReady;
        if (volume != null) volume.active = enabled;
        if (renderD != null) renderD.active = enabled;
        if (quality != null) quality.active = enabled;
        if (brightness != null) brightness.active = enabled && (!displayScreen.isSync || displayScreen.owner);
        if (sync != null) sync.active = enabled && displayScreen.owner;
        if (deleteButtonWidget != null) deleteButtonWidget.active = displayScreen.owner; // delete always available
        if (progress != null) progress.active = enabled && displayScreen.canSeek() && !displayScreen.isLive();

        if (displayScreen.errored) {
            renderErroredOverlay(g, mouseX, mouseY, delta);
            return;
        }

        int contentTop = titleY + font.lineHeight + 8;
        int contentBottom = this.height - PADDING;
        int totalW = this.width - PADDING * 2;
        int totalH = contentBottom - contentTop;

        // Three layouts based on available space (which is what changes with GUI
        // scale). Wide -> suggestions become a vertical column on the right with
        // preview top-left and settings bottom-left. Normal -> current side-by-side
        // top with horizontal suggestions strip below. Compact -> vertical stack;
        // suggestions hide their text rows so the thumbnails alone fit.
        // TODO: refine it in 1.6.x versions
        boolean wide = totalW >= 900 && totalH >= 480;
        boolean compact = !wide && totalW < 600;

        int leftX = PADDING;
        int previewX, previewY, previewW, previewH;
        int settingsX, settingsY, settingsW, settingsH;
        int suggestionsX, suggestionsY, suggestionsW, suggestionsH;
        boolean suggestionsVertical = false;

        if (wide) {
            int rightColW = Math.max(200, Math.min(280, totalW * 3 / 10));
            int leftColW = totalW - rightColW - PANEL_GAP;
            int leftColH = totalH;
            int previewSlice = leftColH * 6 / 10;
            previewX = leftX;
            previewY = contentTop;
            previewW = leftColW;
            previewH = previewSlice;
            settingsX = leftX;
            settingsY = contentTop + previewSlice + PANEL_GAP;
            settingsW = leftColW;
            settingsH = leftColH - previewSlice - PANEL_GAP;
            suggestionsX = leftX + leftColW + PANEL_GAP;
            suggestionsY = contentTop;
            suggestionsW = rightColW;
            suggestionsH = leftColH;
            suggestionsVertical = true;
        } else {
            // Thumbnails now shrink dynamically inside SuggestionsPanel so the
            // title/meta text always fits — no need for a compact threshold.
            final int MIN_SH = 120;

            int topRowH = Math.max(220, (totalH * 6) / 10);
            int sH = totalH - topRowH - PANEL_GAP;
            if (sH < MIN_SH) {
                sH = MIN_SH;
                topRowH = totalH - sH - PANEL_GAP;
            }
            boolean showSuggestions = topRowH >= 160;

            if (compact) {
                previewW = totalW;
                previewH = Math.min(220, topRowH * 3 / 5);
                settingsW = totalW;
                settingsH = topRowH - previewH - PANEL_GAP;
                settingsX = leftX;
                settingsY = contentTop + previewH + PANEL_GAP;
            } else {
                previewW = (totalW * 6) / 10 - PANEL_GAP / 2;
                settingsW = totalW - previewW - PANEL_GAP;
                settingsX = leftX + previewW + PANEL_GAP;
                previewH = topRowH;
                settingsH = topRowH;
                settingsY = contentTop;
            }
            previewX = leftX;
            previewY = contentTop;
            suggestionsX = leftX;
            suggestionsY = contentTop + topRowH + PANEL_GAP;
            suggestionsW = totalW;
            suggestionsH = showSuggestions ? sH : 0;
        }

        drawPanel(g, previewX, previewY, previewW, previewH,
                Component.translatable("dreamdisplays.ui.preview").getString());
        drawPanel(g, settingsX, settingsY, settingsW, settingsH,
                Component.translatable("dreamdisplays.ui.settings").getString());

        renderPreviewSection(g, previewX, previewY, previewW, previewH);
        renderSettingsSection(g, settingsX, settingsY, settingsW, settingsH);

        if (suggestions != null) {
            suggestions.visible = suggestionsH > 0;
            suggestions.setVertical(suggestionsVertical);
            suggestions.setCompactCards(false);
            suggestions.setX(suggestionsX);
            suggestions.setY(suggestionsY);
            suggestions.setWidth(suggestionsW);
            suggestions.setHeight(suggestionsH);

            String currentId = YtDlp.extractVideoId(displayScreen.getVideoUrl());
            if (currentId != null && !Objects.equals(currentId, lastSuggestedVideoId)) {
                lastSuggestedVideoId = currentId;
                suggestions.setRelatedTo(currentId);
            }
        }

        layoutOwnerActions(settingsX, settingsY, settingsW, settingsH);

        super.render(g, mouseX, mouseY, delta);

        renderTooltips(g, mouseX, mouseY);
    }

    private void renderErroredOverlay(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (suggestions != null) suggestions.visible = false;
        if (volume != null) { volume.active = false; volume.visible = false; }
        if (renderD != null) { renderD.active = false; renderD.visible = false; }
        if (quality != null) { quality.active = false; quality.visible = false; }
        if (brightness != null) { brightness.active = false; brightness.visible = false; }
        if (sync != null) { sync.active = false; sync.visible = false; }
        if (backButtonWidget != null) { backButtonWidget.active = false; backButtonWidget.visible = false; }
        if (forwardButtonWidget != null) { forwardButtonWidget.active = false; forwardButtonWidget.visible = false; }
        if (pauseButtonWidget != null) { pauseButtonWidget.active = false; pauseButtonWidget.visible = false; }
        if (renderDReset != null) { renderDReset.active = false; renderDReset.visible = false; }
        if (qualityReset != null) { qualityReset.active = false; qualityReset.visible = false; }
        if (brightnessReset != null) { brightnessReset.active = false; brightnessReset.visible = false; }
        if (volumeReset != null) { volumeReset.active = false; volumeReset.visible = false; }
        if (syncReset != null) { syncReset.active = false; syncReset.visible = false; }
        if (progress != null) { progress.active = false; progress.visible = false; }
        if (muteButtonWidget != null) { muteButtonWidget.active = false; muteButtonWidget.visible = false; }

        int panelW = Math.min(420, this.width - 40);
        int panelX = this.width / 2 - panelW / 2;
        int panelY = this.height / 2 - 70;
        drawPanel(g, panelX, panelY, panelW, 130,
                Component.translatable("dreamdisplays.ui.error").getString());
        List<Component> lines = List.of(
                Component.translatable("dreamdisplays.error.loadingerror.1").withStyle(s -> s.withColor(ChatFormatting.RED)),
                Component.translatable("dreamdisplays.error.loadingerror.2").withStyle(s -> s.withColor(ChatFormatting.RED)),
                Component.translatable("dreamdisplays.error.loadingerror.4").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                Component.translatable("dreamdisplays.error.loadingerror.5").withStyle(s -> s.withColor(ChatFormatting.GRAY))
        );
        int y = panelY + headerHeight() + 8;
        for (Component line : lines) {
            g.drawString(font, line, this.width / 2 - font.width(line) / 2, y, 0xFFFFFFFF, false);
            y += font.lineHeight + 4;
        }
        if (deleteButtonWidget != null) {
            deleteButtonWidget.setX(panelX + panelW / 2 - 22);
            deleteButtonWidget.setY(panelY + 130 - 24);
            deleteButtonWidget.setWidth(20);
            deleteButtonWidget.setHeight(20);
        }
        if (reportButtonWidget != null) {
            reportButtonWidget.setX(panelX + panelW / 2 + 2);
            reportButtonWidget.setY(panelY + 130 - 24);
            reportButtonWidget.setWidth(20);
            reportButtonWidget.setHeight(20);
        }
        super.render(g, mouseX, mouseY, delta);
    }

    private void renderPreviewSection(GuiGraphics g, int px, int py, int pw, int ph) {
        DisplayScreen scr = Objects.requireNonNull(displayScreen);
        int innerX = px + PANEL_PADDING_X;
        int innerY = py + headerHeight();
        int innerW = pw - PANEL_PADDING_X * 2;

        int controlsRowY = py + ph - PANEL_PADDING_Y - CTRL_BTN;
        int controlsLeft = innerX;
        int controlsRight = innerX + innerW;

        int previewMaxH = controlsRowY - innerY - 6;
        int frameW = innerW;
        int frameH = previewMaxH;
        g.fill(innerX, innerY, innerX + frameW, innerY + frameH, 0xFF000000);

        float ratio = scr.getWidth() / Math.max(1f, scr.getHeight());
        int videoW;
        int videoH;
        if (frameW / (float) frameH > ratio) {
            videoH = frameH;
            videoW = (int) (videoH * ratio);
        } else {
            videoW = frameW;
            videoH = (int) (videoW / ratio);
        }
        int videoX = innerX + (frameW - videoW) / 2;
        int videoY = innerY + (frameH - videoH) / 2;

        if (scr.isVideoStarted() && scr.texture != null && scr.textureId != null) {
            scr.fitTexture();
            g.blit(RenderPipelines.GUI_TEXTURED, scr.textureId,
                    videoX, videoY, 0F, 0F, videoW, videoH,
                    scr.textureWidth, scr.textureHeight,
                    scr.textureWidth, scr.textureHeight);
        } else {
            Identifier thumb = currentThumbnail();
            if (thumb != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, thumb,
                        videoX, videoY, 0F, 0F, videoW, videoH, 320, 180);
                g.fill(videoX, videoY, videoX + videoW, videoY + videoH, 0x80000000);
            }
            String waiting = Component.translatable("dreamdisplays.ui.waiting").getString();
            g.drawString(font, waiting,
                    innerX + frameW / 2 - font.width(waiting) / 2,
                    innerY + frameH / 2 - font.lineHeight / 2,
                    0xFFCCCCCC, true);
        }

        renderTitleOverlay(g, scr, innerX, innerY + frameH, frameW);

        boolean canSeek = !(scr.isSync && !scr.owner) && scr.canSeek();
        if (backButtonWidget != null) {
            backButtonWidget.setX(controlsLeft);
            backButtonWidget.setY(controlsRowY);
            backButtonWidget.setWidth(CTRL_BTN);
            backButtonWidget.setHeight(CTRL_BTN);
            backButtonWidget.active = canSeek;
        }
        if (forwardButtonWidget != null) {
            forwardButtonWidget.setX(controlsLeft + CTRL_BTN + 4);
            forwardButtonWidget.setY(controlsRowY);
            forwardButtonWidget.setWidth(CTRL_BTN);
            forwardButtonWidget.setHeight(CTRL_BTN);
            forwardButtonWidget.active = canSeek;
        }
        if (muteButtonWidget != null) {
            muteButtonWidget.setX(controlsLeft + CTRL_BTN * 2 + 8);
            muteButtonWidget.setY(controlsRowY);
            muteButtonWidget.setWidth(CTRL_BTN);
            muteButtonWidget.setHeight(CTRL_BTN);
            muteButtonWidget.active = !(scr.isSync && !scr.owner);
            muteButtonWidget.setIconTextureId(Identifier.fromNamespaceAndPath(
                    Initializer.MOD_ID, scr.muted ? "mute" : "sound"));
        }
        if (pauseButtonWidget != null) {
            pauseButtonWidget.setX(controlsRight - CTRL_BTN);
            pauseButtonWidget.setY(controlsRowY);
            pauseButtonWidget.setWidth(CTRL_BTN);
            pauseButtonWidget.setHeight(CTRL_BTN);
            pauseButtonWidget.active = !(scr.isSync && !scr.owner);
            pauseButtonWidget.setIconTextureId(Identifier.fromNamespaceAndPath(
                    Initializer.MOD_ID, scr.getPaused() ? "play" : "pause"));
        }
        if (progress != null) {
            int progX = controlsLeft + CTRL_BTN * 3 + 12;
            int progRight = controlsRight - CTRL_BTN - 4;
            int progW = Math.max(40, progRight - progX);
            progress.setX(progX);
            progress.setY(controlsRowY);
            progress.setWidth(progW);
            progress.setHeight(CTRL_BTN);
        }
    }

    private void renderTitleOverlay(GuiGraphics g, DisplayScreen scr,
                                    int x, int y, int w) {
        String videoId = YtDlp.extractVideoId(scr.getVideoUrl());
        com.dreamdisplays.ytdlp.YtVideoInfo meta = videoId != null
                ? com.dreamdisplays.ytdlp.VideoMetadataCache.get(videoId) : null;
        if (videoId != null && meta == null) {
            com.dreamdisplays.ytdlp.VideoMetadataCache.requestAsync(videoId);
        }

        String title = meta != null ? meta.getTitle() : null;
        // Fall back to the title cache (populated instantly on pick) before showing
        // the raw URL, which would flash for a frame while metadata loads async.
        if ((title == null || title.isEmpty()) && videoId != null) {
            title = com.dreamdisplays.ytdlp.VideoTitleCache.get(videoId);
        }
        if (title == null || title.isEmpty()) title = scr.getVideoUrl();
        if (title == null) title = "—";

        String channel = meta != null ? meta.getUploader() : null;
        String views = meta != null ? meta.formatViews() : "";
        String likes = meta != null ? meta.formatLikes() : "";
        String published = meta != null ? meta.getPublishedText() : null;
        boolean isNew = meta != null && meta.isRecent(7);

        int padX = 4;
        int padY = 3;
        int textW = w - padX * 2;
        String shown = trimToWidth(title, textW);

        int boxH = font.lineHeight * 2 + padY * 3;
        int boxY = y - boxH;
        g.fill(x, boxY, x + w, y, 0xC0000000);

        int titleX = x + padX;
        int titleY = boxY + padY;
        if (isNew) {
            String tag = Component.translatable("dreamdisplays.ui.new").getString();
            int tw = font.width(tag) + 6;
            int th = font.lineHeight;
            g.fill(titleX, titleY - 1, titleX + tw, titleY + th, 0xFFE53935);
            g.drawString(font, tag, titleX + 3, titleY, 0xFFFFFFFF, false);
            titleX += tw + 4;
            shown = trimToWidth(title, textW - tw - 4);
        }
        g.drawString(font, shown, titleX, titleY, 0xFFFFFFFF, false);

        StringBuilder meta2 = new StringBuilder();
        if (channel != null && !channel.isEmpty()) meta2.append(channel);
        if (!views.isEmpty()) {
            if (meta2.length() > 0) meta2.append(" • ");
            meta2.append(views);
        }
        if (!likes.isEmpty()) {
            if (meta2.length() > 0) meta2.append(" • ");
            meta2.append(likes).append(" ").append(Component.translatable("dreamdisplays.ui.likes").getString());
        }
        if (published != null && !published.isEmpty()) {
            if (meta2.length() > 0) meta2.append(" • ");
            meta2.append(published);
        }
        String metaShown = trimToWidth(meta2.toString(), textW);
        g.drawString(font, metaShown, x + padX,
                boxY + padY + font.lineHeight + padY, 0xFFAAAAAA, false);
    }

    private String trimToWidth(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String dots = "...";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (font.width(sb.toString() + s.charAt(i) + dots) > maxW) break;
            sb.append(s.charAt(i));
        }
        return sb + dots;
    }

    private @Nullable Identifier currentThumbnail() {
        if (displayScreen == null) return null;
        String url = displayScreen.getVideoUrl();
        if (url == null) return null;
        String id = YtDlp.extractVideoId(url);
        if (id == null) return null;
        Identifier ready = Thumbnails.get(id);
        if (ready != null) return ready;
        Thumbnails.request(id, "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
        return null;
    }

    private void renderSettingsSection(GuiGraphics g, int px, int py, int pw, int ph) {
        int innerX = px + PANEL_PADDING_X;
        int innerY = py + headerHeight();
        int innerW = pw - PANEL_PADDING_X * 2;

        int rowY = innerY;
        int volumeRowY = rowY;
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.volume", volume, volumeReset);
        int renderDRowY = rowY;
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.render-distance", renderD, renderDReset);
        int qualityRowY = rowY;
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.quality", quality, qualityReset);
        int brightnessRowY = rowY;
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.brightness", brightness, brightnessReset);
        rowY += 6;
        int syncRowY = rowY;
        renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.synchronization", sync, syncReset);

        volumeHover = labelHover(innerX + 6, volumeRowY, "dreamdisplays.button.volume");
        renderDHover = labelHover(innerX + 6, renderDRowY, "dreamdisplays.button.render-distance");
        qualityHover = labelHover(innerX + 6, qualityRowY, "dreamdisplays.button.quality");
        brightnessHover = labelHover(innerX + 6, brightnessRowY, "dreamdisplays.button.brightness");
        syncHover = labelHover(innerX + 6, syncRowY, "dreamdisplays.button.synchronization");
    }

    private HoverArea labelHover(int x, int rowY, String key) {
        int w = font.width(Component.translatable(key));
        int textY = rowY + ROW_H / 2 - font.lineHeight / 2;
        return new HoverArea(x, textY, w, font.lineHeight);
    }

    private int renderRow(GuiGraphics g, int x, int y, int w, String key,
                          @Nullable AbstractWidget control, @Nullable ButtonWidget reset) {
        g.fill(x, y, x + w, y + ROW_H, ROW_BG);
        Component label = Component.translatable(key);
        g.drawString(font, label, x + 6, y + ROW_H / 2 - font.lineHeight / 2, 0xFFFFFFFF, false);

        int rightEdge = x + w - 4;
        if (reset != null) {
            reset.setX(rightEdge - RESET_W);
            reset.setY(y);
            reset.setWidth(RESET_W);
            reset.setHeight(ROW_H);
            rightEdge -= RESET_W + 4;
        }
        if (control != null) {
            int controlW = Math.min(CONTROL_W, Math.max(60,
                    rightEdge - (x + 6 + font.width(label) + 8)));
            control.setX(rightEdge - controlW);
            control.setY(y);
            control.setWidth(controlW);
            control.setHeight(ROW_H);
        }
        return y + ROW_H + ROW_GAP;
    }

    private void layoutOwnerActions(int sx, int sy, int sw, int sh) {
        int btn = CTRL_BTN;
        int padding = PANEL_PADDING_X;
        int rightEdge = sx + sw - padding;
        int yEdge = sy + sh - padding - btn;

        if (reportButtonWidget != null) {
            reportButtonWidget.setX(rightEdge - btn);
            reportButtonWidget.setY(yEdge);
            reportButtonWidget.setWidth(btn);
            reportButtonWidget.setHeight(btn);
            rightEdge -= btn + 4;
        }
        if (deleteButtonWidget != null) {
            deleteButtonWidget.setX(rightEdge - btn);
            deleteButtonWidget.setY(yEdge);
            deleteButtonWidget.setWidth(btn);
            deleteButtonWidget.setHeight(btn);
        }
    }

    private int headerHeight() {
        return PANEL_PADDING_Y + font.lineHeight + 6;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        if (modLabelHover != null && UpdateCheck.shouldShowArrow()
                && modLabelHover.contains((int) event.x(), (int) event.y())) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(MODRINTH_URL));
            } catch (Exception ignored) {
            }
            return true;
        }
        return super.mouseClicked(event, dbl);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (progress != null && progress.commitDragIfActive()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    private void renderTooltips(GuiGraphics g, int mouseX, int mouseY) {
        DisplayScreen scr = displayScreen;
        if (scr == null || scr.errored) return;

        if (volumeHover != null && volumeHover.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable("dreamdisplays.button.volume.tooltip.1").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                    Component.translatable("dreamdisplays.button.volume.tooltip.2").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.translatable("dreamdisplays.button.volume.tooltip.3").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.translatable("dreamdisplays.button.volume.tooltip.4",
                                    volume != null ? (int) (volume.value * 200) : 0)
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD))
            ), mouseX, mouseY);
        }
        if (renderDHover != null && renderDHover.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable("dreamdisplays.button.render-distance.tooltip.1").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                    Component.translatable("dreamdisplays.button.render-distance.tooltip.2").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.translatable("dreamdisplays.button.render-distance.tooltip.3").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.literal(""),
                    Component.translatable("dreamdisplays.button.render-distance.tooltip.8",
                                    renderD != null ? (int) (renderD.value * (128 - 24) + 24) : 0)
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD))
            ), mouseX, mouseY);
        }
        if (qualityHover != null && qualityHover.contains(mouseX, mouseY) && quality != null) {
            List<Component> tip = new java.util.ArrayList<>(List.of(
                    Component.translatable("dreamdisplays.button.quality.tooltip.1").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                    Component.translatable("dreamdisplays.button.quality.tooltip.2").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.literal(""),
                    Component.translatable("dreamdisplays.button.quality.tooltip.4",
                                    qualityFromFraction(quality.value))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD))
            ));
            try {
                if (Integer.parseInt(scr.getQuality()) >= 1080) {
                    tip.add(Component.translatable("dreamdisplays.button.quality.tooltip.5")
                            .withStyle(s -> s.withColor(ChatFormatting.YELLOW)));
                }
            } catch (NumberFormatException ignored) {
            }
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
        if (brightnessHover != null && brightnessHover.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable("dreamdisplays.button.brightness.tooltip.1").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                    Component.translatable("dreamdisplays.button.brightness.tooltip.2").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.literal(""),
                    Component.translatable("dreamdisplays.button.brightness.tooltip.3",
                                    brightness != null ? (int) Math.floor(brightness.value * 200) : 100)
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD))
            ), mouseX, mouseY);
        }
        if (syncHover != null && syncHover.contains(mouseX, mouseY) && sync != null) {
            g.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable("dreamdisplays.button.synchronization.tooltip.1").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                    Component.translatable("dreamdisplays.button.synchronization.tooltip.2").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.translatable("dreamdisplays.button.synchronization.tooltip.3").withStyle(s -> s.withColor(ChatFormatting.GRAY)),
                    Component.literal(""),
                    Component.translatable("dreamdisplays.button.synchronization.tooltip.5",
                                    sync.value
                                            ? Component.translatable("dreamdisplays.button.enabled")
                                            : Component.translatable("dreamdisplays.button.disabled"))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD))
            ), mouseX, mouseY);
        }

        if (deleteButtonWidget != null && hovered(mouseX, mouseY, deleteButtonWidget)) {
            g.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable("dreamdisplays.button.delete.tooltip.1").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                    Component.translatable("dreamdisplays.button.delete.tooltip.2").withStyle(s -> s.withColor(ChatFormatting.GRAY))
            ), mouseX, mouseY);
        }
        if (reportButtonWidget != null && hovered(mouseX, mouseY, reportButtonWidget)) {
            g.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable("dreamdisplays.button.report.tooltip.1").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                    Component.translatable("dreamdisplays.button.report.tooltip.2").withStyle(s -> s.withColor(ChatFormatting.GRAY))
            ), mouseX, mouseY);
        }
    }

    private void drawPanel(GuiGraphics g, int x, int y, int w, int h, String title) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        int b = PANEL_BORDER;
        g.fill(x, y, x + w, y + 1, b);
        g.fill(x, y + h - 1, x + w, y + h, b);
        g.fill(x, y, x + 1, y + h, b);
        g.fill(x + w - 1, y, x + w, y + h, b);
        g.drawString(font, title, x + PANEL_PADDING_X, y + PANEL_PADDING_Y, 0xFFFFFFFF, false);
    }

    private double qualityFraction(String q) {
        if (displayScreen == null) return 0;
        List<Integer> list = displayScreen.getQualityList();
        if (list.isEmpty()) return 0;
        int target;
        try {
            target = Integer.parseInt(q.replace("p", ""));
        } catch (Exception e) {
            target = 720;
        }
        int closest = list.get(0);
        int minDiff = Math.abs(target - closest);
        for (int v : list) {
            int d = Math.abs(target - v);
            if (d < minDiff) {
                minDiff = d;
                closest = v;
            }
        }
        return list.indexOf(closest) / (double) Math.max(1, list.size() - 1);
    }

    private String qualityFromFraction(double v) {
        if (displayScreen == null) return "720";
        List<Integer> list = displayScreen.getQualityList();
        if (list.isEmpty()) return "144";
        int idx = (int) Math.round(v * (list.size() - 1));
        idx = Math.max(0, Math.min(list.size() - 1, idx));
        return String.valueOf(list.get(idx));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderModLabel(GuiGraphics g, int x, int y) {
        boolean update = UpdateCheck.shouldShowArrow();
        Component name = Component.literal("Dream Displays");
        Component ver = Component.literal(" " + GeneralUtil.getModVersion())
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0xFF6AB7FF));
        Component label = name.copy().append(ver);
        g.drawString(font, label, x, y, 0xFFFFFFFF, true);

        int textW = font.width(label);
        int totalW = textW;
        if (update) {
            float t = ((System.currentTimeMillis() - modLabelOpenedAtMs) % 1800L) / 1800F;
            int arrowYOffset = 0;
            if (t < 0.25F) {
                float p = t / 0.25F;
                arrowYOffset = (int) (-Math.sin(p * Math.PI) * 3F);
            }
            Component arrow = Component.literal(" ▲")
                    .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0xFFFF4040));
            g.drawString(font, arrow, x + textW, y + arrowYOffset, 0xFFFFFFFF, true);
            totalW += font.width(arrow);
        }

        modLabelHover = new HoverArea(x, y - 1, totalW, font.lineHeight + 2);
    }

    private record HoverArea(int x, int y, int w, int h) {
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
