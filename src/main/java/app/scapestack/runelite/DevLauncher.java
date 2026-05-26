package app.scapestack.runelite;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Side-loads {@link ScapestackSyncPlugin} into a RuneLite dev session.
 *
 * RuneLite's --developer-mode CLI flag is unreliable across versions
 * (depends on a SafeMode whitelist file). Calling
 * {@code ExternalPluginManager.loadBuiltin(...)} before
 * {@code RuneLite.main(...)} is the canonical pattern from the
 * RuneLite docs (https://github.com/runelite/example-plugin).
 *
 * Invoked via './gradlew runClient' — see plugin/build.gradle.
 */
public final class DevLauncher {

    private DevLauncher() {}

    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(ScapestackSyncPlugin.class);
        RuneLite.main(args);
    }
}
