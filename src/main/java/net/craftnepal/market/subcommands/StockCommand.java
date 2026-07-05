package net.craftnepal.market.subcommands;

import net.craftnepal.market.subcommands.stock.*;

public class StockCommand extends NestedCommand {

    public StockCommand() {
        registerSubCommand(new Pause());
        registerSubCommand(new Resume());
        registerSubCommand(new Inject());
        registerSubCommand(new Status());
    }

    @Override
    public String getRequiredPermission() {
        return "market.admin";
    }

    @Override
    public String getName() {
        return "stock";
    }

    @Override
    public String getDescription() {
        return "Manage the stock market engine.";
    }

    @Override
    public String getSyntax() {
        return "/market admin stock <subcommand>";
    }

    @Override
    public java.util.List<String> getAliases() {
        return null;
    }
}
