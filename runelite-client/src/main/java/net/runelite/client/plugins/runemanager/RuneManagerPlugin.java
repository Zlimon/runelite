package net.runelite.client.plugins.runemanager;

import com.google.gson.*;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import okhttp3.*;

import javax.inject.Inject;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j

@PluginDescriptor(
        name = "1RuneManager",
        description = "Official RuneManager plugin"
)

public class RuneManagerPlugin extends Plugin {
    @Provides
    RuneManagerConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(RuneManagerConfig.class);
    }

    @Inject
    private RuneManagerConfig runeManagerConfig;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ItemManager itemManager;

    private AvailableCollections[] collections;

    private String currentCollection;

    private boolean collectionLogOpen = false;

    public static final MediaType MEDIA_TYPE_MARKDOWN
            = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient httpClient = new OkHttpClient();

    private int previousCollectionLogValue;

    private static final Pattern UNIQUES_OBTAINED_PATTERN = Pattern.compile("Obtained: <col=(.+?)>([0-9]+)/([0-9]+)</col>");

    private static final Pattern KILL_COUNT_PATTERN = Pattern.compile("(.+?): <col=(.+?)>([0-9]+)</col>");

    private static final Pattern ITEM_NAME_PATTERN = Pattern.compile("<col=(.+?)>(.+?)</col>");

    @Override
    protected void startUp() throws Exception {
        System.out.println("HENTER BOSS OVERSIKT");

        Request request = new Request.Builder()
                .header("uuid", "d7d865c5-e37f-4228-a1c1-a5190f0f34cb")
                .url("http://runemanager-osrs.test/api/collection")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful())
            {
                log.debug("Error looking up boss overview: {}", response);
            }

            // Headers responseHeaders = response.headers();
            // for (int i = 0; i < responseHeaders.size(); i++) {
                // System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
            // }

            String collectionData = response.body().string();

            Gson gson = new Gson();
            JsonArray collectionOverview = gson.fromJson(collectionData, JsonArray.class);

            collections = gson.fromJson(collectionOverview, AvailableCollections[].class);
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (AvailableCollections availableCollections : collections) {
            System.out.println(availableCollections.getName());
        }
    }
 
    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived) throws IOException {
        final NPC npc = npcLootReceived.getNpc();

        // Find if NPC is available for data scraping
        for (AvailableCollections availableCollections : collections) {
            if (availableCollections.getName().equals(npc.getName().toLowerCase())) {
                final Collection<ItemStack> items = npcLootReceived.getItems();
                final String name = npc.getName();
                final int combat = npc.getCombatLevel();

                lootReceivedChatMessage(items, + ' ' + name);
                submitLoot(name, items);
            }
        }
    }

    private CollectionItem[] buildEntries(final Collection<ItemStack> itemStacks)
    {
        return itemStacks.stream()
                .map(itemStack -> buildLootTrackerItem(itemStack.getId(), itemStack.getQuantity()))
                .toArray(CollectionItem[]::new);
    }

    private CollectionItem buildLootTrackerItem(int itemId, int quantity)
    {
        final ItemComposition itemComposition = itemManager.getItemComposition(itemId);

        return new CollectionItem(
                itemId,
                itemComposition.getName(),
                quantity);
    }

    private static Collection<ItemStack> stack(Collection<ItemStack> items)
    {
        final List<ItemStack> list = new ArrayList<>();

        for (final ItemStack item : items)
        {
            int quantity = 0;
            for (final ItemStack i : list)
            {
                if (i.getId() == item.getId())
                {
                    quantity = i.getQuantity();
                    list.remove(i);
                    break;
                }
            }
            if (quantity > 0)
            {
                list.add(new ItemStack(item.getId(), item.getQuantity() + quantity, item.getLocation()));
            }
            else
            {
                list.add(item);
            }
        }

        return list;
    }

    public void submitLoot(String collectionName, final Collection<ItemStack> items) throws IOException {
        LinkedHashMap<String,Integer> loot = new LinkedHashMap<String, Integer>();

        final CollectionItem[] entries = buildEntries(stack(items));

        for (CollectionItem item : entries) {
            String itemName = item.getName();
            int itemQuantity = item.getQuantity();

            System.out.println("GAMMELT NAVN " + itemName);

            itemName = itemName.replace(" ", "_").replaceAll("[+.^:,']","").toLowerCase();

            System.out.println("NYTT NAVN " + itemName);

            loot.put(itemName, itemQuantity);
        }

        postCollectionLogData(collectionName, loot);
    }

    private void lootReceivedChatMessage(final Collection<ItemStack> items, final String name)
    {
        for (ItemStack item: items) {
            final String message = new ChatMessageBuilder()
                    .append(ChatColorType.HIGHLIGHT)
                    .append("You've killed ")
                    .append(name)
                    .append(" for ")
                    .append(item.toString())
                    .append(" loot.")
                    .build();

            chatMessageManager.queue(
                    QueuedMessage.builder()
                            .type(ChatMessageType.CONSOLE)
                            .runeLiteFormattedMessage(message)
                            .build());
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != 621) {
            collectionLogOpen = false;
            return;
        }

        collectionLogOpen = true;
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (collectionLogOpen) {
            int collectionLogValue = client.getVarbitValue(6906);
            if (collectionLogValue != previousCollectionLogValue) {
                getCurrentCollectionLogHeaderData();

                previousCollectionLogValue = collectionLogValue;
            }
        }
    }

    private void getCurrentCollectionLogHeaderData() {
        clientThread.invokeLater(() ->
        {
            // list - 621, 11

            final Widget collectionLogHeader = client.getWidget(621, 19); // Right widget header panel
            if (collectionLogHeader == null) {
                return;
            }

            final Widget[] header = collectionLogHeader.getDynamicChildren(); // 0 - Collection name, 1 - Uniques obtained, 2 - Killcount
            if (header == null) {
                return;
            }

            String collectionName = header[0].getText().replaceAll("[+.^:,']","");

            int uniquesObtained = 0;
            Matcher uniquesObtainedMatcher = UNIQUES_OBTAINED_PATTERN.matcher(header[1].getText());
            if (uniquesObtainedMatcher.find())
            {
                uniquesObtained = Integer.parseInt(uniquesObtainedMatcher.group(2));
            }

            int killCount = 0;
            Matcher killCountMatcher = KILL_COUNT_PATTERN.matcher(header[2].getText());
            if (killCountMatcher.find())
            {
                killCount = Integer.parseInt(killCountMatcher.group(3));
            }

            System.out.println("WIDGET BOSS: " + collectionName);

            try {
                getCollectionLogContentData(collectionName, uniquesObtained, killCount);
            } catch (IOException e) {
                e.printStackTrace();
            }

            //for (JsonElement boss : bossOverview) {
            //    System.out.println("API BOSS: " + boss.getAsJsonObject().get("boss").getAsString());
            //    System.out.println("UPDATED TIME: " + boss.getAsJsonObject().get("updated_at").getAsString());

            //    if (boss.getAsJsonObject().get("boss").getAsString().equals(bossName.toLowerCase())) {
            //        System.out.println("FANT BOSS");
            //       //if (true) {
            //            //getCollectionLogContent(bossName);
            //       //} else {
            //       //    System.out.println(bossName + " allerede scrapet!");
            //       //}
            //        break;
            //    }
            //}
        });
    }

    private void getCollectionLogContentData(String collectionName, int uniquesObtained, int killCount) throws IOException {
        //final Widget collectionLog = client.getWidget(WidgetInfo.COLLECTION_LOG_LOOT);
        final Widget collectionLog = client.getWidget(621, 35); // Right widget loot panel
        if (collectionLog == null) {
            return;
        }

        final Widget[] log = collectionLog.getDynamicChildren();
        if (log == null) {
            return;
        }

        LinkedHashMap<String,Integer> loot = new LinkedHashMap<String, Integer>();

        loot.put("obtained", uniquesObtained);
        loot.put("kill_count", killCount);

        for (Widget item : log) {
            String itemName = "";
            int itemQuantity = 0;

            Matcher itemNameMatcher = ITEM_NAME_PATTERN.matcher(item.getName());
            if (itemNameMatcher.find())
            {
                itemName = itemNameMatcher.group(2);
            }

            System.out.println("GAMMELT NAVN " + itemName);

            itemName = itemName.replace(" ", "_").replaceAll("[+.^:,']","").toLowerCase();

            System.out.println("NYTT NAVN " + itemName);

            if (item.getOpacity() == 0) {
                itemQuantity = item.getItemQuantity();
            }

            loot.put(itemName, itemQuantity);
        }

        postCollectionLogData(collectionName, loot);
    }

    private void postCollectionLogData(String collectionName, LinkedHashMap<String,Integer> loot) throws IOException {
        Gson gson = new Gson();
        String collectionJson = gson.toJson(loot);

        //makeModel(collectionName, loot);

        Request request = new Request.Builder()
                .header("uuid", "d7d865c5-e37f-4228-a1c1-a5190f0f34cb")
                .url("http://runemanager-osrs.test/api/boss/" + collectionName.toLowerCase())
                .put(RequestBody.create(MEDIA_TYPE_MARKDOWN, collectionJson))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            System.out.println(response.body().string());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void makeModel(String bossName, LinkedHashMap<String,Integer> loot) throws IOException {
        String fileName = RuneLite.RUNELITE_DIR + "\\model\\" + bossName.replace(" ", "") + ".php";
        purgeList(fileName);

        FileWriter writer = new FileWriter(fileName, true);

        writer.write("<?php\n");
        writer.write("\r\n");
        writer.write("namespace App\\Boss;\r\n");
        writer.write("\r\n");
        writer.write("use Illuminate\\Database\\Eloquent\\Model;\r\n");
        writer.write("\r\n");
        writer.write("class " + (bossName.substring(0, 1).toUpperCase() + bossName.substring(1)).replace(" ", "") + " extends Model\r\n");
        writer.write("{\r\n");
        writer.write("    protected $table = '" + bossName.toLowerCase().replaceAll(" ", "_") + "';\r\n");
        writer.write("\r\n");
        writer.write("    protected $fillable = [\r\n");
        //writer.write("        'obtained',\r\n");
        //writer.write("        'kill_count',\r\n");
        for (HashMap.Entry me : loot.entrySet()) {
            System.out.println("Key: "+me.getKey() + " & Value: " + me.getValue());
            String key = me.getKey().toString();
            writer.write("        '" + key + "',\r\n");
        }
        writer.write("    ];\r\n");
        writer.write("\r\n");
        writer.write("    protected $hidden = ['user_id'];\n");
        writer.write("}\r\n");

        writer.close();

        makeMigration(bossName.replace(" ", ""), loot);
    }

    private void makeMigration(String bossName, LinkedHashMap<String,Integer> loot) throws IOException {
        String fileName = RuneLite.RUNELITE_DIR + "\\migration\\" + bossName + " migration.php";
        purgeList(fileName);

        FileWriter writer = new FileWriter(fileName, true);

        writer.write("$table->id();\r\n");
        writer.write("$table->integer('user_id')->unsigned()->unique();\r\n");
        //writer.write("$table->integer('obtained')->default(0)->unsigned();\r\n");
        //writer.write("$table->integer('kill_count')->default(0)->unsigned();\r\n");
        for (HashMap.Entry me : loot.entrySet()) {
            System.out.println("Key: "+me.getKey() + " & Value: " + me.getValue());
            String key = me.getKey().toString();
            writer.write("$table->integer('"+key+"')->default(0)->unsigned();" + "\r\n");
        }
        writer.write("$table->timestamps();");

        writer.close();
    }

    private void purgeList(String fileName) {
        File purge = new File(fileName);
        purge.delete();
    }
}
