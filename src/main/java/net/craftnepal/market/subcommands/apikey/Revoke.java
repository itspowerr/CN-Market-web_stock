package net.craftnepal.market.subcommands.apikey;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.stock.ApiKeyManager;
import net.craftnepal.market.utils.SendMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class Revoke extends SubCommand {

    @Override
    public String getName() {
        return "revoke";
    }

    @Override
    public List<String> getAliases() {
        return List.of("remove", "delete", "invalidate");
    }

    @Override
    public String getDescription() {
        return "Immediately invalidate your current API key.";
    }

    @Override
    public String getSyntax() {
        return "/market apikey revoke";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return;

        String uuid = player.getUniqueId().toString();

        if (!ApiKeyManager.hasApiKey(uuid)) {
            SendMessage.sendPlayerMessage(player, "&cYou don't have an API key to revoke.");
            return;
        }

        ApiKeyManager.revokeKey(uuid);
        SendMessage.sendPlayerMessage(player, "&aYour API key has been revoked. Any active sessions are now invalid.");
        SendMessage.sendPlayerMessage(player, "&7Use &f/market apikey generate &7to create a new one.");
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        return null;
    }
}
