package net.craftnepal.market.subcommands.stock;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.utils.SendMessage;
import net.craftnepal.market.stock.StockMarketEngine;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class Resume extends SubCommand {

    @Override
    public String getName() { return "resume"; }

    @Override
    public List<String> getAliases() { return null; }

    @Override
    public String getDescription() {
        return "Resume the stock market after a pause.";
    }

    @Override
    public String getSyntax() {
        return "/market admin stock resume";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("market.admin")) {
            SendMessage.sendPlayerMessage(player, "§cYou don't have permission.");
            return;
        }

        if (!StockMarketEngine.isPaused()) {
            SendMessage.sendPlayerMessage(player, "§eStock market is not paused.");
            return;
        }

        StockMarketEngine.resume();
        SendMessage.sendPlayerMessage(player, "§aStock market resumed.");
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        return null;
    }
}
