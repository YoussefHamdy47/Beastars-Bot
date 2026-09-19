package org.bunnys;

import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.utils.TokenLoader;

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
                // when emoji are added or renamed in the Developer Portal - it rewrites
                // the constants in AppDesign.java so the source stays in step.
                .setReloadEmojis(false)

                // Identities and secrets all come from .env, so this file carries none
                // of them and the repository can be published as it stands. Every key
                // below is documented in .env.example.
                .addTestServerIds(TokenLoader.list("TEST_SERVERS").toArray(String[]::new))
                .addDeveloperIds(TokenLoader.list("DEVELOPERS").toArray(String[]::new))

                // Shown on /uptime and on the bot's own /info user card. Leave
                // DEVELOPER_NAME unset in .env to remove the credit entirely.
                .setDeveloperCredit(TokenLoader.optional("DEVELOPER_NAME"), TokenLoader.optional("DEVELOPER_URL"))

                .setTokenKey("TOKEN")
                .setDatabaseName(TokenLoader.orDefault("DATABASE_NAME", "beastars"))
                .setMongoUriKey("MongoURI")

                // Concurrent commands, then how many may wait before the bot starts
                // refusing. Raise the first if /admin errorlog health ever reports a
                // rejection; the Mongo connection pool follows it automatically.
                .setCommandPool(24, 100)
                // GUILD_MESSAGES is required and is not privileged. Without it the
                // gateway never sends MESSAGE_CREATE, so @mention commands silently do
                // nothing while slash commands carry on working - a failure with no
                // symptom except the thing not happening.
                //
                // MESSAGE_CONTENT is deliberately absent: Discord delivers content for
                // messages that mention the bot without the privileged intent.
                //
                // GUILD_MEMBERS is switched on from .env, NOT hardcoded. It is
                // privileged, and requesting one Discord has not granted closes the
                // gateway outright (4014) - the bot would not start at all. The
                // leaderboard's hidden roles, hide-departed and role filter stay dormant
                // until MEMBER_INTENT=true, at which point they light up on the next
                // restart with no rebuild.
                .addIntents(intents())
                .build();

    }

    /**
     * The gateway intents to request.
     *
     * <p>Kept out of the builder chain because the privileged one is conditional: see
     * the note at the call site. Defaults to off, so a fresh checkout always boots.
     */
    private static GatewayIntent[] intents() {
        if (TokenLoader.flag("MEMBER_INTENT", false))
            return new GatewayIntent[]{GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS};

        return new GatewayIntent[]{GatewayIntent.GUILD_MESSAGES};
    }
}