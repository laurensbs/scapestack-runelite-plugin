package app.scapestack.runelite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes the contract fixture through the same serializer used by RuneLite. */
public final class SyncPayloadFixtureWriter {
    private static final String CAPTURED_AT = "2026-07-18T12:34:56Z";

    private SyncPayloadFixtureWriter() {}

    static JsonObject fixturePayload() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.capturedAt = CAPTURED_AT;
        snapshot.accountType = "ironman";
        snapshot.questsCompleted = Arrays.asList("Cook's Assistant", "Dragon Slayer I");
        snapshot.skills = Arrays.asList(
            new GameStateReader.SkillLevel("Agility", 35, 22406),
            new GameStateReader.SkillLevel("Ranged", 70, 737627)
        );
        snapshot.diariesCompleted = Collections.singletonList(
            new GameStateReader.DiaryCompletion("Karamja", "Easy")
        );
        snapshot.collectionLogItemIds = Arrays.asList(11785, 11787, 12922);
        snapshot.collectionLogStatus = new CollectionLogReader.Status(
            true,
            2,
            100,
            3,
            "2026-07-18T12:30:00Z"
        );
        snapshot.bankItems = Arrays.asList(
            new GameStateReader.BankItem(1511, "Logs", 6),
            new GameStateReader.BankItem(2351, "Iron bar", 5)
        );
        snapshot.bankStatus = new GameStateReader.BankStatus(
            true,
            2,
            "2026-07-18T12:31:00Z",
            null
        );
        snapshot.slayer = new GameStateReader.SlayerState(
            132,
            51,
            47,
            19,
            "Dust devils",
            "Catacombs of Kourend",
            Arrays.asList(1, 2),
            Arrays.asList("Banshees", "Black demons")
        );
        Map<String, Integer> bossKc = new LinkedHashMap<>();
        bossKc.put("Vorkath", 48);
        bossKc.put("Zulrah", 0);
        snapshot.bossKc = bossKc;
        snapshot.bossKcStatus = BossKillCountReader.Result.available(
            bossKc,
            BossKillCountReader.defaultBossCatalog().size(),
            "2026-07-18T12:32:00Z",
            0
        );
        snapshot.coverage = PluginSnapshotContract.observedCoverage(snapshot);
        return ScapestackSyncPlugin.buildSyncPayload("Iron Lynx", snapshot, new Gson());
    }

    public static void main(String[] args) throws Exception {
        Path destination = args.length > 0
            ? Paths.get(args[0])
            : Paths.get("..", "tests", "fixtures", "plugin-sync-v3.json");
        Files.createDirectories(destination.toAbsolutePath().getParent());
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(fixturePayload()) + System.lineSeparator();
        Files.write(destination, json.getBytes(StandardCharsets.UTF_8));
    }
}
