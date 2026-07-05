package net.craftnepal.market.subcommands.stock;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.stock.StockMarketEngine;
import net.craftnepal.market.utils.SendMessage;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Inject extends SubCommand {

    @Override
    public String getName() { return "inject"; }

    @Override
    public List<String> getAliases() { return null; }

    @Override
    public String getDescription() {
        return "Inject a test trade into the market. Sets the last price.";
    }

    @Override
    public String getSyntax() {
        return "/market admin stock inject <ticker> <price> <quantity>";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("market.admin")) {
            SendMessage.sendPlayerMessage(player, "§cYou don't have permission.");
            return;
        }

        if (args.length < 4) {
            SendMessage.sendPlayerMessage(player, "§cUsage: " + getSyntax());
            return;
        }

        String ticker = args[1].toUpperCase();
        double price;
        int quantity;

        try {
            price = Double.parseDouble(args[2]);
            quantity = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            SendMessage.sendPlayerMessage(player, "§cInvalid number format.");
            return;
        }

        if (price <= 0 || quantity <= 0) {
            SendMessage.sendPlayerMessage(player, "§cPrice and quantity must be positive.");
            return;
        }

        // Validate ticker is a valid material
        try {
            Material.valueOf(ticker);
        } catch (IllegalArgumentException e) {
            SendMessage.sendPlayerMessage(player, "§cInvalid ticker: " + ticker);
            return;
        }

        var trade = StockMarketEngine.injectTrade(ticker, price, quantity);
        if (trade == null) {
            SendMessage.sendPlayerMessage(player, "§cFailed to inject trade. Engine not loaded.");
        } else {
            SendMessage.sendPlayerMessage(player, "§aInjected trade: §7" + quantity + "x " + ticker + " @ $" + String.format("%.2f", price));
        }
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        if (args.length == 2) {
            return Stream.of(Material.values())
                    .filter(Material::isItem)
                    .map(Material::name)
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
        }
        if (args.length == 3) return List.of("<price>");
        if (args.length == 4) return List.of("<quantity>");
        return null;
    }
}
