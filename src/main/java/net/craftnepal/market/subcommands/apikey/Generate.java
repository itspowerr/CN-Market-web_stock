package net.craftnepal.market.subcommands.apikey;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.stock.ApiKeyManager;
import net.craftnepal.market.utils.SendMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class Generate extends SubCommand {

    @Override
    public String getName() {
        return "generate";
    }

    @Override
    public List<String> getAliases() {
        return List.of("create", "new");
    }

    @Override
    public String getDescription() {
        return "Generate a new API key for the stock market website.";
    }

    @Override
    public String getSyntax() {
        return "/market apikey generate";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return;

        String uuid = player.getUniqueId().toString();
        ApiKeyManager.revokeKey(uuid);

        String plainKey = ApiKeyManager.createApiKey(uuid);
        if (plainKey == null) {
            SendMessage.sendPlayerMessage(player, "&cFailed to generate API key. Check console for errors.");
            return;
        }

        long expiresAt = ApiKeyManager.getExpiryTimestamp(uuid);

        SendMessage.sendPlayerMessage(player, "&a============================================");
        SendMessage.sendPlayerMessage(player, "&aYour Stock Market API Key:");
        SendMessage.sendPlayerMessage(player, "");
        SendMessage.sendPlayerMessage(player, " &f" + plainKey);
        SendMessage.sendPlayerMessage(player, "");
        SendMessage.sendPlayerMessage(player, "&c&lWARNING: This key will ONLY be shown once!");
        SendMessage.sendPlayerMessage(player, "&7Expires: &f" + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(expiresAt)));
        SendMessage.sendPlayerMessage(player, "");
        SendMessage.sendPlayerMessage(player, "&7Use &f/market apikey status &7to view expiry info.");
        SendMessage.sendPlayerMessage(player, "&7Use &f/market apikey renew &7to generate a new key.");
        SendMessage.sendPlayerMessage(player, "&7Use &f/market apikey revoke &7to immediately invalidate.");
        SendMessage.sendPlayerMessage(player, "&a============================================");
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        return null;
    }
}
