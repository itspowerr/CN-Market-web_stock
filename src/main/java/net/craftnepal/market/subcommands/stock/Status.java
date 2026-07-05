package net.craftnepal.market.subcommands.stock;

import me.kodysimpson.simpapi.command.SubCommand;
import net.craftnepal.market.utils.SendMessage;
import net.craftnepal.market.stock.StockMarketEngine;
import net.craftnepal.market.managers.DatabaseManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class Status extends SubCommand {

    @Override
    public String getName() { return "status"; }

    @Override
    public List<String> getAliases() { return null; }

    @Override
    public String getDescription() {
        return "Show stock market engine status and stats.";
    }

    @Override
    public String getSyntax() {
        return "/market admin stock status";
    }

    @Override
    public void perform(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("market.admin")) {
            SendMessage.sendPlayerMessage(player, "§cYou don't have permission.");
            return;
        }

        String status = StockMarketEngine.isPaused() ? "§cPAUSED" : "§aRUNNING";
        SendMessage.sendPlayerMessage(player, "§7--- §bStock Market Status §7---");
        SendMessage.sendPlayerMessage(player, "§7Engine: " + status);

        int openOrders = 0;
        int tradesToday = 0;
        try (Connection conn = DatabaseManager.getConnection()) {
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM stock_orders WHERE status IN ('OPEN', 'PARTIALLY_FILLED')");
            if (rs.next()) openOrders = rs.getInt(1);
            rs.close();

            long dayAgo = System.currentTimeMillis() - 86400000L;
            var ps = conn.prepareStatement("SELECT COUNT(*) FROM stock_trades WHERE timestamp > ?");
            ps.setLong(1, dayAgo);
            rs = ps.executeQuery();
            if (rs.next()) tradesToday = rs.getInt(1);
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            // ignore
        }

        SendMessage.sendPlayerMessage(player, "§7Open orders: §f" + openOrders);
        SendMessage.sendPlayerMessage(player, "§7Trades (24h): §f" + tradesToday);
    }

    @Override
    public List<String> getSubcommandArguments(Player player, String[] args) {
        return null;
    }
}
