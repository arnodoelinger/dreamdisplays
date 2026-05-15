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

/**
 * A button widget that we use in display configuration GUI.
 */
@NullMarked
public abstract class SliderWidget extends AbstractWidget {

    private static final Identifier TEXTURE_ID = Identifier.withDefaultNamespace(
            "widget/slider"
    );
    private static final Identifier HIGHLIGHTED_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_highlighted");
    private static final Identifier HANDLE_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_handle");
    private static final Identifier HANDLE_HIGHLIGHTED_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_handle_highlighted");
    public double value;
    private boolean sliderFocused;

    public SliderWidget(
            int x,
            int y,
            int width,
            int height,
            Component message,
            double value
    ) {
        super(x, y, width, height, message);
        this.value = value;
    }

    private Identifier getTexture() {
        return this.isFocused() && !this.sliderFocused
                ? HIGHLIGHTED_TEXTURE_ID
                : TEXTURE_ID;
    }

    private Identifier getHandleTexture() {
        return !this.isHovered && !this.sliderFocused
                ? HANDLE_TEXTURE_ID
                : HANDLE_HIGHLIGHTED_TEXTURE_ID;
    }

    protected MutableComponent createNarrationMessage() {
        return Component.translatable("gui.narrate.slider", this.getMessage());
    }

    public void updateWidgetNarration(NarrationElementOutput builder) {
        builder.add(NarratedElementType.TITLE, this.createNarrationMessage());
        if (this.active) {
            if (this.isFocused()) {
                builder.add(
                        NarratedElementType.USAGE,
                        Component.translatable("narration.slider.usage.focused")
                );
            } else {
                builder.add(
                        NarratedElementType.USAGE,
                        Component.translatable("narration.slider.usage.hovered")
                );
            }
        }
    }

    // from ExtendedSlider.class
    public void renderWidget(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                this.getTexture(),
                this.getX(),
                this.getY(),
                this.getWidth(),
                this.getHeight()
        );
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                this.getHandleTexture(),
                this.getX() + (int) (this.value * (double) (this.width - 8)),
                this.getY(),
                8,
                this.getHeight()
        );
        int i = this.active ? 16777215 : 10526880;
        MutableComponent message = this.getMessage()
                .copy()
                .withStyle(style -> style.withColor(i));
        this.renderScrollingStringOverContents(
                graphics.textRendererForWidget(
                        this,
                        GuiGraphics.HoveredTextEffects.TOOLTIP_AND_CURSOR
                ),
                message,
                2
        ); // , i | Mth.ceil(this.alpha * 255.0F) << 24
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.setValueFromMouse(event.x());
        super.playDownSound(Minecraft.getInstance().getSoundManager());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        super.onDrag(event, dragX, dragY);
        this.setValueFromMouse(event.x());
    }

    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            this.sliderFocused = false;
        } else {
            InputType guiNavigationType =
                    Minecraft.getInstance().getLastInputType();
            if (
                    guiNavigationType == InputType.MOUSE ||
                            guiNavigationType == InputType.KEYBOARD_TAB
            ) {
                this.sliderFocused = true;
            }
        }
    }

    private void setValueFromMouse(double mouseX) {
        this.setFractionalValue(
                (mouseX - (double) (this.getX() + 4)) / (double) (this.width - 8)
        );
    }

    private void setFractionalValue(double fractionalValue) {
        double oldValue = this.value;
        this.value = Mth.clamp(fractionalValue, 0.0, 1.0);
        if (!Mth.equal(oldValue, this.value)) {
            this.applyValue();
        }
        this.updateMessage();
    }

    protected abstract void updateMessage();

    protected abstract void applyValue();
}
