package net.runelite.client.plugins.runemanager;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@AllArgsConstructor
@Getter
@EqualsAndHashCode
public class CollectionItem {
    private final int id;
    private final String name;
    private int quantity;
}
