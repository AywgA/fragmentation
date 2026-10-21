package com.fragmentation.entity;

import com.fragmentation.config.ExplosiveConfig;
import com.fragmentation.explosive.FlashbangHandler;
import com.fragmentation.explosive.GrenadeExplosionHandler;
import com.fragmentation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 通用爆炸物投掷实体：承载任意被注册为闪光弹/手雷的物品，
 * 具备真实的出手初速、方块反弹、落地滚动摩擦与引信倒计时；
 * 飞行外观为该物品自身。
 */
public class ExplosiveThrowableEntity extends ThrowableItemProjectile {
    private String configId = "";
    private int fuse = 60;
    private boolean resting;
    private int lastBounceTick = -10;

    public ExplosiveThrowableEntity(EntityType<? extends ExplosiveThrowableEntity> type, Level level) {
        super(type, level);
    }

    public ExplosiveThrowableEntity(LivingEntity thrower, Level level) {
        super(ModEntities.EXPLOSIVE_THROWABLE.get(), thrower, level);
    }

    /** 由投掷事件调用：绑定配置ID、外观、引信并赋予出手速度。 */
    public void launch(Player thrower, String id, ItemStack visual, int fuseTicks) {
        this.configId = id;
        setItem(visual);
        this.fuse = fuseTicks;
        this.resting = false;
        shootFromRotation(thrower, thrower.getXRot(), thrower.getYRot(), 0.0F, 1.4F, 0.9F);
    }

    /**
     * 接管原模组投掷物：复制其位置/速度/朝向/所有者，继承其飞行
     * 状态（不重新赋初速），引信从接管时开始计时。用于在原模组
     * 投掷动作完整播放（进度条/拉环）后无缝替换实体。
     */
    public void takeOver(Projectile original, String id, ItemStack visual, int fuseTicks) {
        this.configId = id;
        setItem(visual);
        this.fuse = fuseTicks;
        this.resting = false;
        this.setOwner(original.getOwner());
        this.setPos(original.position());
        this.setDeltaMovement(original.getDeltaMovement());
        this.setXRot(original.getXRot());
        this.setYRot(original.getYRot());
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
    }

    @Override
    protected Item getDefaultItem() {
        if (!configId.isEmpty()) {
            try {
                String itemId = ExplosiveConfig.itemIdOf(configId);
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
                if (item != null && item != Items.AIR) {
                    return item;
                }
            } catch (Exception ignored) {
            }
        }
        return Items.SNOWBALL;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (resting) {
            Vec3 velocity = getDeltaMovement();
            if (velocity.horizontalDistanceSqr() > 1.0E-5) {
                setDeltaMovement(velocity.x * 0.8, velocity.y, velocity.z * 0.8);
            }
        }
        if (fuse > 0) {
            fuse--;
        }
        if (fuse <= 0) {
            detonate();
        }
    }

    private void detonate() {
        // 爆心不得位于实体方块内部（否则冲击波从墙内起爆被墙
        // 自身截断，两侧都造不成伤害）：沿上方寻找最近空气位置。
        BlockPos blockPos = BlockPos.containing(position());
        BlockPos.MutableBlockPos cursor =
            new BlockPos.MutableBlockPos(
                blockPos.getX(), blockPos.getY(), blockPos.getZ());
        for (int i = 0; i < 8; i++) {
            if (!level().getBlockState(cursor).canOcclude()) {
                if (i > 0) {
                    setPos(
                        position().x,
                        cursor.getY() + 0.1,
                        position().z
                    );
                }
                break;
            }
            cursor.move(Direction.UP);
        }

        ExplosiveConfig config =
            com.fragmentation.FragmentationMod.getExplosiveConfig();
        ExplosiveConfig.ExplosiveEntry entry = config.getEntry(configId);
        ExplosiveConfig.ExplosiveType type = entry != null
            ? entry.type
            : ExplosiveConfig.ExplosiveType.GRENADE;

        if (type == ExplosiveConfig.ExplosiveType.FLASH) {
            FlashbangHandler.onDetonate(this, configId);
        } else {
            GrenadeExplosionHandler.onGrenadeExplode(this, configId);
        }
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        Vec3 velocity = getDeltaMovement();
        Direction face = hit.getDirection();
        Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 reflected = velocity
            .subtract(normal.scale(2.0 * velocity.dot(normal)))
            .scale(0.45);
        setDeltaMovement(reflected);

        // 命中点把实体整个推出墙面外（碰撞半径 0.25 + 余量），
        // 否则碰撞箱大半嵌进墙里，看起来"卡进墙中"且爆炸位置
        // 紧贴墙面导致伤害被墙截断。
        Vec3 location = hit.getLocation();
        setPos(
            location.x + normal.x * 0.3,
            location.y + normal.y * 0.3,
            location.z + normal.z * 0.3
        );

        resting = face == Direction.UP && reflected.length() < 0.25;
        if (resting) {
            setDeltaMovement(reflected.x, 0.0, reflected.z);
        }

        if (tickCount - lastBounceTick > 2) {
            level().playSound(
                null,
                BlockPos.containing(location),
                SoundEvents.STONE_HIT,
                SoundSource.PLAYERS,
                0.5F,
                0.8F + random.nextFloat() * 0.3F
            );
            lastBounceTick = tickCount;
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        Vec3 velocity = getDeltaMovement();
        setDeltaMovement(velocity.scale(0.3).add(0.0, 0.25, 0.0));
        Vec3 location = hit.getLocation();
        if (velocity.lengthSqr() > 1.0E-6) {
            Vec3 reverse = velocity.normalize().scale(-0.02);
            setPos(location.x + reverse.x, location.y + reverse.y, location.z + reverse.z);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("ConfigId", configId);
        tag.putInt("Fuse", fuse);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.configId = tag.getString("ConfigId");
        this.fuse = tag.getInt("Fuse");
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
