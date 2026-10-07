package top.vulpine.catalog.velocity.config;

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
        @Comment("and allows private projects to be read.")
        @CustomKey("token")
        public String token = "";

    }

    @CustomKey("tracking")
    public Tracking tracking = new Tracking();

    public static class Tracking extends OkaeriConfig {

        @Comment("Recognise and start tracking plugins automatically on startup.")
        @Comment("Disabling this stops new jars being adopted.")
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

            @Comment("Whether Catalog installs updates on its own. Off by default.")
            @CustomKey("auto_update")
            public boolean autoUpdate = false;

            @Comment("How long to wait after a version is published before installing it")
            @Comment("automatically. Avoids installing a release the author hotfixes shortly after.")
            @CustomKey("soak_minutes")
            public int soakMinutes = 120;

        }

    }

    @CustomKey("updates")
    public Updates updates = new Updates();

    public static class Updates extends OkaeriConfig {

        @Comment("How often to check for updates, in minutes.")
        @Comment("Set to 0 to only check when the proxy starts.")
        @CustomKey("check_interval_minutes")
        public int checkIntervalMinutes = 180;

    }

    @CustomKey("trash")
    public Trash trash = new Trash();

    public static class Trash extends OkaeriConfig {

        @Comment("How long a removed plugin stays in the trash before it is deleted.")
        @Comment("Old removals are cleared out when the proxy starts. Set to 0 to keep them forever.")
        @CustomKey("retention_days")
        public int retentionDays = 30;

    }

    @Comment("Log level for this plugin. Can be: DEBUG, INFO, WARN, ERROR.")
    @Comment("Leave as it is if you don't know what to choose.")
    @CustomKey("log_level")
    public LogLevel logLevel = LogLevel.INFO;

}
