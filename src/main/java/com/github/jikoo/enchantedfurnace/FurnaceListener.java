package com.github.jikoo.enchantedfurnace;

import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Listener for all furnace-related events.
 *
 * @author Jikoo
 */
public class FurnaceListener implements Listener {

	private final EnchantedFurnace plugin;

	public FurnaceListener(final EnchantedFurnace plugin) {
		this.plugin = plugin;
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	public void onFurnaceConsumeFuel(final FurnaceBurnEvent event) {
		Furnace furnace = this.plugin.getFurnace(event.getBlock());
		if (furnace == null) {
			return;
		}
		if (furnace.isPaused() && furnace.resume()) {
			event.setCancelled(true);
			return;
		}
		// Unbreaking causes furnace to burn for longer, increase burn time
		int burnTime = this.getCappedTicks(event.getBurnTime(), -furnace.getBurnModifier(), 0.2);
		// Efficiency causes furnace to burn faster, reduce burn time to match smelt rate increase
		burnTime = this.getCappedTicks(burnTime, furnace.getCookModifier(), 0.5);
		event.setBurnTime(burnTime);
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	public void onItemSmelt(final FurnaceSmeltEvent event) {
		final Furnace furnace = this.plugin.getFurnace(event.getBlock());
		if (furnace == null) {
			return;
		}

		if (furnace.getFortune() > 0) {
			boolean listContains = this.plugin.getFortuneList().contains(event.getSource().getType().name());
			if (this.plugin.isBlacklist() ? !listContains : listContains) {
				this.applyFortune(event, furnace);
			}
		}

		if (furnace.shouldPause(event)) {
			new BukkitRunnable() {
				@Override
				public void run() {
					furnace.pause();
				}
			}.runTask(this.plugin);
		} else if (ReflectionUtils.areFurnacesSupported()) {
			final int cookModifier = furnace.getCookModifier();
			if (cookModifier != 0) {
				new BukkitRunnable() {
					@Override
					public void run() {
						BlockState state = event.getBlock().getState();
						if (!(state instanceof org.bukkit.block.Furnace)) {
							return;
						}
						org.bukkit.block.Furnace tile = (org.bukkit.block.Furnace) state;
						// PaperSpigot compatibility: lag compensation patch can set furnaces to negative cook time.
						if (tile.getCookTime() < 0) {
							tile.setCookTime((short) 0);
							tile.update();
						}
						ReflectionUtils.setFurnaceCookTime(furnace.getBlock(), FurnaceListener.this.getCappedTicks(200, cookModifier, 0.5));
					}
				}.runTask(this.plugin);
			}
		}
	}

	private int getCappedTicks(final int baseTicks, final int baseModifier, final double fractionModifier) {
		return Math.max(1, Math.min(Short.MAX_VALUE, this.getModifiedTicks(baseTicks, baseModifier, fractionModifier)));
	}

	private int getModifiedTicks(final int baseTicks, final int baseModifier, final double fractionModifier) {
		if (baseModifier == 0) {
			return baseTicks;
		}
		if (baseModifier > 0) {
			return (int) (baseTicks / (1 + baseModifier * fractionModifier));
		}
		return (int) (baseTicks * (1 + -baseModifier * fractionModifier));
	}

	@SuppressWarnings("deprecation")
	private void applyFortune(final FurnaceSmeltEvent event, final Furnace furnace) {
		FurnaceInventory inventory = furnace.getFurnaceTile().getInventory();
		// Fortune result quantities are weighted - 0 bonus has 2 weight, any other number has 1 weight
		// To easily recreate this, a random number between -1 inclusive and fortune level exclusive is generated.
		int bonus = ThreadLocalRandom.current().nextInt(furnace.getFortune() + 2) - 1;
		if (bonus <= 0) {
			return;
		}
		// Check extras against max - 1 because of guaranteed single output
		if (inventory.getResult() != null && inventory.getResult().getAmount() + bonus > inventory.getResult().getType().getMaxStackSize() - 1) {
			bonus = inventory.getResult().getType().getMaxStackSize() - 1 - inventory.getResult().getAmount();
			if (bonus <= 0) {
				return;
			}
		}
		ItemStack newResult = null;
		if (inventory.getResult() == null) {
			Iterator<Recipe> iterator = Bukkit.recipeIterator();
			while (iterator.hasNext()) {
				Recipe recipe = iterator.next();
				if (!(recipe instanceof FurnaceRecipe)) {
					continue;
				}
				ItemStack input = ((FurnaceRecipe) recipe).getInput();
				if (input.getType() != inventory.getSmelting().getType()) {
					continue;
				}
				if (input.getData().getData() == -1) {
					// Inexact match, continue iterating
					newResult = new ItemStack(recipe.getResult());
				}
				if (input.getData().equals(inventory.getSmelting().getData())) {
					// Exact match
					newResult = new ItemStack(recipe.getResult());
					break;
				}
				// Incorrect data, not a match
				continue;
			}
			if (newResult == null) {
				this.plugin.getLogger().warning("Unable to obtain fortune result for MaterialData "
						+ inventory.getSmelting().getData() + ". Please report this error.");
				return;
			}
		} else {
			newResult = inventory.getResult().clone();
		}
		newResult.setAmount(1 + bonus);
		event.setResult(newResult);
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onBlockPlace(final BlockPlaceEvent event) {
		if (event.getItemInHand() != null && event.getItemInHand().getType() == Material.FURNACE) {
			this.plugin.createFurnace(event.getBlock(), event.getItemInHand());
		}
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	public void onBlockBreak(final BlockBreakEvent event) {
		if (event.getBlock().getType() != Material.FURNACE && event.getBlock().getType() != Material.BURNING_FURNACE) {
			return;
		}
		ItemStack is = this.plugin.destroyFurnace(event.getBlock());
		if (is != null) {
			Player player = event.getPlayer();
			if (player.getGameMode() != GameMode.CREATIVE
					&& !event.getBlock().getDrops(player.getItemInHand()).isEmpty()) {
				event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), is);
			}
			event.setCancelled(true);
			event.getBlock().setType(Material.AIR);
		}
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onInventoryClick(final InventoryClickEvent event) {
		if (event.getView().getTopInventory().getType() != InventoryType.FURNACE) {
			return;
		}
		this.furnaceContentsChanged(event.getView().getTopInventory());
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onInventoryMoveItem(final InventoryMoveItemEvent e) {
		Inventory furnace;
		if (e.getDestination().getType() == InventoryType.FURNACE) {
			furnace = e.getDestination();
		} else if (e.getSource().getType() == InventoryType.FURNACE) {
			furnace = e.getSource();
		} else {
			return;
		}
		this.furnaceContentsChanged(furnace);
	}

	private void furnaceContentsChanged(final Inventory inventory) {
		if (!(inventory.getHolder() instanceof org.bukkit.block.Furnace)) {
			return;
		}
		final org.bukkit.block.Furnace tile = (org.bukkit.block.Furnace) inventory.getHolder();
		final Furnace furnace = this.plugin.getFurnace(tile.getBlock());
		if (furnace == null) {
			return;
		}
		final int cookModifier = furnace.getCookModifier();
		if ((!ReflectionUtils.areFurnacesSupported() || cookModifier != 0) && !furnace.canPause()) {
			return;
		}
		new BukkitRunnable() {
			@Override
			public void run() {
				if (ReflectionUtils.areFurnacesSupported() && cookModifier != 0) {
					// PaperSpigot compatibility: lag compensation patch can set furnaces to negative cook time.
					if (tile.getCookTime() < 0) {
						tile.setCookTime((short) 0);
						tile.update();
					}
					ReflectionUtils.setFurnaceCookTime(furnace.getBlock(), FurnaceListener.this.getCappedTicks(200, cookModifier, 0.5));
				}
				if (furnace.isPaused()) {
					furnace.resume();
				} else if (furnace.shouldPause(null)) {
					furnace.pause();
				}
			}
		}.runTask(this.plugin);
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onChunkLoad(final ChunkLoadEvent event) {
		this.plugin.loadChunkFurnaces(event.getChunk());
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onChunkUnload(final ChunkUnloadEvent event) {
		this.plugin.unloadChunkFurnaces(event.getChunk());
	}
}