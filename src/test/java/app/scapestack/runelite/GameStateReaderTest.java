package app.scapestack.runelite;

import net.runelite.api.vars.AccountType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GameStateReaderTest {
    @Test
    public void normalizesRuneLiteAccountTypesForScapestack() {
        assertEquals("normal", GameStateReader.normalizeAccountType(AccountType.NORMAL));
        assertEquals("ironman", GameStateReader.normalizeAccountType(AccountType.IRONMAN));
        assertEquals("hardcore_ironman", GameStateReader.normalizeAccountType(AccountType.HARDCORE_IRONMAN));
        assertEquals("ultimate_ironman", GameStateReader.normalizeAccountType(AccountType.ULTIMATE_IRONMAN));
        assertEquals("group_ironman", GameStateReader.normalizeAccountType(AccountType.GROUP_IRONMAN));
        assertEquals("hardcore_group_ironman", GameStateReader.normalizeAccountType(AccountType.HARDCORE_GROUP_IRONMAN));
        assertEquals("normal", GameStateReader.normalizeAccountType(null));
    }
}
