package com.tierlookup.client;

import com.tierlookup.TierLookupClient;
import com.tierlookup.client.runtime.MinecraftRuntime;
import com.tierlookup.client.ui.PlayerNoteEditor;
import com.tierlookup.client.ui.PlayerNotePanel;
import com.tierlookup.client.ui.PlayerQuickMenu;
import com.tierlookup.model.PlayerIdentity;
import com.tierlookup.service.ProfileService;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

public final class TabInteractionScreen extends Screen {
    private final PlayerNotePanel notePanel = new PlayerNotePanel();
    private PlayerIdentity contextPlayer;
    private PlayerQuickMenu.Layout contextLayout;
    private int contextX;
    private int contextY;
    private int mouseX;
    private int mouseY;
    private boolean playerListReleased;
    public TabInteractionScreen() {
        super(Text.literal("TierLookup TAB"));
    }
    public static boolean open() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof TabInteractionScreen) {
            return true;
        }
        if (client.currentScreen != null || !HudBridge.beginTabInteraction(client)) {
            return false;
        }
        client.setScreen(new TabInteractionScreen());
        return true;
    }
    public static boolean isOpen() {
        return MinecraftClient.getInstance().currentScreen instanceof TabInteractionScreen;
    }
    public static void finishForSelection() {
        MinecraftClient client = MinecraftClient.getInstance();
        HudBridge.endTabInteraction();
        if (client.currentScreen instanceof TabInteractionScreen) {
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
    public void tick() {
        boolean held = MinecraftRuntime.active().playerListPressed(MinecraftClient.getInstance());
        if (!held) {
            playerListReleased = true;
        } else if (playerListReleased && !notePanel.editing()) {
            close();
        }
    }
    @Override
    public boolean keyPressed(KeyInput input) {
        if (notePanel.editing()) {
            PlayerNoteEditor.Result result = notePanel.handleKey(input);
            if (result == PlayerNoteEditor.Result.SAVE) {
                saveNote();
            } else if (result == PlayerNoteEditor.Result.CANCEL) {
                closeNoteEditor();
            }
            return true;
        }
        int key = input.getKeycode();
        if (key == 256 || key == KeyBindingBridge.openKeyCode()) {
            close();
        }
        return true;
    }
    @Override
    public boolean charTyped(CharInput input) {
        return notePanel.editing() ? notePanel.handleChar(input) : true;
    }
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (notePanel.editing() && click.button() == 0 && notePanel.click(click.x(), click.y(), textRenderer)) {
            return true;
        }
        if (click.button() == 1) {
            return handleRightClick(click.x(), click.y());
        }
        if (click.button() != 0) {
            return true;
        }
        if (contextPlayer != null) {
            if (handleContextClick(click.x(), click.y())) {
                return true;
            }
            closeContextMenu();
            return true;
        }
        PlayerIdentity player = HudBridge.frozenPlayerAt(click.x(), click.y());
        if (player != null) {
            TierLookupClient.openPlayerFromInteractiveTab(player);
        }
        return true;
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount > 0) {
            HudBridge.cycleFrozenTabKit(-1);
        } else if (verticalAmount < 0) {
            HudBridge.cycleFrozenTabKit(1);
        }
        return true;
    }
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (notePanel.editing()) {
            HudBridge.lockFrozenPreview(notePanel.player());
        }
        int hoverX = contextPlayer == null && !notePanel.editing() ? mouseX : -10000;
        int hoverY = contextPlayer == null && !notePanel.editing() ? mouseY : -10000;
        boolean drawn = HudBridge.renderFrozenTab(context, MinecraftClient.getInstance(), hoverX, hoverY);
        if (!drawn) {
            context.fill(6, 6, 250, 28, 0xE0141A20);
            context.drawTextWithShadow(textRenderer, "TierLookup TAB render failed", 12, 12, 0xFFFF8A8A);
        }
        if (contextPlayer != null) {
            renderContextMenu(context);
        }
        if (notePanel.editing()) {
            renderNoteEditor(context);
        }
    }
    private boolean handleRightClick(double x, double y) {
        if (contextPlayer != null) {
            closeContextMenu();
            return true;
        }
        PlayerIdentity player = HudBridge.frozenPlayerAt(x, y);
        if (player == null) {
            close();
            return true;
        }
        contextPlayer = player;
        contextX = (int) Math.round(x);
        contextY = (int) Math.round(y);
        contextLayout = null;
        return true;
    }
    private void renderContextMenu(DrawContext context) {
        ProfileService service = TierLookupClient.profileServiceInstance();
        if (service == null || contextPlayer == null) {
            return;
        }
        boolean russian = MinecraftBridge.russian(MinecraftClient.getInstance());
        boolean notesEnabled = TierLookupClient.configInstance() != null && TierLookupClient.configInstance().notesEnabled();
        boolean favorite = service.watchlisted(contextPlayer.uuid());
        contextLayout = PlayerQuickMenu.draw( context,
            textRenderer,
            contextX,
            contextY,
            width,
            height,
            contextPlayer.name(),
            favorite,
            notesEnabled,
            russian,
            mouseX,
            mouseY,
            0xFF66D9EF);
    }
    private boolean handleContextClick(double x, double y) {
        ProfileService service = TierLookupClient.profileServiceInstance();
        if (service == null || contextPlayer == null) {
            return false;
        }
        boolean notesEnabled = TierLookupClient.configInstance() != null && TierLookupClient.configInstance().notesEnabled();
        PlayerQuickMenu.Layout layout = contextLayout != null ? contextLayout : PlayerQuickMenu.layout(contextX, contextY, width, height, notesEnabled);
        PlayerQuickMenu.Action action = layout.actionAt(x, y);
        if (action == null) {
            return false;
        }
        PlayerIdentity player = contextPlayer;
        switch (action) {
            case FAVORITE -> {
                boolean next = !service.watchlisted(player.uuid());
                service.setWatchlisted(player.uuid(), next);
                if (next) {
                    service.markWatchViewed(player.uuid());
                }
                closeContextMenu();
            }
            case NOTE -> {
                notePanel.begin(player, service.playerNote(player.uuid()));
                closeContextMenu();
                HudBridge.lockFrozenPreview(player);
            }
            case MESSAGE -> {
                closeContextMenu();
                MinecraftClient client = MinecraftClient.getInstance();
                HudBridge.endTabInteraction();
                client.setScreen(null);
                MinecraftRuntime.active().openPrefilledChat(client, "/msg " + player.name() + " ");
            }
            case COPY_NAME -> {
                TierLookupClient.copyTextFromUi(player.name());
                closeContextMenu();
            }
        }
        return true;
    }
    private void renderNoteEditor(DrawContext context) {
        OverlayRenderer.TabPreviewBounds preview = HudBridge.frozenPreviewBounds();
        int width = preview == null ? 220 : preview.w();
        int x = preview == null ? Math.max(6, this.width - width - 6) : preview.x();
        int y = preview == null ? 32 : Math.min(height - PlayerNotePanel.EDIT_HEIGHT - 4, preview.y() + preview.h() + 2);
        notePanel.draw(context, textRenderer, x, y, width, notePanel.player().uuid(), "");
    }
    private void saveNote() {
        ProfileService service = TierLookupClient.profileServiceInstance();
        if (service != null && notePanel.player() != null) {
            service.setPlayerNote(notePanel.player().uuid(), notePanel.value());
        }
        closeNoteEditor();
    }
    private void closeNoteEditor() {
        notePanel.clear();
        HudBridge.unlockFrozenPreview();
    }
    private void closeContextMenu() {
        contextPlayer = null;
        contextLayout = null;
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
    @Override
    public void close() {
        notePanel.clear();
        closeContextMenu();
        HudBridge.unlockFrozenPreview();
        HudBridge.endTabInteraction();
        MinecraftClient.getInstance().setScreen(null);
        TierLookupClient.reconcileInputAfterModal();
    }
}
