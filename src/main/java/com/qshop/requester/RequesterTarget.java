package com.qshop.requester;

import com.qshop.shop.Shop;
import com.qshop.shop.ShopEntry;
import com.qshop.shop.ShopManager;
import com.qshop.shop.ShopTab;

import javax.annotation.Nullable;

/** Resolved live Q-shop target; the UUIDs are the persistent identity. */
public record RequesterTarget(Shop shop, ShopTab tab, ShopEntry entry,
                              int tabIndex, int entryIndex) {
    @Nullable
    public static RequesterTarget resolve(String shopUuid, String tabUuid, String entryUuid) {
        if (isBlank(shopUuid) || isBlank(tabUuid) || isBlank(entryUuid)) return null;
        Shop shop = ShopManager.byUuid(shopUuid);
        if (shop == null) return null;
        shop.ensureTabs();
        for (int tabIndex = 0; tabIndex < shop.tabs.size(); tabIndex++) {
            ShopTab tab = shop.tabs.get(tabIndex);
            if (!tabUuid.equals(tab.uuid)) continue;
            for (int entryIndex = 0; entryIndex < tab.entries.size(); entryIndex++) {
                ShopEntry entry = tab.entries.get(entryIndex);
                if (entryUuid.equals(entry.uuid)) {
                    return new RequesterTarget(shop, tab, entry, tabIndex, entryIndex);
                }
            }
            return null;
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
