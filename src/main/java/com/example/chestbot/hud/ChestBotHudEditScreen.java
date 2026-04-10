package com.example.chestbot.hud;

import com.example.chestbot.BotBridge;
import com.example.chestbot.ChestBotMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class ChestBotHudEditScreen extends Screen {

    private static final int SNAP_THRESHOLD = 6;
    private static final int RESIZE_HANDLE_SIZE = 12;
    private static final float MIN_SCALE = 0.5F;
    private static final float MAX_SCALE = 3.0F;

    private int chestHudX;
    private int chestHudY;
    private float chestHudScale;
    private int farmingHudX;
    private int farmingHudY;
    private float farmingHudScale;
    private boolean firstInit = true;
    private boolean dragging;
    private boolean resizing;
    private ResizeAnchor resizeAnchor = ResizeAnchor.NONE;
    private boolean wasLeftMouseDown;
    private boolean wasEscapeDown;
    private boolean wasTabDown;
    private int dragOffsetX;
    private int dragOffsetY;
    private double resizeFixedRight;
    private float resizeStartScale;
    private HudTarget activeTarget = HudTarget.CHEST;

    public ChestBotHudEditScreen() {
        super(Text.literal("창고지기 HUD 수정"));
    }

    @Override
    protected void init() {
        BotBridge bridge = ChestBotMod.getBridge();
        if (bridge == null || client == null) {
            return;
        }

        if (firstInit) {
            firstInit = false;
            chestHudX = bridge.getHudX();
            chestHudY = bridge.getHudY();
            chestHudScale = bridge.getHudScale();
            farmingHudX = bridge.getFarmingHudX();
            farmingHudY = bridge.getFarmingHudY();
            farmingHudScale = bridge.getFarmingHudScale();
        }

        clampToScreen();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateInput(mouseX, mouseY);

        context.fill(0, 0, width, height, 0x7A000000);
        context.fill(0, height / 2, width, height / 2 + 1, 0x33FFFFFF);
        context.fill(width / 2, 0, width / 2 + 1, height, 0x33FFFFFF);

        boolean hovered = isOverHud(mouseX, mouseY, activeTarget);
        ChestBotHudRenderer.HudBox hudBox = currentHudBox(activeTarget);
        if (hovered || dragging) {
            int borderColor = dragging ? 0xCCA9D7D0 : 0x66A9D7D0;
            context.fill(currentX(activeTarget) - 2, currentY(activeTarget) - 2, currentX(activeTarget) + scaledWidth(hudBox) + 2, currentY(activeTarget) + scaledHeight(hudBox) + 2, borderColor);
        }

        ChestBotHudRenderer.renderAllPreviews(context);

        drawResizeHandle(context, leftResizeHandleLeft(), resizeHandleTop(), resizing && resizeAnchor == ResizeAnchor.BOTTOM_LEFT);
        drawResizeHandle(context, rightResizeHandleLeft(), resizeHandleTop(), resizing && resizeAnchor == ResizeAnchor.BOTTOM_RIGHT);

        context.drawText(textRenderer,
                Text.literal(String.format("대상: %s | X: %d  Y: %d  Scale: %.2f", activeTarget.label, currentX(activeTarget), currentY(activeTarget), currentScale(activeTarget))),
                4, 4, 0xFFAAAAAA, false);

        Text hint = Text.literal("클릭으로 대상 선택 | 드래그 이동 | 아래 양쪽 모서리 크기 조절 | TAB 전환 | ESC 종료");
        int hintWidth = textRenderer.getWidth(hint);
        context.fill(0, height - 24, width, height, 0xAA000000);
        context.drawText(textRenderer, hint, (width - hintWidth) / 2, height - 16, 0xFFE0D4F0, true);

        super.render(context, mouseX, mouseY, delta);
    }

    private void updateInput(int mouseX, int mouseY) {
        if (client == null) {
            return;
        }

        long windowHandle = client.getWindow().getHandle();
        boolean leftMouseDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean escapeDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean tabDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;

        if (escapeDown && !wasEscapeDown) {
            close();
            wasEscapeDown = true;
            return;
        }

        if (tabDown && !wasTabDown) {
            activeTarget = activeTarget == HudTarget.CHEST ? HudTarget.FARMING : HudTarget.CHEST;
        }

        if (leftMouseDown && !wasLeftMouseDown) {
            if (isOverLeftResizeHandle(mouseX, mouseY)) {
                resizing = true;
                resizeAnchor = ResizeAnchor.BOTTOM_LEFT;
                dragging = false;
                resizeFixedRight = currentX(activeTarget) + scaledWidth(currentHudBox(activeTarget));
                resizeStartScale = currentScale(activeTarget);
            } else if (isOverRightResizeHandle(mouseX, mouseY)) {
                resizing = true;
                resizeAnchor = ResizeAnchor.BOTTOM_RIGHT;
                dragging = false;
                resizeFixedRight = currentX(activeTarget) + scaledWidth(currentHudBox(activeTarget));
                resizeStartScale = currentScale(activeTarget);
            } else if (isOverHud(mouseX, mouseY, HudTarget.CHEST)) {
                activeTarget = HudTarget.CHEST;
                dragging = true;
                resizing = false;
                resizeAnchor = ResizeAnchor.NONE;
                dragOffsetX = mouseX - currentX(activeTarget);
                dragOffsetY = mouseY - currentY(activeTarget);
            } else if (isOverHud(mouseX, mouseY, HudTarget.FARMING)) {
                activeTarget = HudTarget.FARMING;
                dragging = true;
                resizing = false;
                resizeAnchor = ResizeAnchor.NONE;
                dragOffsetX = mouseX - currentX(activeTarget);
                dragOffsetY = mouseY - currentY(activeTarget);
            } else {
                dragging = false;
                resizing = false;
                resizeAnchor = ResizeAnchor.NONE;
            }
        } else if (leftMouseDown && dragging) {
            ChestBotHudRenderer.HudBox hudBox = currentHudBox(activeTarget);
            int rawX = mouseX - dragOffsetX;
            int rawY = mouseY - dragOffsetY;
            int nextX = snapX(clamp(rawX, 0, Math.max(0, width - scaledWidth(hudBox))), hudBox);
            int nextY = snapY(clamp(rawY, 0, Math.max(0, height - scaledHeight(hudBox))), hudBox);
            setCurrentLayout(activeTarget, nextX, nextY, currentScale(activeTarget));
            applyLiveLayout();
        } else if (leftMouseDown && resizing) {
            float newScale = computeResizedScale(mouseX, mouseY);
            if (newScale != currentScale(activeTarget)) {
                setCurrentLayout(activeTarget, currentX(activeTarget), currentY(activeTarget), newScale);
                if (resizeAnchor == ResizeAnchor.BOTTOM_LEFT) {
                    ChestBotHudRenderer.HudBox resizedBox = currentHudBox(activeTarget);
                    setCurrentLayout(activeTarget, (int) Math.round(resizeFixedRight - scaledWidth(resizedBox)), currentY(activeTarget), currentScale(activeTarget));
                }
                clampToScreen();
                applyLiveLayout();
            }
        } else if (!leftMouseDown && wasLeftMouseDown) {
            dragging = false;
            resizing = false;
            resizeAnchor = ResizeAnchor.NONE;
        }

        wasLeftMouseDown = leftMouseDown;
        wasEscapeDown = escapeDown;
        wasTabDown = tabDown;
    }

    @Override
    public void close() {
        BotBridge bridge = ChestBotMod.getBridge();
        if (bridge != null) {
            bridge.applyHudLayout(chestHudX, chestHudY, chestHudScale);
            bridge.applyFarmingHudLayout(farmingHudX, farmingHudY, farmingHudScale);
            bridge.persistHudLayout();
            bridge.setHudEditMode(false);
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private boolean isOverHud(int mouseX, int mouseY, HudTarget target) {
        return currentHudBox(target).contains(mouseX, mouseY);
    }

    private boolean isOverLeftResizeHandle(int mouseX, int mouseY) {
        int left = leftResizeHandleLeft();
        int top = resizeHandleTop();
        return mouseX >= left && mouseX <= left + RESIZE_HANDLE_SIZE
                && mouseY >= top && mouseY <= top + RESIZE_HANDLE_SIZE;
    }

    private boolean isOverRightResizeHandle(int mouseX, int mouseY) {
        int left = rightResizeHandleLeft();
        int top = resizeHandleTop();
        return mouseX >= left && mouseX <= left + RESIZE_HANDLE_SIZE
                && mouseY >= top && mouseY <= top + RESIZE_HANDLE_SIZE;
    }

    private int snapX(int value, ChestBotHudRenderer.HudBox hudBox) {
        int rightEdge = width - scaledWidth(hudBox);
        int centerLine = rightEdge / 2;
        if (Math.abs(value) <= SNAP_THRESHOLD) return 0;
        if (Math.abs(value - centerLine) <= SNAP_THRESHOLD) return centerLine;
        if (Math.abs(value - rightEdge) <= SNAP_THRESHOLD) return rightEdge;
        return value;
    }

    private int snapY(int value, ChestBotHudRenderer.HudBox hudBox) {
        int bottomEdge = height - scaledHeight(hudBox);
        int centerLine = bottomEdge / 2;
        if (Math.abs(value) <= SNAP_THRESHOLD) return 0;
        if (Math.abs(value - centerLine) <= SNAP_THRESHOLD) return centerLine;
        if (Math.abs(value - bottomEdge) <= SNAP_THRESHOLD) return bottomEdge;
        return value;
    }

    private int leftResizeHandleLeft() {
        return currentX(activeTarget);
    }

    private int rightResizeHandleLeft() {
        return currentX(activeTarget) + scaledWidth(currentHudBox(activeTarget)) - RESIZE_HANDLE_SIZE;
    }

    private int resizeHandleTop() {
        return currentY(activeTarget) + scaledHeight(currentHudBox(activeTarget)) - RESIZE_HANDLE_SIZE;
    }

    private ChestBotHudRenderer.HudBox currentHudBox(HudTarget target) {
        if (client == null) {
            return new ChestBotHudRenderer.HudBox(currentX(target), currentY(target), 0, 0, currentScale(target));
        }
        return target == HudTarget.CHEST
                ? ChestBotHudRenderer.getHudBox(ChestBotMod.getBridge(), true, client, currentX(target), currentY(target), currentScale(target))
                : FarmingHudRenderer.getHudBox(ChestBotMod.getBridge(), true, client, currentX(target), currentY(target), currentScale(target));
    }

    private int scaledWidth(ChestBotHudRenderer.HudBox hudBox) {
        return Math.round(hudBox.width() * hudBox.scale());
    }

    private int scaledHeight(ChestBotHudRenderer.HudBox hudBox) {
        return Math.round(hudBox.height() * hudBox.scale());
    }

    private void clampToScreen() {
        for (HudTarget target : HudTarget.values()) {
            ChestBotHudRenderer.HudBox hudBox = currentHudBox(target);
            setCurrentLayout(target,
                    clamp(currentX(target), 0, Math.max(0, width - scaledWidth(hudBox))),
                    clamp(currentY(target), 0, Math.max(0, height - scaledHeight(hudBox))),
                    currentScale(target));
        }
    }

    private void applyLiveLayout() {
        BotBridge bridge = ChestBotMod.getBridge();
        if (bridge != null) {
            bridge.applyHudLayout(chestHudX, chestHudY, chestHudScale);
            bridge.applyFarmingHudLayout(farmingHudX, farmingHudY, farmingHudScale);
        }
    }

    private float computeResizedScale(int mouseX, int mouseY) {
        ChestBotHudRenderer.HudBox hudBox = currentHudBox(activeTarget);
        float baseWidth = Math.max(hudBox.width(), 1);
        float baseHeight = Math.max(hudBox.height(), 1);
        float widthScale;

        if (resizeAnchor == ResizeAnchor.BOTTOM_LEFT) {
            widthScale = (float) ((resizeFixedRight - mouseX) / baseWidth);
        } else {
            widthScale = (float) ((mouseX - currentX(activeTarget)) / baseWidth);
        }

        float heightScale = (float) ((mouseY - currentY(activeTarget)) / baseHeight);
        return clampScale(Math.max(widthScale, heightScale));
    }

    private int currentX(HudTarget target) {
        return target == HudTarget.CHEST ? chestHudX : farmingHudX;
    }

    private int currentY(HudTarget target) {
        return target == HudTarget.CHEST ? chestHudY : farmingHudY;
    }

    private float currentScale(HudTarget target) {
        return target == HudTarget.CHEST ? chestHudScale : farmingHudScale;
    }

    private void setCurrentLayout(HudTarget target, int x, int y, float scale) {
        if (target == HudTarget.CHEST) {
            chestHudX = x;
            chestHudY = y;
            chestHudScale = scale;
        } else {
            farmingHudX = x;
            farmingHudY = y;
            farmingHudScale = scale;
        }
    }

    private enum HudTarget {
        CHEST("창고 HUD"),
        FARMING("농작물 HUD");

        private final String label;

        HudTarget(String label) {
            this.label = label;
        }
    }

    private void drawResizeHandle(DrawContext context, int left, int top, boolean active) {
        int handleColor = active ? 0xFFE0D4F0 : 0xFFB388FF;
        context.fill(left, top, left + RESIZE_HANDLE_SIZE, top + RESIZE_HANDLE_SIZE, handleColor);
        context.fill(left + 2, top + 2, left + RESIZE_HANDLE_SIZE - 2, top + RESIZE_HANDLE_SIZE - 2, 0xCC101010);
    }

    private enum ResizeAnchor {
        NONE,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampScale(float value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }
}
