package com.github.alexthe666.iceandfire.entity;

import com.github.alexthe666.iceandfire.entity.ai.*;
import com.github.alexthe666.iceandfire.event.EventLiving;
import com.google.common.base.Predicate;
import net.ilexiconn.llibrary.server.animation.Animation;
import net.ilexiconn.llibrary.server.animation.AnimationHandler;
import net.ilexiconn.llibrary.server.animation.IAnimatedEntity;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class EntityCockatrice extends EntityTameable implements IAnimatedEntity, IBlacklistedFromStatues {

    private int animationTick;
    private Animation currentAnimation;
    private static final DataParameter<Boolean> HEN = EntityDataManager.<Boolean>createKey(EntityCockatrice.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> STARING = EntityDataManager.<Boolean>createKey(EntityCockatrice.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> TARGET_ENTITY = EntityDataManager.<Integer>createKey(EntityCockatrice.class, DataSerializers.VARINT);
    private boolean isSitting;
    public float sitProgress;
    private boolean isStaring;
    public float stareProgress;
    public int ticksStaring = 0;
    public static final Animation ANIMATION_JUMPAT = Animation.create(30);
    public static final Animation ANIMATION_WATTLESHAKE = Animation.create(20);
    public static final Animation ANIMATION_BITE = Animation.create(15);
    public static final Animation ANIMATION_SPEAK = Animation.create(10);
    private CockatriceAIStareAttack aiStare;
    private EntityAIAttackMelee aiMelee;
    private boolean isMeleeMode = false;
    private EntityLivingBase targetedEntity;
    private int clientSideAttackTime;
    public static final float VIEW_RADIUS = 0.6F;
    public EntityCockatrice(World worldIn) {
        super(worldIn);
        this.setSize(0.96F, 0.9F);
    }

    protected void initEntityAI() {
        this.tasks.addTask(1, new EntityAISwimming(this));
        this.tasks.addTask(2, this.aiSit = new EntityAISit(this));
        this.tasks.addTask(3, aiStare = new CockatriceAIStareAttack(this, 1.0D, 0, 15.0F));
        this.tasks.addTask(3, aiMelee = new EntityAIAttackMelee(this, 1.5D, false));
        this.tasks.addTask(4, new CockatriceAIWander(this, 1.0D));
        this.tasks.addTask(5, new CockatriceAIAggroLook(this));
        this.tasks.addTask(6, new EntityAIWatchClosest(this, EntityLivingBase.class, 6.0F));
        this.tasks.addTask(7, new EntityAILookIdle(this));
        this.targetTasks.addTask(1, new EntityAIOwnerHurtByTarget(this));
        this.targetTasks.addTask(2, new EntityAIOwnerHurtTarget(this));
        this.targetTasks.addTask(3, new EntityAIHurtByTarget(this, false, new Class[0]));
        this.targetTasks.addTask(4, new CockatriceAITarget(this, EntityLivingBase.class, true, new Predicate<Entity>() {
            @Override
            public boolean apply(@Nullable Entity entity) {
                return (entity instanceof EntityMob || entity instanceof EntityPlayer || EventLiving.isAnimaniaFerret(entity)) && !EventLiving.isAnimaniaChicken(entity);
            }
        }));
        this.tasks.removeTask(aiMelee);
    }

    private boolean canUseStareOn(Entity entity) {
        if (entity instanceof IBlacklistedFromStatues && !((IBlacklistedFromStatues) entity).canBeTurnedToStone()) {
            return false;
        }
        return true;
    }

    private void switchAI(boolean melee) {
        if (melee) {
            this.tasks.removeTask(aiStare);
            if (aiMelee != null) {
                this.tasks.addTask(3, aiMelee);
            }
            this.isMeleeMode = true;
        } else {
            this.tasks.removeTask(aiMelee);
            if (aiStare != null) {
                this.tasks.addTask(3, aiStare);
            }
            this.isMeleeMode = false;
        }
    }

    @Override
    public boolean attackEntityAsMob(Entity entityIn) {
        if (this.isStaring()) {
            return false;
        }
        if (this.getRNG().nextBoolean()) {
            if (this.getAnimation() != ANIMATION_JUMPAT && this.getAnimation() != ANIMATION_BITE) {
                this.setAnimation(ANIMATION_JUMPAT);
            }
            return false;
        } else {
            if (this.getAnimation() != ANIMATION_BITE && this.getAnimation() != ANIMATION_JUMPAT) {
                this.setAnimation(ANIMATION_BITE);
            }
            return false;
        }

    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.3D);
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(40.0D);
        this.getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(5.0D);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(32.0D);
    }

    public boolean canMove() {
        return !this.isSitting() && !(this.getAnimation() == ANIMATION_JUMPAT && this.getAnimationTick() < 10);
    }


    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(HEN, false);
        this.dataManager.register(STARING, false);
        this.dataManager.register(TARGET_ENTITY, Integer.valueOf(0));
    }

    public void setTargetedEntity(int entityId) {
        this.dataManager.set(TARGET_ENTITY, Integer.valueOf(entityId));
    }

    public boolean hasTargetedEntity() {
        return ((Integer) this.dataManager.get(TARGET_ENTITY)).intValue() != 0;
    }

    @Nullable
    public EntityLivingBase getTargetedEntity() {
        if (!this.hasTargetedEntity()) {
            return null;
        } else if (this.world.isRemote) {
            if (this.targetedEntity != null) {
                return this.targetedEntity;
            } else {
                Entity entity = this.world.getEntityByID(((Integer) this.dataManager.get(TARGET_ENTITY)).intValue());
                if (entity instanceof EntityLivingBase) {
                    this.targetedEntity = (EntityLivingBase) entity;
                    return this.targetedEntity;
                } else {
                    return null;
                }
            }
        } else {
            return this.getAttackTarget();
        }
    }

    public void notifyDataManagerChange(DataParameter<?> key) {
        super.notifyDataManagerChange(key);
        if (TARGET_ENTITY.equals(key)) {
            this.clientSideAttackTime = 0;
            this.targetedEntity = null;
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        tag.setBoolean("Hen", this.isHen());
        tag.setBoolean("Staring", this.isStaring());
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag);
        this.setHen(tag.getBoolean("Hen"));
        this.setStaring(tag.getBoolean("Staring"));
    }

    public boolean isSitting() {
        if (world.isRemote) {
            boolean isSitting = (((Byte) this.dataManager.get(TAMED)).byteValue() & 1) != 0;
            this.isSitting = isSitting;
            return isSitting;
        }
        return isSitting;
    }

    public void setSitting(boolean sitting) {
        if (!world.isRemote) {
            this.isSitting = sitting;
        }
        byte b0 = ((Byte) this.dataManager.get(TAMED)).byteValue();
        if (sitting) {
            this.dataManager.set(TAMED, Byte.valueOf((byte) (b0 | 1)));
        } else {
            this.dataManager.set(TAMED, Byte.valueOf((byte) (b0 & -2)));
        }
    }

    public void fall(float distance, float damageMultiplier) {
    }

    @Override
    @Nullable
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        livingdata = super.onInitialSpawn(difficulty, livingdata);
        this.setHen(this.getRNG().nextBoolean());
        return livingdata;
    }

    public void setHen(boolean hen) {
        this.dataManager.set(HEN, hen);
    }

    public boolean isHen() {
        return this.dataManager.get(HEN);
    }

    public boolean isStaring() {
        if (world.isRemote) {
            return this.isStaring = this.dataManager.get(STARING);
        }
        return isStaring;
    }

    public void setStaring(boolean staring) {
        this.dataManager.set(STARING, staring);
        if (!world.isRemote) {
            this.isStaring = staring;
        }
    }

    public void forcePreyToLook(EntityLiving mob) {
        mob.getLookHelper().setLookPosition(this.posX, this.posY + (double) this.getEyeHeight(), this.posZ, (float) mob.getHorizontalFaceSpeed(), (float) mob.getVerticalFaceSpeed());
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if(!world.isRemote){
            if(this.getAttackTarget() == null || this.getAttackTarget().isDead){
               this.setTargetedEntity(0);
            }else if(this.isStaring() || this.shouldStareAttack(this.getAttackTarget())){
                this.setTargetedEntity(this.getAttackTarget().getEntityId());
            }
        }
        if (this.getAnimation() == ANIMATION_BITE && this.getAttackTarget() != null && this.getAnimationTick() == 7) {
            double dist = this.getDistanceSq(this.getAttackTarget());
            if (dist < 2) {
                this.getAttackTarget().attackEntityFrom(DamageSource.causeMobDamage(this), ((int) this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue()));
            }
        }
        if (this.getAnimation() == ANIMATION_JUMPAT && this.getAttackTarget() != null) {
            double dist = this.getDistanceSq(this.getAttackTarget());
            double d0 = this.getAttackTarget().posX - this.posX;
            double d1 = this.getAttackTarget().posZ - this.posZ;
            float leap = MathHelper.sqrt(d0 * d0 + d1 * d1);
            if (dist <= 16.0D && this.onGround && this.getAnimationTick() == 10) {

                if ((double) leap >= 1.0E-4D) {
                    this.motionX += d0 / (double) leap * 0.800000011920929D + this.motionX * 0.20000000298023224D;
                    this.motionZ += d1 / (double) leap * 0.800000011920929D + this.motionZ * 0.20000000298023224D;
                }
                this.motionY = 0.5F;
            }
            if (dist < 1 && this.getAnimationTick() > 10) {
                this.getAttackTarget().attackEntityFrom(DamageSource.causeMobDamage(this), ((int) this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue()));
                if ((double) leap >= 1.0E-4D) {
                    this.getAttackTarget().motionX += d0 / (double) leap * 0.800000011920929D + this.motionX * 0.20000000298023224D;
                    this.getAttackTarget().motionZ += d1 / (double) leap * 0.800000011920929D + this.motionZ * 0.20000000298023224D;
                }
            }
        }
        boolean sitting = isSitting();
        if (sitting && sitProgress < 20.0F) {
            sitProgress += 0.5F;
        } else if (!sitting && sitProgress > 0.0F) {
            sitProgress -= 0.5F;
        }

        boolean staring = isStaring();
        if (staring && stareProgress < 20.0F) {
            stareProgress += 0.5F;
        } else if (!staring && stareProgress > 0.0F) {
            stareProgress -= 0.5F;
        }
        if (!world.isRemote) {
            if (staring) {
                ticksStaring++;
            } else {
                ticksStaring = 0;
            }
        }
        if (!world.isRemote && staring && (this.getAttackTarget() == null || this.shouldMelee())) {
            this.setStaring(false);
        }
        if (this.getAttackTarget() != null) {
            this.getLookHelper().setLookPosition(this.getAttackTarget().posX, this.getAttackTarget().posY + (double) this.getAttackTarget().getEyeHeight(), this.getAttackTarget().posZ, (float) this.getHorizontalFaceSpeed(), (float) this.getVerticalFaceSpeed());
            if (!shouldMelee() && this.getAttackTarget() instanceof EntityLiving && !(this.getAttackTarget() instanceof EntityPlayer)) {
                forcePreyToLook((EntityLiving) this.getAttackTarget());
            }
        }
        if (!this.world.isRemote && this.getAttackTarget() != null && EntityGorgon.isEntityLookingAt(this, this.getAttackTarget(), VIEW_RADIUS) && EntityGorgon.isEntityLookingAt(this.getAttackTarget(), this, VIEW_RADIUS)) {
            if (!shouldMelee()) {
                if (!this.isStaring()) {
                    this.setStaring(true);
                } else {
                    this.getAttackTarget().addPotionEffect(new PotionEffect(MobEffects.WITHER, 10, 2));
                }
            }
        }
        if (!this.world.isRemote && this.getAttackTarget() == null && this.getRNG().nextInt(300) == 0 && this.getAnimation() == NO_ANIMATION) {
            this.setAnimation(ANIMATION_WATTLESHAKE);
        }
        if (!this.world.isRemote) {
            if (shouldMelee() && !this.isMeleeMode) {
                switchAI(true);
            }
            if (!shouldMelee() && this.isMeleeMode) {
                switchAI(false);
            }
        }

        if (this.world.isRemote &&this.getTargetedEntity() != null && EntityGorgon.isEntityLookingAt(this, this.getTargetedEntity(), VIEW_RADIUS) && EntityGorgon.isEntityLookingAt(this.getTargetedEntity(), this, VIEW_RADIUS)) {
            if (this.hasTargetedEntity()) {
                if (this.clientSideAttackTime < this.getAttackDuration()) {
                    ++this.clientSideAttackTime;
                }

                EntityLivingBase entitylivingbase = this.getTargetedEntity();

                if (entitylivingbase != null) {
                    this.getLookHelper().setLookPositionWithEntity(entitylivingbase, 90.0F, 90.0F);
                    this.getLookHelper().onUpdateLook();
                    double d5 = (double) this.getAttackAnimationScale(0.0F);
                    double d0 = entitylivingbase.posX - this.posX;
                    double d1 = entitylivingbase.posY + (double) (entitylivingbase.height * 0.5F) - (this.posY + (double) this.getEyeHeight());
                    double d2 = entitylivingbase.posZ - this.posZ;
                    double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
                    d0 = d0 / d3;
                    d1 = d1 / d3;
                    d2 = d2 / d3;
                    double d4 = this.rand.nextDouble();

                    while (d4 < d3) {
                        d4 += 1.8D - d5 + this.rand.nextDouble() * (1.7D - d5);
                        this.world.spawnParticle(EnumParticleTypes.SPELL_MOB, this.posX + d0 * d4, this.posY + d1 * d4 + (double) this.getEyeHeight(), this.posZ + d2 * d4, 0.0D, 0.0D, 0.0D, new int[]{3484199});
                    }
                }
            }
        }
        AnimationHandler.INSTANCE.updateAnimations(this);
    }

    public float getAttackAnimationScale(float f) {
        return ((float)this.clientSideAttackTime + f) / (float)this.getAttackDuration();
    }

    public boolean shouldStareAttack(Entity entity){
        return this.getDistance(entity) > 5;
    }
    public int getAttackDuration() {
        return 80;
    }

    private boolean shouldMelee() {
        boolean blindness = this.isPotionActive(MobEffects.BLINDNESS) || this.getAttackTarget() != null && this.getAttackTarget().isPotionActive(MobEffects.BLINDNESS);
        if (this.getAttackTarget() != null) {
            if (this.getDistance(this.getAttackTarget()) < 4D || EventLiving.isAnimaniaFerret(this.getAttackTarget()) || blindness || !this.canUseStareOn(this.getAttackTarget())) {
                return this.getAnimation() == NO_ANIMATION;
            }
        }
        return false;
    }

    @Override
    public void travel(float strafe, float forward, float vertical) {
        if (!this.canMove() && !this.isBeingRidden()) {
            strafe = 0;
            forward = 0;
        }
        super.travel(strafe, forward, vertical);
    }

    @Nullable
    @Override
    public EntityAgeable createChild(EntityAgeable ageable) {
        return null;
    }

    @Override
    public int getAnimationTick() {
        return animationTick;
    }

    @Override
    public void setAnimationTick(int tick) {
        animationTick = tick;
    }

    @Override
    public Animation getAnimation() {
        return currentAnimation;
    }

    @Override
    public void setAnimation(Animation animation) {
        currentAnimation = animation;
    }

    @Override
    public Animation[] getAnimations() {
        return new Animation[]{NO_ANIMATION, ANIMATION_JUMPAT, ANIMATION_WATTLESHAKE, ANIMATION_BITE, ANIMATION_SPEAK};
    }

    @Override
    public boolean canBeTurnedToStone() {
        return false;
    }

    public boolean isTargetBlocked(Vec3d target) {
        if (target != null) {
            RayTraceResult rayTrace = world.rayTraceBlocks(new Vec3d(this.getPosition()), target, false);
            if (rayTrace != null && rayTrace.hitVec != null) {
                BlockPos pos = new BlockPos(rayTrace.hitVec);
                if (!world.isAirBlock(pos)) {
                    return true;
                }
            }
        }
        return false;
    }
}