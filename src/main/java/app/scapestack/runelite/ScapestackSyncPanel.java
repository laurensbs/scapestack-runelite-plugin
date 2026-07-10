package app.scapestack.runelite;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

final class ScapestackSyncPanel extends PluginPanel {
    private static final Color BG = new Color(31, 25, 16);
    private static final Color PANEL = new Color(45, 36, 23);
    private static final Color BORDER = new Color(114, 86, 31);
    private static final Color GOLD = new Color(222, 173, 55);
    private static final Color TEXT = new Color(235, 226, 204);
    private static final Color MUTED = new Color(166, 150, 123);

    private final ScapestackSyncConfig config;
    private final ConfigManager configManager;
    private final Runnable syncNow;

    private final JLabel statusValue = valueLabel("Ready");
    private final JLabel lastSyncValue = valueLabel("Not synced yet");
    private final JLabel autoRefreshValue = valueLabel("Off");
    private final JLabel accountModeValue = valueLabel("Account mode unknown");
    private final JLabel playerValue = valueLabel("Log in to detect");
    private final JLabel bankValue = valueLabel("Bank checks off");
    private final JLabel nextActionValue = valueLabel("Press Sync now");
    private final JLabel collectionLogValue = valueLabel("");
    private final JButton bankToggle = primaryButton("Use bank checks");
    private final JPanel collectionLogRow = row("Collection Log", collectionLogValue);
    private final JPanel troubleshootingBody = card();

    ScapestackSyncPanel(
        ScapestackSyncConfig config,
        ConfigManager configManager,
        Runnable syncNow
    ) {
        this.config = config;
        this.configManager = configManager;
        this.syncNow = syncNow;

        setLayout(new BorderLayout());
        setBackground(BG);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG);

        content.add(header());
        content.add(Box.createVerticalStrut(10));
        content.add(syncCard());
        content.add(Box.createVerticalStrut(10));
        content.add(whatSyncsCard());
        content.add(Box.createVerticalStrut(10));
        content.add(troubleshootingCard());

