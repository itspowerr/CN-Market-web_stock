package net.craftnepal.market.subcommands.stock;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.utils.SendMessage;
import net.craftnepal.market.stock.StockMarketEngine;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class Pause extends SubCommand {

    @Override
    public String getName() { return "pause"; }

    @Override
    public List<String> getAliases() { return null; }

    @Override
    public String getDescription() {
        return "Pause the stock market. All order matching stops.";
    }

    @Override
    public String getSyntax() {
        return "/market admin stock pause [reason]";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("market.admin")) {
            SendMessage.sendPlayerMessage(player, "§cYou don't have permission.");
            return;
        }

        if (StockMarketEngine.isPaused()) {
            SendMessage.sendPlayerMessage(player, "§eStock market is already paused.");
            return;
        }

        StockMarketEngine.pause();
        String reason = args.length > 1 ? String.join(" ", args).substring(args[0].length() + 1) : "No reason given";
        SendMessage.sendPlayerMessage(player, "§cStock market paused. Reason: §7" + reason);
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        return null;
    }
}
