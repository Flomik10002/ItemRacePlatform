package com.redlimerl.speedrunigt.race;

import com.redlimerl.speedrunigt.utils.ButtonWidgetHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

@Environment(EnvType.CLIENT)
public class RaceLeaveWorldConfirmScreen extends Screen {
    private final Screen parent;
    private final Runnable disconnectWorldAction;
    private final Runnable leaveMatchAndDisconnectAction;

    public RaceLeaveWorldConfirmScreen(
            Screen parent,
            Runnable disconnectWorldAction,
            Runnable leaveMatchAndDisconnectAction
    ) {
        super(Text.translatable("speedrunigt.race.leave_world_confirm.title"));
        this.parent = parent;
        this.disconnectWorldAction = disconnectWorldAction;
        this.leaveMatchAndDisconnectAction = leaveMatchAndDisconnectAction;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int baseY = this.height / 2 - 20;
        int buttonWidth = 260;
        int buttonX = centerX - buttonWidth / 2;

        this.addDrawableChild(ButtonWidgetHelper.create(
                buttonX, baseY + 20, buttonWidth, 20,
                Text.translatable("speedrunigt.race.leave_world_confirm.disconnect_only")
                        .formatted(Formatting.YELLOW),
                button -> disconnectWorldAction.run()
        ));

        this.addDrawableChild(ButtonWidgetHelper.create(
                buttonX, baseY + 44, buttonWidth, 20,
                Text.translatable("speedrunigt.race.leave_world_confirm.leave_match")
                        .formatted(Formatting.RED),
                button -> leaveMatchAndDisconnectAction.run()
        ));

        this.addDrawableChild(ButtonWidgetHelper.create(
                buttonX, baseY + 74, buttonWidth, 20,
                ScreenTexts.CANCEL,
                button -> close()
        ));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int centerX = this.width / 2;
        int y = this.height / 2 - 44;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, y, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.translatable("speedrunigt.race.leave_world_confirm.description")
                        .formatted(Formatting.GRAY),
                centerX,
                y + 12,
                0xFFAAAAAA
        );
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}

