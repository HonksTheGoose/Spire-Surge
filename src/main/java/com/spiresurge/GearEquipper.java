package com.spiresurge;

import com.spiresurge.data.MobPool;
import com.spiresurge.data.SurgeData;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Hands out gear and enchantments to a surge mob.
 *
 * Gear set chance = weight * (tierMultiplier * entryGearMultiplier) ^ tier, so one number
 * shifts the whole spread towards leather (below 1.0) or netherite (above 1.0).
 */
public final class GearEquipper {

    private GearEquipper() {
    }

    public static void equip(Mob mob, MobPool.Resolved entry, ServerLevel level) {
        RandomSource random = mob.getRandom();

        double multiplier = Math.max(0.01, SurgeConfig.TIER_MULTIPLIER.get() * entry.gearMultiplier());
        SurgeData.GearSet set = pickSet(random, multiplier);
        int tier = set == null ? 0 : set.tier();

        if (set != null) {
            double equipChance = SurgeConfig.SLOT_EQUIP_CHANCE.get();
            double dropChance = SurgeConfig.DROP_CHANCE.get();
            for (EquipmentSlot slot : entry.slots()) {
                List<ResourceLocation> options = set.items().get(slot);
                if (options == null || options.isEmpty() || random.nextDouble() >= equipChance) {
                    continue;
                }
                // Don't swap a skeleton's bow (or a pillager's crossbow) for a sword - they'd stop shooting.
                if (slot == EquipmentSlot.MAINHAND && mob.getItemBySlot(slot).getItem() instanceof ProjectileWeaponItem) {
                    continue;
                }
                ResourceLocation id = options.get(random.nextInt(options.size()));
                Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
                if (item.isEmpty() || item.get() == Items.AIR) {
                    continue; // item from a mod that isn't installed
                }
                mob.setItemSlot(slot, new ItemStack(item.get()));
                if (dropChance >= 0.0) {
                    mob.setDropChance(slot, (float) dropChance);
                }
            }
        }

        if (SurgeConfig.ENCHANT_CHANCE.get() > 0.0 && SurgeConfig.MAX_ENCHANTS_PER_ITEM.get() > 0) {
            for (EquipmentSlot slot : entry.slots()) {
                // re-read from the mob so we enchant the stack it actually holds
                enchant(level, random, mob.getItemBySlot(slot), tier);
            }
        }
    }

    private static SurgeData.GearSet pickSet(RandomSource random, double multiplier) {
        List<SurgeData.GearSet> sets = SurgeData.gearSets();
        int minTier = SurgeConfig.MIN_TIER.get();
        int maxTier = SurgeConfig.MAX_TIER.get();

        double[] weights = new double[sets.size()];
        double total = 0.0;
        for (int i = 0; i < sets.size(); i++) {
            SurgeData.GearSet s = sets.get(i);
            if (s.tier() < minTier || s.tier() > maxTier) {
                continue;
            }
            weights[i] = s.weight() * Math.pow(multiplier, s.tier());
            total += weights[i];
        }
        if (total <= 0.0) {
            return null;
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0.0 && weights[i] > 0.0) {
                return sets.get(i);
            }
        }
        return null;
    }

    private static void enchant(ServerLevel level, RandomSource random, ItemStack stack, int tier) {
        if (stack.isEmpty() || random.nextDouble() >= SurgeConfig.ENCHANT_CHANCE.get()) {
            return;
        }
        Registry<Enchantment> registry = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Set<ResourceLocation> whitelist = idSet(SurgeConfig.ENCHANT_WHITELIST.get());
        Set<ResourceLocation> blacklist = idSet(SurgeConfig.ENCHANT_BLACKLIST.get());

        int count = 1 + random.nextInt(SurgeConfig.MAX_ENCHANTS_PER_ITEM.get());
        List<Holder<Enchantment>> chosen = new ArrayList<>();

        for (int n = 0; n < count; n++) {
            List<Holder<Enchantment>> holders = new ArrayList<>();
            List<SurgeData.EnchantEntry> entries = new ArrayList<>();
            int total = 0;

            for (SurgeData.EnchantEntry e : SurgeData.enchantEntries()) {
                if (e.weight() <= 0 || tier < e.minTier() || blacklist.contains(e.enchantment())
                        || (!whitelist.isEmpty() && !whitelist.contains(e.enchantment()))) {
                    continue;
                }
                Optional<Holder.Reference<Enchantment>> found =
                        registry.getHolder(ResourceKey.create(Registries.ENCHANTMENT, e.enchantment()));
                if (found.isEmpty()) {
                    continue; // enchantment from a mod that isn't installed
                }
                Holder<Enchantment> holder = found.get();
                if (chosen.contains(holder) || !holder.value().isSupportedItem(stack) || !compatible(holder, chosen)) {
                    continue;
                }
                holders.add(holder);
                entries.add(e);
                total += e.weight();
            }
            if (total <= 0) {
                break;
            }

            int roll = random.nextInt(total);
            int index = 0;
            for (; index < entries.size() - 1; index++) {
                roll -= entries.get(index).weight();
                if (roll < 0) {
                    break;
                }
            }
            Holder<Enchantment> holder = holders.get(index);
            SurgeData.EnchantEntry entry = entries.get(index);

            int lo = Math.max(1, entry.minLevel());
            int hi = Math.max(lo, entry.maxLevel());
            int level1 = lo + random.nextInt(hi - lo + 1);
            level1 = (int) Math.round(level1 * SurgeConfig.ENCHANT_LEVEL_MULTIPLIER.get());
            int cap = SurgeConfig.ALLOW_OVER_MAX_LEVELS.get() ? 255 : holder.value().getMaxLevel();
            level1 = Mth.clamp(level1, 1, Math.max(1, cap));

            stack.enchant(holder, level1);
            chosen.add(holder);
        }
    }

    /** Respects "exclusive" enchantments (e.g. Protection vs Blast Protection, Sharpness vs Smite). */
    private static boolean compatible(Holder<Enchantment> candidate, List<Holder<Enchantment>> chosen) {
        for (Holder<Enchantment> other : chosen) {
            if (candidate.value().exclusiveSet().contains(other) || other.value().exclusiveSet().contains(candidate)) {
                return false;
            }
        }
        return true;
    }

    private static Set<ResourceLocation> idSet(String csv) {
        Set<ResourceLocation> out = new HashSet<>();
        for (String s : SurgeConfig.csv(csv)) {
            ResourceLocation id = ResourceLocation.tryParse(s);
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }
}
