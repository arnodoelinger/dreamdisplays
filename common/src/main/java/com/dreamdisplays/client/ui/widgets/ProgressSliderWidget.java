package com.dreamdisplays.client.ui.widgets;

import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NullMarked;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

@NullMarked
public class ProgressSliderWidget extends AbstractWidget {

    public static final long MAX_DRAG_DELTA_NS = 10L * 1_000_000_000L;
    private static final Identifier TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider");
    private static final Identifier HIGHLIGHTED_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_highlighted");
    private static final Identifier HANDLE_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_handle");
    private static final Identifier HANDLE_HIGHLIGHTED_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_handle_highlighted");
    private final LongSupplier currentSupplier;
    private final LongSupplier durationSupplier;
    private final LongConsumer seekConsumer;

    private boolean sliderFocused;
    private boolean dragging = false;
    private long dragAnchorNanos = 0;
    private long dragTargetNanos = 0;

    public ProgressSliderWidget(int x, int y, int width, int height,
                                LongSupplier currentSupplier,
                                LongSupplier durationSupplier,
                                LongConsumer seekConsumer) {
        super(x, y, width, height, Component.empty());
        this.currentSupplier = currentSupplier;
        this.durationSupplier = durationSupplier;
        this.seekConsumer = seekConsumer;
    }

    private static String formatTime(long nanos) {
        if (nanos <= 0) return "00:00";
        long s = nanos / 1_000_000_000L;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long dur = durationSupplier.getAsLong();
        long cur = dragging ? dragTargetNanos : currentSupplier.getAsLong();
        double value = dur > 0 ? Mth.clamp(cur / (double) dur, 0.0, 1.0) : 0.0;

        g.blitSprite(RenderPipelines.GUI_TEXTURED, getTrackSprite(),
                getX(), getY(), getWidth(), getHeight());
        int handleX = getX() + (int) (value * (double) (getWidth() - 8));
        g.blitSprite(RenderPipelines.GUI_TEXTURED, getHandleSprite(),
                handleX, getY(), 8, getHeight());

        MutableComponent label = buildLabel(cur, dur);
        renderScrollingStringOverContents(
                g.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.TOOLTIP_AND_CURSOR),
                label,
                4
        );
    }

    private MutableComponent buildLabel(long cur, long dur) {
        int color = active ? 0xFFFFFFFF : 0xFFA0A0A0;
        return Component.literal(formatTime(cur) + " / " + formatTime(dur))
                .copy()
                .withStyle(s -> s.withColor(color));
    }

    private Identifier getTrackSprite() {
        return this.isFocused() && !this.sliderFocused ? HIGHLIGHTED_TEXTURE_ID : TEXTURE_ID;
    }

    private Identifier getHandleSprite() {
        return !this.isHovered && !this.sliderFocused ? HANDLE_TEXTURE_ID : HANDLE_HIGHLIGHTED_TEXTURE_ID;
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return Component.translatable("gui.narrate.slider", buildLabel(
                currentSupplier.getAsLong(), durationSupplier.getAsLong()));
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput builder) {
        builder.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!active) return;
        long dur = durationSupplier.getAsLong();
        if (dur <= 0) return;
        dragAnchorNanos = currentSupplier.getAsLong();
        dragTargetNanos = clampDelta(positionFromMouse(event.x(), dur));
        dragging = true;
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        super.onDrag(event, dragX, dragY);
        if (!dragging || !active) return;
        long dur = durationSupplier.getAsLong();
        if (dur <= 0) return;
        dragTargetNanos = clampDelta(positionFromMouse(event.x(), dur));
    }

    public boolean commitDragIfActive() {
        if (!dragging) return false;
        dragging = false;
        seekConsumer.accept(dragTargetNanos);
        return true;
    }

    private long positionFromMouse(double mouseX, long dur) {
        double pct = Mth.clamp((mouseX - (double) (getX() + 4))
                / (double) (getWidth() - 8), 0.0, 1.0);
        return (long) (pct * dur);
    }

    private long clampDelta(long target) {
        long min = Math.max(0, dragAnchorNanos - MAX_DRAG_DELTA_NS);
        long max = dragAnchorNanos + MAX_DRAG_DELTA_NS;
        return Math.max(min, Math.min(max, target));
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            this.sliderFocused = false;
        } else {
            InputType t = Minecraft.getInstance().getLastInputType();
            if (t == InputType.MOUSE || t == InputType.KEYBOARD_TAB) {
                this.sliderFocused = true;
            }
        }
    }
}
