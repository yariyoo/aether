package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        AetherBootstrapHooks.onBlockBreak(pos);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void onStartDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        AetherBootstrapHooks.onBlockBreakClick(Minecraft.getInstance(), pos);
    }

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void onContainerInput(int containerId, int slot, int button, ContainerInput input, Player player,
                                  CallbackInfo ci) {
        AetherBootstrapHooks.onContainerInput(containerId, slot, button, input);
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void onInteract(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
                            CallbackInfoReturnable<InteractionResult> cir) {
        AetherBootstrapHooks.onEntityInteract(entity, false);
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void onUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        AetherBootstrapHooks.onUseItem(Minecraft.getInstance(), "air (" + hand + ")");
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                             CallbackInfoReturnable<InteractionResult> cir) {
        AetherBootstrapHooks.onUseItem(Minecraft.getInstance(),
                "block " + hit.getBlockPos().toShortString() + " (" + hand + ")");
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void onAttack(Player player, Entity entity, CallbackInfo ci) {
        AetherBootstrapHooks.onEntityInteract(entity, true);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void onContinueDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        AetherBootstrapHooks.onBlockBreakClick(Minecraft.getInstance(), pos);
    }
}

