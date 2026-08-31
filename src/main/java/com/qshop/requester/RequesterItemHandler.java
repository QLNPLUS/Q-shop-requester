package com.qshop.requester;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Directional view used by hoppers and pipes. */
final class RequesterItemHandler implements IItemHandler {
    private final IItemHandler delegate;
    private final boolean canInsert;
    private final boolean canExtract;

    RequesterItemHandler(IItemHandler delegate, boolean canInsert, boolean canExtract) {
        this.delegate = delegate;
        this.canInsert = canInsert;
        this.canExtract = canExtract;
    }

    @Override public int getSlots() { return delegate.getSlots(); }
    @Override public ItemStack getStackInSlot(int slot) { return delegate.getStackInSlot(slot); }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return canInsert ? delegate.insertItem(slot, stack, simulate) : stack.copy();
    }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return canExtract ? delegate.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
    }
    @Override public int getSlotLimit(int slot) { return delegate.getSlotLimit(slot); }
    @Override public boolean isItemValid(int slot, ItemStack stack) {
        return canInsert && delegate.isItemValid(slot, stack);
    }
}
