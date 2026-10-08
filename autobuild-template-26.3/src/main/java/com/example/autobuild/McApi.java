package com.example.autobuild;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * TẤT CẢ các lời gọi API Minecraft hay đổi tên giữa các phiên bản được gom vào đây.
 * Nếu bạn gặp lỗi biên dịch khi lên phiên bản mới, thường chỉ cần sửa file này.
 */
public final class McApi {
    private McApi() {}

    public static Block blockByName(String name) {
        Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(name));
        return (b == Blocks.AIR && !name.equals("minecraft:air")) ? null : b;
    }

    public static void msg(String s) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.sendSystemMessage(Component.literal("[AutoBuild] " + s));
    }

    public static InteractionResult use(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        return mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
    }

    /** Dùng vật phẩm đang cầm (xô nước/lava: đặt hoặc múc theo hướng nhìn). */
    public static InteractionResult useItem(Minecraft mc, LocalPlayer p) {
        return mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    public static void breakStep(Minecraft mc, BlockPos pos, boolean first) {
        if (first) mc.gameMode.startDestroyBlock(pos, Direction.UP);
        else mc.gameMode.continueDestroyBlock(pos, Direction.UP);
    }

    public static void stopBreaking(Minecraft mc) {
        mc.gameMode.stopDestroyBlock();
    }

    /** Chọn dụng cụ phá nhanh nhất trên thanh nhanh (hotbar). */
    public static void holdBestTool(LocalPlayer p, BlockState s) {
        Inventory inv = p.getInventory();
        int best = inv.getSelectedSlot();
        float bs = inv.getItem(best).getDestroySpeed(s);
        for (int i = 0; i < 9; i++) {
            float sp = inv.getItem(i).getDestroySpeed(s);
            if (sp > bs) { bs = sp; best = i; }
        }
        inv.setSelectedSlot(best);
    }

    public static Item itemByName(String name) {
        Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(name));
        return it == Items.AIR ? null : it;
    }

    public static void swing(LocalPlayer p) {
        p.swing(InteractionHand.MAIN_HAND);
    }

    public static void setRot(LocalPlayer p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    /** Click trong một menu (rương, túi đồ). Đây là chỗ API hay bị đổi tên. */
    public static void click(Minecraft mc, LocalPlayer p, int containerId, int slot, int button, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(containerId, slot, button, type, p);
    }

    public static int count(LocalPlayer p, Item item) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** Đưa vật phẩm lên tay. Trả về true nếu đã cầm sẵn trên tay (có thể cần 1 tick để hoán đổi từ túi đồ). */
    public static boolean hold(Minecraft mc, LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                click(mc, p, p.inventoryMenu.containerId, i, inv.getSelectedSlot(), ClickType.SWAP);
                return false;
            }
        }
        return false;
    }

    public static void releaseKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
    }
}
