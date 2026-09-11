package network.darkland.db.sql.mariadb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbDialectTest {

    @Test
    void generatedIndexColumnStaysWithinMariaDbIdentifierLimit() {
        List<String> statements = new MariaDbDialect().createIndexStatements(
                "nexus_players", "i".repeat(63), "score");

        assertTrue(statements.getFirst().contains("`" + "i".repeat(59) + "_col`"));
        assertFalse(statements.getFirst().contains("`" + "i".repeat(63) + "_col`"));
    }
}
