package org.bunnys;

import org.bunnys.handler.BunnyHub;

import net.dv8tion.jda.api.requests.GatewayIntent;

public class Main {
    public static void main(String[] args) {

        @SuppressWarnings("unused")
        BunnyHub client = BunnyHub.create()
                .setEventPackage("org.bunnys.events")
                .setCommandPackage("org.bunnys.commands")
                .setButtonPackage("org.bunnys.buttons")
                .setModalPackage("org.bunnys.modals")
                .setSelectPackage("org.bunnys.selects")
                .setLogActions(true)
                .setAutoLogin(true)
                // Switching between the test and production bots needs nothing here:
                // EmojiRegistry re-points every emoji constant at whichever application
                // is running, by name, on every boot. Set this true for ONE boot only
                // when emoji are added or renamed in the Developer Portal — it rewrites
                // the constants in AppDesign.java so the source stays in step.
                .setReloadEmojis(false)
                .addTestServerIds("1187559385359200316", "1131194563256664176")
                .addDeveloperIds("333644367539470337")
                // Shown on /uptime. Set the name to null to remove the credit entirely.
                .setDeveloperCredit(".Bunnys", "https://github.com/YoussefHamdy47")
                .setTokenKey("TOKEN")

                // MongoDB. Change these two when the client provides their cluster —
                // the database name is theirs to choose, and the URI key is whatever
                // the .env entry ends up being called. Nothing else needs editing.
                .setDatabaseName("GBF")
                .setMongoUriKey("MongoURI")

                // Concurrent commands, then how many may wait before the bot starts
                // refusing. Raise the first if /admin errorlog health ever reports a
                // rejection; the Mongo connection pool follows it automatically.
                .setCommandPool(24, 100)
                // GUILD_MESSAGES is REQUIRED and is not privileged. Without it the
                // gateway never sends MESSAGE_CREATE, so onMessageReceived never fires
                // and @mention commands silently do nothing. Slash commands still work,
                // because interactions arrive over HTTP and need no intent at all —
                // which is exactly what makes that failure so quiet.
                //
                // MESSAGE_CONTENT is deliberately absent: Discord delivers content for
                // messages that mention the bot without the privileged intent.
                //
                // GUILD_MEMBERS is also absent. Nothing here needs it any more — member
                // objects for commands arrive inside the interaction/message payload,
                // and the only other caller is ClientReady's member-count log, which
                // falls back to the guild's approximate count. Add it back only if you
                // start needing full member lists.
                .addIntents(GatewayIntent.GUILD_MESSAGES)
                .build();

    }
}
