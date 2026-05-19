package de.mhus.vance.toolpack.mail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Manual live-check against a real Zoho IMAP account. Disabled by
 * default — run with:
 *
 * <pre>
 *   mvn -pl vance-toolpack test -Dtest=ZohoImapLiveCheck \
 *       -Dvance.live.zoho=true
 * </pre>
 *
 * Password is read from {@code ~/tmp/key.txt}. The test prints the
 * folder list and the latest 5 message headers to stdout — no
 * assertions, since the inbox content is unknown.
 */
@EnabledIfSystemProperty(named = "vance.live.zoho", matches = "true")
class ZohoImapLiveCheck {

    @Test
    void connect_list_folders_and_recent_messages() throws IOException {
        String password = Files.readString(
                Path.of(System.getProperty("user.home"), "tmp", "key.txt")).strip();
        ImapClient imap = new ImapClient(ImapConfig.fromParameters(Map.of(
                "host", "imap.zoho.com",
                "port", 993,
                "tls", true,
                "user", "mike@mhus.de",
                "password", password,
                "defaultFolder", "INBOX",
                "timeoutSeconds", 20)));

        System.out.println("=== Folders ===");
        for (String f : imap.listFolders()) System.out.println("  " + f);

        System.out.println("=== Latest 5 INBOX messages ===");
        List<Map<String, Object>> rows = imap.listMessages("INBOX", 5, false, null);
        for (Map<String, Object> r : rows) {
            System.out.printf("  [%s] %s | from=%s | %s%n",
                    r.get("receivedAt"), r.get("subject"), r.get("from"),
                    Boolean.TRUE.equals(r.get("seen")) ? "read" : "UNREAD");
        }
    }
}
