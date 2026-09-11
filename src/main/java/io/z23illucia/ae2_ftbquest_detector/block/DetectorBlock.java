package io.z23illucia.ae2_ftbquest_detector.block;

import com.mojang.logging.LogUtils;
import io.z23illucia.ae2_ftbquest_detector.blockentity.DetectorBlockEntity;
import io.z23illucia.ae2_ftbquest_detector.network.DetectorNetwork;
import io.z23illucia.ae2_ftbquest_detector.network.DetectorOwnerPayload;
import io.z23illucia.ae2_ftbquest_detector.utility.DetectorStatusPolicy;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamDisplayNameResolver;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamOwnershipValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.annotation.Nullable;

@SuppressWarnings("null")
public class DetectorBlock extends Block implements EntityBlock {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public DetectorBlock() {
        super(BlockBehaviour.Properties.of().strength(0.8f));
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(@NotNull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new DetectorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state,
                                                                  @NotNull BlockEntityType<T> blockEntityType) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof DetectorBlockEntity detector) {
                detector.tick();
            }
        };
    }

    @Override
    public @NotNull InteractionResult use(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                          @NotNull Player player, @NotNull InteractionHand hand,
                                          @NotNull BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DetectorBlockEntity detector) {
            TeamOwnershipValidator.Status teamStatus = detector.getOwnerTeamStatus();
            DetectorStatusPolicy.State presentation = DetectorStatusPolicy.resolve(
                    teamStatus, detector.isNetworkConflict(), state.getValue(POWERED));
            if (presentation == DetectorStatusPolicy.State.NO_OWNER_TEAM) {
                sendActionBar(serverPlayer, Component.translatable("ae2-ftbquests-detector.detector.no_owner"));
                return InteractionResult.SUCCESS;
            }
            if (presentation == DetectorStatusPolicy.State.INVALID_OWNER_TEAM) {
                sendActionBar(serverPlayer, Component.translatable("ae2-ftbquests-detector.detector.invalid_owner"));
                return InteractionResult.SUCCESS;
            }
            if (presentation == DetectorStatusPolicy.State.TEMPORARILY_UNAVAILABLE) {
                sendActionBar(serverPlayer, Component.translatable("ae2-ftbquests-detector.detector.uncharged"));
                return InteractionResult.SUCCESS;
            }
            if (presentation == DetectorStatusPolicy.State.NETWORK_CONFLICT) {
                sendActionBar(serverPlayer, Component.translatable("ae2-ftbquests-detector.detector.network_conflict"));
                return InteractionResult.SUCCESS;
            }
            if (presentation == DetectorStatusPolicy.State.OFFLINE) {
                sendActionBar(serverPlayer, Component.translatable("ae2-ftbquests-detector.detector.uncharged"));
                return InteractionResult.SUCCESS;
            }

            if (detector.ownerTeamId != null) {
                try {
                    String rawTeamName = TeamDisplayNameResolver.resolveRawTeamName(
                            detector.ownerTeamId, detector.ownerTeamNameCache);
                    if (rawTeamName != null) {
                        DetectorNetwork.sendToPlayer(serverPlayer,
                                new DetectorOwnerPayload(detector.ownerTeamId, rawTeamName));
                        if (TeamDisplayNameResolver.isTeamNameTooShort(
                                detector.ownerTeamId, detector.ownerTeamNameCache, 3)
                                && detector.shortNameWarnedPlayers.add(serverPlayer.getUUID())) {
                            serverPlayer.displayClientMessage(
                                    Component.translatable("ae2-ftbquests-detector.detector.team_name_too_short"),
                                    false);
                        }
                    }
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to resolve detector owner at {}", pos, exception);
                    sendActionBar(serverPlayer, Component.translatable("ae2-ftbquests-detector.detector.invalid_owner"));
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void sendActionBar(ServerPlayer player, Component message) {
        player.connection.send(new ClientboundSetActionBarTextPacket(message));
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                            @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        if (!level.isClientSide && placer instanceof ServerPlayer player) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DetectorBlockEntity detector) {
                detector.setOwnerTeam(player);
                detector.requestReconnect();
            }
        }
        super.setPlacedBy(level, pos, state, placer, stack);
    }

    @Override
    public void neighborChanged(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                @NotNull Block block, @NotNull BlockPos fromPos, boolean isMoving) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DetectorBlockEntity detector) {
                detector.requestReconnect();
            }
        }
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
    }
}
