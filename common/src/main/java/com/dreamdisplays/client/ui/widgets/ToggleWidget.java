package com.dreamdisplays.client.ui.widgets;

import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;

/**
 * A button widget that we use in display configuration GUI.
 */
@NullMarked
public abstract class ToggleWidget extends AbstractWidget {

    private static final Identifier TEXTURE_ID = Identifier.withDefaultNamespace(
            "widget/slider"
    );
    private static final Identifier HIGHLIGHTED_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_highlighted");
    private static final Identifier HANDLE_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_handle");
    private static final Identifier HANDLE_HIGHLIGHTED_TEXTURE_ID =
            Identifier.withDefaultNamespace("widget/slider_handle_highlighted");
    public boolean value;
    private double dValue;
    private boolean sliderFocused;

    public ToggleWidget(
            int x,
            int y,
            int width,
            int height,
            Component message,
            boolean value
    ) {
        super(x, y, width, height, message);
        this.dValue = value ? 1 : 0;
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

    @Override
    protected MutableComponent createNarrationMessage() {
        return Component.translatable("gui.narrate.slider", this.getMessage());
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
    }

    @Override
    public void renderWidget(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        guiGraphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                this.getTexture(),
                this.getX(),
                this.getY(),
                this.getWidth(),
                this.getHeight()
        );
        guiGraphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                this.getHandleTexture(),
                this.getX() + (int) (this.dValue * (double) (this.width - 8)),
                this.getY(),
                8,
                this.getHeight()
        );
        int i = this.active ? 16777215 : 10526880;
        MutableComponent message = this.getMessage()
                .copy()
                .withStyle(style -> style.withColor(i));
        this.renderScrollingStringOverContents(
                guiGraphics.textRendererForWidget(
                        this,
                        GuiGraphics.HoveredTextEffects.TOOLTIP_AND_CURSOR
                ),
                message,
                2
        ); // , i | Mth.ceil(this.alpha * 255.0F) << 24
    }

    @Override
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

    private void setValueFromMouse() {
        value = !value;
        dValue = value ? 1 : 0;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.setValueFromMouse();
        this.updateMessage();
        this.applyValue();
        super.playDownSound(Minecraft.getInstance().getSoundManager());
    }

    protected abstract void updateMessage();

    public abstract void applyValue();

    public void setValue(boolean newValue) {
        if (this.value != newValue) {
            this.value = newValue;
            this.dValue = newValue ? 1 : 0;
            updateMessage();
            applyValue();
        }
    }
}
