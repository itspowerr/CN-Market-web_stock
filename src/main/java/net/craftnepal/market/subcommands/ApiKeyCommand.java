package net.craftnepal.market.subcommands;

import net.craftnepal.market.subcommands.apikey.*;

import java.util.List;

public class ApiKeyCommand extends NestedCommand {

    public ApiKeyCommand() {
        registerSubCommand(new Generate());
        registerSubCommand(new Renew());
        registerSubCommand(new Revoke());
        registerSubCommand(new Status());
    }

    @Override
    public String getRequiredPermission() {
        return "market.use";
    }

    @Override
    public String getName() {
        return "apikey";
    }

    @Override
    public List<String> getAliases() {
        return List.of("api", "key", "token");
    }

    @Override
    public String getDescription() {
        return "Manage your stock market API key.";
    }

    @Override
    public String getSyntax() {
        return "/market apikey <generate|renew|revoke|status>";
    }
}
