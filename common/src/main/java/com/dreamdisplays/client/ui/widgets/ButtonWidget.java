package com.dreamdisplays.client.ui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A button widget that we use in display configuration GUI.
 */
@NullMarked
public abstract class ButtonWidget extends AbstractWidget {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted")
    );
    private final int iconWidth;
    private final int iconHeight;
    private final int margin;
    private Identifier iconTextureId;
    private @Nullable WidgetSprites setSprites = null;

    public ButtonWidget(
            int x,
            int y,
            int width,
            int height,
            int iconWidth,
            int iconHeight,
            Identifier iconTextureId,
            int margin
    ) {
        super(x, y, width, height, Component.empty());
        this.iconWidth = iconWidth;
        this.iconHeight = iconHeight;
        this.iconTextureId = iconTextureId;
        this.margin = margin;
    }

    public void setIconTextureId(Identifier iconTextureId) {
        this.iconTextureId = iconTextureId;
    }

    public void setSprites(WidgetSprites setSprites) {
        this.setSprites = setSprites;
    }

    public abstract void onPress();

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.onPress();
        super.playDownSound(Minecraft.getInstance().getSoundManager());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
    }

    @Override
    protected void renderWidget(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        guiGraphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                setSprites != null
                        ? setSprites.get(this.active, this.isHoveredOrFocused())
                        : SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(),
                this.getY(),
                this.getWidth(),
                this.getHeight(),
                ARGB.white(this.alpha)
        );

        int dW = getWidth() - 2 * margin;
        int dH = getHeight() - 2 * margin;

        int iconW = dW;
        int iconH = (int) Math.max(
                (((double) iconHeight) / iconWidth) * iconW,
                dH
        );
        iconW = (int) ((((double) iconWidth) / iconHeight) * iconH);

        int dx = getX() + getWidth() / 2 - iconW / 2;
        int dy = getY() + getHeight() / 2 - iconH / 2;

        guiGraphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                iconTextureId,
                dx,
                dy,
                iconW,
                iconH,
                ARGB.white(this.alpha)
        );
    }
}
