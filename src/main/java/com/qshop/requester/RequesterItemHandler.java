package com.qshop.requester;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * 方向性视图,供漏斗/管道按方向存取。
 *
 * <p>26.1.2 迁移:能力系统的类型由 {@code IItemHandler} 换成
 * {@code ResourceHandler<ItemResource>},因此本类改为实现后者。
 *
 * <p><b>为什么保留 {@code ItemStackHandler} 而非全面换用
 * {@code ItemStacksResourceHandler}</b>:已用 javap 查证 QShop 26.1.2 的
 * {@code QShopAddonApi.buy/sell/barter} 仍以 {@code IItemHandler} 为契约。
 * 保留 {@code ItemStackHandler} 后,RequesterService(约 25 处业务逻辑)、
 * RequesterBlock(掉落逻辑)、RequesterMenu(SlotItemHandler)全部无需改动,
 * 能力系统的变更被限制在本文件内。
 *
 * <p>{@code ItemStackHandler} 在 26.1.2 <b>并未实现</b> {@code ResourceHandler}
 * (它只有 IItemHandler / IItemHandlerModifiable / ValueIOSerializable),
 * 因此这里做显式桥接。
 *
 * <p>语义差异(按 26.1.2 javadoc 核对):
 * <ul>
 *   <li>旧 {@code insertItem} 返回<b>未插入的剩余</b>;新 {@code insert} 返回
 *       <b>已插入量</b>。两者相反,故此处做 {@code getCount() - 剩余} 换算。</li>
 *   <li>旧 {@code extractItem} 返回 {@code ItemStack};新 {@code extract} 返回
 *       <b>已提取量</b>,故取 {@code getCount()}。</li>
 *   <li>新 API 用 {@link TransactionContext} 取代 {@code simulate};
 *       此处用 {@link Transaction#openRoot()} 开事务并提交,等价旧代码的
 *       {@code simulate=false}。</li>
 *   <li>{@code resource} 必须非空、{@code amount} 必须非负,否则抛
 *       {@code IllegalArgumentException}。故显式拒绝空资源与负数量。</li>
 * </ul>
 */
final class RequesterItemHandler implements ResourceHandler<ItemResource> {
    private final ItemStackHandler delegate;
    private final boolean canInsert;
    private final boolean canExtract;

    RequesterItemHandler(ItemStackHandler delegate, boolean canInsert, boolean canExtract) {
        this.delegate = delegate;
        this.canInsert = canInsert;
        this.canExtract = canExtract;
    }

    @Override public int size() {
        return delegate.getSlots();
    }

    @Override public ItemResource getResource(int index) {
        return ItemResource.of(delegate.getStackInSlot(index));
    }

    @Override public long getAmountAsLong(int index) {
        return delegate.getStackInSlot(index).getCount();
    }

    @Override public long getCapacityAsLong(int index, ItemResource resource) {
        // 与 isValid 的要求一致:对无效资源返回 0。
        return isValid(index, resource) ? delegate.getSlotLimit(index) : 0L;
    }

    @Override public boolean isValid(int index, ItemResource resource) {
        // javadoc:resource 必须非空。
        if (resource == null || resource.isEmpty()) return false;
        return canInsert && delegate.isItemValid(index, stackFor(resource, 1));
    }

    @Override public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        if (resource == null || resource.isEmpty() || amount < 0) return 0;
        if (!canInsert || amount == 0) return 0;

        ItemStack toInsert = stackFor(resource, amount);
        // Transaction 实现 AutoCloseable:不 commit 则 close() 自动回滚,
        // 因此无需 abort()(该方法不存在)。
        try (Transaction tx = Transaction.openRoot()) {
            ItemStack remaining = delegate.insertItem(index, toInsert, false);
            tx.commit();
            // 新 API 返回"已插入量",旧 API 返回"剩余量" —— 相反,故相减。
            return amount - remaining.getCount();
        }
    }

    @Override public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        if (resource == null || resource.isEmpty() || amount < 0) return 0;
        if (!canExtract || amount == 0) return 0;

        ItemStack stored = delegate.getStackInSlot(index);
        // 只提取与请求资源同种的物品。
        if (stored.isEmpty() || !resource.matches(stored)) return 0;

        try (Transaction tx = Transaction.openRoot()) {
            ItemStack extracted = delegate.extractItem(index, amount, false);
            tx.commit();
            // 新 API 返回"已提取量"。
            return extracted.getCount();
        }
    }

    /** 由资源构造恰好 count 个的 ItemStack;count 会被夹到合法范围。 */
    private static ItemStack stackFor(ItemResource resource, int count) {
        ItemStack stack = new ItemStack(resource.getItem(), Mth.clamp(count, 1, 64));
        return stack;
    }
}
