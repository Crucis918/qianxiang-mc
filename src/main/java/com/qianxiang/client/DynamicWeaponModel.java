package com.qianxiang.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 动态外貌物品的 {@link BakedModel} 包裹——让产物按组件数据即时换肤。
 * <p>
 * 在 {@code ModelEvent.ModifyBakingResult} 里替换掉静态 json 模型：
 * </p>
 * <ul>
 *   <li>物品栈带 {@code qianxiang:composed_attributes} 时，
 *       {@link #getRenderPasses(ItemStack, boolean)} 返回一个「动态 pass」：
 *       quad 来自 {@link DynamicWeaponTexture} 按（形状×颜色×档位）hash 烘焙的挤出模型，
 *       {@link #getRenderTypes} 指向绑定动态纹理的 RenderType——
 *       物品栏、手持、掉落物、锻造台结果槽预览全部自动生效。</li>
 *   <li>无组件（旧存档/创造空壳）时不干预：quads / 渲染类型全部委托给原静态模型，
 *       静态纹理做兜底。</li>
 * </ul>
 * <p>仅客户端：经 {@link ClientSetup}（Dist.CLIENT）注册，服务端不加载。</p>
 */
public final class DynamicWeaponModel implements BakedModel {
    private final BakedModel delegate;

    private DynamicWeaponModel(BakedModel delegate) {
        this.delegate = delegate;
    }

    /**
     * 把 {@link DynamicWeaponTexture#SUPPORTED_ITEM_IDS} 里已存在模型的物品包上动态外貌。
     * 尚未注册的物品（如并行开发中的 spell_book）自动跳过，注册了即生效。
     */
    public static void wrap(Map<ModelResourceLocation, BakedModel> models) {
        for (String id : DynamicWeaponTexture.SUPPORTED_ITEM_IDS) {
            ModelResourceLocation mrl = ModelResourceLocation.inventory(
                    ResourceLocation.fromNamespaceAndPath("qianxiang", id));
            BakedModel original = models.get(mrl);
            if (original != null && !(original instanceof DynamicWeaponModel)) {
                models.put(mrl, new DynamicWeaponModel(original));
            }
        }
    }

    /** 有组件 → 动态 pass；无组件 → 自身（quads 委托静态模型，渲染类型走原版查找）。 */
    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        DynamicWeaponTexture.Variant variant = DynamicWeaponTexture.variantFor(stack);
        if (variant == null) {
            return List.of(this);
        }
        return List.of(new DynamicPass(variant, delegate.getParticleIcon()));
    }

    /** 相机变换委托给原模型（gui/手持/掉落物的位移缩放保持一致），但返回自身继续走我们的 pass 逻辑。 */
    @Override
    public BakedModel applyTransform(ItemDisplayContext transformType, PoseStack poseStack, boolean applyLeftHandTransform) {
        delegate.applyTransform(transformType, poseStack, applyLeftHandTransform);
        return this;
    }

    // ============================ 静态兜底：全部委托 ============================

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        return delegate.getQuads(state, direction, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return delegate.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return delegate.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return delegate.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return delegate.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return delegate.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return delegate.getOverrides();
    }

    // ============================ 动态 pass ============================

    /** 单个动态变体的渲染 pass：quad 指向动态纹理挤出模型，RenderType 绑定动态纹理。 */
    private static final class DynamicPass implements BakedModel {
        private final DynamicWeaponTexture.Variant variant;
        private final TextureAtlasSprite particle;

        DynamicPass(DynamicWeaponTexture.Variant variant, TextureAtlasSprite particle) {
            this.variant = variant;
            this.particle = particle;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
            return direction == null ? variant.quads : List.of();
        }

        /** 关键：本 pass 的渲染类型绑定动态纹理，而不是方块图集。 */
        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            return List.of(variant.renderType);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return false;
        }

        @Override
        public boolean usesBlockLight() {
            return false;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return particle;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }
}