        add(content, BorderLayout.NORTH);
        refresh();
    }

    static BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(24, 18, 10));
        g.fillRoundRect(2, 2, 28, 28, 8, 8);
        g.setColor(GOLD);
        g.drawRoundRect(3, 3, 26, 26, 7, 7);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("S", 10, 23);
        g.dispose();
        return image;
    }

    void setStatus(String status) {
        setLabel(statusValue, status);
    }

    void setLastSync(String lastSync) {
        setLabel(lastSyncValue, lastSync);
    }

    void setAccountMode(String accountMode) {
        setLabel(accountModeValue, accountMode);
    }

    void setPlayerName(String playerName) {
        setLabel(playerValue, playerName == null || playerName.isBlank() ? "Log in to detect" : playerName);
    }

    void setBankStatus(String bankStatus) {
        setLabel(bankValue, bankStatus);
    }

    void setCollectionLogStatus(String collectionLogStatus) {
        SwingUtilities.invokeLater(() -> {
            String text = collectionLogStatus == null ? "" : collectionLogStatus.trim();
            collectionLogValue.setText(text.isBlank() ? "-" : text);
            collectionLogRow.setVisible(shouldShowCollectionLogInstruction(text));
            revalidate();
            repaint();
        });
    }

    void setNextAction(String nextAction) {
        setLabel(nextActionValue, nextAction);
    }

    void refresh() {
        SwingUtilities.invokeLater(() -> {
            bankToggle.setText(config.syncBankItems() ? "Bank checks on" : "Bank checks off");
            autoRefreshValue.setText(config.autoSync()
                ? "Every " + ScapestackSyncPlugin.normalizedAutoSyncIntervalMinutes(config.autoSyncIntervalMinutes()) + " min"
                : "Off");
            revalidate();
            repaint();
        });
    }

    private JPanel header() {
        JPanel panel = card();
        panel.add(title("ScapeStack Sync"));
        panel.add(copy("Keeps your OSRS planner current from RuneLite."));
        return panel;
    }

    private JPanel syncCard() {
        JPanel panel = card();
        JButton syncButton = primaryButton("Sync now");
        syncButton.addActionListener(e -> syncNow.run());
        bankToggle.addActionListener(e -> {
            configManager.setConfiguration(
                ScapestackSyncPlugin.CONFIG_GROUP,
                ScapestackSyncPlugin.KEY_SYNC_BANK_ITEMS,
                !config.syncBankItems()
            );
            refresh();
        });
        JButton recommendedButton = secondaryButton("Use recommended sync");
        recommendedButton.addActionListener(e -> {
            configManager.setConfiguration(
                ScapestackSyncPlugin.CONFIG_GROUP,
                ScapestackSyncPlugin.KEY_AUTO_SYNC,
                true
            );
            configManager.setConfiguration(
                ScapestackSyncPlugin.CONFIG_GROUP,
                ScapestackSyncPlugin.KEY_SYNC_BANK_ITEMS,
                true
            );
            configManager.setConfiguration(
                ScapestackSyncPlugin.CONFIG_GROUP,
                ScapestackSyncPlugin.KEY_AUTO_SYNC_INTERVAL_MINUTES,
                ScapestackSyncPlugin.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES
            );
            configManager.setConfiguration(
                ScapestackSyncPlugin.CONFIG_GROUP,
                ScapestackSyncPlugin.KEY_CHAT_FEEDBACK,
                true
            );
            setStatus("Recommended sync on");
            refresh();
        });

        panel.add(row("Status", statusValue));
        panel.add(row("Player", playerValue));
        panel.add(row("Account mode", accountModeValue));
        panel.add(row("Last sync", lastSyncValue));
        panel.add(row("Auto refresh", autoRefreshValue));
        panel.add(row("Bank checks", bankValue));
        panel.add(row("Next action", nextActionValue));
        collectionLogRow.setVisible(false);
        panel.add(collectionLogRow);
        panel.add(Box.createVerticalStrut(8));
        panel.add(syncButton);
        panel.add(Box.createVerticalStrut(6));
        panel.add(recommendedButton);
        panel.add(Box.createVerticalStrut(6));
        panel.add(bankToggle);
        return panel;
    }

    private JPanel whatSyncsCard() {
        JPanel panel = card();
        panel.add(sectionTitle("Planner checks"));
        panel.add(copy("Skills, quests, diaries, Slayer task and bank readiness."));
        panel.add(copy("Recommended sync refreshes after login and then every 15 minutes while you play."));
        panel.add(copy("Turn bank checks off if you only want progress sync."));
        return panel;
    }

    private JPanel troubleshootingCard() {
        JPanel wrapper = card();
        JButton toggle = secondaryButton("Troubleshooting");
        troubleshootingBody.add(copy("If bank checks are empty, open your bank once and sync again."));
        troubleshootingBody.add(copy("If Collection Log is missing, open it once, then sync again."));
        troubleshootingBody.add(copy("If sync is rejected, use Reconnect player, then Sync now."));
        troubleshootingBody.setVisible(false);
        toggle.addActionListener(e -> {
            troubleshootingBody.setVisible(!troubleshootingBody.isVisible());
            wrapper.revalidate();
            wrapper.repaint();
        });
        wrapper.add(toggle);
        wrapper.add(Box.createVerticalStrut(6));
        wrapper.add(troubleshootingBody);
        return wrapper;
    }

    private static JPanel card() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            new EmptyBorder(10, 10, 10, 10)
        ));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JPanel row(String label, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(PANEL);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel key = new JLabel(label);
        key.setForeground(MUTED);
        key.setFont(key.getFont().deriveFont(Font.BOLD, 11f));
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(key, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(GOLD);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel copy(String text) {
        JLabel label = new JLabel("<html><body style='width:205px'>" + text + "</body></html>");
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel valueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        return label;
    }

    private static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(GOLD);
        button.setForeground(Color.BLACK);
        button.setFocusPainted(false);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return button;
    }

    private static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(PANEL);
        button.setForeground(TEXT);
        button.setFocusPainted(false);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return button;
    }

    private static void setLabel(JLabel label, String text) {
        SwingUtilities.invokeLater(() -> label.setText(text == null || text.isBlank() ? "-" : text));
    }

    private static boolean shouldShowCollectionLogInstruction(String text) {
        return text != null
            && !text.isBlank()
            && !"Collection Log synced.".equals(text);
    }
}
