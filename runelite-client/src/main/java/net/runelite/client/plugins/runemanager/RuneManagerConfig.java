package net.runelite.client.plugins.runemanager;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runemanagerconfig")
public interface RuneManagerConfig extends Config {
    @ConfigItem(
            keyName = "username",
            name = "Username",
            description = "RuneManager username. NOT RuneScape OR RuneLite username!",
            position = 0
    )
    default String username() { return "Simon"; }

    @ConfigItem(
            keyName = "password",
            name = "Password",
            description = "RuneManager password. NOT RuneScape or RuneLite password",
            secret = true,
            position = 1
    )
    default String password() { return "runemanager1234"; }
}
