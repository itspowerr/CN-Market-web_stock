package net.craftnepal.market.subcommands.apikey;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.stock.ApiKeyManager;
import net.craftnepal.market.utils.SendMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class Renew extends SubCommand {

    @Override
    public String getName() {
        return "renew";
    }

    @Override
    public List<String> getAliases() {
        return List.of("regenerate", "reset");
    }

    @Override
    public String getDescription() {
        return "Regenerate your API key (old one is immediately invalidated).";
    }

    @Override
    public String getSyntax() {
        return "/market apikey renew";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return;

        String uuid = player.getUniqueId().toString();
        ApiKeyManager.revokeKey(uuid);

        String plainKey = ApiKeyManager.createApiKey(uuid);
        if (plainKey == null) {
            SendMessage.sendPlayerMessage(player, "&cFailed to renew API key. Check console for errors.");
            return;
        }

        long expiresAt = ApiKeyManager.getExpiryTimestamp(uuid);

        SendMessage.sendPlayerMessage(player, "&a============================================");
        SendMessage.sendPlayerMessage(player, "&aAPI Key Renewed! Your new key:");
        SendMessage.sendPlayerMessage(player, "");
        SendMessage.sendPlayerMessage(player, " &f" + plainKey);
        SendMessage.sendPlayerMessage(player, "");
        SendMessage.sendPlayerMessage(player, "&c&lWARNING: This key will ONLY be shown once!");
        SendMessage.sendPlayerMessage(player, "&7Old key has been invalidated.");
        SendMessage.sendPlayerMessage(player, "&7Expires: &f" + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(expiresAt)));
        SendMessage.sendPlayerMessage(player, "&a============================================");
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        return null;
    }
}
