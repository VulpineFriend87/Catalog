package top.vulpine.catalog.paper.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.CustomKey;
import eu.okaeri.configs.annotation.Header;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;
import top.vulpine.commons.log.LogLevel;

@Header("Catalog Configuration - By Vulpine (https://vulpine.top)")
@Header("")
@Header("Catalog manages plugins published on Modrinth. It identifies every jar by its")
@Header("hash, so it always knows exactly what you have installed. Anything it does not")
@Header("recognise is left completely alone.")
@Header("")
public class Config extends OkaeriConfig {

    @CustomKey("modrinth")
    public Modrinth modrinth = new Modrinth();

    public static class Modrinth extends OkaeriConfig {

        @Comment("A Modrinth personal access token. Optional: it raises the request limit")
        @Comment("and allows private projects to be read. Leave empty to stay anonymous.")
        @CustomKey("token")
        public String token = "";

    }

    @CustomKey("tracking")
    public Tracking tracking = new Tracking();

    public static class Tracking extends OkaeriConfig {

        @Comment("Recognise and start tracking plugins automatically on startup.")
        @Comment("Disabling this does not untrack anything; it only stops new jars being adopted.")
        @CustomKey("auto_track")
        public boolean autoTrack = true;

        @Comment("Applied to a plugin when it is first adopted or installed. Changing these")
        @Comment("later does not affect plugins that are already tracked, only new ones.")
        @CustomKey("defaults")
        public Defaults defaults = new Defaults();

        public static class Defaults extends OkaeriConfig {

            @Comment("Which builds to consider: RELEASE, BETA or ALPHA.")
            @Comment("Channels are cumulative, so BETA also accepts releases.")
            @CustomKey("channel")
            public ReleaseChannel channel = ReleaseChannel.RELEASE;

            @Comment("Whether Catalog installs updates on its own. Off by default: adopting")
            @Comment("your whole plugins folder and then queueing updates nobody asked for")
            @Comment("is exactly the behaviour this plugin exists to avoid.")
            @CustomKey("auto_update")
            public boolean autoUpdate = false;

            @Comment("How long to wait after a version is published before installing it")
            @Comment("automatically. Avoids picking up a release the author hotfixes an hour later.")
            @CustomKey("soak_minutes")
            public int soakMinutes = 120;

        }

    }

    @CustomKey("updates")
    public Updates updates = new Updates();

    public static class Updates extends OkaeriConfig {

        @Comment("How often to ask Modrinth whether anything is out of date, in minutes.")
        @Comment("Set to 0 to only check when the server starts.")
        @CustomKey("check_interval_minutes")
        public int checkIntervalMinutes = 180;

    }

    @Comment("Allow installing builds this server is not declared compatible with.")
    @Comment("With this on, a plugin's version screen gains a button listing every build ever")
    @Comment("published, and any of them can be installed. They are not filtered by Minecraft")
    @Comment("version or by loader, so most of them will fail to load. Off unless you know")
    @Comment("exactly why you want it.")
    @CustomKey("allow_incompatible_installs")
    public boolean allowIncompatibleInstalls = false;

    @Comment("Log level for the plugin. Can be: DEBUG, INFO, WARN, ERROR.")
    @Comment("Leave as it is if you don't know what to choose.")
    @CustomKey("log_level")
    public LogLevel logLevel = LogLevel.INFO;

}
