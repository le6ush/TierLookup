package com.tierlookup.client;

import com.tierlookup.TierLookupClient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

public final class SearchCaptureScreen extends Screen {
    public SearchCaptureScreen() {
        super(Text.literal("TierLookup Search"));
    }
    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null) {
            client.setScreen(new SearchCaptureScreen());
        }
    }
    public static boolean isOpen() {
        return MinecraftClient.getInstance().currentScreen instanceof SearchCaptureScreen;
    }
    public static void closeIfOpen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof SearchCaptureScreen) {
            client.setScreen(null);
        }
    }
    @Override
    public boolean shouldPause() {
        return false;
    }
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
    @Override
    public boolean keyPressed(KeyInput input) {
        OverlayRenderer overlay = TierLookupClient.overlayInstance();
        if (overlay != null && overlay.noteEditing() && overlay.handleNoteKey(input)) {
            return true;
        }
        TierLookupClient.handleSearchCaptureKey(input.getKeycode());
        return true;
    }
    @Override
    public boolean charTyped(CharInput input) {
        OverlayRenderer overlay = TierLookupClient.overlayInstance();
        if (overlay != null && overlay.noteEditing()) {
            return overlay.handleNoteChar(input);
        }
        return super.charTyped(input);
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        OverlayRenderer overlay = TierLookupClient.overlayInstance();
        if (overlay != null && verticalAmount != 0) {
            overlay.scrollFavorites(verticalAmount > 0 ? -1 : 1);
        }
        return true;
    }
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        OverlayRenderer overlay = TierLookupClient.overlayInstance();
        if (overlay != null) {
            overlay.renderFromScreen(context, MinecraftClient.getInstance());
        }
    }
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    }
    @Override
    protected void applyBlur(DrawContext context) {
    }
    @Override
    public void renderInGameBackground(DrawContext context) {
    }
}
