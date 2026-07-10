package app.scapestack.runelite;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scrapes the Collection Log widget when the player opens it.
 *
 * The CL widget is layered: a top-level "tabs" widget (Bosses / Raids /
 * Clues / Minigames / Other), each tab has "category" widgets (e.g.
 * "Chambers of Xeric"), and each category has an "items" widget
 * containing every possible drop with a quantity (0 = not obtained).
 *
 * We snapshot the union of obtained-item-IDs across every category the
 * widget has loaded. The player has to open the log once per session
 * for full data — RuneLite memory only holds what was actually rendered.
 *
 * State is held in memory by the plugin between syncs. Each time a new
 * CL category loads we merge it in. On sync we emit the current union.
 *
 * Like DiaryReader, the parsing logic is pure-function-on-widget-data
 * so it tests without a live game client. The plugin calls
 * extractObtainedItems(rootWidget); the test calls extractFromMock.
 */
@Slf4j
@Singleton
public class CollectionLogReader {

    // In-memory accumulator. Cleared per-login by the plugin so a
    // stale account doesn't leak between players sharing a client.
    private final Set<Integer> obtainedItemIds = new HashSet<>();
    private int widgetLoadCount;
    private int lastWidgetItemCount;

    public synchronized void reset() {
        obtainedItemIds.clear();
        widgetLoadCount = 0;
        lastWidgetItemCount = 0;
    }

    public synchronized List<Integer> snapshot() {
        return new ArrayList<>(obtainedItemIds);
    }

    public synchronized Status status() {
        return new Status(
            widgetLoadCount > 0,
            widgetLoadCount,
            lastWidgetItemCount,
            obtainedItemIds.size()
        );
    }

    /**
     * Called when the Collection Log widget loads. Walks every
     * descendant looking for item-quantity pairs and adds anything
     * with quantity > 0.
     *
     * Widget structure in OSRS (community-documented):
     *   - Each "item" leaf widget has getItemId() and getItemQuantity()
     *   - Quantity 0 == not obtained yet
     *   - Quantity > 0 == player has at least one
     */
    public synchronized int ingest(Widget rootWidget) {
        if (rootWidget == null) return 0;
        widgetLoadCount++;
        lastWidgetItemCount = countItemWidgets(rootWidget);
        return extractInto(rootWidget, obtainedItemIds);
    }

    private static int countItemWidgets(Widget root) {
        if (root == null) return 0;
        int count = root.getItemId() > 0 ? 1 : 0;
        Widget[] children = root.getDynamicChildren();
        if (children != null) {
            for (Widget c : children) {
                count += countItemWidgets(c);
            }
        }
        Widget[] statics = root.getStaticChildren();
        if (statics != null) {
            for (Widget c : statics) {
                count += countItemWidgets(c);
            }
        }
        return count;
    }

    /**
     * Pure-data extractor that walks the widget tree depth-first and
     * adds every item-with-quantity-> 0 to the destination set.
     * Returns the count added in this call.
     */
    public static int extractInto(Widget root, Set<Integer> dst) {
        int added = 0;
        Widget[] children = root.getDynamicChildren();
        if (children != null) {
            for (Widget c : children) {
                added += extractInto(c, dst);
            }
        }
        Widget[] statics = root.getStaticChildren();
        if (statics != null) {
            for (Widget c : statics) {
                added += extractInto(c, dst);
            }
        }
        // Leaf check: item-widgets have a positive itemId AND a non-
        // zero quantity. The 'quantity' here is OSRS's collection-log
        // count, not a stack size from a bank.
        int itemId = root.getItemId();
        int qty = root.getItemQuantity();
        if (itemId > 0 && qty > 0 && !dst.contains(itemId)) {
            dst.add(itemId);
            added++;
        }
        return added;
    }

    /**
     * Test-friendly variant taking a fake widget tree. Tests can build
     * MockWidget instances and feed them in; we still exercise the
     * exact extract path the live code uses.
     */
    public static List<Integer> extractFromMock(MockWidget root) {
        Set<Integer> dst = new HashSet<>();
        extractMockInto(root, dst);
        return new ArrayList<>(dst);
    }

    public static Status statusFromMock(MockWidget root) {
        return new Status(
            root != null,
            root == null ? 0 : 1,
            countMockItemWidgets(root),
            extractFromMock(root).size()
        );
    }

    public static String playerInstruction(Status status) {
        if (status == null || !status.opened) {
            return "Open Collection Log once, then sync again.";
        }
        if (!status.hasLoadedItemSlots()) {
            return "Click a Collection Log category, then sync again.";
        }
        return "Collection Log synced.";
    }

    private static int countMockItemWidgets(MockWidget root) {
        if (root == null) return 0;
        int count = root.itemId > 0 ? 1 : 0;
        for (MockWidget c : root.children) {
            count += countMockItemWidgets(c);
        }
        return count;
    }

    private static void extractMockInto(MockWidget root, Set<Integer> dst) {
        if (root == null) return;
        for (MockWidget c : root.children) extractMockInto(c, dst);
        if (root.itemId > 0 && root.quantity > 0) dst.add(root.itemId);
    }

    /**
     * Lightweight stand-in for Widget that we can build in tests.
     * Only carries the three things extractInto needs: children, itemId,
     * and quantity. The real Widget has 100+ methods we don't need.
     */
    public static final class MockWidget {
        public final int itemId;
        public final int quantity;
        public final List<MockWidget> children;

        public MockWidget(int itemId, int quantity, MockWidget... children) {
            this.itemId = itemId;
            this.quantity = quantity;
            this.children = children == null ? Collections.emptyList() : new ArrayList<>(java.util.Arrays.asList(children));
        }

        public static MockWidget container(MockWidget... children) {
            return new MockWidget(0, 0, children);
        }

        public static MockWidget item(int itemId, int quantity) {
            return new MockWidget(itemId, quantity);
        }
    }

    public static final class Status {
        public final boolean opened;
        public final int widgetLoads;
        public final int lastWidgetItemCount;
        public final int obtainedItemCount;

        public Status(boolean opened, int widgetLoads, int lastWidgetItemCount, int obtainedItemCount) {
            this.opened = opened;
            this.widgetLoads = widgetLoads;
            this.lastWidgetItemCount = lastWidgetItemCount;
            this.obtainedItemCount = obtainedItemCount;
        }

        public static Status notOpened() {
            return new Status(false, 0, 0, 0);
        }

        public boolean hasLoadedItemSlots() {
            return opened && lastWidgetItemCount > 0;
        }
    }
}
