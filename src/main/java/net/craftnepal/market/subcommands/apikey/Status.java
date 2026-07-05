package net.craftnepal.market.subcommands.apikey;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.stock.ApiKeyManager;
import net.craftnepal.market.stock.ApiKeyManager.ApiKeyData;
import net.craftnepal.market.utils.SendMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class Status extends SubCommand {

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public List<String> getAliases() {
        return List.of("info", "check");
    }

    @Override
    public String getDescription() {
        return "View your API key status and expiry information.";
    }

    @Override
    public String getSyntax() {
        return "/market apikey status";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return;

        String uuid = player.getUniqueId().toString();
        ApiKeyData data = ApiKeyManager.loadApiKey(uuid);

        if (data == null) {
            SendMessage.sendPlayerMessage(player, "&cYou don't have an API key. Use &f/market apikey generate &cto create one.");
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        SendMessage.sendPlayerMessage(player, "&7--- &bAPI Key Status &7---");
        SendMessage.sendPlayerMessage(player, "&7Expires: &f" + fmt.format(new Date(data.expiresAt)));

        if (data.isExpired()) {
            SendMessage.sendPlayerMessage(player, "&cYour API key has expired! Use /market apikey renew");
        } else {
            long daysLeft = (data.expiresAt - System.currentTimeMillis()) / (24L * 60L * 60L * 1000L);
            SendMessage.sendPlayerMessage(player, "&7Time remaining: &f" + daysLeft + " days");
        }

        if (data.totpEnabled) {
            SendMessage.sendPlayerMessage(player, "&72FA: &aEnabled");
        } else {
            SendMessage.sendPlayerMessage(player, "&72FA: &cNot set up (will be prompted on first login)");
        }

        if (data.hasValidSession()) {
            String sessionExpiry = fmt.format(new Date(data.sessionExpiresAt));
            SendMessage.sendPlayerMessage(player, "&7Active session: &aYes &7(expires " + sessionExpiry + ")");
        } else {
            SendMessage.sendPlayerMessage(player, "&7Active session: &cNone");
        }

        if (data.lastUsedAt > 0) {
            SendMessage.sendPlayerMessage(player, "&7Last used: &f" + fmt.format(new Date(data.lastUsedAt)));
        }
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        return null;
    }
}
